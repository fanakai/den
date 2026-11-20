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
    private var serverErrors = 0
    private val requests = mutableListOf<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m.gguf") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            requests += range
            if (serverErrors > 0) {
                serverErrors--
                ex.sendResponseHeaders(503, -1)
                ex.close()
                return@createContext
            }
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

    private fun storeWithPath() = HttpModelStore(dir, {
        RemoteFile("m.gguf", "http://127.0.0.1:${server.address.port}/m.gguf", sha, data.size.toLong(), "hf/owner/repo/m.gguf")
    }, minFreeBytes = 0)

    @Test
    fun storesUnderPathAndRecordsTheManifest() = runTest {
        val a = storeWithPath().fetch(variant)
        assertEquals(File(dir, "hf/owner/repo/m.gguf").path, a.path)
        val e = ModelManifest(dir).entries().single()
        assertEquals("hf/owner/repo/m.gguf", e.path)
        assertEquals("m" to "q4_0", e.model to e.quant)
        assertTrue(e.complete)
    }

    @Test
    fun sameFileFromAnotherSourceIsNotDownloadedAgain() = runTest {
        storeWithPath().fetch(variant)
        val fromMirror = HttpModelStore(dir, {
            RemoteFile("m.gguf", "http://127.0.0.1:${server.address.port}/m.gguf", sha, data.size.toLong(), "ms/owner/repo/m.gguf")
        }, minFreeBytes = 0).fetch(variant)
        assertEquals(1, requests.size)
        assertEquals(File(dir, "hf/owner/repo/m.gguf").path, fromMirror.path)
    }

    @Test
    fun adoptsAFileStoredBeforePaths() = runTest {
        File(dir, "m.gguf").writeBytes(data)
        File(dir, "m.gguf.sha256").writeText(sha)
        storeWithPath().fetch(variant)
        assertTrue(requests.isEmpty())
        assertFalse(File(dir, "m.gguf").exists())
        assertTrue(File(dir, "hf/owner/repo/m.gguf").isFile)
    }

    @Test
    fun failedDownloadStaysIncompleteInTheManifest() = runTest {
        assertFailsWith<IOException> { store(sha256 = "0".repeat(64)).fetch(variant) }
        assertFalse(ModelManifest(dir).entries().single().complete)
    }

    @Test
    fun retriesAfterAServerError() = runTest {
        serverErrors = 1
        val s = HttpModelStore(dir, {
            RemoteFile("m.gguf", "http://127.0.0.1:${server.address.port}/m.gguf", sha, data.size.toLong())
        }, minFreeBytes = 0, retryDelayMs = 10)
        assertContentEquals(data, File(s.fetch(variant).path).readBytes())
        assertEquals(2, requests.size)
    }

    @Test
    fun givesUpAfterThreeServerErrors() = runTest {
        serverErrors = 5
        val s = HttpModelStore(dir, {
            RemoteFile("m.gguf", "http://127.0.0.1:${server.address.port}/m.gguf", sha, data.size.toLong())
        }, minFreeBytes = 0, retryDelayMs = 10)
        assertFailsWith<IOException> { s.fetch(variant) }
        assertEquals(3, requests.size)
    }

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
