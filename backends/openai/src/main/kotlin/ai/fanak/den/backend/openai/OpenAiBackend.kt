package ai.fanak.den.backend.openai

import ai.fanak.den.core.backend.BackendDescriptor
import ai.fanak.den.core.backend.BackendMode
import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Features
import ai.fanak.den.core.backend.Finish
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.backend.SlotPolicy
import ai.fanak.den.core.backend.SupportTier
import ai.fanak.den.core.backend.UnsupportedFeatureException
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A server with the OpenAI chat API (`/v1/chat/completions`, streamed), and the Den models it serves. [models] maps
 * a Den model name to the server's id, e.g. `qwen3-1.7b` to `Qwen3-1.7B-Q4_K_M`.
 */
@Serializable
data class Endpoint(
    // Short and unique: part of the backend id, e.g. "pc".
    val name: String,
    // Up to /v1, e.g. http://192.168.1.20:8080/v1.
    val baseUrl: String,
    val location: Location = Location.LAN,
    val models: Map<String, String>,
    // Sent as a bearer token; null for a LAN server without one.
    val apiKey: String? = null,
)

/** Runs requests on an [Endpoint]: nothing is loaded on the phone, and the router plans it only when its policy allows. */
class OpenAiBackend(
    private val endpoint: Endpoint,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 120_000,
) : DenBackend {
    override val id = "openai:${endpoint.name}"
    override val version = "1"

    override val about = BackendDescriptor(
        id, "OpenAI-compatible server ${endpoint.name}", "", "Fanak Den", "Apache-2.0", "https://github.com/fanakai/den",
        SupportTier.EXPERIMENTAL, setOf(BackendMode.CHAT), FORMATS, SlotPolicy.UNMETERED, endpoint.location,
        setOf(Features.JSON_SCHEMA),
    )

    // The server picks its own hardware; the router needs an accelerator to plan with.
    override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU), FORMATS)

    // Checks that the server answers and serves the model, so the router moves on to the next candidate otherwise.
    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
        val model = endpoint.models[artifact.variant.model]
            ?: throw IllegalArgumentException("${endpoint.name} does not serve ${artifact.variant.model}")
        withContext(Dispatchers.IO) {
            val conn = open("models")
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${endpoint.name}")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        return OpenAiSession(model)
    }

    override fun health(): Health = Health.Ok

    override fun serves(model: String) = model in endpoint.models

    private fun open(path: String): HttpURLConnection =
        (URL(endpoint.baseUrl.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "fanak-den/0.1")
            endpoint.apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private inner class OpenAiSession(private val model: String) : Session {
        override fun generate(req: GenRequest): Flow<GenEvent> = callbackFlow {
            if (req.tools.isNotEmpty()) throw UnsupportedFeatureException("tools", id)
            if (req.grammar != null) throw UnsupportedFeatureException("grammar", id)
            val conn = open("chat/completions").apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            launch(Dispatchers.IO) {
                try {
                    conn.outputStream.use { it.write(body(req, model).toString().encodeToByteArray()) }
                    if (conn.responseCode != 200) {
                        val detail = conn.errorStream?.use { it.readBytes().decodeToString().take(300) }.orEmpty()
                        throw IOException("HTTP ${conn.responseCode} from ${endpoint.name}: $detail")
                    }
                    val t0 = System.currentTimeMillis()
                    var first = 0L
                    var outputTokens = 0
                    var usage: JsonObject? = null
                    var finish = Finish.STOP
                    var thinking = false
                    fun emit(text: String): Boolean {
                        if (first == 0L) first = System.currentTimeMillis()
                        outputTokens++
                        return trySendBlocking(GenEvent.Text(text)).isSuccess
                    }
                    conn.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val chunk = JSON.parseToJsonElement(data).jsonObject
                            (chunk["usage"] as? JsonObject)?.let { usage = it }
                            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
                            val delta = choice["delta"] as? JsonObject
                            // llama-server and Lemonade send a thinking model's thoughts apart, as reasoning_content.
                            // They go to the app in <think> tags, as the same model gives them on the phone.
                            val thought = (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
                            if (!thought.isNullOrEmpty()) {
                                if (!emit(if (thinking) thought else "<think>\n$thought")) return@useLines
                                thinking = true
                            }
                            val text = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                            if (!text.isNullOrEmpty()) {
                                if (!emit(if (thinking) "\n</think>\n\n$text" else text)) return@useLines
                                thinking = false
                            }
                            when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
                                "length" -> finish = Finish.LENGTH
                            }
                        }
                    }
                    if (thinking) trySendBlocking(GenEvent.Text("\n</think>"))
                    val end = System.currentTimeMillis()
                    val u = usage
                    send(GenEvent.Done(GenStats(
                        promptTokens = u?.int("prompt_tokens") ?: 0,
                        // Without usage, one streamed chunk is about one token.
                        outputTokens = u?.int("completion_tokens") ?: outputTokens,
                        prefillMs = (if (first > 0) first else end) - t0,
                        decodeMs = if (first > 0) end - first else 0,
                        finish = finish,
                    )))
                    close()
                } catch (e: Throwable) {
                    close(e)
                }
            }
            // Closing the connection ends a blocked read when the app cancels.
            awaitClose { conn.disconnect() }
        }

        override fun unload() {}
    }

    private fun body(req: GenRequest, model: String) = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            req.messages.forEach { m -> add(buildJsonObject { put("role", m.role.name.lowercase()); put("content", m.content) }) }
        })
        put("max_tokens", req.maxTokens)
        put("temperature", req.temperature)
        put("top_p", req.topP)
        if (req.stop.isNotEmpty()) put("stop", buildJsonArray { req.stop.forEach { add(JsonPrimitive(it)) } })
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
        req.jsonSchema?.let { schema ->
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", "reply")
                    put("schema", JSON.parseToJsonElement(schema))
                })
            })
        }
    }

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        // A server takes a model by its id, whatever the file: every format the catalogs list.
        val FORMATS = setOf(ModelFormat.GGUF, ModelFormat.LITERTLM, ModelFormat.PTE, ModelFormat.QNN)
    }
}
