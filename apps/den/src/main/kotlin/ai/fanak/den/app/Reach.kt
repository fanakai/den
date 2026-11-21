package ai.fanak.den.app

import ai.fanak.den.core.backend.Location
import android.content.Context

/**
 * Where each client may run its requests, set on the Den screen: on the phone only, the default; also on the LAN
 * servers; or also in the cloud. Clients are app packages, and HTTP clients as "http:<token name>". Also counts where
 * their requests ran.
 */
internal object Reach {
    // Each level is a location and the ones before it.
    val LEVELS = listOf(Location.DEVICE, Location.LAN, Location.CLOUD)

    private fun prefs(c: Context) = c.getSharedPreferences("reach", Context.MODE_PRIVATE)
    private fun counts(c: Context) = c.getSharedPreferences("reach_counts", Context.MODE_PRIVATE)

    fun http(name: String) = "http:$name"

    fun of(c: Context, client: String): Set<Location> = LEVELS.take(LEVELS.indexOf(level(c, client)) + 1).toSet()

    // The farthest location allowed.
    fun level(c: Context, client: String): Location =
        runCatching { prefs(c).getString(client, null) }.getOrNull()?.let { v -> LEVELS.find { it.name == v } } ?: Location.DEVICE

    fun set(c: Context, client: String, level: Location) = prefs(c).edit().putString(client, level.name).apply()

    // Stored per client and location as "requests tokens", the tokens being prompt and output together.
    @Synchronized
    fun record(c: Context, client: String, location: Location, tokens: Int) {
        val key = "$client|${location.name}"
        val (n, t) = parse(counts(c).getString(key, null))
        counts(c).edit().putString(key, "${n + 1} ${t + tokens}").apply()
    }

    // Location -> (requests, tokens) of one client.
    fun ran(c: Context, client: String): Map<Location, Pair<Long, Long>> =
        Location.entries.mapNotNull { l -> counts(c).getString("$client|${l.name}", null)?.let { l to parse(it) } }.toMap()

    fun forget(c: Context, client: String) {
        prefs(c).edit().remove(client).apply()
        counts(c).edit().apply { Location.entries.forEach { remove("$client|${it.name}") } }.apply()
    }

    private fun parse(v: String?): Pair<Long, Long> =
        v?.split(' ')?.takeIf { it.size == 2 }?.let { (it[0].toLongOrNull() ?: 0L) to (it[1].toLongOrNull() ?: 0L) } ?: (0L to 0L)
}
