package com.brick.assistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * The edge-to-edge intro in a "display over other apps" window that never takes taps: Android lets touches
 * pass straight through to the app underneath (instantly) only for overlay windows at most 80% opaque, so
 * the window is drawn at that opacity. It can outlive Brick's card: after you dismiss Brick it plays out on
 * its own, and the next tap anywhere (which still goes to your app) fades it away.
 */
object IntroOverlay {
    private const val PASS_THROUGH_ALPHA = 0.8f

    private var glow: EdgeGlowView? = null
    private var watcher: View? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    val isPlaying: Boolean get() = glow?.isPlaying == true

    private fun wm(context: Context) = context.getSystemService(WindowManager::class.java)

    /** Shows and plays the intro (replaces one that's still playing). Returns false if it couldn't be shown. */
    fun play(context: Context): Boolean {
        val app = context.applicationContext
        remove(app)
        val v = EdgeGlowView(app)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = PASS_THROUGH_ALPHA
            title = "Brick intro"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        return try {
            wm(app).addView(v, lp)
            glow = v
            com.brick.assistant.optic.OpticBridge.busy(app, "intro", true)
            v.onFinished = { remove(app) }
            v.post { v.play() }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Brick was dismissed: the next tap anywhere (it still reaches your app) fades the intro away. */
    @SuppressLint("ClickableViewAccessibility")
    fun fadeOnNextTap(context: Context) {
        val app = context.applicationContext
        if (!isPlaying || watcher != null) return
        val v = View(app)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) glow?.fadeAway()
            false
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Brick tap watcher"
        }
        try {
            wm(app).addView(v, lp)
            watcher = v
        } catch (_: Exception) {}
    }

    fun fadeAway() { glow?.fadeAway() }

    /** Keeps the card clear: the intro window sits above it, so it skips drawing over [screenRect]. */
    fun setHole(screenRect: android.graphics.RectF?) { hole = screenRect; glow?.hole = screenRect }

    /** The card's area on screen while it's up (other overlays keep it clear too). */
    var hole: android.graphics.RectF? = null
        private set

    fun remove(context: Context) {
        val wm = wm(context.applicationContext)
        watcher?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        watcher = null
        glow?.let {
            it.onFinished = null
            try { wm.removeViewImmediate(it) } catch (_: Exception) {}
            com.brick.assistant.optic.OpticBridge.busy(context, "intro", false)
        }
        glow = null
    }
}
