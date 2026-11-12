package com.brick.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import com.brick.assistant.brain.Control
import com.brick.assistant.brain.Readout

/**
 * A small card of its own, shown above the Brick card, for a terminal readout (the night sky panel). Same
 * look as the Brick card: dark panel, scanlines, thin border in Brick's color.
 */
class PanelCard(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val panel = RectF()
    private var scanLines = FloatArray(0)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = ViewColors.alpha(ViewColors.brick, 150)
    }
    private val scan = Paint().apply { color = android.graphics.Color.argb(70, 0, 0, 0); strokeWidth = 1f }
    private val radius = 6 * d

    private val text = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.MONOSPACE
        setTextColor(ViewColors.text)
        setHorizontallyScrolling(true)
    }
    private val image = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val controlsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Called when you touch the card (a switch, slider or button), so the card stays up instead of closing. */
    var onInteract: (() -> Unit)? = null

    private var current: Readout? = null
    private var shownImage: String? = null
    private val ticker = object : Runnable {
        override fun run() {
            val r = current ?: return
            if (!r.live || visibility != View.VISIBLE) return
            r.refresh?.let { update(it()) }
            postDelayed(this, TICK_MS)
        }
    }

    init {
        setWillNotDraw(false)
        val p = (14 * d).toInt()
        // Never wraps (the box must stay square); scrolls sideways on a very narrow screen.
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(text)
        }
        column.addView(image, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        column.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        column.addView(controlsBox, LinearLayout.LayoutParams(-1, -2))
        // A tall readout (picture, long answer, controls) scrolls inside the card instead of running off the screen.
        val vertical = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(column) }
        addView(vertical, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(p, p, p, p) })
        visibility = View.GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onInteract?.invoke()
        return false
    }

    /** Shows [readout], or hides the card when null. */
    fun show(readout: Readout?) {
        removeCallbacks(ticker)
        if (readout == null) { current = null; visibility = View.GONE; return }
        current = readout
        text.text = color(readout.text)
        showImage(readout.image)
        buildControls(readout.controls)
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = 24 * d
            animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        if (readout.live) postDelayed(ticker, TICK_MS)
    }

    /** The same readout with new state: the text and the controls' values change in place (a drag isn't cut off). */
    private fun update(r: Readout) {
        val wasLive = current?.live == true
        val sameText = current?.text == r.text
        current = r
        if (!sameText) text.text = color(r.text) // a tick with nothing new doesn't relayout
        if (r.controls.size == bound.size && r.controls.indices.all { r.controls[it]::class == bound[it].control::class }) {
            r.controls.forEachIndexed { i, c -> bound[i].rebind(c) }
        } else {
            buildControls(r.controls)
        }
        if (r.live && !wasLive) { removeCallbacks(ticker); postDelayed(ticker, TICK_MS) }
    }

    /** After a control: show what changed (state takes a moment to settle, e.g. the volume). */
    private fun refreshSoon() {
        val f = current?.refresh ?: return
        postDelayed({ if (current != null) update(f()) }, 180)
    }

    private fun showImage(url: String?) {
        if (url == shownImage) return
        shownImage = url
        image.setImageDrawable(null)
        image.visibility = View.GONE
        if (url == null) return
        ImageLoader.load(url) { b ->
            if (b != null && shownImage == url) {
                image.setImageBitmap(b)
                image.maxHeight = (200 * d).toInt()
                image.visibility = View.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------ controls

    private interface Bound { val control: Control; fun rebind(c: Control) }
    private val bound = ArrayList<Bound>()

    private fun mono(s: String, c: Int, sp: Float = 12f) = TextView(context).apply {
        text = s
        setTextColor(c)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun buttonView(label: String) = mono("[ $label ]", ViewColors.brick).apply {
        gravity = Gravity.CENTER
        val pad = (8 * d).toInt()
        setPadding(pad / 2, pad, pad / 2, pad)
        background = GradientDrawable().apply {
            setStroke((1 * d).toInt().coerceAtLeast(1), ViewColors.alpha(ViewColors.brick, 120))
            cornerRadius = 4 * d
            setColor(ViewColors.alpha(ViewColors.brick, 18))
        }
        isClickable = true
    }

    private fun buildControls(list: List<Control>) {
        controlsBox.removeAllViews()
        bound.clear()
        if (list.isEmpty()) return
        val gap = (8 * d).toInt()
        var buttonRow: LinearLayout? = null
        for (c in list) {
            when (c) {
                is Control.Button -> {
                    val row = buttonRow?.takeIf { it.childCount < 3 } ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        controlsBox.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                    }.also { buttonRow = it }
                    val v = buttonView(c.label)
                    val b = object : Bound {
                        override var control: Control = c
                        override fun rebind(c: Control) { control = c; v.text = "[ ${c.label} ]" }
                    }
                    v.setOnClickListener { (b.control as Control.Button).run(); refreshSoon() }
                    row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (row.childCount > 0) leftMargin = gap })
                    bound += b
                }
                is Control.Toggle -> {
                    buttonRow = null
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val label = mono(c.label, ViewColors.text)
                    val state = buttonView("")
                    row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(state, LinearLayout.LayoutParams((96 * d).toInt(), -2))
                    val b = object : Bound {
                        override var control: Control = c
                        override fun rebind(c: Control) {
                            control = c
                            val on = (c as Control.Toggle).get()
                            label.text = c.label
                            state.text = if (on) "[ ON  ]" else "[ OFF ]"
                            state.setTextColor(if (on) ViewColors.brick else ViewColors.dim)
                        }
                    }
                    b.rebind(c)
                    state.setOnClickListener {
                        val t = b.control as Control.Toggle
                        t.set(!t.get())
                        b.rebind(t)
                        refreshSoon()
                    }
                    controlsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                    bound += b
                }
                is Control.Slider -> {
                    buttonRow = null
                    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val label = mono(c.label, ViewColors.text)
                    val value = mono("", ViewColors.unit)
                    head.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                    head.addView(value)
                    val bar = SeekBar(context).apply {
                        max = 1000
                        progressTintList = android.content.res.ColorStateList.valueOf(ViewColors.brick)
                        thumbTintList = android.content.res.ColorStateList.valueOf(ViewColors.brick)
                        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ViewColors.dim)
                    }
                    var dragging = false
                    val b = object : Bound {
                        override var control: Control = c
                        override fun rebind(c: Control) {
                            control = c
                            val s = c as Control.Slider
                            label.text = s.label
                            if (dragging) return
                            val v = s.get().coerceIn(s.range.start, s.range.endInclusive)
                            bar.progress = ((v - s.range.start) / (s.range.endInclusive - s.range.start) * 1000).toInt()
                            value.text = s.format(v)
                        }
                    }
                    fun valueAt(p: Int): Float {
                        val s = b.control as Control.Slider
                        var v = s.range.start + (s.range.endInclusive - s.range.start) * p / 1000f
                        if (s.steps > 0) {
                            val step = (s.range.endInclusive - s.range.start) / (s.steps + 1)
                            v = s.range.start + Math.round((v - s.range.start) / step) * step
                        }
                        return v
                    }
                    b.rebind(c)
                    bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val s = b.control as Control.Slider
                            val v = valueAt(p)
                            value.text = s.format(v)
                            s.set(v)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) { dragging = true; onInteract?.invoke() }
                        override fun onStopTrackingTouch(sb: SeekBar) { dragging = false; refreshSoon() }
                    })
                    controlsBox.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                    controlsBox.addView(bar, LinearLayout.LayoutParams(-1, -2))
                    bound += b
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        panel.set(1f, 1f, w - 1f, h - 1f)
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
        canvas.drawRoundRect(panel, radius, radius, fill)
        canvas.save()
        canvas.clipRect(panel)
        canvas.drawLines(scanLines, scan)
        canvas.restore()
        canvas.drawRoundRect(panel, radius, radius, border)
    }

    companion object {
        private const val TICK_MS = 500L
        private val LABEL = Regex("^[A-Z][A-Z/-]{1,6}(?= |$)")

        /**
         * Fits [Box.W] to this screen: the columns of the readout that fit both this card (14dp side margins and
         * padding, 11.5sp) and the in-app log (16dp padding, up to 420dp, 12sp with 0.4sp letter spacing).
         */
        fun fitWidth(context: Context) {
            val m = context.resources.displayMetrics
            val dp = m.density
            fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, m)
            val paint = Paint().apply { typeface = Typeface.MONOSPACE }
            fun cols(widthPx: Float, sizeSp: Float, spacingSp: Float): Int {
                paint.textSize = sp(sizeSp)
                return ((widthPx - 1) / (paint.measureText("M") + sp(spacingSp))).toInt()
            }
            val screen = m.widthPixels.toFloat()
            val card = cols(screen - 56 * dp, 11.5f, 0f)
            val app = cols(minOf(screen - 32 * dp, 420 * dp), 12f, 0.4f)
            // "| " and " |" around each row.
            com.brick.assistant.brain.Box.fit(minOf(card, app) - 4)
        }

        /** Frame dim, "[ TITLE ]" in Brick's color, row labels in the unit's color, bar fill in Brick's color. */
        fun color(text: String): CharSequence = SpannableStringBuilder().apply {
            fun span(s: String, c: Int, bold: Boolean = false) {
                if (s.isEmpty()) return
                val start = length
                append(s)
                setSpan(ForegroundColorSpan(c), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bold) setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val dim = ViewColors.dim
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append("\n")
                if (line.startsWith("+")) {
                    val t0 = line.indexOf("[ ")
                    val t1 = line.indexOf(" ]")
                    if (t0 >= 0 && t1 > t0) {
                        span(line.substring(0, t0), dim)
                        span(line.substring(t0, t1 + 2), ViewColors.brick, bold = true)
                        span(line.substring(t1 + 2), dim)
                    } else span(line, dim)
                    return@forEachIndexed
                }
                val body = line.removePrefix("| ").removeSuffix(" |")
                span("| ", dim)
                val lab = LABEL.find(body)?.value.orEmpty()
                span(lab, ViewColors.unit, bold = true)
                // One span per run of a color, not per character (a live readout redraws twice a second).
                val rest = body.substring(lab.length)
                var inBar = false
                var from = 0
                var run = 0
                rest.forEachIndexed { j, c ->
                    val col = when {
                        c == '[' -> { inBar = true; dim }
                        c == ']' -> { inBar = false; dim }
                        inBar && c == '#' -> ViewColors.brick
                        inBar -> dim
                        else -> ViewColors.text
                    }
                    if (j > from && col != run) { span(rest.substring(from, j), run); from = j }
                    run = col
                }
                if (from < rest.length) span(rest.substring(from), run)
                span(" |", dim)
            }
        }
    }
}
