package ai.fanak.den.core.bench

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
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
import ai.fanak.den.core.recipe.LocalRecipeStore
import ai.fanak.den.core.recipe.RecipeFiles
import ai.fanak.den.core.router.EngineCrashedException
import ai.fanak.den.core.router.InMemoryFailureStore
import ai.fanak.den.core.router.Router
import ai.fanak.den.core.router.Source
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirstRunTest {
    private val device = DeviceProfile(Platform.ANDROID, 35, "T760", "unisoc-t760", "arm64-v8a", 8_000, 4_000)
    private val q4km = Variant("m", ModelFormat.GGUF, "q4_k_m", 400, 600, arch = "qwen3", params = 596_049_920L)
    private val q40 = Variant("m", ModelFormat.GGUF, "q4_0", 380, 580, arch = "qwen3", params = 596_049_920L)
    private val big = Variant("big", ModelFormat.GGUF, "q4_k_m", 2_500, 3_000, arch = "qwen3", params = 4_022_468_096L)

    // Decode ms for 100 tokens per accelerator, and the text; a missing accelerator crashes the engine.
    private class Fake(val decodeMs: Map<Accel, Long>, val text: Map<Accel, String> = emptyMap()) : DenBackend {
        override val id = "llamacpp"
        val loads = mutableListOf<Accel>()
        override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU, Accel.GPU), setOf(ModelFormat.GGUF))
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
            loads += opts.accel
            val ms = decodeMs[opts.accel] ?: throw EngineCrashedException("engine died on ${opts.accel}")
            return object : Session {
                override fun generate(req: GenRequest) = flow {
                    emit(GenEvent.Text(text[opts.accel] ?: "fox"))
                    emit(GenEvent.Done(GenStats(300, 100, 1_000, ms)))
                }
                override fun unload() {}
            }
        }
        override fun health() = Health.Ok
    }

    private val catalog = object : ModelCatalog {
        override fun variants(model: String) = if (model == "big") listOf(big) else listOf(q4km, q40)
    }
    private val store = object : ModelStore {
        override suspend fun fetch(variant: Variant) = Artifact(variant, "/m/${variant.quant}")
    }

    private fun setup(b: Fake, dir: File = createTempDirectory().toFile()): Triple<Router, LocalRecipeStore, InMemoryFailureStore> {
        val local = LocalRecipeStore(dir)
        val failures = InMemoryFailureStore()
        val first = FirstRunBench(store, local, "ZTE Z2357N", today = { "2026-11-21" })
        return Triple(Router(listOf(b), InMemoryRecipeStore(emptyList()), catalog, store, failures, local, first), local, failures)
    }

    @Test
    fun firstOpenMeasuresTheFileOnEachAcceleratorAndKeepsTheFastest() = runTest {
        // GPU decodes twice as fast as the CPU, which the default order tries first.
        val b = Fake(mapOf(Accel.CPU to 10_000L, Accel.GPU to 5_000L))
        val dir = createTempDirectory().toFile()
        val (router, _) = setup(b, dir)

        val opened = router.open("m", device)
        assertEquals(Accel.GPU, opened.candidate.accel)
        assertEquals(Source.RECIPE_LOCAL, opened.candidate.source)
        assertEquals(q4km, opened.candidate.variant)
        // CPU and GPU measured, then the GPU loaded for use.
        assertEquals(listOf(Accel.CPU, Accel.GPU, Accel.GPU), b.loads)

        val saved = RecipeFiles.parse(File(dir, "T760.yaml").readText())
        assertEquals(listOf(Accel.GPU, Accel.CPU), saved.rankings.single().choices.map { it.accel })
        assertEquals(20.0, saved.rankings.single().measured!!.decodeTps)
        assertTrue(saved.rankings.single().measured!!.protocol!!.startsWith("first-run benchmark"))

        // A later router, e.g. after a restart, reads the file and measures nothing.
        val b2 = Fake(mapOf(Accel.CPU to 10_000L, Accel.GPU to 5_000L))
        assertEquals(Accel.GPU, setup(b2, dir).first.open("m", device).candidate.accel)
        assertEquals(listOf(Accel.GPU), b2.loads)

        // A model of another size gets no choice from this ranking.
        assertEquals(Source.DEFAULT, router.plan("big", device).first().source)

        // After a system update the recipe is measured again.
        assertTrue(LocalRecipeStore(dir, "new build").find(device).isEmpty())
        assertTrue(!File(dir, "T760.yaml").exists())
    }

    @Test
    fun wrongOutputOrACrashLeavesTheCpu() = runTest {
        val wrong = Fake(mapOf(Accel.CPU to 10_000L, Accel.GPU to 5_000L), mapOf(Accel.GPU to "!!!!"))
        val (r1, local) = setup(wrong)
        assertEquals(Accel.CPU, r1.open("m", device).candidate.accel)
        assertEquals(listOf(Accel.CPU), local.find(device).single().rankings.single().choices.map { it.accel })

        // The GPU crash is recorded, so the router does not try it again either.
        val crash = Fake(mapOf(Accel.CPU to 10_000L))
        val (r2, _, failures) = setup(crash)
        assertEquals(Accel.CPU, r2.open("m", device).candidate.accel)
        assertTrue(r2.plan("m", device).none { it.accel == Accel.GPU && it.variant == q4km })
        assertEquals(listOf(Accel.CPU, Accel.GPU, Accel.CPU), crash.loads)
        assertTrue(failures.isBlocked(ai.fanak.den.core.router.FailureKey("T760", "llamacpp", Accel.GPU, "m", ModelFormat.GGUF, "q4_k_m", crash.version)))
    }

    @Test
    fun withOneAcceleratorThereIsNothingToMeasure() = runTest {
        val cpuOnly = object : DenBackend by Fake(mapOf(Accel.CPU to 10_000L)) {
            override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF))
        }
        val dir = createTempDirectory().toFile()
        val local = LocalRecipeStore(dir)
        val r = Router(listOf(cpuOnly), InMemoryRecipeStore(emptyList()), catalog, store, local = local, firstRun = FirstRunBench(store, local, "x"))
        assertEquals(Source.DEFAULT, r.open("m", device).candidate.source)
        assertTrue(dir.listFiles()!!.none { it.extension == "yaml" })
    }

    @Test
    fun filesOnDiskAreComparedToo() = runTest {
        // CPU only; the same decode, but Q4_0 prefills twice as fast (as on the A52s).
        val b = object : DenBackend {
            override val id = "llamacpp"
            override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF))
            override suspend fun load(artifact: Artifact, opts: LoadOptions): Session = object : Session {
                override fun generate(req: GenRequest) = flow {
                    emit(GenEvent.Text("fox"))
                    emit(GenEvent.Done(GenStats(300, 100, if (artifact.variant.quant == "q4_0") 1_000 else 2_000, 10_000)))
                }
                override fun unload() {}
            }
            override fun health() = Health.Ok
        }
        val onDisk = object : ModelStore by store {
            override fun has(variant: Variant) = true
        }
        val local = LocalRecipeStore(createTempDirectory().toFile())
        val r = Router(listOf(b), InMemoryRecipeStore(emptyList()), catalog, onDisk, local = local, firstRun = FirstRunBench(onDisk, local, "x"))
        val opened = r.open("m", device)
        assertEquals("q4_0" to Source.RECIPE_LOCAL, opened.candidate.variant.quant to opened.candidate.source)
        assertEquals(listOf("q4_0", "q4_k_m"), local.find(device).single().rankings.single().choices.map { it.quant })
    }
}
