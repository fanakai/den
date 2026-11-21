package ai.fanak.den

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Tool
import kotlinx.serialization.builtins.ListSerializer
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.FileRole
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Runs the backends in their own process (`:den_engine`), so a native crash, e.g. a GPU driver abort, kills this
 * process and not the app. Backends are created here again from their class name.
 */
internal class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught", e) })
    private val backends = ConcurrentHashMap<String, DenBackend>()
    private val sessions = ConcurrentHashMap<Long, Session>()
    // Request callback -> (session, job).
    private val jobs = ConcurrentHashMap<IBinder, Pair<Long, Job>>()
    private val nextId = AtomicLong(1)

    // An exception other than these few would crash this process on the way back to the caller.
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Throwable) {
        throw IllegalStateException(e.message ?: e.toString())
    }

    private fun backend(name: String) = backends.getOrPut(name) { create(this, name) }

    private val binder = object : IEngine.Stub() {
        override fun pid() = Process.myPid()

        override fun probe(backend: String, device: String): Array<String> = guard {
            val caps = backend(backend).probe(JSON.decodeFromString(DeviceProfile.serializer(), device))
            (caps.accels.map { "accel:${it.name}" } + caps.formats.map { "format:${it.id}" } +
                caps.architectures.orEmpty().map { "arch:$it" }).toTypedArray()
        }

        override fun version(backend: String) = guard { backend(backend).version }

        override fun residentKb(): Long = residentKb("self") ?: -1

        override fun load(backend: String, artifact: String, options: String): Long = guard {
            val a = JSON.decodeFromString(Artifact.serializer(), artifact)
            val o = JSON.decodeFromString(LoadOptions.serializer(), options)
            // A file the user added from storage is a content URI: the engine opens it here (same app, so the same
            // permission) and gives the backend the open descriptor as "fd:N", kept open with the session. Its
            // /proc/self/fd path would not do: opening it again needs a storage permission.
            val doc = a.path.takeIf { it.startsWith("content://") }?.let {
                contentResolver.openFileDescriptor(android.net.Uri.parse(it), "r") ?: error("cannot open $it")
            }
            val local = if (doc != null) a.copy(files = a.files + (FileRole.MODEL to "fd:${doc.fd}")) else a
            val s = try {
                runBlocking { backend(backend).load(local, o) }
            } catch (e: Throwable) {
                doc?.close()
                throw e
            }
            val kept = if (doc == null) s else object : Session by s {
                override fun unload() {
                    s.unload()
                    doc.close()
                }
            }
            nextId.getAndIncrement().also { sessions[it] = kept }
        }

        override fun generate(
            session: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int,
            temperature: Float, topP: Float, stop: Array<String>, cache: Boolean, threads: Int,
            constraints: Array<String>, callback: IGenCallback,
        ) {
            val s = sessions[session] ?: return run { runCatching { callback.onError("unknown session $session") } }
            // Keyed by the callback: several requests of a shared model can wait on one session.
            val key = callback.asBinder()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val messages = roles.zip(contents) { r, c -> ChatMessage(Role.valueOf(r), c) }
                    s.generate(GenRequest(
                        messages, maxTokens, temperature, topP, stop.toList(), cache, threads.takeIf { it > 0 },
                        grammar = constraints.getOrNull(0)?.ifEmpty { null },
                        jsonSchema = constraints.getOrNull(1)?.ifEmpty { null },
                        tools = constraints.getOrNull(2)?.let { JSON.decodeFromString(ListSerializer(Tool.serializer()), it) }.orEmpty(),
                    )).collect { e ->
                        when (e) {
                            is GenEvent.Text -> callback.onText(e.text)
                            is GenEvent.Done -> callback.onDone(e.stats.toLongs())
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { callback.onError("cancelled") }
                } catch (e: Exception) {
                    runCatching { callback.onError(e.message ?: e.toString()) }
                } finally {
                    jobs.remove(key)
                }
            }
            jobs[key] = session to job
            job.start()
        }

        override fun cancel(callback: IGenCallback) {
            jobs[callback.asBinder()]?.second?.cancel()
        }

        override fun unload(session: Long) = guard {
            jobs.values.filter { it.first == session }.forEach { it.second.cancel() }
            sessions.remove(session)?.unload()
            Unit
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        sessions.values.forEach { runCatching { it.unload() } }
        sessions.clear()
        super.onDestroy()
    }

    internal companion object {
        const val TAG = "DenEngine"
        val JSON = Json { ignoreUnknownKeys = true }

        // A Kotlin object, a constructor taking a Context, or one without arguments.
        fun create(context: Context, name: String): DenBackend {
            val c = Class.forName(name)
            runCatching { return c.getField("INSTANCE").get(null) as DenBackend }
            runCatching { return c.getConstructor(Context::class.java).newInstance(context) as DenBackend }
            return c.getConstructor().newInstance() as DenBackend
        }

        fun creatable(backend: DenBackend): Boolean {
            val c = backend.javaClass
            return runCatching { c.getField("INSTANCE").get(null) === backend }.getOrDefault(false) ||
                runCatching { c.getConstructor(Context::class.java) }.isSuccess ||
                runCatching { c.getConstructor() }.isSuccess
        }
    }
}

// VmRSS of /proc/<proc>/status in kB ("self" for this process).
internal fun residentKb(proc: String): Long? = runCatching {
    java.io.File("/proc/$proc/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()
    }
}.getOrNull()
