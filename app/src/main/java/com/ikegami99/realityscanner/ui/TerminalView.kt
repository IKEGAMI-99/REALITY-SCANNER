package com.ikegami99.realityscanner.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ikegami99.realityscanner.logging.AppLogger

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val green = Color.rgb(112, 255, 112)
    private val dim = Color.rgb(42, 150, 62)
    private val bgColor = Color.rgb(2, 6, 2)

    private val scroll = ScrollView(context)
    private val output = TextView(context)
    private val lines = ArrayDeque<String>()
    private var paused = false
    private lateinit var demoButton: TextView

    var onExport: (() -> Unit)? = null
    var onUpdate: (() -> Unit)? = null
    var onDemo: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(bgColor)

        val title = TextView(context).apply {
            text = ">> PROCESS TERMINAL // LIVE"
            setTextColor(green)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        output.apply {
            setTextColor(green)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.08f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            text = "> boot sequence pending...\n> _"
        }
        scroll.apply {
            isFillViewport = true
            addView(
                output,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(7))
        }

        val pause = cliButton("[ PAUSE ]") {
            paused = !paused
            it.text = if (paused) "[ RESUME ]" else "[ PAUSE ]"
        }
        val clear = cliButton("[ CLEAR ]") {
            lines.clear()
            output.text = "> _"
        }
        demoButton = cliButton("[ DEMO ]") { onDemo?.invoke() }
        val export = cliButton("[ EXPORT ]") { onExport?.invoke() }
        val update = cliButton("[ UPDATE ]") { onUpdate?.invoke() }

        controls.addView(pause, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(clear, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(demoButton, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(export, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(update, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun setDemoActive(active: Boolean) {
        if (::demoButton.isInitialized) {
            demoButton.text = if (active) "[ CAMERA ]" else "[ DEMO ]"
        }
    }

    fun append(entry: AppLogger.Entry) {
        lines.addLast(entry.line())
        while (lines.size > 250) lines.removeFirst()
        if (paused) return

        output.text = buildString {
            lines.forEach { append(it).append('\n') }
            append("> _")
        }
        scroll.post { scroll.fullScroll(FOCUS_DOWN) }
    }

    private fun cliButton(textValue: String, action: (TextView) -> Unit): TextView {
        return TextView(context).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextColor(green)
            textSize = 8.8f
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), dim)
                cornerRadius = 0f
            }
            setOnClickListener { action(this) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
