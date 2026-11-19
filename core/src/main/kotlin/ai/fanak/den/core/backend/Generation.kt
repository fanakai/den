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

data class GenStats(
    val promptTokens: Int,
    val outputTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
) {
    val prefillTps get() = rate(promptTokens, prefillMs)
    val decodeTps get() = rate(outputTokens, decodeMs)

    private fun rate(tokens: Int, ms: Long) = if (ms > 0) tokens * 1000.0 / ms else 0.0
}

sealed interface GenEvent {
    data class Text(val text: String) : GenEvent
    data class Done(val stats: GenStats) : GenEvent
}
