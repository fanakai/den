package ai.fanak.den.backend.litertlm

import ai.fanak.den.core.backend.Finish
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats

/**
 * Stop strings over streamed text, which LiteRT-LM does not take: text that could still be the start of a stop string
 * is held back until the next piece shows it is not.
 */
internal class StopScan(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }
    private val held = StringBuilder()

    var hit = false
        private set

    /** What can be passed on after [piece]; null when nothing can yet. Nothing more once a stop string was found. */
    fun add(piece: String): String? {
        if (hit) return null
        held.append(piece)
        val at = stops.mapNotNull { s -> held.indexOf(s).takeIf { it >= 0 } }.minOrNull()
        if (at != null) {
            hit = true
            val out = held.substring(0, at)
            held.clear()
            return out.ifEmpty { null }
        }
        val keep = stops.maxOfOrNull { s -> (s.length - 1 downTo 1).firstOrNull { held.endsWith(s.substring(0, it)) } ?: 0 } ?: 0
        val out = held.substring(0, held.length - keep)
        held.delete(0, held.length - keep)
        return out.ifEmpty { null }
    }

    /** The text held back at the end of the reply. */
    fun rest(): String? = held.toString().also { held.clear() }.ifEmpty { null }
}

// Greedy at temperature 0, as llama.cpp does; otherwise top-p with a top-k of 40.
internal data class Sampling(val topK: Int, val topP: Double, val temperature: Double) {
    companion object {
        fun of(req: GenRequest) =
            if (req.temperature <= 0f) Sampling(1, 1.0, 1.0) else Sampling(40, req.topP.toDouble(), req.temperature.toDouble())
    }
}

internal object Stats {
    fun of(prompt: Int, output: Int, prefillMs: Long, decodeMs: Long, maxTokens: Int, stopped: Boolean) = GenStats(
        promptTokens = prompt,
        outputTokens = output,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        finish = if (!stopped && output >= maxTokens) Finish.LENGTH else Finish.STOP,
    )
}
