package ai.fanak.den.app

import android.content.Context

// Models the user keeps loaded (the "Keep loaded" switch on the Den screen), by model name.
object Pins {
    private fun prefs(c: Context) = c.getSharedPreferences("pins", Context.MODE_PRIVATE)

    fun has(c: Context, model: String) = prefs(c).getBoolean(model, false)

    fun set(c: Context, model: String, on: Boolean) {
        prefs(c).edit().apply { if (on) putBoolean(model, true) else remove(model) }.apply()
    }
}
