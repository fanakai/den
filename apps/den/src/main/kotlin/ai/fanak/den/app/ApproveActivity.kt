package ai.fanak.den.app

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Asks the user whether an app may use Den, or whether Den may download a hub repository for it. Opened by the client
 * app through the SDK, or from the notification. Not exported: the package and repository come from the Den service,
 * which knows the caller from Binder, so no app can ask in another's name.
 */
class ApproveActivity : Activity() {
    private var pkg: String? = null
    private var repo: String? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Fanak Den"
        repo = intent.getStringExtra("repo")
        repo?.let { r ->
            // Answered elsewhere already (notification, Den screen, timeout).
            val a = Downloads.asking(r) ?: return finish()
            return show(
                null,
                "Download ${Downloads.shortName(r)} for ${a.by}?",
                "${Downloads.describe(a)}\n\nFrom ${Downloads.hubOf(r)}, " +
                    "a repository Den has not reviewed. Check its licence before you use it.",
                "Download",
            )
        }
        val p = intent.getStringExtra("pkg") ?: return finish()
        if (p !in Approvals.pending) return finish()
        pkg = p
        show(
            runCatching { packageManager.getApplicationIcon(p) }.getOrNull(),
            "Allow ${Approvals.label(this, p)} to use the models on this phone?",
            "$p\n\nIts requests run in Den, on this phone. You can change this later in the Den app.",
            "Allow",
        )
    }

    private fun show(icon: Drawable?, question: String, details: String, yes: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        icon?.let { d ->
            val size = (40 * resources.displayMetrics.density).toInt()
            head.addView(ImageView(this).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(size, size).apply { marginEnd = pad })
        }
        head.addView(TextView(this).apply { text = question; textSize = 18f })
        root.addView(head)
        root.addView(TextView(this).apply { text = details; setPadding(0, pad, 0, pad) })
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(Button(this).apply { text = "Deny"; setOnClickListener { answer(false) } })
        buttons.addView(Button(this).apply { text = yes; setOnClickListener { answer(true) } })
        root.addView(buttons)
        setContentView(root)
    }

    private fun answer(allow: Boolean) {
        answered = true
        repo?.let { Downloads.answer(this, it, allow) }
        pkg?.let { Approvals.set(this, it, allow) }
        finish()
    }

    // Back or a tap outside closes it without a decision; the app or repository is not asked again for a few minutes.
    override fun onDestroy() {
        if (isFinishing && !answered) {
            repo?.let { if (Downloads.asking(it) != null) Downloads.answer(this, it, false) }
            pkg?.let { if (it in Approvals.pending) Approvals.dismiss(this, it) }
        }
        super.onDestroy()
    }
}
