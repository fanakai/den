package ai.fanak.den.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys of cloud endpoints, encrypted with an AES key that stays in the Android Keystore. Only the Den app reads
 * them, to call the endpoint; client apps never get them.
 */
internal object Secrets {
    private const val ALIAS = "den-api-keys"

    private fun prefs(c: Context) = c.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(c: Context, name: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(secret.toByteArray())
        prefs(c).edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    // Null when none is stored or it no longer decrypts.
    fun get(c: Context, name: String): String? = runCatching {
        val data = Base64.decode(prefs(c).getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        cipher.doFinal(data, 12, data.size - 12).decodeToString()
    }.getOrNull()

    fun remove(c: Context, name: String) = prefs(c).edit().remove(name).apply()
}
