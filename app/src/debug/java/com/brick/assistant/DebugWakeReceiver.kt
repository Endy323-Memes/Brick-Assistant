package com.brick.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brick.assistant.wake.WakeWordService

/**
 * Debug builds only: runs the Hey Brick listener for a test (e.g. measuring CPU use) without changing the
 * Hey Brick setting. Brick must be on screen when starting it (Android only starts microphone services then).
 *   adb shell am broadcast -n com.brick.assistant/.DebugWakeReceiver --ez on true
 */
class DebugWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("on", false)) WakeWordService.start(context) else WakeWordService.stop(context)
    }
}
