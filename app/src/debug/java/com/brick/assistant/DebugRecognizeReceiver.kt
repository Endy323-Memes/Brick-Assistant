package com.brick.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import com.brick.assistant.voice.VoiceListener
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: checks that the speech recognizer accepts a streamed audio source (the path used for
 * Bluetooth headphones and the car microphone). Feeds a 16 kHz mono 16-bit WAV from Brick's files folder:
 *   adb shell am broadcast -n com.brick.assistant/.DebugRecognizeReceiver --es file test.wav
 * The result is logged under the tag BrickDebug.
 */
class DebugRecognizeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val f = File(context.filesDir, intent.getStringExtra("file") ?: "test.wav")
        if (!f.exists()) { Log.w(TAG, "missing ${f.absolutePath}"); return }
        val pcm = f.readBytes().let { it.copyOfRange(44.coerceAtMost(it.size), it.size) }
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    var i = 0
                    while (i < pcm.size) { // real time, 100 ms at a time
                        val n = minOf(3200, pcm.size - i)
                        out.write(pcm, i, n)
                        i += n
                        Thread.sleep(100)
                    }
                    out.write(ByteArray(32000)) // a second of silence to end the utterance
                }
            } catch (e: Exception) { Log.w(TAG, "stream: $e") }
        }
        val pending = goAsync()
        VoiceListener(context).start(object : VoiceListener.Callbacks {
            override fun onPartial(text: String) { Log.i(TAG, "partial: $text") }
            override fun onResult(text: String?) { Log.i(TAG, "RESULT: $text"); pending.finish() }
            override fun onError(code: Int) { Log.i(TAG, "ERROR: $code"); pending.finish() }
        }, read)
    }

    companion object { const val TAG = "BrickDebug" }
}
