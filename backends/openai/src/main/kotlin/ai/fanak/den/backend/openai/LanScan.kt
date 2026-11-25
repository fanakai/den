package ai.fanak.den.backend.openai

import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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

/**
 * An OpenAI-compatible server that answered on the LAN, with the model ids of its `GET /models`; or, with [error], one
 * that announced itself by a beacon but did not answer, and why.
 */
data class FoundServer(val baseUrl: String, val models: List<String>, val error: String? = null)

/** Finds OpenAI-compatible servers on the local network, when the user asks: a short scan of known ports. */
object LanScan {
    /** llama-server, Lemonade and vLLM, Ollama, LM Studio. */
    val PORTS = listOf(8080, 8000, 11434, 1234)

    private val PATHS = listOf("/v1", "/api/v1")

    /**
     * Lemonade servers send `{"service": "lemonade", "hostname": ..., "url": "http://<ip>:<port>/api/v1/"}` by UDP
     * broadcast to this port every 2 s (lemonade-sdk/lemonade, `src/cpp/server/core/http_listener.cpp`).
     */
    const val BEACON_PORT = 13305

    // Its own thread: a scan holds all the threads of Dispatchers.IO while it runs, and the beacons would wait.
    private val BEACON_THREAD = Dispatchers.IO.limitedParallelism(1)

    /** [scan] of [hosts] and [listen] together: a Lemonade server on a port the scan does not try is found by its beacon. */
    suspend fun find(
        hosts: List<String>,
        ports: List<Int> = PORTS,
        windowMs: Int = 3_000,
        beaconPort: Int = BEACON_PORT,
    ): List<FoundServer> = coroutineScope {
        val heard = async { listen(windowMs, beaconPort) }
        val scanned = scan(hosts, ports)
        val seen = scanned.map { URL(it.baseUrl).authority }.toSet()
        // Lemonade also sends each beacon to 127.0.0.1; that one is left out when the server was heard on the network.
        val urls = heard.await().let { all ->
            all.filterNot { u -> URL(u).host == "127.0.0.1" && all.any { o -> o != u && URL(o).port == URL(u).port } }
        }
        scanned + urls.filter { URL(it).authority !in seen }.map { base ->
            withContext(Dispatchers.IO) {
                val u = URL(base)
                try {
                    Socket().use { it.connect(InetSocketAddress(u.host, u.port), 1_000) }
                } catch (e: IOException) {
                    return@withContext FoundServer(base, emptyList(), refusal(e, u.port))
                }
                try {
                    FoundServer(base, models(base, 1_000, 3_000))
                } catch (e: IOException) {
                    FoundServer(base, emptyList(), "sends the Lemonade beacon but GET /models failed: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    /**
     * The base URLs that Lemonade beacons announce within [windowMs], each once. A beacon counts only when its URL names
     * the address it came from, so one machine cannot point the phone at another.
     */
    suspend fun listen(windowMs: Int = 3_000, port: Int = BEACON_PORT): List<String> = withContext(BEACON_THREAD) {
        val heard = linkedSetOf<String>()
        val end = System.currentTimeMillis() + windowMs
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                val buf = ByteArray(2048)
                while (true) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) break
                    s.soTimeout = left.toInt()
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    beaconUrl(p.data.decodeToString(0, p.length), p.address)?.let { heard += it }
                }
            }
        } catch (_: IOException) {
            // The port is taken: no beacons, the scan still runs.
        }
        heard.toList()
    }

    // Lemonade listens on localhost by default and still sends its beacon, with its network address. A firewall that
    // drops the connection (Windows) gives a timeout instead of a refusal.
    private fun refusal(e: IOException, port: Int) = if (e is ConnectException) {
        "sends the Lemonade beacon but refuses connections from the network: set its host to 0.0.0.0"
    } else {
        "sends the Lemonade beacon but does not answer on port $port: set its host to 0.0.0.0 and let the port through its firewall"
    }

    internal fun beaconUrl(text: String, from: InetAddress): String? {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((o["service"] as? JsonPrimitive)?.content != "lemonade") return null
        val url = (o["url"] as? JsonPrimitive)?.content ?: return null
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        // A dual-stack socket gives an IPv4 sender as ::ffff:a.b.c.d; getByAddress turns it back into a.b.c.d.
        if (u.protocol != "http" || u.host != InetAddress.getByAddress(from.address).hostAddress) return null
        return url.trimEnd('/')
    }

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
