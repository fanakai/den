package ai.fanak.den

import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.router.Source
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal const val DEN_PACKAGE = "ai.fanak.den"
private const val DEN_BIND = "ai.fanak.den.BIND"

// Binding to the Den app's service (connected mode).
internal class DenAppConnection(private val context: Context) {
    private val lock = Mutex()
    private var service: IDenService? = null
    private var conn: ServiceConnection? = null

    private fun intent() = Intent(DEN_BIND).setPackage(DEN_PACKAGE)

    fun installed() = context.packageName != DEN_PACKAGE && context.packageManager.resolveService(intent(), 0) != null

    private suspend fun service(): IDenService = lock.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        close()
        withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        val s = IDenService.Stub.asInterface(binder)
                        service = s
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        service = null
                    }

                    override fun onBindingDied(name: ComponentName) {
                        service = null
                    }
                }
                conn = c
                if (!context.bindService(intent(), c, Context.BIND_AUTO_CREATE)) {
                    conn = null
                    cont.resumeWithException(IOException("cannot bind to the Den app"))
                }
            }
        }
    }

    suspend fun open(model: String): DenModel {
        val svc = service()
        return suspendCancellableCoroutine { cont ->
            svc.open(model, object : IOpenCallback.Stub() {
                override fun onOpened(session: Long, backend: String, accel: String, quant: String, source: String) {
                    val m = RemoteModel(model, svc, session, backend, Accel.valueOf(accel), quant, Source.valueOf(source))
                    if (cont.isActive) cont.resume(m) else m.close()
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resumeWithException(IOException("Den app: $message"))
                }
            })
        }
    }

    fun close() {
        conn?.let { runCatching { context.unbindService(it) } }
        conn = null
        service = null
    }
}

internal class RemoteModel(
    override val name: String,
    private val svc: IDenService,
    private val session: Long,
    override val backend: String,
    override val accel: Accel,
    override val quant: String,
    override val source: Source,
) : DenModel() {
    override val connected = true

    override fun chat(request: GenRequest): Flow<GenEvent> = callbackFlow {
        var done = false
        svc.generate(
            session,
            request.messages.map { it.role.name.lowercase() }.toTypedArray(),
            request.messages.map { it.content }.toTypedArray(),
            request.maxTokens,
            request.temperature,
            request.topP,
            request.stop.toTypedArray(),
            object : IGenCallback.Stub() {
                override fun onText(text: String) {
                    trySend(GenEvent.Text(text))
                }

                override fun onDone(stats: LongArray) {
                    done = true
                    trySend(GenEvent.Done(GenStats(stats[0].toInt(), stats[1].toInt(), stats[2], stats[3])))
                    close()
                }

                override fun onError(message: String) {
                    done = true
                    close(IOException("Den app: $message"))
                }
            },
        )
        awaitClose { if (!done) runCatching { svc.cancel(session) } }
    }

    override fun close() {
        runCatching { svc.close(session) }
    }
}
