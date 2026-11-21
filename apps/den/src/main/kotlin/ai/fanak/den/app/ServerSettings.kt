package ai.fanak.den.app

import ai.fanak.den.backend.openai.Endpoint
import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// The OpenAI-compatible servers on the LAN the user added on the Den screen. Cloud ones wait for keys in the
// Keystore and per-app consent (#62).
object ServerSettings {
    private val list = ListSerializer(Endpoint.serializer())

    private fun prefs(c: Context) = c.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun all(c: Context): List<Endpoint> =
        prefs(c).getString("list", null)?.let { runCatching { Json.decodeFromString(list, it) }.getOrNull() } ?: emptyList()

    fun add(c: Context, e: Endpoint) = save(c, all(c).filter { it.name != e.name } + e)

    fun remove(c: Context, name: String) = save(c, all(c).filter { it.name != name })

    private fun save(c: Context, l: List<Endpoint>) = prefs(c).edit().putString("list", Json.encodeToString(list, l)).apply()

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
