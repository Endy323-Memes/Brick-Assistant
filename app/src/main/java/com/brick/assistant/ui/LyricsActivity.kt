package com.brick.assistant.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.brick.assistant.brain.LyricsHolder
import com.brick.assistant.brain.LyricsPanel
import com.brick.assistant.brain.LyricsSync
import com.brick.assistant.overlay.LyricsView
import com.brick.assistant.overlay.ViewColors

/**
 * The lyrics from Brick's card, full screen: the same terminal lyric view (bigger), the song's place with a
 * seek bar, and previous / play-pause / next. Keeps the screen on while the song plays.
 */
class LyricsActivity : Activity() {
    private lateinit var panel: LyricsPanel
    private lateinit var lyrics: LyricsView
    private lateinit var playButton: ControlButton
    private lateinit var timeLeft: TextView
    private lateinit var timeRight: TextView
    private lateinit var seek: SeekBar
    private var dragging = false
    private val d by lazy { resources.displayMetrics.density }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            lyrics.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the song playing now gets its lyrics full screen.
        panel = LyricsHolder.current?.takeIf { it.sameSong() } ?: run { finish(); return }
        window.statusBarColor = ViewColors.background
        window.navigationBarColor = ViewColors.background

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ViewColors.background)
            val p = (16 * d).toInt()
            setPadding(p, (12 * d).toInt(), p, (12 * d).toInt())
            // Drawn edge to edge: keep the header off the status bar and the buttons above the navigation bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(p + bars.left, (12 * d).toInt() + bars.top, p + bars.right, (12 * d).toInt() + bars.bottom)
                insets
            }
        }
        val doc = panel.doc
        // Title, with Close at the top right.
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(mono(doc.title, 18f, ViewColors.brick, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ControlButton(this, ControlButton.Kind.CLOSE, "Close") { finish() }, LinearLayout.LayoutParams((64 * d).toInt(), (52 * d).toInt()))
        root.addView(head)
        root.addView(mono(listOfNotNull(doc.artist, panel.app?.let { "on $it" }).joinToString("  "), 12.5f, ViewColors.text))
        val kind = when (doc.sync) { LyricsSync.WORD -> "word synced"; LyricsSync.LINE -> "line synced"; LyricsSync.PLAIN -> "plain text" }
        root.addView(mono("$kind · from ${doc.source.label}" + (doc.matchedBy?.let { " · matched by $it" } ?: ""), 10.5f, ViewColors.unit).apply { setPadding(0, (2 * d).toInt(), 0, (10 * d).toInt()) })
        root.addView(View(this).apply { setBackgroundColor(ViewColors.alpha(ViewColors.brick, 120)) }, LinearLayout.LayoutParams(-1, (1 * d).toInt().coerceAtLeast(1)))

        lyrics = LyricsView(this).apply { fullScreen = true; bind(panel) }
        root.addView(lyrics, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt() })

        // Where the song is: 01:23 [========------] 04:56
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeLeft = mono("--:--", 11f, ViewColors.text)
        timeRight = mono("--:--", 11f, ViewColors.dim)
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(ViewColors.brick)
            thumbTintList = android.content.res.ColorStateList.valueOf(ViewColors.brick)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ViewColors.dim)
            isEnabled = panel.seek != null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) panel.duration()?.let { timeLeft.text = clock(it * p / 1000) }
                }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    dragging = false
                    panel.duration()?.let { dur -> panel.seek?.invoke(dur * s.progress / 1000) }
                }
            })
        }
        times.addView(timeLeft)
        times.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(timeRight)
        root.addView(times)

        // Play / Pause: a drawn icon with its name under it. No skipping: these lyrics are for this one song.
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        playButton = ControlButton(this, ControlButton.Kind.PAUSE, "Pause") { panel.toggle?.invoke(); lyrics.postDelayed({ refresh() }, 150) }.apply { isEnabled = panel.toggle != null }
        buttons.addView(playButton, LinearLayout.LayoutParams((150 * d).toInt(), (76 * d).toInt()))
        root.addView(buttons)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!::lyrics.isInitialized) return
        lyrics.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (::lyrics.isInitialized) lyrics.removeCallbacks(ticker)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var checkedSongAt = 0L

    private fun refresh() {
        // The song changed (skipped, or another app started): these lyrics no longer fit, so close.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - checkedSongAt >= 1000) {
            checkedSongAt = now
            if (!panel.sameSong()) { finish(); return }
        }
        val playing = panel.playing()
        if (playing) playButton.set(ControlButton.Kind.PAUSE, "Pause") else playButton.set(ControlButton.Kind.PLAY, "Play")
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pos = panel.position()
        val dur = panel.duration()
        if (!dragging) {
            timeLeft.text = pos?.let(::clock) ?: "--:--"
            if (pos != null && dur != null && dur > 0) seek.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
        }
        timeRight.text = dur?.let(::clock) ?: "--:--"
        lyrics.invalidate()
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun mono(text: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setTextColor(color)
    }

}

/**
 * A button in the terminal look: an outlined box with a plain drawn icon (the usual play, pause and close
 * shapes) and its name in small type under it, so what it does is clear at a glance.
 */
class ControlButton(context: android.content.Context, private var kind: Kind, private var label: String, onTap: () -> Unit) : View(context) {
    enum class Kind { PLAY, PAUSE, CLOSE }

    private val d = resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val icon = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }
    private val words = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
    }
    private val path = android.graphics.Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onTap() }
    }

    fun set(k: Kind, l: String) {
        if (k == kind && l == label) return
        kind = k; label = l; contentDescription = l
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); invalidate() }
    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val brick = ViewColors.brick
        val on = isEnabled
        val main = kind == Kind.PLAY || kind == Kind.PAUSE
        val r = android.graphics.RectF(d, d, width - d, height - d)
        fill.color = ViewColors.alpha(brick, if (isPressed) 70 else if (main) 40 else 18)
        canvas.drawRoundRect(r, 5 * d, 5 * d, fill)
        stroke.strokeWidth = (if (main) 1.6f else 1.1f) * d
        stroke.color = ViewColors.alpha(brick, if (on) 170 else 60)
        canvas.drawRoundRect(r, 5 * d, 5 * d, stroke)

        val color = if (on) brick else ViewColors.alpha(brick, 80)
        icon.color = color
        words.color = if (on) ViewColors.text else ViewColors.dim
        val labelH = words.fontSpacing
        // Icon in the space above the label.
        val s = (if (main) 13f else 10f) * d
        val cx = width / 2f
        val cy = (height - labelH) / 2f + 2 * d
        path.reset()
        icon.style = android.graphics.Paint.Style.FILL
        when (kind) {
            Kind.PLAY -> { path.moveTo(cx - s * 0.7f, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx - s * 0.7f, cy + s); path.close(); canvas.drawPath(path, icon) }
            Kind.PAUSE -> {
                val w = s * 0.55f
                canvas.drawRoundRect(cx - s * 0.85f, cy - s, cx - s * 0.85f + w, cy + s, d, d, icon)
                canvas.drawRoundRect(cx + s * 0.85f - w, cy - s, cx + s * 0.85f, cy + s, d, d, icon)
            }
            Kind.CLOSE -> {
                icon.style = android.graphics.Paint.Style.STROKE
                icon.strokeWidth = 2.2f * d
                val c = s * 0.75f
                canvas.drawLine(cx - c, cy - c, cx + c, cy + c, icon)
                canvas.drawLine(cx + c, cy - c, cx - c, cy + c, icon)
            }
        }
        canvas.drawText(label, cx, height - 7 * d - words.descent(), words)
    }
}
