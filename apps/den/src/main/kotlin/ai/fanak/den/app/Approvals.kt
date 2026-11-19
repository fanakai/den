package ai.fanak.den.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// Apps the user allowed or denied, by package name. Asked once per app, on its first open().
internal object Approvals {
    private const val TIMEOUT_MS = 60_000L
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private fun prefs(c: Context) = c.getSharedPreferences("approvals", Context.MODE_PRIVATE)

    fun all(c: Context): Map<String, Boolean> = prefs(c).all.mapValues { it.value == true }

    val pending: Set<String> get() = waiting.keys

    fun set(c: Context, pkg: String, allow: Boolean) {
        prefs(c).edit().putBoolean(pkg, allow).apply()
        c.getSystemService(NotificationManager::class.java).cancel(pkg, 0)
        waiting.remove(pkg)?.complete(allow)
    }

    fun forget(c: Context, pkg: String) = prefs(c).edit().remove(pkg).apply()

    // False when denied, or not answered within a minute.
    suspend fun request(c: Context, pkg: String): Boolean {
        val p = prefs(c)
        if (p.contains(pkg)) return p.getBoolean(pkg, false)
        val d = waiting.computeIfAbsent(pkg) { CompletableDeferred<Boolean>().also { notify(c, pkg) } }
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: false.also {
            waiting.remove(pkg, d)
            c.getSystemService(NotificationManager::class.java).cancel(pkg, 0)
        }
    }

    fun label(c: Context, pkg: String): String =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    private fun notify(c: Context, pkg: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("approvals", "App approvals", NotificationManager.IMPORTANCE_HIGH))
        fun action(allow: Boolean): PendingIntent {
            val i = Intent(c, ApprovalReceiver::class.java).putExtra("pkg", pkg).putExtra("allow", allow)
            return PendingIntent.getBroadcast(
                c, pkg.hashCode() * 2 + if (allow) 1 else 0, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "approvals")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Allow ${label(c, pkg)} to use local models?")
            .setContentText(pkg)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Allow", action(true)).build())
            .addAction(Notification.Action.Builder(null, "Deny", action(false)).build())
            .build()
        nm.notify(pkg, 0, n)
    }
}

// Not exported: only Den's own notification can send it.
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        Approvals.set(context, pkg, intent.getBooleanExtra("allow", false))
    }
}
