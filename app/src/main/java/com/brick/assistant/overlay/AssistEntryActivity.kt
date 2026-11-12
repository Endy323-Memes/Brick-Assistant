package com.brick.assistant.overlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.brick.assistant.BrickApp
import com.brick.assistant.HomeActivity
import com.brick.assistant.brain.hasPermission

/**
 * The floating assistant. A transparent screen over the current app that shows only Brick's card,
 * so the app behind stays visible and Brick itself never opens. It's used by every trigger: the
 * digital-assistant gesture or side key (directly or through [com.brick.assistant.assist.BrickSession]),
 * the voice command button and the Ask Brick tile. A visible screen may use the microphone, so no
 * overlay permission or foreground service is needed.
 */
class AssistEntryActivity : Activity() {
    private var controller: AssistController? = null
    /** The floating card in Brick's terminal look (the edge-to-edge intro is unchanged). */
    private lateinit var card: TermCard
    /** The readout card (when shown) above Brick's card. */
    private lateinit var stack: android.widget.LinearLayout
    private lateinit var edgeGlow: EdgeGlowView
    private var started = false
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BrickApp).container
        recordTrigger(this, intent)

        if (!container.settings.current.useOverlay) {
            startActivity(Intent(this, HomeActivity::class.java).setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        // Optic's edge visualizer steps aside while the card is up.
        com.brick.assistant.optic.OpticBridge.busy(this, "card", true)
        // "Hey Brick" with the screen off or locked: wake the screen and show the card over the lock screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // Anything that opens another app (Maps, the dialer, Messages...) waits until you unlock.
        container.assistant.handOffGate = { run -> runUnlocked(run) }
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Draw edge to edge with see-through system bars (no black bars above and below the app behind).
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        card = TermCard(this)
        // Readouts (the night sky panel) get their own card stacked above Brick's, which keeps its size.
        val panelCard = PanelCard(this)
        card.onPanel = { r -> panelCard.show(r) }
        panelCard.onInteract = { controller?.keepOpen() }
        stack = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(panelCard, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
            addView(card, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(
            stack,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply {
                    bottomMargin = (14 * d).toInt()
                    leftMargin = (14 * d).toInt()
                    rightMargin = (14 * d).toInt()
                },
        )
        card.onStartTyping = { controller?.startTyping() }
        card.onStartTalking = { if (hasPermission(Manifest.permission.RECORD_AUDIO)) controller?.startTalking() }
        card.onTyped = { t -> controller?.submitTyped(t) }
        card.setOnClickListener {
            val c = controller
            if (card.typing) return@setOnClickListener
            if (c != null && c.listening) {
                c.stopListening()
            } else {
                startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                close()
            }
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Above the navigation bar, and above the keyboard while typing.
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                maxOf(insets.getInsets(WindowInsets.Type.navigationBars()).bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        // The assistant window is only as big as the card, so taps anywhere else go straight to the app
        // underneath, instantly. Brick is told about those taps (outside touches) and dismisses itself.
        setContentView(root)
        replayTaps = BrickTapService.isOn
        if (replayTaps) {
            // Tap pass-through is on: catch a tap or swipe outside the card, step aside, and replay it into
            // the app underneath right away (Android won't hand it to that app on its own).
            root.setOnTouchListener { _, e -> catchOutsideGesture(e); true }
        } else {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        }
        // The edge-to-edge intro draws in its own full-screen window behind the card that ignores touches.
        // Unlocked with "Display over other apps" allowed: the intro is an overlay that taps pass straight
        // through (instantly). On the lock screen it's a window of this screen instead.
        edgeGlow = EdgeGlowView(this)
        useOverlayIntro = IntroOverlay.canShow(this) &&
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true
        val playIntro = !intent.getBooleanExtra(EXTRA_RETURN, false)
        if (useOverlayIntro) {
            if (playIntro && !IntroOverlay.play(this)) useOverlayIntro = false
            // Leave the card clear of the intro (its window is above the card's).
            // The hole covers both cards (the readout card sits above Brick's).
            stack.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!closing) IntroOverlay.setHole(cardRect(v)) }
        }
        if (!useOverlayIntro) {
            window.decorView.post {
                addGlowWindow()
                if (playIntro) edgeGlow.post { edgeGlow.play() }
            }
        }
        card.animateIn()
        controller = AssistController(container, card) { close() }
        // Start listening right away (not after the screen finishes opening) so the start of your command isn't lost.
        started = true
        begin()
    }

    private var replayTaps = false
    private val gesture = ArrayList<Pair<Float, Float>>()
    private var gestureStart = 0L

    private fun catchOutsideGesture(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gesture.clear()
                gesture += e.rawX to e.rawY
                gestureStart = e.eventTime
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (gesture.size < 60) gesture += e.rawX to e.rawY else gesture[gesture.lastIndex] = e.rawX to e.rawY
            }
            android.view.MotionEvent.ACTION_UP -> {
                gesture += e.rawX to e.rawY
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                val (x0, y0) = gesture.first()
                val moved = gesture.any { (x, y) -> kotlin.math.hypot(x - x0, y - y0) > slop }
                val held = e.eventTime - gestureStart
                passThrough(if (moved) gesture.toList() else listOf(x0 to y0), if (moved) held.coerceAtLeast(60) else held.coerceIn(30, 600))
            }
        }
    }

    /** Hides Brick at once and replays the gesture into the app underneath (about 40 ms later). */
    private fun passThrough(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (closing) return
        val service = BrickTapService.instance
        if (service == null || !useOverlayIntro) { close(); return }
        closing = true
        controller?.cancel()
        // An invisible, untouchable window doesn't stand in the way of the replayed touch.
        window.attributes = window.attributes.apply { alpha = 0f }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        IntroOverlay.setHole(null)
        val app = applicationContext
        window.decorView.postDelayed({
            service.replay(points, durationMs)
            finishQuietly()
            // Only your next tap fades the intro, not the one being replayed.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ IntroOverlay.fadeOnNextTap(app) }, durationMs + 150)
        }, 40)
    }

    /** The card's final on-screen rectangle (ignores the slide-in animation). */
    private fun cardRect(v: View): android.graphics.RectF {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val top = loc[1] - v.translationY
        return android.graphics.RectF(loc[0].toFloat(), top, (loc[0] + v.width).toFloat(), top + v.height)
    }

    private var glowAdded = false
    private var useOverlayIntro = false

    /** Full-screen, touch-through window for the intro, layered just behind the card's window. */
    private fun addGlowWindow() {
        if (glowAdded || isFinishing) return
        val token = window.decorView.windowToken ?: return
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            this.token = token
            title = "Brick intro"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        try {
            windowManager.addView(edgeGlow, lp)
            glowAdded = true
        } catch (_: Exception) {}
    }

    private fun removeGlowWindow() {
        if (!glowAdded) return
        glowAdded = false
        try { windowManager.removeViewImmediate(edgeGlow) } catch (_: Exception) {}
    }

    /** A tap outside the card: it already went to the app underneath; Brick just steps aside. */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
            // Typing on the keyboard counts as "outside" the card's window: keep the card.
            if (::card.isInitialized && card.typing && keyboardShowing()) return true
            if (closing) {
                IntroOverlay.fadeAway()
                if (::edgeGlow.isInitialized) edgeGlow.fadeAway()
            } else {
                close()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun keyboardShowing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    override fun onResume() {
        super.onResume()
        if (started || closing) return
        started = true
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::edgeGlow.isInitialized && !closing) {
            if (useOverlayIntro) IntroOverlay.play(this) else edgeGlow.play()
        }
        recordTrigger(this, intent)
        setIntent(intent)
        if (!closing) begin()
    }

    private fun begin() {
        val c = controller ?: return
        // Battery's one-purpose gestures: the night sky / photo spots answer right away; the searches listen for
        // just the song, video or place.
        val topic = com.brick.assistant.brain.Topic.of(intent?.getStringExtra(EXTRA_TOPIC))
        intent?.removeExtra(EXTRA_TOPIC) // once: a later onResume or new intent doesn't repeat it
        c.topic = topic
        if (topic?.instant != null) {
            c.runInstant(topic)
            return
        }
        // Battery's "Brick card" action: open ready to type (no microphone needed).
        if (intent?.getStringExtra(EXTRA_MODE) == "type") {
            if (!card.typing) card.startTyping()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            card.setState(PulseView.Mode.MESSAGE, "Open Brick and allow the microphone to talk to me.", "Tap here to open Brick")
            return
        }
        c.start()
    }

    /** Leaving (home, recents, another app opening) ends the request. */
    override fun onStop() {
        super.onStop()
        val assistant = (application as BrickApp).container.assistant
        // Brick opened another app (a photo spot's route) and asked a question: come back over it to listen.
        val comeBack = !closing && assistant.awaitingConfirmation && assistant.returnToCardUntil > System.currentTimeMillis()
        if (unlocking) return // the unlock prompt is showing; the hand-off follows
        IntroOverlay.setHole(null)
        if (!isFinishing) {
            controller?.cancel()
            finish()
        }
        if (comeBack) {
            val app = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    app.startActivity(
                        Intent(app, AssistEntryActivity::class.java)
                            .putExtra(EXTRA_SOURCE, "answer")
                            .putExtra(EXTRA_RETURN, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {}
            }, 900)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    private fun close() {
        if (closing) return
        closing = true
        controller?.cancel()
        // The readout card leaves with Brick's.
        (stack.getChildAt(0) as? PanelCard)?.animate()?.alpha(0f)?.setDuration(200)?.start()
        card.animateOut {
            if (useOverlayIntro) {
                // The intro plays out on its own above everything; the next tap fades it. Brick can go now.
                IntroOverlay.setHole(null)
                IntroOverlay.fadeOnNextTap(this)
                finishQuietly()
            } else if (::edgeGlow.isInitialized && edgeGlow.isPlaying) {
                // Let the intro play out; meanwhile taps go straight to the app underneath, and the next tap
                // anywhere also fades the intro away.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                watchNextTap { edgeGlow.fadeAway() }
                edgeGlow.onFinished = { removeTapWatcher(); finishQuietly() }
            } else {
                finishQuietly()
            }
        }
    }

    private var tapWatcher: View? = null

    /**
     * Notices the next tap anywhere on screen without taking it: a 1-pixel window that asks Android for
     * "outside touch" notices. The tap itself still goes to whatever app is underneath.
     */
    private fun watchNextTap(onTap: () -> Unit) {
        if (tapWatcher != null) return
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) onTap()
            false
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            token = window.decorView.windowToken
            title = "Brick tap watcher"
        }
        try {
            windowManager.addView(v, lp)
            tapWatcher = v
        } catch (_: Exception) {}
    }

    private fun removeTapWatcher() {
        tapWatcher?.let { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        tapWatcher = null
    }

    private fun finishQuietly() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun runUnlocked(run: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { run(); return }
        // Keep the card while the unlock prompt shows, then hand off.
        unlocking = true
        km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { unlocking = false; run() }
            override fun onDismissCancelled() { unlocking = false; close() }
            override fun onDismissError() { unlocking = false; close() }
        })
    }

    private var unlocking = false

    override fun onDestroy() {
        // The card is gone: nothing on screen needs keeping clear any more (another app may be opening).
        IntroOverlay.setHole(null)
        removeTapWatcher()
        removeGlowWindow()
        com.brick.assistant.optic.OpticBridge.busy(this, "card", false)
        val assistant = (application as BrickApp).container.assistant
        if (assistant.handOffGate != null && !isChangingConfigurations) assistant.handOffGate = null
        controller?.destroy()
        controller = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "brick_source"
        /** Coming back over another app to hear the answer to Brick's question. */
        const val EXTRA_RETURN = "brick_return"
        /** "type" opens the card ready to type; anything else (or nothing) starts listening. */
        const val EXTRA_MODE = "brick_mode"
        /** A [com.brick.assistant.brain.Topic] id ("spotify", "night_sky"...): a one-purpose request from Battery. */
        const val EXTRA_TOPIC = "brick_topic"

        /** Remembers how the assistant was last triggered (shown in Settings to help troubleshooting). */
        fun recordTrigger(context: Context, intent: Intent?) {
            val src = intent?.getStringExtra(EXTRA_SOURCE) ?: when (intent?.action) {
                Intent.ACTION_ASSIST -> "assist intent"
                Intent.ACTION_VOICE_COMMAND -> "voice command"
                else -> "other"
            }
            context.getSharedPreferences("assist_diag", MODE_PRIVATE).edit()
                .putString("last", src)
                .putLong("at", System.currentTimeMillis())
                .apply()
        }

        fun lastTrigger(context: Context): String? {
            val p = context.getSharedPreferences("assist_diag", MODE_PRIVATE)
            val src = p.getString("last", null) ?: return null
            val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(p.getLong("at", 0)))
            return "$src ($at)"
        }
    }
}
