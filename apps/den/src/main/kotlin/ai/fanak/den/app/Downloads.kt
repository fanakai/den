package ai.fanak.den.app

import ai.fanak.den.core.model.DownloadRefusedException
import ai.fanak.den.core.model.RemoteFile
import ai.fanak.den.core.model.Variant
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the Den app checks before it downloads a file for a client: the user's approval of the repository (hf: and
 * ms: names only), the disk quota, and an unmetered network for large files.
 */
internal object Downloads {
    private const val TIMEOUT_MS = 60_000L
    private const val QUIET_MS = 5 * 60_000L
    // Larger files wait for an unmetered network unless mobile data is allowed.
    private const val LARGE_BYTES = 100L shl 20
    private const val DEFAULT_QUOTA_GB = 20

    private fun prefs(c: Context) = c.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    // Any repository on a hub; curated and built-in names were reviewed when they were added.
    fun needsApproval(model: String) = HUBS.any { model.startsWith(it) }

    // Model names from hubs Den has not reviewed: each repository or tag is approved by the user once.
    private val HUBS = listOf("hf:", "ms:", "ollama:")

    // The hub's name and the model without its prefix, for the approval screen and the notification.
    fun hubOf(model: String) = when {
        model.startsWith("ms:") -> "ModelScope"
        model.startsWith("ollama:") -> "the Ollama registry"
        else -> "Hugging Face"
    }

    fun shortName(model: String) = HUBS.fold(model) { m, p -> m.removePrefix(p) }

    // By name, revision included: hf:owner/repo and hf:owner/repo@rev are asked separately.
    fun approved(c: Context): Set<String> = prefs(c).getStringSet("repos", emptySet()).orEmpty().toSortedSet()

    fun forget(c: Context, model: String) = prefs(c).edit().putStringSet("repos", approved(c) - model).apply()

    fun mobileData(c: Context) = prefs(c).getBoolean("mobile_data", false)

    fun setMobileData(c: Context, on: Boolean) = prefs(c).edit().putBoolean("mobile_data", on).apply()

    fun quotaGb(c: Context) = prefs(c).getInt("quota_gb", DEFAULT_QUOTA_GB)

    fun setQuotaGb(c: Context, gb: Int) {
        require(gb in 1..1024)
        prefs(c).edit().putInt("quota_gb", gb).apply()
    }

    class Ask(val model: String, val file: String, val bytes: Long, val licence: String?, val by: String)

    private val waiting = ConcurrentHashMap<String, Pair<Ask, CompletableDeferred<Boolean>>>()
    private val quiet = ConcurrentHashMap<String, Long>()

    val pending: List<Ask> get() = waiting.values.map { it.first }

    fun asking(model: String): Ask? = waiting[model]?.first

    fun answer(c: Context, model: String, allow: Boolean) {
        if (allow) prefs(c).edit().putStringSet("repos", approved(c) + model).apply() else quiet[model] = SystemClock.elapsedRealtime() + QUIET_MS
        nm(c).cancel("repo:$model", 0)
        waiting.remove(model)?.second?.complete(allow)
    }

    /** Throws [DownloadRefusedException] when the user does not approve or the quota is full; waits for Wi-Fi. */
    suspend fun check(c: Context, v: Variant, r: RemoteFile, usedBytes: Long, by: String, show: (PendingIntent) -> Unit) {
        if (needsApproval(v.model) && v.model !in approved(c) && !ask(c, Ask(v.model, r.fileName, r.sizeBytes, r.licence, by), show)) {
            throw DownloadRefusedException("the download of ${v.model} was not approved; approve it in the Den app")
        }
        val quota = quotaGb(c).toLong() shl 30
        if (usedBytes + r.sizeBytes > quota) {
            throw DownloadRefusedException(
                "${r.fileName} (${r.sizeBytes shr 20} MB) does not fit in the Den quota: ${usedBytes shr 20} of " +
                    "${quota shr 20} MB used; delete a model or raise the quota in the Den app",
            )
        }
        if (r.sizeBytes >= LARGE_BYTES) waitForUnmetered(c, r)
    }

    fun screen(c: Context, model: String): PendingIntent = PendingIntent.getActivity(
        c, ("repo:$model").hashCode(), Intent(c, ApproveActivity::class.java).putExtra("repo", model),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun ask(c: Context, a: Ask, show: (PendingIntent) -> Unit): Boolean {
        quiet[a.model]?.let { if (SystemClock.elapsedRealtime() < it) return false else quiet.remove(a.model) }
        val d = waiting.computeIfAbsent(a.model) {
            (a to CompletableDeferred<Boolean>()).also {
                notifyAsk(c, a)
                show(screen(c, a.model))
            }
        }.second
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: false.also {
            if (waiting.remove(a.model)?.second == d) {
                quiet[a.model] = SystemClock.elapsedRealtime() + QUIET_MS
                nm(c).cancel("repo:${a.model}", 0)
            }
        }
    }

    // No network at all is left to the download, which fails at once as offline.
    private fun metered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // Shown on the Den screen too: the notification needs a permission the user may have denied.
    val waitingForWifi: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private suspend fun waitForUnmetered(c: Context, r: RemoteFile) {
        val tag = "wifi:${r.path}"
        val label = "${r.fileName}, ${r.sizeBytes shr 20} MB"
        var told = false
        try {
            while (metered(c) && !mobileData(c)) {
                if (!told) {
                    android.util.Log.i("DenService", "download ${r.fileName}: waiting for Wi-Fi")
                    waitingForWifi += label
                    notifyWaiting(c, tag, r)
                    told = true
                }
                delay(5_000)
            }
        } finally {
            if (told) {
                waitingForWifi -= label
                nm(c).cancel(tag, 0)
            }
        }
    }

    fun describe(a: Ask) = "${a.file}, ${a.bytes shr 20} MB, licence ${a.licence ?: "not given"}"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun notifyAsk(c: Context, a: Ask) {
        nm(c).createNotificationChannel(NotificationChannel("approvals", "App approvals", NotificationManager.IMPORTANCE_HIGH))
        fun action(allow: Boolean) = PendingIntent.getBroadcast(
            c, ("repo:${a.model}").hashCode() * 2 + if (allow) 1 else 0,
            Intent(c, ApprovalReceiver::class.java).putExtra("repo", a.model).putExtra("allow", allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(c, "approvals")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Download ${shortName(a.model)}?")
            .setContentText(describe(a))
            .setContentIntent(screen(c, a.model))
            .addAction(Notification.Action.Builder(null, "Download", action(true)).build())
            .addAction(Notification.Action.Builder(null, "Deny", action(false)).build())
            .build()
        nm(c).notify("repo:${a.model}", 0, n)
    }

    private fun notifyWaiting(c: Context, tag: String, r: RemoteFile) {
        nm(c).createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Waiting for Wi-Fi")
            .setContentText("${r.fileName}, ${r.sizeBytes shr 20} MB. Mobile data can be allowed in the Den app.")
            .setContentIntent(open)
            .build()
        nm(c).notify(tag, 0, n)
    }
}
