package com.brick.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.brick.assistant.overlay.AssistEntryActivity

/**
 * Invisible trampoline. Older Brick builds had the main screen here, and Android remembers this exact
 * component as the "digital assistant" when it was picked back then (One UI keeps
 * `com.brick.assistant/.MainActivity`). So:
 *  - assist / voice-command launches go to the floating card ([AssistEntryActivity]) without bringing
 *    Brick's main screen forward;
 *  - anything else (old home-screen shortcuts) opens the main screen ([HomeActivity]).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent
        val assist = src?.action == Intent.ACTION_ASSIST || src?.action == Intent.ACTION_VOICE_COMMAND
        val next = if (assist) {
            Intent(this, AssistEntryActivity::class.java)
                .setAction(src?.action)
                .putExtra(AssistEntryActivity.EXTRA_SOURCE, "digital assistant (legacy setting)")
        } else {
            Intent(this, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
        }
        src?.extras?.let { next.putExtras(it) }
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
