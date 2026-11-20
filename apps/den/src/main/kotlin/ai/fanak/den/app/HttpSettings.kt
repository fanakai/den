package ai.fanak.den.app

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The HTTP endpoint, off by default. Tokens are made by the user in the Den app, one per client app, shown once, and
 * stored as SHA-256 only.
 */
internal object HttpSettings {
    // 8000 is a common development port: another app there would get the clients' tokens and prompts.
    const val DEFAULT_PORT = 8742

    private const val HASH = "sha256:"

    private fun prefs(c: Context) = c.getSharedPreferences("http", Context.MODE_PRIVATE)
    private fun tokens(c: Context) = c.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)

    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    fun port(c: Context) = prefs(c).getInt("port", DEFAULT_PORT)

    fun setPort(c: Context, port: Int) {
        require(port in 1024..65535) { "port must be between 1024 and 65535" }
        prefs(c).edit().putInt("port", port).apply()
    }

    // Stored key -> client name; the key is a hash, so it identifies the token without revealing it.
    fun all(c: Context): Map<String, String> = migrated(c).all.mapValues { it.value.toString() }

    fun name(c: Context, token: String): String? = migrated(c).getString(key(token), null)

    // The token is returned once, to be shown to the user; only its hash is kept.
    fun create(c: Context, name: String): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = "den-" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        tokens(c).edit().putString(key(token), name).apply()
        return token
    }

    fun revoke(c: Context, key: String) = tokens(c).edit().remove(key).apply()

    private fun key(token: String) =
        HASH + MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    // Tokens stored in clear before this version are replaced by their hash.
    private fun migrated(c: Context) = tokens(c).also { p ->
        val clear = p.all.filterKeys { !it.startsWith(HASH) }
        if (clear.isNotEmpty()) {
            p.edit().apply {
                clear.forEach { (token, name) -> remove(token); putString(key(token), name.toString()) }
            }.apply()
        }
    }
}
