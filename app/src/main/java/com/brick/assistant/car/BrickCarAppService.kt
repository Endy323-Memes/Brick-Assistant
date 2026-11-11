package com.brick.assistant.car

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.SpeechRecognizer
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.brick.assistant.BrickApp
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.brain.Speaker
import com.brick.assistant.brain.hasPermission
import com.brick.assistant.voice.VoiceListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.concurrent.thread

/** Brick in Android Auto: a screen in the car's app launcher with a Talk button. */
class BrickCarAppService : CarAppService() {
    // Brick is a personal app that isn't on the Play Store, so any Android Auto host may connect.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = BrickCarScreen(carContext)
    }
}

/**
 * Tap **Talk**, speak, and Brick answers through the car speakers; the screen shows what you said and
 * both replies. Brick listens through the car's microphone. A question from Brick (send this text?
 * start directions?) listens again for the answer automatically. Directions go to the car's navigation app.
 */
class BrickCarScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private enum class State { IDLE, LISTENING, WORKING }

    private val container = (carContext.applicationContext as BrickApp).container
    private val listener = VoiceListener(carContext)
    private var state = State.IDLE
    private var heard = ""
    private var partial = ""
    private var brickLine = ""
    private var unitLine = ""
    private var note: String? = null
    private var job: Job? = null

    @Volatile private var recording = false
    private var carRecord: CarAudioRecord? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        container.actions.inCar = true
        container.assistant.carNavigator = { uri -> navigate(uri) }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopCarMic()
        listener.destroy()
        job?.cancel()
        container.actions.inCar = false
        container.assistant.carNavigator = null
    }

    override fun onGetTemplate(): Template {
        val message = when (state) {
            State.LISTENING -> partial.ifBlank { "Listening…" }
            State.WORKING -> listOf(heard.takeIf { it.isNotBlank() }?.let { "“$it”" }, brickLine.ifBlank { "Working…" }, unitLine.takeIf { it.isNotBlank() })
                .filterNotNull().joinToString("\n")
            State.IDLE -> note ?: if (heard.isBlank()) {
                "Tap Talk and ask Brick anything: directions, music, texts, timers, weather."
            } else {
                listOf("“$heard”", brickLine.takeIf { it.isNotBlank() }, unitLine.takeIf { it.isNotBlank() }).filterNotNull().joinToString("\n")
            }
        }
        val b = MessageTemplate.Builder(message)
            .setTitle(
                when (state) {
                    State.LISTENING -> "Brick · Listening"
                    State.WORKING -> "Brick · Working"
                    State.IDLE -> "Brick"
                },
            )
            .setHeaderAction(Action.APP_ICON)
        when (state) {
            State.LISTENING -> b.addAction(action("Done") { listener.stop(); stopCarMic() })
            State.WORKING -> b.addAction(action("Stop") { stopAll() })
            State.IDLE -> b.addAction(action("Talk", primary = true) { startListening() })
        }
        return b.build()
    }

    private fun action(title: String, primary: Boolean = false, onClick: () -> Unit): Action {
        val a = Action.Builder().setTitle(title).setOnClickListener(onClick)
        if (primary) {
            a.setBackgroundColor(CarColor.BLUE)
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) a.setFlags(Action.FLAG_PRIMARY)
        }
        return a.build()
    }

    // ------------------------------------------------------------------ listening

    private fun startListening() {
        note = null
        if (!carContext.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            note = "Open Brick on your phone and allow the microphone first."
            invalidate()
            return
        }
        if (!listener.isAvailable()) {
            note = "Speech recognition isn't available on this phone."
            invalidate()
            return
        }
        stopCarMic()
        state = State.LISTENING
        partial = ""
        invalidate()
        val audio = openCarMic()
        listener.start(object : VoiceListener.Callbacks {
            override fun onPartial(text: String) { partial = text; invalidate() }
            override fun onResult(text: String?) {
                stopCarMic()
                if (text.isNullOrBlank()) idle("I didn't catch that. Tap Talk to try again.") else handle(text)
            }
            override fun onError(code: Int) {
                stopCarMic()
                idle(
                    when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap Talk to try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
                        SpeechRecognizer.ERROR_CLIENT -> null
                        else -> "Speech recognition error ($code)."
                    },
                )
            }
        }, audio)
    }

    /**
     * Records from the car's microphone (Car App API 5+, Android 13+) and streams it to the recognizer
     * through a pipe. Returns null to use the phone's microphone instead.
     */
    private fun openCarMic(): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT < 33 || carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) return null
        return try {
            val rec = CarAudioRecord.create(carContext)
            val (read, write) = ParcelFileDescriptor.createPipe()
            rec.startRecording()
            carRecord = rec
            recording = true
            thread(name = "brick-car-mic") {
                val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
                val started = System.currentTimeMillis()
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                        while (recording && System.currentTimeMillis() - started < 15_000) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                } catch (_: IOException) {
                    // The recognizer closed its end (it heard enough).
                } catch (_: Exception) {}
                try { rec.stopRecording() } catch (_: Exception) {}
            }
            read
        } catch (e: Exception) {
            null
        }
    }

    private fun stopCarMic() {
        recording = false
        carRecord?.let { try { it.stopRecording() } catch (_: Exception) {} }
        carRecord = null
    }

    /** Note dictation through the car mic: everything said until 5 seconds of silence. */
    private fun startDictation() {
        state = State.LISTENING
        partial = ""
        invalidate()
        listener.dictate(object : VoiceListener.Callbacks {
            override fun onPartial(text: String) { partial = text; invalidate() }
            override fun onResult(text: String?) { stopCarMic(); handle(text.orEmpty()) }
            override fun onError(code: Int) { stopCarMic(); idle("Microphone permission is needed.") }
        }, audio = { stopCarMic(); openCarMic() })
    }

    // ------------------------------------------------------------------ answering

    private fun handle(text: String) {
        heard = text.ifBlank { "(nothing)" }
        brickLine = ""
        unitLine = ""
        state = State.WORKING
        invalidate()
        if (text.isNotBlank()) container.log.add(LogEntry(Speaker.USER, text))
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                container.assistant.handle(text) { e ->
                    container.log.add(e)
                    when (e.speaker) {
                        Speaker.DAVID -> brickLine = e.text
                        Speaker.ROBOT -> unitLine = e.text
                        else -> {}
                    }
                    invalidate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unitLine = "Something went wrong."
            }
            // Brick asked a yes-or-no question: listen for the answer.
            when {
                container.assistant.awaitingConfirmation -> startListening()
                container.assistant.awaitingDictation -> startDictation()
                else -> idle(null)
            }
        }
    }

    private fun idle(message: String?) {
        state = State.IDLE
        note = message
        invalidate()
    }

    private fun stopAll() {
        job?.cancel()
        listener.cancel()
        stopCarMic()
        idle(null)
    }

    private fun navigate(uri: String) {
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)))
        } catch (e: Exception) {
            note = "I couldn't start the car's navigation app."
            invalidate()
        }
    }
}
