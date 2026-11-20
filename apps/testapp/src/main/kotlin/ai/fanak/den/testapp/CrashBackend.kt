package ai.fanak.den.testapp

import ai.fanak.den.backend.llamacpp.LlamaCppBackend
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.flow

/**
 * llama.cpp that crashes its process on the quant named in <filesDir>/crash, "load q4_k_m" or "gen q4_k_m", as a GPU
 * driver abort would. Created again in the engine process, which reads the same file.
 */
class CrashBackend(context: Context) : DenBackend {
    private val llama = LlamaCppBackend()
    private val marker = File(context.filesDir, "crash")
    override val id = "crash"
    override val version get() = llama.version

    override fun probe(device: DeviceProfile) = llama.probe(device)

    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
        if (armed("load", artifact)) crash()
        val s = llama.load(artifact, opts)
        if (!armed("gen", artifact)) return s
        return object : Session {
            override fun generate(req: GenRequest) = flow<Nothing> { crash() }

            override fun unload() = s.unload()
        }
    }

    override fun health() = llama.health()

    private fun armed(stage: String, a: Artifact) = runCatching { marker.readText().trim() }.getOrNull() == "$stage ${a.variant.quant}"

    // An uncaught exception on another thread ends the process, as a crash in native code does.
    private fun crash(): Nothing {
        Thread { throw IllegalStateException("forced engine crash") }.start()
        Thread.sleep(10_000)
        error("still alive")
    }
}

// The test app's extra "crash": the marker for CrashBackend ("load q4_k_m", "gen q4_k_m", "off"); "reset" also
// forgets recorded crashes.
fun Context.armCrash(value: String) {
    val marker = File(filesDir, "crash")
    if (value == "off" || value == "reset") marker.delete() else marker.writeText(value)
    if (value == "reset") File(filesDir, "den/failures.json").delete()
}
