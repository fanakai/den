package ai.fanak.den

import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.GgufHeader
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.RemoteFile
import ai.fanak.den.core.model.Variant
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// A GGUF file the user picked (Storage Access Framework): used where it is, never copied.
@Serializable
data class LocalFile(
    // The model id apps use: file:<name without .gguf>.
    val id: String,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val quant: String,
    val arch: String? = null,
    val params: Long? = null,
    val maxContext: Int? = null,
    val kvBytesPerToken: Long? = null,
) {
    // minRamMb by the hub catalogs' rough rule; the router uses the KV cache size from the header when it has it.
    val variant get() = Variant(
        id, ModelFormat.GGUF, quant, sizeBytes, ((sizeBytes shr 20) * 5 / 4).toInt() + 512,
        arch = arch, params = params, maxContext = maxContext, kvBytesPerToken = kvBytesPerToken,
    )
}

// The files the user added, kept in this app's preferences, with the read permission Android keeps for each.
internal class LocalFiles(context: Context) : ModelCatalog {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("den_local_files", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<LocalFile> =
        runCatching { json.decodeFromString(ListSerializer(LocalFile.serializer()), prefs.getString("files", "[]")!!) }.getOrDefault(emptyList())

    private fun save(files: List<LocalFile>) = prefs.edit().putString("files", json.encodeToString(ListSerializer(LocalFile.serializer()), files)).apply()

    /**
     * Adds a picked document: keeps its read permission across restarts, hashes it once and reads its GGUF header.
     * Slow for a large file (it is read once in full), so call it off the main thread.
     */
    fun add(uri: Uri): LocalFile {
        val cr = context.contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "model.gguf"
        val header = cr.openInputStream(uri)!!.use { GgufHeader.read(it) }
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        cr.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        val base = name.removeSuffix(".gguf").removeSuffix(".GGUF")
        val quant = QUANT.find(name)?.groupValues?.get(1)?.lowercase() ?: "unknown"
        val file = LocalFile(
            "file:$base", uri.toString(), name, size, md.digest().joinToString("") { "%02x".format(it) }, quant,
            header.arch, header.params, header.contextLength, header.kvBytesPerToken,
        )
        save(all().filter { it.id != file.id } + file)
        return file
    }

    fun remove(id: String) {
        val f = all().firstOrNull { it.id == id } ?: return
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        save(all().filter { it.id != id })
    }

    override val models get() = all().map { it.id }

    override fun variants(model: String) = all().filter { it.id == model }.map { it.variant }

    override fun remote(variant: Variant) =
        all().firstOrNull { it.variant == variant }?.let { RemoteFile(it.name, it.uri, it.sha256, it.sizeBytes, it.uri) }

    // On disk already: the document itself.
    fun artifact(variant: Variant): Artifact? = all().firstOrNull { it.variant == variant }?.let { Artifact(variant, it.uri) }

    private companion object {
        val QUANT = Regex("""[-_.]((?:UD-)?(?:I?Q\d[A-Z0-9_]*|BF16|F16|F32))\.gguf$""", RegexOption.IGNORE_CASE)
    }
}

// Local files first, without a download; everything else from [inner].
internal class LocalFirstStore(private val local: LocalFiles, private val inner: ModelStore) : ModelStore {
    override suspend fun fetch(variant: Variant) = local.artifact(variant) ?: inner.fetch(variant)

    override fun has(variant: Variant) = local.artifact(variant) != null || inner.has(variant)

    override fun freeBytes() = inner.freeBytes()
}
