package ai.fanak.den.core.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A model file format, by its id as recipes write it (`gguf`): an open value, so a new engine (MNN, ONNX Runtime
 * GenAI, RKLLM) brings its own without a change here or to the recipe schema.
 */
@Serializable
@JvmInline
value class ModelFormat(val id: String) {
    init {
        require(id.isNotBlank() && id == id.lowercase()) { "format id must be lower case: $id" }
    }

    override fun toString() = id

    companion object {
        val GGUF = ModelFormat("gguf")
        val LITERTLM = ModelFormat("litertlm")
        val PTE = ModelFormat("pte")
        val QNN = ModelFormat("qnn")
    }
}

// One downloadable file of a model. soc is set only for SoC-specific builds (e.g. NPU).
@Serializable
data class Variant(
    val model: String,
    val format: ModelFormat,
    val quant: String,
    val sizeBytes: Long,
    val minRamMb: Int,
    val soc: String? = null,
    // GGUF architecture and parameter count, when the catalog knows them: what recipes match on (#107).
    val arch: String? = null,
    val params: Long? = null,
    // Longest context the file supports, in tokens: trained (GGUF) or compiled in (NPU builds). Null when unknown.
    val maxContext: Int? = null,
    // KV cache per token of context, in bytes, from the GGUF header; with the file size it gives the RAM a context needs.
    val kvBytesPerToken: Long? = null,
)

// What a file of an artifact is for.
@Serializable
enum class FileRole {
    @SerialName("model") MODEL,
    // The vision encoder of a multimodal GGUF (mmproj).
    @SerialName("projector") PROJECTOR,
    @SerialName("tokenizer") TOKENIZER,
    @SerialName("other") OTHER,
}

/**
 * A variant on disk: its files by role. One GGUF has only a model file; a VLM adds its projector, and a QNN or Genie
 * build has several binaries and a tokenizer.
 */
@Serializable
data class Artifact(
    val variant: Variant,
    val files: Map<FileRole, String>,
) {
    init {
        require(FileRole.MODEL in files) { "an artifact needs its model file" }
    }

    // A single model file.
    constructor(variant: Variant, path: String) : this(variant, mapOf(FileRole.MODEL to path))

    // The model file.
    val path: String get() = files.getValue(FileRole.MODEL)

    fun file(role: FileRole): String? = files[role]
}

interface ModelCatalog {
    // The model names this catalog knows.
    val models: List<String> get() = emptyList()

    fun variants(model: String): List<Variant>

    // Looks the model up (network, cache) before `variants` is asked; a catalog that knows its models returns them.
    suspend fun resolve(id: String): List<Variant> = variants(id)

    // Where to download a variant from; null when this catalog does not serve it.
    fun remote(variant: Variant): RemoteFile? = null

    // The model's licence id when known before any lookup, e.g. from a curated entry.
    fun licence(model: String): String? = null

    // A newer commit of the model's repository than the one in use; null when up to date or not from a hub.
    suspend fun newerCommit(model: String): String? = null
}

/** Several catalogs as one: models and variants in catalog order, a variant's file from the first catalog that has it. */
class CompositeCatalog(private val catalogs: List<ModelCatalog>) : ModelCatalog {
    override val models get() = catalogs.flatMap { it.models }.distinct()

    override fun variants(model: String) = catalogs.flatMap { it.variants(model) }.distinct()

    override suspend fun resolve(id: String) = catalogs.flatMap { it.resolve(id) }.distinct()

    override fun remote(variant: Variant) = catalogs.firstNotNullOfOrNull { it.remote(variant) }

    override fun licence(model: String) = catalogs.firstNotNullOfOrNull { it.licence(model) }

    override suspend fun newerCommit(model: String): String? = catalogs.firstNotNullOfOrNull { it.newerCommit(model) }
}

// Downloads and caches variants; in connected mode one copy is shared by all apps.
interface ModelStore {
    suspend fun fetch(variant: Variant): Artifact

    // On disk already, so fetch() downloads nothing. A quick check (size), not the SHA-256.
    fun has(variant: Variant): Boolean = false

    // Room left for downloads, in bytes; null when this store does not download.
    fun freeBytes(): Long? = null
}
