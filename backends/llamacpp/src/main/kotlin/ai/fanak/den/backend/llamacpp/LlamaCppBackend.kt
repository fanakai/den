package ai.fanak.den.backend.llamacpp

import ai.fanak.den.core.backend.BackendDescriptor
import ai.fanak.den.core.backend.BackendMode
import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Features
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.backend.SupportTier
import ai.fanak.den.core.backend.UnsupportedFeatureException
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ABOUT = BackendDescriptor(
    id = "llamacpp",
    name = "llama.cpp",
    version = "",
    author = "The ggml authors; Android build by Fanak Den",
    licence = "MIT",
    url = "https://github.com/ggml-org/llama.cpp",
    tier = SupportTier.CORE,
    modes = setOf(BackendMode.CHAT),
    formats = setOf(ModelFormat.GGUF),
    features = setOf(Features.GRAMMAR, Features.JSON_SCHEMA),
)

class LlamaCppBackend : DenBackend {
    override val id = "llamacpp"

    // ggml version and llama.cpp commit, e.g. "0.14.0-a1b2c3d".
    override val version by lazy { LlamaNative.version() }

    override val about get() = ABOUT

    @Volatile
    private var lastError: String? = null

    // GPU is reported only when the OpenCL backend loaded and found a device (Adreno 7xx and newer).
    private val hasGpu by lazy {
        runCatching { LlamaNative.devices().any { it.endsWith("\tgpu") } }.getOrDefault(false)
    }

    override fun probe(device: DeviceProfile): Capabilities {
        if (device.platform != Platform.ANDROID || device.abi != "arm64-v8a") return Capabilities.NONE
        val accels = if (hasGpu) setOf(Accel.CPU, Accel.GPU) else setOf(Accel.CPU)
        return Capabilities(accels, setOf(ModelFormat.GGUF), ARCHITECTURES)
    }

    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
        require(opts.accel == Accel.CPU || opts.accel == Accel.GPU) { "llamacpp: ${opts.accel} not supported" }
        val gpuLayers = if (opts.accel == Accel.GPU) 999 else 0
        // 0 = native picks from the cores Android allows right now; availableProcessors() ignores that.
        val threads = opts.threads ?: 0
        val handle = withContext(Dispatchers.IO) {
            try {
                LlamaNative.load(artifact.path, opts.contextLength, threads, gpuLayers, opts.mmap, opts.sequences.coerceAtLeast(1)).also { h ->
                    // Warm-up with a prompt as long as a real one: a 1-token "hi" left the first request at
                    // 3.8 tok/s prefill on the A52s, against ~75 for the next ones.
                    LlamaNative.generate(h, arrayOf("user"), arrayOf(WARMUP.encodeToByteArray()), 2, 0f, 1f, emptyArray(), false, 0, emptyArray()) { true }
                }
            } catch (e: EngineException) {
                lastError = e.message
                throw e
            }
        }
        lastError = null
        return LlamaSession(handle)
    }

    override fun health(): Health = lastError?.let { Health.Degraded(it) } ?: Health.Ok
}

private class LlamaSession(@Volatile private var handle: Long) : Session {
    // Requests hold the read lock while they generate, together; unload takes the write lock and waits for them.
    private val lock = ReentrantReadWriteLock()

    // Tool calls need the model's own chat template with tools, which this engine does not apply yet.
    override fun generate(req: GenRequest): Flow<GenEvent> =
        if (req.tools.isNotEmpty()) flow { throw UnsupportedFeatureException("tools", "llama.cpp") } else text(req)

    override suspend fun embed(texts: List<String>): List<FloatArray> = throw UnsupportedFeatureException("embeddings", "llama.cpp")

    private fun text(req: GenRequest): Flow<GenEvent> = callbackFlow {
        launch(Dispatchers.IO) {
            try {
                val s = lock.read {
                    check(handle != 0L) { "session unloaded" }
                    LlamaNative.generate(
                        handle,
                        req.messages.map { it.role.name.lowercase() }.toTypedArray(),
                        req.messages.map { it.content.encodeToByteArray() }.toTypedArray(),
                        req.maxTokens,
                        req.temperature,
                        req.topP,
                        req.stop.map { it.encodeToByteArray() }.toTypedArray(),
                        req.cache,
                        req.threads ?: 0,
                        arrayOf((req.grammar ?: "").encodeToByteArray(), (req.jsonSchema ?: "").encodeToByteArray()),
                    ) { utf8 ->
                        trySendBlocking(GenEvent.Text(utf8.decodeToString())).isSuccess && isActive
                    }
                }
                send(GenEvent.Done(GenStats.of(s)))
                close()
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose()
    }

    override fun fork(): Session? = lock.read {
        if (handle == 0L) return null
        runCatching { LlamaSession(LlamaNative.fork(handle)) }.getOrNull()
    }

    override fun unload() = lock.write {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }
}

private const val WARMUP =
    "The fennec fox lives in the Sahara and digs dens in the sand to escape the heat of the day. " +
        "It hunts at night for insects, small rodents and birds, and gets most of its water from food."
