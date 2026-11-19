package ai.fanak.den.app

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.DenModel
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
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Hosts the engine for every app on the phone: one loaded copy of each model, shared by all callers.
class DenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val den by lazy {
        val config = DenConfig(mode = DenMode.EMBEDDED, onDownload = { log("download ${it.fileName} ${it.bytes shr 20}/${it.total shr 20} MB") })
        Den.connect(this, config)
    }

    private class Loaded(val model: DenModel, var refs: Int)
    private class Session(val model: String, val uid: Int, var job: Job? = null)

    private val loaded = mutableMapOf<String, Loaded>()
    private val lock = Mutex()
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val nextId = AtomicLong(1)

    private val binder = object : IDenService.Stub() {
        override fun version() = 1

        override fun models() = den.models.toTypedArray()

        override fun open(model: String, callback: IOpenCallback) {
            val uid = Binder.getCallingUid()
            scope.launch {
                try {
                    val m = lock.withLock {
                        loaded.getOrPut(model) { Loaded(den.model(model), 0) }.also { it.refs++ }.model
                    }
                    val id = nextId.getAndIncrement()
                    sessions[id] = Session(model, uid)
                    // A client that dies releases its sessions.
                    callback.asBinder().linkToDeath({ scope.launch { release(id) } }, 0)
                    log("open $model for ${caller(uid)}: session $id, ${m.backend} ${m.accel} ${m.quant}, refs ${refs(model)}")
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
                val m = lock.withLock { loaded[s.model]?.model } ?: return@launch callback.onError("model unloaded")
                try {
                    m.chat(GenRequest(messages, maxTokens, temperature, topP, stop.toList())).collect { e ->
                        when (e) {
                            is GenEvent.Text -> callback.onText(e.text)
                            is GenEvent.Done -> e.stats.run {
                                callback.onDone(longArrayOf(promptTokens.toLong(), outputTokens.toLong(), prefillMs, decodeMs))
                            }
                        }
                    }
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
        s.job?.cancel()
        lock.withLock {
            val l = loaded[s.model] ?: return
            if (--l.refs == 0) {
                l.model.close()
                loaded.remove(s.model)
            }
        }
        log("close session $id (${s.model}), refs ${refs(s.model)}")
    }

    private fun refs(model: String) = loaded[model]?.refs ?: 0

    private fun caller(uid: Int) = packageManager.getNameForUid(uid) ?: uid.toString()

    override fun onBind(intent: Intent): IBinder {
        // Keeps the process and its cores when the calling app goes to the background (see #7).
        runCatching { startForegroundService(Intent(this, DenService::class.java)) }
            .onFailure { log("cannot start in foreground: $it") }
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("den", "Fanak Den", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "den")
            .setContentTitle("Fanak Den")
            .setContentText("Serving models to apps")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent): Boolean {
        log("all clients unbound")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        loaded.values.forEach { it.model.close() }
        loaded.clear()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenService", msg)
}
