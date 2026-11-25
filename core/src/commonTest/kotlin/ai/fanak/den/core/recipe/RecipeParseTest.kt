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

    @Test
    fun aChoiceCanNameTheKvCache() {
        val r = RecipeFiles.parse(recipe(choices = "      - { backend: b, accel: cpu, format: gguf, quant: q, kv_cache: q8_0 }\n"))
        assertEquals(ai.fanak.den.core.backend.KvCache.Q8_0, r.rankings.single().choices.single().kvCache)
        assertEquals(ai.fanak.den.core.backend.KvCache.F16, RecipeFiles.parse(recipe()).rankings.single().choices.single().kvCache)
        assertEquals(r, RecipeFiles.parse(RecipeFiles.encode(r)))
        assertFailsWith<RecipeFormatException> {
            RecipeFiles.parse(recipe(choices = "      - { backend: b, accel: cpu, format: gguf, quant: q, kv_cache: q4_0 }\n"))
        }
    }

    @Test
    fun aFormatOfANewEngineNeedsNoChangeToCore() {
        val r = RecipeFiles.parse(recipe(choices = "      - { backend: mnn, accel: cpu, format: mnn, quant: q4 }\n"))
        val format = r.rankings.single().choices.single().format
        assertEquals(ai.fanak.den.core.model.ModelFormat("mnn"), format)
        assertEquals("mnn", format.toString())
        // Written back as it was read.
        assertEquals(r, RecipeFiles.parse(RecipeFiles.encode(r)))
        assertEquals(ai.fanak.den.core.model.ModelFormat.GGUF, RecipeFiles.parse(recipe(choices = "      - { backend: b, accel: cpu, format: gguf, quant: q }\n")).rankings.single().choices.single().format)
    }
}