package ai.fanak.den.backend.llamacpp

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlamaCppBackend : DenBackend {
    override val id = "llamacpp"

    @Volatile
    private var lastError: String? = null

    // GPU paths come with #4; CPU only for now.
    override fun probe(device: DeviceProfile): Capabilities =
        if (device.platform == Platform.ANDROID && device.abi == "arm64-v8a") {
            Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF))
        } else {
            Capabilities.NONE
        }

    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
        require(opts.accel == Accel.CPU) { "llamacpp: ${opts.accel} not supported yet" }
        val threads = opts.threads ?: Runtime.getRuntime().availableProcessors()
        val handle = withContext(Dispatchers.IO) {
            try {
                LlamaNative.load(artifact.path, opts.contextLength, threads, 0)
            } catch (e: EngineException) {
                lastError = e.message
                throw e
            }
        }
        return LlamaSession(handle)
    }

    override fun health(): Health = lastError?.let { Health.Degraded(it) } ?: Health.Ok
}

private class LlamaSession(private var handle: Long) : Session {
    private val lock = Any()

    override fun generate(req: GenRequest): Flow<GenEvent> = callbackFlow {
        launch(Dispatchers.IO) {
            try {
                val s = synchronized(lock) {
                    check(handle != 0L) { "session unloaded" }
                    LlamaNative.generate(
                        handle,
                        req.messages.map { it.role.name.lowercase() }.toTypedArray(),
                        req.messages.map { it.content }.toTypedArray(),
                        req.maxTokens,
                        req.temperature,
                        req.topP,
                        req.stop.toTypedArray(),
                    ) { utf8 ->
                        trySendBlocking(GenEvent.Text(utf8.decodeToString())).isSuccess && isActive
                    }
                }
                send(GenEvent.Done(GenStats(s[0].toInt(), s[1].toInt(), s[2], s[3])))
                close()
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose()
    }

    override fun unload() = synchronized(lock) {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }
}
