package ai.fanak.den.core.model

import kotlin.concurrent.Volatile

/**
 * Stable names for roles, such as `den.default` and `den.small`, so an app keeps its code when a better model comes:
 * each alias names models in order of preference and resolves to the first that runs on this phone. The user can point
 * an alias at one model instead (the Den screen).
 */
class ModelAliases(private val defaults: Map<String, List<String>>) {
    // Replaced as a whole on each change: changes are rare, reads are not.
    @Volatile private var overrides: Map<String, String> = emptyMap()

    init {
        defaults.keys.forEach { require(isAliasName(it)) { "alias $it must start with $PREFIX" } }
    }

    val names: Set<String> get() = defaults.keys + overrides.keys

    fun isAlias(name: String) = name in defaults || name in overrides

    fun defaults(alias: String): List<String> = defaults[alias].orEmpty()

    fun override(alias: String): String? = overrides[alias]

    // Points [alias] at [model]; null goes back to the defaults.
    fun set(alias: String, model: String?) {
        require(isAliasName(alias)) { "alias $alias must start with $PREFIX" }
        overrides = if (model == null) overrides - alias else overrides + (alias to model)
    }

    /** The model behind [name]: the user's choice, else the first default that [runs], else the first default. */
    suspend fun resolve(name: String, runs: suspend (String) -> Boolean): String {
        overrides[name]?.let { return it }
        val choices = defaults[name] ?: return name
        return choices.firstOrNull { runCatching { runs(it) }.getOrDefault(false) } ?: choices.firstOrNull() ?: name
    }

    companion object {
        const val PREFIX = "den."

        fun isAliasName(name: String) = name.startsWith(PREFIX) && name.length > PREFIX.length
    }
}
