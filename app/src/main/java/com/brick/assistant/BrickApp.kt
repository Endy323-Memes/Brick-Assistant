package com.brick.assistant

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.brick.assistant.brain.Actions
import com.brick.assistant.brain.Assistant
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.data.ResponseStore
import com.brick.assistant.data.SettingsStore
import com.brick.assistant.reminders.ReminderScheduler
import com.brick.assistant.voice.AndroidTts
import com.brick.assistant.voice.AudioPlayer
import com.brick.assistant.voice.BeepMuter
import com.brick.assistant.voice.VoiceEngine

class BrickApp : Application() {
    lateinit var container: AppContainer
        private set
    /** False only in the moment between Android starting Brick's providers and onCreate finishing. */
    val ready: Boolean get() = ::container.isInitialized

    override fun onCreate() {
        super.onCreate()
        BeepMuter.restore(this) // in case the app was killed while listening
        com.brick.assistant.voice.OfflineSpeech.refresh(this) // so the first request already uses on-device speech
        com.brick.assistant.data.Palettes.init(this)
        com.brick.assistant.data.Places.init(this)
        com.brick.assistant.overlay.PanelCard.fitWidth(this) // readouts as wide as the card
        container = AppContainer(this)
        ReminderScheduler.createChannel(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.brick.assistant.overlay.PanelCard.fitWidth(this) // rotated, or the display size changed
    }
}

/** Simple manual dependency container shared by the whole app. */
class AppContainer(val context: Context) {
    val settings = SettingsStore(context)
    val responses = ResponseStore(context)
    val tts = AndroidTts(context)
    val player = AudioPlayer(context)
    val voice = VoiceEngine(context, settings, tts, player)
    val actions = Actions(context, settings)
    val assistant = Assistant(responses, voice, actions).apply {
        onBrand = { b -> if (settings.current.brandGlow && !actions.inCar) com.brick.assistant.overlay.BrandOverlay.play(context, b) }
        // Quiet When: dark (or daylight, by the setting) and no headphones means text only. Never in the car.
        textOnly = {
            val s = settings.current
            !actions.inCar && com.brick.assistant.voice.Outdoors.quiet(context, s.quietDark, s.quietDay, s.outdoorLux)
        }
    }
    /** Conversation history shared by the app screen and the floating overlay. */
    val log = mutableStateListOf<LogEntry>()
}
