package ai.fanak.den.testapp

import ai.fanak.den.Den
import ai.fanak.den.DEN_CERTIFICATES
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.backend.llamacpp.LlamaCppBackend
import android.content.Context
import android.util.Log
import java.io.File

// The models were copied into <filesDir>/models with adb run-as before the SDK existed.
// With `crash`, CrashBackend comes before llama.cpp, so the router tries it first. `denCertificates` replaces the
// trusted Den app signers (tests of the signature check).
fun Context.testDen(
    useMmap: Boolean = true,
    mode: DenMode = DenMode.AUTO,
    crash: Boolean = false,
    denCertificates: List<String> = DEN_CERTIFICATES,
    bundledRecipes: Boolean = true,
    contextLength: Int = 4096,
    minContextLength: Int? = null,
    aliases: Map<String, List<String>> = ai.fanak.den.DEFAULT_ALIASES,
) = Den.connect(
    this,
    DenConfig(
        modelDir = File(filesDir, "models"),
        useMmap = useMmap,
        mode = mode,
        denCertificates = denCertificates,
        bundledRecipes = bundledRecipes,
        contextLength = contextLength,
        minContextLength = minContextLength,
        aliases = aliases,
        backends = if (crash) listOf(CrashBackend(this), LlamaCppBackend()) else null,
        onDownload = { Log.i("DenTest", "download ${it.fileName} ${it.bytes shr 20}/${it.total shr 20} MB") },
    ),
)

// Only parts from outside the SDK: the echo backend, catalog and store, in the app process.
fun Context.echoDen() = Den.connect(
    this,
    DenConfig(mode = DenMode.EMBEDDED, backends = listOf(EchoBackend), catalogs = listOf(EchoCatalog), store = EchoStore),
)
