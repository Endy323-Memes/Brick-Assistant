package com.brick.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Tap pass-through: while Brick's card is up, a tap or swipe outside it can't reach the app underneath, so
 * Brick catches it, steps aside and replays the same gesture through this service right away. It only
 * performs gestures Brick hands it; it doesn't read the screen or other apps.
 */
class BrickTapService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }

    /** Replays a gesture: [points] in screen pixels (one point = a tap), lasting [durationMs]. */
    fun replay(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 3000))
        return try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        @Volatile var instance: BrickTapService? = null
            private set

        val isOn: Boolean get() = instance != null
    }
}
