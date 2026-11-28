package ai.fanak.den.backend.litertlm

import ai.fanak.den.core.backend.BackendDescriptor
import ai.fanak.den.core.backend.BackendMode
import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.backend.SupportTier
import ai.fanak.den.core.backend.UnsupportedFeatureException
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val ABOUT = BackendDescriptor(
    id = "litertlm",
    name = "LiteRT-LM",
    version = "",
    author = "Google; Android backend by Fanak Den",
    licence = "Apache-2.0",
    url = "https://github.com/google-ai-edge/LiteRT-LM",
    tier = SupportTier.CORE,
    modes = setOf(BackendMode.CHAT),
    formats = setOf(ModelFormat.LITERTLM),
)

/**
 * `.litertlm` files on the CPU (XNNPACK) and the GPU (OpenCL). The NPU path needs Qualcomm's QAIRT libraries, whose
 * licence is not settled (#56), so it is not offered. [context] gives the folder where the GPU keeps its compiled
 * programs, which makes the second load faster.
 */
class LiteRtLmBackend(private val context: Context?) : DenBackend {
    constructor() : this(null)

    override val id = "litertlm"

    override val version: String get() = BuildConfig.LITERTLM_VERSION

    override val about get() = ABOUT

    @Volatile
    private var lastError: String? = null

    override fun probe(device: DeviceProfile): Capabilities {
        if (device.platform != Platform.ANDROID || device.abi != "arm64-v8a") return Capabilities.NONE
        val accels = if (device.openCl) setOf(Accel.CPU, Accel.GPU) else setOf(Accel.CPU)
        return Capabilities(accels, setOf(ModelFormat.LITERTLM))
    }

    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
        val backend = when (opts.accel) {
            Accel.CPU -> Backend.CPU(threadCount = opts.threads)
            Accel.GPU -> Backend.GPU()
            Accel.NPU -> throw IllegalArgumentException("litertlm: NPU not supported")
        }
        val cache = context?.let { File(it.cacheDir, "litertlm").apply { mkdirs() }.path }
        val engine = withContext(Dispatchers.IO) {
            try {
                // Without it, an engine reports no token counts or speeds; read when the engine is created.
                @OptIn(ExperimentalApi::class)
                ExperimentalFlags.enableBenchmark = true
                Engine(EngineConfig(artifact.path, backend, maxNumTokens = opts.contextLength, cacheDir = cache)).apply { initialize() }
            } catch (e: Exception) {
                lastError = e.message
                throw e
            }
        }
        lastError = null
        return LiteRtLmSession(engine)
    }

    override fun health(): Health = lastError?.let { Health.Degraded(it) } ?: Health.Ok
}

private class LiteRtLmSession(private val engine: Engine) : Session {
    // One conversation at a time on an engine: requests wait for each other.
    private val busy = Mutex()

    override fun generate(req: GenRequest): Flow<GenEvent> = flow {
        if (req.tools.isNotEmpty()) throw UnsupportedFeatureException("tools", "LiteRT-LM")
        if (req.grammar != null || req.jsonSchema != null) throw UnsupportedFeatureException("grammars", "LiteRT-LM")
        busy.withLock {
            val turns = Turns.of(req.messages)
            val config = ConversationConfig(
                systemInstruction = turns.system?.let { Contents.of(it) },
                initialMessages = turns.history.map { if (it.role == Role.ASSISTANT) Message.model(it.content) else Message.user(it.content) },
                samplerConfig = sampler(req),
            )
            engine.createConversation(config).use { conversation ->
                val stop = StopScan(req.stop)
                val t0 = System.nanoTime()
                var first = 0L
                try {
                    conversation.sendMessageAsync(turns.last, maxOutputToken = req.maxTokens).collect { m ->
                        if (first == 0L) first = System.nanoTime()
                        stop.add(m.toString())?.let { emit(GenEvent.Text(it)) }
                        if (stop.hit) conversation.cancelProcess()
                    }
                } catch (e: Exception) {
                    // Cancelled at a stop string: the reply up to it is complete.
                    if (!stop.hit) throw e
                }
                val end = System.nanoTime()
                stop.rest()?.let { emit(GenEvent.Text(it)) }
                // Output tokens and decode speed from LiteRT-LM's benchmark info (experimental). Its prefill count covers
                // only the last piece it prefilled, so the prompt is the KV cache's tokens less the output, and the
                // prefill time is the time to the first piece.
                @OptIn(ExperimentalApi::class)
                val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                val total = runCatching { conversation.getTokenCount() }.getOrDefault(0)
                val output = info?.lastDecodeTokenCount ?: 0
                val decodeTps = info?.lastDecodeTokensPerSecond ?: 0.0
                val stats = Stats.of(
                    prompt = (total - output).coerceAtLeast(0),
                    output = output,
                    prefillMs = if (first > 0) (first - t0) / 1_000_000 else 0,
                    decodeMs = if (decodeTps > 0) (output * 1000.0 / decodeTps).toLong() else if (first > 0) (end - first) / 1_000_000 else 0,
                    maxTokens = req.maxTokens,
                    stopped = stop.hit,
                )
                emit(GenEvent.Done(stats))
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun unload() = engine.close()
}

private fun sampler(req: GenRequest) = Sampling.of(req).let { SamplerConfig(it.topK, it.topP, it.temperature) }

// A request as LiteRT-LM takes it: the system message, the earlier turns, and the last user message, which is sent.
internal data class Turns(val system: String?, val history: List<ChatMessage>, val last: String) {
    companion object {
        fun of(messages: List<ChatMessage>): Turns {
            val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }.ifEmpty { null }
            val rest = messages.filter { it.role != Role.SYSTEM }
            require(rest.isNotEmpty() && rest.last().role == Role.USER) { "litertlm: the last message must be the user's" }
            return Turns(system, rest.dropLast(1), rest.last().content)
        }
    }
}
