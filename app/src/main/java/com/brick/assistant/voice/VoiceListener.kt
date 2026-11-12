package com.brick.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Speech recognition without the recognizer's own start/stop beeps, so Brick's listening sound is
 * the only sound. Google's recognizer has no option to turn its tones off, so the sound streams it
 * plays them on are muted while listening and restored about 0.4 s after it stops (also at the next
 * launch if the app was killed mid-listen).
 *
 * The recognizer is chosen explicitly (Google first, never Brick's own proxy service), so it keeps
 * working when Brick is the default assistant.
 */
class VoiceListener(context: Context) {
    interface Callbacks {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onLevel(level: Float) {}
        fun onResult(text: String?)
        fun onError(code: Int)
    }

    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null
    private var session = 0

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(ctx) || recognizerComponent(ctx) != null

    /**
     * Must be called on the main thread. [audio], when given (Android 13+), is 16 kHz mono 16-bit PCM to
     * recognize instead of the phone's microphone (the car's microphone in Android Auto).
     */
    fun start(cb: Callbacks, audio: android.os.ParcelFileDescriptor? = null, silenceMs: Long? = null) {
        listenStart = System.currentTimeMillis()
        stopRequested = false
        startRound(cb, audio, silenceMs)
    }

    /** You tapped stop: deliver what was heard, never start another round. */
    @Volatile private var stopRequested = false

    /** When the current request started listening (on-device rounds that heard nothing restart within it). */
    private var listenStart = 0L

    private fun startRound(cb: Callbacks, audio: android.os.ParcelFileDescriptor?, silenceMs: Long?) {
        reset()
        val id = ++session
        callbacks = cb
        OfflineSpeech.refresh(ctx)
        // Brick records itself, so it can start over: with the regular recognizer when the on-device one fails,
        // or for another on-device round when that one gave up on silence too soon.
        retry = if (audio == null) ({ startRound(cb, null, silenceMs) }) else null
        if (audio == null && android.os.Build.VERSION.SDK_INT >= 33) {
            val level: (Float) -> Unit = { l -> main.post { if (id == session) cb.onLevel(l) } }
            // Bluetooth headphones connected: record from their microphone (opening the link takes a moment).
            if (headsetWanted()) {
                Thread({
                    val dev = HeadsetMic.acquire(ctx)
                    val s = MicStream.open(ctx, dev, onLevel = level, onClose = { if (dev != null) HeadsetMic.release(ctx) })
                    main.post {
                        if (id != session) { s?.close(); return@post }
                        headset = s
                        begin(id, cb, s?.audio, silenceMs)
                    }
                }, "brick-headset-open").start()
                return
            }
            // Record right away and stream it, so nothing is lost while the recognizer connects.
            val s = MicStream.open(ctx, null, onLevel = level)
            headset = s
            begin(id, cb, s?.audio, silenceMs)
            return
        }
        begin(id, cb, audio, silenceMs)
    }

    private var headset: MicStream? = null

    private fun headsetWanted(): Boolean =
        (ctx as? com.brick.assistant.BrickApp)?.container?.settings?.current?.headsetMic == true && HeadsetMic.isConnected(ctx)

    private fun closeHeadset() {
        headset?.close()
        headset = null
    }

    private var lastPartial = ""
    private var retry: (() -> Unit)? = null

    /** True when [recognizer] is the on-device one. */
    private var onDevice = false

    private fun begin(id: Int, cb: Callbacks, audio: android.os.ParcelFileDescriptor?, silenceMs: Long? = null) {
        lastPartial = ""
        // Switch recognizers when the language pack finished downloading, or the on-device one gave up.
        if (recognizer != null && onDevice != OfflineSpeech.usable) { recognizer?.destroy(); recognizer = null }
        val r = recognizer ?: create().also { recognizer = it }
        val usingOnDevice = onDevice
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (id == session) cb.onReady() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (id == session) cb.onLevel(((rmsdB + 1f) / 9f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (id == session) { cb.onLevel(0f); closeHeadset() } }
            override fun onError(error: Int) {
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && listenAgain(id)) return
                if (usingOnDevice && id == session && OfflineSpeech.isFallbackError(error)) {
                    OfflineSpeech.giveUp(error)
                    val again = retry
                    if (again != null && lastPartial.isEmpty()) {
                        closeHeadset()
                        main.post { if (id == session) again() }
                        return
                    }
                }
                finish(id) { cb.onError(error) }
            }
            override fun onResults(results: Bundle?) {
                // With a streamed source (headphones, car mic) the final result can come back empty: use the last partial.
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: lastPartial.takeIf { it.isNotBlank() }
                if (text == null && listenAgain(id)) return
                finish(id) { cb.onResult(text) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (id != session) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { lastPartial = it; cb.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        BeepMuter.mute(ctx)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        // No internet: ask the regular recognizer for its offline model instead of failing with a network error.
        if (!usingOnDevice && OfflineSpeech.offline(ctx)) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (silenceMs != null) {
            // Dictation: ask the recognizer to wait through pauses (not every recognizer honors this).
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        }
        if (audio != null && android.os.Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
        r.startListening(intent)
    }

    /**
     * Recognizers (the on-device one especially) end a turn as soon as they hear a noise and then silence (the
     * tap, Brick's own listening sound), long before you start talking. While nothing was recognized and it's
     * been under [PATIENCE_MS], quietly listen again.
     */
    private fun listenAgain(id: Int): Boolean {
        if (stopRequested || id != session || lastPartial.isNotEmpty()) return false
        if (System.currentTimeMillis() - listenStart >= PATIENCE_MS) return false
        val again = retry ?: return false
        closeHeadset()
        main.post { if (id == session) again() }
        return true
    }

    /**
     * Dictation (notes): keeps listening across pauses and delivers everything said once there have been
     * [silenceMs] without speech. [Callbacks.onPartial] gets the whole text so far. An empty result means
     * nothing was said.
     */
    fun dictate(cb: Callbacks, silenceMs: Long = 5_000, audio: (() -> android.os.ParcelFileDescriptor?)? = null) {
        val parts = ArrayList<String>()
        var lastSpeech = System.currentTimeMillis()
        val dictation = ++dictationId
        finishNow = false
        var partialNow = "" // words of the round in progress, not final yet
        fun soFar(extra: String? = null) = (parts + listOfNotNull(extra?.takeIf { it.isNotBlank() })).joinToString(" ")
        fun deliver() {
            if (dictation != dictationId) return
            dictationId++
            main.removeCallbacks(watchdogTask)
            reset() // stop the round in progress
            BeepMuter.restoreSoon(ctx, main)
            cb.onResult(soFar(partialNow))
        }
        // The silence limit is timed here, not by the recognizer (which can wait much longer before giving up).
        watchdogTask = object : Runnable {
            override fun run() {
                if (dictation != dictationId) return
                if (finishNow || System.currentTimeMillis() - lastSpeech >= silenceMs) deliver() else main.postDelayed(this, 250)
            }
        }
        main.postDelayed(watchdogTask, 250)
        lateinit var round: () -> Unit
        fun next() {
            if (dictation != dictationId) return
            if (finishNow || System.currentTimeMillis() - lastSpeech >= silenceMs) {
                deliver()
            } else {
                round()
            }
        }
        round = {
            start(object : Callbacks {
                override fun onReady() = cb.onReady()
                override fun onPartial(text: String) {
                    if (text != partialNow) lastSpeech = System.currentTimeMillis()
                    partialNow = text
                    cb.onPartial(soFar(text))
                }
                override fun onLevel(level: Float) = cb.onLevel(level)
                override fun onResult(text: String?) {
                    if (!text.isNullOrBlank()) { parts += text.trim(); lastSpeech = System.currentTimeMillis() }
                    partialNow = ""
                    next()
                }
                override fun onError(code: Int) {
                    if (code == SpeechRecognizer.ERROR_CLIENT && dictation != dictationId) return
                    if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { dictationId++; cb.onError(code); return }
                    next() // no speech this round: keep waiting until the silence limit
                }
            }, audio?.invoke(), silenceMs)
            // start() clears pending timers, so keep the silence timer running across rounds.
            main.removeCallbacks(watchdogTask)
            main.postDelayed(watchdogTask, 250)
        }
        round()
    }

    private var dictationId = 0
    private var watchdogTask: Runnable = Runnable {}
    @Volatile private var finishNow = false

    /** Ends dictation early (delivers what was said so far on the next callback). */
    fun cancelDictation() { dictationId++ }

    /** Stop recording and deliver whatever was heard. */
    fun stop() {
        finishNow = true // during dictation: deliver what was said so far
        stopRequested = true
        closeHeadset() // ends the audio stream, so the recognizer finishes with what it heard
        recognizer?.stopListening()
    }

    fun cancel() {
        dictationId++
        reset()
        BeepMuter.restoreSoon(ctx, main)
    }

    private fun reset() {
        closeHeadset()
        session++
        recognizer?.cancel()
        callbacks = null
        main.removeCallbacksAndMessages(null)
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish(id: Int, deliver: () -> Unit) {
        if (id != session) return
        closeHeadset()
        // Let the stop tone play into the muted streams, then restore and continue.
        main.postDelayed({
            BeepMuter.restore(ctx)
            if (id == session) deliver()
        }, 400)
    }

    private fun create(): SpeechRecognizer {
        onDevice = false
        if (OfflineSpeech.usable && android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { onDevice = true }
            } catch (e: Exception) {
                OfflineSpeech.giveUp(-1)
            }
        }
        val comp = recognizerComponent(ctx)
        return if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    companion object {
        /** How long Brick waits for you to start talking on the on-device recognizer. */
        private const val PATIENCE_MS = 6_000L

        /** Best installed recognizer that isn't Brick itself (Google app first). */
        fun recognizerComponent(context: Context): ComponentName? {
            val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo }
                .filter { it.packageName != context.packageName }
            val order = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
            val pick = order.firstNotNullOfOrNull { p -> services.firstOrNull { it.packageName == p } } ?: services.firstOrNull()
            return pick?.let { ComponentName(it.packageName, it.name) }
        }
    }
}

/** Mutes the streams recognizer tones play on, remembering what to restore even across a crash. */
object BeepMuter {
    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private const val PREFS = "beep_muter"

    fun mute(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val already = prefs.getString("muted", "").orEmpty().split(",").filter { it.isNotEmpty() }.toMutableSet()
        for (s in streams) {
            if (s.toString() in already) continue
            try {
                if (!am.isStreamMute(s)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    already += s.toString()
                }
            } catch (_: SecurityException) {
                // Muting this stream would change Do Not Disturb; skip it.
            }
        }
        prefs.edit().putString("muted", already.joinToString(",")).commit()
    }

    fun restore(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("muted", "").orEmpty().split(",").filter { it.isNotEmpty() }.forEach {
            try { am.adjustStreamVolume(it.toInt(), AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        prefs.edit().remove("muted").commit()
    }

    fun restoreSoon(context: Context, handler: Handler) {
        handler.postDelayed({ restore(context) }, 400)
    }
}
