package ai.fanak.den.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModelFormat {
    @SerialName("gguf") GGUF,
    @SerialName("litertlm") LITERTLM,
    @SerialName("pte") PTE,
    @SerialName("qnn") QNN,
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
)

@Serializable
data class Artifact(
    val variant: Variant,
    val path: String,
)

interface ModelCatalog {
    // The model names this catalog knows.
    val models: List<String> get() = emptyList()

    fun variants(model: String): List<Variant>

    // Looks the model up (network, cache) before `variants` is asked; a catalog that knows its models returns them.
    suspend fun resolve(id: String): List<Variant> = variants(id)

    // Where to download a variant from; null when this catalog does not serve it.
    fun remote(variant: Variant): RemoteFile? = null
}

/** Several catalogs as one: models and variants in catalog order, a variant's file from the first catalog that has it. */
class CompositeCatalog(private val catalogs: List<ModelCatalog>) : ModelCatalog {
    override val models get() = catalogs.flatMap { it.models }.distinct()

    override fun variants(model: String) = catalogs.flatMap { it.variants(model) }.distinct()

    override suspend fun resolve(id: String) = catalogs.flatMap { it.resolve(id) }.distinct()

    override fun remote(variant: Variant) = catalogs.firstNotNullOfOrNull { it.remote(variant) }
}

// Downloads and caches variants; in connected mode one copy is shared by all apps.
interface ModelStore {
    suspend fun fetch(variant: Variant): Artifact
}
