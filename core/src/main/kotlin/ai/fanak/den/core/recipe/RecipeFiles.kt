package ai.fanak.den.core.recipe

import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File

class RecipeFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Reads recipe files. Strict in CI, so a typo fails the build; lenient on the phone, so a recipe of a newer schema
 * with more fields still reads, and one that cannot be read is skipped instead of failing the load.
 */
object RecipeFiles {
    private val strict = Yaml(configuration = YamlConfiguration(strictMode = true))
    private val lenient = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun parse(text: String, source: String = "recipe", strictMode: Boolean = true): Recipe {
        val r = try {
            (if (strictMode) strict else lenient).decodeFromString(Recipe.serializer(), text)
        } catch (e: Exception) {
            throw RecipeFormatException("$source: ${e.message}", e)
        }
        fun bad(msg: String): Nothing = throw RecipeFormatException("$source: $msg")
        if (r.schema > Recipe.SCHEMA) bad("schema ${r.schema} is newer than ${Recipe.SCHEMA}")
        if (r.socFamily.isBlank()) bad("soc_family is empty")
        if (r.rankings.isEmpty()) bad("no rankings")
        r.rankings.forEach { k ->
            if (k.model.arch.isBlank() || k.model.paramsB <= 0) bad("ranking without a model architecture and size")
            if (k.choices.isEmpty()) bad("no choices for ${k.model}")
            if (k.choices.distinct().size != k.choices.size) bad("duplicate choices for ${k.model}")
        }
        if (r.rankings.map { it.model }.distinct().size != r.rankings.size) bad("two rankings for one model size")
        return r
    }

    // As the hand-written files: plain strings on one line, no empty or default fields.
    private val writer = Yaml(
        configuration = YamlConfiguration(
            encodeDefaults = false,
            singleLineStringStyle = SingleLineStringStyle.Plain,
            breakScalarsAt = Int.MAX_VALUE,
        ),
    )

    // A recipe as it is written to recipes/<soc>.yaml, e.g. by den bench.
    fun encode(recipe: Recipe): String {
        val body = writer.encodeToString(Recipe.serializer(), recipe)
        // The schema is a default, so the writer leaves it out; files always say it.
        return (if (body.startsWith("schema:")) body else "schema: ${recipe.schema}\n$body").trimEnd() + "\n"
    }

    // On the phone: null when unreadable or of a newer schema, so the other recipes still apply.
    fun parseOrNull(text: String, source: String = "recipe"): Recipe? =
        runCatching { parse(text, source, strictMode = false) }.getOrNull()

    // Every *.yaml under dir, e.g. recipes/SM8750.yaml.
    fun load(dir: File): List<Recipe> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "yaml" }
            .sortedBy { it.path }
            .map { parse(it.readText(), it.relativeTo(dir).path) }
            .toList()
}
