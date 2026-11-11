package com.brick.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Brick's "dual-voice ribbon" card art.
 *
 * - A rotating amber -> cyan bloom rings a deep navy glass card (amber = David, cyan = robot unit).
 * - Across the top, luminous amber silk (David) is woven with spring-loaded cyan bars (the robot unit).
 * - Everything follows a real level: your microphone while listening, Brick's own audio output while
 *   David or the robot speaks. Fast attack, slower release, gentle breathing in silence.
 */
class PulseView(context: Context) : View(context) {

    enum class Mode { LISTENING, THINKING, DAVID, ROBOT, MESSAGE }

    var mode = Mode.LISTENING
    /** Microphone level 0..1 (listening). */
    var micLevel = 0f
    /** Brick's playback level 0..1 (while David or the robot speaks). */
    var outputLevel: () -> Float = { 0f }

    private val d = resources.displayMetrics.density
    val glowPad = 18 * d
    val ribbonHeight = 58 * d
    private val radius = 30 * d

    // Colors from the active palette (amber = Brick, cyan = the unit in the default palette).
    private val amber = ViewColors.brick
    private val ember = ViewColors.ember
    private val cyan = ViewColors.unit
    private val ice = ViewColors.ice
    private val teal = ViewColors.teal

    private val card = RectF()
    private val inner = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(16 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(22 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val silk = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val silkGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL) }
    private val path = Path()
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    private var silkShade: LinearGradient? = null

