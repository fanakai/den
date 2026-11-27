package ai.fanak.den

import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.UnsupportedFeatureException
import ai.fanak.den.core.backend.features
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.DownloadProgress
import ai.fanak.den.core.router.NoUsableBackendException
import ai.fanak.den.core.router.RouteHint
import ai.fanak.den.core.router.Source
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal const val DEN_PACKAGE = "ai.fanak.den"
private const val DEN_BIND = "ai.fanak.den.BIND"

/**
 * SHA-256 of the certificates the Den app may be signed with: the release key, and the debug key of the maintainer's
 * machine for test builds. Any app can be installed under the name `ai.fanak.den` on a phone without Den.
 */
val DEN_CERTIFICATES = listOf(
    "18cceb3706589de26a13391755582a0b73db2b6770073296a9794a71a50124a9", // release, CN=Fanak Den, 2026-11-20
    "963cb88f53be718d5942b2f86eaaaea3da0f2035ead12c33a6b3ff796220c05c", // debug
)

internal enum class DenAppStatus { MISSING, UNTRUSTED, OK }

// Binding to the Den app's service (connected mode).
internal class DenAppConnection(
    private val context: Context,
    private val onDownload: (DownloadProgress) -> Unit,
    private val certificates: List<String> = DEN_CERTIFICATES,
    private val approvalScreen: Boolean = true,
) {
    private val lock = Mutex()
    private var service: IDenService? = null
    private var conn: ServiceConnection? = null

    private fun intent() = Intent(DEN_BIND).setPackage(DEN_PACKAGE)

    fun status(): DenAppStatus {
        val pm = context.packageManager
        if (context.packageName == DEN_PACKAGE || pm.resolveService(intent(), 0) == null) return DenAppStatus.MISSING
        // Checks the whole signing history, so a release key rotated later still matches.
        val signed = certificates.any { sha ->
            runCatching { pm.hasSigningCertificate(DEN_PACKAGE, sha.hexBytes(), PackageManager.CERT_INPUT_SHA256) }
                .getOrDefault(false)
        }
        return if (signed) DenAppStatus.OK else DenAppStatus.UNTRUSTED
    }

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

    // The Den app's OpenAI-compatible endpoint, or null when it is off or the Den app is older than version 4.
    suspend fun httpEndpoint(): String? {
        val svc = service()
        if (svc.version() < 4) return null
        return svc.httpPort().takeIf { it > 0 }?.let { "http://127.0.0.1:$it/v1" }
    }

    // A Den app older than version 7 ignores the hint; it has no server to send the model to.
    private fun options(contextLength: Int, minContextLength: Int, hint: RouteHint? = null) =
        org.json.JSONObject().put("contextLength", contextLength).put("minContextLength", minContextLength)
            .apply { if (hint != null) put("hint", hint.name.lowercase()) }.toString()

    // The Den app's route, or null when it is older than version 5.
    suspend fun plan(model: String, contextLength: Int, minContextLength: Int): List<Route>? {
        val svc = service()
        if (svc.version() < 5) return null
        return svc.plan(model, options(contextLength, minContextLength)).map { line ->
            val (backend, accel, quant, source) = line.split(' ')
            Route(backend, Accel.valueOf(accel), quant, Source.valueOf(source))
        }
    }

    suspend fun open(model: String, contextLength: Int, minContextLength: Int, hint: RouteHint? = null): DenModel {
        val svc = service()
        val v5 = svc.version() >= 5
        // A Den app older than version 8 would read an unknown hint as private: FAST goes without a hint there.
        val sent = if (hint == RouteHint.FAST && svc.version() < 8) null else hint
        return suspendCancellableCoroutine { cont ->
            val callback = object : IOpenCallback.Stub() {
                @Volatile private var context = 0
                @Volatile private var resolved: String? = null

                override fun onOpened(session: Long, backend: String, accel: String, quant: String, source: String) {
                    val name = resolved ?: model
                    val m = RemoteModel(name, svc, session, backend, Accel.valueOf(accel), quant, Source.valueOf(source), context)
                    m.alias = model.takeIf { it != name }
                    if (cont.isActive) cont.resume(m) else m.close()
                }

                override fun onModel(model: String) {
                    resolved = model
                }

                override fun onContext(contextLength: Int) {
                    context = contextLength
                }

                // The same exception as in embedded mode when no candidate could run.
                override fun onFailed(kind: String, message: String) {
                    if (!cont.isActive) return
                    cont.resumeWithException(
                        if (kind == "no_backend") NoUsableBackendException(model, emptyList(), message) else IOException("Den app: $message"),
                    )
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resumeWithException(IOException("Den app: $message"))
                }

                override fun onProgress(file: String, bytes: Long, total: Long) {
                    if (cont.isActive) onDownload(DownloadProgress(file, bytes, total))
                }

                override fun onApproval(screen: PendingIntent) {
                    if (cont.isActive && approvalScreen) showApproval(screen)
                }
            }
            cont.invokeOnCancellation { runCatching { svc.cancelOpen(callback) } }
            if (v5) svc.openWith(model, options(contextLength, minContextLength, sent), callback) else svc.open(model, callback)
        }
    }

    // Android lets the Den app's screen open only while this app is visible; otherwise the notification remains.
    private fun showApproval(screen: PendingIntent) {
        val options = when {
            Build.VERSION.SDK_INT >= 36 ->
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE,
                ).toBundle()
            Build.VERSION.SDK_INT >= 34 ->
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                ).toBundle()
            else -> null
        }
        runCatching { screen.send(context, 0, null, null, null, null, options) }
            .onFailure { android.util.Log.w("Den", "cannot open the Den app's approval screen: $it") }
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
    override val contextLength: Int = 0,
) : DenModel() {
    override val connected = true

    override fun chat(request: GenRequest): Flow<GenEvent> = callbackFlow {
        // The AIDL request has no grammar, schema or tools yet; the Den app's HTTP endpoint has them.
        request.features.firstOrNull()?.let { throw UnsupportedFeatureException(it, "the Den app connection") }
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
                    trySendBlocking(GenEvent.Text(text))
                }

                override fun onDone(stats: LongArray) {
                    done = true
                    trySendBlocking(GenEvent.Done(GenStats.of(stats)))
                    close()
                }

                override fun onError(message: String) {
                    done = true
                    close(IOException("Den app: $message"))
                }
            },
        )
        awaitClose { if (!done) runCatching { svc.cancel(session) } }
    }.buffer(Channel.UNLIMITED) // the service cannot be slowed across oneway calls; a full buffer would drop text

    override fun close() {
        runCatching { svc.close(session) }
    }
}

internal fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
