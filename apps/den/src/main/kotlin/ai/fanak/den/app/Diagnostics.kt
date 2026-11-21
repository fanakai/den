package ai.fanak.den.app

import ai.fanak.den.Den
import ai.fanak.den.ModelFiles
import ai.fanak.den.core.backend.GenStats
import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/**
 * The diagnostics file the user shares from the Den screen: device, failures, recent requests and the Den log, with
 * no prompt or reply text. Nothing leaves the phone unless the user shares it.
 */
object Diagnostics {
    // One request: what ran and how fast, never what was said.
    data class Request(
        val at: Long, val model: String, val backend: String, val accel: String, val quant: String,
        val ttftMs: Long?, val stats: GenStats, val thermal: String,
    )

    private const val KEEP = 50
    private val requests = ArrayDeque<Request>()

    fun record(r: Request) = synchronized(requests) {
        requests.addLast(r)
        while (requests.size > KEEP) requests.removeFirst()
    }

    fun recent(): List<Request> = synchronized(requests) { requests.toList() }

    // Log tags of Den's own code; the system shows an app only its own lines.
    private val TAGS = listOf("DenService", "Den", "den-llama", "DenEngine")

    fun build(context: Context, den: Den): String = buildString {
        val p = den.device()
        appendLine("# Fanak Den diagnostics, ${Instant.now()}")
        appendLine("No prompt or reply text is in this file.")
        appendLine()
        appendLine("## Device")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("build ${Build.FINGERPRINT}")
        appendLine("SoC ${p.soc} (${p.socFamily}), ABI ${p.abi}, GPU ${p.gpu ?: "unknown"}, NPU ${p.npu?.vendor ?: "none"}")
        appendLine("RAM ${p.availRamMb}/${p.ramMb} MB free, thermal ${p.thermal}")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Den app $version, ${den.recipes.size} recipes, ${den.localRecipes.find(p).size} local")
        val files = ModelFiles(File(context.filesDir, "den/models"))
        appendLine("models: ${files.list().joinToString { "${it.model} ${it.quant} ${it.bytes shr 20} MB${if (it.complete) "" else " (unfinished)"}" }.ifEmpty { "none" }}")
        appendLine()
        appendLine("## Plans")
        den.models.forEach { m -> appendLine("$m: ${den.plan(m).joinToString { "${it.backend.id} ${it.accel} ${it.variant.quant} (${it.source})" }.ifEmpty { "nothing runs" }}") }
        appendLine()
        appendLine("## Failures")
        appendLine(File(context.filesDir, "den/failures.json").takeIf { it.isFile }?.readText()?.ifBlank { null } ?: "none")
        appendLine()
        appendLine("## Recent requests")
        val rs = recent()
        if (rs.isEmpty()) appendLine("none since the Den app started")
        rs.forEach { r ->
            val s = r.stats
            appendLine(
                "${Instant.ofEpochMilli(r.at)} ${r.model} ${r.backend} ${r.accel} ${r.quant}: first token ${r.ttftMs ?: "-"} ms, " +
                    "prompt ${s.promptTokens} tok %.1f tok/s, output ${s.outputTokens} tok %.1f tok/s, ${s.finish}, thermal ${r.thermal}"
                        .format(s.prefillTps, s.decodeTps),
            )
        }
        appendLine()
        appendLine("## Log")
        log().forEach { appendLine(it) }
    }

    // The recent lines of Den's tags. A parse error can quote the request it failed on, so such lines are left out.
    private fun log(): List<String> = runCatching {
        val args = listOf("logcat", "-d", "-t", "1000", "-v", "threadtime", "-s") + TAGS.map { "$it:V" }
        Runtime.getRuntime().exec(args.toTypedArray()).inputStream.bufferedReader().readLines()
    }.getOrElse { listOf("log not readable: $it") }.filter { safe(it) }

    fun safe(line: String) = "JSON input" !in line && "\"content\"" !in line && "\"messages\"" !in line && !EMPTY.containsMatchIn(line)

    // llama.cpp's load progress: a dot per line.
    private val EMPTY = Regex(""":\s*\.*\s*$""")
}
