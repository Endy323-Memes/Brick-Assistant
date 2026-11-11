package com.brick.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Uses the microphone of connected Bluetooth headphones. Android only opens a headset's microphone through
 * its headset (call) link, so while Brick listens through it the headphones switch to headset mode; Brick
 * hands the link back as soon as nothing is listening.
 */
object HeadsetMic {
    private var holders = 0

    private fun isHeadset(type: Int) =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /** The headphones' microphone, or null when no Bluetooth headset with a mic is connected. */
    fun inputDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: if (Build.VERSION.SDK_INT >= 31) {
                // SCO microphones are often only listed once the headset link is up; it's offered as a communication device.
                am.availableCommunicationDevices.firstOrNull { isHeadset(it.type) }
            } else {
                null
            }
    }

    fun isConnected(context: Context) = inputDevice(context) != null

    /** Opens the headset link (counted, so wake word and requests can share it). Returns the mic to record from. */
    @Synchronized
    fun acquire(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        if (inputDevice(context) == null) return null
        holders++
        if (holders == 1) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    am.availableCommunicationDevices.firstOrNull { isHeadset(it.type) }?.let { am.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION") am.startBluetoothSco()
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                }
            } catch (_: Exception) {}
        }
        // The headset's input shows up once the link is open.
        repeat(15) {
            val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isHeadset(it.type) }
            if (dev != null) return dev
            Thread.sleep(100)
        }
        return inputDevice(context) ?: run { release(context); null }
    }

    @Synchronized
    fun release(context: Context) {
        if (holders == 0) return
        holders--
        if (holders > 0) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") am.isBluetoothScoOn = false
                @Suppress("DEPRECATION") am.stopBluetoothSco()
            }
        } catch (_: Exception) {}
    }

    /**
     * Records 16 kHz mono 16-bit audio from the headset mic into a pipe for the speech recognizer
     * (Android 13+). Call [Stream.close] when recognition ends. Null when it can't be opened.
     */
    @SuppressLint("MissingPermission")
    fun openStream(context: Context, maxMs: Long = 15_000): Stream? {
        if (Build.VERSION.SDK_INT < 33) return null
        val dev = acquire(context) ?: run { release(context); return null }
        return try {
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); release(context); return null }
            rec.preferredDevice = dev
            val (read, write) = ParcelFileDescriptor.createPipe()
            val s = Stream(context.applicationContext, rec, read)
            rec.startRecording()
            thread(name = "brick-headset-mic") {
                val buf = ByteArray(3200)
                val start = System.currentTimeMillis()
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                        while (s.open && System.currentTimeMillis() - start < maxMs) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                } catch (_: IOException) {
                } catch (_: Exception) {}
                s.close()
                s.finish()
            }
            s
        } catch (e: Exception) {
            release(context)
            null
        }
    }

    class Stream internal constructor(private val ctx: Context, private val rec: AudioRecord, val audio: ParcelFileDescriptor) {
        @Volatile internal var open = true
            private set

        /** Stops recording; the recording thread then frees the mic and the headset link. */
        @Synchronized
        fun close() {
            if (!open) return
            open = false
            try { rec.stop() } catch (_: Exception) {}
        }

        private var finished = false

        @Synchronized
        internal fun finish() {
            if (finished) return
            finished = true
            try { rec.release() } catch (_: Exception) {}
            release(ctx)
        }
    }
}
