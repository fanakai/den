package ai.fanak.den.backend.openai

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.Finish
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.Variant
import ai.fanak.den.service.OpenAiServer
import java.io.IOException
import java.net.ServerSocket
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** The backend against Den's own OpenAI-compatible server, as it would talk to a llama-server on the LAN. */
class OpenAiBackendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var last: GenRequest? = null
    @Volatile private var served = 0

    // Repeats the last message word by word; slowly when asked, so a cancel arrives mid-stream.
    private val server = OpenAiServer(
        scope,
        models = { listOf("echo-1.7b") },
        use = { _, name, block ->
            check(name == "echo-1.7b") { "unknown model $name" }
            block { req ->
                last = req
                flow {
                    val words = req.messages.last().content.split(" ")
                    words.take(req.maxTokens).forEach {
                        if (it == "slow") delay(200)
                        served++
                        emit(GenEvent.Text("$it "))
                    }
                    val finish = if (words.size > req.maxTokens) Finish.LENGTH else Finish.STOP
                    emit(GenEvent.Done(GenStats(7, minOf(words.size, req.maxTokens), 1, 1, finish)))
                }
            }
        },
        client = { token -> if (token == TOKEN) "test" else null },
        log = {},
    ).apply { start(0) }

    private val endpoint = Endpoint("pc", "http://127.0.0.1:${server.port}/v1", Location.LAN, mapOf("qwen3-1.7b" to "echo-1.7b"), TOKEN)
    private val backend = OpenAiBackend(endpoint)
    private val variant = Variant("qwen3-1.7b", ModelFormat.GGUF, "q4_k_m", 1_100_000_000, 2_000)
    private fun load(b: OpenAiBackend = backend, v: Variant = variant) = runBlocking { b.load(Artifact(v, ""), LoadOptions(Accel.CPU)) }
    private fun ask(text: String, maxTokens: Int = 64, schema: String? = null) =
        GenRequest(listOf(ChatMessage(Role.USER, text)), maxTokens = maxTokens, jsonSchema = schema)

    @AfterTest
    fun stop() {
        stops.forEach { it() }
        server.stop()
        scope.cancel()
    }

    @Test
    fun aReplyStreamsWithItsStats() = runBlocking {
        val events = load().generate(ask("the fox digs a den")).toList()
        val text = events.filterIsInstance<GenEvent.Text>().joinToString("") { it.text }
        assertEquals("the fox digs a den ", text)
        val stats = (events.last() as GenEvent.Done).stats
        assertEquals(7, stats.promptTokens)
        assertEquals(5, stats.outputTokens)
        assertEquals(Finish.STOP, stats.finish)
        assertEquals(listOf("the fox digs a den"), last!!.messages.map { it.content })
    }

    @Test
    fun aThinkingModelsThoughtsComeInThinkTags() = runBlocking {
        // Chunks as Lemonade 2026.47.1 streams them for Qwen3-1.7B-GGUF.
        val chunks = listOf(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"index":0,"delta":{"reasoning_content":"Okay"}}]}""",
            """{"choices":[{"index":0,"delta":{"reasoning_content":", hi."}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"Hello"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}""",
        )
        val events = sse(chunks).load(Artifact(variant, ""), LoadOptions(Accel.CPU)).generate(ask("Say hi.")).toList()
        assertEquals("<think>\nOkay, hi.\n</think>\n\nHello!", events.filterIsInstance<GenEvent.Text>().joinToString("") { it.text })
        assertEquals(4, (events.last() as GenEvent.Done).stats.outputTokens)
        // Cut off while thinking: the tag is still closed.
        val cut = sse(chunks.take(3)).load(Artifact(variant, ""), LoadOptions(Accel.CPU)).generate(ask("Say hi.")).toList()
        assertEquals("<think>\nOkay, hi.\n</think>", cut.filterIsInstance<GenEvent.Text>().joinToString("") { it.text })
    }

    // A server that answers each chat with [chunks] as an event stream, and lists the mapped model.
    private fun sse(chunks: List<String>): OpenAiBackend {
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/v1/models") { x ->
            val b = """{"data":[{"id":"echo-1.7b"}]}""".encodeToByteArray()
            x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        http.createContext("/v1/chat/completions") { x ->
            x.requestBody.readBytes()
            val b = (chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n").encodeToByteArray()
            x.responseHeaders.add("Content-Type", "text/event-stream")
            x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        http.start()
        stops += { http.stop(0) }
        return OpenAiBackend(endpoint.copy(baseUrl = "http://127.0.0.1:${http.address.port}/v1"))
    }

    private val stops = mutableListOf<() -> Unit>()

    @Test
    fun maxTokensEndsWithLength() = runBlocking {
        val done = load().generate(ask("one two three four", maxTokens = 2)).toList().last() as GenEvent.Done
        assertEquals(Finish.LENGTH, done.stats.finish)
        assertEquals(2, last!!.maxTokens)
    }

    @Test
    fun aJsonSchemaReachesTheServer() = runBlocking {
        load().generate(ask("true", schema = """{"type":"boolean"}""")).toList()
        assertEquals("""{"type":"boolean"}""", last!!.jsonSchema)
    }

    @Test
    fun cancellingStopsTheStream() = runBlocking {
        val words = (1..30).joinToString(" ") { "slow" }
        val got = load().generate(ask(words)).filterIsInstance<GenEvent.Text>().take(2).toList()
        assertEquals(2, got.size)
        delay(1_000)
        assertTrue(served < 30, "the server went on after the cancel: $served words")
    }

    @Test
    fun aModelItDoesNotServeOrAServerThatIsDownFailsTheLoad() {
        assertFailsWith<IllegalArgumentException> { load(v = variant.copy(model = "qwen3-8b")) }
        val free = ServerSocket(0).use { it.localPort }
        val down = OpenAiBackend(endpoint.copy(baseUrl = "http://127.0.0.1:$free/v1"))
        assertFailsWith<IOException> { load(down) }
        val wrongKey = OpenAiBackend(endpoint.copy(apiKey = "nope"))
        assertFailsWith<IOException> { load(wrongKey) }
    }

    @Test
    fun theDescriptorSaysWhereItRuns() {
        assertEquals(Location.LAN, backend.about.location)
        assertTrue(backend.descriptor!!.missing().isEmpty(), "${backend.descriptor!!.missing()}")
        assertEquals("openai:pc", backend.id)
    }

    @Test
    fun theEndpointsOfALocationAreOneBackendReadOnEachLoad() = runBlocking {
        var list = emptyList<Endpoint>()
        val lan = OpenAiEndpoints({ list })
        assertEquals(ai.fanak.den.core.backend.Capabilities.NONE, lan.probe(DEVICE))
        assertFailsWith<IllegalStateException> { lan.load(Artifact(variant, ""), LoadOptions(Accel.CPU)) }
        // A server that is down first, then the one that serves the model.
        val free = ServerSocket(0).use { it.localPort }
        list = listOf(endpoint.copy(name = "down", baseUrl = "http://127.0.0.1:$free/v1"), endpoint)
        assertTrue(Accel.CPU in lan.probe(DEVICE).accels)
        val text = lan.load(Artifact(variant, ""), LoadOptions(Accel.CPU)).generate(ask("hello there")).filterIsInstance<GenEvent.Text>().toList()
        assertEquals("hello there ", text.joinToString("") { it.text })
        // A cloud endpoint is not in the LAN backend.
        list = listOf(endpoint.copy(location = Location.CLOUD))
        assertEquals(ai.fanak.den.core.backend.Capabilities.NONE, lan.probe(DEVICE))
        assertEquals(Location.LAN, lan.about.location)
    }

    private companion object {
        const val TOKEN = "test-token"
        val DEVICE = ai.fanak.den.core.device.DeviceProfile(
            ai.fanak.den.core.device.Platform.ANDROID, 34, "SM7325", "snapdragon-778g", "arm64-v8a", 5_500, 1_900,
        )
    }
}
