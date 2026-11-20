package ai.fanak.den.app

import ai.fanak.den.DenModel
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import android.content.Context
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Off by default. Tokens are made by the user in the Den app, one per client app.
internal object HttpSettings {
    const val PORT = 8000

    private fun prefs(c: Context) = c.getSharedPreferences("http", Context.MODE_PRIVATE)
    private fun tokens(c: Context) = c.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)

    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    // token -> name
    fun all(c: Context): Map<String, String> = tokens(c).all.mapValues { it.value.toString() }

    fun name(c: Context, token: String): String? = tokens(c).getString(token, null)

    fun create(c: Context, name: String): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = "den-" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        tokens(c).edit().putString(token, name).apply()
        return token
    }

    fun revoke(c: Context, token: String) = tokens(c).edit().remove(token).apply()
}

private class HttpError(val code: Int, message: String) : Exception(message)

private const val KEEPALIVE_MS = 3_000L

// A write to the client failed; other IOExceptions (a hub, a download) are errors to report to the client.
private class ClientGone(cause: IOException) : IOException(cause)

// Minimal OpenAI-compatible server on 127.0.0.1: GET /v1/models and POST /v1/chat/completions (with streaming).
internal class HttpEndpoint(
    private val scope: CoroutineScope,
    private val models: () -> List<String>,
    private val use: suspend (String, suspend (DenModel) -> Unit) -> Unit,
    private val client: (String) -> String?,
    private val log: (String) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val s = ServerSocket(HttpSettings.PORT, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "den-http") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                scope.launch(Dispatchers.IO) {
                    try {
                        c.use { serve(it) }
                    } catch (e: IOException) {
                        log("http client gone: $e")
                    }
                }
            }
        }
        log("http on 127.0.0.1:${HttpSettings.PORT}")
    }

    fun stop() {
        val s = server ?: return
        s.close()
        server = null
        log("http off")
    }

    private suspend fun serve(sock: Socket) {
        sock.soTimeout = 30_000
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val parts = (input.line() ?: return).split(" ")
        val method = parts[0]
        val path = parts.getOrNull(1)?.substringBefore('?') ?: ""
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = input.line() ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        try {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 4 shl 20) throw HttpError(413, "request too large")
            val body = ByteArray(len).also { b -> var n = 0; while (n < len) n += input.read(b, n, len - n).also { if (it < 0) return } }
            val token = headers["authorization"]?.removePrefix("Bearer")?.trim()
            val name = token?.let(client) ?: throw HttpError(401, "missing or unknown token; create one in the Den app")
            log("http $method $path from $name")
            when {
                method == "GET" && path == "/v1/models" -> out.json(200, modelList())
                method == "POST" && path == "/v1/chat/completions" -> chat(body.decodeToString(), out)
                else -> throw HttpError(404, "not found: $method $path")
            }
        } catch (e: HttpError) {
            out.json(e.code, error(e.message ?: ""))
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fails quietly when a stream has already started.
            log("http error: $e")
            runCatching { out.json(500, error(e.message ?: e.toString())) }
        }
    }

    private fun modelList() = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            models().forEach { add(buildJsonObject { put("id", it); put("object", "model"); put("owned_by", "fanak-den") }) }
        }
    }

    private suspend fun chat(body: String, out: OutputStream) {
        val req = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw HttpError(400, "invalid JSON")
        val name = req.str("model") ?: throw HttpError(400, "model is required")
        // hf:owner/repo names are resolved on demand, so they are not in /v1/models.
        if (name !in models() && !name.startsWith("hf:")) throw HttpError(404, "unknown model $name; see /v1/models")
        val messages = (req["messages"] as? JsonArray)?.map { m ->
            val o = m.jsonObject
            val role = runCatching { Role.valueOf(o.str("role")!!.uppercase()) }.getOrNull()
                ?: throw HttpError(400, "unsupported role ${o["role"]}")
            ChatMessage(role, text(o["content"]))
        }?.takeIf { it.isNotEmpty() } ?: throw HttpError(400, "messages is required")
        val maxTokens = (req["max_completion_tokens"] ?: req["max_tokens"])?.jsonPrimitive?.intOrNull ?: 512
        val stop = when (val s = req["stop"]) {
            is JsonPrimitive -> listOfNotNull(s.contentOrNull)
            is JsonArray -> s.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> emptyList()
        }
        val gen = GenRequest(
            messages, maxTokens,
            req["temperature"]?.jsonPrimitive?.floatOrNull ?: 0.7f,
            req["top_p"]?.jsonPrimitive?.floatOrNull ?: 0.95f,
            stop,
        )
        if (req["stream"]?.jsonPrimitive?.booleanOrNull == true) {
            stream(name, gen, out)
            return
        }
        var loaded = false
        try {
            use(name) { m ->
                loaded = true
                reply(m, name, gen, out)
            }
        } catch (e: Exception) {
            // Before the load nothing is written, so an IOException here comes from the hub, not from the client.
            if (loaded || e is HttpError || e is kotlinx.coroutines.CancellationException) throw e
            throw HttpError(503, "cannot load $name: ${e.message}")
        }
    }

    private fun completionId() = "chatcmpl-" + System.nanoTime().toString(36)

    // The header goes out before the model loads, and a comment every few seconds until the first token: a first
    // load takes 10 to 35 s on a phone, and HTTP clients give up on a silent connection.
    private suspend fun stream(name: String, gen: GenRequest, out: OutputStream): Unit = coroutineScope {
        val id = completionId()
        val created = System.currentTimeMillis() / 1000
        fun chunk(delta: JsonObject, finish: String?) = buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", name)
            putJsonArray("choices") {
                add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish) })
            }
        }
        val lock = Any()
        // A failed write (client gone) throws, which cancels the load or the generation.
        fun write(text: String) = synchronized(lock) {
            try {
                out.write(text.toByteArray())
                out.flush()
            } catch (e: IOException) {
                throw ClientGone(e)
            }
        }
        fun event(data: String) = write("data: $data\n\n")

        out.write(head(200, "text/event-stream", "Cache-Control: no-cache\r\n"))
        event(chunk(buildJsonObject { put("role", "assistant"); put("content", "") }, null).toString())
        var phase = "loading"
        val beat = launch(Dispatchers.IO) {
            while (true) {
                delay(KEEPALIVE_MS)
                write(": $phase\n\n")
            }
        }
        try {
            use(name) { m ->
                phase = "prefilling"
                val split = ThinkSplitter()
                fun send(p: Pair<String, String>) {
                    if (p.first.isNotEmpty()) event(chunk(buildJsonObject { put("reasoning_content", p.first) }, null).toString())
                    if (p.second.isNotEmpty()) event(chunk(buildJsonObject { put("content", p.second) }, null).toString())
                }
                m.chat(gen).collect { e ->
                    beat.cancel()
                    when (e) {
                        is GenEvent.Text -> send(split.feed(e.text))
                        is GenEvent.Done -> {
                            send(split.flush())
                            event(chunk(JsonObject(emptyMap()), finish(e, gen.maxTokens)).toString())
                        }
                    }
                }
            }
            event("[DONE]")
        } catch (e: ClientGone) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The status line is already sent; the error goes in the stream, as OpenAI does.
            log("http stream error: $e")
            val msg = e.message ?: e.toString()
            runCatching { event(error(if (phase == "loading") "cannot load $name: $msg" else msg).toString()) }
        } finally {
            beat.cancel()
        }
    }

    private suspend fun reply(m: DenModel, name: String, gen: GenRequest, out: OutputStream) {
        val split = ThinkSplitter()
        val reasoning = StringBuilder()
        val content = StringBuilder()
        var done: GenEvent.Done? = null
        m.chat(gen).collect { e ->
            val p = if (e is GenEvent.Text) split.feed(e.text) else split.flush().also { done = e as GenEvent.Done }
            reasoning.append(p.first)
            content.append(p.second)
        }
        val s = done!!.stats
        out.json(200, buildJsonObject {
            put("id", completionId()); put("object", "chat.completion"); put("created", System.currentTimeMillis() / 1000)
            put("model", name)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content.toString())
                        if (reasoning.isNotBlank()) put("reasoning_content", reasoning.toString().trim())
                    }
                    put("finish_reason", finish(done!!, gen.maxTokens))
                })
            }
            putJsonObject("usage") {
                put("prompt_tokens", s.promptTokens)
                put("completion_tokens", s.outputTokens)
                put("total_tokens", s.promptTokens + s.outputTokens)
            }
        })
    }

    private fun finish(d: GenEvent.Done, maxTokens: Int) = if (d.stats.outputTokens >= maxTokens) "length" else "stop"

    // content is a string, or a list of parts of which only text is used.
    private fun text(c: kotlinx.serialization.json.JsonElement?): String = when (c) {
        is JsonPrimitive -> c.contentOrNull ?: ""
        is JsonArray -> c.joinToString("") { (it as? JsonObject)?.str("text") ?: "" }
        else -> ""
    }

    private fun error(message: String) = buildJsonObject {
        putJsonObject("error") { put("message", message); put("type", "invalid_request_error") }
    }
}

