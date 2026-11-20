package ai.fanak.den

import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.Variant
import java.io.File

// A model file on disk: complete (verified or not yet), or a download that has not finished.
data class CachedFile(
    val fileName: String,
    val model: String,
    val quant: String,
    val bytes: Long,
    val totalBytes: Long,
    val complete: Boolean,
)

/** The model files in one folder, as known to the catalog. Files the catalog does not know are left alone. */
class ModelFiles(private val dir: File, private val catalog: ModelCatalog = BuiltinCatalog) {
    private class Entry(val variant: Variant, val fileName: String)

    private val entries: List<Entry>
        get() = catalog.models.flatMap { catalog.variants(it) }.mapNotNull { v -> catalog.remote(v)?.let { Entry(v, it.fileName) } }

    fun list(): List<CachedFile> = entries.mapNotNull { e ->
        val f = File(dir, e.fileName)
        val part = File(dir, e.fileName + ".part")
        when {
            f.isFile -> CachedFile(e.fileName, e.variant.model, e.variant.quant, f.length(), e.variant.sizeBytes, true)
            part.isFile -> CachedFile(e.fileName, e.variant.model, e.variant.quant, part.length(), e.variant.sizeBytes, false)
            else -> null
        }
    }

    val usedBytes: Long get() = list().sumOf { it.bytes }

    val freeBytes: Long get() = dir.usableSpace

    // Every file of the model (all quants), with its checksum marker and any partial download. Returns the bytes freed.
    fun delete(model: String): Long = entries.filter { it.variant.model == model }.sumOf { remove(it.fileName) }

    // Unfinished downloads, except the files named in `keep` (downloads in progress).
    fun deletePartial(keep: Set<String> = emptySet()): Long =
        entries.filter { it.fileName !in keep && File(dir, it.fileName + ".part").isFile }
            .sumOf { e -> File(dir, e.fileName + ".part").let { p -> p.length().also { p.delete() } } }

    private fun remove(fileName: String): Long =
        listOf(fileName, "$fileName.sha256", "$fileName.part").sumOf { name ->
            val f = File(dir, name)
            if (f.isFile) f.length().also { f.delete() } else 0L
        }
}
