package ai.fanak.den.core.model

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

// One curated model: a short name for a trusted source, in `models/<name>.yaml`.
@Serializable
data class IndexEntry(
    val name: String,
    // A model id another catalog resolves, e.g. hf:unsloth/Qwen3-4B-GGUF.
    val source: String,
    val licence: String,
    val description: String = "",
    val aliases: List<String> = emptyList(),
)

object ModelIndex {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun parse(text: String, source: String = "model index"): IndexEntry {
        val e = runCatching { yaml.decodeFromString(IndexEntry.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("$source: ${it.message}", it) }
        require(e.name.isNotBlank() && e.name == e.name.lowercase()) { "$source: name must be lower case and not empty" }
        require(':' in e.source) { "$source: source must be a model id such as hf:owner/repo" }
        return e
    }
}

/** Short names from the curated index, resolved through `sources` (for example Hugging Face) under the short name. */
class IndexCatalog(private val entries: List<IndexEntry>, private val sources: ModelCatalog) : ModelCatalog {
    // Variant under the short name -> variant of the source.
    private val origin = ConcurrentHashMap<Variant, Variant>()

    override val models get() = entries.map { it.name }

    private fun entry(id: String) = entries.firstOrNull { it.name == id || id in it.aliases }

    override fun variants(model: String): List<Variant> {
        val e = entry(model) ?: return emptyList()
        return origin.keys.filter { it.model == e.name }
    }

    override suspend fun resolve(id: String): List<Variant> {
        val e = entry(id) ?: return emptyList()
        sources.resolve(e.source).forEach { v -> origin[v.copy(model = e.name)] = v }
        return variants(e.name)
    }

    override fun licence(model: String) = entry(model)?.licence

    override suspend fun newerCommit(model: String) = entry(model)?.let { sources.newerCommit(it.source) }

    // The curated licence, reviewed with the entry, over the one the source gives.
    override fun remote(variant: Variant) = origin[variant]?.let { sources.remote(it) }?.let { r ->
        entry(variant.model)?.let { r.copy(licence = it.licence) } ?: r
    }
}
