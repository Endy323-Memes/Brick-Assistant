package com.brick.assistant.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Apps that get their own colors for the edge animation. */
enum class Brand(val colors: IntArray) {
    SPOTIFY(intArrayOf(0xFF1DB954.toInt(), 0xFF1ED760.toInt(), 0xFF169C46.toInt(), 0xFF57E389.toInt())),
    YOUTUBE(intArrayOf(0xFFFF0000.toInt(), 0xFFFF4E45.toInt(), 0xFFCC0000.toInt(), 0xFFFF2D55.toInt())),
    /** The four Google colors around the screen: blue, red, yellow, green. */
    GOOGLE_MAPS(intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt())),
    /** Driver, in monochrome: white through silver to graphite. */
    DRIVER(intArrayOf(0xFFFFFFFF.toInt(), 0xFFB8B8B8.toInt(), 0xFF6A6A6A.toInt(), 0xFFDCDCDC.toInt())),
}

/**
 * The intro in an app's colors (Spotify green, YouTube red, the four Google colors): two light trails race from
 * the bottom center up both screen edges and meet at the top, lighting a thin band of dots that hug the edges
 * with the intro's heat-shimmer distortion. The middle of the screen stays clear. Plays once and fades.
 */
class BrandGlowView(context: Context, brand: Brand) : View(context) {
    private val d = resources.displayMetrics.density
    private val cols = brand.colors
    /** The colors in a loop around the screen, for the sweep and for the dots. */
    private val ring = intArrayOf(cols[0], cols[1], cols[2], cols[3], cols[0])

