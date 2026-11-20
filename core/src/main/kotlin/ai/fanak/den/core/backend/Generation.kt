package ai.fanak.den.core.backend

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(
    val role: Role,
    val content: String,
)

data class GenRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val stop: List<String> = emptyList(),
)

// Why the reply ended: end of text or a stop string, `maxTokens`, or a full context.
enum class Finish { STOP, LENGTH, CONTEXT }

data class GenStats(
    val promptTokens: Int,
    val outputTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val finish: Finish = Finish.STOP,
) {
    val prefillTps get() = rate(promptTokens, prefillMs)
    val decodeTps get() = rate(outputTokens, decodeMs)

    private fun rate(tokens: Int, ms: Long) = if (ms > 0) tokens * 1000.0 / ms else 0.0

    // [promptTokens, outputTokens, prefillMs, decodeMs, finish], as engines and AIDL pass them.
    fun toLongs() = longArrayOf(promptTokens.toLong(), outputTokens.toLong(), prefillMs, decodeMs, finish.ordinal.toLong())

    companion object {
        // Older senders give four values, without the reason.
        fun of(a: LongArray) = GenStats(
            a[0].toInt(), a[1].toInt(), a[2], a[3],
            Finish.entries.getOrElse(a.getOrElse(4) { 0L }.toInt()) { Finish.STOP },
        )
    }
}

sealed interface GenEvent {
    data class Text(val text: String) : GenEvent
    data class Done(val stats: GenStats) : GenEvent
}
