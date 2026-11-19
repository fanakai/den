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
data class Variant(
    val model: String,
    val format: ModelFormat,
    val quant: String,
    val sizeBytes: Long,
    val minRamMb: Int,
    val soc: String? = null,
)

data class Artifact(
    val variant: Variant,
    val path: String,
)

interface ModelCatalog {
    fun variants(model: String): List<Variant>
}

// Downloads and caches variants; in connected mode one copy is shared by all apps.
interface ModelStore {
    suspend fun fetch(variant: Variant): Artifact
}
