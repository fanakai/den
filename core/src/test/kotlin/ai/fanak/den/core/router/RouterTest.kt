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
import ai.fanak.den.core.recipe.ModelSize
import ai.fanak.den.core.recipe.Ranking
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

    private val size = 1_720_574_976L
    private val q4km = Variant(model, ModelFormat.GGUF, "q4_k_m", 1_100, 1_500, arch = "qwen3", params = size)
    private val q40 = Variant(model, ModelFormat.GGUF, "q4_0", 1_000, 1_400, arch = "qwen3", params = size)
    private val npu = Variant(model, ModelFormat.LITERTLM, "int4", 1_200, 1_600, soc = "SM8750", arch = "qwen3", params = size)
    private val huge = Variant(model, ModelFormat.GGUF, "f16", 3_400, 8_000, arch = "qwen3", params = size)

    private class FakeBackend(
        override val id: String,
        private val caps: Capabilities,
        private val failOn: Set<Accel> = emptySet(),
        private val crashOn: Set<Accel> = emptySet(),
    ) : DenBackend {
        val loaded = mutableListOf<Pair<Variant, Accel>>()
        override fun probe(device: DeviceProfile) = caps
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
            loaded += artifact.variant to opts.accel
            if (opts.accel in crashOn) throw EngineCrashedException("engine died on ${opts.accel}")
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

    private fun llama(failOn: Set<Accel> = emptySet(), crashOn: Set<Accel> = emptySet()) =
        FakeBackend("llamacpp", Capabilities(setOf(Accel.CPU, Accel.GPU), setOf(ModelFormat.GGUF)), failOn, crashOn)

    private fun litert(failOn: Set<Accel> = emptySet(), crashOn: Set<Accel> = emptySet()) =
        FakeBackend("litert", Capabilities(setOf(Accel.GPU, Accel.NPU), setOf(ModelFormat.LITERTLM)), failOn, crashOn)

    private fun router(backends: List<DenBackend>, recipes: List<Recipe> = emptyList()) =
        Router(backends, InMemoryRecipeStore(recipes), catalog, store)

    @Test
    fun recipeChoicesComeFirstInOrder() {
        val recipe = Recipe(
            socFamily = "snapdragon-8-elite",
            rankings = listOf(
                Ranking(
                    ModelSize("qwen3", 1.7),
                    listOf(
                        RecipeChoice("litert", Accel.NPU, ModelFormat.LITERTLM, "int4"),
                        RecipeChoice("llamacpp", Accel.GPU, ModelFormat.GGUF, "q4_k_m"),
                    ),
                ),
            ),
        )
        val plan = router(listOf(llama(), litert()), listOf(recipe)).plan(model, device)

        assertEquals(Source.RECIPE_FAMILY, plan[0].source)
        assertEquals(npu to Accel.NPU, plan[0].variant to plan[0].accel)
        assertEquals(q4km to Accel.GPU, plan[1].variant to plan[1].accel)
        assertTrue(plan.drop(2).all { it.source == Source.DEFAULT })
    }

    @Test
    fun defaultsPreferFourBitQuantsOverLargerFiles() {
        val q8 = Variant(model, ModelFormat.GGUF, "q8_0", 1_900, 2_000)
        val bf16 = Variant(model, ModelFormat.GGUF, "bf16", 2_400, 2_500)
        val q2 = Variant(model, ModelFormat.GGUF, "q2_k", 700, 800)
        val cat = object : ModelCatalog {
            override fun variants(model: String) = listOf(bf16, q8, q2, q40, q4km)
        }
        val plan = Router(listOf(llama()), InMemoryRecipeStore(emptyList()), cat, store).plan(model, device)
        // q2_k is never picked without a recipe.
        assertEquals(listOf("q4_k_m", "q4_0", "q8_0", "bf16"), plan.filter { it.accel == Accel.CPU }.map { it.variant.quant })
    }

    @Test
    fun networkFailureStopsBeforeOtherFiles() = runTest {
        var fetches = 0
        val offline = object : ModelStore {
            override suspend fun fetch(variant: Variant): Artifact {
                fetches++
                throw ai.fanak.den.core.model.OfflineException("no network")
            }
        }
        val r = Router(listOf(llama()), InMemoryRecipeStore(emptyList()), catalog, offline)
        val e = assertFailsWith<NoUsableBackendException> { r.open(model, device) }
        assertEquals(1, fetches)
        assertEquals(1, e.failures.size)
    }

    @Test
    fun unknownArchitectureIsRefusedBeforeDownload() = runTest {
        var fetches = 0
        val counting = object : ModelStore {
            override suspend fun fetch(variant: Variant): Artifact = Artifact(variant, "/m").also { fetches++ }
        }
        val newer = object : ModelCatalog {
            override fun variants(model: String) = listOf(q4km.copy(arch = "qwen9"), q40.copy(arch = "qwen9"))
        }
        val engine = FakeBackend("llamacpp", Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF), setOf("qwen3", "llama")))
        // Said even when the files would not fit in RAM either: a newer engine is what the user needs first.
        val e = assertFailsWith<NoUsableBackendException> {
            Router(listOf(engine), InMemoryRecipeStore(emptyList()), newer, counting).open(model, device.copy(availRamMb = 500))
        }
        assertEquals("$model needs a newer engine (architecture qwen9)", e.message)
        assertEquals(0, fetches)

        // Known or unreported architectures are still tried.
        val r = Router(listOf(engine), InMemoryRecipeStore(emptyList()), catalog, counting)
        assertEquals(q4km, r.open(model, device).candidate.variant)
        val noSet = FakeBackend("llamacpp", Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF)))
        assertEquals(2, Router(listOf(noSet), InMemoryRecipeStore(emptyList()), newer, counting).plan(model, device).size)
    }

    @Test
    fun refusedDownloadStopsBeforeOtherFiles() = runTest {
        var fetches = 0
        val refusing = object : ModelStore {
            override suspend fun fetch(variant: Variant): Artifact {
                fetches++
                throw ai.fanak.den.core.model.DownloadRefusedException("not approved")
            }
        }
        val e = assertFailsWith<NoUsableBackendException> {
            Router(listOf(llama()), InMemoryRecipeStore(emptyList()), catalog, refusing).open(model, device)
        }
        assertEquals(1, fetches)
        assertEquals("not approved", e.failures.single().error.message)
    }

    @Test
    fun emptyPlanSaysWhy() = runTest {
        val r = router(listOf(llama()))
        val tooSmall = assertFailsWith<NoUsableBackendException> { r.open(model, device.copy(availRamMb = 500)) }
        assertTrue(tooSmall.message!!.startsWith("no file of $model fits in free RAM: 500 MB free"))
        val empty = object : ModelCatalog {
            override fun variants(model: String) = emptyList<Variant>()
        }
        val unknown = assertFailsWith<NoUsableBackendException> {
            Router(listOf(llama()), InMemoryRecipeStore(emptyList()), empty, store).open("other", device)
        }
        assertTrue(unknown.message!!.startsWith("unknown model other"))
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
    fun defaultsPreferCpuThenGpuThenNpu() {
        val accels = router(listOf(llama(), litert())).plan(model, device).map { it.accel }.distinct()
        assertEquals(listOf(Accel.CPU, Accel.GPU, Accel.NPU), accels)
    }

    @Test
    fun fallsBackWhenLoadFails() = runTest {
        val opened = router(listOf(llama(failOn = setOf(Accel.CPU)), litert())).open(model, device)

        assertEquals(2, opened.failures.size)
        assertEquals("llamacpp", opened.candidate.backend.id)
        assertEquals(Accel.GPU, opened.candidate.accel)
        assertTrue(opened.needsBenchmark)
    }

    @Test
    fun crashedCandidateIsSkippedNextTime() = runTest {
        val llama = llama(crashOn = setOf(Accel.CPU))
        val r = router(listOf(llama, litert()))

        assertEquals(Accel.GPU, r.open(model, device).candidate.accel)
        assertEquals(Accel.GPU, r.open(model, device).candidate.accel)
        assertEquals(2, llama.loaded.count { it.second == Accel.CPU })
    }

    @Test
    fun lostEngineStopsWithoutTryingOtherFiles() = runTest {
        val lost = object : DenBackend {
            var loads = 0
            override val id = "llamacpp"
            override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU, Accel.GPU), setOf(ModelFormat.GGUF))
            override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
                loads++
                throw EngineLostException("the engine process did not start")
            }
            override fun health() = Health.Ok
        }
        assertFailsWith<NoUsableBackendException> { router(listOf(lost)).open(model, device) }
        assertEquals(1, lost.loads)
    }

    @Test
    fun ordinaryFailureIsRetriedNextTime() = runTest {
        val llama = llama(failOn = setOf(Accel.CPU))
        val r = router(listOf(llama, litert()))

        r.open(model, device)
        r.open(model, device)
        assertEquals(4, llama.loaded.count { it.second == Accel.CPU })
    }

    @Test
    fun crashDuringGenerationIsReported() = runTest {
        val r = router(listOf(llama(), litert()))
        val first = r.open(model, device)
        assertEquals(q4km to Accel.CPU, first.candidate.variant to first.candidate.accel)

        r.reportFailure(device, first.candidate, EngineCrashedException("died while generating"))
        val next = r.open(model, device).candidate
        assertEquals(q40 to Accel.CPU, next.variant to next.accel)
    }

    @Test
    fun crashOnOneSocDoesNotBlockAnother() = runTest {
        val r = router(listOf(llama()))
        val first = r.open(model, device)
        assertEquals(q4km to Accel.CPU, first.candidate.variant to first.candidate.accel)
        r.reportFailure(device, first.candidate, EngineCrashedException("died"))

        fun hasIt(d: DeviceProfile) = r.plan(model, d).any { it.variant == q4km && it.accel == Accel.CPU }
        assertTrue(!hasIt(device))
        assertTrue(hasIt(device.copy(soc = "SM7325")))
    }

    @Test
    fun throwsWhenNothingLoads() = runTest {
        val r = router(listOf(llama(failOn = setOf(Accel.CPU, Accel.GPU))))
        val e = assertFailsWith<NoUsableBackendException> { r.open(model, device) }
        assertEquals(4, e.failures.size)
    }

    @Test
    fun aFileWithAShorterContextIsSkippedOrCapped() = runTest {
        // An NPU build compiled for 1280 tokens, then the usual GGUF.
        val ekv = npu.copy(maxContext = 1280)
        val gguf = q4km.copy(maxContext = 40960)
        val cat = object : ModelCatalog {
            override fun variants(model: String) = listOf(ekv, gguf)
        }
        val contexts = mutableListOf<Int>()
        val b = object : DenBackend {
            override val id = "litert"
            override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU, Accel.NPU), setOf(ModelFormat.LITERTLM, ModelFormat.GGUF))
            override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
                contexts += opts.contextLength
                return object : Session {
                    override fun generate(req: GenRequest): Flow<GenEvent> = emptyFlow()
                    override fun unload() {}
                }
            }
            override fun health() = Health.Ok
        }
        val recipe = Recipe(
            socFamily = "snapdragon-8-elite",
            rankings = listOf(Ranking(ModelSize("qwen3", 1.7), listOf(RecipeChoice("litert", Accel.NPU, ModelFormat.LITERTLM, "int4")))),
        )
        val r = Router(listOf(b), InMemoryRecipeStore(listOf(recipe)), cat, store)

        assertTrue(r.plan(model, device, minContext = 4096).none { it.variant == ekv })
        assertEquals(gguf, r.open(model, device, contextLength = 4096).candidate.variant)
        // The app accepts 1024 tokens: the NPU file runs with its 1280.
        assertEquals(ekv, r.open(model, device, contextLength = 4096, minContext = 1024).candidate.variant)
        assertEquals(listOf(4096, 1280), contexts)

        val onlyNpu = Router(listOf(b), InMemoryRecipeStore(emptyList()), object : ModelCatalog {
            override fun variants(model: String) = listOf(ekv)
        }, store)
        val e = assertFailsWith<NoUsableBackendException> { onlyNpu.open(model, device, contextLength = 4096) }
        assertEquals("no file of $model supports a context of 4096 tokens (the longest has 1280)", e.message)
    }

    @Test
    fun anAppCanRefuseALicence() = runTest {
        val licences = mapOf(q4km to "cc-by-nc-4.0", q40 to "apache-2.0")
        val cat = object : ModelCatalog {
            override fun variants(model: String) = listOf(q4km, q40)
            override fun remote(variant: Variant) = ai.fanak.den.core.model.RemoteFile("f", "u", "s", 1, "p", licences[variant])
        }
        val nc = { id: String? -> !ai.fanak.den.core.model.Licences.nonCommercial(id) }
        val r = Router(listOf(llama()), InMemoryRecipeStore(emptyList()), cat, store, allowLicence = nc)
        assertTrue(r.plan(model, device).none { it.variant == q4km })
        assertEquals(q40, r.open(model, device).candidate.variant)

        val none = Router(listOf(llama()), InMemoryRecipeStore(emptyList()), cat, store, allowLicence = { false })
        val e = assertFailsWith<NoUsableBackendException> { none.open(model, device) }
        assertEquals("this app does not allow the licence of $model (cc-by-nc-4.0, apache-2.0)", e.message)
    }

    @Test
    fun licenceClasses() {
        val l = ai.fanak.den.core.model.Licences
        assertTrue(l.nonCommercial("cc-by-nc-4.0") && l.nonCommercial("cc-by-nc-sa-4.0") && l.nonCommercial("CC-BY-NC-ND-4.0"))
        assertTrue(!l.nonCommercial("apache-2.0") && !l.nonCommercial("mit") && !l.nonCommercial(null))
        assertTrue(l.usePolicy("llama3.1") && l.usePolicy("gemma") && !l.usePolicy("apache-2.0"))
        assertTrue(l.unknown(null) && l.unknown("other") && !l.unknown("mit"))
    }
}