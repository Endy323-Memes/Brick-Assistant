package com.brick.assistant.overlay

import com.brick.assistant.brain.Patterns

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

    // The box in three parts: its top border (title) and bottom border stay put; only the rows between them scroll.
    private fun boxText() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.MONOSPACE
        setTextColor(ViewColors.text)
        setHorizontallyScrolling(true)
        includeFontPadding = false
        // Characters at their exact width, not rounded to whole pixels, so the box can fill the card exactly.
        paintFlags = paintFlags or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    }
    private val top = boxText()
    private val text = boxText()
    private val bottom = boxText()
    private var boxSp = 11.5f
    private val image = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        maxHeight = (128 * resources.displayMetrics.density).toInt()
        visibility = View.GONE
    }
    private lateinit var rows: android.widget.ScrollView
    private val controlsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val lyricsView = LyricsView(context).apply { visibility = View.GONE }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Called when you touch the card (a switch, slider or button), so the card stays up instead of closing. */
    var onInteract: (() -> Unit)? = null

    /** Called by the Close button at the bottom of the card. */
    var onClose: (() -> Unit)? = null
    private val closeButton by lazy { buttonView("Close").apply { setOnClickListener { onClose?.invoke() } } }

    private var current: Readout? = null
    private var shownImage: String? = null
    private var hasImage = false
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
        val p = (7 * d).toInt()
        // Picture (small) and the box's title pinned at the top.
        column.addView(image, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (5 * d).toInt() })
        column.addView(top, LinearLayout.LayoutParams(-1, -2))
        // Only the inside of the box scrolls: it takes what room is left (weight) and shrinks when the card is full.
        val inside = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text, LinearLayout.LayoutParams(-1, -2))
            addView(lyricsView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        }
        rows = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = false; addView(inside) }
        column.addView(rows, LinearLayout.LayoutParams(-1, -2, 1f))
        // The box's bottom border and the buttons pinned at the bottom.
        column.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        column.addView(controlsBox, LinearLayout.LayoutParams(-1, -2))
        column.addView(closeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(p, p, p, p) })
        // The box fills the card's width: its text size follows the card.
        column.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) post { fitBox() } }
        visibility = View.GONE
    }

    /** Splits the box: first line (top border), last line (bottom border), and the rows between. */
    private fun setBox(t: String) {
        val lines = t.lines()
        val boxed = lines.size >= 2 && lines.first().startsWith("+") && lines.last().startsWith("+")
        if (boxed) {
            top.text = color(lines.first()); top.visibility = View.VISIBLE
            text.text = color(lines.subList(1, lines.size - 1).joinToString("\n"))
            bottom.text = color(lines.last()); bottom.visibility = View.VISIBLE
        } else {
            top.visibility = View.GONE; bottom.visibility = View.GONE
            text.text = color(t)
        }
        fitBox()
    }

    /** The text size at which the box's widest line just fits the card (so its border sits near the card's edge). */
    private fun fitBox() {
        val room = column.width
        if (room <= 0) return
        // The box's own border line sets the width (a stray longer line would leave a gap at the edge).
        val lines = (current?.text ?: return).lines()
        val widest = lines.firstOrNull { it.startsWith("+") }?.trimEnd() ?: lines.maxByOrNull { it.length } ?: return
        if (widest.isEmpty()) return
        // Measured at a known size with sub-pixel spacing (as drawn), then scaled to the room. One pass, no loop.
        val ref = 10f
        val p = Paint(top.paint).apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ref, resources.displayMetrics) }
        val w = p.measureText(widest)
        if (w <= 0f) return
        val sp = (ref * (room - 2) / w).coerceIn(9f, 14f)
        if (kotlin.math.abs(sp - boxSp) < 0.02f) return
        boxSp = sp
        for (v in listOf(top, text, bottom)) v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
        // Short on room (the keyboard is up, or Brick's panel is tall): the photo gives way so the rows and buttons stay usable.
        val want = if (hasImage && limit >= ROOM_FOR_IMAGE_DP * d) View.VISIBLE else View.GONE
        if (image.visibility != want) image.visibility = want
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
        val fresh = current?.text?.lines()?.firstOrNull() != readout.text.lines().firstOrNull()
        current = readout
        setBox(readout.text)
        if (fresh) rows.scrollTo(0, 0) // a new answer starts at its top
        showImage(readout.image)
        buildControls(readout.controls)
        showLyrics(readout)
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
        if (!sameText) setBox(r.text) // a tick with nothing new doesn't relayout
        showLyrics(r)
        if (r.controls.size == bound.size && r.controls.indices.all { r.controls[it]::class == bound[it].control::class }) {
            r.controls.forEachIndexed { i, c -> bound[i].rebind(c) }
        } else {
            buildControls(r.controls)
        }
        if (r.live && !wasLive) { removeCallbacks(ticker); postDelayed(ticker, TICK_MS) }
    }

    private fun showLyrics(r: Readout) {
        val l = r.lyrics
        if (l == null) { lyricsView.visibility = View.GONE; return }
        lyricsView.bind(l)
        lyricsView.visibility = View.VISIBLE
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
        hasImage = false
        if (url == null) return
        ImageLoader.load(url) { b ->
            if (b != null && shownImage == url) {
                image.setImageBitmap(b)
                hasImage = true
                image.visibility = View.VISIBLE
                requestLayout()
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
        // One line always (so every button in a row is the same height): a long name gets a slightly smaller font.
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        addOnLayoutChangeListener { tv, _, _, _, _, _, _, _, _ ->
            val t = tv as TextView
            val room = t.width - t.paddingLeft - t.paddingRight
            if (room <= 0) return@addOnLayoutChangeListener
            val px = { s: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s, resources.displayMetrics) }
            val p = android.graphics.Paint(t.paint)
            var sp = 12f
            p.textSize = px(sp)
            while (sp > 9f && p.measureText(t.text.toString()) > room) { sp -= 0.5f; p.textSize = px(sp) }
            if (kotlin.math.abs(t.textSize - p.textSize) > 0.5f) t.post { t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }
        }
    }

    private fun buildControls(list: List<Control>) {
        controlsBox.removeAllViews()
        bound.clear()
        if (list.isEmpty()) return
        val gap = (8 * d).toInt()
        // Buttons in a run are spread evenly over rows that each fill the width (4 buttons: two rows of two).
        val pending = ArrayList<Control.Button>()
        fun endGroup() {
            var at = 0
            for (size in com.brick.assistant.brain.buttonRows(pending.size)) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                controlsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                for (c in pending.subList(at, at + size)) {
                    val v = buttonView(c.label)
                    val b = object : Bound {
                        override var control: Control = c
                        override fun rebind(c: Control) { control = c; v.text = "[ ${c.label} ]" }
                    }
                    v.setOnClickListener { (b.control as Control.Button).run(); refreshSoon() }
                    row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (row.childCount > 0) leftMargin = gap })
                    bound += b
                }
                at += size
            }
            pending.clear()
        }
        for (c in list) {
            when (c) {
                is Control.Button -> pending += c
                is Control.Toggle -> {
                    endGroup()
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
                    endGroup()
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
        endGroup()
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
        /** Below this much height the photo is dropped: the title, a few rows, the buttons and Close need about 300dp. */
        private const val ROOM_FOR_IMAGE_DP = 420
        private val LABEL = Patterns.get("^[A-Z][A-Z/-]{1,6}(?= |$)")

        /**
         * Fits [Box.W] to this screen: the columns of the readout that fit both this card (14dp side margins, 7dp
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
            val card = cols(screen - 42 * dp, 11.5f, 0f)
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