    // Animation state
    private var env = 0f          // smoothed voice level
    private var aAmp = 0.3f
    private var cAmp = 0.3f
    private var spin = 0.3f
    private var bias = 0.5f       // 0 = amber, 1 = cyan
    private var angle = 0f
    private var time = 0f
    private var lastFrame = 0L
    private var bars = FloatArray(0)
    /** 0..1, fades the loading comet in while Brick works and the voice art out. */
    private var loading = 0f
    private var silentFor = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val trailGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(7 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(9 * d, BlurMaskFilter.Blur.NORMAL) }

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    init {
        // Blur mask filters are hardware accelerated from Android 9; older versions draw in software.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
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

    /** Only take a height when one is given exactly; with "wrap content" parents the card's text decides. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else suggestedMinimumHeight
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        card.set(glowPad, glowPad, w - glowPad, h - glowPad)
        inner.set(card.left + 1.5f * d, card.top + 1.5f * d, card.right - 1.5f * d, card.bottom - 1.5f * d)
        sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(amber, ember, cyan, teal, cyan, ice, amber), null)
        fill.shader = LinearGradient(0f, card.top, 0f, card.bottom,
            intArrayOf(
                ViewColors.alpha(ViewColors.mix(ViewColors.panel, ViewColors.text, 0.06f), 238),
                ViewColors.alpha(ViewColors.panel, 244),
                ViewColors.alpha(ViewColors.background, 250),
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        highlight.shader = LinearGradient(0f, card.top, 0f, card.top + 36 * d,
            Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val mid = card.top + ribbonHeight * 0.55f
        bar.shader = LinearGradient(0f, mid - ribbonHeight * 0.4f, 0f, mid + ribbonHeight * 0.4f,
            intArrayOf(ice, cyan, teal, cyan, ice), floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
        barGlow.color = cyan
        silkShade = LinearGradient(card.left, 0f, card.right, 0f,
            intArrayOf(Color.TRANSPARENT, amber, ember, amber, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        bars = FloatArray(((card.width() - radius * 1.2f) / (7 * d)).toInt().coerceAtLeast(8))
        barEnv = FloatArray(bars.size) { i -> sin(PI * (i / (bars.size - 1f))).toFloat() }
        // The silk's sample points and their fixed bell envelope.
        val left = card.left + radius * 0.5f
        val right = card.right - radius * 0.5f
        val w = right - left
        val us = ArrayList<Float>()
        var px = left
        while (px <= right) {
            us += (px - left) / w
            px += 2.5f * d
        }
        silkX = FloatArray(us.size)
        px = left
        for (i in us.indices) { silkX[i] = px; px += 2.5f * d }
        silkU = us.toFloatArray()
        silkEnv = FloatArray(us.size) { i -> sin(PI * silkU[i]).toFloat() }
    }

    private var barEnv = FloatArray(0)
    private var silkX = FloatArray(0)
    private var silkU = FloatArray(0)
    private var silkEnv = FloatArray(0)
    private val silkWidths = floatArrayOf(2.8f, 2.0f, 1.4f, 1.0f)
    private val silkAlphas = intArrayOf(255, 175, 115, 75)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        time += dt

        // Voice envelope: your mic while listening, Brick's own output while it speaks.
        val out = outputLevel()
        val speaking = mode == Mode.DAVID || mode == Mode.ROBOT
        // Between lines (Brick waiting on a result) the speaking modes go quiet: show the loader instead.
        silentFor = if (speaking && out <= 0.01f) silentFor + dt else 0f
        val waiting = mode == Mode.THINKING || (speaking && silentFor > 0.45f)
        val raw = when (mode) {
            Mode.LISTENING -> micLevel
            Mode.DAVID, Mode.ROBOT -> out
            else -> 0f
        }.coerceIn(0f, 1f)
        env += (raw - env) * min(1f, dt * if (raw > env) 24f else 5f)
        val breath = 0.05f * (0.5f + 0.5f * sin(time * 1.7f))

        val aT: Float; val cT: Float; val sT: Float; val bT: Float
        when (mode) {
            Mode.LISTENING -> { aT = 0.16f + breath + 0.95f * env; cT = 0.16f + breath + 0.95f * env; sT = 0.3f + env * 0.6f; bT = 0.5f }
            Mode.THINKING -> { aT = 0f; cT = 0f; sT = 0.55f; bT = 0.5f }
            Mode.DAVID -> { aT = 0.3f + 0.85f * env; cT = 0.08f; sT = 0.35f; bT = 0.08f }
            Mode.ROBOT -> { aT = 0.08f; cT = 0.3f + 0.85f * env; sT = 0.35f; bT = 0.92f }
            Mode.MESSAGE -> { aT = 0.14f; cT = 0.14f; sT = 0.2f; bT = 0.5f }
        }
        val k = min(1f, dt * 9f)
        aAmp += (aT - aAmp) * k
        cAmp += (cT - cAmp) * k
        spin += (sT - spin) * min(1f, dt * 4f)
        bias += (bT - bias) * min(1f, dt * 5f)
        angle = (angle + spin * dt * 360f) % 360f
        loading += ((if (waiting) 1f else 0f) - loading) * min(1f, dt * 6f)

        drawBloom(canvas)
        canvas.drawRoundRect(card, radius, radius, fill)
        canvas.drawRoundRect(inner, radius - 1.5f * d, radius - 1.5f * d, highlight)
        drawEdge(canvas)
        drawBars(canvas, dt)
        drawSilk(canvas)
        drawLoader(canvas)
    }

    private fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    /**
     * Loading: a faint gradient rail across the ribbon with a luminous comet gliding back and forth on it,
     * amber on the left warming into cyan on the right, its trail fading behind it.
     */
    private fun drawLoader(canvas: Canvas) {
        if (loading < 0.01f) return
        val left = card.left + radius * 0.9f
        val right = card.right - radius * 0.9f
        val w = right - left
        val mid = card.top + ribbonHeight * 0.55f
        val a = loading

        track.shader = silkShade
        track.strokeWidth = 1.4f * d
        track.alpha = (70 * a).toInt()
        canvas.drawLine(left, mid, right, mid, track)

        val period = 1.9f
        fun pos(tt: Float): Float = 0.5f - 0.5f * kotlin.math.cos((tt / period * 2 * PI).toFloat())
        val u = pos(time)
        val tailU = pos(time - 0.26f)
        val hx = left + u * w
        val tx = left + tailU * w
        val color = mix(amber, cyan, u)
        if (abs(hx - tx) > 1f) {
            val g = LinearGradient(tx, mid, hx, mid, Color.TRANSPARENT, color, Shader.TileMode.CLAMP)
            trailGlow.shader = g
            trailGlow.strokeWidth = 7 * d
            trailGlow.alpha = (150 * a).toInt()
            canvas.drawLine(tx, mid, hx, mid, trailGlow)
            trail.shader = g
            trail.strokeWidth = 2.6f * d
            trail.alpha = (255 * a).toInt()
            canvas.drawLine(tx, mid, hx, mid, trail)
        }
        val pulse = 1f + 0.12f * sin(time * 6.2f)
        headGlow.color = color
        headGlow.alpha = (200 * a).toInt()
        canvas.drawCircle(hx, mid, 9 * d * pulse, headGlow)
        headPaint.color = mix(Color.WHITE, color, 0.25f)
        headPaint.alpha = (255 * a).toInt()
        canvas.drawCircle(hx, mid, 3.4f * d, headPaint)
    }


    private fun drawBloom(canvas: Canvas) {
        val s = sweep ?: return
        matrix.setRotate(angle, width / 2f, height / 2f)
        s.setLocalMatrix(matrix)
        bloom.shader = s
        bloom.strokeWidth = (5f + 5f * env) * d
        bloom.alpha = (120 + 120 * env + 15 * sin(time * 2.2f)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(card, radius, radius, bloom)
        // Wash toward the voice that's speaking.
        val w = abs(bias - 0.5f) * 2f
        if (w > 0.02f) {
            tint.color = if (bias < 0.5f) amber else cyan
            tint.alpha = (w * (70 + 90 * env)).toInt().coerceIn(0, 255)
            tint.strokeWidth = 10 * d
            canvas.drawRoundRect(card, radius, radius, tint)
        }
    }

    private fun drawEdge(canvas: Canvas) {
        edge.shader = sweep
        edge.alpha = 230
        canvas.drawRoundRect(card, radius, radius, edge)
    }

    private fun drawBars(canvas: Canvas, dt: Float) {
        if (bars.isEmpty()) return
        val left = card.left + radius * 0.6f
        val right = card.right - radius * 0.6f
        val w = right - left
        val mid = card.top + ribbonHeight * 0.55f
        val maxA = ribbonHeight * 0.38f
        val step = w / (bars.size - 1)
        val bw = 3.2f * d
        for (i in bars.indices) {
            val env1 = barEnv[i]
            val n = 0.5f + 0.5f * abs(sin(i * 1.73f + time * 7.3f) * 0.65f + sin(i * 0.61f - time * 3.4f) * 0.35f)
            val target = (maxA * cAmp * env1 * n).coerceAtLeast(1.2f * d)
            // Spring: snap up quickly, fall back smoothly.
            bars[i] += (target - bars[i]) * min(1f, dt * if (target > bars[i]) 26f else 9f)
            val h = bars[i]
            val x = left + i * step
            val a = (70 + 185 * env1).toInt()
            if (h > 3 * d) {
                barGlow.alpha = (a * 0.55f * (1f - loading)).toInt()
                canvas.drawRoundRect(x - bw, mid - h, x + bw, mid + h, bw, bw, barGlow)
            }
            bar.alpha = (a * (1f - loading)).toInt()
            canvas.drawRoundRect(x - bw / 2, mid - h, x + bw / 2, mid + h, bw, bw, bar)
        }
    }

    private fun drawSilk(canvas: Canvas) {
        val mid = card.top + ribbonHeight * 0.55f
        val maxA = ribbonHeight * 0.36f
        val widths = silkWidths
        val alphas = silkAlphas
        val wave = time * (2.4f + env * 2f)
        val slow = time * 1.3f
        for (strand in widths.indices.reversed()) {
            path.reset()
            val phase = strand * 0.85f
            val amp = maxA * aAmp * (1f - strand * 0.17f)
            for (i in silkX.indices) {
                val u = silkU[i]
                val y = mid + amp * silkEnv[i] * sin(u * 9.5f + wave + phase) *
                    (0.75f + 0.25f * sin(slow + u * 4f))
                if (i == 0) path.moveTo(silkX[i], y) else path.lineTo(silkX[i], y)
            }
            val shade = silkShade
            if (strand == 0) {
                silkGlow.shader = shade
                silkGlow.strokeWidth = (6f + 4f * env) * d
                silkGlow.alpha = ((110 + 120 * env) * (1f - loading)).toInt().coerceIn(0, 255)
                canvas.drawPath(path, silkGlow)
            }
            silk.shader = shade
            silk.strokeWidth = widths[strand] * d * (1f + 0.35f * env)
            silk.alpha = (alphas[strand] * (1f - loading)).toInt()
            canvas.drawPath(path, silk)
        }
    }
}
