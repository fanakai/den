package ai.fanak.den

import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.RemoteFile
import ai.fanak.den.core.model.Variant
import java.io.File
import java.io.FileNotFoundException

internal data class CatalogEntry(
    val variant: Variant,
    val fileName: String,
    val url: String,
    val sha256: String,
    // Where the file is stored, relative to the model folder.
    val path: String = fileName,
    val licence: String? = null,
)

// Until the live catalog (#31). minRamMb is a rough margin until den bench measures peak RAM (#14).
internal object BuiltinCatalog : ModelCatalog {
    private const val HF = "https://huggingface.co/unsloth"

    // Parameter counts from the GGUF metadata of the unsloth repositories (Hugging Face `expand[]=gguf`).
    private val PARAMS = mapOf("0.6B" to 596_049_920L, "1.7B" to 1_720_574_976L)

    // KV cache per token from the GGUF headers: 28 layers, 8 KV heads, keys and values of 128, F16.
    private const val QWEN3_KV = 114_688L

    private fun qwen3(size: String, quant: String, bytes: Long, ramMb: Int, sha256: String): CatalogEntry {
        val file = "Qwen3-$size-${quant.uppercase()}.gguf"
        return CatalogEntry(
            Variant("qwen3-${size.lowercase()}", ModelFormat.GGUF, quant, bytes, ramMb, arch = "qwen3", params = PARAMS[size], maxContext = 40960, kvBytesPerToken = QWEN3_KV),
            file,
            "$HF/Qwen3-$size-GGUF/resolve/main/$file",
            sha256,
            "hf/unsloth/Qwen3-$size-GGUF/$file",
            // As on the model cards of Qwen and unsloth.
            "apache-2.0",
        )
    }

    // LiteRT-LM files of litert-community, pinned to a commit: the repositories replace files under the same name.
    // int4: dynamic INT4 weights (block 32), float activations; 4,096 tokens of context in the file's metadata. The KV
    // cache is float, twice the bytes of llama.cpp's F16 per token.
    private fun qwen3Litert(size: String, commit: String, bytes: Long, ramMb: Int, sha256: String): CatalogEntry {
        val file = "Qwen3-${size}_dynamic_wi4b32_afp32.litertlm"
        return CatalogEntry(
            Variant("qwen3-${size.lowercase()}", ModelFormat.LITERTLM, "int4", bytes, ramMb, arch = "qwen3", params = PARAMS[size], maxContext = 4096, kvBytesPerToken = 2 * QWEN3_KV),
            file,
            "https://huggingface.co/litert-community/Qwen3-$size/resolve/$commit/$file",
            sha256,
            "hf/litert-community/Qwen3-$size/$file",
            "apache-2.0",
        )
    }

    val entries = listOf(
        qwen3("0.6B", "q4_0", 382_156_480, 700, "33bcc57074ec7b6eada5a90651ee546ec0c2b271002c22baf9f1b2dd1e8f75cb"),
        qwen3("0.6B", "q4_k_m", 396_705_472, 700, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"),
        qwen3("1.7B", "q4_0", 1_056_782_912, 1_500, "c876f159707a4e4f70e045106c69db15bfc935a4981706fd4f65c6e7ea1e81c5"),
        qwen3("1.7B", "q4_k_m", 1_107_409_472, 1_500, "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897"),
        qwen3Litert("0.6B", "8d42ab5f1db14cad987fac57aeb17d070f9e180d", 343_490_560, 700, "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"),
        qwen3Litert("1.7B", "c421cc3775575372ac2ca4aa1bebc97300ae36b6", 977_184_032, 1_500, "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8"),
    )

    override val models get() = entries.map { it.variant.model }.distinct()

    override fun variants(model: String) = entries.filter { it.variant.model == model }.map { it.variant }

    fun entry(variant: Variant) = entries.first { it.variant == variant }

    override fun licence(model: String) = entries.firstOrNull { it.variant.model == model }?.licence

    override fun remote(variant: Variant) =
        entries.firstOrNull { it.variant == variant }?.let {
            RemoteFile(it.fileName, it.url, it.sha256, variant.sizeBytes, it.path, it.licence)
        }
}

// Model files already on disk, with no download (DenConfig.download = false).
internal class FileModelStore(val dir: File, private val catalog: ModelCatalog = BuiltinCatalog) : ModelStore {
    override suspend fun fetch(variant: Variant): Artifact {
        val r = catalog.remote(variant) ?: throw FileNotFoundException("no file for $variant in the catalog")
        // Placed by hand: under its path, or directly in the folder.
        val f = File(dir, r.path).takeIf { it.isFile } ?: File(dir, r.fileName)
        if (!f.isFile) throw FileNotFoundException("missing ${f.path}")
        return Artifact(variant, f.path)
    }

    override fun has(variant: Variant): Boolean {
        val r = catalog.remote(variant) ?: return false
        return File(dir, r.path).isFile || File(dir, r.fileName).isFile
    }
}
