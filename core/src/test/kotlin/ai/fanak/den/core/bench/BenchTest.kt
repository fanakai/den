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
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.RecipeFiles
import ai.fanak.den.core.router.Candidate
import ai.fanak.den.core.router.Source
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchTest {
    private val q40 = Variant("m", ModelFormat.GGUF, "q4_0", 400, 600, arch = "qwen3", params = 596_049_920L)
    private val q4km = Variant("m", ModelFormat.GGUF, "q4_k_m", 420, 620, arch = "qwen3", params = 596_049_920L)

    // Prefill and decode in ms for 300 prompt and 100 output tokens, and the text, per (accel, quant).
    private class Fake(val speeds: Map<Pair<Accel, String>, Triple<Long, Long, String>>) : DenBackend {
        override val id = "llamacpp"
        override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU, Accel.GPU), setOf(ModelFormat.GGUF))
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
            val (prefill, decode, text) = speeds[opts.accel to artifact.variant.quant] ?: error("load failed")
            return object : Session {
                override fun generate(req: GenRequest) = flow {
                    emit(GenEvent.Text(text))
                    emit(GenEvent.Done(GenStats(300, 100, prefill, decode)))
                }
                override fun unload() {}
            }
        }
        override fun health() = Health.Ok
    }

    private val store = object : ModelStore {
        override suspend fun fetch(variant: Variant) = Artifact(variant, "/m/${variant.quant}")
    }

    private fun candidates(b: DenBackend) = listOf(
        Candidate(b, Accel.GPU, q40, Source.DEFAULT),
        Candidate(b, Accel.CPU, q40, Source.DEFAULT),
        Candidate(b, Accel.CPU, q4km, Source.DEFAULT),
        Candidate(b, Accel.GPU, q4km, Source.DEFAULT),
    )

    private fun result(c: List<CandidateResult>) = BenchResult(
        "m", "qwen3", 596_049_920L, "ZTE Z2357N", "T760", "unisoc-t760", "2026-11-20",
        "den bench, 0.14.0-d2462f8f7, automatic threads, 289-token prompt, median of 5 runs, battery 22% charging, thermal NONE",
        Conditions(), Conditions(), c,
    )

    @Test
    fun ranksByDecodeThenPrefillWithinFivePercent() = runTest {
        val fox = "The fox digs a den."
        val b = Fake(
            mapOf(
                // decode tok/s: CPU q4_0 10.0, CPU q4_k_m 10.3 (tie, slower prefill), GPU q4_0 5.0
                (Accel.CPU to "q4_0") to Triple(1_500L, 10_000L, fox),
                (Accel.CPU to "q4_k_m") to Triple(2_000L, 9_700L, fox),
                (Accel.GPU to "q4_0") to Triple(500L, 20_000L, fox),
                // wrong output on the GPU
                (Accel.GPU to "q4_k_m") to Triple(500L, 5_000L, "!!!!"),
            ),
        )
        val results = Bench(store, runs = 3, sha256 = { "sha-${it.quant}" }).run(candidates(b))
        val bad = results.single { it.accel == Accel.GPU && it.quant == "q4_k_m" }
        assertTrue(bad.error!!.startsWith("output differs from the CPU reference"))
        assertEquals(1.0, results.single { it.accel == Accel.GPU && it.quant == "q4_0" }.match)
        assertNull(results.single { it.accel == Accel.CPU && it.quant == "q4_0" }.match)

        val ranked = result(results).ranked().map { it.accel to it.quant }
        assertEquals(listOf(Accel.CPU to "q4_0", Accel.CPU to "q4_k_m", Accel.GPU to "q4_0"), ranked)
    }

    @Test
    fun writesARecipeThatParsesStrictly() = runTest {
        val b = Fake(mapOf((Accel.CPU to "q4_0") to Triple(1_500L, 10_000L, "fox")))
        val sha = "33bcc57074ec7b6eada5a90651ee546ec0c2b271002c22baf9f1b2dd1e8f75cb"
        val results = Bench(store, runs = 5, sha256 = { sha }).run(candidates(b))
        assertEquals(3, results.count { it.error != null })

        val recipe = result(results).recipe()!!
        val text = RecipeFiles.encode(recipe)
        // Written as the hand-made files: schema first, no quotes, no empty fields.
        assertTrue(text.startsWith("schema: 1\nsoc_family: unisoc-t760\n"), text)
        assertTrue('"' !in text && "null" !in text && "avoid" !in text, text)
        val parsed = RecipeFiles.parse(text)
        assertEquals(recipe, parsed)
        val ranking = parsed.rankings.single()
        assertEquals(0.6, ranking.model.paramsB)
        assertEquals(200.0, ranking.measured!!.prefillTps)
        assertEquals(10.0, ranking.measured!!.decodeTps)
        assertEquals(sha, ranking.measured!!.sha256)
    }

    @Test
    fun stopsWhenAConditionNoLongerHolds() = runTest {
        val b = Fake(mapOf((Accel.CPU to "q4_0") to Triple(1_500L, 10_000L, "<think>\n\n</think>\n\nfox")))
        var checks = 0
        val e = assertFailsWith<IllegalStateException> {
            Bench(store, runs = 1, check = { if (++checks > 1) "the screen is off" else null }).run(candidates(b))
        }
        assertEquals("den bench stopped: the screen is off", e.message)
        assertEquals("fox", Bench.reply("<think>\n\n</think>\n\nfox"))
    }

    @Test
    fun spreadTakesTheMedian() {
        assertEquals(Spread(3.0, 1.0, 9.0), Spread.of(listOf(9.0, 1.0, 3.0)))
        assertEquals(Spread(2.5, 1.0, 9.0), Spread.of(listOf(9.0, 1.0, 2.0, 3.0)))
    }
}
