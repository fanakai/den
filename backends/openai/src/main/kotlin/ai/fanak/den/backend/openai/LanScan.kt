package ai.fanak.den.backend.openai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** An OpenAI-compatible server that answered on the LAN, with the model ids of its `GET /models`. */
data class FoundServer(val baseUrl: String, val models: List<String>)

/** Finds OpenAI-compatible servers on the local network, when the user asks: a short scan of known ports. */
object LanScan {
    /** llama-server, Lemonade and vLLM, Ollama, LM Studio. */
    val PORTS = listOf(8080, 8000, 11434, 1234)

    private val PATHS = listOf("/v1", "/api/v1")

    /**
     * The hosts of [address]'s network, at most its /24, so a large network is not scanned whole. The phone itself
     * stays in: a server can run on it too, in Termux for example.
     */
    fun hosts(address: Inet4Address, prefix: Int): List<String> {
        val p = prefix.coerceIn(24, 30)
        val ip = address.address.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
        val mask = (0xffffffffL shl (32 - p)) and 0xffffffffL
        val net = ip and mask
        val last = net or (mask.inv() and 0xffffffffL)
        return ((net + 1) until last).map { n -> (3 downTo 0).joinToString(".") { ((n shr (8 * it)) and 0xff).toString() } }
    }

    /**
     * Each of [hosts] on each of [ports]: a TCP connect first, so a closed port costs at most [connectMs], then
     * `GET /models` under `/v1` and `/api/v1`. Servers in the order of [hosts] and [ports].
     */
    suspend fun scan(
        hosts: List<String>,
        ports: List<Int> = PORTS,
        connectMs: Int = 300,
        parallel: Int = 64,
    ): List<FoundServer> = coroutineScope {
        val gate = Semaphore(parallel)
        hosts.flatMap { h -> ports.map { h to it } }.map { (h, port) ->
            async(Dispatchers.IO) { gate.withPermit { probe(h, port, connectMs) } }
        }.awaitAll().filterNotNull()
    }

    private fun probe(host: String, port: Int, connectMs: Int): FoundServer? {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), connectMs) }
        } catch (_: IOException) {
            return null
        }
        for (path in PATHS) {
            val base = "http://$host:$port$path"
            val ids = runCatching { models(base, 1_000, 3_000) }.getOrNull() ?: continue
            return FoundServer(base, ids)
        }
        return null
    }

    /** The ids of `GET [baseUrl]/models`; throws when the server does not answer with an OpenAI model list. */
    fun models(baseUrl: String, connectMs: Int = 3_000, readMs: Int = 10_000, apiKey: String? = null): List<String> {
        val conn = URL(baseUrl.trimEnd('/') + "/models").openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.setRequestProperty("User-Agent", "fanak-den/0.1")
        apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from $baseUrl/models")
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val data = runCatching { Json.parseToJsonElement(body).jsonObject["data"] }.getOrNull() as? JsonArray
                ?: throw IOException("$baseUrl/models is not an OpenAI model list")
            return data.mapNotNull { runCatching { (it.jsonObject["id"] as? JsonPrimitive)?.content }.getOrNull() }
        } finally {
            conn.disconnect()
        }
    }
}
