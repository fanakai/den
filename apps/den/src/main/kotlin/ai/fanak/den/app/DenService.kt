package ai.fanak.den.app

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.IDenService
import ai.fanak.den.IGenCallback
import ai.fanak.den.IOpenCallback
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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Hosts the engine for every app on the phone: one loaded copy of each model, shared by all callers.
class DenService : Service() {
    // One failing client must not take down the process that serves the others.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("DenService", "uncaught", e) },
    )
    private val den by lazy {
        val config = DenConfig(mode = DenMode.EMBEDDED, onDownload = { log("download ${it.fileName} ${it.bytes shr 20}/${it.total shr 20} MB") })
        Den.connect(this, config)
    }

    // Holds the client's binder: a death link on a binder that is garbage-collected never fires.
    private class Session(val model: String, val uid: Int, val client: IBinder, var death: IBinder.DeathRecipient? = null, var job: Job? = null)

    private val pool = ModelPool(
        load = { den.model(it) },
        need = { name, avail -> den.plan(name, den.device().copy(availRamMb = avail)).firstOrNull()?.variant?.minRamMb },
        availMb = { den.device().availRamMb },
        now = SystemClock::elapsedRealtime,
        log = ::log,
    )
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val nextId = AtomicLong(1)

    private val binder = object : IDenService.Stub() {
        override fun version() = 1

        override fun models() = den.models.toTypedArray()

        override fun open(model: String, callback: IOpenCallback) {
            val uid = Binder.getCallingUid()
            scope.launch {
                val pkg = callerPackage(uid)
                if (!Approvals.request(this@DenService, pkg)) {
                    log("open $model for $pkg: not allowed")
                    return@launch callback.onError("$pkg is not allowed to use Den; allow it in the Den app")
                }
                try {
                    val m = pool.acquire(model)
                    val id = nextId.getAndIncrement()
                    // A client that dies releases its sessions.
                    val session = Session(model, uid, callback.asBinder())
                    sessions[id] = session
                    session.death = IBinder.DeathRecipient { scope.launch { release(id) } }.also { session.client.linkToDeath(it, 0) }
                    log("open $model for ${caller(uid)}: session $id, ${m.backend} ${m.accel} ${m.quant}, sessions ${pool.sessions(model)}")
                    callback.onOpened(id, m.backend, m.accel.name, m.quant, m.source.name)
                } catch (e: Exception) {
                    log("open $model for ${caller(uid)} failed: $e")
                    callback.onError(e.message ?: e.toString())
                }
            }
        }

        override fun generate(
            session: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int,
            temperature: Float, topP: Float, stop: Array<String>, callback: IGenCallback,
        ) {
            val s = sessions[session]
            if (s == null || s.uid != Binder.getCallingUid()) {
                callback.onError("unknown session $session")
                return
            }
            val messages = roles.zip(contents) { r, c -> ChatMessage(Role.valueOf(r.uppercase()), c) }
            s.job = scope.launch {
                val t0 = System.currentTimeMillis()
                try {
                    pool.use(s.model) { m -> m.chat(GenRequest(messages, maxTokens, temperature, topP, stop.toList())).collect { e ->
                        when (e) {
                            is GenEvent.Text -> callback.onText(e.text)
                            is GenEvent.Done -> e.stats.run {
                                // Requests on one model run one at a time; wait is the time spent queued.
                                val wait = System.currentTimeMillis() - t0 - prefillMs - decodeMs
                                log("gen session $session for ${caller(s.uid)}: wait $wait ms, prefill $prefillMs ms, decode $decodeMs ms")
                                callback.onDone(longArrayOf(promptTokens.toLong(), outputTokens.toLong(), prefillMs, decodeMs))
                            }
                        }
                    } }
                } catch (e: CancellationException) {
                    callback.onError("cancelled")
                } catch (e: Exception) {
                    callback.onError(e.message ?: e.toString())
                }
            }
        }

        override fun cancel(session: Long) {
            sessions[session]?.takeIf { it.uid == Binder.getCallingUid() }?.job?.cancel()
        }

        override fun close(session: Long) {
            if (sessions[session]?.uid == Binder.getCallingUid()) scope.launch { release(session) }
        }
    }

    private suspend fun release(id: Long) {
        val s = sessions.remove(id) ?: return
        s.death?.let { runCatching { s.client.unlinkToDeath(it, 0) } }
        s.job?.cancel()
        pool.release(s.model)
        log("close session $id (${s.model}), sessions ${pool.sessions(s.model)}")
    }

    private val http = HttpEndpoint(
        scope,
        models = { den.models },
        use = { name, block -> pool.use(name, block) },
        client = { HttpSettings.name(this, it) },
        log = ::log,
    )

    private fun applyHttp() {
        if (HttpSettings.enabled(this)) {
            runCatching { http.start() }.onFailure { log("http failed to start: $it") }
        } else {
            http.stop()
        }
    }

    private fun caller(uid: Int) = packageManager.getNameForUid(uid) ?: uid.toString()

    private fun callerPackage(uid: Int) = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: uid.toString()

    private var bound = false

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            while (true) {
                delay(60_000)
                pool.unloadIdle(IDLE_MS)
            }
        }
    }

    // Levels below RUNNING_LOW are not worth giving up a loaded model for.
    override fun onTrimMemory(level: Int) {
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            log("trim memory, level $level")
            scope.launch { pool.trim() }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        onRebind(intent)
        return binder
    }

    override fun onRebind(intent: Intent) {
        bound = true
        // Keeps the process and its cores when the calling app goes to the background (see #7).
        runCatching { startForegroundService(Intent(this, DenService::class.java)) }
            .onFailure { log("cannot start in foreground: $it") }
    }

    // Also sent by the Den screen when the HTTP endpoint is turned on or off.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("den", "Fanak Den", NotificationManager.IMPORTANCE_LOW))
        val text = if (HttpSettings.enabled(this)) "Serving models to apps and on 127.0.0.1:${HttpSettings.PORT}" else "Serving models to apps"
        val n = Notification.Builder(this, "den")
            .setContentTitle("Fanak Den")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        applyHttp()
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent): Boolean {
        log("all clients unbound")
        bound = false
        stopIfIdle()
        return true
    }

    private fun stopIfIdle() {
        if (bound || HttpSettings.enabled(this)) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        http.stop()
        scope.cancel()
        pool.closeAll()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenService", msg)

    private companion object {
        const val IDLE_MS = 5 * 60_000L
    }
}
