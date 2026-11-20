package ai.fanak.den

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.ThermalState
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.router.EngineCrashedException
import ai.fanak.den.core.router.EngineLostException
import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The binding to EngineService. The system restarts the engine process after it dies; sessions do not survive that.
internal class EngineConnection(private val context: Context) : AutoCloseable {
    class Bound(val engine: IEngine, val pid: Int, val generation: Int)

    private val lock = Object()
    private var bound: Bound? = null
    private var generation = 0
    private var binding = false
    private var closed = false
    private val watchers = mutableSetOf<() -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val e = IEngine.Stub.asInterface(service)
            val pid = runCatching { e.pid() }.getOrDefault(0)
            synchronized(lock) {
                bound = Bound(e, pid, ++generation)
                lock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = lost()

        override fun onBindingDied(name: ComponentName) = lost()
    }

    // Used only on Android 10 and later (DenConfig.isolate).
    @TargetApi(29)
    fun get(): Bound = synchronized(lock) {
        check(!closed) { "Den is closed" }
        if (!binding) {
            val intent = Intent(context, EngineService::class.java)
            if (!context.bindService(intent, Context.BIND_AUTO_CREATE, EXECUTOR, connection)) {
                throw EngineLostException("cannot start the engine process")
            }
            binding = true
        }
        val end = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (bound == null) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0) throw EngineLostException("the engine process did not start")
            lock.wait(left)
        }
        bound!!
    }

    fun alive(b: Bound) = synchronized(lock) { bound?.generation == b.generation }

    // False when the engine of `b` is already gone.
    fun watch(b: Bound, onDeath: () -> Unit): Boolean = synchronized(lock) {
        if (bound?.generation != b.generation) return false
        watchers += onDeath
        true
    }

    fun unwatch(onDeath: () -> Unit) = synchronized(lock) { watchers -= onDeath }

    // The system does not always restart a service whose process crashed (A52s, Android 14): bind again on next use.
    private fun lost() {
        val w = synchronized(lock) {
            bound = null
            if (binding) runCatching { context.unbindService(connection) }
            binding = false
            watchers.toList().also { watchers.clear() }
        }
        w.forEach { it() }
    }

    // A crash blocks the candidate; a stop by the system (low memory, user) does not. Waits up to 2 s for the record.
    fun error(b: Bound, cause: Throwable?): EngineLostException {
        if (Build.VERSION.SDK_INT >= 30 && b.pid > 0) {
            val am = context.getSystemService(ActivityManager::class.java)
            repeat(20) {
                val info = am.getHistoricalProcessExitReasons(context.packageName, b.pid, 1).firstOrNull()
                if (info != null) {
                    val what = info.description?.takeIf { it.isNotBlank() } ?: reasonName(info.reason)
                    return if (info.reason in CRASHES) {
                        EngineCrashedException("engine process crashed ($what)", cause)
                    } else {
                        EngineLostException("engine process stopped ($what)", cause)
                    }
                }
                Thread.sleep(100)
            }
        }
        return EngineCrashedException("engine process died", cause)
    }

    override fun close() = synchronized(lock) {
        closed = true
        if (binding) runCatching { context.unbindService(connection) }
        binding = false
        bound = null
    }

    private companion object {
        const val START_TIMEOUT_MS = 15_000L
        val EXECUTOR = Executors.newSingleThreadExecutor { Thread(it, "den-engine-connection") }
        val CRASHES = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_ANR,
        )

        fun reasonName(r: Int) = when (r) {
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_SIGNALED -> "signal"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            else -> "reason $r"
        }
    }
}

// Runs `inner` in the engine process: the instance here only gives its class and id.
internal class IsolatedBackend(private val engine: EngineConnection, inner: DenBackend) : DenBackend {
    private val cls = inner.javaClass.name
    override val id = inner.id

    @Volatile private var ver: String? = null
    @Volatile private var caps: Pair<DeviceProfile, Capabilities>? = null

    override val version: String
        get() = ver ?: call { it.engine.version(cls) }.also { ver = it }

    override fun probe(device: DeviceProfile): Capabilities {
        val d = device.copy(availRamMb = 0, thermal = ThermalState.NONE)
        caps?.takeIf { it.first == d }?.let { return it.second }
        val entries = call { it.engine.probe(cls, EngineService.JSON.encodeToString(DeviceProfile.serializer(), d)) }
        val c = Capabilities(
            entries.filter { it.startsWith("accel:") }.map { Accel.valueOf(it.removePrefix("accel:")) }.toSet(),
            entries.filter { it.startsWith("format:") }.map { ModelFormat.valueOf(it.removePrefix("format:")) }.toSet(),
        )
        caps = d to c
        return c
    }

    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session = withContext(Dispatchers.IO) {
        val b = engine.get()
        val id = try {
            b.engine.load(
                cls,
                EngineService.JSON.encodeToString(Artifact.serializer(), artifact),
                EngineService.JSON.encodeToString(LoadOptions.serializer(), opts),
            )
        } catch (e: RemoteException) {
            throw engine.error(b, e)
        }
        IsolatedSession(engine, b, id)
    }

    override fun health() = Health.Ok

    private fun <T> call(block: (EngineConnection.Bound) -> T): T {
        val b = engine.get()
        return try {
            block(b)
        } catch (e: RemoteException) {
            throw engine.error(b, e)
        }
    }
}

private class IsolatedSession(
    private val engine: EngineConnection,
    private val b: EngineConnection.Bound,
    private val id: Long,
) : Session {
    override fun generate(req: GenRequest): Flow<GenEvent> = callbackFlow {
        if (!engine.alive(b)) throw EngineLostException("the engine process restarted; the model must be loaded again")
        var done = false
        val onDeath: () -> Unit = { launch(Dispatchers.IO) { close(engine.error(b, null)) } }
        val callback = object : IGenCallback.Stub() {
            override fun onText(text: String) {
                trySendBlocking(GenEvent.Text(text))
            }

            override fun onDone(stats: LongArray) {
                done = true
                trySendBlocking(GenEvent.Done(GenStats.of(stats)))
                close()
            }

            override fun onError(message: String) {
                done = true
                close(RuntimeException(message))
            }
        }
        if (!engine.watch(b, onDeath)) {
            onDeath()
        } else {
            try {
                b.engine.generate(
                    id, req.messages.map { it.role.name }.toTypedArray(), req.messages.map { it.content }.toTypedArray(),
                    req.maxTokens, req.temperature, req.topP, req.stop.toTypedArray(), callback,
                )
            } catch (e: RemoteException) {
                close(engine.error(b, e))
            }
        }
        awaitClose {
            engine.unwatch(onDeath)
            if (!done && engine.alive(b)) runCatching { b.engine.cancel(callback) }
        }
    }.buffer(Channel.UNLIMITED)

    override fun unload() {
        if (engine.alive(b)) runCatching { b.engine.unload(id) }
    }
}
