package ai.fanak.den.app

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.DenModel
import ai.fanak.den.IDenService
import ai.fanak.den.IGenCallback
import ai.fanak.den.IOpenCallback
import ai.fanak.den.ModelFiles
import ai.fanak.den.backend.llamacpp.LlamaCppBackend
import ai.fanak.den.backend.openai.OpenAiEndpoints
import ai.fanak.den.core.backend.BackendDescriptor
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.model.DownloadProgress
import ai.fanak.den.core.model.RemoteFile
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.router.Expected
import ai.fanak.den.core.router.NoUsableBackendException
import ai.fanak.den.core.router.RouteHint
import ai.fanak.den.core.router.RoutingPolicy
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Hosts the engine for every app on the phone: one loaded copy of each model, shared by all callers.
class DenService : Service() {
    // One failing client must not take down the process that serves the others.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("DenService", "uncaught", e) },
    )
    private val den by lazy {
        // Two apps at once share each decoding step of a model instead of waiting for each other (#154). The servers
        // of the Den screen run a model only when no file of it runs on the phone, LAN before cloud, and only for the
        // apps the user allowed to go there (Reach).
        val servers = { ServerSettings.all(this) }
        val config = DenConfig(
            mode = DenMode.EMBEDDED, parallel = 2, onDownload = ::progress, beforeDownload = ::beforeDownload,
            backends = listOf(LlamaCppBackend(), OpenAiEndpoints(servers), OpenAiEndpoints(servers, Location.CLOUD)),
            routing = RoutingPolicy.LOCAL_FIRST,
        )
        Den.connect(this, config).also { AliasSettings.applyTo(this, it.aliases) }
    }

    // Holds the client's binder: a death link on a binder that is garbage-collected never fires.
    private class Session(
        val model: String, val uid: Int, val pkg: String, val hint: RouteHint?, val client: IBinder,
        var death: IBinder.DeathRecipient? = null, var job: Job? = null,
    )

    // Opens still in progress, by the client's callback binder; they get the download progress of their model.
    private class Pending(var model: String, val uid: Int, val callback: IOpenCallback, var job: Job? = null)
    private val pending = ConcurrentHashMap<IBinder, Pending>()
    // File name -> model, for the downloads in progress.
    private val downloads = ConcurrentHashMap<String, String>()

    // The context the next load of a model uses: what the last version 5 client asked for, (length, shortest).
    private val wanted = ConcurrentHashMap<String, Pair<Int, Int>>()

    // The prompt and answer lengths the last app that opened the model expects, for the path its copy is loaded on.
    private val expectedOf = ConcurrentHashMap<String, Expected>()

    private val pool = ModelPool(
        load = { name, reach, hint ->
            val e = expectedOf[name]
            wanted[name]?.let { (length, min) -> den.model(name, length, min, reach, hint, e) } ?: den.model(name, reach = reach, hint = hint, expected = e)
        },
        prepare = { name, reach ->
            den.resolve(name)
            val file = den.fileName(name)
            if (file != null) downloads[file] = name
            try {
                den.download(name, reach)
            } finally {
                if (file != null) downloads.remove(file)
            }
        },
        need = { name, avail -> den.ramNeedMb(name, den.device().copy(availRamMb = avail)) },
        availMb = { den.device().availRamMb },
        now = SystemClock::elapsedRealtime,
        log = ::log,
        exclusive = { name -> den.exclusiveAccelerator(name) },
        pinned = { name -> Pins.has(this, name) },
    )
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val nextId = AtomicLong(1)

    private val binder = object : IDenService.Stub() {
        override fun version() = 9

        override fun httpPort() = if (HttpSettings.enabled(this@DenService)) http.port else -1

        override fun models() = den.models.toTypedArray()

        override fun open(model: String, callback: IOpenCallback) = start(model, callback, null, null)

        override fun openWith(model: String, options: String, callback: IOpenCallback) =
            start(model, callback, runCatching { contextOf(options) }.getOrNull(), hintOf(options), expectedOf(options))

        override fun plan(model: String, options: String): Array<String> {
            val (_, min) = runCatching { contextOf(options) }.getOrNull() ?: (0 to 0)
            // Binder thread: hf: names need the network once, then their cache.
            runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(30_000) { den.resolve(model) } } }
            val target = runCatching { kotlinx.coroutines.runBlocking { den.resolveAlias(model) } }.getOrDefault(model)
            val reach = reachOf(callerPackage(Binder.getCallingUid()), hintOf(options))
            return den.plan(target, minContextLength = min, reach = reach).map { "${it.backend.id} ${it.accel} ${it.variant.quant} ${it.source}" }.toTypedArray()
        }

        override fun cancelOpen(callback: IOpenCallback) {
            pending[callback.asBinder()]?.takeIf { it.uid == Binder.getCallingUid() }?.job?.cancel()
        }

        // [context]: (length, shortest accepted) from a version 5 client, which also gets onContext and onFailed.
        private fun start(model: String, callback: IOpenCallback, context: Pair<Int, Int>?, hint: RouteHint?, expected: Expected? = null) {
            val v5 = context != null
            fun fail(kind: String, message: String) =
                runCatching { if (v5) callback.onFailed(kind, message) else callback.onError(message) }
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
                        fail("error", "$pkg is not allowed to use Den; allow it in the Den app")
                        return@launch
                    }
                    // An alias (den.default) opens the model behind it, with the Den app's aliases.
                    val target = den.resolveAlias(model)
                    p.model = target
                    if (context != null) wanted[target] = context
                    if (expected != null) expectedOf[target] = expected
                    val reach = reachOf(pkg, hint)
                    var m = pool.acquire(target, reach, hint)
                    // Loaded for another app with a shorter context than this one accepts: reloaded when it is idle.
                    if (context != null && m.contextLength in 1 until context.second) {
                        pool.release(target)
                        if (!pool.evict(target)) throw IllegalStateException("$target runs with ${m.contextLength} tokens of context for another app; try again when it is idle")
                        m = pool.acquire(target, reach, hint)
                    }
                    val id = nextId.getAndIncrement()
                    // A client that dies releases its sessions.
                    val session = Session(target, uid, pkg, hint, client)
                    sessions[id] = session
                    session.death = IBinder.DeathRecipient { scope.launch { release(id) } }.also { client.linkToDeath(it, 0) }
                    log("open $target for ${caller(uid)}: session $id, ${m.backend} ${m.accel} ${m.quant}, sessions ${pool.sessions(target)}")
                    if (v5) callback.onContext(m.contextLength)
                    if (v5 && target != model) callback.onModel(target)
                    callback.onOpened(id, m.backend, m.accel.name, m.quant, m.source.name)
                } catch (e: CancellationException) {
                    log("open $model for ${caller(uid)} cancelled")
                    fail("error", "cancelled")
                } catch (e: Exception) {
                    log("open $model for ${caller(uid)} failed: $e")
                    fail(if (e is NoUsableBackendException) "no_backend" else "error", e.message ?: e.toString())
                } finally {
                    pending.remove(client)
                    runCatching { client.unlinkToDeath(early, 0) }
                }
            }
            p.job?.start()
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
                        // Read on each request, so a level the user lowers applies to open sessions too.
                        pool.use(s.model, reachOf(s.pkg, s.hint), s.hint) { m -> recorded(s.model, m, m.chat(GenRequest(messages, maxTokens, temperature, topP, stop.toList())), s.pkg).collect { e ->
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
        // Aliases too, so OpenAI clients can list and pick them.
        models = { den.models + den.aliases.names.sorted() },
        // An alias opens the model behind it, so both share one loaded copy.
        use = { who, name, block ->
            val target = den.resolveAlias(name)
            val client = Reach.http(who)
            pool.use(target, Reach.of(this, client)) { m -> block { req -> recorded(target, m, m.chat(req), client) } }
        },
        client = { HttpSettings.name(this, it) },
        log = ::log,
        systemInfo = ::systemInfo,
    )

    // Every field, defaults too, so clients need not know them.
    private val INFO_JSON = Json { encodeDefaults = true }

    // The device and the backends, for GET /den/v1/system-info.
    private fun systemInfo(): JsonObject {
        val p = den.device()
        val backends = kotlinx.coroutines.runBlocking { den.backendDescriptors() }
        return buildJsonObject {
            put("den_version", runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull())
            putJsonObject("device") {
                put("soc", p.soc)
                put("soc_family", p.socFamily)
                put("android_api", p.osApi)
                put("ram_mb", p.ramMb)
                put("free_ram_mb", p.availRamMb)
                put("thermal", p.thermal.name)
            }
            put("backends", INFO_JSON.encodeToJsonElement(ListSerializer(BackendDescriptor.serializer()), backends))
        }
    }

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

    private suspend fun updateModel(name: String): String = when {
        name in downloads.values -> "$name is downloading; wait for it to finish"
        !pool.evict(name) -> "$name is generating; try again when it is idle"
        else -> try {
            den.update(name)?.let { "updated $name to commit ${it.take(7)}" } ?: "$name is up to date"
        } catch (e: Exception) {
            "update of $name failed: ${e.message}"
        }
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

    // For the diagnostics file: speeds and the path taken, never the text.
    private fun recorded(model: String, m: DenModel, events: Flow<GenEvent>, client: String): Flow<GenEvent> {
        var t0 = 0L
        var first: Long? = null
        return events.onStart { t0 = System.currentTimeMillis() }.onEach { e ->
            when (e) {
                is GenEvent.Text -> if (first == null) first = System.currentTimeMillis() - t0
                is GenEvent.Done -> {
                    Diagnostics.record(Diagnostics.Request(t0, model, m.backend, m.accel.name, m.quant, first, e.stats, den.device().thermal.name))
                    Reach.record(this, client, m.location, e.stats.promptTokens + e.stats.outputTokens, m.offPhone)
                }
            }
        }
    }

    // {"contextLength":4096,"minContextLength":2048} -> (4096, 2048).
    // What the user allows the client, narrowed by what the app asked for this model.
    private fun reachOf(client: String, hint: RouteHint?): Set<Location> = Reach.of(this, client).let { hint?.limit(it) ?: it }

    // {"hint":"private"} -> PRIVATE; null without one. An unknown one, from a newer SDK, keeps the model on the phone.
    private fun hintOf(options: String): RouteHint? {
        val h = runCatching { org.json.JSONObject(options).optString("hint", "") }.getOrDefault("")
        if (h.isEmpty()) return null
        return RouteHint.entries.find { it.name.equals(h, ignoreCase = true) } ?: RouteHint.PRIVATE
    }

    // {"promptTokens":3000,"outputTokens":200} -> Expected(3000, 200); null without promptTokens.
    private fun expectedOf(options: String): Expected? = runCatching {
        val o = org.json.JSONObject(options)
        if (!o.has("promptTokens")) null
        else Expected(o.getInt("promptTokens").coerceIn(1, 1 shl 20), o.optInt("outputTokens", 0).coerceIn(0, 1 shl 20))
    }.getOrNull()

    private fun contextOf(options: String): Pair<Int, Int> {
        val o = org.json.JSONObject(options)
        val length = o.getInt("contextLength").coerceIn(256, 1 shl 20)
        return length to o.optInt("minContextLength", length).coerceIn(256, length)
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
            // Pinned models go only when the system is about to kill processes.
            val critical = level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE
            scope.launch { pool.trim(critical) }
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

        // Here, not in the screen's own Den: the service's catalog must follow the new commit, or it would download
        // the old one again.
        suspend fun update(model: String): String = current?.updateModel(model) ?: "the Den service is not running; open the Den app again"

        // Unloads a model before den bench loads its own copy; false while it is generating.
        suspend fun unload(model: String): Boolean = current?.pool?.evict(model) ?: true

        // Points an alias at a model (null: its defaults), for the Den screen; the running service follows at once.
        fun setAlias(context: android.content.Context, alias: String, model: String?) {
            AliasSettings.set(context, alias, model)
            current?.den?.aliases?.set(alias, model)
        }

        suspend fun deletePartial(context: android.content.Context): String {
            val keep = current?.downloads?.keys?.toSet() ?: emptySet()
            return "removed unfinished downloads, ${ModelFiles(modelDir(context)).deletePartial(keep) shr 20} MB"
        }

        private fun modelDir(context: android.content.Context) = java.io.File(context.filesDir, "den/models")
    }
}
