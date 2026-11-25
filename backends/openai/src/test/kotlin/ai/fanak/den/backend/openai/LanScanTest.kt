package ai.fanak.den.backend.openai

import com.sun.net.httpserver.HttpServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class LanScanTest {
    private fun serve(path: String, body: String) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(path) { ex ->
            val b = body.toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        start()
    }

    // Like llama-server.
    private val llama = serve("/v1/models", """{"object":"list","data":[{"id":"Qwen3-0.6B"},{"id":"gemma-3-1b"}]}""")

    // Answers on /api/v1 only, like Lemonade.
    private val lemonade = serve("/api/v1/models", """{"object":"list","data":[{"id":"Qwen3-4B-GGUF","object":"model"}]}""")

    // Open, but not an OpenAI server.
    private val other = serve("/v1/models", """<html>router login</html>""")

    @AfterTest
    fun stop() {
        listOf(llama, lemonade, other).forEach { it.stop(0) }
    }

    @Test
    fun findsServersOnTheirPortsAndPaths() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        val found = LanScan.scan(listOf("127.0.0.1"), listOf(closed, llama.address.port, other.address.port, lemonade.address.port))
        assertEquals(
            listOf(
                FoundServer("http://127.0.0.1:${llama.address.port}/v1", listOf("Qwen3-0.6B", "gemma-3-1b")),
                FoundServer("http://127.0.0.1:${lemonade.address.port}/api/v1", listOf("Qwen3-4B-GGUF")),
            ),
            found,
        )
    }

    @Test
    fun hostsStayInTheirSlash24() {
        val a = InetAddress.getByName("192.168.1.37") as Inet4Address
        val h = LanScan.hosts(a, 24)
        assertEquals(254, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
        assertEquals(254, LanScan.hosts(InetAddress.getByName("10.4.200.9") as Inet4Address, 16).size)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), LanScan.hosts(InetAddress.getByName("10.0.0.3") as Inet4Address, 29).take(2))
    }
}
