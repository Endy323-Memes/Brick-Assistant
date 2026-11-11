package com.brick.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Audio captured by the "Hey Brick" listener after it heard the wake word, so the card can hand the very
 * first words of your command to the recognizer (they're spoken while the card is still opening).
 */
object WakeHandoff {
    private class Chunk(val at: Long, val bytes: ByteArray)

    private val chunks = ArrayList<Chunk>()
    @Volatile private var since = 0L
    @Volatile var collecting = false
        private set

    /** The wake word was heard: start keeping what's said next. */
    @Synchronized fun begin() { chunks.clear(); since = SystemClock.elapsedRealtime(); collecting = true }

    /** Called by the wake listener for each 16 kHz mono 16-bit chunk while [collecting]. */
    @Synchronized fun add(pcm: ShortArray, n: Int) {
        if (!collecting) return
        val now = SystemClock.elapsedRealtime()
        if (now - since > 5_000) { collecting = false; return }
        val b = ByteArray(n * 2)
        for (i in 0 until n) { b[i * 2] = (pcm[i].toInt() and 0xFF).toByte(); b[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
        // Stamp each chunk with when it started.
        chunks += Chunk(now - n * 1000L / 16_000, b)
    }

    /** Audio recorded before [before] (when the card's own mic started), and stops collecting. */
    @Synchronized fun take(before: Long): ByteArray {
        collecting = false
        val fresh = SystemClock.elapsedRealtime() - since < 8_000
        val out = ByteArrayOutputStream()
        if (fresh) chunks.filter { it.at < before }.forEach { out.write(it.bytes) }
        chunks.clear()
        return out.toByteArray()
    }
}

/**
 * Records 16 kHz mono 16-bit audio and streams it to the speech recognizer through a pipe (Android 13+).
 * Recording starts at once, so nothing is lost while the recognizer is still connecting; audio the wake
 * word listener kept is sent first. [device] picks a microphone (Bluetooth headset), or null for the phone.
 */
class MicStream private constructor(
    private val rec: AudioRecord,
    val audio: ParcelFileDescriptor,
    private val onClose: () -> Unit,
) {
    @Volatile var open = true
        private set
    private var finished = false

    /** Stops recording; the thread then frees the microphone. */
    @Synchronized fun close() {
        if (!open) return
        open = false
        try { rec.stop() } catch (_: Exception) {}
    }

    @Synchronized private fun finish() {
        if (finished) return
        finished = true
        try { rec.release() } catch (_: Exception) {}
        onClose()
    }

    companion object {
        private const val RATE = 16_000

        @SuppressLint("MissingPermission")
        fun open(
            context: Context,
            device: AudioDeviceInfo? = null,
            maxMs: Long = 15_000,
            onLevel: ((Float) -> Unit)? = null,
            onClose: () -> Unit = {},
        ): MicStream? {
            if (Build.VERSION.SDK_INT < 33) return null
            return try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // A few seconds of room: the recognizer may take a moment before it starts reading.
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2 * 4))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); onClose(); return null }
                if (device != null) rec.preferredDevice = device
                val (read, write) = ParcelFileDescriptor.createPipe()
                val s = MicStream(rec, read, onClose)
                rec.startRecording()
                val startedAt = SystemClock.elapsedRealtime()
                thread(name = "brick-mic-stream") {
                    val buf = ByteArray(3200) // 100 ms
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            // First the words said right after "Hey Brick", then live audio.
                            WakeHandoff.take(startedAt).takeIf { it.isNotEmpty() }?.let { out.write(it) }
                            while (s.open && SystemClock.elapsedRealtime() - startedAt < maxMs) {
                                val n = rec.read(buf, 0, buf.size)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                onLevel?.let { cb ->
                                    var sum = 0.0
                                    var i = 0
                                    while (i + 1 < n) {
                                        val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort() / 32768.0
                                        sum += v * v
                                        i += 2
                                    }
                                    cb((sqrt(sum / (n / 2).coerceAtLeast(1)) * 6).toFloat().coerceIn(0f, 1f))
                                }
                            }
                        }
                    } catch (_: IOException) {
                        // The recognizer closed its end (it heard enough).
                    } catch (_: Exception) {}
                    s.close()
                    s.finish()
                }
                s
            } catch (e: Exception) {
                onClose()
                null
            }
        }
    }
}
