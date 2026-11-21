package ai.fanak.den.core.recipe

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The table of recipes in recipes/README.md is made from the recipe files, so it cannot drift from them.
 * After a recipe change: `./gradlew :core:jvmTest -Pden.writeTable`, and commit the README with it.
 */
class RecipeTableTest {
    private val dir = File(System.getProperty("den.recipes"))
    private val readme = File(dir, "README.md")

    @Test
    fun theReadmeTableMatchesTheRecipes() {
        val text = readme.readText()
        val start = text.indexOf(START)
        val end = text.indexOf(END)
        check(start >= 0 && end > start) { "recipes/README.md has no generated table" }
        val table = table(RecipeFiles.load(dir))
        val updated = text.substring(0, start + START.length) + "\n" + table + text.substring(end)
        if (System.getProperty("den.writeTable") != null) return readme.writeText(updated)
        assertEquals(text, updated, "the table in recipes/README.md is out of date: ./gradlew :core:jvmTest -Pden.writeTable")
    }

    private fun table(recipes: List<Recipe>): String = buildString {
        appendLine("| Phone | Model size | First choice | Then | Prefill (tok/s) | Decode (tok/s) | Measured |")
        appendLine("|---|---|---|---|---|---|---|")
        recipes.sortedBy { it.soc ?: it.socFamily }.forEach { r ->
            r.rankings.forEach { k ->
                val m = k.measured
                val phone = "${m?.device?.substringBefore(" (") ?: "?"} (${r.soc ?: r.socFamily})"
                val size = "${k.model.arch.replaceFirstChar { it.uppercase() }} ${k.model.paramsB}B"
                val then = k.choices.drop(1).joinToString("; ") { it.label() }.ifEmpty { "-" }
                appendLine("| $phone | $size | ${k.choices.first().label()} | $then | ${m?.prefillTps ?: "-"} | ${m?.decodeTps ?: "-"} | ${m?.date ?: "-"} |")
            }
        }
    }

    private fun RecipeChoice.label() = "$backend, ${accel.name}, ${quant.uppercase()}"

    private companion object {
        const val START = "<!-- Made from the recipe files by RecipeTableTest; do not edit. -->"
        const val END = "<!-- End of the generated table. -->"
    }
}