    private val right = Path()
    private val left = Path()
    private val seg = Path()
    private val rightMeasure = PathMeasure()
    private val leftMeasure = PathMeasure()
    private val measures = arrayOf(rightMeasure, leftMeasure)
    private var length = 0f
    private val pos = FloatArray(2)
    private val loc = IntArray(2)
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null

    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(22 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private var t = 0f
    private var elapsed = 0f

    var onFinished: (() -> Unit)? = null

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TOTAL_MS.toLong()
        interpolator = LinearInterpolator()
        addUpdateListener {
            val ms = (it.animatedValue as Float) * TOTAL_MS
            elapsed = ms / 1000f
            t = ms / TOTAL_MS
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

    fun play() {
        alpha = 1f
        visibility = VISIBLE
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

    /** The ring color at a point, by its angle around the screen center (matches the trails' sweep). */
    private fun colorAround(x: Float, y: Float): Int {
        var a = (atan2(y - height / 2f, x - width / 2f) / (2 * Math.PI)).toFloat() - elapsed * 80f / 360f
        a -= kotlin.math.floor(a)
        val p = a * (ring.size - 1)
        val i = p.toInt().coerceIn(0, ring.size - 2)
        return ViewColors.mix(ring[i], ring[i + 1], p - i)
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
        right.moveTo(cx, b); right.lineTo(r - rad, b)
        right.arcTo(r - 2 * rad, b - 2 * rad, r, b, 90f, -90f, false)
        right.lineTo(r, tp + rad)
        right.arcTo(r - 2 * rad, tp, r, tp + 2 * rad, 0f, -90f, false)
        right.lineTo(cx, tp)
        left.reset()
        left.moveTo(cx, b); left.lineTo(l + rad, b)
        left.arcTo(l, b - 2 * rad, l + 2 * rad, b, 90f, 90f, false)
        left.lineTo(l, tp + rad)
        left.arcTo(l, tp, l + 2 * rad, tp + 2 * rad, 180f, 90f, false)
        left.lineTo(cx, tp)
        rightMeasure.setPath(right, false)
        leftMeasure.setPath(left, false)
        length = rightMeasure.length
        sweep = SweepGradient(cx, h / 2f, ring, null)
    }

    /** How far along a trail (from the bottom center) the edge point nearest (x, y) is. */
    private fun along(x: Float, y: Float): Float {
        val w = width.toFloat()
        val h = height.toFloat()
        val dx = abs(x - w / 2f)
        val toSide = w / 2f - dx
        val toBottom = h - y
        val toTop = y
        return when (minOf(toSide, toBottom, toTop)) {
            toBottom -> dx
            toSide -> w / 2f + (h - y)
            else -> w / 2f + h + (w / 2f - dx)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (length <= 0f) return
        // Keep Brick's card clear only while it's on screen (read live: the card leaves as the app opens).
        IntroOverlay.hole?.let { hr ->
            getLocationOnScreen(loc)
            canvas.clipOutRect(hr.left - loc[0], hr.top - loc[1], hr.right - loc[0], hr.bottom - loc[1])
        }
        // Same shape as the intro: trace, flash and glow, then fade.
        val trace = 1f - (1f - (t / 0.36f).coerceIn(0f, 1f)).pow(3)
        val fade = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f).coerceIn(0f, 1f).pow(1.6f)
        val flash = if (t in 0.32f..0.5f) 1f - abs(t - 0.41f) / 0.09f else 0f
        val s = sweep ?: return
        matrix.setRotate(elapsed * 80f, width / 2f, height / 2f)
        s.setLocalMatrix(matrix)

        drawEdgeDots(canvas, trace, fade)

        val end = length * trace
        val holding = t > 0.36f && t < 0.7f
        val breathe = if (holding) 0.88f + 0.12f * sin(elapsed * 4.2f) else 1f
        bloom.shader = s
        line.shader = s
        for (m in measures) {
            seg.reset()
            m.getSegment(0f, end, seg, true)
            bloom.strokeWidth = (12f + 10f * flash) * d
            bloom.alpha = ((170 + 85 * flash) * fade * breathe).toInt().coerceIn(0, 255)
            canvas.drawPath(seg, bloom)
            line.strokeWidth = (3f + 1.5f * flash) * d
            line.alpha = (240 * fade).toInt().coerceIn(0, 255)
            canvas.drawPath(seg, line)
            if (trace < 1f) {
                m.getPosTan(end, pos, null)
                val r = 26 * d
                head.shader = RadialGradient(pos[0], pos[1], r,
                    intArrayOf(Color.argb(230, 255, 255, 255), ViewColors.alpha(colorAround(pos[0], pos[1]), 140), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(pos[0], pos[1], r, head)
            }
        }
    }

    /**
     * Dots in a thin band along every edge (a little deeper at the bottom), lit as the trails pass, with the
     * intro's continuous shimmer: two travelling waves bend the grid and the dots swell and shrink.
     */
    private fun drawEdgeDots(canvas: Canvas, trace: Float, fade: Float) {
        val level = (min(1f, t / 0.12f) * fade).coerceIn(0f, 1f)
        if (level < 0.02f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val spacing = 13 * d
        val sideBand = 34 * d
        val bottomBand = 64 * d
        val end = length * trace
        val swayAmp = 7 * d
        var y = spacing * 0.5f
        var row = 0
        while (y < h) {
            val stagger = if (row % 2 == 0) 0f else spacing / 2f
            val fromTop = y / sideBand
            val fromBottom = (h - y) / bottomBand
            val rowDepth = min(fromTop, fromBottom)
            var x = spacing * 0.5f + stagger
            // Rows away from the top and bottom only have dots near the sides: jump across the middle.
            while (x < w) {
                val fromSide = min(x, w - x) / sideBand
                val depth = min(fromSide, rowDepth)
                if (depth >= 1f) {
                    if (rowDepth >= 1f && x < w / 2f) x = w - sideBand - ((w - sideBand - x) % spacing) else x += spacing
                    continue
                }
                val v = 1f - depth
                val behind = end - along(x, y)
                if (behind > 0f) {
                    // Brighter right behind the trail heads, settling to a soft glow.
                    val wake = kotlin.math.exp(-behind / (160 * d))
                    val xd = x / d
                    val yd = y / d
                    val sway = swayAmp * level * (0.45f + 0.55f * v)
                    val sx = sway * (0.65f * sin(yd * 0.021f + xd * 0.006f + elapsed * 2.3f) + 0.35f * sin(xd * 0.047f - yd * 0.012f - elapsed * 3.4f))
                    val sy = sway * 0.55f * (0.6f * sin(xd * 0.017f - yd * 0.009f + elapsed * 1.9f) + 0.4f * sin(yd * 0.051f + xd * 0.02f - elapsed * 2.8f))
                    val swell = 1f + 0.3f * sin(xd * 0.031f + yd * 0.024f - elapsed * 3.1f) * level
                    val bright = (level * (0.1f + 0.75f * v.pow(1.4f)) + 0.6f * wake * level * v).coerceIn(0f, 1f)
                    if (bright > 0.03f) {
                        dot.color = colorAround(x, y)
                        dot.alpha = (bright * 235).toInt()
                        canvas.drawCircle(x + sx, y + sy, (1.0f + 0.9f * wake + 0.6f * v) * d * swell, dot)
                    }
                }
                x += spacing
            }
            y += spacing * 0.866f
            row++
        }
    }

    private companion object {
        const val TOTAL_MS = 2700f
    }
}
