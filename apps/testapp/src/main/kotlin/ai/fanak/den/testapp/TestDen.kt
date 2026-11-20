package ai.fanak.den.testapp

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import android.content.Context
import android.util.Log
import java.io.File

// The models were copied into <filesDir>/models with adb run-as before the SDK existed.
fun Context.testDen(useMmap: Boolean = true, mode: DenMode = DenMode.AUTO) = Den.connect(
    this,
    DenConfig(
        modelDir = File(filesDir, "models"),
        useMmap = useMmap,
        mode = mode,
        onDownload = { Log.i("DenTest", "download ${it.fileName} ${it.bytes shr 20}/${it.total shr 20} MB") },
    ),
)

// Only parts from outside the SDK: the echo backend, catalog and store, in the app process.
fun Context.echoDen() = Den.connect(
    this,
    DenConfig(mode = DenMode.EMBEDDED, backends = listOf(EchoBackend), catalogs = listOf(EchoCatalog), store = EchoStore),
)
