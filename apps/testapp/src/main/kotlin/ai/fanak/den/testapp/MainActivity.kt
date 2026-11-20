package ai.fanak.den.testapp

import ai.fanak.den.Den
import ai.fanak.den.DenMode
import ai.fanak.den.DenModel
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
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
        if (intent.getBooleanExtra("echo", false)) echoDen() else testDen(intent.getBooleanExtra("mmap", true), mode)
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
        if (intent.getBooleanExtra("autorun", false)) run()
        if (intent.getBooleanExtra("soak", false)) {
            startForegroundService(android.content.Intent(this, SoakService::class.java).putExtras(intent))
        }
        if (intent.getBooleanExtra("remote", false)) remoteCheck(model, prompt.text.toString())
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
                log("using ${m.backend} ${m.accel} ${m.quant} (${m.source}), connected ${m.connected}, load ${System.currentTimeMillis() - t0} ms, free ${den.device().availRamMb} MB")

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
