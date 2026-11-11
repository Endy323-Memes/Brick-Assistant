package com.brick.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.brain.Speaker
import com.brick.assistant.ui.BrickRoot
import com.brick.assistant.ui.BrickTheme

/** Brick's main screen (the launcher entry). */
class HomeActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fully transparent system bars, no contrast backdrop behind the 3-button navigation.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleAssist(intent)
        setContent {
            BrickTheme {
                BrickRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Microphone services may only be started while Brick is on screen, so (re)start Hey Brick here.
        if (vm.settings.current.wakeWordEnabled) com.brick.assistant.wake.WakeWordService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssist(intent)
    }

    private fun handleAssist(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) {
            vm.autoListenRequested = true
            if (intent.getBooleanExtra(EXTRA_OVERLAY_MISSING, false)) {
                vm.log.add(
                    LogEntry(
                        Speaker.SYSTEM,
                        "To answer over other apps, allow \"Display over other apps\" for Brick in Settings > Assistant.",
                    ),
                )
            }
        }
    }

    companion object {
        const val EXTRA_OVERLAY_MISSING = "overlay_missing"
    }
}
