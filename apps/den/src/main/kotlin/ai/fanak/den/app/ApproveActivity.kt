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
 * Asks the user whether an app may use Den. Opened by the client app through the SDK, or from the notification. Not
 * exported: the package comes from the Den service, which knows the caller from Binder, so no app can ask for another.
 */
class ApproveActivity : Activity() {
    private lateinit var pkg: String
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra("pkg") ?: return finish()
        // Answered elsewhere already (notification, Den screen, timeout).
        if (pkg !in Approvals.pending) return finish()
        title = "Fanak Den"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        icon()?.let { d ->
            val size = (40 * resources.displayMetrics.density).toInt()
            head.addView(ImageView(this).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(size, size).apply { marginEnd = pad })
        }
        head.addView(TextView(this).apply {
            text = "Allow ${Approvals.label(this@ApproveActivity, pkg)} to use the models on this phone?"
            textSize = 18f
        })
        root.addView(head)
        root.addView(TextView(this).apply {
            text = "$pkg\n\nIts requests run in Den, on this phone. You can change this later in the Den app."
            setPadding(0, pad, 0, pad)
        })
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(Button(this).apply { text = "Deny"; setOnClickListener { answer(false) } })
        buttons.addView(Button(this).apply { text = "Allow"; setOnClickListener { answer(true) } })
        root.addView(buttons)
        setContentView(root)
    }

    private fun icon(): Drawable? = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    private fun answer(allow: Boolean) {
        answered = true
        Approvals.set(this, pkg, allow)
        finish()
    }

    // Back or a tap outside closes it without a decision; the app is not asked again for a few minutes.
    override fun onDestroy() {
        if (isFinishing && !answered && ::pkg.isInitialized && pkg in Approvals.pending) Approvals.dismiss(this, pkg)
        super.onDestroy()
    }
}
