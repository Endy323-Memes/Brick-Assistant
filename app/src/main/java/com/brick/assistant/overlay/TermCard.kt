package com.brick.assistant.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating assistant card in the terminal look: a dark, thin-bordered panel with a prompt header,
 * a block-character level meter that follows your voice (or Brick's), and the session as terminal lines:
 * `you$ ...`, `brick> ...` (amber), `unit> ...` (blue).
 */
@SuppressLint("ViewConstructor")
class TermCard(context: Context) : FrameLayout(context), AssistUi {
    private val d = resources.displayMetrics.density

    // Colors from the active palette.
    private val amber = ViewColors.brick
    private val blue = ViewColors.unit
    private val red = ViewColors.alert
    private val textHigh = ViewColors.text
    private val textMid = ViewColors.dim
    private val hairline = ViewColors.line

    var mode = PulseView.Mode.LISTENING
        private set
    private var micLevel = 0f
    private var outputLevel: () -> Float = { 0f }
    private var mainText = ""
    private var subText: String? = null
    private var env = 0f
    private val bars = FloatArray(14)
    private var lastFrame = 0L
    private var time = 0f

    private val panel = RectF()
    private var scanLines = FloatArray(0)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val scan = Paint().apply { color = Color.argb(70, 0, 0, 0); strokeWidth = 1f }
    private val rule = Paint().apply { color = hairline; strokeWidth = 1f * d }
    private val meter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(14 * resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val radius = 6 * d
    private val pad = 16 * d
    private val headerH = 34 * d

    private val header = text(12.5f, textHigh, bold = true)
    private val status = text(11.5f, blue, bold = true)
    private val history = text(13f, textMid).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val main = text(16f, textHigh)

    /**
     * The answer, in a box that never grows past [MAIN_LINES] lines: longer answers scroll instead, following
     * along as they're read out (auto-scroll), and you can drag through them yourself (that stops the follow).
     */
    private val mainScroll = object : android.widget.ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = main.lineHeight * MAIN_LINES + main.paddingTop + main.paddingBottom
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(main)
    }
    /** When the current answer appeared (auto-scroll paces itself from here), or 0 when not following. */
    private var followSince = 0L
    private val tapDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { this@TermCard.performClick(); return true }
    })

    /** A terminal readout to show in its own card above this one (the night sky panel), or null to hide it. */
    var onPanel: ((com.brick.assistant.brain.Readout?) -> Unit)? = null

    /** True while the type box is open (typing to Brick instead of speaking). */
    var typing = false
        private set
    /** Tapped "type": stop listening and wait for typing. */
    var onStartTyping: (() -> Unit)? = null
    /** Tapped "talk": go back to listening. */
    var onStartTalking: (() -> Unit)? = null
    /** A typed request was sent. */
    var onTyped: ((String) -> Unit)? = null

    private val input = android.widget.EditText(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(textHigh)
        setHintTextColor(ViewColors.mix(textMid, ViewColors.background, 0.35f))
        hint = "type to brick…"
        typeface = Typeface.MONOSPACE
        background = null
        setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        isSingleLine = false
        maxLines = 4
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) textCursorDrawable = null
        setOnEditorActionListener { _, id, e ->
            val enter = e?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && e.action == android.view.KeyEvent.ACTION_DOWN
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) { submit(); true } else false
        }
    }
    private val send = text(16f, blue, bold = true).apply {
        text = " ⏎"
        setPadding((10 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
        setOnClickListener { submit() }
    }
    private val typeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val typeButton = text(12.5f, textMid, bold = true).apply {
        text = "[ ⌨ type ]"
        setPadding(0, (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        setOnClickListener { if (typing) stopTyping() else startTyping() }
    }

    private fun imm() = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)

    fun startTyping() {
        if (typing) return
        typing = true
        typeRow.visibility = View.VISIBLE
        typeButton.text = "[ 🎙 talk ]"
        onStartTyping?.invoke()
        render(force = true)
        input.requestFocus()
        input.post { imm()?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun stopTyping() {
        if (!typing) return
        typing = false
        imm()?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        typeRow.visibility = View.GONE
        typeButton.text = "[ ⌨ type ]"
        onStartTalking?.invoke()
    }

    private fun submit() {
        val t = input.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        input.setText("")
        onTyped?.invoke(t)
    }

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { tick() }
    }

    init {
        setWillNotDraw(false)
        val p = pad.toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, 0, p, p)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = headerH.toInt()
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
        }
        content.addView(top)
        content.addView(history, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * d).toInt() })
        content.addView(mainScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        @SuppressLint("ClickableViewAccessibility")
        mainScroll.setOnTouchListener { _, e ->
            // Your finger takes over from the auto-scroll; a plain tap still works like tapping the card.
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) followSince = 0L
            tapDetector.onTouchEvent(e)
            false
        }
        // The type box (hidden until you tap "type").
        typeRow.addView(text(16f, blue, bold = true).apply { text = "you$ " })
        typeRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        typeRow.addView(send)
        content.addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        // Bottom row: the type / talk switch on the left, room on the right for the meter.
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(typeButton)
        }
        content.addView(bottom, LinearLayout.LayoutParams(-1, (30 * d).toInt()).apply { topMargin = (2 * d).toInt() })
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        minimumHeight = (128 * d).toInt()
        header.text = SpannableStringBuilder().apply {
            span("brick@android", amber, bold = true)
            span(":", textMid)
            span("~", blue)
            span("$", textHigh)
        }
        setState(PulseView.Mode.LISTENING, "Go ahead…")
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setLineSpacing(2 * d, 1f)
    }

    private fun SpannableStringBuilder.span(s: String, color: Int, bold: Boolean = false): SpannableStringBuilder {
        val start = length
        append(s)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = SystemClock.uptimeMillis()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ AssistUi

    override fun setPanel(readout: com.brick.assistant.brain.Readout?) { onPanel?.invoke(readout) }

    private var quiet = false
    override fun setQuiet(on: Boolean) { if (on != quiet) { quiet = on; render(force = true) } }

    override fun setState(mode: PulseView.Mode, mainText: String, subText: String?) {
        // A new request clears the last readout.
        if (mode == PulseView.Mode.LISTENING || mode == PulseView.Mode.THINKING) setPanel(null)
        // A new answer starts at the top and follows along as it's read out.
        if (mainText != this.mainText || mode != this.mode) {
            val answer = mode == PulseView.Mode.DAVID || mode == PulseView.Mode.ROBOT || mode == PulseView.Mode.MESSAGE
            followSince = if (answer) SystemClock.uptimeMillis() else 0L
            if (answer) mainScroll.scrollTo(0, 0)
        }
        this.mode = mode
        this.mainText = mainText
        this.subText = subText
        render()
    }

    override fun setPartial(text: String) {
        if (mode == PulseView.Mode.LISTENING) { mainText = text; render() }
    }

    override fun setMicLevel(level: Float) { micLevel = level }

    override fun setOutputLevel(source: () -> Float) { outputLevel = source }

    private fun accent(): Int = when (mode) {
        PulseView.Mode.DAVID -> amber
        PulseView.Mode.MESSAGE -> textMid
        else -> blue
    }

    private val spinner = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

    private var shownKey: Any? = null

    /** Rebuilds the text lines, but only when something visible changed (text, cursor blink, spinner frame). */
    private fun render(force: Boolean = false) {
        val cursorOn = ((SystemClock.uptimeMillis() / 530) % 2L) == 0L
        val frame = if (mode == PulseView.Mode.THINKING) (SystemClock.uptimeMillis() / 80) % spinner.size else 0L
        val key = listOf(mode, mainText, subText, if (mode == PulseView.Mode.LISTENING) cursorOn else false, frame, typing)
        if (key == shownKey && !force) return
        shownKey = key
        status.setTextColor(if (mode == PulseView.Mode.MESSAGE) red else accent())
        // Typing: the type box is the prompt, so the listening line is hidden.
        main.visibility = if (typing && mode == PulseView.Mode.LISTENING) View.GONE else View.VISIBLE
        status.text = when (mode) {
            PulseView.Mode.LISTENING -> if (typing) "[ TYPING ]" else "[ LISTENING ]"
            PulseView.Mode.THINKING -> "[ ${spinner[((SystemClock.uptimeMillis() / 80) % spinner.size).toInt()]} WORKING ]"
            PulseView.Mode.DAVID -> if (quiet) "[ BRICK · MUTED ]" else "[ BRICK ]"
            PulseView.Mode.ROBOT -> if (quiet) "[ UNIT · MUTED ]" else "[ UNIT ]"
            PulseView.Mode.MESSAGE -> "[ ! ]"
        }
        val sub = subText?.takeIf { it.isNotBlank() }
        history.visibility = if (sub == null) View.GONE else View.VISIBLE
        if (sub != null) {
            history.text = SpannableStringBuilder().apply {
                // While Brick or the unit speaks, the line above is what you asked (or Brick's line before the unit).
                if (mode == PulseView.Mode.ROBOT && sub != mainText) span("brick> ", amber, bold = true) else span("you$ ", blue, bold = true)
                span(sub, textMid)
            }
        }
        main.text = SpannableStringBuilder().apply {
            when (mode) {
                PulseView.Mode.LISTENING -> {
                    span("you$ ", blue, bold = true)
                    span(mainText, textHigh)
                    span(if (cursorOn) " █" else "  ", blue)
                }
                PulseView.Mode.THINKING -> {
                    span("you$ ", blue, bold = true)
                    span(mainText, textHigh)
                }
                PulseView.Mode.DAVID -> { span("brick> ", amber, bold = true); span(mainText, textHigh) }
                PulseView.Mode.ROBOT -> { span("unit> ", blue, bold = true); span(mainText, textHigh) }
                PulseView.Mode.MESSAGE -> { span("! ", red, bold = true); span(mainText, textHigh) }
            }
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        time += dt
        val raw = when (mode) {
            PulseView.Mode.LISTENING -> micLevel
            PulseView.Mode.DAVID, PulseView.Mode.ROBOT -> outputLevel()
            else -> 0f
        }.coerceIn(0f, 1f)
        env += (raw - env) * min(1f, dt * if (raw > env) 22f else 6f)
        for (i in bars.indices) {
            val wobble = 0.55f + 0.45f * kotlin.math.abs(sin(i * 1.9f + time * 8.3f) * 0.6f + sin(i * 0.7f - time * 4.1f) * 0.4f)
            val target = when (mode) {
                // Working: a soft wave rolls through the meter.
                PulseView.Mode.THINKING -> 0.18f + 0.5f * (0.5f + 0.5f * sin(time * 6f - i * 0.6f))
                PulseView.Mode.MESSAGE -> 0.06f
                else -> (0.06f + 0.94f * env * wobble)
            }
            bars[i] += (target - bars[i]) * min(1f, dt * if (target > bars[i]) 24f else 8f)
        }
        render()
        follow(now)
        invalidate()
    }

    /**
     * Keeps the part being read out in view: the answer scrolls at about speaking pace, holding the current line a
     * little above the middle. While you're talking or typing, the newest words stay in view.
     */
    private fun follow(now: Long) {
        val max = (main.height - mainScroll.height).coerceAtLeast(0)
        if (max == 0) return
        if (mode == PulseView.Mode.LISTENING || mode == PulseView.Mode.THINKING) {
            if (mainScroll.scrollY != max) mainScroll.scrollTo(0, max)
            return
        }
        if (followSince == 0L) return
        val total = (mainText.length / CHARS_PER_SECOND).coerceAtLeast(1f)
        val done = ((now - followSince) / 1000f / total).coerceIn(0f, 1f)
        val target = (done * main.height - mainScroll.height * 0.55f).toInt().coerceIn(0, max)
        if (target > mainScroll.scrollY) mainScroll.scrollTo(0, target)
        if (done >= 1f && mainScroll.scrollY >= max) followSince = 0L
    }

    // ------------------------------------------------------------------ drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        panel.set(1f, 1f, w - 1f, h - 1f)
        // The scanlines, computed once per size (same lines as before, drawn in one call each frame).
        val ys = ArrayList<Float>()
        var y = panel.top
        while (y < panel.bottom) { ys += y; y += 3 * d }
        scanLines = FloatArray(ys.size * 4).also { a ->
            ys.forEachIndexed { i, yy -> a[i * 4] = panel.left; a[i * 4 + 1] = yy; a[i * 4 + 2] = panel.right; a[i * 4 + 3] = yy }
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(ViewColors.mix(ViewColors.panel, ViewColors.text, 0.03f), ViewColors.background), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val a = accent()
        // Soft accent glow under the panel, then the panel, scanlines, border and header rule.
        glow.color = a
        glow.alpha = (40 + 60 * env).toInt()
        canvas.drawRoundRect(panel, radius, radius, glow)
        canvas.drawRoundRect(panel, radius, radius, fill)
        canvas.save()
        canvas.clipRect(panel)
        canvas.drawLines(scanLines, scan)
        canvas.restore()
        border.color = a
        border.alpha = 150
        canvas.drawRoundRect(panel, radius, radius, border)
        canvas.drawLine(pad, headerH, width - pad, headerH, rule)
        drawMeter(canvas, a)
    }

    /** Block-character style meter in the bottom right: quantized bars like ▁▂▃▄▅▆▇█. */
    private fun drawMeter(canvas: Canvas, color: Int) {
        val bw = 5 * d
        val gap = 2.5f * d
        val maxH = 20 * d
        val right = width - pad
        val bottom = height - pad * 0.9f
        val left = right - bars.size * (bw + gap) + gap
        for (i in bars.indices) {
            val level = (bars[i] * 8f).toInt().coerceIn(1, 8) / 8f // eight steps, like block characters
            val h = maxH * level
            val x = left + i * (bw + gap)
            meter.color = color
            meter.alpha = (110 + 145 * level).toInt().coerceIn(0, 255)
            canvas.drawRect(x, bottom - h, x + bw, bottom, meter)
        }
    }

    fun animateIn() {
        alpha = 0f
        translationY = 40 * d
        animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
    }

    fun animateOut(end: () -> Unit) {
        animate().alpha(0f).translationY(30 * d).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).withEndAction(end).start()
    }

    companion object {
        /** The answer box shows at most this many lines (the card's usual size); longer answers scroll. */
        const val MAIN_LINES = 5
        /** About how fast Brick and the unit speak (and how fast you read): paces the auto-scroll. */
        const val CHARS_PER_SECOND = 14f
    }
}
