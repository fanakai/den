package ai.fanak.den.service

import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.Timeout
import com.openai.errors.UnauthorizedException
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.io.IOException
import java.net.Socket
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

class OpenAiServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadDelayMs = 0L

    // Repeats the last message word by word, after an optional reasoning block.
    private val echo: Chat = { req: GenRequest ->
        flow {
            val words = req.messages.last().content.split(" ")
            words.forEach { emit(GenEvent.Text("$it ")) }
            emit(GenEvent.Done(GenStats(words.size, words.size, 1, 1)))
        }
    }

    private val server = OpenAiServer(
        scope,
        models = { listOf("echo") },
        use = { name, block ->
            delay(loadDelayMs)
            if (name.startsWith("hf:missing")) throw IOException("HTTP 401 for the hub")
            block(echo)
        },
        client = { token -> if (token == TOKEN) "test" else null },
        log = {},
        systemInfo = { kotlinx.serialization.json.buildJsonObject { put("backends", kotlinx.serialization.json.JsonPrimitive("llamacpp")) } },
    ).apply { start(0) }

    private fun client(readTimeout: Duration = Duration.ofSeconds(10)): OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(TOKEN)
        .baseUrl("http://127.0.0.1:${server.port}/v1")
        .timeout(Timeout.builder().read(readTimeout).request(Duration.ofSeconds(60)).build())
        .maxRetries(0)
        .build()

    private fun params(text: String) = ChatCompletionCreateParams.builder()
        .model("echo")
        .addUserMessage(text)
        .maxCompletionTokens(16)
        .build()

    // A raw request, for what the OpenAI client cannot send.
    private fun raw(request: String): String = Socket("127.0.0.1", server.port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(request.toByteArray())
        s.getInputStream().readBytes().decodeToString()
    }

    private fun post(body: String, extraHeaders: String = "") = raw(
        "POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $TOKEN\r\n$extraHeaders" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body",
    )

    @AfterTest
    fun stop() {
        server.stop()
        scope.cancel()
    }

    @Test
    fun systemInfoNeedsATokenAndGivesTheJson() {
        val ok = raw("GET /den/v1/system-info HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $TOKEN\r\n\r\n")
        assertTrue(ok.startsWith("HTTP/1.1 200") && ok.endsWith("""{"backends":"llamacpp"}"""), ok)
        assertTrue(raw("GET /den/v1/system-info HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 401"))
    }

    @Test
    fun listsModelsToTheOpenAiClient() {
        assertEquals(listOf("echo"), client().models().list().data().map { it.id() })
    }

    @Test
    fun answersTheOpenAiClient() {
        val c = client().chat().completions().create(params("hello from the test"))
        assertEquals("hello from the test ", c.choices()[0].message().content().get())
        assertEquals("stop", c.choices()[0].finishReason().asString())
        assertEquals(4L, c.usage().get().completionTokens())
    }

    @Test
    fun streamsToTheOpenAiClient() {
        val text = StringBuilder()
        client().chat().completions().createStreaming(params("one two three")).use { s ->
            s.stream().forEach { chunk -> chunk.choices().forEach { ch -> ch.delta().content().ifPresent { text.append(it) } } }
        }
        assertEquals("one two three ", text.toString())
    }

    @Test
    fun keepAlivesCarryAStreamThroughASlowLoad() {
        // A 5 s load against a 4 s read timeout: only the early header and the keep-alives keep the client waiting.
        loadDelayMs = 5_000
        val text = StringBuilder()
        client(readTimeout = Duration.ofSeconds(4)).chat().completions().createStreaming(params("slow load")).use { s ->
            s.stream().forEach { chunk -> chunk.choices().forEach { ch -> ch.delta().content().ifPresent { text.append(it) } } }
        }
        assertEquals("slow load ", text.toString())
    }

    @Test
    fun refusesAnUnknownToken() {
        val c = OpenAIOkHttpClient.builder().apiKey("wrong").baseUrl("http://127.0.0.1:${server.port}/v1").maxRetries(0).build()
        assertFailsWith<UnauthorizedException> { c.models().list() }
    }

    @Test
    fun refusesFieldsItWouldIgnore() {
        val msg = """"messages":[{"role":"user","content":"hi"}]"""
        val tools = """"tools":[{"type":"function","function":{"name":"f","parameters":{}}}]"""
        assertTrue(post("""{"model":"echo",$msg,$tools}""").startsWith("HTTP/1.1 400"))
        assertTrue(post("""{"model":"echo",$msg,"response_format":{"type":"json_object"}}""").startsWith("HTTP/1.1 400"))
        assertTrue(post("""{"model":"echo",$msg,"n":2}""").startsWith("HTTP/1.1 400"))
        assertTrue(post("""{"model":"echo",$msg,"response_format":{"type":"text"},"tools":[]}""").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun refusesChunkedBodies() {
        val r = raw("POST /v1/chat/completions HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 411"), r)
    }

    @Test
    fun limitsHeaders() {
        val many = (1..70).joinToString("") { "X-$it: v\r\n" }
        assertTrue(raw("GET /v1/models HTTP/1.1\r\n$many\r\n").startsWith("HTTP/1.1 431"))
        assertTrue(raw("GET /v1/models HTTP/1.1\r\nX-Long: ${"a".repeat(9000)}\r\n\r\n").startsWith("HTTP/1.1 431"))
    }

    @Test
    fun limitsConnections() {
        // Eight clients that connect and send nothing hold every slot; the ninth is refused at once.
        val idle = (1..8).map { Socket("127.0.0.1", server.port) }
        try {
            Thread.sleep(300)
            assertTrue(raw("GET /v1/models HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n").startsWith("HTTP/1.1 503"))
        } finally {
            idle.forEach { it.close() }
        }
    }

    @Test
    fun separatesReasoning() {
        val r = post("""{"model":"echo","messages":[{"role":"user","content":"<think>why</think> answer"}]}""")
        assertTrue(""""content":"answer """" in r, r)
        assertTrue(""""reasoning_content":"why"""" in r, r)
    }

    @Test
    fun reportsALoadErrorInTheStream() {
        val r = post("""{"model":"hf:missing/repo","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
        assertTrue(r.startsWith("HTTP/1.1 200"), r)
        assertTrue("cannot load hf:missing/repo: HTTP 401 for the hub" in r, r)
    }

    private companion object {
        const val TOKEN = "den-test-token"
    }
}
