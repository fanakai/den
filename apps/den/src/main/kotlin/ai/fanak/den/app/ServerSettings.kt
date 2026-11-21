package ai.fanak.den.app

import ai.fanak.den.backend.openai.Endpoint
import ai.fanak.den.core.backend.Location
import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// The OpenAI-compatible servers the user added on the Den screen: on the LAN, or in the cloud with an API key. The
// keys are stored apart, encrypted (Secrets), and filled in only when the Den app reads the list.
object ServerSettings {
    private val list = ListSerializer(Endpoint.serializer())

    private fun prefs(c: Context) = c.getSharedPreferences("servers", Context.MODE_PRIVATE)

    private fun secret(name: String) = "server:$name"

    private fun stored(c: Context): List<Endpoint> =
        prefs(c).getString("list", null)?.let { runCatching { Json.decodeFromString(list, it) }.getOrNull() } ?: emptyList()

    fun all(c: Context): List<Endpoint> = stored(c).map { e -> e.copy(apiKey = Secrets.get(c, secret(e.name))) }

    fun add(c: Context, e: Endpoint) {
        check(e)
        if (e.apiKey != null) Secrets.put(c, secret(e.name), e.apiKey!!) else Secrets.remove(c, secret(e.name))
        save(c, stored(c).filter { it.name != e.name } + e.copy(apiKey = null))
    }

    fun remove(c: Context, name: String) {
        Secrets.remove(c, secret(name))
        save(c, stored(c).filter { it.name != name })
    }

    private fun save(c: Context, l: List<Endpoint>) = prefs(c).edit().putString("list", Json.encodeToString(list, l)).apply()

    // A cloud endpoint gets the prompts and the key over the internet: HTTPS only.
    fun check(e: Endpoint) {
        require(e.location != Location.CLOUD || e.baseUrl.startsWith("https://")) { "a cloud endpoint needs an https:// URL" }
        require(e.location != Location.DEVICE) { "a server is on the LAN or in the cloud" }
    }

    // "den-name = server-id" per line; an empty text maps each of the server's models to its own name.
    fun parseModels(text: String, serverModels: List<String>): Map<String, String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return serverModels.associateWith { it }
        return lines.associate { l ->
            val parts = l.split('=', limit = 2).map { it.trim() }
            require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "\"$l\": write den-name = server-model" }
            parts[0] to parts[1]
        }
    }
}
