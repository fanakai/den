package ai.fanak.den

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold

/**
 * A conversation on a model: keeps the messages, so each turn sends the history and the engine takes it from its
 * cache instead of prefilling it again. One turn at a time. `model.newChat()` starts one.
 */
class Chat internal constructor(
    private val model: DenModel,
    system: String?,
    private val maxTokens: Int,
    private val temperature: Float,
) {
    private val history = mutableListOf<ChatMessage>().apply { if (system != null) add(ChatMessage(Role.SYSTEM, system)) }

    val messages: List<ChatMessage> get() = synchronized(history) { history.toList() }

    // Streams the reply; it joins the history once complete. A failed or cancelled turn leaves the history as it was.
    fun send(text: String): Flow<GenEvent> = flow {
        val request = synchronized(history) { GenRequest(history + ChatMessage(Role.USER, text), maxTokens, temperature) }
        val reply = StringBuilder()
        var done = false
        model.chat(request).collect { e ->
            if (e is GenEvent.Text) reply.append(e.text)
            if (e is GenEvent.Done) done = true
            emit(e)
        }
        if (done) synchronized(history) { history += listOf(ChatMessage(Role.USER, text), ChatMessage(Role.ASSISTANT, reply.toString())) }
    }

    suspend fun reply(text: String): String =
        send(text).filterIsInstance<GenEvent.Text>().fold(StringBuilder()) { sb, t -> sb.append(t.text) }.toString()

    fun clear() = synchronized(history) { history.retainAll { it.role == Role.SYSTEM } }
}
