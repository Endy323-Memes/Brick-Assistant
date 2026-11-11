package com.brick.assistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The assistant card: dual-voice ribbon art plus label, main line and sub line. */
@SuppressLint("ViewConstructor")
class AssistCard(context: Context) : FrameLayout(context), AssistUi {
    private val d = resources.displayMetrics.density
    val pulse = PulseView(context)
    private val label = text(11f, Color.rgb(0x4D, 0xD0, 0xE1), Typeface.MONOSPACE, bold = true).apply { letterSpacing = 0.18f }
    private val main = text(19f, Color.WHITE).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }
    private val sub = text(14f, Color.rgb(0xAA, 0xB4, 0xC8)).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }

    init {
        val pad = pulse.glowPad.toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + dp(22), pad + pulse.ribbonHeight.toInt(), pad + dp(22), pad + dp(20))
            addView(label)
            addView(main, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        addView(pulse, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        minimumHeight = dp(150)
        setState(PulseView.Mode.LISTENING, "Go ahead…")
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun text(sp: Float, color: Int, face: Typeface = Typeface.DEFAULT, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (bold) Typeface.create(face, Typeface.BOLD) else face
    }

    override fun setState(mode: PulseView.Mode, mainText: String, subText: String?) {
        pulse.mode = mode
        val (lbl, color, mono) = when (mode) {
            PulseView.Mode.LISTENING -> Triple("BRICK · LISTENING", Color.rgb(0x4D, 0xD0, 0xE1), false)
            PulseView.Mode.THINKING -> Triple("BRICK · WORKING", Color.rgb(0xAA, 0xB4, 0xC8), false)
            PulseView.Mode.DAVID -> Triple("BRICK", Color.rgb(0xFF, 0xB7, 0x4D), false)
            PulseView.Mode.ROBOT -> Triple("UNIT", Color.rgb(0x4D, 0xD0, 0xE1), true)
            PulseView.Mode.MESSAGE -> Triple("BRICK", Color.rgb(0xAA, 0xB4, 0xC8), false)
        }
        label.text = lbl
        label.setTextColor(color)
        main.typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        main.text = mainText
        sub.text = subText.orEmpty()
        sub.visibility = if (subText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    override fun setPartial(text: String) {
        if (pulse.mode == PulseView.Mode.LISTENING) main.text = text
    }

    override fun setMicLevel(level: Float) { pulse.micLevel = level }

    override fun setOutputLevel(source: () -> Float) { pulse.outputLevel = source }

    fun animateIn() {
        alpha = 0f
        translationY = dp(160).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    fun animateOut(end: () -> Unit) {
        animate().alpha(0f).translationY(dp(120).toFloat()).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).withEndAction(end).start()
    }
}

/** Hosts an [AssistCard] in a window drawn over other apps (needs "Display over other apps"). */
class AssistOverlay(
    private val context: Context,
    private val onOutsideTouch: () -> Unit,
    onCardTap: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    val card = AssistCard(context)
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }
    private var shown = false

    init {
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.setOnClickListener { onCardTap() }
    }

    /** Returns false if the window could not be added (permission revoked). */
    fun show(): Boolean {
        if (shown) return true
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = (6 * context.resources.displayMetrics.density).toInt()
            title = "Brick assistant"
        }
        return try {
            wm.addView(root, lp)
            shown = true
            card.animateIn()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hide(then: () -> Unit) {
        if (!shown) { then(); return }
        card.animateOut { removeNow(); then() }
    }

    fun removeNow() {
        if (!shown) return
        shown = false
        try { wm.removeView(root) } catch (_: Exception) {}
    }
}
