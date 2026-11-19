package ai.fanak.den.app

import ai.fanak.den.Den
import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// What Den sees on this phone, and which apps may use it. Model management UI comes later.
class MainActivity : Activity() {
    private lateinit var apps: LinearLayout

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
                appendLine(m)
                den.plan(m).forEachIndexed { i, c -> appendLine("  $i. ${c.backend.id} ${c.accel} ${c.variant.quant} (${c.source})") }
            }
        }
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { this.text = text })
            addView(apps)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        showApps()
    }

    private fun showApps() {
        apps.removeAllViews()
        apps.addView(TextView(this).apply { text = "\nApps"; textSize = 18f })
        val rows = Approvals.pending.associateWith { null as Boolean? } + Approvals.all(this)
        if (rows.isEmpty()) apps.addView(TextView(this).apply { text = "No app has asked yet." })
        rows.forEach { (pkg, allowed) ->
            val state = when (allowed) { true -> "allowed"; false -> "denied"; null -> "waiting" }
            apps.addView(TextView(this).apply { text = "${Approvals.label(context, pkg)} ($pkg): $state" })
            apps.addView(LinearLayout(this).apply {
                if (allowed != true) addView(button("Allow") { Approvals.set(context, pkg, true) })
                if (allowed != false) addView(button("Deny") { Approvals.set(context, pkg, false) })
                if (allowed != null) addView(button("Forget") { Approvals.forget(context, pkg) })
            })
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick(); showApps() }
    }
}
