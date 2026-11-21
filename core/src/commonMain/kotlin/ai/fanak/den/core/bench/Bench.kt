package ai.fanak.den.core.bench

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.Measurement
import ai.fanak.den.core.recipe.ModelSize
import ai.fanak.den.core.recipe.Ranking
import ai.fanak.den.core.recipe.Recipe
import ai.fanak.den.core.recipe.RecipeChoice
import ai.fanak.den.core.router.Candidate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

@Serializable
data class Spread(val median: Double, val min: Double, val max: Double) {
    companion object {
        fun of(xs: List<Double>): Spread {
            val s = xs.sorted()
            val mid = s.size / 2
            val median = if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
            return Spread(median.round1(), s.first().round1(), s.last().round1())
        }
    }
}

// One candidate: a file on a backend and accelerator. Speeds in tokens per second.
@Serializable
data class CandidateResult(
    val backend: String,
    val accel: Accel,
    val format: ModelFormat,
    val quant: String,
    val engine: String? = null,
    val sha256: String? = null,
    val loadMs: Long? = null,
    val ttftMs: Spread? = null,
    val prefillTps: Spread? = null,
    val prefillLongTps: Spread? = null,
    val decodeTps: Spread? = null,
    val promptTokens: Int = 0,
    val longPromptTokens: Int = 0,
    val outputTokens: Int = 0,
    val peakRamMb: Int? = null,
    // Reply to the copy check, and the share of the CPU reply it reproduces; null without a CPU reference.
    val sample: String? = null,
    val match: Double? = null,
    val error: String? = null,
) {
    val ok get() = error == null && decodeTps != null && prefillTps != null
    val choice get() = RecipeChoice(backend, accel, format, quant)
}

// Battery and heat at the start or the end of a benchmark: both change the numbers on a phone.
@Serializable
data class Conditions(val batteryPct: Int? = null, val charging: Boolean? = null, val thermal: String? = null)

@Serializable
data class BenchResult(
    val model: String,
    val arch: String?,
    val params: Long?,
    val device: String,
    val soc: String,
    val socFamily: String,
    val date: String,
    val protocol: String,
    val start: Conditions,
    val end: Conditions,
    val candidates: List<CandidateResult>,
) {
    // Fastest decode first; a choice within 5% of the best decode is ranked by prefill instead.
    fun ranked(): List<CandidateResult> {
        val left = candidates.filter { it.ok }.toMutableList()
        val out = mutableListOf<CandidateResult>()
        while (left.isNotEmpty()) {
            val best = left.maxOf { it.decodeTps!!.median }
            val pick = left.filter { it.decodeTps!!.median >= best * (1 - TIE) }.maxBy { it.prefillTps!!.median }
            out += pick
            left -= pick
        }
        return out
    }

    // A recipe with one ranking, for this model's size; null when the size is unknown or nothing ran.
    fun recipe(): Recipe? {
        val ranked = ranked()
        val first = ranked.firstOrNull() ?: return null
        if (arch == null || params == null) return null
        val measured = Measurement(
            first.prefillTps!!.median, first.decodeTps!!.median, device, date, first.peakRamMb, protocol, first.sha256,
        )
        val size = ModelSize(arch, (params / 1e8).roundToInt() / 10.0)
        return Recipe(socFamily = socFamily, soc = soc, rankings = listOf(Ranking(size, ranked.map { it.choice }, measured)))
    }

    // Every candidate with its numbers, failures included: kept beside the recipe.
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val TIE = 0.05
        private val JSON = Json { prettyPrint = true }
    }
}

/**
 * Measures each candidate the router would try: load time, time to first token, prefill on a short and a long
 * prompt, and decode, with greedy decoding over [runs] runs after a warm-up. A candidate on another accelerator
 * whose output differs from the CPU output of the same file is recorded as failed.
 */
