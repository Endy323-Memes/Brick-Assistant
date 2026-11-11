package com.brick.assistant.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow

/**
 * One-shot intro played when the assistant opens: two light trails race from the bottom center up both
 * screen edges (following the display's rounded corners) and meet at the top, over a warm haze rising
 * from the bottom. The border then flashes and fades. Not voice reactive; same every time.
 */
class EdgeGlowView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    // Same animation in any palette: Brick's color, a warm blend, the unit's color and a light tint.
    private val amber = ViewColors.brick
    private val ember = ViewColors.ember
    private val cyan = ViewColors.unit
    private val ice = ViewColors.ice

    private val right = Path()
    private val left = Path()
    private val seg = Path()
    private val loc = IntArray(2)
    private var length = 0f
    private val rightMeasure = PathMeasure()
    private val leftMeasure = PathMeasure()
    private val pos = FloatArray(2)
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null

    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(22 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val measures = arrayOf(rightMeasure, leftMeasure)
    private val headMatrix = Matrix()
    private val headShader = RadialGradient(0f, 0f, HEAD_R * d,
        intArrayOf(Color.argb(230, 255, 255, 255), ViewColors.alpha(ice, 120), Color.TRANSPARENT),
        floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = headShader }
    private val haze = Paint()

    /** 0..1 progress of the whole intro. */
    private var t = 1f
    /** Seconds since the intro started (ripples and the glow's breathing keep moving during the hold). */
    private var elapsed = 0f
    /** True while the finished border glows before fading. */
    private var holding = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        // Trace and flash (0.9 s), then glow and hold (1.1 s), then fade (0.7 s).
        duration = (TRACE_MS + HOLD_MS + FADE_MS).toLong()
        interpolator = LinearInterpolator()
        addUpdateListener {
            val ms = (it.animatedValue as Float) * (TRACE_MS + HOLD_MS + FADE_MS)
            elapsed = ms / 1000f
            holding = ms >= TRACE_MS && ms < TRACE_MS + HOLD_MS
            t = when {
                ms < TRACE_MS -> ms / TRACE_MS * 0.62f
                ms < TRACE_MS + HOLD_MS -> 0.62f
                else -> 0.62f + (ms - TRACE_MS - HOLD_MS) / FADE_MS * 0.38f
            }
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                visibility = GONE
                onFinished?.invoke()
                onFinished = null
            }
        })
    }

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
        visibility = GONE
    }

    /** Screen area to leave clear (the card, when the intro is drawn above it). Null = draw everywhere. */
    var hole: android.graphics.RectF? = null
        set(v) { field = v; invalidate() }

    /** Called once when the intro has fully faded out. */
    var onFinished: (() -> Unit)? = null

    val isPlaying: Boolean get() = animator.isRunning

    /** Fades the intro out quickly (a second tap after dismissing Brick), then finishes it. */
    fun fadeAway() {
        if (!animator.isRunning) return
        animate().cancel()
        animate().alpha(0f).setDuration(260).withEndAction { animator.cancel() }.start()
    }

    fun play() {
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        t = 0f
        animator.cancel()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun cornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
            if (r != null && r > 0) return r.toFloat()
        }
        return 44 * d
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = 2.5f * d
        val l = inset
        val tp = inset
        val r = w - inset
        val b = h - inset
        val rad = min(cornerRadius(), min(w, h) / 2f) - inset
        val cx = w / 2f
        right.reset()
        right.moveTo(cx, b)
        right.lineTo(r - rad, b)
        right.arcTo(r - 2 * rad, b - 2 * rad, r, b, 90f, -90f, false)
        right.lineTo(r, tp + rad)
        right.arcTo(r - 2 * rad, tp, r, tp + 2 * rad, 0f, -90f, false)
        right.lineTo(cx, tp)
        left.reset()
        left.moveTo(cx, b)
        left.lineTo(l + rad, b)
        left.arcTo(l, b - 2 * rad, l + 2 * rad, b, 90f, 90f, false)
        left.lineTo(l, tp + rad)
        left.arcTo(l, tp, l + 2 * rad, tp + 2 * rad, 180f, 90f, false)
        left.lineTo(cx, tp)
        rightMeasure.setPath(right, false)
        leftMeasure.setPath(left, false)
        length = rightMeasure.length
        sweep = SweepGradient(cx, h / 2f, intArrayOf(amber, ember, cyan, ice, cyan, ember, amber), null)
        haze.shader = LinearGradient(0f, h.toFloat(), 0f, h * 0.62f,
            intArrayOf(ViewColors.alpha(ViewColors.mix(amber, ember, 0.5f), 150), ViewColors.alpha(cyan, 70), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (length <= 0f) return
        hole?.let { h ->
            getLocationOnScreen(loc)
            canvas.clipOutRect(h.left - loc[0], h.top - loc[1], h.right - loc[0], h.bottom - loc[1])
        }
        // Phases: trace 0..0.5, full-border flash 0.5..0.62, fade 0.62..1.
        val trace = easeOutCubic((t / 0.5f).coerceIn(0f, 1f))
        val fade = if (t < 0.62f) 1f else (1f - (t - 0.62f) / 0.38f).coerceIn(0f, 1f).pow(1.6f)
        val flash = if (t in 0.48f..0.7f) 1f - kotlin.math.abs(t - 0.56f) / 0.14f else 0f

        // Warm haze rising from the bottom edge.
        val hazeLevel = (min(1f, t / 0.25f) * fade).coerceIn(0f, 1f)
        if (hazeLevel > 0.01f) {
            haze.alpha = (hazeLevel * 255).toInt()
            val rise = height * (0.62f + 0.38f * (1f - trace))
            canvas.save()
            canvas.translate(0f, rise - height * 0.62f)
            canvas.drawRect(0f, height * 0.62f, width.toFloat(), height.toFloat(), haze)
            canvas.restore()
        }

        drawDotMatrix(canvas, trace, fade)

        val s = sweep ?: return
        matrix.setRotate(elapsed * 80f, width / 2f, height / 2f)
        s.setLocalMatrix(matrix)
        bloom.shader = s
        line.shader = s
        val end = length * trace
        for (m in measures) {
            seg.reset()
            m.getSegment(0f, end, seg, true)
            bloom.strokeWidth = (12f + 10f * flash) * d
            val breathe = if (holding) 0.88f + 0.12f * kotlin.math.sin(elapsed * 4.2f) else 1f
            bloom.alpha = ((170 + 85 * flash) * fade * breathe).toInt().coerceIn(0, 255)
            canvas.drawPath(seg, bloom)
            line.strokeWidth = (3f + 1.5f * flash) * d
            line.alpha = (240 * fade).toInt().coerceIn(0, 255)
            canvas.drawPath(seg, line)
            // Bright head on each trail while it travels.
            if (trace < 1f) {
                m.getPosTan(end, pos, null)
                // One head gradient made once at the origin, moved to the trail's head (same look, no new shader per frame).
                headMatrix.setTranslate(pos[0], pos[1])
                headShader.setLocalMatrix(headMatrix)
                canvas.drawCircle(pos[0], pos[1], HEAD_R * d, head)
            }
        }
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Amber (bottom) -> cyan (top) palette for the dots. */
    private val dotColors = IntArray(24) { i ->
        ViewColors.mix(ViewColors.mix(amber, ember, 0.5f), ViewColors.mix(cyan, ice, 0.4f), i / 23f)
    }

    /**
     * Dot-matrix texture lit by the rising haze. A wave front expands from the bottom center with the
     * light trails; dots near it are pushed outward and swell, with a small ripple trailing behind.
     */
    private fun drawDotMatrix(canvas: Canvas, trace: Float, fade: Float) {
        val level = (min(1f, t / 0.2f) * fade).coerceIn(0f, 1f)
        if (level < 0.02f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val spacing = 13 * d
        val cx = w / 2f
        val hazeTop = h * (0.30f + 0.45f * (1f - trace))   // the lit area rises with the trails
        val front = trace * h * 1.15f                         // wave front radius from bottom center
        val sigma = 70 * d
        val push = 16 * d
        val ripplePhase = elapsed * 18f
        val swayAmp = 7 * d
        val g2 = 2 * sigma * sigma
        val rippleFall = 220 * d
        val e1 = elapsed * 2.3f
        val e2 = elapsed * 3.4f
        val e3 = elapsed * 1.9f
        val e4 = elapsed * 2.8f
        val e5 = elapsed * 3.1f
        val grid = dotGrid(w, h, spacing, cx)
        val pts = grid.pts
        for (row in 0 until grid.rows) {
            val y = grid.rowY[row]
            if (y <= hazeTop - spacing * 4) break
            val v = ((y - hazeTop) / (h - hazeTop)).coerceIn(0f, 1f)
            val sway = swayAmp * level * (0.45f + 0.55f * v)
            val vGlow = 0.04f + 0.7f * v.pow(1.6f)
            val color = dotColors[((1f - v) * 23).toInt().coerceIn(0, 23)]
            val baseRadius = 0.5f * v
            var i = grid.rowStart[row] * STRIDE
            val endI = grid.rowStart[row + 1] * STRIDE
            while (i < endI) {
                val x = pts[i]
                val r = pts[i + 2]
                val off = r - front
                val g = kotlin.math.exp(-(off * off) / g2)
                // Behind the front: a damped ripple.
                val ripple = if (off < 0) kotlin.math.sin(pts[i + 5] - ripplePhase) * kotlin.math.exp(off / rippleFall) * 0.35f else 0f
                val disp = push * (g + ripple)
                // Continuous sway: two travelling waves bend the grid like heat shimmer over the whole intro.
                val sx = sway * (0.65f * sin(pts[i + 6] + e1) + 0.35f * sin(pts[i + 7] - e2))
                val sy = sway * 0.55f * (0.6f * sin(pts[i + 8] + e3) + 0.4f * sin(pts[i + 9] - e4))
                val px = x + pts[i + 3] * disp + sx
                val py = y + pts[i + 4] * disp + sy
                // Dots swell and shrink as the distortion passes through them.
                val swell = 1f + 0.3f * sin(pts[i + 10] - e5) * level
                val edge = pts[i + 11]
                val bright = (level * (vGlow + 0.3f * edge * edge) + 0.9f * g * level).coerceIn(0f, 1f)
                if (bright > 0.03f) {
                    dot.color = color
                    dot.alpha = (bright * 235).toInt()
                    canvas.drawCircle(px, py, (1.0f + 1.7f * g + baseRadius) * d * swell, dot)
                }
                i += STRIDE
            }
        }
    }

    /**
     * The dot grid's fixed per-dot values (position, distance and direction from the bottom center, and the
     * spatial parts of the sway waves), worked out once per size instead of every frame.
     */
    private class DotGrid(val w: Float, val h: Float, val rows: Int, val rowY: FloatArray, val rowStart: IntArray, val pts: FloatArray)
    private var grid: DotGrid? = null

    private fun dotGrid(w: Float, h: Float, spacing: Float, cx: Float): DotGrid {
        grid?.let { if (it.w == w && it.h == h) return it }
        // Every row the intro can ever reach (the lit area's highest top is 30% down the screen).
        val lowest = h * 0.30f - spacing * 4
        val ys = ArrayList<Float>()
        val starts = ArrayList<Int>()
        val values = ArrayList<Float>()
        var y = h - spacing * 0.5f
        var row = 0
        var count = 0
        while (y > lowest) {
            ys += y
            starts += count
            val stagger = if (row % 2 == 0) 0f else spacing / 2f
            var x = spacing * 0.5f + stagger
            while (x < w) {
                val dx = x - cx
                val dy = y - h
                val r = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val xd = x / d
                val yd = y / d
                values += x; values += y; values += r
                values += dx / r; values += dy / r
                values += r / (22 * d)
                values += yd * 0.021f + xd * 0.006f
                values += xd * 0.047f - yd * 0.012f
                values += xd * 0.017f - yd * 0.009f
                values += yd * 0.051f + xd * 0.02f
                values += xd * 0.031f + yd * 0.024f
                values += 1f - (min(x, w - x) / (w * 0.22f)).coerceIn(0f, 1f)
                count++
                x += spacing
            }
            y -= spacing * 0.866f
            row++
        }
        starts += count
        return DotGrid(w, h, ys.size, ys.toFloatArray(), starts.toIntArray(), values.toFloatArray()).also { grid = it }
    }

    private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

    private companion object {
        const val TRACE_MS = 900f
        const val HOLD_MS = 1100f
        const val FADE_MS = 700f
        const val HEAD_R = 26f
        /** Floats stored per dot in [DotGrid.pts]. */
        const val STRIDE = 12
    }
}
