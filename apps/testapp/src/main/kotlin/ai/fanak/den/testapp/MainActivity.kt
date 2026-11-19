package ai.fanak.den.testapp

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.router.NoUsableBackendException
import ai.fanak.den.core.router.Opened
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
    private val device by lazy { AndroidDevice(this) }
    private val router by lazy { testRouter() }
    private val modelDir get() = modelDir()

    private var model = Catalog.models.first()
    private var opened: Opened? = null
    private var job: Job? = null

    private lateinit var info: TextView
    private lateinit var prompt: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("model")?.let { model = it }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        info = TextView(this).apply { textSize = 12f }
        val models = LinearLayout(this)
        Catalog.models.forEach { m -> models.addView(Button(this).apply { text = m; setOnClickListener { select(m) } }) }
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
    }

    private fun select(m: String) {
        if (m == model) return
        opened?.session?.unload()
        opened = null
        model = m
        showPlan()
    }

    private fun showPlan() {
        val p = device.profile()
        val plan = router.plan(model, p)
        val text = buildString {
            appendLine("${p.soc} (${p.socFamily}), API ${p.osApi}, RAM ${p.availRamMb}/${p.ramMb} MB free, thermal ${p.thermal}")
            appendLine("model $model, models in ${modelDir.path}")
            plan.forEachIndexed { i, c ->
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
                val o = opened ?: router.open(model, device.profile()).also { opened = it }
                o.failures.forEach { log("failed: ${it.candidate.backend.id} ${it.candidate.accel} ${it.candidate.variant.quant}: ${it.error.message}") }
                log("using ${o.candidate.backend.id} ${o.candidate.accel} ${o.candidate.variant.quant} (${o.candidate.source})")

                val req = GenRequest(listOf(ChatMessage(Role.USER, prompt.text.toString())), maxTokens = 128, temperature = 0f)
                val sb = StringBuilder()
                o.session.generate(req).collect { e ->
                    when (e) {
                        is GenEvent.Text -> { sb.append(e.text); output.text = sb }
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
            } catch (e: NoUsableBackendException) {
                output.text = "error: ${e.message}"
                e.failures.forEach { log("failed: ${it.candidate.accel} ${it.candidate.variant.quant}: ${it.error}") }
            } catch (e: Exception) {
                output.text = "error: ${e.message}"
                log("error: $e")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        opened?.session?.unload()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenTest", msg)
}
