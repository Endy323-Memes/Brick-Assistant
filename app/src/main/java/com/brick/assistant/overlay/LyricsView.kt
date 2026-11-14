package com.brick.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.brick.assistant.brain.LyricLine
import com.brick.assistant.brain.Lyrics
import com.brick.assistant.brain.LyricsPanel
import com.brick.assistant.brain.LyricsSync
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Driver's lyric view in Brick's terminal look: each line with its [mm:ss.xx] tag, the line playing now in an
 * outlined box with its percent and a [#####.....] bar, sung words lit word by word (the active one lifted),
 * past lines dimmed, and the list keeping the current line a third of the way down. Tap a line to jump there.
 * Plain lyrics just scroll. Kept short (about five lines) so the card stays small.
 */
class LyricsView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = sp(12.5f) }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = sp(12.5f) }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = sp(9.5f) }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * d }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint()
    // Reused every frame instead of allocated: the current line's box and the two edge fades (rebuilt only when
    // their size, place or color changes).
    private val boxRect = RectF()
    private var topFade: LinearGradient? = null
    private var bottomFade: LinearGradient? = null
    private var fadeF = -1f
    private var fadeEdge = 0
    private var fadeH = -1

    private var panel: LyricsPanel? = null
    private var lines: List<LyricLine> = emptyList()
    private var synced = false

    /** One laid-out lyric line: its wrapped rows, and for word lyrics which row and column each word sits at. */
    private class Item(val rows: List<String>, val wordRow: IntArray, val wordX: FloatArray, var top: Float = 0f, var height: Float = 0f, var grow: Float = 0f)
    private var items: List<Item> = emptyList()
    private var laidOutWidth = 0

    private var scroll = 0f
    private var follow = true
    private var lastTouchAt = 0L
    private var downY = 0f
    private var downScroll = 0f
    private var dragged = false
    private var touching = false
    private var lastY = 0f
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastFrameAt = 0L
    private var snap = true

    private val padX = 8 * d
    private val rowGap = 3 * d
    private val itemGap = 7 * d
    private val lineH get() = textPaint.fontSpacing
    private val tagH get() = tagPaint.fontSpacing

    fun bind(p: LyricsPanel) {
        if (panel?.doc === p.doc) { panel = p; return }
        panel = p
        lines = p.doc.lines
        synced = p.doc.sync != LyricsSync.PLAIN
        laidOutWidth = 0
        scroll = 0f
        follow = true
        snap = true
        scroller.forceFinished(true)
        requestLayout()
        invalidate()
    }

    /** The full-screen view: fills its space, with bigger text. */
    var fullScreen = false
        set(v) {
            field = v
            val k = if (v) 1.45f else 1f
            textPaint.textSize = sp(12.5f * k); boldPaint.textSize = sp(12.5f * k); tagPaint.textSize = sp(9.5f * k)
            laidOutWidth = 0
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (fullScreen) { setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec)); return }
        // About five lines tall: room for the boxed current line plus a couple either side.
        val h = (lineH * 5 + tagH * 3 + itemGap * 4 + 18 * d).roundToInt()
        setMeasuredDimension(w, if (lines.isEmpty()) (lineH * 2).roundToInt() else h)
    }

    private fun layoutItems(width: Int) {
        if (width == laidOutWidth && items.size == lines.size) return
        laidOutWidth = width
        val avail = width - 2 * padX - 12 * d
        val space = textPaint.measureText(" ")
        items = lines.map { line ->
            if (line.words.isNotEmpty()) {
                val rows = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
                val wr = IntArray(line.words.size)
                val wx = FloatArray(line.words.size)
                var x = 0f
                for ((i, w) in line.words.withIndex()) {
                    val ww = boldPaint.measureText(w.text)
                    if (x > 0f && x + ww > avail) { rows.add(StringBuilder()); x = 0f }
                    wr[i] = rows.lastIndex
                    wx[i] = x
                    if (rows.last().isNotEmpty()) rows.last().append(' ')
                    rows.last().append(w.text)
                    x += ww + space
                }
                Item(rows.map { it.toString() }, wr, wx)
            } else {
                // A timed blank line is a break in the singing: show a note rather than an empty box.
                Item(wrap(if (synced && line.text.isBlank()) "♪ ♪ ♪" else line.text, avail), IntArray(0), FloatArray(0))
            }
        }
    }

    private fun wrap(text: String, avail: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (w in text.split(' ')) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && boldPaint.measureText(trial) > avail) { out += cur.toString(); cur = StringBuilder(w) } else cur = StringBuilder(trial)
        }
        out += cur.toString()
        return out
    }

    private fun tag(ms: Long): String {
        val m = ms / 60_000
        val s = (ms / 1000) % 60
        val c = (ms / 10) % 100
        return "[%02d:%02d.%02d]".format(m, s, c)
    }

    /** Lays the items out top to bottom for this frame (the current line is taller: it has the bar). */
    /** Returns true while a box is still growing or shrinking. */
    private fun place(current: Int, dt: Long): Boolean {
        var y = 0f
        var moving = false
        val step = dt / BOX_MS.toFloat()
        for ((i, it) in items.withIndex()) {
            val want = if (i == current) 1f else 0f
            it.grow = if (it.grow < want) (it.grow + step).coerceAtMost(want) else (it.grow - step).coerceAtLeast(want)
            if (it.grow != want) moving = true
            it.top = y
            val tagRow = if (synced) tagH + 2 * d else 0f
            // The box and bar grow in and shrink away smoothly, so the lines around them slide instead of jumping.
            it.height = tagRow + it.rows.size * (lineH + rowGap) + it.grow * (tagH + 4 * d + 10 * d)
            y += it.height + itemGap
        }
        return moving
    }

    private fun contentHeight() = items.lastOrNull()?.let { it.top + it.height } ?: 0f

    override fun onDraw(canvas: Canvas) {
        val p = panel ?: return
        if (lines.isEmpty()) {
            textPaint.color = ViewColors.dim
            canvas.drawText(if (p.doc.instrumental) "(instrumental: no lyrics)" else "(no lyrics)", padX, lineH, textPaint)
            return
        }
        layoutItems(width)
        val pos = p.position()
        val playing = p.playing()
        val current = if (synced && pos != null) Lyrics.lineAt(lines, pos) else -1
        val now = System.currentTimeMillis()
        val dt = (if (lastFrameAt == 0L) FRAME_MS else now - lastFrameAt).coerceIn(1L, 100L)
        lastFrameAt = now
        // The first frame opens right on the current line, with nothing sliding in from the top.
        val first = snap
        snap = false
        val boxMoving = place(current, if (first) BOX_MS else dt)
        if (first && synced && current >= 0) scroll = (items[current].top - height / 3f).coerceIn(0f, maxScroll())

        // Follow the song: the current line a third of the way down (paused for a few seconds after you scroll).
        val maxScroll = maxScroll()
        val flinging = !scroller.isFinished
        if (!follow && !touching && !flinging && now - lastTouchAt > FOLLOW_AGAIN_MS) follow = true
        var gliding = false
        if (synced && follow && current >= 0 && !touching && !flinging) {
            val target = (items[current].top - height / 3f).coerceIn(0f, maxScroll)
            // Eases over about half a second, the same at any frame rate.
            scroll += (target - scroll) * (1f - Math.pow(0.004, dt / 1000.0).toFloat())
            if (abs(target - scroll) < 0.5f) scroll = target else gliding = true
        }
        scroll = scroll.coerceIn(0f, maxScroll)

        val brick = ViewColors.brick
        val unit = ViewColors.unit
        val text = ViewColors.text
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(0f, -scroll)
        for ((i, it) in items.withIndex()) {
            if (it.top + it.height < scroll - 4 * d || it.top > scroll + height + 4 * d) continue
            val line = lines[i]
            val isCurrent = i == current
            val nextT = lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
            val isPast = synced && pos != null && pos >= nextT
            val a = when { !synced -> 235; isCurrent -> 255; isPast -> 170; else -> 225 }
            val inset = it.grow * 6 * d
            var y = it.top + inset
            if (it.grow > 0.02f) {
                // The outlined box Driver draws around the line playing now (fading in and out with its size).
                val r = boxRect.apply { set(1 * d, it.top, width - 1 * d, it.top + it.height) }
                boxFill.color = ViewColors.alpha(brick, (26 * it.grow).roundToInt())
                canvas.drawRoundRect(r, 4 * d, 4 * d, boxFill)
                boxPaint.color = ViewColors.alpha(brick, (150 * it.grow).roundToInt())
                canvas.drawRoundRect(r, 4 * d, 4 * d, boxPaint)
            }
            val span = ((if (nextT == Long.MAX_VALUE) (line.timeMs ?: 0) + 4000 else nextT) - (line.timeMs ?: 0)).coerceIn(400L, 16_000L)
            val progress = if (isCurrent && pos != null) ((pos - (line.timeMs ?: 0)).toFloat() / span).coerceIn(0f, 1f) else if (isPast) 1f else 0f
            if (synced) {
                tagPaint.color = ViewColors.alpha(if (isCurrent) brick else unit, if (isCurrent) 255 else a * 3 / 4)
                tagPaint.isFakeBoldText = isCurrent
                y += tagH
                canvas.drawText(tag(line.timeMs ?: 0), padX, y - tagPaint.descent(), tagPaint)
                if (isCurrent) {
                    val pct = "${(progress * 100).roundToInt()}%"
                    canvas.drawText(pct, width - padX - tagPaint.measureText(pct), y - tagPaint.descent(), tagPaint)
                }
                y += 2 * d
            }
            val paintFor = { bold: Boolean -> if (bold) boldPaint else textPaint }
            if (line.words.isNotEmpty() && pos != null) {
                for ((wi, w) in line.words.withIndex()) {
                    val nextW = line.words.getOrNull(wi + 1)?.timeMs ?: nextT
                    val active = isCurrent && pos >= w.timeMs && pos < nextW
                    val sung = pos >= w.timeMs
                    val pt = paintFor(isCurrent)
                    pt.color = when {
                        active -> brick
                        sung && isCurrent -> ViewColors.alpha(brick, 225)
                        isCurrent -> text
                        else -> ViewColors.alpha(text, a)
                    }
                    val baseY = y + (it.wordRow[wi] + 1) * (lineH + rowGap) - rowGap - pt.descent()
                    val x = padX + it.wordX[wi]
                    if (active) {
                        // The word being sung: a little bigger and lifted, as in Driver.
                        canvas.save()
                        val ww = pt.measureText(w.text)
                        canvas.scale(1.08f, 1.08f, x + ww / 2, baseY)
                        canvas.drawText(w.text, x, baseY - 3 * d, pt)
                        canvas.restore()
                    } else {
                        canvas.drawText(w.text, x, baseY, pt)
                    }
                }
                y += it.rows.size * (lineH + rowGap)
            } else {
                val pt = paintFor(isCurrent)
                pt.color = when { isCurrent -> brick; else -> ViewColors.alpha(text, a) }
                for (row in it.rows) {
                    y += lineH
                    canvas.drawText(row, padX, y - pt.descent(), pt)
                    y += rowGap
                }
            }
            if (isCurrent && it.grow > 0.6f) {
                // [#########...........] under the line, filling as it's sung.
                val cols = ((width - 2 * padX) / tagPaint.measureText("#")).toInt() - 2
                val filled = (progress * cols).roundToInt().coerceIn(0, cols)
                y += tagH
                val bar = "[" + "#".repeat(filled) + ".".repeat(cols - filled) + "]"
                tagPaint.color = ViewColors.alpha(brick, (255 * ((it.grow - 0.6f) / 0.4f)).roundToInt().coerceIn(0, 255))
                tagPaint.isFakeBoldText = false
                canvas.drawText(bar, padX, y - tagPaint.descent(), tagPaint)
            }
        }
        canvas.restore()

        // Soft fade at the top and bottom edges into the card.
        val f = (if (fullScreen) 28 else 16) * d
        val edge = if (fullScreen) ViewColors.background else ViewColors.panel

        if (f != fadeF || edge != fadeEdge || height != fadeH || topFade == null) {
            fadeF = f; fadeEdge = edge; fadeH = height
            topFade = LinearGradient(0f, 0f, 0f, f, edge, ViewColors.alpha(edge, 0), Shader.TileMode.CLAMP)
            bottomFade = LinearGradient(0f, height - f, 0f, height.toFloat(), ViewColors.alpha(edge, 0), edge, Shader.TileMode.CLAMP)
        }
        fade.shader = topFade
        if (scroll > 1f) canvas.drawRect(0f, 0f, width.toFloat(), f, fade)
        fade.shader = bottomFade
        if (scroll < maxScroll - 1f) canvas.drawRect(0f, height - f, width.toFloat(), height.toFloat(), fade)

        // Every screen refresh while anything moves (the song playing, a glide, a drag or fling, the box resizing);
        // only a paused, still list waits between redraws.
        if (isShown) {
            if ((synced && playing) || gliding || touching || flinging || boxMoving) postInvalidateOnAnimation()
            else postInvalidateDelayed(IDLE_MS)
        }
    }

    private fun maxScroll() = (contentHeight() - height + 12 * d).coerceAtLeast(0f)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat()
            lastTouchAt = System.currentTimeMillis()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // A touch catches a coasting list where it is (and isn't a tap).
                dragged = !scroller.isFinished
                scroller.forceFinished(true)
                touching = true
                downY = e.y; lastY = e.y; downScroll = scroll
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged && abs(e.y - downY) > slop) { dragged = true; lastY = e.y }
                if (dragged) {
                    scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll())
                    lastY = e.y
                    follow = false
                    lastTouchAt = System.currentTimeMillis()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touching = false
                if (dragged) {
                    // Let go mid-swipe: keep coasting, like any list.
                    vt.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vy = vt.yVelocity
                    if (abs(vy) > minFling) {
                        scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                    follow = false
                    lastTouchAt = System.currentTimeMillis()
                } else {
                    // Tap a line: jump the song there.
                    val y = e.y + scroll
                    val i = items.indexOfFirst { y >= it.top && y < it.top + it.height + itemGap }
                    val t = lines.getOrNull(i)?.timeMs
                    val seek = panel?.seek
                    if (t != null && seek != null) {
                        seek(t)
                        follow = true
                        performClick()
                    }
                }
                vt.recycle(); velocity = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touching = false
                velocity?.recycle(); velocity = null
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private const val FRAME_MS = 33L
        private const val IDLE_MS = 500L
        private const val FOLLOW_AGAIN_MS = 4000L
        private const val BOX_MS = 220L
    }
}
