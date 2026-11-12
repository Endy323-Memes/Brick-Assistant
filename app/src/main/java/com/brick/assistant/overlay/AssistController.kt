package com.brick.assistant.overlay

import android.speech.SpeechRecognizer
import com.brick.assistant.AppContainer
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.brain.Speaker
import com.brick.assistant.voice.VoiceListener
import com.brick.assistant.wake.WakeWordGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Something that can show the assistant's progress (the floating card, the assist session). */
interface AssistUi {
    fun setState(mode: PulseView.Mode, mainText: String, subText: String? = null)
    fun setPartial(text: String)
    fun setMicLevel(level: Float)
    /** Brick's own playback level, so the card can follow the voice that's speaking. */
    fun setOutputLevel(source: () -> Float) {}
    /** A terminal readout under the answer (the night sky panel), or null to clear it. */
    fun setPanel(readout: com.brick.assistant.brain.Readout?) {}
    /** Answering in text only (Quiet When): the card says so. */
    fun setQuiet(on: Boolean) {}
}

/**
 * One hands-free request: recognition -> David + robot replies.
 * [onDone] is called when the UI should go away.
 */
class AssistController(
    private val container: AppContainer,
    private val ui: AssistUi,
    private val onDone: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = VoiceListener(container.context)

    init {
        // The card follows Brick's real audio while David or the robot speaks.
        ui.setOutputLevel { container.player.outputLevel }
    }
    private var job: Job? = null
    var listening = false
        private set
    private var holdingWakeWord = false

    private fun holdWakeWord() { if (!holdingWakeWord) { holdingWakeWord = true; WakeWordGate.hold() } }
    private fun releaseWakeWord() { if (holdingWakeWord) { holdingWakeWord = false; WakeWordGate.release() } }

    /**
     * Battery's one-purpose gestures (Spotify search, directions...): the next request is read for this topic,
     * then it's cleared (follow-up answers are ordinary requests).
     */
    var topic: com.brick.assistant.brain.Topic? = null

    /** Runs a topic that needs nothing said (the night sky report, photo spots near you). */
    fun runInstant(t: com.brick.assistant.brain.Topic) {
        val text = t.instant ?: return
        topic = t
        holdWakeWord()
        handle(text)
    }

    fun start() {
        job?.cancel()
        listener.cancel()
        holdWakeWord() // the Hey Brick listener steps aside until this request is finished
        val t = topic
        ui.setState(PulseView.Mode.LISTENING, if (t != null) "${t.prompt}: ${t.hint}…" else "Go ahead…")
        job = scope.launch {
            listening = true
            if (!listener.isAvailable()) {
                message("Speech recognition isn't available on this device.")
                return@launch
            }
            listener.start(object : VoiceListener.Callbacks {
                override fun onPartial(text: String) = ui.setPartial(text)
                override fun onLevel(level: Float) = ui.setMicLevel(level)
                override fun onResult(text: String?) {
                    listening = false
                    ui.setMicLevel(0f)
                    if (text.isNullOrBlank()) message("I didn't catch that.") else handle(text)
                }
                override fun onError(code: Int) {
                    listening = false
                    ui.setMicLevel(0f)
                    if (code == SpeechRecognizer.ERROR_CLIENT) return
                    message(
                        when (code) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed. Open Brick to grant it."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
                            else -> "Speech recognition error ($code)."
                        },
                    )
                }
            })
        }
    }

    /** Note dictation: everything said until 5 seconds of silence becomes the note. */
    private fun dictate() {
        job?.cancel()
        listener.cancel()
        holdWakeWord()
        ui.setState(PulseView.Mode.LISTENING, "Go ahead, I'm writing…")
        listening = true
        listener.dictate(object : VoiceListener.Callbacks {
            override fun onPartial(text: String) = ui.setPartial(text)
            override fun onLevel(level: Float) = ui.setMicLevel(level)
            override fun onResult(text: String?) {
                listening = false
                ui.setMicLevel(0f)
                handle(text.orEmpty(), dictation = true)
            }
            override fun onError(code: Int) {
                listening = false
                ui.setMicLevel(0f)
                message("Microphone permission is needed. Open Brick to grant it.")
            }
        })
    }

    /** Finish listening early with what was heard so far. */
    fun stopListening() = listener.stop()

    /** True while requests are typed in the card instead of spoken. */
    var typing = false
        private set

    /** The type box opened: stop listening and wait for typed requests. */
    fun startTyping() {
        typing = true
        job?.cancel()
        listener.cancel()
        listening = false
        holdWakeWord()
        ui.setMicLevel(0f)
        ui.setState(PulseView.Mode.LISTENING, "")
    }

    /** Back to speaking. */
    fun startTalking() {
        typing = false
        start()
    }

    /** A typed request (or a typed answer to Brick's question, or a typed note). */
    fun submitTyped(text: String) {
        if (text.isBlank()) return
        handle(text.trim(), dictation = container.assistant.awaitingDictation)
    }

    private fun handle(text: String, dictation: Boolean = false) {
        if (text.isNotBlank()) container.log.add(LogEntry(Speaker.USER, text))
        ui.setState(PulseView.Mode.THINKING, if (dictation && text.isBlank()) "(nothing)" else text)
        job?.cancel()
        waitingToClose = false
        job = scope.launch {
            var lastDavid: String? = null
            var panel = false
            var lyrics = false
            var answerChars = 0
            val forTopic = topic
            topic = null
            try {
                container.assistant.handle(text, forTopic) { e ->
                    container.log.add(e)
                    ui.setQuiet(container.assistant.lastTextOnly)
                    if (e.panel) { panel = true; if (e.readout?.lyrics != null) lyrics = true; ui.setPanel(e.readout ?: com.brick.assistant.brain.Readout(e.text)); return@handle }
                    if (e.speaker == Speaker.DAVID || e.speaker == Speaker.ROBOT) answerChars += e.text.length
                    when (e.speaker) {
                        Speaker.DAVID -> { lastDavid = e.text; ui.setState(PulseView.Mode.DAVID, e.text, text) }
                        Speaker.ROBOT -> ui.setState(PulseView.Mode.ROBOT, e.text, lastDavid ?: text)
                        else -> {}
                    }
                }
                // Typing: the card stays up for the answer or the next request (tap outside to close).
                if (typing) return@launch
                // Lyrics: the card stays up while you read along (tap outside to close).
                if (lyrics) return@launch
                // Brick asked a yes-or-no question (e.g. "Send this text?"): listen for the answer.
                if (container.assistant.awaitingConfirmation) {
                    start()
                    return@launch
                }
                // Taking a note: listen until 5 seconds of silence, then save it.
                if (container.assistant.awaitingDictation) {
                    dictate()
                    return@launch
                }
                // Time to read what wasn't spoken, or the readout (tap outside to close sooner).
                val readMs = (answerChars / TermCard.CHARS_PER_SECOND * 1000).toLong() + 2500
                waitingToClose = true
                delay(
                    when {
                        container.assistant.lastTextOnly -> maxOf(readMs, if (panel) 10_000L else 4000L).coerceAtMost(25_000L)
                        panel -> 10_000L
                        else -> 1400L
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.log.add(LogEntry(Speaker.SYSTEM, "Error: ${e.message}"))
            }
            onDone()
        }
    }

    private fun message(text: String) {
        listening = false
        ui.setState(PulseView.Mode.MESSAGE, text)
        job?.cancel()
        job = scope.launch {
            delay(2200)
            onDone()
        }
    }

    /** Waiting out the time to read the answer before the card closes. */
    @Volatile private var waitingToClose = false

    /** You touched the readout (a switch, slider or button): the card stays up until you tap outside it. */
    fun keepOpen() {
        if (waitingToClose) { waitingToClose = false; job?.cancel() }
    }

    fun cancel() {
        job?.cancel()
        listener.cancel()
        listening = false
        releaseWakeWord()
    }

    fun destroy() {
        cancel()
        listener.destroy()
        scope.cancel()
    }
}
