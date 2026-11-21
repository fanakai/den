package ai.fanak.den.backend.llamacpp

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.Finish
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.Variant
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

/**
 * The real engine through JNI, on the CPU of the test machine, with stories260K (1.2 MB): what CI can check of the
 * native code on every pull request. Speed and accelerators are measured on phones, not here.
 */
class EngineTest {
    private fun gen(
        h: Long,
        prompt: String,
        maxTokens: Int,
        stop: List<String> = emptyList(),
        cache: Boolean = true,
        threads: Int = 0,
        // [grammar, JSON schema], each empty when unused.
        constraints: List<String> = listOf("", ""),
        roles: List<String> = listOf("user"),
        contents: List<String> = listOf(prompt),
        keepGoing: (String) -> Boolean = { true },
    ): Pair<String, GenStats> {
        val text = StringBuilder()
        val s = LlamaNative.generate(
            h, roles.toTypedArray(), contents.map { it.encodeToByteArray() }.toTypedArray(), maxTokens, 0f, 1f,
            stop.map { it.encodeToByteArray() }.toTypedArray(), cache, threads,
            constraints.map { it.encodeToByteArray() }.toTypedArray(),
        ) { utf8 ->
            text.append(utf8.decodeToString())
            keepGoing(text.toString())
        }
        return text.toString() to GenStats.of(s)
    }

    private fun withEngine(nCtx: Int = 256, block: (Long) -> Unit) {
        val h = LlamaNative.load(model.path, nCtx, 2, 0, true)
        try {
            block(h)
        } finally {
            LlamaNative.free(h)
        }
    }

    @Test
    fun greedyGenerationIsRepeatable() = withEngine { h ->
        val (a, s) = gen(h, PROMPT, 24)
        val (b, _) = gen(h, PROMPT, 24)
        assertTrue(a.isNotBlank(), "no text")
        assertEquals(a, b)
        assertTrue(s.promptTokens > 0 && s.outputTokens > 0, "stats $s")
    }

    @Test
    fun aChatReusesItsHistory() = withEngine(nCtx = 512) { h ->
        val (reply, first) = gen(h, PROMPT, 16)
        assertEquals(0, first.cachedTokens)
        // The same prompt again: all but the last token come from the cache, with the same reply.
        val (again, s) = gen(h, PROMPT, 16)
        assertEquals(reply, again)
        assertEquals(s.promptTokens - 1, s.cachedTokens)

        // Turn 2 shares the first turn's prompt; the reply is the one a full prefill gives.
        val roles = listOf("user", "assistant", "user")
        val contents = listOf(PROMPT, reply, "Then what happened?")
        val (cached, turn2) = gen(h, "", 16, roles = roles, contents = contents)
        assertTrue(turn2.cachedTokens > 0 && turn2.cachedTokens < turn2.promptTokens, "stats $turn2")
        val (full, none) = gen(h, "", 16, cache = false, roles = roles, contents = contents)
        assertEquals(0, none.cachedTokens)
        assertEquals(full, cached)
    }

    @Test
    fun threadsCanBeSetPerRequest() = withEngine { h ->
        assertEquals(2, gen(h, PROMPT, 4).second.threads)
        assertEquals(1, gen(h, PROMPT, 4, threads = 1).second.threads)
    }

    @Test
    fun aGrammarOrAJsonSchemaConstrainsTheReply() = withEngine { h ->
        val (word, _) = gen(h, PROMPT, 16, constraints = listOf("root ::= \"yes\" | \"no\"", ""))
        assertTrue(word in setOf("yes", "no"), "not in the grammar: '$word'")
        // The JSON grammar allows whitespace after the value.
        val (bool, _) = gen(h, PROMPT, 16, constraints = listOf("", """{"type":"boolean"}"""))
        assertTrue(bool.trim() in setOf("true", "false"), "not a JSON boolean: '$bool'")
        assertFailsWith<EngineException> { gen(h, PROMPT, 4, constraints = listOf("", "{not json")) }
    }

    @Test
    fun maxTokensEndsWithLength() = withEngine { h ->
        val (_, s) = gen(h, PROMPT, 4)
        assertEquals(4, s.outputTokens)
        assertEquals(Finish.LENGTH, s.finish)
    }

    // llama.cpp raises a smaller context to 256 tokens.
    @Test
    fun fullContextEndsWithContext() = withEngine(nCtx = 256) { h ->
        val (_, s) = gen(h, PROMPT, 500)
        assertEquals(Finish.CONTEXT, s.finish)
        assertEquals(256, s.promptTokens + s.outputTokens)
    }

    @Test
    fun promptLongerThanContextFails() = withEngine(nCtx = 256) { h ->
        assertFailsWith<EngineException> { gen(h, PROMPT.repeat(30), 8) }
    }

    @Test
    fun stopStringCutsTheReply() = withEngine { h ->
        val (full, _) = gen(h, PROMPT, 32)
        val stop = full.substring(full.length / 2).trim().take(4)
        val (cut, s) = gen(h, PROMPT, 32, listOf(stop))
        assertEquals(full.substring(0, full.indexOf(stop)), cut)
        assertEquals(Finish.STOP, s.finish)
    }

    @Test
    fun cancelStopsAndTheSessionGoesOn() = withEngine { h ->
        val (_, s) = gen(h, PROMPT, 64) { false }
        assertTrue(s.outputTokens < 64, "not stopped: $s")
        assertEquals(gen(h, PROMPT, 16).first.isNotBlank(), true)
    }

    @Test
    fun backendSessionStreamsCancelsAndUnloads() = runBlocking {
        val session = LlamaCppBackend().load(
            Artifact(Variant("stories260k", ModelFormat.GGUF, "f32", model.length(), 0), model.path),
            LoadOptions(Accel.CPU, contextLength = 256, threads = 2),
        )
        val req = GenRequest(listOf(ChatMessage(Role.USER, PROMPT)), maxTokens = 16, temperature = 0f)
        fun text(events: List<GenEvent>) = events.filterIsInstance<GenEvent.Text>().joinToString("") { it.text }
        val events = session.generate(req).toList()
        assertTrue(events.last() is GenEvent.Done)
        // Collecting only the first piece cancels the rest; the session must still serve the next request.
        session.generate(req.copy(maxTokens = 64)).filterIsInstance<GenEvent.Text>().first()
        assertEquals(text(events), text(session.generate(req).toList()))
        session.unload()
        assertFailsWith<IllegalStateException> { session.generate(req).toList() }
        Unit
    }

    companion object {
        private const val PROMPT = "Once upon a time, there was a little girl named Lily."
        private const val URL_ = "https://huggingface.co/ggml-org/test-model-stories260K/resolve/" +
            "479896ec924af6d40fd419ab8f4d1eb2101de00d/stories260K-f32.gguf"
        private const val SHA256 = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d"

        lateinit var model: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val lib = File(System.getProperty("den.engineLib") ?: "")
            if (!lib.isFile) {
                if (System.getenv("CI") != null) fail("desktop engine not built: $lib")
                Assume.assumeTrue("desktop engine not built on this machine (Windows)", false)
            }
            model = File(System.getProperty("den.testModels"), "stories260K-f32.gguf")
            if (!model.isFile || sha256(model) != SHA256) download()
        }

        private fun download() {
            model.parentFile?.mkdirs()
            val conn = URL(URL_).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "fanak-den/0.1")
            conn.inputStream.use { input -> model.outputStream().use { input.copyTo(it) } }
            check(sha256(model) == SHA256) { "checksum mismatch for $URL_" }
        }

        private fun sha256(f: File) =
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
