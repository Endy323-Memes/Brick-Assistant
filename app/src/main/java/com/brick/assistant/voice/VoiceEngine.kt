package com.brick.assistant.voice

import android.content.Context
import com.brick.assistant.data.AppSettings
import com.brick.assistant.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Produces audio for the two voices:
 *  - David: the real "Microsoft David Desktop" SAPI voice, streamed from the companion
 *    Windows server (tools/david-server.ps1) and cached on the phone. Falls back to a
 *    tuned Android voice when the server can't be reached.
 *  - Robot: an Android (or David) voice run through [RobotFx].
 */
class VoiceEngine(
    context: Context,
    private val settings: SettingsStore,
    private val tts: AndroidTts,
    private val player: AudioPlayer,
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.filesDir, "david_cache").apply {
        mkdirs()
        // Older builds cached stitched lines at the original speed; drop those once.
        listFiles()?.filter { !it.name.contains("_v2s") }?.forEach { it.delete() }
    }

    /** Cache file for [text] at the current speaking rate and custom-response speed. */
    private fun cacheFile(text: String, s: AppSettings) =
        File(cacheDir, cacheKey(text, s.davidRate) + "_v2s" + (s.davidStitchSpeed * 100).roundToInt() + ".wav")
    private val tmpDir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val tmpCounter = AtomicInteger()
    @Volatile private var serverDownUntil = 0L

    class Rendered(val file: File, val isRealDavid: Boolean)

    /** Android may clear the cache folder while Brick runs, so recreate it every time. */
    private fun tmpFile(prefix: String): File {
        tmpDir.mkdirs()
        return File(tmpDir, "$prefix${tmpCounter.incrementAndGet() % 24}.wav")
    }

    // ---------------------------------------------------------------- David

    /**
     * David audio for [text], best source first:
     *  1. phone cache, 2. real David recordings bundled in the APK (assets/david),
     *  3. the optional PC server, 4. the on-device David match (an Android voice reshaped
     *  to David's measured pitch and tone).
     */
    suspend fun renderDavid(text: String): Rendered? = withContext(Dispatchers.IO) {
        val s = settings.current
        if (s.mainVoice == "classic") return@withContext renderClassic(text, s)
        val cached = cacheFile(text, s)
        if (cached.exists() && cached.length() > 44) return@withContext Rendered(cached, true)

        // A response ending in "-" is spoken without the dash and then cut off mid-word.
        val interrupted = DavidStitcher.endsInterrupted(text)
        val say = if (interrupted) DavidStitcher.withoutDash(text) else text
        if (say.isBlank()) return@withContext null

        var pcm: PcmAudio? = null
        var cacheable = true
        var lastStart = -1
        var lastEnd = -1

        // 1. Real David recording of the whole line (built-in responses).
        if (s.davidRate == 0) readAsset("david/${cacheKey(say, 0)}.wav")?.let { pcm = Wav.read(it) }

        // 2. Optional PC server.
        if (pcm == null && s.davidServerUrl.isNotBlank() && System.currentTimeMillis() > serverDownUntil) {
            val bytes = fetchFromServer(s, say)
            if (bytes != null) pcm = Wav.read(bytes) else serverDownUntil = System.currentTimeMillis() + 60_000
        }

        // 3. Stitched from real David word recordings.
        if (pcm == null && DavidStitcher.coverage(say, words) >= 0.7f) {
            val missing = DavidStitcher.tokenize(say).map { it.first }.distinct().filter { !words.contains(it) }
            val extra = HashMap<String, FloatArray>()
            for (w in missing) davidMatchPcm(w, s)?.let { extra[w] = resample(it, words.sampleRate).samples }
            cacheable = missing.isEmpty()
            DavidStitcher.stitch(say, words) { extra[it] }?.let {
                // Custom responses run a little faster than the recorded words (pitch unchanged).
                val sp = s.davidStitchSpeed.coerceIn(0.8f, 1.8f)
                pcm = PcmAudio(Wsola.speed(it.audio.samples, it.audio.sampleRate, sp), it.audio.sampleRate)
                lastStart = (it.lastWordStart / sp).toInt()
                lastEnd = (it.lastWordEnd / sp).toInt()
            }
        }

        // 4. On-device David match for everything else.
        if (pcm == null) {
            cacheable = false
            pcm = davidMatchPcm(say, s)
        }
        var audio = pcm ?: return@withContext null
        if (interrupted) audio = DavidStitcher.cutOff(audio, lastStart, lastEnd)

        val out = if (cacheable) cached else tmpFile("david")
        Wav.write(out, audio)
        Rendered(out, cacheable)
    }

    // ---------------------------------------------------------------- Classic (natural male main voice)

    private val classicDir = File(context.filesDir, "classic_cache").apply { mkdirs() }

    /** The natural male main voice: a Google male voice, slightly deeper, with calmer intonation. Cached. */
    private suspend fun renderClassic(text: String, s: AppSettings): Rendered? {
        val interrupted = DavidStitcher.endsInterrupted(text)
        val say = (if (interrupted) DavidStitcher.withoutDash(text) else text).trim()
        if (say.isBlank()) return null
        val voice = s.classicVoice.ifBlank { tts.preferredMaleVoice() }
        val key = cacheKey("$voice|${s.classicRate}|${s.classicPitch}|${s.classicExpression}|$interrupted", 0) + cacheKey(say, 0).take(12)
        classicDir.mkdirs()
        val cached = File(classicDir, "$key.wav")
        if (cached.exists() && cached.length() > 44) return Rendered(cached, false)
        val src = tmpFile("classicsrc")
        if (!tts.synthesize(say, voice, s.classicPitch, s.classicRate, src)) return null
        var pcm = Wav.read(src) ?: return null
        pcm = RobotFx.calm(pcm, s.classicExpression, question = say.endsWith("?"))
        if (interrupted) pcm = RobotFx.cutEnd(pcm)
        Wav.write(cached, pcm)
        // Keep the cache small.
        classicDir.listFiles()?.takeIf { it.size > 400 }?.sortedBy { it.lastModified() }?.take(100)?.forEach { it.delete() }
        return Rendered(cached, false)
    }

    private suspend fun davidMatchPcm(text: String, s: AppSettings): PcmAudio? {
        val base = tmpFile("davidsrc")
        val voice = s.davidFallbackVoice.ifBlank { tts.preferredMaleVoice() }
        if (!tts.synthesize(text, voice, 1f, s.davidFallbackRate, base)) return null
        val pcm = Wav.read(base) ?: return null
        return RobotFx.davidMatch(pcm)
    }

    private fun resample(a: PcmAudio, rate: Int): PcmAudio {
        if (a.sampleRate == rate) return a
        val ratio = a.sampleRate.toDouble() / rate
        val n = (a.samples.size / ratio).toInt()
        val x = a.samples
        return PcmAudio(FloatArray(n) { i ->
            val p = i * ratio
            val i0 = p.toInt().coerceAtMost(x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val f = (p - i0).toFloat()
            x[i0] * (1 - f) + x[i1] * f
        }, rate)
    }

    private val words: WordSource by lazy { AssetWordSource(appContext) }

    private fun readAsset(path: String): ByteArray? = try {
        appContext.assets.open(path).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    /** Number of real David recordings bundled in the APK. */
    fun bundledDavidCount(): Int = try {
        appContext.assets.list("david").orEmpty().count { it.endsWith(".wav") }
    } catch (e: Exception) {
        0
    }

    private fun fetchFromServer(s: AppSettings, text: String): ByteArray? = try {
        val url = URL("${baseUrl(s.davidServerUrl)}/speak?rate=${s.davidRate}")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        if (s.davidToken.isNotBlank()) c.setRequestProperty("X-Token", s.davidToken)
        c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val result = if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        c.disconnect()
        result?.takeIf { it.size > 44 && String(it, 0, 4, Charsets.US_ASCII) == "RIFF" }
    } catch (e: Exception) {
        null
    }

    /** Pings the David server and returns a human readable status line. */
    suspend fun testServer(): String = withContext(Dispatchers.IO) {
        val s = settings.current
        if (s.davidServerUrl.isBlank()) return@withContext "Enter the server address first."
        try {
            val c = URL("${baseUrl(s.davidServerUrl)}/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            if (s.davidToken.isNotBlank()) c.setRequestProperty("X-Token", s.davidToken)
            val code = c.responseCode
            val body = (if (code == 200) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            c.disconnect()
            if (code == 200) {
                serverDownUntil = 0
                "Connected: $body"
            } else {
                "Server answered with HTTP $code. $body"
            }
        } catch (e: Exception) {
            "Could not reach the server (${e.javaClass.simpleName}). Is the PC on the same Wi-Fi and the script running?"
        }
    }

    /** Downloads real David audio for every text so responses play instantly and offline. */
    suspend fun prerender(texts: List<String>, onProgress: (done: Int, total: Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val s = settings.current
            var ok = 0
            texts.distinct().let { list ->
                list.forEachIndexed { i, text ->
                    val f = cacheFile(text, s)
                    if (f.exists() && f.length() > 44) {
                        ok++
                    } else {
                        fetchFromServer(s, text)?.let { f.writeBytes(it); ok++ }
                    }
                    withContext(Dispatchers.Main) { onProgress(i + 1, list.size) }
                }
            }
            ok
        }

    fun clearCache(): Int {
        val files = cacheDir.listFiles().orEmpty()
        files.forEach { it.delete() }
        return files.size
    }

    fun cacheCount(): Int = cacheDir.listFiles()?.size ?: 0

    // ---------------------------------------------------------------- Robot

    suspend fun renderRobot(text: String): File? = withContext(Dispatchers.Default) {
        val s = settings.current
        if (s.unitStyle == "natural") {
            // Natural female voice (the Google Maps voice family), no effects at all.
            val out = tmpFile("unit")
            val voice = s.naturalVoice.ifBlank { tts.preferredFemaleVoice() }
            val say = if (DavidStitcher.endsInterrupted(text)) DavidStitcher.withoutDash(text) else text
            return@withContext if (tts.synthesize(say, voice, 1f, s.naturalRate, out)) out else null
        }
        val base: File
        if (s.robotUseDavidBase) {
            base = renderDavid(text)?.file ?: return@withContext null
        } else {
            base = tmpFile("robotsrc")
            // Natural pitch here; RobotFx moves it to the target pitch without changing formants.
            val voice = s.robotVoice.ifBlank { tts.preferredFemaleVoice() }
            if (!tts.synthesize(text, voice, 1f, s.robotRate, base)) return@withContext null
        }
        if (!s.robotFxEnabled) return@withContext base
        val pcm = Wav.read(base) ?: return@withContext base
        val out = tmpFile("robot")
        Wav.write(out, RobotFx.process(pcm, s))
        out
    }

    // ---------------------------------------------------------------- Playback

    suspend fun play(file: File) = player.play(file)
    fun beginSpeaking() = player.acquireFocus()
    fun endSpeaking() = player.releaseFocus()

    suspend fun speakDavid(text: String) {
        renderDavid(text)?.let { play(it.file) }
    }

    suspend fun speakRobot(text: String) {
        renderRobot(text)?.let { play(it) }
    }

    companion object {
        fun baseUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return try {
                val uri = URI(u)
                if (uri.port == -1 && uri.host != null) "${uri.scheme}://${uri.host}:5150" else u
            } catch (e: Exception) {
                u
            }
        }

        private fun cacheKey(text: String, rate: Int): String {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest("$rate|$text".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
