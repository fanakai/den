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
import ai.fanak.den.core.device.Thermal
import ai.fanak.den.core.device.ThermalState
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
        var sequences = 0
        override fun probe(device: DeviceProfile) = caps
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
            loaded += artifact.variant to opts.accel
            sequences = opts.sequences
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

        // The file already fetched, on the next accelerator, before another file.
        assertEquals(1, opened.failures.size)
        assertEquals("llamacpp", opened.candidate.backend.id)
        assertEquals(q4km to Accel.GPU, opened.candidate.variant to opened.candidate.accel)
        assertTrue(opened.needsBenchmark)
    }

    @Test
    fun crashedCandidateIsSkippedNextTime() = runTest {
        val llama = llama(crashOn = setOf(Accel.CPU))
        val r = router(listOf(llama, litert()))

        assertEquals(Accel.GPU, r.open(model, device).candidate.accel)
        assertEquals(Accel.GPU, r.open(model, device).candidate.accel)
        assertEquals(1, llama.loaded.count { it.second == Accel.CPU })
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
        assertEquals(2, llama.loaded.count { it.second == Accel.CPU })
    }

    @Test
    fun crashDuringGenerationIsReported() = runTest {
        val r = router(listOf(llama(), litert()))
        val first = r.open(model, device)
        assertEquals(q4km to Accel.CPU, first.candidate.variant to first.candidate.accel)

        r.reportFailure(device, first.candidate, EngineCrashedException("died while generating"))
        val next = r.open(model, device).candidate
        assertEquals(q4km to Accel.GPU, next.variant to next.accel)
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

    // Qwen3-0.6B Q4_0 with the KV cache size of its GGUF header: 364 MB of weights, 448 MB of KV at 4096 tokens.
    private val sized = Variant(model, ModelFormat.GGUF, "q4_0", 382_156_480, 700, arch = "qwen3", params = size, kvBytesPerToken = 114_688)

    private fun sizedRouter(free: () -> Int = { device.availRamMb }, s: ModelStore = store) = Router(
        listOf(llama()), InMemoryRecipeStore(emptyList()),
        object : ModelCatalog { override fun variants(model: String) = listOf(sized) }, s, freeRamMb = free,
    )

    @Test
    fun ramNeedCountsTheKvCacheAndMmapedWeightsOnlyInTotal() {
        // 448 MB of KV + 520 MB of buffers in free RAM; the 364 MB of mmapped weights only against the phone's RAM.
        assertEquals(Router.RamNeed(968, 1332), Router.ramNeed(sized, 4096))
        assertEquals(Router.RamNeed(1332, 1332), Router.ramNeed(sized, 4096, mmap = false))
        assertEquals(Router.RamNeed(744, 1108), Router.ramNeed(sized, 2048))
        // Without the KV cache size, the catalog's estimate.
        assertEquals(Router.RamNeed(1400, 1400), Router.ramNeed(q40, 4096))
    }

    @Test
    fun littleFreeRamGivesAShorterContextDownToTheAppsMinimum() = runTest {
        val r = sizedRouter(free = { 800 })
        assertTrue(r.plan(model, device.copy(availRamMb = 800), minContext = 4096).isEmpty())
        val opened = r.open(model, device, contextLength = 4096, minContext = 1024)
        assertEquals(2048, opened.contextLength)

        // Not even the minimum fits: no load, and the reason names the memory.
        val e = assertFailsWith<NoUsableBackendException> { sizedRouter(free = { 600 }).open(model, device, minContext = 1024) }
        assertTrue(e.failures.isNotEmpty() && e.failures.all { it.error is NotEnoughMemoryException }, "${e.failures}")
    }

    @Test
    fun sequencesNeedAKvCacheEachInFreeRam() = runTest {
        // 4096 tokens: 968 MB for one sequence, 1416 MB for two.
        val b = llama()
        val r = { free: Int -> Router(listOf(b), InMemoryRecipeStore(emptyList()), object : ModelCatalog { override fun variants(model: String) = listOf(sized) }, store, freeRamMb = { free }) }
        r(1_500).open(model, device, sequences = 2)
        assertEquals(2, b.sequences)
        r(1_200).open(model, device, sequences = 2)
        assertEquals(1, b.sequences)
    }

    @Test
    fun freeRamIsReadAgainBeforeEachCandidate() = runTest {
        var free = 6_000
        val r = sizedRouter(free = { free })
        assertEquals(4096, r.open(model, device).contextLength)
        // Another app took memory since the plan was made.
        free = 900
        val e = assertFailsWith<NoUsableBackendException> { r.open(model, device) }
        assertTrue(e.failures.all { it.error is NotEnoughMemoryException })
    }

    @Test
    fun aFileTheDiskCannotHoldIsLeftOut() = runTest {
        val full = object : ModelStore {
            override suspend fun fetch(variant: Variant) = Artifact(variant, "/m")
            override fun freeBytes() = 100L shl 20
        }
        val e = assertFailsWith<NoUsableBackendException> { sizedRouter(s = full).open(model, device) }
        assertEquals("no file of $model fits on disk: 100 MB free, the smallest needs 364 MB", e.message)
        // On disk already: no room needed.
        val present = object : ModelStore by full {
            override fun has(variant: Variant) = true
        }
        assertEquals(sized, sizedRouter(s = present).open(model, device).candidate.variant)
    }

    @Test
    fun aModelLargerThanThePhoneIsSaidSo() = runTest {
        val e = assertFailsWith<NoUsableBackendException> { sizedRouter().open(model, device.copy(ramMb = 1_000)) }
        assertEquals("no file of $model fits in this phone's 1000 MB of RAM: the smallest needs 1332 MB", e.message)
    }

    @Test
    fun aHotPhoneTriesSmallerFilesFirst() {
        val r = router(listOf(llama()))
        val cool = r.plan(model, device)
        assertEquals(q4km, cool.first().variant)
        val hot = r.plan(model, device.copy(thermal = ThermalState.SEVERE))
        // q4_0 (1000 bytes) before q4_k_m (1100); otherwise the cool order, so each file keeps its accelerator order.
        assertEquals(cool.sortedBy { it.variant.sizeBytes }, hot)
        assertEquals(q40 to Accel.CPU, hot.first().variant to hot.first().accel)
        assertEquals(cool, r.plan(model, device.copy(thermal = ThermalState.MODERATE)))
    }

    @Test
    fun aSessionThatHeatsUpUsesFewerThreads() {
        assertEquals(null, Thermal.threads(4, ThermalState.LIGHT))
        assertEquals(3, Thermal.threads(4, ThermalState.MODERATE))
        assertEquals(2, Thermal.threads(4, ThermalState.SEVERE))
        assertEquals(1, Thermal.threads(1, ThermalState.CRITICAL))
        // Unknown engine choice: left to the engine.
        assertEquals(null, Thermal.threads(0, ThermalState.SEVERE))
    }

    @Test
    fun anUnknownChipUsesTheRecipeOfItsSeries() {
        // SM7450 is not in the table; its series is, through a family recipe.
        val series = Recipe(
            socFamily = "snapdragon-7-series",
            rankings = listOf(Ranking(ModelSize("qwen3", 1.7), listOf(RecipeChoice("llamacpp", Accel.CPU, ModelFormat.GGUF, "q4_0")))),
        )
        val sm7450 = device.copy(soc = "SM7450", socFamily = ai.fanak.den.core.device.SocFamilies.of("SM7450"))
        val first = router(listOf(llama()), listOf(series)).plan(model, sm7450).first()
        assertEquals(Source.RECIPE_FAMILY to q40, first.source to first.variant)
    }

    // A server on the network: no RAM or disk on the phone, no download.
    private class Remote(override val id: String, val where: ai.fanak.den.core.backend.Location) : DenBackend {
        val loaded = mutableListOf<String>()
        override val about = ai.fanak.den.core.backend.BackendDescriptor(
            id, id, "1", "test", "MIT", "https://example.org", ai.fanak.den.core.backend.SupportTier.EXPERIMENTAL,
            setOf(ai.fanak.den.core.backend.BackendMode.CHAT), setOf(ModelFormat.GGUF), location = where,
        )
        override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF))
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
            loaded += artifact.variant.quant
            return object : Session {
                override fun generate(req: GenRequest): Flow<GenEvent> = emptyFlow()
                override fun unload() {}
            }
        }
        override fun health() = Health.Ok
    }

    private fun policyRouter(policy: RoutingPolicy, online: Boolean = true, local: DenBackend = llama(), s: ModelStore = store) = Router(
        listOf(Remote("cloud", ai.fanak.den.core.backend.Location.CLOUD), local, Remote("lan", ai.fanak.den.core.backend.Location.LAN)),
        InMemoryRecipeStore(emptyList()), catalog, s, policy = policy, online = { online },
    )

    @Test
    fun localOnlyIsTheDefaultAndKeepsEverythingOnThePhone() {
        val plan = policyRouter(RoutingPolicy.LOCAL_ONLY).plan(model, device)
        assertTrue(plan.isNotEmpty() && plan.all { it.location == ai.fanak.den.core.backend.Location.DEVICE })
        // The constructor's default.
        assertTrue(Router(listOf(Remote("lan", ai.fanak.den.core.backend.Location.LAN)), InMemoryRecipeStore(emptyList()), catalog, store).plan(model, device).isEmpty())
    }

    @Test
    fun localFirstAddsTheNetworkAfterThePhoneLanBeforeCloud() = runTest {
        val plan = policyRouter(RoutingPolicy.LOCAL_FIRST).plan(model, device).map { it.location }
        val firstRemote = plan.indexOfFirst { it != ai.fanak.den.core.backend.Location.DEVICE }
        assertTrue(plan.take(firstRemote).all { it == ai.fanak.den.core.backend.Location.DEVICE })
        assertEquals(ai.fanak.den.core.backend.Location.LAN, plan[firstRemote])
        assertTrue(plan.indexOfFirst { it == ai.fanak.den.core.backend.Location.CLOUD } > plan.indexOfLast { it == ai.fanak.den.core.backend.Location.LAN })

        // Nothing fits on the phone: the LAN server runs it, with no download to the phone.
        var fetches = 0
        val counting = object : ModelStore { override suspend fun fetch(variant: Variant) = Artifact(variant, "/m").also { fetches++ } }
        val opened = policyRouter(RoutingPolicy.LOCAL_FIRST, local = llama(failOn = setOf(Accel.CPU, Accel.GPU)), s = counting).open(model, device.copy(availRamMb = 6_000))
        assertEquals("lan", opened.candidate.backend.id)
        assertEquals(opened.failures.size, fetches)
    }

    @Test
    fun aRefusedDownloadStillLeavesTheLanServer() = runTest {
        var fetches = 0
        val refusing = object : ModelStore {
            override suspend fun fetch(variant: Variant): Artifact {
                fetches++
                throw ai.fanak.den.core.model.DownloadRefusedException("not approved")
            }
        }
        val opened = policyRouter(RoutingPolicy.LOCAL_FIRST, s = refusing).open(model, device)
        assertEquals("lan", opened.candidate.backend.id)
        // One download asked, the other files of the phone skipped.
        assertEquals(1, fetches)
        assertEquals("not approved", opened.failures.single().error.message)
    }

    @Test
    fun eachHintPicksBetweenPhoneLanAndCloud() {
        val dev = ai.fanak.den.core.backend.Location.DEVICE
        val lan = ai.fanak.den.core.backend.Location.LAN
        val cloud = ai.fanak.den.core.backend.Location.CLOUD
        val r = policyRouter(RoutingPolicy.REMOTE_FIRST)
        fun where(hint: RouteHint?, reach: Set<ai.fanak.den.core.backend.Location>? = null) =
            r.plan(model, device, reach = hint?.limit(reach) ?: reach).map { it.location }.distinct()
        assertEquals(listOf(lan, cloud, dev), where(null))
        assertEquals(listOf(dev), where(RouteHint.PRIVATE))
        assertEquals(listOf(lan, dev), where(RouteHint.CHEAP))
        // A hint never widens what the user allowed.
        assertEquals(listOf(dev), where(RouteHint.CHEAP, setOf(dev)))
        assertEquals(listOf(dev), where(RouteHint.PRIVATE, setOf(dev, lan, cloud)))
    }

    @Test
    fun aHotPhoneMovesToTheLanWhenAllowed() {
        val dev = ai.fanak.den.core.backend.Location.DEVICE
        val lan = ai.fanak.den.core.backend.Location.LAN
        val cloud = ai.fanak.den.core.backend.Location.CLOUD
        val r = policyRouter(RoutingPolicy.LOCAL_FIRST)
        val hot = device.copy(thermal = ThermalState.SEVERE)
        assertEquals(listOf(dev, lan, cloud), r.plan(model, device).map { it.location }.distinct())
        assertEquals(listOf(lan, dev, cloud), r.plan(model, hot).map { it.location }.distinct())
        // Not allowed off the phone: it stays there, hot or not.
        assertEquals(listOf(dev), r.plan(model, hot, reach = setOf(dev)).map { it.location }.distinct())
        assertEquals(listOf(dev), policyRouter(RoutingPolicy.LOCAL_ONLY).plan(model, hot).map { it.location }.distinct())
    }

    @Test
    fun aReachOnThePhoneLeavesTheServersOut() = runTest {
        val phone = setOf(ai.fanak.den.core.backend.Location.DEVICE)
        val r = policyRouter(RoutingPolicy.REMOTE_FIRST)
        val plan = r.plan(model, device, reach = phone)
        assertTrue(plan.isNotEmpty() && plan.all { it.location == ai.fanak.den.core.backend.Location.DEVICE })
        // Nothing runs on the phone: the open fails instead of going to the LAN server.
        val none = policyRouter(RoutingPolicy.LOCAL_FIRST, local = llama(failOn = setOf(Accel.CPU, Accel.GPU)))
        assertFailsWith<Exception> { none.open(model, device.copy(availRamMb = 6_000), reach = phone) }
        val lan = phone + ai.fanak.den.core.backend.Location.LAN
        assertTrue(r.plan(model, device, reach = lan).none { it.location == ai.fanak.den.core.backend.Location.CLOUD })
    }

    @Test
    fun remoteFirstPutsTheNetworkFirstAndOfflineDropsIt() {
        val plan = policyRouter(RoutingPolicy.REMOTE_FIRST).plan(model, device)
        assertEquals(ai.fanak.den.core.backend.Location.LAN, plan.first().location)
        val offline = policyRouter(RoutingPolicy.REMOTE_FIRST, online = false).plan(model, device)
        assertTrue(offline.isNotEmpty() && offline.all { it.location == ai.fanak.den.core.backend.Location.DEVICE })
    }

    @Test
    fun theRouteSaysWhatItLeftOutAndWhy() = runTest {
        val q2 = Variant(model, ModelFormat.GGUF, "q2_k", 700, 900, arch = "qwen3", params = size)
        val nc = q40.copy(quant = "q5_k_m")
        val cat = object : ModelCatalog {
            override fun variants(model: String) = listOf(q4km, q40, npu, huge, q2, nc)
            override fun remote(variant: Variant) = ai.fanak.den.core.model.RemoteFile("f", "u", "s", 1, "p", if (variant == nc) "cc-by-nc-4.0" else "mit")
        }
        val r = Router(listOf(llama()), InMemoryRecipeStore(emptyList()), cat, store, allowLicence = { it != "cc-by-nc-4.0" })
        val other = device.copy(soc = "SM7325")
        val first = r.open(model, other).candidate
        r.reportFailure(other, first, EngineCrashedException("died"))

        val why = r.explain(model, other).rejected.associate { (it.quant to it.accel) to it.reason }
        assertEquals(RejectReason.OTHER_SOC, why["int4" to null])
        assertEquals(RejectReason.RAM, why["f16" to null])
        assertEquals(RejectReason.LOW_BIT, why["q2_k" to null])
        assertEquals(RejectReason.LICENCE, why["q5_k_m" to null])
        assertEquals(RejectReason.CRASHED, why[first.variant.quant to first.accel])

        // The model that opens next carries the same reasons.
        assertEquals(r.explain(model, other).rejected, r.open(model, other).rejected)
    }
}