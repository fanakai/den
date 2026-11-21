package ai.fanak.den.app

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.ModelFiles
import ai.fanak.den.core.bench.BenchResult
import ai.fanak.den.core.model.HubLinks
import ai.fanak.den.core.bench.Contribute
import ai.fanak.den.core.recipe.RecipeFiles
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What Den sees on this phone, and which apps may use it. Model management UI comes later.
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var models: LinearLayout
    private var status: String? = null
    private lateinit var apps: LinearLayout
    private lateinit var http: LinearLayout
    private lateinit var downloads: LinearLayout
    private lateinit var aliasesView: LinearLayout
    private lateinit var bench: LinearLayout
    private lateinit var diag: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val den = Den.connect(this)
        val p = den.device()
        val text = buildString {
            appendLine("Fanak Den")
            appendLine()
            appendLine("${p.soc} (${p.socFamily}), API ${p.osApi}, RAM ${p.availRamMb}/${p.ramMb} MB free")
            appendLine("models in ${den.modelDir.path}")
            den.models.forEach { m ->
                appendLine()
                appendLine("$m, licence ${den.licence(m) ?: "not given"}")
                den.plan(m).forEachIndexed { i, c -> appendLine("  $i. ${c.backend.id} ${c.accel} ${c.variant.quant} (${c.source})") }
            }
        }
        models = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        http = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        downloads = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        aliasesView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bench = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diag = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { this.text = text })
            addView(models)
            addView(backendsView)
            addView(aliasesView)
            addView(downloads)
            addView(bench)
            addView(apps)
            addView(http)
            addView(diag)
            addView(notices())
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // The endpoint does not survive a reboot or update; opening the Den app brings it back.
        if (HttpSettings.enabled(this)) startForegroundService(Intent(this, DenService::class.java))
        showModels()
        showDownloads()
        showAliases()
        showBench()
        showApps()
        showHttp()
        showDiagnostics()
    }

    // Each alias, the model behind it now, and a button that moves it: through the models, then back to its defaults.
    private fun showAliases() {
        aliasesView.removeAllViews()
        aliasesView.addView(TextView(this).apply { text = "\nAliases"; textSize = 18f })
        aliasesView.addView(TextView(this).apply { text = "Apps can ask for an alias instead of a model, and keep working when it moves." })
        val aliases = benchDen.aliases.also { AliasSettings.applyTo(this, it) }
        aliases.names.sorted().forEach { alias ->
            val row = TextView(this)
            aliasesView.addView(row)
            val set = aliases.override(alias)
            scope.launch {
                val now = benchDen.resolveAlias(alias)
                row.text = "$alias: $now" + if (set != null) " (set by you)" else " (first of ${aliases.defaults(alias).joinToString()} that runs)"
            }
            val choices = listOf<String?>(null) + benchDen.models
            val next = choices[(choices.indexOf(set) + 1) % choices.size]
            aliasesView.addView(button("Change $alias to ${next ?: "its defaults"}") {
                DenService.setAlias(this, alias, next)
                aliases.set(alias, next)
            })
        }
    }

    private fun showDownloads() {
        downloads.removeAllViews()
        downloads.addView(TextView(this).apply { text = "\nDownloads"; textSize = 18f })
        val used = ModelFiles(File(filesDir, "den/models")).usedBytes
        val quota = Downloads.quotaGb(this)
        downloads.addView(TextView(this).apply { text = "Quota: ${used shr 20} of ${quota.toLong() shl 10} MB used" })
        val field = EditText(this).apply { hint = "GB"; setText(quota.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        downloads.addView(LinearLayout(this).apply {
            addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Set quota (GB)") {
                runCatching { Downloads.setQuotaGb(context, field.text.toString().toInt()) }
                    .onFailure { Toast.makeText(context, "quota must be between 1 and 1024 GB", Toast.LENGTH_SHORT).show() }
            })
        })
        val mobile = Downloads.mobileData(this)
        downloads.addView(TextView(this).apply {
            text = if (mobile) "Large files download over mobile data too." else "Large files wait for Wi-Fi or another unmetered network."
        })
        Downloads.waitingForWifi.forEach { f -> downloads.addView(TextView(this).apply { text = "$f: waiting for Wi-Fi" }) }
        downloads.addView(button(if (mobile) "Wait for Wi-Fi" else "Allow mobile data") { Downloads.setMobileData(this, !mobile) })
        downloads.addView(TextView(this).apply { text = "Hub repositories (hf:, ms:) are downloaded only once you approve them." })
        Downloads.pending.forEach { a ->
            downloads.addView(TextView(this).apply { text = "${a.model} for ${a.by}: ${Downloads.describe(a)}, waiting" })
            downloads.addView(LinearLayout(this).apply {
                addView(button("Download") { Downloads.answer(context, a.model, true) })
                addView(button("Deny") { Downloads.answer(context, a.model, false) })
            })
        }
        Downloads.approved(this).forEach { m ->
            downloads.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = "$m: approved" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("Forget") { Downloads.forget(context, m) })
            })
        }
    }

    // On the downloaded files only, for den bench and the diagnostics: never downloads.
    private val benchDenLazy = lazy { Den.connect(this, DenConfig(mode = DenMode.EMBEDDED, download = false)) }
    private val benchDen by benchDenLazy
    private var benchJob: Job? = null
    private var benchRunning = false
    private var benchLines = listOf<String>()
    private var benched: Pair<BenchResult, String?>? = null

    private fun showBench() {
        bench.removeAllViews()
        bench.addView(TextView(this).apply { text = "\nBenchmark"; textSize = 18f })
        bench.addView(TextView(this).apply {
            text = "Measures each downloaded file of a model on this phone, a few minutes per file. " +
                "Keep this screen open, with power saving off; the result is a recipe you can send to Fanak Den."
        })
        val files = ModelFiles(File(filesDir, "den/models")).list().filter { it.complete }
        if (benchRunning) {
            bench.addView(button("Stop") { benchJob?.cancel() })
        } else {
            if (files.isEmpty()) bench.addView(TextView(this).apply { text = "Download a model first." })
            files.groupBy { it.model }.forEach { (m, fs) ->
                bench.addView(button("Benchmark $m (${fs.joinToString { it.quant }})") { startBench(m, fs.map { it.quant }.toSet()) })
            }
        }
        benchLines.forEach { l -> bench.addView(TextView(this).apply { text = l }) }
        val (r, yaml) = benched ?: return
        if (yaml == null) return
        bench.addView(TextView(this).apply { text = yaml; textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        bench.addView(LinearLayout(this).apply {
            addView(button("Copy") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Den recipe", yaml))
            })
            addView(button("Share") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, yaml), null))
            })
            // A new SoC becomes a pull request with the file; a known one an issue, so the maintainers merge it.
            val known = benchDen.recipes.any { it.soc == r.soc }
            addView(button(if (known) "Send as issue" else "Open pull request") {
                val url = if (known) Contribute.issueUrl(r, yaml) else Contribute.newRecipeUrl(r.soc, yaml)
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(context, "no browser to open GitHub", Toast.LENGTH_SHORT).show() }
            })
        })
    }

    private fun startBench(model: String, quants: Set<String>) {
        benched = null
        benchLines = listOf("Starting $model")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        benchRunning = true
        benchJob = scope.launch {
            try {
                if (!DenService.unload(model)) error("$model is generating for an app; try again when it is idle")
                val r = benchDen.bench(model, quants = quants) { line -> benchLines = benchLines + line; showBench() }
                val yaml = r.recipe()?.let { RecipeFiles.encode(it) }
                withContext(Dispatchers.IO) {
                    val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
                    val name = "${r.soc}-${model.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                    File(dir, "$name.json").writeText(r.toJson())
                    yaml?.let { File(dir, "$name.yaml").writeText(it) }
                }
                benched = r to yaml
                benchLines = r.ranked().map { c ->
                    "${c.accel.name.lowercase()} ${c.quant}: prefill ${c.prefillTps!!.median}, decode ${c.decodeTps!!.median} tok/s"
                } + r.candidates.filter { !it.ok }.map { "${it.accel.name.lowercase()} ${it.quant}: ${it.error}" } +
                    if (yaml == null) listOf("No recipe: nothing ran, or the model size is unknown.") else emptyList()
            } catch (e: CancellationException) {
                benchLines = benchLines + "Stopped"
                throw e
            } catch (e: Exception) {
                benchLines = benchLines + "Failed: ${e.message}"
            } finally {
                benchRunning = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showBench()
            }
        }
        showBench()
    }

    // The notices of the code in Den (assets/den/NOTICE and den/licenses, from the SDK), shown on request.
    private fun notices() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = "\nLicences"; textSize = 18f })
        addView(TextView(context).apply { text = "Fanak Den is open source under the Apache License 2.0, and includes other open source software." })
        val text = TextView(context).apply { textSize = 10f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        addView(Button(context).apply {
            this.text = "Show licences"
            setOnClickListener {
                text.text = buildString {
                    append(assets.open("den/NOTICE").bufferedReader().use { it.readText() })
                    assets.list("den/licenses").orEmpty().sorted().forEach { name ->
                        append("\n\n==== $name ====\n\n")
                        append(assets.open("den/licenses/$name").bufferedReader().use { it.readText() })
                    }
                }
                visibility = android.view.View.GONE
            }
        })
        addView(text)
    }

    // The backends with their descriptors; filled once, off the main thread (the version comes from the engine).
    private val backendsView by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "\nBackends"; textSize = 18f })
            scope.launch {
                benchDen.backendDescriptors().forEach { d ->
                    addView(TextView(context).apply {
                        text = "${d.name} ${d.version} (${d.id}), ${d.tier.name.lowercase()}, ${d.licence}\n" +
                            "${d.author}, ${d.url}\n${d.modes.joinToString { it.name.lowercase() }}, " +
                            "${d.formats.joinToString { it.id }}, slots ${d.slots.name.lowercase()}"
                    })
                }
            }
        }
    }

    // The diagnostics file, shown before it is shared; null until the user asks for it.
    private var diagnostics: File? = null

    private fun showDiagnostics() {
        diag.removeAllViews()
        diag.addView(TextView(this).apply { text = "\nDiagnostics"; textSize = 18f })
        diag.addView(TextView(this).apply {
            text = "A file with this phone, the recorded failures, recent speeds and the Den log, to attach to a bug report " +
                "or a recipe. It holds no prompt or reply text, and leaves the phone only if you share it."
        })
        diag.addView(button("Prepare diagnostics") {
            scope.launch {
                diagnostics = withContext(Dispatchers.IO) {
                    val dir = DiagnosticsProvider.dir(this@MainActivity).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    File(dir, "den-diagnostics-${java.time.LocalDate.now()}.txt").apply { writeText(Diagnostics.build(this@MainActivity, benchDen)) }
                }
                showDiagnostics()
            }
        })
        val f = diagnostics ?: return
        diag.addView(button("Share ${f.name}") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, DiagnosticsProvider.uri(f))
                .putExtra(Intent.EXTRA_SUBJECT, "Fanak Den diagnostics")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, null))
        })
        diag.addView(TextView(this).apply { text = f.readText(); textSize = 10f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
    }

    private fun showApps() {
        apps.removeAllViews()
        apps.addView(TextView(this).apply { text = "\nApps"; textSize = 18f })
        // Waiting, or closed without an answer (Allow or Deny here ends the quiet time).
        val rows = (Approvals.pending + Approvals.dismissed).associateWith { null as Boolean? } + Approvals.all(this)
        if (rows.isEmpty()) apps.addView(TextView(this).apply { text = "No app has asked yet." })
        rows.forEach { (pkg, allowed) ->
            val state = when (allowed) {
                true -> "allowed"; false -> "denied"
                null -> if (pkg in Approvals.pending) "waiting" else "not answered"
            }
            apps.addView(TextView(this).apply { text = "${Approvals.label(context, pkg)} ($pkg): $state" })
            apps.addView(LinearLayout(this).apply {
                if (allowed != true) addView(button("Allow") { Approvals.set(context, pkg, true) })
                if (allowed != false) addView(button("Deny") { Approvals.set(context, pkg, false) })
                if (allowed != null) addView(button("Forget") { Approvals.forget(context, pkg) })
            })
        }
    }

    private fun showModels() {
        models.removeAllViews()
        val files = ModelFiles(File(filesDir, "den/models"))
        models.addView(TextView(this).apply { text = "\nModels"; textSize = 18f })
        models.addView(TextView(this).apply {
            text = "${files.usedBytes shr 20} MB used by models, ${files.freeBytes shr 20} MB free on the phone"
        })
        status?.let { s -> models.addView(TextView(this).apply { text = s }) }
        val list = files.list()
        if (list.isEmpty()) models.addView(TextView(this).apply { text = "No model downloaded yet." })
        list.forEach { f ->
            val size = if (f.complete) "${f.bytes shr 20} MB" else "${f.bytes shr 20}/${f.totalBytes shr 20} MB, unfinished"
            models.addView(TextView(this).apply { text = "${f.model} ${f.quant}: ${f.fileName}, $size, licence ${f.licence ?: "not given"}" })
        }
        list.map { it.model }.distinct().forEach { m ->
            val kept = Pins.has(this, m)
            models.addView(LinearLayout(this).apply {
                addView(button("Delete $m") { this@MainActivity.run { DenService.delete(this@MainActivity, m) } })
                // Kept loaded once loaded: never unloaded for another model or when idle, only by critical memory.
                addView(button(if (kept) "Kept loaded" else "Keep loaded") { Pins.set(context, m, !kept) })
            })
        }
        if (list.any { !it.complete }) models.addView(button("Remove unfinished downloads") { run { DenService.deletePartial(this) } })
        list.map { it.model }.distinct().filter { it.startsWith("hf:") }.forEach { m ->
            models.addView(button("Check $m for updates") {
                run { benchDen.newerCommit(m)?.let { "$m: a newer commit (${it.take(7)}) is on the hub" } ?: "$m is up to date" }
            })
        }
        // GGUF files the user already has: used where they are, never copied.
        benchDen.addedFiles.forEach { a ->
            models.addView(TextView(this).apply { text = "${a.id}: ${a.name}, ${a.sizeBytes shr 20} MB, added from storage (not copied)" })
            models.addView(button("Remove ${a.id}") { benchDen.removeFile(a.id); status = "Removed ${a.id}; the file itself stays" })
        }
        models.addView(button("Add a model file") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(pick, PICK_FILE)
        })
        models.addView(button("Add a folder of model files") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER) })
        benchDen.addedFolders.forEach { f ->
            val name = f.lastPathSegment?.substringAfterLast(':') ?: "folder"
            models.addView(LinearLayout(this).apply {
                addView(button("Look again in $name") { addFolder(f) })
                addView(button("Forget $name") { benchDen.removeFolder(f); status = "Forgot $name; its files stay" })
            })
        }
        // A pasted Hugging Face link gives the model id apps use; the download starts when an app opens it.
        val link = EditText(this).apply { hint = "Hugging Face link or hf:owner/repo"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        models.addView(link, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        models.addView(button("Get model id") {
            status = HubLinks.modelId(link.text.toString())?.let { id ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Den model", id))
                "Apps can use $id (copied)"
            } ?: "Not a Hugging Face link"
        })
    }

    // Runs a file action off the main thread, then shows its result.
    private fun run(action: suspend () -> String) {
        scope.launch {
            status = withContext(Dispatchers.IO) { action() }
            showModels()
        }
    }

    // The picked GGUF: hashed once (it is read in full) off the main thread, then listed.
    @Deprecated("Activity result API needs AndroidX, which the Den app does not use")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == PICK_FOLDER) return addFolder(uri)
        if (requestCode != PICK_FILE) return
        status = "Adding the file: reading it once to check it…"
        showModels()
        scope.launch {
            status = runCatching { benchDen.addFile(uri) }
                .fold({ "Added ${it.id}: apps can use it by that name" }, { "Not added: ${it.message}" })
            showModels()
        }
    }

    // Every GGUF of the folder, each read once to check it; files already added are skipped.
    private fun addFolder(tree: android.net.Uri) {
        status = "Adding the files of the folder: reading each once…"
        showModels()
        scope.launch {
            status = runCatching { benchDen.addFolder(tree) }
                .fold({ found -> if (found.isEmpty()) "No new GGUF file in the folder" else "Added ${found.joinToString { it.id }}" }, { "Not added: ${it.message}" })
            showModels()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (benchDenLazy.isInitialized()) benchDen.close()
        super.onDestroy()
    }

    private fun showHttp() {
        http.removeAllViews()
        val on = HttpSettings.enabled(this)
        http.addView(TextView(this).apply { text = "\nHTTP endpoint (OpenAI-compatible)"; textSize = 18f })
        val port = HttpSettings.port(this)
        http.addView(TextView(this).apply {
            text = if (on) "On: http://127.0.0.1:$port/v1, for apps on this phone only" else "Off"
        })
        http.addView(button(if (on) "Turn off" else "Turn on") {
            HttpSettings.setEnabled(this, !on)
            startForegroundService(Intent(this, DenService::class.java))
        })
        val portField = EditText(this).apply { hint = "port"; setText(port.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        http.addView(LinearLayout(this).apply {
            addView(portField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Set port") {
                runCatching { HttpSettings.setPort(context, portField.text.toString().toInt()) }
                    .onFailure { Toast.makeText(context, "port must be between 1024 and 65535", Toast.LENGTH_SHORT).show() }
                if (HttpSettings.enabled(context)) startForegroundService(Intent(context, DenService::class.java))
            })
        })
        http.addView(TextView(this).apply { text = "Each client sends its token as the API key (Authorization: Bearer)." })
        val name = EditText(this).apply { hint = "client name" }
        http.addView(name)
        http.addView(button("New token") {
            val client = name.text.toString().ifBlank { "client" }
            fresh = client to HttpSettings.create(this, client)
        })
        // Shown once: only its hash is stored.
        fresh?.let { (client, token) ->
            http.addView(TextView(this).apply {
                text = "New token for $client, shown only now:\n$token"
                setTextIsSelectable(true)
            })
            http.addView(button("Copy") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Den token", token))
            })
        }
        HttpSettings.all(this).forEach { (key, client) ->
            http.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = client }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("Revoke") { HttpSettings.revoke(context, key) })
            })
        }
    }

    // The token made on this screen, until the screen closes.
    private var fresh: Pair<String, String>? = null

    private companion object {
        const val PICK_FILE = 7
        const val PICK_FOLDER = 8
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick(); showModels(); showDownloads(); showAliases(); showBench(); showApps(); showHttp(); showDiagnostics() }
    }
}