class Bench(
    private val store: ModelStore,
    private val runs: Int = 5,
    private val contextLength: Int = 4096,
    // SHA-256 of a variant's file, for the recipe.
    private val sha256: (Variant) -> String? = { null },
    // Resident memory of the engine now, in MB; sampled while a candidate runs.
    private val ramMb: () -> Int? = { null },
    private val onProgress: (String) -> Unit = {},
    // Why the numbers would no longer hold (screen off, power saving), checked before each candidate.
    private val check: () -> String? = { null },
    private val clock: () -> Long = monotonicMillis(),
    private val outputTokens: Int = OUTPUT_TOKENS,
    // The full protocol; without it, no warm-up and no long prompt (the first-run benchmark).
    private val full: Boolean = true,
    // The error behind a failed candidate, e.g. an engine crash for the router's failure store.
    private val onFailure: (Candidate, Throwable) -> Unit = { _, _ -> },
) {
    init {
        require(runs >= 1)
    }

    suspend fun run(candidates: List<Candidate>): List<CandidateResult> {
        val reference = mutableMapOf<Variant, String>()
        // The CPU runs first, so each file has its reference output before the other accelerators run.
        return candidates.sortedBy { it.accel != Accel.CPU }.map { c ->
            check()?.let { throw IllegalStateException("den bench stopped: $it") }
            val label = "${c.backend.id} ${c.accel} ${c.variant.quant}"
            onProgress("bench $label")
            val r = measure(c, reference[c.variant])
            if (c.accel == Accel.CPU && r.result.error == null) r.text?.let { reference[c.variant] = it }
            onProgress("bench $label: ${r.result.error ?: "decode ${r.result.decodeTps?.median} tok/s"}")
            r.result
        }
    }

    private class Measured(val result: CandidateResult, val text: String? = null)

    private suspend fun measure(c: Candidate, ref: String?): Measured {
        val base = CandidateResult(
            c.backend.id, c.accel, c.variant.format, c.variant.quant,
            engine = runCatching { c.backend.version }.getOrNull(), sha256 = sha256(c.variant),
        )
        var peak: Int? = null
        return try {
            val artifact = store.fetch(c.variant)
            coroutineScope {
                val sampler = launch {
                    while (isActive) {
                        ramMb()?.let { m -> peak = maxOf(peak ?: 0, m) }
                        delay(250)
                    }
                }
                try {
                    val t0 = clock()
                    val session = c.backend.load(artifact, LoadOptions(c.accel, minOf(contextLength, c.variant.maxContext ?: contextLength)))
                    val loadMs = clock() - t0
                    try {
                        run(session, base.copy(loadMs = loadMs), ref)
                    } finally {
                        session.unload()
                    }
                } finally {
                    sampler.cancel()
                }
            }.let { Measured(it.result.copy(peakRamMb = peak), it.text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailure(c, e)
            Measured(base.copy(error = e.message ?: e.toString()))
        }
    }

    private suspend fun run(session: Session, base: CandidateResult, ref: String?): Measured {
        if (full) generate(session, SHORT, 16)
        val text = reply(generate(session, CHECK, CHECK_TOKENS).text)
        val short = mutableListOf<Pair<Long, GenStats>>()
        val long = mutableListOf<GenStats>()
        repeat(runs) {
            val s = generate(session, SHORT, outputTokens)
            short += s.ttftMs to s.stats
            if (full) long += generate(session, LONG, 1).stats
        }
        val match = ref?.let { matchShare(text, it) }
        val stats = short.map { it.second }
        val result = base.copy(
            ttftMs = Spread.of(short.map { it.first.toDouble() }),
            prefillTps = Spread.of(stats.map { it.prefillTps }),
            prefillLongTps = if (long.isEmpty()) null else Spread.of(long.map { it.prefillTps }),
            decodeTps = Spread.of(stats.map { it.decodeTps }),
            promptTokens = stats.first().promptTokens,
            longPromptTokens = long.firstOrNull()?.promptTokens ?: 0,
            outputTokens = stats.first().outputTokens,
            sample = text,
            match = match?.round2(),
            error = match?.takeIf { it < MIN_MATCH }?.let { "output differs from the CPU reference (${(it * 100).roundToInt()}% matches)" },
        )
        return Measured(result, text)
    }

    private class Generated(val ttftMs: Long, val stats: GenStats, val text: String)

    private suspend fun generate(session: Session, prompt: String, maxTokens: Int): Generated {
        val t0 = clock()
        var first: Long? = null
        val text = StringBuilder()
        var stats: GenStats? = null
        session.generate(GenRequest(listOf(ChatMessage(Role.USER, prompt)), maxTokens, temperature = 0f, cache = false)).collect { e ->
            when (e) {
                is GenEvent.Text -> {
                    if (first == null) first = clock() - t0
                    text.append(e.text)
                }
                is GenEvent.Done -> stats = e.stats
            }
        }
        return Generated(first ?: (clock() - t0), checkNotNull(stats) { "no stats from the engine" }, text.toString())
    }

    companion object {
        const val OUTPUT_TOKENS = 128

        // Below this share of the reference output, the candidate computes something else.
        const val MIN_MATCH = 0.5

        // The correctness check: a copy task, whose greedy answer does not change with small rounding differences
        // between accelerators, while wrong arithmetic gives another text.
        const val CHECK = "Repeat this sentence exactly, and nothing else: " +
            "The fennec fox lives in the Sahara and digs dens in the sand to escape the heat of the day. /no_think"
        const val CHECK_TOKENS = 48

        // The reply without an empty or filled <think> block.
        fun reply(text: String) = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()

        // About 300 tokens, as in the earlier recipes; the reply runs to the token limit.
        val SHORT = buildString {
            append("Read these notes, then write a long story about the fox, in at least 300 words. /no_think\n")
            repeat(11) { append("The fennec fox lives in the Sahara and digs dens in the sand to escape the heat of the day. ") }
        }

        // About 1,000 tokens, for prefill on a longer context.
        val LONG = buildString {
            append("Summarize the following notes in one sentence. /no_think\n")
            repeat(45) { append("The fennec fox lives in the Sahara and digs dens in the sand to escape the heat of the day. ") }
        }

        // Share of the reference that the text reproduces from the start, in characters.
        fun matchShare(text: String, ref: String): Double =
            if (ref.isEmpty()) 1.0 else text.commonPrefixWith(ref).length.toDouble() / ref.length
    }
}

// Milliseconds since this call, from a monotonic clock: only differences are measured.
private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

private fun Double.round1() = (this * 10).roundToInt() / 10.0

private fun Double.round2() = (this * 100).roundToInt() / 100.0
