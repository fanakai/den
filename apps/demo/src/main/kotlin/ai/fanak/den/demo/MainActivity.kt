package ai.fanak.den.demo

import ai.fanak.den.Den
import ai.fanak.den.DenConfig
import ai.fanak.den.DenMode
import ai.fanak.den.DenModel
import ai.fanak.den.core.backend.GenEvent
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
import kotlinx.coroutines.withTimeout

// A second app that uses only the SDK, always through the Den app.
// am start -n ai.fanak.den.demo/.MainActivity --es prompt "..." --ez autorun true --ei repeat 3 [--ei openTimeout 30]
class MainActivity : Activity() {
    private val scope = MainScope()
    private val den by lazy {
        Den.connect(this, DenConfig(mode = DenMode.CONNECTED, onDownload = { p ->
            val line = "downloading ${p.fileName}: ${p.bytes shr 20}/${p.total shr 20} MB"
            log(line)
            runOnUiThread { output.text = line }
        }))
    }
    private var model: DenModel? = null
    private var job: Job? = null

    private lateinit var prompt: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        prompt = EditText(this).apply {
            setText(intent.getStringExtra("prompt") ?: "Give three facts about the fennec fox. /no_think")
        }
        output = TextView(this).apply { textSize = 14f }
        root.addView(prompt, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Button(this).apply { text = "Ask"; setOnClickListener { ask() } })
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (intent.getBooleanExtra("autorun", false)) ask()
    }

    private fun ask() {
        if (job?.isActive == true) return
        val name = intent.getStringExtra("model") ?: "qwen3-0.6b"
        job = scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                // For tests: give up opening after this many seconds (cancels the download in the Den app).
                val limit = intent.getIntExtra("openTimeout", 0)
                val m = model ?: (if (limit > 0) withTimeout(limit * 1000L) { den.model(name) } else den.model(name)).also { model = it }
                log("using ${m.backend} ${m.accel} ${m.quant} via the Den app, open ${System.currentTimeMillis() - t0} ms")
                repeat(intent.getIntExtra("repeat", 1)) {
                    val start = System.currentTimeMillis()
                    var first = 0L
                    val sb = StringBuilder()
                    m.chat(prompt.text.toString(), maxTokens = 128, temperature = 0f).collect { e ->
                        when (e) {
                            is GenEvent.Text -> {
                                if (first == 0L) first = System.currentTimeMillis() - start
                                sb.append(e.text)
                                output.text = sb
                            }
                            is GenEvent.Done -> e.stats.run {
                                log("first token $first ms, total ${System.currentTimeMillis() - start} ms | " +
                                    "prompt $promptTokens tok, %.1f tok/s | output $outputTokens tok, %.1f tok/s".format(prefillTps, decodeTps))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                output.text = "error: ${e.message}"
                log("error: $e")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        model?.close()
        den.close()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("DenDemo", msg)
}
