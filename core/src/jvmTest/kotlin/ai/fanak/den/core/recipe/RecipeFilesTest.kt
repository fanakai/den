package ai.fanak.den.core.recipe

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.device.SocFamilies
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.router.Expected
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

    // Sizes as the catalogs report them: built-in names and hub repositories carry the same GGUF metadata.
    private val sizes = mapOf(
        "qwen3-0.6b" to 596_049_920L, "hf:unsloth/Qwen3-0.6B-GGUF" to 596_049_920L,
        "qwen3-1.7b" to 1_720_574_976L, "hf:unsloth/Qwen3-1.7B-GGUF" to 1_720_574_976L,
        "qwen3-1.2b" to 1_200_000_000L, "qwen3-0.8b" to 800_000_000L, "llama-1.1b" to 1_100_000_000L,
    )

    private val catalog = object : ModelCatalog {
        override fun variants(model: String) = listOf("q4_0", "q4_k_m").map { q ->
            val params = sizes[model]
            val big = (params ?: 0) > 1_000_000_000L
            val arch = if (model.startsWith("llama")) "llama" else "qwen3"
            Variant(model, ModelFormat.GGUF, q, if (big) 1_100 else 400, if (big) 1_500 else 600, arch = arch, params = params)
        }
    }
    private val store = object : ModelStore {
        override suspend fun fetch(variant: Variant) = Artifact(variant, "/m")
    }

    private fun first(device: DeviceProfile, model: String, accels: Set<Accel>, recipes: List<Recipe> = repoRecipes) =
        Router(listOf(Llama(accels)), InMemoryRecipeStore(recipes), catalog, store).plan(model, device).first()

    // Both S25 sizes put the CPU first; with the GPU first at 1.7B, a test sees which ranking a size takes.
    private val gpuFirstAt17 = repoRecipes.map { r ->
        if (r.soc != "SM8750") r
        else r.copy(rankings = r.rankings.map { k ->
            if (k.model.paramsB != 1.7) k else k.copy(choices = k.choices.sortedBy { it.accel != Accel.GPU })
        })
    }

    @Test
    fun repoRecipesParse() {
        assertTrue(repoRecipes.size >= 2)
        assertTrue(repoRecipes.all { r -> r.soc != null && r.rankings.all { it.measured != null } })
    }

    @Test
    fun curatedNameAndHubRepositoryGetTheSameChoice() {
        for ((curated, hub) in listOf("qwen3-0.6b" to "hf:unsloth/Qwen3-0.6B-GGUF", "qwen3-1.7b" to "hf:unsloth/Qwen3-1.7B-GGUF")) {
            val a = first(s25, curated, setOf(Accel.CPU, Accel.GPU))
            val b = first(s25, hub, setOf(Accel.CPU, Accel.GPU))
            assertEquals(Source.RECIPE_SOC, b.source)
            assertEquals(a.accel to a.variant.quant, b.accel to b.variant.quant)
        }
    }

    @Test
    fun sizesBetweenMeasuredOnesTakeTheNearest() {
        // On a log scale 1.2B is nearer 1.7B (GPU first here), 0.8B nearer 0.6B (CPU first).
        assertEquals(Accel.GPU, first(s25, "qwen3-1.2b", setOf(Accel.CPU, Accel.GPU), gpuFirstAt17).accel)
        assertEquals(Accel.CPU, first(s25, "qwen3-0.8b", setOf(Accel.CPU, Accel.GPU), gpuFirstAt17).accel)
    }

    @Test
    fun anotherArchitectureUsesTheNearestSizeToo() {
        val c = first(s25, "llama-1.1b", setOf(Accel.CPU, Accel.GPU), gpuFirstAt17)
        assertEquals(Source.RECIPE_SOC, c.source)
        assertEquals(Accel.GPU, c.accel)
    }

    @Test
    fun modelOfUnknownSizeGetsTheDefaultOrder() {
        assertEquals(Source.DEFAULT, first(s25, "mystery", setOf(Accel.CPU, Accel.GPU)).source)
    }

    @Test
    fun avoidRemovesAChoiceForThatModelOnly() {
        val avoid = Avoid(ModelSize("qwen3", 1.7), RecipeChoice("llamacpp", Accel.GPU, ModelFormat.GGUF, "q4_0"), "test")
        val recipes = repoRecipes.map { r -> if (r.soc == "SM8750") r.copy(avoid = listOf(avoid)) else r }
        fun firstWith(model: String) =
            Router(listOf(Llama(setOf(Accel.CPU, Accel.GPU))), InMemoryRecipeStore(recipes), catalog, store).plan(model, s25).first()
        assertEquals(Accel.CPU to "q4_0", firstWith("qwen3-1.7b").let { it.accel to it.variant.quant })
        assertEquals(Accel.GPU, firstWith("llama-1.1b").accel)
    }

    @Test
    fun s25FollowsItsRecipes() {
        val small = first(s25, "qwen3-0.6b", setOf(Accel.CPU, Accel.GPU))
        assertEquals(Source.RECIPE_SOC, small.source)
        assertEquals(Accel.CPU to "q4_0", small.accel to small.variant.quant)

        // Without LiteRT-LM and the NPU, llama.cpp's GPU is the first of its 1.7B choices left.
        val big = first(s25, "qwen3-1.7b", setOf(Accel.CPU, Accel.GPU))
        assertEquals(Accel.GPU to "q4_0", big.accel to big.variant.quant)
    }

    @Test
    fun s25SendsLongPromptsToTheNpu() {
        val router = Router(listOf(Llama(setOf(Accel.CPU, Accel.GPU, Accel.NPU))), InMemoryRecipeStore(repoRecipes), catalog, store)
        fun first(e: Expected?) = router.plan("qwen3-0.6b", s25, expected = e).first().accel
        assertEquals(Accel.CPU, first(null))
        assertEquals(Accel.NPU, first(Expected(2000, 16)))
        assertEquals(Accel.CPU, first(Expected(300, 128)))
    }

    // llama.cpp and LiteRT-LM on the CPU and the GPU, with a .litertlm file beside the GGUF ones.
    private fun withLiteRt(): Router {
        val litert = object : DenBackend {
            override val id = "litertlm"
            override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU, Accel.GPU), setOf(ModelFormat.LITERTLM))
            override suspend fun load(artifact: Artifact, opts: LoadOptions): Session = error("not used")
            override fun health() = Health.Ok
        }
        val both = object : ModelCatalog {
            override fun variants(model: String) = catalog.variants(model) +
                Variant(model, ModelFormat.LITERTLM, "int4", 400, 700, arch = "qwen3", params = sizes[model])
        }
        return Router(listOf(Llama(setOf(Accel.CPU, Accel.GPU)), litert), InMemoryRecipeStore(repoRecipes), both, store)
    }

    private fun Router.paths(model: String, device: DeviceProfile, e: Expected? = null) =
        plan(model, device, expected = e).map { "${it.backend.id} ${it.accel} ${it.variant.quant}" }

    @Test
    fun s25ChoosesBetweenLlamaCppAndLiteRtLm() {
        val plan = withLiteRt().paths("qwen3-0.6b", s25)
        assertEquals(listOf("litertlm GPU int4", "llamacpp CPU q4_0", "llamacpp GPU q4_0", "litertlm CPU int4"), plan.take(4))
    }

    @Test
    fun a52sChoosesLiteRtLmFor06bOnly() {
        val router = withLiteRt()
        assertEquals(listOf("litertlm GPU int4", "llamacpp CPU q4_0", "llamacpp CPU q4_k_m"), router.paths("qwen3-0.6b", a52s).take(3))
        assertEquals("litertlm GPU int4", router.paths("qwen3-0.6b", a52s, Expected(2000, 16)).first())
        assertEquals("litertlm GPU int4", router.paths("qwen3-0.6b", a52s, Expected(300, 128)).first())
        assertEquals("llamacpp CPU q4_0", router.paths("qwen3-1.7b", a52s).first())
    }

    @Test
    fun a52sStaysOnCpu() {
        val c = first(a52s, "qwen3-1.7b", setOf(Accel.CPU))
        assertEquals(Source.RECIPE_SOC, c.source)
        assertEquals(Accel.CPU to "q4_0", c.accel to c.variant.quant)
    }

    @Test
    fun t760FollowsItsRecipe() {
        val zte = DeviceProfile(Platform.ANDROID, 34, "T760", SocFamilies.of("T760"), "arm64-v8a", 3_400, 1_200)
        val c = first(zte, "qwen3-0.6b", setOf(Accel.CPU))
        assertEquals(Source.RECIPE_SOC, c.source)
        assertEquals(Accel.CPU to "q4_0", c.accel to c.variant.quant)
    }

    @Test
    fun gpuRecipeFallsBackWhenNoGpu() {
        val c = first(s25, "qwen3-1.7b", setOf(Accel.CPU))
        assertEquals(Accel.CPU to "q4_0", c.accel to c.variant.quant)
    }
}
