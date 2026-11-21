package ai.fanak.den.testapp

import ai.fanak.den.IDenService
import ai.fanak.den.IGenCallback
import ai.fanak.den.IOpenCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

// Raw AIDL check of the Den app service; the SDK wraps this in connected mode next.
// am start -n ai.fanak.den.testapp/.MainActivity --ez remote true --es model qwen3-0.6b
fun Context.remoteCheck(model: String, prompt: String) {
    val log = { msg: String -> Log.i("DenRemote", msg) }
    val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = IDenService.Stub.asInterface(binder)
            log("connected, version ${svc.version()}, models ${svc.models().joinToString()}")
            val t0 = System.currentTimeMillis()
            svc.open(model, object : IOpenCallback.Stub() {
                override fun onOpened(session: Long, backend: String, accel: String, quant: String, source: String) {
                    log("session $session: $backend $accel $quant ($source), open ${System.currentTimeMillis() - t0} ms")
                    val sb = StringBuilder()
                    svc.generate(session, arrayOf("user"), arrayOf(prompt), 128, 0f, 1f, emptyArray(), object : IGenCallback.Stub() {
                        override fun onText(text: String) { sb.append(text) }

                        override fun onDone(stats: LongArray) {
                            log("text: ${sb.toString().replace('\n', ' ')}")
                            log("prompt ${stats[0]} tok, %.1f tok/s | output ${stats[1]} tok, %.1f tok/s".format(
                                stats[0] * 1000.0 / maxOf(stats[2], 1), stats[1] * 1000.0 / maxOf(stats[3], 1)))
                            svc.close(session)
                        }

                        override fun onError(message: String) { log("generate error: $message") }
                    })
                }

                override fun onError(message: String) { log("open error: $message") }

                override fun onProgress(file: String, bytes: Long, total: Long) { log("download $file ${bytes shr 20}/${total shr 20} MB") }

                override fun onApproval(screen: android.app.PendingIntent) { log("approval screen offered") }

                override fun onContext(contextLength: Int) { log("context $contextLength") }

                override fun onFailed(kind: String, message: String) { log("open failed ($kind): $message") }

                override fun onModel(model: String) { log("model $model") }
            })
        }

        override fun onServiceDisconnected(name: ComponentName) { log("disconnected") }
    }
    val ok = bindService(Intent("ai.fanak.den.BIND").setPackage("ai.fanak.den"), conn, Context.BIND_AUTO_CREATE)
    log("bind $ok")
}
