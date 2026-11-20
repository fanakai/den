package ai.fanak.den.app

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.ModelFiles
import ai.fanak.den.IDenService
import ai.fanak.den.IGenCallback
import ai.fanak.den.IOpenCallback
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.model.DownloadProgress
import ai.fanak.den.core.model.RemoteFile
import ai.fanak.den.core.model.Variant
import ai.fanak.den.service.OpenAiServer
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
import kotlinx.coroutines.CoroutineStart
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
        val config = DenConfig(mode = DenMode.EMBEDDED, onDownload = ::progress, beforeDownload = ::beforeDownload)
        Den.connect(this, config)
    }

    // Holds the client's binder: a death link on a binder that is garbage-collected never fires.
    private class Session(val model: String, val uid: Int, val client: IBinder, var death: IBinder.DeathRecipient? = null, var job: Job? = null)

    // Opens still in progress, by the client's callback binder; they get the download progress of their model.
    private class Pending(val model: String, val uid: Int, val callback: IOpenCallback, var job: Job? = null)
    private val pending = ConcurrentHashMap<IBinder, Pending>()
    // File name -> model, for the downloads in progress.
    private val downloads = ConcurrentHashMap<String, String>()

    private val pool = ModelPool(
        load = { den.model(it) },
        prepare = { name ->
            den.resolve(name)
            val file = den.fileName(name)
            if (file != null) downloads[file] = name
            try {
                den.download(name)
            } finally {
                if (file != null) downloads.remove(file)
            }
        },
        need = { name, avail -> den.plan(name, den.device().copy(availRamMb = avail)).firstOrNull()?.variant?.minRamMb },
        availMb = { den.device().availRamMb },
        now = SystemClock::elapsedRealtime,
        log = ::log,
    )
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val nextId = AtomicLong(1)

    private val binder = object : IDenService.Stub() {
        override fun version() = 4

        override fun httpPort() = if (HttpSettings.enabled(this@DenService)) http.port else -1

        override fun models() = den.models.toTypedArray()

        override fun open(model: String, callback: IOpenCallback) {
            val uid = Binder.getCallingUid()
            val client = callback.asBinder()
            val p = Pending(model, uid, callback)
            pending[client] = p
            // Until the session exists, a client that dies cancels its open (and the download it started).
            val early = IBinder.DeathRecipient { p.job?.cancel() }
            runCatching { client.linkToDeath(early, 0) }
            p.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val pkg = callerPackage(uid)
                    // Version 3 clients open the approval screen; older ones ignore it and get the notification.
                    if (!Approvals.request(this@DenService, pkg) { screen -> runCatching { callback.onApproval(screen) } }) {
                        log("open $model for $pkg: not allowed")
                        return@launch callback.onError("$pkg is not allowed to use Den; allow it in the Den app")
                    }
                    val m = pool.acquire(model)
                    val id = nextId.getAndIncrement()
                    // A client that dies releases its sessions.
                    val session = Session(model, uid, client)
                    sessions[id] = session
                    session.death = IBinder.DeathRecipient { scope.launch { release(id) } }.also { client.linkToDeath(it, 0) }
                    log("open $model for ${caller(uid)}: session $id, ${m.backend} ${m.accel} ${m.quant}, sessions ${pool.sessions(model)}")
                    callback.onOpened(id, m.backend, m.accel.name, m.quant, m.source.name)
                } catch (e: CancellationException) {
                    log("open $model for ${caller(uid)} cancelled")
                    runCatching { callback.onError("cancelled") }
                } catch (e: Exception) {
                    log("open $model for ${caller(uid)} failed: $e")
                    runCatching { callback.onError(e.message ?: e.toString()) }
                } finally {
                    pending.remove(client)
                    runCatching { client.unlinkToDeath(early, 0) }
                }
            }
            p.job?.start()
        }

        override fun cancelOpen(callback: IOpenCallback) {
            pending[callback.asBinder()]?.takeIf { it.uid == Binder.getCallingUid() }?.job?.cancel()
        }

        override fun generate(
            session: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int,
            temperature: Float, topP: Float, stop: Array<String>, callback: IGenCallback,
        ) {
            val s = sessions[session]
            if (s == null || s.uid != Binder.getCallingUid()) {
                runCatching { callback.onError("unknown session $session") }
                return
            }
            // One request per session at a time, so cancel() always stops the running one.
            synchronized(s) {
                if (s.job?.isActive == true) {
                    runCatching { callback.onError("session $session is already generating") }
                    return
                }
                s.job = scope.launch(start = CoroutineStart.LAZY) {
                    val t0 = System.currentTimeMillis()
                    try {
                        // Parsed here, not on the Binder thread: a bad value from a client must reach onError.
                        val messages = roles.zip(contents) { r, c ->
                            ChatMessage(requireNotNull(Role.entries.find { it.name == r.uppercase() }) { "unknown role $r" }, c)
                        }
                        pool.use(s.model) { m -> m.chat(GenRequest(messages, maxTokens, temperature, topP, stop.toList())).collect { e ->
                            when (e) {
                                is GenEvent.Text -> callback.onText(e.text)
                                is GenEvent.Done -> e.stats.run {
                                    // Requests on one model run one at a time; wait is the time spent queued.
                                    val wait = System.currentTimeMillis() - t0 - prefillMs - decodeMs
                                    log("gen session $session for ${caller(s.uid)}: wait $wait ms, prefill $prefillMs ms, decode $decodeMs ms")
                                    callback.onDone(toLongs())
                                }
                            }
                        } }
                    } catch (e: CancellationException) {
                        runCatching { callback.onError("cancelled") }
                    } catch (e: Exception) {
                        runCatching { callback.onError(e.message ?: e.toString()) }
                    }
                }
            }
            s.job?.start()
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

    private val http = OpenAiServer(
        scope,
        models = { den.models },
        use = { name, block -> pool.use(name) { m -> block { req -> m.chat(req) } } },
        client = { HttpSettings.name(this, it) },
        log = ::log,
    )

    // Starts, stops, or moves the endpoint to the port set on the Den screen.
    private fun applyHttp() {
        val port = HttpSettings.port(this)
        if (!HttpSettings.enabled(this)) return http.stop()
        if (http.port == port) return
        http.stop()
        runCatching { http.start(port) }.onFailure { log("http failed to start on $port: $it") }
    }

    private suspend fun deleteModel(name: String): String = when {
        name in downloads.values -> "$name is downloading; cancel it first"
        !pool.evict(name) -> "$name is generating; try again when it is idle"
        else -> "deleted $name, ${den.files.delete(name) shr 20} MB"
    }.also(::log)

    // The user approves hub repositories; the clients waiting for this model get the approval screen.
    private suspend fun beforeDownload(v: Variant, r: RemoteFile) {
        val waiting = pending.values.filter { it.model == v.model }
        val by = waiting.map { Approvals.label(this, callerPackage(it.uid)) }.distinct().joinToString().ifEmpty { "an HTTP client" }
        val used = den.files.list().filter { it.path != r.path }.sumOf { it.bytes }
        Downloads.check(this, v, r, used, by) { screen -> waiting.forEach { runCatching { it.callback.onApproval(screen) } } }
    }

    private fun progress(p: DownloadProgress) {
        log("download ${p.fileName} ${p.bytes shr 20}/${p.total shr 20} MB")
        val model = downloads[p.fileName] ?: return
        pending.values.filter { it.model == model }.forEach { runCatching { it.callback.onProgress(p.fileName, p.bytes, p.total) } }
    }

    private fun caller(uid: Int) = packageManager.getNameForUid(uid) ?: uid.toString()

    private fun callerPackage(uid: Int) = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: uid.toString()

    private var bound = false

    override fun onCreate() {
        super.onCreate()
        current = this
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
        val text = if (HttpSettings.enabled(this)) "Serving models to apps and on 127.0.0.1:${HttpSettings.port(this)}" else "Serving models to apps"
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
        current = null
        http.stop()
        scope.cancel()
        pool.closeAll()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenService", msg)

    companion object {
        private const val IDLE_MS = 5 * 60_000L

        // The running service, for the Den screen in the same process. Not an intent: the service is exported,
        // and an intent action would let any app delete models.
        @Volatile private var current: DenService? = null

        // Deletes a model's files, unloading it first; without a running service nothing is loaded.
        suspend fun delete(context: android.content.Context, model: String): String =
            current?.deleteModel(model) ?: "deleted $model, ${ModelFiles(modelDir(context)).delete(model) shr 20} MB"

        suspend fun deletePartial(context: android.content.Context): String {
            val keep = current?.downloads?.keys?.toSet() ?: emptySet()
            return "removed unfinished downloads, ${ModelFiles(modelDir(context)).deletePartial(keep) shr 20} MB"
        }

        private fun modelDir(context: android.content.Context) = java.io.File(context.filesDir, "den/models")
    }
}
