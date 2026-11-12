package com.brick.assistant.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * On-device speech recognition (Android 13+), so Brick hears you without a network connection.
 *
 * The phone's on-device recognizer (Private Compute Services on most phones) needs a language pack. The first
 * time Brick listens it checks whether the pack for your language is installed: if it is, every request is
 * recognized on the phone; if it can be, Brick asks the system to download it and uses the regular recognizer
 * until it's there. If the on-device recognizer fails, Brick goes back to the regular one for the rest of
 * the session.
 */
object OfflineSpeech {
    enum class State { UNKNOWN, CHECKING, READY, DOWNLOADING, UNAVAILABLE }

    private const val TAG = "BrickSpeech"

    @Volatile var state = State.UNKNOWN
        private set

    /** True when requests should go to the on-device recognizer. */
    val usable: Boolean get() = state == State.READY

    fun languageTag(): String = Locale.getDefault().toLanguageTag()

    /** Checks (again) whether the language pack is installed. Main thread. Quick and harmless to call often. */
    fun refresh(context: Context) {
        if (state == State.READY || state == State.CHECKING || state == State.UNAVAILABLE) return
        if (Build.VERSION.SDK_INT < 33) { state = State.UNAVAILABLE; return }
        val ctx = context.applicationContext
        val available = try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Exception) { false }
        if (!available) { state = State.UNAVAILABLE; Log.i(TAG, "no on-device recognizer"); return }
        val previous = state
        state = State.CHECKING
        val r = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (e: Exception) {
            state = State.UNAVAILABLE; Log.w(TAG, "on-device recognizer failed to start", e); return
        }
        val tag = languageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        try {
            r.checkRecognitionSupport(intent, ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    state = when {
                        matches(s.installedOnDeviceLanguages, tag) -> State.READY
                        matches(s.pendingOnDeviceLanguages, tag) -> State.DOWNLOADING
                        matches(s.supportedOnDeviceLanguages, tag) -> {
                            if (previous != State.DOWNLOADING) try { r.triggerModelDownload(intent) } catch (e: Exception) { Log.w(TAG, "download request failed", e) }
                            State.DOWNLOADING
                        }
                        else -> State.UNAVAILABLE
                    }
                    Log.i(TAG, "on-device $tag: $state (installed ${s.installedOnDeviceLanguages}, supported ${s.supportedOnDeviceLanguages.size} languages)")
                    r.destroy()
                }

                override fun onError(error: Int) {
                    state = State.UNAVAILABLE
                    Log.i(TAG, "on-device support check failed: $error")
                    r.destroy()
                }
            })
        } catch (e: Exception) {
            state = State.UNAVAILABLE
            Log.w(TAG, "on-device support check failed", e)
            r.destroy()
        }
    }

    /** The on-device recognizer failed mid-use: use the regular recognizer for the rest of this run. */
    fun giveUp(error: Int) {
        state = State.UNAVAILABLE
        Log.i(TAG, "on-device recognizer error $error, using the regular recognizer")
    }

    /** "en-US" in the list, or the bare language ("en") when only that is listed. */
    internal fun matches(list: List<String>, tag: String): Boolean {
        if (list.any { it.equals(tag, ignoreCase = true) }) return true
        val lang = tag.substringBefore('-')
        return list.any { it.equals(lang, ignoreCase = true) }
    }

    /** Errors after which the on-device recognizer isn't worth trying again. */
    fun isFallbackError(error: Int): Boolean = error in setOf(
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    )

    /** No working internet right now (the regular recognizer is then asked to use its offline model). */
    fun offline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
