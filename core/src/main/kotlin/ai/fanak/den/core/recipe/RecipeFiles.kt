package ai.fanak.den.core.recipe

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File

class RecipeFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object RecipeFiles {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun parse(text: String, source: String = "recipe"): Recipe {
        val r = try {
            yaml.decodeFromString(Recipe.serializer(), text)
        } catch (e: Exception) {
            throw RecipeFormatException("$source: ${e.message}", e)
        }
        fun bad(msg: String): Nothing = throw RecipeFormatException("$source: $msg")
        if (r.model.isBlank()) bad("model is empty")
        if (r.socFamily.isBlank()) bad("soc_family is empty")
        if (r.choices.isEmpty()) bad("no choices")
        if (r.choices.distinct().size != r.choices.size) bad("duplicate choices")
        return r
    }

    // Every *.yaml under dir, e.g. recipes/qwen3-0.6b/SM8750.yaml.
    fun load(dir: File): List<Recipe> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "yaml" }
            .sortedBy { it.path }
            .map { parse(it.readText(), it.relativeTo(dir).path) }
            .toList()
}
