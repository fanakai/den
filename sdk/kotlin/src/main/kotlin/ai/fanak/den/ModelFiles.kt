package ai.fanak.den

import ai.fanak.den.core.model.ManifestEntry
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelManifest
import java.io.File

// A model file on disk: complete, or a download that has not finished.
data class CachedFile(
    val fileName: String,
    val model: String,
    val quant: String,
    val bytes: Long,
    val totalBytes: Long,
    val complete: Boolean,
    // Relative to the model folder, e.g. hf/unsloth/Qwen3-0.6B-GGUF/Qwen3-0.6B-Q4_0.gguf.
    val path: String = fileName,
)

/**
 * The model files in one folder, read from its manifest, so it never asks a catalog what exists.
 * `legacy` is the catalog whose files may still sit directly in the folder, from before the manifest.
 */
class ModelFiles(private val dir: File, private val legacy: ModelCatalog = BuiltinCatalog) {
    private val manifest = ModelManifest(dir)

    init {
        adoptLegacy()
    }

    fun list(): List<CachedFile> = manifest.entries().mapNotNull { e ->
        val f = File(dir, e.path)
        val part = File(dir, e.path + ".part")
        when {
            e.complete && f.isFile -> e.cached(f.length(), true)
            part.isFile -> e.cached(part.length(), false)
            // Not verified yet (an adopted file of the wrong size): shown, so it can be removed.
            f.isFile -> e.cached(f.length(), false)
            else -> null
        }
    }

    val usedBytes: Long get() = list().sumOf { it.bytes }

    val freeBytes: Long get() = dir.usableSpace

    // Every file of the model (all quants), with its checksum marker and any partial download. Returns the bytes freed.
    fun delete(model: String): Long = manifest.entries().filter { it.model == model }.sumOf { remove(it) }

    // Unfinished downloads, except the files named in `keep` (downloads in progress, by file name).
    fun deletePartial(keep: Set<String> = emptySet()): Long =
        manifest.entries().filter { !it.complete && it.fileName !in keep }.sumOf { remove(it) }

    private fun ManifestEntry.cached(bytes: Long, complete: Boolean) =
        CachedFile(fileName, model, quant, bytes, sizeBytes, complete, path)

    private fun remove(e: ManifestEntry): Long {
        val freed = listOf("", ".sha256", ".part").sumOf { ext ->
            val f = File(dir, e.path + ext)
            if (f.isFile) f.length().also { f.delete() } else 0L
        }
        manifest.remove(e.path)
        // Empty source and repository folders go too.
        var parent = File(dir, e.path).parentFile
        while (parent != null && parent != dir && parent.list()?.isEmpty() == true) {
            parent.delete()
            parent = parent.parentFile
        }
        return freed
    }

    // Moves files stored under their bare name (before paths and the manifest) to their path, and records them.
    // The store still checks size and checksum before it uses a file.
    private fun adoptLegacy() {
        for (model in legacy.models) for (v in legacy.variants(model)) {
            val r = legacy.remote(v) ?: continue
            if (r.path == r.fileName) continue
            if (!File(dir, r.fileName).isFile && !File(dir, r.fileName + ".part").isFile) continue
            File(dir, r.path).parentFile?.mkdirs()
            listOf("", ".sha256", ".part").forEach { ext ->
                File(dir, r.fileName + ext).takeIf { it.isFile }?.renameTo(File(dir, r.path + ext))
            }
            val complete = File(dir, r.path).let { it.isFile && it.length() == r.sizeBytes }
            manifest.put(ManifestEntry(v.model, v.format, v.quant, r.path, r.sha256, r.sizeBytes, r.url, complete))
        }
    }
}
