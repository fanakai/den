package ai.fanak.den.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Apps the user allowed or denied, by package name and signing certificate, so another app installed later under the
 * same name is asked again. Asked once per app, on its first open().
 */
internal object Approvals {
    private const val TIMEOUT_MS = 60_000L
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Stored as "allow <sha256>" or "deny <sha256>"; a Boolean is a decision from before certificates were stored.
    private class Decision(val allow: Boolean, val cert: String?)

    private fun prefs(c: Context) = c.getSharedPreferences("approvals", Context.MODE_PRIVATE)

    private fun read(c: Context, pkg: String): Decision? = when (val v = prefs(c).all[pkg]) {
        is Boolean -> Decision(v, null)
        is String -> Decision(v.substringBefore(' ') == "allow", v.substringAfter(' ', "").ifEmpty { null })
        else -> null
    }

    fun all(c: Context): Map<String, Boolean> = prefs(c).all.keys.associateWith { read(c, it)?.allow == true }

    val pending: Set<String> get() = waiting.keys

    fun set(c: Context, pkg: String, allow: Boolean) {
        quiet.remove(pkg)
        val value = (if (allow) "allow " else "deny ") + (certificate(c, pkg) ?: "")
        prefs(c).edit().putString(pkg, value.trim()).apply()
        c.getSystemService(NotificationManager::class.java).cancel(pkg, 0)
        waiting.remove(pkg)?.complete(allow)
    }

    // The app's reach goes too: another app installed later under this name starts on the phone only.
    fun forget(c: Context, pkg: String) {
        prefs(c).edit().remove(pkg).apply()
        Reach.forget(c, pkg)
        quiet.remove(pkg)
    }

    // An app whose request was dismissed or not answered is not asked again for a while, so it cannot flood the user.
    private const val QUIET_MS = 5 * 60_000L
    private val quiet = ConcurrentHashMap<String, Long>()

    // Apps in their quiet time, listed on the Den screen so the user can still answer them there.
    val dismissed: Set<String> get() = quiet.filterValues { SystemClock.elapsedRealtime() < it }.keys

    // The user closed the approval screen without answering: no decision is stored.
    fun dismiss(c: Context, pkg: String) {
        quiet[pkg] = SystemClock.elapsedRealtime() + QUIET_MS
        c.getSystemService(NotificationManager::class.java).cancel(pkg, 0)
        waiting.remove(pkg)?.complete(false)
    }

    // The approval screen for one app. Not exported: only the Den service, which knows the caller from Binder, opens it.
    fun screen(c: Context, pkg: String): PendingIntent = PendingIntent.getActivity(
        c, pkg.hashCode(), Intent(c, ApproveActivity::class.java).putExtra("pkg", pkg),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * False when denied, dismissed, or not answered within a minute. A new request posts a notification and passes the
     * approval screen to [show], so a client app in the foreground can open it (no notification permission needed).
     */
    suspend fun request(c: Context, pkg: String, show: (PendingIntent) -> Unit = {}): Boolean {
        read(c, pkg)?.let { d ->
            when {
                // Given before certificates were stored: tied now to the app installed under that name.
                d.cert == null -> return d.allow.also { set(c, pkg, it) }
                signedWith(c, pkg, d.cert) -> return d.allow
                // Another app under the same name: the decision does not carry over.
                else -> forget(c, pkg)
            }
        }
        quiet[pkg]?.let { if (SystemClock.elapsedRealtime() < it) return false else quiet.remove(pkg) }
        val d = waiting.computeIfAbsent(pkg) {
            CompletableDeferred<Boolean>().also {
                notify(c, pkg)
                show(screen(c, pkg))
            }
        }
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: false.also {
            waiting.remove(pkg, d)
            quiet[pkg] = SystemClock.elapsedRealtime() + QUIET_MS
            c.getSystemService(NotificationManager::class.java).cancel(pkg, 0)
        }
    }

    // SHA-256 of the app's current signing certificate (the first one when it has several signers).
    private fun certificate(c: Context, pkg: String): String? = runCatching {
        val info = c.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?: return null
        // The history runs from the original certificate to the current one.
        val cert = if (info.hasMultipleSigners()) info.apkContentsSigners.first() else info.signingCertificateHistory.last()
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrElse { android.util.Log.w("DenService", "no certificate for $pkg: $it"); null }

    // Checks the signing history too, so an app that rotates its key keeps its approval.
    private fun signedWith(c: Context, pkg: String, sha256: String) = runCatching {
        val bytes = sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        c.packageManager.hasSigningCertificate(pkg, bytes, PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

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
        val open = screen(c, pkg)
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
        intent.getStringExtra("repo")?.let { return Downloads.answer(context, it, intent.getBooleanExtra("allow", false)) }
        val pkg = intent.getStringExtra("pkg") ?: return
        Approvals.set(context, pkg, intent.getBooleanExtra("allow", false))
    }
}
