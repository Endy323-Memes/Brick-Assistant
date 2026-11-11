package com.brick.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.assistant.wake.ModelInstaller
import com.brick.assistant.wake.SpeechGate
import com.brick.assistant.wake.WakeWordService
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: runs a 16 kHz mono WAV through the same sound gate and speech model as "Hey Brick" and
 * logs whether it fired and how much audio was decoded (tag BrickDebug).
 *   adb shell am broadcast -n com.brick.assistant/.DebugWakeTestReceiver --es file heybrick.wav
 */
class DebugWakeTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val f = File(context.filesDir, intent.getStringExtra("file") ?: "heybrick.wav")
        val pending = goAsync()
        thread {
            try {
                val bytes = f.readBytes()
                val pcm = ShortArray((bytes.size - 44) / 2) { i -> ((bytes[44 + i * 2 + 1].toInt() shl 8) or (bytes[44 + i * 2].toInt() and 0xFF)).toShort() }
                val model = Model(ModelInstaller.ensure(context).absolutePath)
                val rec = Recognizer(model, 16000f, "[\"hey brick\", \"[unk]\"]").apply { setWords(true) }
                val gate = SpeechGate()
                var fired = -1
                var decoded = 0
                var i = 0
                var idx = 0
                while (i + 1600 <= pcm.size) {
                    val chunk = pcm.copyOfRange(i, i + 1600)
                    val before = gate.active
                    if (WakeWordService.step(gate, rec, chunk, 1600) && fired < 0) fired = idx
                    if (gate.active || before) decoded++
                    i += 1600
                    idx++
                }
                Log.i("BrickDebug", "WAKE TEST: chunks=$idx decoded~=$decoded fired_at_chunk=$fired")
                rec.close(); model.close()
            } catch (e: Exception) {
                Log.w("BrickDebug", "WAKE TEST failed: $e")
            } finally {
                pending.finish()
            }
        }
    }
}
