package com.brick.assistant.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brick.assistant.HomeActivity
import com.brick.assistant.R
import com.brick.assistant.assist.BrickVoiceInteractionService
import com.brick.assistant.brain.hasPermission
import com.brick.assistant.overlay.AssistEntryActivity
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Lets Brick's own listening take the microphone; the wake word steps aside while anything holds it. */
object WakeWordGate {
    private var holders = 0
    @Volatile var paused = false
        private set

    @Synchronized fun hold() { holders++; paused = true }
    @Synchronized fun release() { holders = (holders - 1).coerceAtLeast(0); paused = holders > 0 }
}

/**
 * Listens for "Hey Brick" fully offline (Vosk small English model, restricted to that phrase) and opens
 * Brick's floating card. Runs as a microphone foreground service, also with the screen off or locked (the
 * card then wakes the screen and shows over the lock screen). Always uses the phone's microphone, so
 * Bluetooth headphones stay in normal (music) mode; requests themselves use the headphones' mic.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 11
        private const val SAMPLE_RATE = 16000
        private const val PHRASE = "hey brick"
        private const val MIN_CONFIDENCE = 0.6
        /** Below this battery level (and not charging), "Hey Brick" pauses when the setting is on. */
        private const val LOW_BATTERY = 15

        @Volatile var status: String = "Off"
            private set

        /**
         * One 100 ms chunk of listening: the speech model only runs around sound (silence is skipped, which is
         * the big battery saver). Returns true when "Hey Brick" was heard.
         */
        fun step(gate: SpeechGate, rec: Recognizer, chunk: ShortArray, n: Int): Boolean {
            val out = gate.offer(chunk, n)
            for ((c, len) in out.chunks) {
                if (rec.acceptWaveForm(c, len) && heard(rec.result)) {
                    rec.reset()
                    gate.reset()
                    return true
                }
            }
            if (out.ended) {
                // The sound stopped: let the model finish what it heard, then start fresh.
                val hit = heard(rec.finalResult)
                rec.reset()
                return hit
            }
            return false
        }

        /** True for a confident "hey brick" (every word at or above [MIN_CONFIDENCE]). */
        fun heard(json: String): Boolean = try {
            val o = JSONObject(json)
            val words = o.optJSONArray("result")
            o.optString("text") == PHRASE && words != null && words.length() == 2 &&
                (0 until words.length()).all { words.getJSONObject(it).optDouble("conf", 0.0) >= MIN_CONFIDENCE }
        } catch (e: Exception) {
            false
        }

        /** Call while Brick is visible (Android only allows starting microphone services from the foreground). */
        fun start(context: Context) {
            if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) return
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    @Volatile private var stopping = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            // Android refused a microphone service (e.g. started from the background): give up quietly.
            status = "Open Brick to turn on Hey Brick"
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) {
            stopping = false
            worker = Thread({ runLoop() }, "brick-wake-word").also { it.start() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Brick", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listening for \"Hey Brick\"")
            .setContentText("Processed on this phone only. Turn off in Brick > Settings.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        status = "Loading the speech model…"
        val modelDir = try {
            ModelInstaller.ensure(this)
        } catch (e: Exception) {
            status = "Could not unpack the speech model (${e.message})"
            stopSelf()
            return
        }
        var model: Model? = null
        var rec: Recognizer? = null
        var recorder: AudioRecord? = null
        val power = getSystemService(PowerManager::class.java)
        // Only used while paused in a pocket on phones whose proximity sensor can't wake them. While recording,
        // Android already keeps the CPU awake for the microphone, so no wake lock is needed for listening.
        val cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Brick:HeyBrick").apply { setReferenceCounted(false) }
        val gate = SpeechGate()
        val pocket = PocketDetector(this).also { it.start() }
        fun pocketPause() = (application as com.brick.assistant.BrickApp).container.settings.current.pocketPause
        // Low battery, checked every 30 seconds (cheap, but no need to ask ten times a second).
        val batteries = getSystemService(android.os.BatteryManager::class.java)
        var lowChecked = 0L
        var low = false
        fun lowBattery(): Boolean {
            if (!(application as com.brick.assistant.BrickApp).container.settings.current.lowBatteryPause) return false
            val now = SystemClock.elapsedRealtime()
            if (lowChecked == 0L || now - lowChecked > 30_000) {
                lowChecked = now
                val level = batteries?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                low = power.isPowerSaveMode || (level in 0 until LOW_BATTERY && batteries?.isCharging != true)
            }
            return low
        }
        fun closeRecorder() {
            recorder?.let { runCatching { it.stop() }; it.release() }
            recorder = null
        }
        try {
            model = Model(modelDir.absolutePath)
            rec = Recognizer(model, SAMPLE_RATE.toFloat(), "[\"$PHRASE\", \"[unk]\"]").apply { setWords(true) }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            while (!stopping) {
                if (WakeWordGate.paused) {
                    closeRecorder()
                    if (cpu.isHeld) cpu.release()
                    rec.reset()
                    gate.reset()
                    status = "Paused while Brick is talking"
                    SystemClock.sleep(300)
                    continue
                }
                // In a pocket: stop listening. The CPU may sleep when taking the phone out wakes it anyway.
                if (pocketPause() && pocket.inPocket) {
                    closeRecorder()
                    rec.reset()
                    if (pocket.wakesOnExit) { if (cpu.isHeld) cpu.release() }
                    else if (!power.isInteractive && !cpu.isHeld) cpu.acquire()
                    status = "Paused: the phone is in a pocket"
                    SystemClock.sleep(400)
                    continue
                }
                // Battery saver or low battery: stop listening (and let the CPU sleep) until it's charged or saver is off.
                if (lowBattery()) {
                    closeRecorder()
                    rec.reset()
                    gate.reset()
                    if (cpu.isHeld) cpu.release()
                    status = "Paused: battery saver or low battery"
                    SystemClock.sleep(2000)
                    continue
                }
                if (cpu.isHeld) cpu.release()
                if (recorder == null) {
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, chunk.size * 4),
                    )
                    if (r.state != AudioRecord.STATE_INITIALIZED) {
                        r.release()
                        status = "Microphone busy"
                        SystemClock.sleep(1000)
                        continue
                    }
                    // The phone's own microphone, even with Bluetooth headphones connected.
                    builtInMic()?.let { r.preferredDevice = it }
                    r.startRecording()
                    recorder = r
                    status = "Listening for \"Hey Brick\""
                }
                val n = recorder!!.read(chunk, 0, chunk.size)
                if (n <= 0) { SystemClock.sleep(50); continue }
                // Just heard "Hey Brick": keep what's said next for the card instead of listening for the wake word.
                if (com.brick.assistant.voice.WakeHandoff.collecting) {
                    com.brick.assistant.voice.WakeHandoff.add(chunk, n)
                    continue
                }
                if (step(gate, rec, chunk, n)) trigger()
            }
        } catch (e: Throwable) {
            status = "Hey Brick stopped (${e.message})"
        } finally {
            pocket.stop()
            closeRecorder()
            if (cpu.isHeld) cpu.release()
            rec?.close()
            model?.close()
            if (!stopping) stopSelf()
        }
    }

    private fun builtInMic(): android.media.AudioDeviceInfo? =
        getSystemService(android.media.AudioManager::class.java)?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }



    private fun trigger() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 3000) return
        lastTrigger = now
        com.brick.assistant.voice.WakeHandoff.begin()
        main.post {
            // Screen off or locked: open the card directly; it wakes the screen and shows over the lock screen.
            val asleep = !getSystemService(PowerManager::class.java).isInteractive ||
                getSystemService(KeyguardManager::class.java).isKeyguardLocked
            // Preferred when unlocked: Brick is the active digital assistant, so ask the system to show its session.
            val vis = BrickVoiceInteractionService.active
            if (vis != null && !asleep) {
                try {
                    vis.showSession(Bundle().apply { putString(AssistEntryActivity.EXTRA_SOURCE, "hey brick") }, 0)
                    return@post
                } catch (_: Exception) {}
            }
            // Otherwise open the card directly (allowed when "Display over other apps" is on).
            try {
                startActivity(
                    Intent(this, AssistEntryActivity::class.java)
                        .putExtra(AssistEntryActivity.EXTRA_SOURCE, "hey brick")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        stopping = true
        worker = null
        status = "Off"
        super.onDestroy()
    }
}

/** Copies the bundled Vosk model out of the APK once (Vosk needs real files). */
object ModelInstaller {
    private const val ASSET_DIR = "vosk-en"

    fun ensure(context: Context): File {
        val dest = File(context.filesDir, ASSET_DIR)
        val want = context.assets.open("$ASSET_DIR/VERSION").bufferedReader().use { it.readText().trim() }
        val have = File(dest, "VERSION").takeIf { it.exists() }?.readText()?.trim()
        if (have == want) return dest
        val tmp = File(context.filesDir, "$ASSET_DIR.tmp")
        tmp.deleteRecursively()
        copy(context, ASSET_DIR, tmp)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IllegalStateException("could not move the model into place")
        return dest
    }

    private fun copy(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (c in children) copy(context, "$assetPath/$c", File(target, c))
    }
}
