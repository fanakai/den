package ai.fanak.den.testapp

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import android.content.Context
import android.util.Log
import java.io.File

// The models were copied into <filesDir>/models with adb run-as before the SDK existed.
fun Context.testDen(useMmap: Boolean = true) = Den.connect(
    this,
    DenConfig(
        modelDir = File(filesDir, "models"),
        useMmap = useMmap,
        onDownload = { Log.i("DenTest", "download ${it.fileName} ${it.bytes shr 20}/${it.total shr 20} MB") },
    ),
)
