package ai.fanak.den.app

import ai.fanak.den.Den
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// What Den sees on this phone, and which apps may use it. Model management UI comes later.
class MainActivity : Activity() {
    private lateinit var apps: LinearLayout
    private lateinit var http: LinearLayout

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
        http = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { this.text = text })
            addView(apps)
            addView(http)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // The endpoint does not survive a reboot or update; opening the Den app brings it back.
        if (HttpSettings.enabled(this)) startForegroundService(Intent(this, DenService::class.java))
        showApps()
        showHttp()
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

    private fun showHttp() {
        http.removeAllViews()
        val on = HttpSettings.enabled(this)
        http.addView(TextView(this).apply { text = "\nHTTP endpoint (OpenAI-compatible)"; textSize = 18f })
        http.addView(TextView(this).apply {
            text = if (on) "On: http://127.0.0.1:${HttpSettings.PORT}/v1, for apps on this phone only" else "Off"
        })
        http.addView(button(if (on) "Turn off" else "Turn on") {
            HttpSettings.setEnabled(this, !on)
            startForegroundService(Intent(this, DenService::class.java))
        })
        http.addView(TextView(this).apply { text = "Each client sends its token as the API key (Authorization: Bearer)." })
        val name = EditText(this).apply { hint = "client name" }
        http.addView(name)
        http.addView(button("New token") {
            HttpSettings.create(this, name.text.toString().ifBlank { "client" })
        })
        HttpSettings.all(this).forEach { (token, client) ->
            http.addView(TextView(this).apply {
                text = "$client: $token"
                setTextIsSelectable(true)
            })
            http.addView(LinearLayout(this).apply {
                addView(button("Copy") {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Den token", token))
                })
                addView(button("Revoke") { HttpSettings.revoke(context, token) })
            })
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick(); showApps(); showHttp() }
    }
}
