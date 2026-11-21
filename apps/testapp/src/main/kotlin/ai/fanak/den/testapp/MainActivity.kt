package ai.fanak.den.testapp

import ai.fanak.den.DEN_CERTIFICATES
import ai.fanak.den.Den
import ai.fanak.den.DenMode
import ai.fanak.den.DenModel
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.bench.Contribute
import ai.fanak.den.core.router.NoUsableBackendException
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Drive from adb: am start -n ai.fanak.den.testapp/.MainActivity --es model qwen3-0.6b --es prompt "..." --ez autorun true
class MainActivity : Activity() {
    private val scope = MainScope()
    private val den by lazy {
        val mode = intent.getStringExtra("mode")?.let { DenMode.valueOf(it.uppercase()) } ?: DenMode.AUTO
        val crash = intent.getStringExtra("crash")?.also { armCrash(it) }
        // "denCerts": trusted Den app signers as SHA-256, comma-separated; "none" trusts no Den app.
        val certs = intent.getStringExtra("denCerts")?.let { v -> if (v == "none") emptyList() else v.split(',') } ?: DEN_CERTIFICATES
        // "noRecipes": as a phone without a recipe, for the first-run benchmark; "clearLocal" forgets what it measured.
        if (intent.getBooleanExtra("echo", false)) echoDen()
        else testDen(intent.getBooleanExtra("mmap", true), mode, crash != null, certs, !intent.getBooleanExtra("noRecipes", false))
            .also { if (intent.getBooleanExtra("clearLocal", false)) it.localRecipes.clear() }
    }

    private lateinit var model: String
    private var loaded: DenModel? = null
    private var job: Job? = null

