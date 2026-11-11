package com.brick.assistant.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.brick.assistant.overlay.AssistEntryActivity
import com.brick.assistant.voice.VoiceListener

/**
 * Lets Brick be chosen under Settings > Apps > Default apps > Digital assistant app.
 * Android 10+ only lists apps that provide a VoiceInteractionService.
 */
class BrickVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        active = this
    }

    override fun onShutdown() {
        if (active === this) active = null
        super.onShutdown()
    }

    companion object {
        /** Set while Brick is the active digital assistant (used by the Hey Brick wake word). */
        @Volatile var active: BrickVoiceInteractionService? = null
            private set
    }
}

class BrickSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = BrickSession(this)
}

/**
 * Digital-assistant trigger (gesture or side key). Opens Brick's floating card screen over the
 * current app ([AssistEntryActivity]) and gets out of the way.
 */
class BrickSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistEntryActivity::class.java)
            .putExtra(AssistEntryActivity.EXTRA_SOURCE, args?.getString(AssistEntryActivity.EXTRA_SOURCE) ?: "digital assistant")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            try { context.startActivity(intent) } catch (_: Exception) {}
        }
        hide()
    }
}
/**
 * When Brick is the default assistant, Android also routes apps' speech recognition to the
 * assistant's recognizer. This forwards those requests to Google's recognizer so voice input in
 * other apps keeps working.
 */
class BrickRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val comp = VoiceListener.recognizerComponent(this)
        if (comp == null) {
            safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val r = inner ?: SpeechRecognizer.createSpeechRecognizer(this, comp).also { inner = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { listener.bufferReceived(buffer ?: ByteArray(0)) }
            override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
            override fun onError(error: Int) = safe { listener.error(error) }
            override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) { inner?.stopListening() }
    override fun onCancel(listener: Callback) { inner?.cancel() }

    override fun onDestroy() {
        inner?.destroy()
        inner = null
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }
}
