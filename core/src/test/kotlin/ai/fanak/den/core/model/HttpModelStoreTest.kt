package ai.fanak.den.core.model

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpModelStoreTest {
    private val data = Random(1).nextBytes(3_000_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val dir: File = Files.createTempDirectory("den-store").toFile()
    private val variant = Variant("m", ModelFormat.GGUF, "q4_0", data.size.toLong(), 100)

    private var honorRange = true
    private val requests = mutableListOf<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m.gguf") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            requests += range
            val from = range?.takeIf { honorRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = data.copyOfRange(from, data.size)
            ex.sendResponseHeaders(if (from > 0) 206 else 200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    private fun store(sha256: String = sha) = HttpModelStore(dir, {
        RemoteFile("m.gguf", "http://127.0.0.1:${server.address.port}/m.gguf", sha256, data.size.toLong())
    }, minFreeBytes = 0)

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun downloadsVerifiesAndReuses() = runTest {
        val a = store().fetch(variant)
        assertContentEquals(data, File(a.path).readBytes())
        assertEquals(sha, File(a.path + ".sha256").readText())

        store().fetch(variant)
        assertEquals(1, requests.size)
    }

    @Test
    fun resumesPartialDownload() = runTest {
        File(dir, "m.gguf.part").writeBytes(data.copyOfRange(0, 1_000_000))
        val a = store().fetch(variant)
        assertEquals(listOf<String?>("bytes=1000000-"), requests)
        assertContentEquals(data, File(a.path).readBytes())
    }

    @Test
    fun restartsWhenServerIgnoresRange() = runTest {
        honorRange = false
        File(dir, "m.gguf.part").writeBytes(data.copyOfRange(0, 1_000_000))
        val a = store().fetch(variant)
        assertContentEquals(data, File(a.path).readBytes())
    }

    @Test
    fun rejectsWrongChecksum() = runTest {
        assertFailsWith<IOException> { store(sha256 = "0".repeat(64)).fetch(variant) }
        assertFalse(File(dir, "m.gguf").exists())
        assertFalse(File(dir, "m.gguf.part").exists())
    }

    @Test
    fun failsAtOnceWhenOffline() = runTest {
        val offline = HttpModelStore(dir, {
            RemoteFile("m.gguf", "http://127.0.0.1:${server.address.port}/m.gguf", sha, data.size.toLong())
        }, minFreeBytes = 0, online = { false })
        assertFailsWith<IOException> { offline.fetch(variant) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun verifiesFilePlacedByOtherMeans() = runTest {
        File(dir, "m.gguf").writeBytes(data)
        store().fetch(variant)
        assertTrue(requests.isEmpty())
        assertTrue(File(dir, "m.gguf.sha256").isFile)
    }
}
