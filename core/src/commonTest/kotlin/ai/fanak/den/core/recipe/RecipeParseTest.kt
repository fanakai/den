package ai.fanak.den.core.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Parsing alone, on every target; the recipes of the repository are checked on the JVM (RecipeFilesTest).
class RecipeParseTest {
    private fun recipe(extra: String = "", choices: String = "      - { backend: b, accel: cpu, format: gguf, quant: q }\n") =
        "schema: 1\nsoc_family: f\n${extra}rankings:\n  - model: { arch: a, params_b: 1.0 }\n    choices:\n$choices"

    @Test
    fun strictParsingRejectsUnknownFieldsAndEmptyChoices() {
        RecipeFiles.parse(recipe())
        assertFailsWith<RecipeFormatException> { RecipeFiles.parse(recipe(choices = "      []\n")) }
        assertFailsWith<RecipeFormatException> { RecipeFiles.parse(recipe(extra = "speed: 3\n")) }
        assertFailsWith<RecipeFormatException> {
            RecipeFiles.parse(recipe(choices = "      - { backend: b, accel: tpu, format: gguf, quant: q }\n"))
        }
    }

    @Test
    fun phoneParsingIgnoresNewFieldsAndSkipsNewerSchemas() {
        assertEquals("f", RecipeFiles.parseOrNull(recipe(extra = "speed: 3\n"))?.socFamily)
        assertEquals(null, RecipeFiles.parseOrNull(recipe().replace("schema: 1", "schema: 2")))
        assertEquals(null, RecipeFiles.parseOrNull("not: [a recipe"))
    }
}

