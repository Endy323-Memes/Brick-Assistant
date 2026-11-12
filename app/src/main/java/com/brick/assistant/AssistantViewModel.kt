package com.brick.assistant

import android.app.Application
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.brain.Speaker
import com.brick.assistant.brain.hasPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.brick.assistant.voice.VoiceListener
import com.brick.assistant.wake.WakeWordGate

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as BrickApp).container
    val responses = container.responses
    val settings = container.settings
    val voice = container.voice
    val tts = container.tts

    val log = container.log
    var busy by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var micLevel by mutableFloatStateOf(0f)
        private set
    /** Set when launched as the device's assist app; the UI starts listening once permitted. */
    var autoListenRequested by mutableStateOf(false)
    /** A tab to switch to (from a readout's settings button), or null. */
    var requestedTab by mutableStateOf<Int?>(null)

    private var job: Job? = null
    private val listener = VoiceListener(app)
    private var holdingWakeWord = false

    /** The Hey Brick listener steps aside while Brick listens and answers in the app. */
    private fun holdWakeWord() { if (!holdingWakeWord) { holdingWakeWord = true; WakeWordGate.hold() } }
    private fun releaseWakeWord() { if (holdingWakeWord) { holdingWakeWord = false; WakeWordGate.release() } }

    fun submit(text: String) = submit(text, dictation = false)

    private fun submit(text: String, dictation: Boolean) {
        val t = text.trim()
        // An empty dictation still finishes the note ("I didn't hear anything").
        if (t.isEmpty() && !(dictation && container.assistant.awaitingDictation)) return
        job?.cancel()
        if (t.isNotEmpty()) log.add(LogEntry(Speaker.USER, t))
        job = viewModelScope.launch {
            busy = true
            try {
                container.assistant.handle(t) { log.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(LogEntry(Speaker.SYSTEM, "Error: ${e.message}"))
            } finally {
                busy = false
                releaseWakeWord()
            }
            // Brick asked a yes-or-no question: listen for the answer (typing it works too).
            val mic = getApplication<Application>().hasPermission(android.Manifest.permission.RECORD_AUDIO)
            if (container.assistant.awaitingConfirmation && mic) startListening()
            // Taking a note: dictate until 5 seconds of silence (or type it).
            if (container.assistant.awaitingDictation && mic) startDictation()
        }
    }

    /** Plays David and/or the robot without running a request (previews and tests). */
    fun preview(davidLine: String?, robotLine: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            try {
                container.assistant.preview(davidLine, robotLine)
            } finally {
                busy = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        busy = false
        releaseWakeWord()
    }

    fun clearLog() = log.clear()

    fun startListening() {
        if (!listener.isAvailable()) {
            log.add(LogEntry(Speaker.SYSTEM, "Speech recognition is not available on this device. Install or enable the Google app, or type your request."))
            return
        }
        stop()
        holdWakeWord()
        listening = true
        partial = ""
        job = viewModelScope.launch {
            listener.start(object : VoiceListener.Callbacks {
                override fun onPartial(text: String) { partial = text }
                override fun onLevel(level: Float) { micLevel = level }
                override fun onResult(text: String?) {
                    listening = false
                    micLevel = 0f
                    partial = ""
                    if (!text.isNullOrBlank()) submit(text) else releaseWakeWord()
                }
                override fun onError(code: Int) {
                    listening = false
                    micLevel = 0f
                    partial = ""
                    releaseWakeWord()
                    val msg = when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not catch that."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
                        SpeechRecognizer.ERROR_CLIENT -> null
                        else -> "Speech recognition error ($code)."
                    }
                    msg?.let { log.add(LogEntry(Speaker.SYSTEM, it)) }
                }
            })
        }
    }

    /** Note dictation: everything you say until 5 seconds of silence becomes the note. */
    fun startDictation() {
        if (!listener.isAvailable()) return
        stop()
        holdWakeWord()
        listening = true
        partial = ""
        listener.dictate(object : VoiceListener.Callbacks {
            override fun onPartial(text: String) { partial = text }
            override fun onLevel(level: Float) { micLevel = level }
            override fun onResult(text: String?) {
                listening = false
                micLevel = 0f
                partial = ""
                submit(text.orEmpty(), dictation = true)
            }
            override fun onError(code: Int) {
                listening = false
                micLevel = 0f
                partial = ""
                releaseWakeWord()
                log.add(LogEntry(Speaker.SYSTEM, "Microphone permission is needed."))
            }
        })
    }

    fun stopListening() {
        if (job?.isActive == true) job?.cancel() // still playing the listening sound
        listener.stop()
        listening = false
    }

    override fun onCleared() {
        releaseWakeWord()
        listener.destroy()
        super.onCleared()
    }
}