    private lateinit var info: TextView
    private lateinit var prompt: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = intent.getStringExtra("model") ?: den.models.first()
        intent.getStringExtra("delete")?.let { name ->
            den.files.delete(name)
            log("deleted $name")
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        info = TextView(this).apply { textSize = 12f }
        val models = LinearLayout(this)
        den.models.forEach { m -> models.addView(Button(this).apply { text = m; setOnClickListener { select(m) } }) }
        prompt = EditText(this).apply {
            setText(intent.getStringExtra("prompt") ?: "Explain in two sentences why foxes live in dens. /no_think")
        }
        val run = Button(this).apply { text = "Run"; setOnClickListener { run() } }
        output = TextView(this).apply { textSize = 14f }
        root.addView(info)
        root.addView(models)
        root.addView(prompt, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(run)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        showPlan()
        if (intent.getBooleanExtra("bench", false)) bench()
        if (intent.getBooleanExtra("compare", false)) compare()
        if (intent.getBooleanExtra("autorun", false)) run()
        if (intent.getBooleanExtra("soak", false)) {
            startForegroundService(android.content.Intent(this, SoakService::class.java).putExtras(intent))
        }
        if (intent.getBooleanExtra("remote", false)) remoteCheck(model, prompt.text.toString())
    }

    // den bench: --ez bench true [--ei runs 5] [--es quants q4_0,q4_k_m | all]. Writes <soc>-<model>.yaml (the recipe)
    // and .json (every candidate) to /sdcard/Android/data/ai.fanak.den.testapp/files/bench.
    private fun bench() {
        val runs = intent.getIntExtra("runs", 5)
        val quants = intent.getStringExtra("quants")?.let { if (it == "all") null else it.split(',').toSet() } ?: setOf("q4_0", "q4_k_m")
        // A benchmark takes minutes: the screen must not time out, or the phone slows down and the GPU stalls.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        job = scope.launch {
            try {
                val r = den.bench(model, runs, quants) { log(it) }
                val dir = java.io.File(getExternalFilesDir(null), "bench").apply { mkdirs() }
                val name = "${r.soc}-${model.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                java.io.File(dir, "$name.json").writeText(r.toJson())
                val yaml = r.recipe()?.let { ai.fanak.den.core.recipe.RecipeFiles.encode(it) }
                if (yaml != null) java.io.File(dir, "$name.yaml").writeText(yaml)
                r.ranked().forEach { c -> log("bench ranked: ${c.accel} ${c.quant}, prefill ${c.prefillTps}, decode ${c.decodeTps}, ttft ${c.ttftMs} ms, peak ${c.peakRamMb} MB") }
                r.candidates.filter { !it.ok }.forEach { c -> log("bench failed: ${c.accel} ${c.quant}: ${c.error}") }
                log("bench done: ${dir.path}/$name.${if (yaml != null) "yaml" else "json (no recipe: model size unknown or nothing ran)"}")
                yaml?.lines()?.forEach { log("recipe: $it") }
                if (yaml != null) {
                    val known = den.recipes.any { it.soc == r.soc }
                    log("bench link: ${if (known) Contribute.issueUrl(r, yaml) else Contribute.newRecipeUrl(r.soc, yaml)}")
                }
            } catch (e: Exception) {
                log("bench error: ${e.message}")
            } finally {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // --ez compare true [--ei context N --ei minContext N]: the same steps in embedded and connected mode; each
    // "compare" line should match between them.
    private fun compare() {
        val context = intent.getIntExtra("context", 4096)
        val min = intent.getIntExtra("minContext", 0).takeIf { it > 0 }
        // --es alias den.default=qwen3-0.6b: this app's own alias, for embedded mode.
        val aliases = ai.fanak.den.DEFAULT_ALIASES + intent.getStringExtra("alias")?.split('=', limit = 2)
            ?.let { (a, m) -> mapOf(a to listOf(m)) }.orEmpty()
        job = scope.launch {
            for (mode in listOf(DenMode.EMBEDDED, DenMode.CONNECTED)) {
                val d = testDen(mode = mode, contextLength = context, minContextLength = min, aliases = aliases)
                try {
                    log("compare $mode route: ${d.route(model).joinToString()}")
                    d.model(model).use { m -> log("compare $mode open: ${m.alias?.let { "$it is " } ?: ""}${m.name}, ${m.backend} ${m.accel} ${m.quant} (${m.source}), context ${m.contextLength}") }
                    val e = runCatching { d.model("no-such-model") }.exceptionOrNull()
                    log("compare $mode unknown: ${e?.javaClass?.simpleName}: ${e?.message}")
                } catch (e: Exception) {
                    log("compare $mode error: $e")
                } finally {
                    d.close()
                }
            }
            log("compare done")
        }
    }

    private fun select(m: String) {
        if (m == model) return
        loaded?.close()
        loaded = null
        model = m
        showPlan()
    }

    private fun showPlan() {
        val p = den.device()
        val text = buildString {
            appendLine("${p.soc} (${p.socFamily}), API ${p.osApi}, RAM ${p.availRamMb}/${p.ramMb} MB free, thermal ${p.thermal}")
            appendLine("cpu ${p.cpuFeatures.sorted().joinToString(",")}, gpu ${p.gpu}, opencl ${p.openCl}, vulkan ${p.vulkan}, npu ${p.npu?.vendor}")
            appendLine("model $model, ${den.recipes.size} recipes, models in ${den.modelDir.path}")
            den.plan(model).forEachIndexed { i, c ->
                appendLine("$i. ${c.backend.id} ${c.accel} ${c.variant.quant} (${c.source})")
            }
        }
        info.text = text
        text.lines().filter { it.isNotBlank() }.forEach { log(it) }
    }

    private fun run() {
        if (job?.isActive == true) return
        job = scope.launch {
            output.text = ""
            try {
                val t0 = System.currentTimeMillis()
                val m = loaded ?: den.model(model).also { loaded = it }
                m.failures.forEach { log("failed: ${it.candidate.accel} ${it.candidate.variant.quant}: ${it.error.message}") }
                log("using ${m.backend} ${m.accel} ${m.quant} (${m.source}), licence ${m.licence}, connected ${m.connected}, load ${System.currentTimeMillis() - t0} ms, free ${den.device().availRamMb} MB")
                if (m.connected) log("http endpoint ${den.httpEndpoint()}")

                // A chat of --ei turns N: each turn's first token should not grow with the history.
                val turns = intent.getIntExtra("turns", 0)
                if (turns > 0) {
                    val chat = m.newChat(maxTokens = 96, temperature = 0f)
                    // --ez cache false: the same turns, each prefilling the whole history (the baseline).
                    val cache = intent.getBooleanExtra("cache", true)
                    val history = mutableListOf<ChatMessage>()
                    repeat(turns) { i ->
                        val start = System.currentTimeMillis()
                        var first = 0L
                        val text = if (i == 0) prompt.text.toString() else "Tell me more about that, please. /no_think"
                        val reply = StringBuilder()
                        val events = if (cache) chat.send(text) else {
                            history += ChatMessage(Role.USER, text)
                            m.chat(GenRequest(history.toList(), 96, 0f, cache = false))
                        }
                        events.collect { e ->
                            if (e is GenEvent.Text) reply.append(e.text)
                            if (e is GenEvent.Done && !cache) history += ChatMessage(Role.ASSISTANT, reply.toString())
                            if (e is GenEvent.Text && first == 0L) first = System.currentTimeMillis() - start
                            if (e is GenEvent.Done) e.stats.run {
                                log("turn ${i + 1}: first token $first ms, prompt $promptTokens tok ($cachedTokens cached), prefill $prefillMs ms, output $outputTokens tok, %.1f tok/s".format(decodeTps))
                            }
                        }
                    }
                    return@launch
                }

                val req = GenRequest(listOf(ChatMessage(Role.USER, prompt.text.toString())), maxTokens = 128, temperature = 0f)
                repeat(intent.getIntExtra("repeat", 1)) { i ->
                // For trim tests: a wait between requests, while adb sends a trim-memory signal.
                if (i > 0) kotlinx.coroutines.delay(intent.getIntExtra("pause", 0) * 1000L)
                val sb = StringBuilder()
                val start = System.currentTimeMillis()
                m.chat(req).collect { e ->
                    when (e) {
                        is GenEvent.Text -> {
                            if (sb.isEmpty()) log("first token ${System.currentTimeMillis() - start} ms")
                            sb.append(e.text)
                            output.text = sb
                        }
                        is GenEvent.Done -> {
                            val s = e.stats
                            val line = "prompt ${s.promptTokens} tok, %.1f tok/s | output ${s.outputTokens} tok, %.1f tok/s"
                                .format(s.prefillTps, s.decodeTps)
                            output.append("\n\n$line")
                            log("text: ${sb.toString().replace('\n', ' ')}")
                            log(line)
                        }
                    }
                }
                }
                // For close tests: Den.close() must unload what it opened.
                if (intent.getBooleanExtra("closeAfter", false)) {
                    den.close()
                    loaded = null
                    log("den closed")
                }
            } catch (e: NoUsableBackendException) {
                output.text = "error: ${e.message}"
                log("error: ${e.message}")
                e.failures.forEach { log("failed: ${it.candidate.accel} ${it.candidate.variant.quant}: ${it.error}") }
            } catch (e: Exception) {
                output.text = "error: ${e.message}"
                log("error: $e")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        loaded?.close()
        den.close()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenTest", msg)
}
