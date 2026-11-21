package ai.fanak.den.core.router

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

