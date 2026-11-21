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
        use = { name, block ->
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

    private companion object {
        const val TOKEN = "test-token"
    }
}
