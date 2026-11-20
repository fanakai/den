package ai.fanak.den.core.router

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.ModelFormat
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// The engine process went away and took its sessions with it; reopen the model. Not the candidate's fault, e.g. the
// system stopped the process to free memory.
open class EngineLostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// The engine process died while this candidate loaded or generated: the router blocks it on this device.
class EngineCrashedException(message: String, cause: Throwable? = null) : EngineLostException(message, cause)

@Serializable
data class FailureKey(
    val soc: String,
    val backend: String,
    val accel: Accel,
    val model: String,
    val format: ModelFormat,
    val quant: String,
    // A new backend version tests the path again.
    val backendVersion: String = "",
)

// Remembers candidates that crashed their engine on this device, so the router skips them.
interface FailureStore {
    fun isBlocked(key: FailureKey): Boolean
    fun record(key: FailureKey, error: Throwable)
}

class InMemoryFailureStore : FailureStore {
    private val blocked = mutableSetOf<FailureKey>()

    @Synchronized
    override fun isBlocked(key: FailureKey) = key in blocked

    @Synchronized
    override fun record(key: FailureKey, error: Throwable) {
        if (error is EngineCrashedException) blocked += key
    }
}

/**
 * Crashed candidates saved in [file], so a restart does not crash again on the same path. [env] names the system the
 * crashes happened on (on Android the build fingerprint): after a system or driver update every path is tried again.
 */
class FileFailureStore(private val file: File, private val env: String) : FailureStore {
    @Serializable
    private data class Entry(val env: String, val key: FailureKey, val error: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val entries = load().toMutableList()

    @Synchronized
    override fun isBlocked(key: FailureKey) = entries.any { it.key == key }

    @Synchronized
    override fun record(key: FailureKey, error: Throwable) {
        if (error !is EngineCrashedException || isBlocked(key)) return
        entries += Entry(env, key, error.message ?: error.toString())
        save()
    }

    private fun load(): List<Entry> = runCatching {
        json.decodeFromString(ListSerializer(Entry.serializer()), file.readText()).filter { it.env == env }
    }.getOrDefault(emptyList())

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
