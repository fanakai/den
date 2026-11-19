package ai.fanak.den.core.router

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
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
import ai.fanak.den.core.recipe.InMemoryRecipeStore
import ai.fanak.den.core.recipe.Recipe
import ai.fanak.den.core.recipe.RecipeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RouterTest {
    private val device = DeviceProfile(
        platform = Platform.ANDROID, osApi = 35, soc = "SM8750", socFamily = "snapdragon-8-elite",
        abi = "arm64-v8a", ramMb = 12_000, availRamMb = 6_000,
    )
    private val model = "qwen3-1.7b"

    private val q4km = Variant(model, ModelFormat.GGUF, "q4_k_m", 1_100, 1_500)
    private val q40 = Variant(model, ModelFormat.GGUF, "q4_0", 1_000, 1_400)
    private val npu = Variant(model, ModelFormat.LITERTLM, "int4", 1_200, 1_600, soc = "SM8750")
    private val huge = Variant(model, ModelFormat.GGUF, "f16", 3_400, 8_000)

    private class FakeBackend(
        override val id: String,
        private val caps: Capabilities,
        private val failOn: Set<Accel> = emptySet(),
    ) : DenBackend {
        val loaded = mutableListOf<Pair<Variant, Accel>>()
        override fun probe(device: DeviceProfile) = caps
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
            loaded += artifact.variant to opts.accel
            if (opts.accel in failOn) error("load failed on ${opts.accel}")
            return object : Session {
                override fun generate(req: GenRequest): Flow<GenEvent> = emptyFlow()
                override fun unload() {}
            }
        }
        override fun health() = Health.Ok
    }

    private val catalog = object : ModelCatalog {
        override fun variants(model: String) = listOf(q4km, q40, npu, huge)
    }
    private val store = object : ModelStore {
        override suspend fun fetch(variant: Variant) = Artifact(variant, "/models/${variant.quant}")
    }

    private fun llama(failOn: Set<Accel> = emptySet()) =
        FakeBackend("llamacpp", Capabilities(setOf(Accel.CPU, Accel.GPU), setOf(ModelFormat.GGUF)), failOn)

    private fun litert(failOn: Set<Accel> = emptySet()) =
        FakeBackend("litert", Capabilities(setOf(Accel.GPU, Accel.NPU), setOf(ModelFormat.LITERTLM)), failOn)

    private fun router(backends: List<DenBackend>, recipes: List<Recipe> = emptyList()) =
        Router(backends, InMemoryRecipeStore(recipes), catalog, store)

    @Test
    fun recipeChoicesComeFirstInOrder() {
        val recipe = Recipe(
            model, "snapdragon-8-elite",
            listOf(
                RecipeChoice("litert", Accel.NPU, ModelFormat.LITERTLM, "int4"),
                RecipeChoice("llamacpp", Accel.GPU, ModelFormat.GGUF, "q4_k_m"),
            ),
        )
        val plan = router(listOf(llama(), litert()), listOf(recipe)).plan(model, device)

        assertEquals(Source.RECIPE_FAMILY, plan[0].source)
        assertEquals(npu to Accel.NPU, plan[0].variant to plan[0].accel)
        assertEquals(q4km to Accel.GPU, plan[1].variant to plan[1].accel)
        assertTrue(plan.drop(2).all { it.source == Source.DEFAULT })
    }

    @Test
    fun dropsVariantsThatDoNotFitInRam() {
        val plan = router(listOf(llama())).plan(model, device)
        assertTrue(plan.none { it.variant == huge })
    }

    @Test
    fun dropsSocBuildsForOtherChips() {
        val plan = router(listOf(litert())).plan(model, device.copy(soc = "SM7225"))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun defaultsPreferNpuThenGpuThenCpu() {
        val accels = router(listOf(llama(), litert())).plan(model, device).map { it.accel }.distinct()
        assertEquals(listOf(Accel.NPU, Accel.GPU, Accel.CPU), accels)
    }

    @Test
    fun fallsBackWhenLoadFails() = runTest {
        val litert = litert(failOn = setOf(Accel.NPU, Accel.GPU))
        val opened = router(listOf(llama(), litert)).open(model, device)

        assertEquals(1, opened.failures.size)
        assertEquals("llamacpp", opened.candidate.backend.id)
        assertEquals(Accel.GPU, opened.candidate.accel)
        assertTrue(opened.needsBenchmark)
    }

    @Test
    fun throwsWhenNothingLoads() = runTest {
        val r = router(listOf(llama(failOn = setOf(Accel.CPU, Accel.GPU))))
        val e = assertFailsWith<NoUsableBackendException> { r.open(model, device) }
        assertEquals(4, e.failures.size)
    }
}
