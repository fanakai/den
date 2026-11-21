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
    // Reuse what the session computed for the same prompt prefix (a chat's history); false prefills it all, e.g. to
    // measure prefill.
    val cache: Boolean = true,
    // Threads for this request; null lets the engine pick them for the cores it may use.
    val threads: Int? = null,
    // Constrains the reply: a GBNF grammar, or a JSON schema (as JSON text) the reply must follow.
    val grammar: String? = null,
    val jsonSchema: String? = null,
    // Functions the model may call; a backend without tool calls refuses the request.
    val tools: List<Tool> = emptyList(),
)

// A function the model may call, as OpenAI describes one: its parameters are a JSON schema, as JSON text.
@kotlinx.serialization.Serializable
data class Tool(val name: String, val description: String = "", val parameters: String = "{}")

// A request asks for something this backend does not do yet (tools, embeddings): said instead of ignored.
class UnsupportedFeatureException(val feature: String, backend: String) :
    UnsupportedOperationException("$backend does not support $feature yet")

// The features a request needs, as named in BackendDescriptor.features.
val GenRequest.features: Set<String>
    get() = buildSet {
        if (grammar != null) add(Features.GRAMMAR)
        if (jsonSchema != null) add(Features.JSON_SCHEMA)
        if (tools.isNotEmpty()) add(Features.TOOLS)
    }

// Why the reply ended: end of text or a stop string, `maxTokens`, or a full context.
enum class Finish { STOP, LENGTH, CONTEXT }

data class GenStats(
    val promptTokens: Int,
    val outputTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val finish: Finish = Finish.STOP,
    // Prompt tokens taken from the session's cache instead of prefilled.
    val cachedTokens: Int = 0,
    // Decode threads the engine used; 0 when it did not say.
    val threads: Int = 0,
) {
    val prefillTps get() = rate(promptTokens - cachedTokens, prefillMs)
    val decodeTps get() = rate(outputTokens, decodeMs)

    private fun rate(tokens: Int, ms: Long) = if (ms > 0) tokens * 1000.0 / ms else 0.0

    // [promptTokens, outputTokens, prefillMs, decodeMs, finish, cachedTokens, threads], as engines and AIDL pass them.
    fun toLongs() = longArrayOf(
        promptTokens.toLong(), outputTokens.toLong(), prefillMs, decodeMs, finish.ordinal.toLong(), cachedTokens.toLong(),
        threads.toLong(),
    )

    companion object {
        // Older senders give fewer values: without the reason, or without the cached tokens.
        fun of(a: LongArray) = GenStats(
            a[0].toInt(), a[1].toInt(), a[2], a[3],
            Finish.entries.getOrElse(a.getOrElse(4) { 0L }.toInt()) { Finish.STOP },
            a.getOrElse(5) { 0L }.toInt(),
            a.getOrElse(6) { 0L }.toInt(),
        )
    }
}

sealed interface GenEvent {
    data class Text(val text: String) : GenEvent
    data class Done(val stats: GenStats) : GenEvent
}
