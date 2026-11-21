package ai.fanak.den.app

import ai.fanak.den.core.model.ModelAliases
import android.content.Context

// Where the user pointed an alias (den.default, ...) on the Den screen; an alias not here follows its defaults.
object AliasSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("aliases", Context.MODE_PRIVATE)

    fun all(c: Context): Map<String, String> =
        prefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    // Null goes back to the defaults.
    fun set(c: Context, alias: String, model: String?) {
        prefs(c).edit().apply { if (model == null) remove(alias) else putString(alias, model) }.apply()
    }

    fun applyTo(c: Context, aliases: ModelAliases) = all(c).forEach { (alias, model) -> aliases.set(alias, model) }
}
