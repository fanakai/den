package ai.fanak.den.testapp

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch

// Risk check #7: does a foreground service keep usable cores and survive with the screen off?
// Start: am start -n ai.fanak.den.testapp/.MainActivity --ez soak true --es model qwen3-0.6b --ei minutes 30
class SoakService : Service() {
    private val scope = MainScope()
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (job?.isActive != true) {
            val model = intent?.getStringExtra("model") ?: "qwen3-0.6b"
            val minutes = intent?.getIntExtra("minutes", 30) ?: 30
            job = scope.launch { soak(model, minutes) }
        }
        return START_NOT_STICKY
    }

    private suspend fun soak(model: String, minutes: Int) {
        val den = testDen()
        val m = try {
            den.model(model)
        } catch (e: Exception) {
            log("open failed: $e")
            stopSelf()
            return
        }
        log("start model=$model ${m.backend} ${m.accel} ${m.quant} minutes=$minutes")

        val power = getSystemService(POWER_SERVICE) as PowerManager
        val req = GenRequest(listOf(ChatMessage(Role.USER, "Write two sentences about desert foxes. /no_think")), 64, 0f)
        val end = System.currentTimeMillis() + minutes * 60_000L
        var round = 0
        while (System.currentTimeMillis() < end) {
            round++
            try {
                val s = m.chat(req).filterIsInstance<GenEvent.Done>().last().stats
                val p = den.device()
                log(
                    "round=$round screen=${if (power.isInteractive) "on" else "off"} cpuset=${cpuset()} " +
                        "cores=${allowedCores()} thermal=${p.thermal} freeMb=${p.availRamMb} " +
                        "decode=%.1f prefill=%.1f".format(s.decodeTps, s.prefillTps),
                )
            } catch (e: Exception) {
                log("round=$round error: $e")
            }
            delay(20_000)
        }
        m.close()
        log("done rounds=$round")
        stopSelf()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("soak", "Den soak test", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "soak")
            .setContentTitle("Den soak test")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun cpuset() = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")

    private fun allowedCores() = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("Cpus_allowed_list") }.substringAfter(':').trim()
    }.getOrDefault("?")

    override fun onDestroy() {
        log("destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenSoak", msg)
}
