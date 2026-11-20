package ai.fanak.den.core.model

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// One downloaded (or downloading) file. `path` is relative to the model folder, e.g. hf/<owner>/<repo>/<file>.
@Serializable
data class ManifestEntry(
    val model: String,
    val format: ModelFormat,
    val quant: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val url: String,
    val complete: Boolean,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * What is on disk, in `manifest.json` in the model folder. Disk management reads it instead of asking a catalog,
 * so it costs O(downloaded files) and works offline, however large the catalogs are.
 */
class ModelManifest(private val dir: File) {
    private val file = File(dir, "manifest.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(ManifestEntry.serializer())

    fun entries(): List<ManifestEntry> = synchronized(LOCK) { read() }

    fun put(entry: ManifestEntry) = synchronized(LOCK) { write(read().filter { it.path != entry.path } + entry) }

    fun remove(path: String) = synchronized(LOCK) { write(read().filter { it.path != path }) }

    private fun read(): List<ManifestEntry> =
        if (file.isFile) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()) else emptyList()

    // Written to a temporary file first, so a crash never leaves half a manifest.
    private fun write(entries: List<ManifestEntry>) {
        dir.mkdirs()
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(json.encodeToString(serializer, entries))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        // One lock for every manifest in the process: the SDK and the Den app may open the same folder twice.
        val LOCK = Any()
    }
}