// Moves a leading <think>...</think> block (Qwen3 and similar) out of the reply, as llama.cpp's server does.
// Returns (reasoning, content) for each piece; an empty think block yields nothing.
internal class ThinkSplitter {
    private enum class State { START, THINK, TRIM, CONTENT }
    private var state = State.START
    private var reasoned = false
    private val buf = StringBuilder()

    fun feed(text: String): Pair<String, String> {
        buf.append(text)
        val reasoning = StringBuilder()
        val content = StringBuilder()
        while (true) {
            when (state) {
                State.START -> {
                    val t = buf.trimStart().toString()
                    when {
                        t.startsWith(OPEN) -> { buf.setLength(0); buf.append(t.removePrefix(OPEN)); state = State.THINK }
                        OPEN.startsWith(t) -> break
                        else -> state = State.CONTENT
                    }
                }
                State.THINK -> {
                    val i = buf.indexOf(CLOSE)
                    val end = if (i >= 0) i else buf.length - (CLOSE.length - 1 downTo 1).firstOrNull { buf.endsWith(CLOSE.take(it)) }.let { it ?: 0 }
                    reasoning.append(buf, 0, end)
                    buf.delete(0, if (i >= 0) i + CLOSE.length else end)
                    if (i < 0) break
                    state = State.TRIM
                }
                State.TRIM -> {
                    val t = buf.trimStart().toString()
                    buf.setLength(0)
                    if (t.isEmpty()) break
                    buf.append(t)
                    state = State.CONTENT
                }
                State.CONTENT -> {
                    content.append(buf)
                    buf.setLength(0)
                    break
                }
            }
        }
        return reasoningPart(reasoning.toString()) to content.toString()
    }

    fun flush(): Pair<String, String> {
        val rest = buf.toString()
        buf.setLength(0)
        return if (state == State.THINK) reasoningPart(rest) to "" else "" to rest
    }

    // Drops whitespace before the first real reasoning text.
    private fun reasoningPart(r: String): String {
        if (reasoned) return r
        val t = r.trimStart()
        if (t.isNotEmpty()) reasoned = true
        return t
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun InputStream.line(): String? {
    val b = ByteArrayOutputStream()
    while (true) {
        val c = read()
        if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.UTF_8.name())
        if (c == '\n'.code) return b.toString(Charsets.UTF_8.name()).trimEnd('\r')
        if (b.size() > 8192) return null
        b.write(c)
    }
}

// Bodies end when the connection closes, so no Content-Length or chunking is needed.
private fun head(code: Int, type: String, extra: String = "") =
    "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\n${extra}Connection: close\r\n\r\n".toByteArray()

private fun reason(code: Int) = when (code) {
    200 -> "OK"; 500 -> "Internal Server Error"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"
    503 -> "Service Unavailable"; else -> "Error"
}

private fun OutputStream.json(code: Int, body: JsonObject) {
    write(head(code, "application/json"))
    write(body.toString().toByteArray())
    flush()
}
