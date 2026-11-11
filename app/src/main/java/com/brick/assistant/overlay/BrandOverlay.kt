package com.brick.assistant.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Shows [BrandGlowView] in a "display over other apps" window that taps pass straight through (at most 80%
 * opaque, like the intro), so it can play over Brick's card and over the app Brick opens.
 */
object BrandOverlay {
    private var view: BrandGlowView? = null
    private val main = Handler(Looper.getMainLooper())

    /** Plays the bottom animation in [brand]'s colors (from any thread). */
    fun play(context: Context, brand: Brand) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        main.post { show(app, brand) }
    }

    private fun show(app: Context, brand: Brand) {
        val wm = app.getSystemService(WindowManager::class.java)
        remove(app)
        val v = BrandGlowView(app, brand)
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
            alpha = 0.8f
            title = "Brick app glow"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        try {
            wm.addView(v, lp)
            view = v
            com.brick.assistant.optic.OpticBridge.busy(app, "brand", true)
            v.onFinished = { remove(app) }
            v.post { v.play() }
        } catch (e: Exception) { android.util.Log.w("BrickBrand", "show failed", e) }
    }

    fun remove(context: Context) {
        val wm = context.applicationContext.getSystemService(WindowManager::class.java)
        view?.let {
            it.onFinished = null
            try { wm.removeViewImmediate(it) } catch (_: Exception) {}
            com.brick.assistant.optic.OpticBridge.busy(context, "brand", false)
        }
        view = null
    }
}
