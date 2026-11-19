package ai.fanak.den.app

import ai.fanak.den.Den
import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Placeholder screen: what Den sees on this phone. Model management UI comes later.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val den = Den.connect(this)
        val p = den.device()
        val text = buildString {
            appendLine("Fanak Den")
            appendLine()
            appendLine("${p.soc} (${p.socFamily}), API ${p.osApi}, RAM ${p.availRamMb}/${p.ramMb} MB free")
            appendLine("models in ${den.modelDir.path}")
            den.models.forEach { m ->
                appendLine()
                appendLine(m)
                den.plan(m).forEachIndexed { i, c -> appendLine("  $i. ${c.backend.id} ${c.accel} ${c.variant.quant} (${c.source})") }
            }
        }
        setContentView(TextView(this).apply { setPadding(32, 32, 32, 32); this.text = text })
    }
}
