package com.brick.assistant.voice

import com.brick.assistant.brain.Patterns

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coroutine wrapper around a TextToSpeech engine that renders speech to WAV files. Brick uses Google's
 * speech engine when it's installed (the same voices Google Maps speaks with), otherwise the default engine.
 */
class AndroidTts(context: Context, engine: String? = googleEngineIfInstalled(context)) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }, engine)

    companion object {
        const val GOOGLE_ENGINE = "com.google.android.tts"

        fun googleEngineIfInstalled(context: Context): String? = try {
            context.packageManager.getPackageInfo(GOOGLE_ENGINE, 0)
            GOOGLE_ENGINE
        } catch (e: Exception) {
            null
        }
    }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutex = Mutex()
    private val counter = AtomicInteger()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(true) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
        })
    }

    suspend fun isReady(): Boolean = withTimeoutOrNull(8000) { ready.await() } ?: false

    /** English voices installed on the device. */
    suspend fun voiceNames(): List<String> {
        if (!isReady()) return emptyList()
        return try {
            tts.voices.orEmpty()
                .filter { it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .map { it.name }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * A male English voice to base the David match on (David is a male voice). Google TTS male voices
     * are tried first; blank means the system default.
     */
    suspend fun preferredMaleVoice(): String {
        cachedMale?.let { return it }
        val names = voiceNames()
        val preferred = listOf("en-us-x-tpd-local", "en-us-x-iom-local", "en-us-x-iol-local",
            "en-us-x-tpd-network", "en-us-x-iom-network", "en-us-x-iol-network")
        val pick = preferred.firstOrNull { it in names }
            ?: names.firstOrNull { Patterns.get("(?i)(^|[-_ ])male|#male|_m\\d|-m\\d").containsMatchIn(it) && !it.contains("female", true) }
            ?: ""
        if (names.isNotEmpty()) cachedMale = pick
        return pick
    }

    @Volatile private var cachedMale: String? = null

    /** A female English voice for the robot unit. Google TTS female voices are tried first. */
    suspend fun preferredFemaleVoice(): String {
        cachedFemale?.let { return it }
        val names = voiceNames()
        val preferred = listOf("en-us-x-sfg-local", "en-us-x-tpf-local", "en-us-x-iob-local", "en-us-x-iog-local",
            "en-us-x-tpc-local", "en-us-x-sfg-network", "en-us-x-tpf-network", "en-us-x-iob-network")
        val pick = preferred.firstOrNull { it in names }
            ?: names.firstOrNull { Patterns.get("(?i)female|#female|_f\\d|-f\\d").containsMatchIn(it) }
            ?: ""
        if (names.isNotEmpty()) cachedFemale = pick
        return pick
    }

    @Volatile private var cachedFemale: String? = null

    suspend fun synthesize(text: String, voiceName: String, pitch: Float, rate: Float, out: File): Boolean {
        if (!isReady()) return false
        return mutex.withLock {
            val id = "u" + counter.incrementAndGet()
            val done = CompletableDeferred<Boolean>()
            pending[id] = done
            try {
                val voice = if (voiceName.isNotBlank()) tts.voices?.firstOrNull { it.name == voiceName } else null
                if (voice != null) tts.voice = voice else tts.language = Locale.US
                tts.setPitch(pitch.coerceIn(0.1f, 3f))
                tts.setSpeechRate(rate.coerceIn(0.1f, 3f))
                out.delete()
                if (tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) {
                    pending.remove(id)
                    return@withLock false
                }
                val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
                ok && out.exists() && out.length() > 44
            } catch (e: Exception) {
                pending.remove(id)
                false
            }
        }
    }
}
