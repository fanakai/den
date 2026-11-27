package ai.fanak.den.core.router

import ai.fanak.den.core.backend.GenStats
import kotlin.concurrent.Volatile

// What the requests on one path gave: decode speed and time to the first token, medians of the last few.
data class Speed(val decodeTps: Double, val firstTokenMs: Long, val requests: Int)

// The speeds of the paths that ran on this phone, by backend (each endpoint has its own), accelerator and file.
interface SpeedStore {
    fun record(candidate: Candidate, stats: GenStats)
    fun of(candidate: Candidate): Speed?
}

class InMemorySpeedStore(private val keep: Int = 5) : SpeedStore {
    private class Sample(val tps: Double, val firstMs: Long)

    @Volatile private var samples: Map<String, List<Sample>> = emptyMap()

    override fun record(candidate: Candidate, stats: GenStats) {
        // A reply of a few tokens says little about decode speed.
        if (stats.outputTokens < MIN_TOKENS || stats.decodeMs <= 0) return
        val k = key(candidate)
        val s = Sample(stats.decodeTps, stats.prefillMs)
        // Replaced as a whole: two requests ending at once may lose one sample, which only delays the median.
        samples = samples + (k to ((samples[k] ?: emptyList()) + s).takeLast(keep))
    }

    override fun of(candidate: Candidate): Speed? {
        val s = samples[key(candidate)] ?: return null
        return Speed(median(s.map { it.tps }), median(s.map { it.firstMs.toDouble() }).toLong(), s.size)
    }

    private fun key(c: Candidate) = "${c.backend.id}/${c.accel}/${c.variant.model}/${c.variant.format}/${c.variant.quant}"

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    private companion object {
        const val MIN_TOKENS = 16
    }
}
