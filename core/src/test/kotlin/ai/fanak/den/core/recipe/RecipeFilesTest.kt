package ai.fanak.den.core.recipe

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.router.Router
import ai.fanak.den.core.router.Source
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecipeFilesTest {
    private val repoRecipes = RecipeFiles.load(File(System.getProperty("den.recipes")))

    private val s25 = DeviceProfile(
        Platform.ANDROID, 37, "SM8750", "snapdragon-8-elite", "arm64-v8a", 11_000, 3_300,
    )
    private val a52s = DeviceProfile(
        Platform.ANDROID, 34, "SM7325", "snapdragon-778g", "arm64-v8a", 5_500, 1_900,
    )

    private class Llama(private val accels: Set<Accel>) : DenBackend {
        override val id = "llamacpp"
        override fun probe(device: DeviceProfile) = Capabilities(accels, setOf(ModelFormat.GGUF))
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session = error("not used")
        override fun health() = Health.Ok
    }

    private val catalog = object : ModelCatalog {
        override fun variants(model: String) = listOf("q4_0", "q4_k_m").map { q ->
            val big = model == "qwen3-1.7b"
            Variant(model, ModelFormat.GGUF, q, if (big) 1_100 else 400, if (big) 1_500 else 600)
        }
    }
    private val store = object : ModelStore {
        override suspend fun fetch(variant: Variant) = Artifact(variant, "/m")
    }

    private fun first(device: DeviceProfile, model: String, accels: Set<Accel>) =
        Router(listOf(Llama(accels)), InMemoryRecipeStore(repoRecipes), catalog, store).plan(model, device).first()

    @Test
    fun repoRecipesParse() {
        assertTrue(repoRecipes.size >= 4)
        assertTrue(repoRecipes.all { it.soc != null && it.measured != null })
    }

    @Test
    fun s25FollowsItsRecipes() {
        val small = first(s25, "qwen3-0.6b", setOf(Accel.CPU, Accel.GPU))
        assertEquals(Source.RECIPE_SOC, small.source)
        assertEquals(Accel.CPU to "q4_0", small.accel to small.variant.quant)

        val big = first(s25, "qwen3-1.7b", setOf(Accel.CPU, Accel.GPU))
        assertEquals(Accel.GPU to "q4_0", big.accel to big.variant.quant)
    }

    @Test
    fun a52sStaysOnCpu() {
        val c = first(a52s, "qwen3-1.7b", setOf(Accel.CPU))
        assertEquals(Source.RECIPE_SOC, c.source)
        assertEquals(Accel.CPU to "q4_0", c.accel to c.variant.quant)
    }

    @Test
    fun gpuRecipeFallsBackWhenNoGpu() {
        val c = first(s25, "qwen3-1.7b", setOf(Accel.CPU))
        assertEquals(Accel.CPU to "q4_0", c.accel to c.variant.quant)
    }

    @Test
    fun rejectsUnknownFieldsAndEmptyChoices() {
        assertFailsWith<RecipeFormatException> {
            RecipeFiles.parse("model: m\nsoc_family: f\nchoices: []\n")
        }
        assertFailsWith<RecipeFormatException> {
            RecipeFiles.parse(
                "model: m\nsoc_family: f\nspeed: 3\nchoices:\n  - { backend: b, accel: cpu, format: gguf, quant: q }\n",
            )
        }
        assertFailsWith<RecipeFormatException> {
            RecipeFiles.parse("model: m\nsoc_family: f\nchoices:\n  - { backend: b, accel: tpu, format: gguf, quant: q }\n")
        }
    }
}
