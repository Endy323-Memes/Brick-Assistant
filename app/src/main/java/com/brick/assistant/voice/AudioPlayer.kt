package com.brick.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Plays WAV files one at a time; suspends until playback finishes. Cancelling stops playback. */
class AudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    // Assistant usage: Brick follows the phone's assistant volume instead of the media volume.
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    /** Ducks other audio (music) while the assistant talks. */
    fun acquireFocus() {
        try { audioManager.requestAudioFocus(focusRequest) } catch (_: Exception) {}
    }

    /** Loudness of what Brick is playing right now, 0..1 (drives the card animation). */
    @Volatile var outputLevel = 0f
        private set

    fun releaseFocus() {
        try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
    }

    /** Plays a bundled sound (res/raw) and suspends until it ends. */
    suspend fun playRaw(resId: Int) = playWith { mp ->
        appContext.resources.openRawResourceFd(resId).use { afd ->
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
    }

    suspend fun play(file: File) = playWith { mp -> mp.setDataSource(file.absolutePath) }

    private suspend fun playWith(source: (MediaPlayer) -> Unit) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            var released = false
            var viz: android.media.audiofx.Visualizer? = null
            fun release() {
                if (released) return
                released = true
                try { viz?.enabled = false; viz?.release() } catch (_: Exception) {}
                outputLevel = 0f
                try { mp.release() } catch (_: Exception) {}
            }
            fun finish() {
                release()
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setAudioAttributes(attrs)
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            cont.invokeOnCancellation {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { if (!released) mp.stop() } catch (_: Exception) {}
                    release()
                }
            }
            try {
                source(mp)
                mp.prepare()
                viz = startVisualizer(mp.audioSessionId)
                mp.start()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    /** Measures Brick's own playback (needs the microphone permission Brick already has). */
    private fun startVisualizer(session: Int): android.media.audiofx.Visualizer? = try {
        android.media.audiofx.Visualizer(session).apply {
            captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)
            setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?, rate: Int) {
                    if (wave == null || wave.isEmpty()) return
                    var sum = 0.0
                    for (b in wave) { val s = ((b.toInt() and 0xFF) - 128) / 128.0; sum += s * s }
                    val rms = kotlin.math.sqrt(sum / wave.size)
                    outputLevel = (rms * 3.2).toFloat().coerceIn(0f, 1f)
                }
                override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {}
            }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false)
            enabled = true
        }
    } catch (e: Exception) {
        null
    }
}