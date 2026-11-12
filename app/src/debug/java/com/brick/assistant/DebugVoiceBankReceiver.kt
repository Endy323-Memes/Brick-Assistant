package com.brick.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.brick.assistant.voice.WordSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug builds only: rebuilds the David word bank as 16 kHz Opus on the phone with Android's own encoder
 * (tag BrickDebug), from the old 22 kHz ADPCM bank that tools/render-david-words.ps1 makes:
 *   adb push words.adpcm index.tsv /data/local/tmp/   (then run-as com.brick.assistant cp them to files/voice16/)
 *   adb shell am broadcast -n com.brick.assistant/.DebugVoiceBankReceiver --es op encode [--ei kbps 32]
 * Output (words16.opus, index16.tsv) goes to files/voice16/; copy both into assets/david_words.
 */
class DebugVoiceBankReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dir = File(context.filesDir, "voice16").apply { mkdirs() }
        val app = context.applicationContext
        thread {
            try {
                when (intent.getStringExtra("op")) {
                    "encode" -> encode(app, dir, intent.getIntExtra("kbps", 32) * 1000)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "voice bank failed", e)
            }
        }
    }

    private fun encode(context: Context, dir: File, bitrate: Int) {
        val t0 = System.currentTimeMillis()
        val old = FileAdpcmWords(dir)
        val words = File(dir, "index.tsv").readLines()
            .mapNotNull { it.split('\t').takeIf { p -> p.size == 3 }?.get(0) }
        val blobs = arrayOfNulls<ByteArray>(words.size)
        val counts = IntArray(words.size)
        val delays = IntArray(WORKERS) { -1 }
        val done = java.util.concurrent.atomic.AtomicInteger()
        Log.i(TAG, "${words.size} words, $bitrate bps, $WORKERS encoders")
        val workers = (0 until WORKERS).map { wi ->
            val app = context.applicationContext
        thread {
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                try {
                    for (n in wi until words.size step WORKERS) {
                        val raw = old.get(words[n]) ?: continue
                        val pcm = resample(raw)
                        val (packets, d) = encodeWord(enc, pcm, bitrate)
                        if (delays[wi] < 0) delays[wi] = d
                        val out = java.io.ByteArrayOutputStream()
                        for (p in packets) { out.write(p.size and 0xFF); out.write(p.size shr 8); out.write(p) }
                        blobs[n] = out.toByteArray()
                        counts[n] = pcm.size
                        val k = done.incrementAndGet()
                        if (k % 2000 == 0) Log.i(TAG, "encoded $k/${words.size}, ${(System.currentTimeMillis() - t0) / 1000}s")
                    }
                } finally {
                    enc.release()
                }
            }
        }
        workers.forEach { it.join() }
        val blob = File(dir, "words16.opus").outputStream().buffered(1 shl 20)
        val index = StringBuilder()
        var offset = 0L
        var inSamples = 0L
        for ((n, w) in words.withIndex()) {
            val b = blobs[n] ?: continue
            blob.write(b)
            index.append(w).append('\t').append(offset).append('\t').append(b.size).append('\t').append(counts[n]).append('\n')
            offset += b.size
            inSamples += counts[n]
        }
        blob.close()
        val delay = delays.first { it >= 0 }
        File(dir, "index16.tsv").writeText("#opus\t$RATE\t$delay\n$index")
        val secs = inSamples / RATE
        Log.i(TAG, "DONE ${words.size} words, $offset bytes (${offset * 8 / secs} bps avg), delays ${delays.toList()}, ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /** One word through a fresh encoder session; returns its packets and the encoder delay in samples. */
    private fun encodeWord(enc: MediaCodec, pcm: FloatArray, bitrate: Int): Pair<List<ByteArray>, Int> {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_COMPLEXITY, 10)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 640)
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        // Pad so the encoder lookahead and the last partial frame get flushed out.
        val pad = FloatArray(pcm.size + 640)
        pcm.copyInto(pad)
        val packets = ArrayList<ByteArray>()
        var delay = 0
        var pos = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!inputDone) {
                val ib = enc.dequeueInputBuffer(2_000)
                if (ib >= 0) {
                    if (pos < pad.size) {
                        val n = minOf(320, pad.size - pos)
                        val buf = enc.getInputBuffer(ib)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        for (j in 0 until n) buf.putShort((pad[pos + j] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                        enc.queueInputBuffer(ib, 0, n * 2, pos * 1_000_000L / RATE, 0)
                        pos += n
                    } else {
                        enc.queueInputBuffer(ib, 0, 0, pos * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val ob = enc.dequeueOutputBuffer(info, if (inputDone) 2_000 else 0)
            if (ob >= 0) {
                val b = ByteArray(info.size)
                enc.getOutputBuffer(ob)!!.apply { position(info.offset); get(b) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) delay = delayFrom(b)
                else if (b.isNotEmpty()) packets += b
                enc.releaseOutputBuffer(ob, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                enc.outputFormat.getByteBuffer("csd-1")?.let { delay = nsToSamples(it) }
            }
        }
        enc.stop()
        return packets to delay
    }

    /** Codec delay from Android's "AOPUSDLY" codec-config marker, or the OpusHead pre-skip (48 kHz). */
    private fun delayFrom(b: ByteArray): Int {
        val s = String(b, Charsets.ISO_8859_1)
        val i = s.indexOf("AOPUSDLY")
        if (i >= 0) return nsToSamples(ByteBuffer.wrap(b, i + 16, 8))
        val h = s.indexOf("OpusHead")
        if (h >= 0) return (((b[h + 10].toInt() and 0xFF) or ((b[h + 11].toInt() and 0xFF) shl 8)) * RATE / 48000)
        return 0
    }

    private fun nsToSamples(b: ByteBuffer): Int = (b.order(ByteOrder.LITTLE_ENDIAN).getLong(b.position()) * RATE / 1_000_000_000L).toInt()

    /** 22,050 Hz to 16 kHz with a windowed-sinc low-pass so nothing above 8 kHz folds back. */
    private fun resample(x: FloatArray): FloatArray {
        val n = (x.size.toLong() * RATE / 22050).toInt()
        return FloatArray(n) { i ->
            // 441 input samples per 320 output samples, so the filter phase repeats every 320 outputs.
            val k0 = (i.toLong() * 441 / 320).toInt()
            val taps = TABLE[i % 320]
            var acc = 0f
            for (j in 0 until 2 * HALF) {
                val k = k0 - HALF + 1 + j
                if (k in x.indices) acc += x[k] * taps[j]
            }
            acc
        }
    }

    companion object {
        private const val TAG = "BrickDebug"
        private const val RATE = 16000
        private const val WORKERS = 6
        private const val HALF = 24
        private val TABLE: Array<FloatArray> = Array(320) { ph ->
            val frac = (ph * 441 % 320) / 320.0
            val c = 7600.0 / 22050 // cutoff as a fraction of the input rate
            val h = DoubleArray(2 * HALF) { j ->
                val d = frac + HALF - 1 - j
                val sinc = if (d == 0.0) 2 * c else sin(2 * PI * c * d) / (PI * d)
                sinc * (0.42 + 0.5 * cos(PI * d / HALF) + 0.08 * cos(2 * PI * d / HALF))
            }
            val sum = h.sum()
            FloatArray(2 * HALF) { (h[it] / sum).toFloat() }
        }
    }
}

/** The old bank from files: one IMA ADPCM blob (4 bits per sample, low nibble first, each word from a fresh
 * predictor) and index.tsv lines of "word, byte offset, sample count", at 22,050 Hz. */
private class FileAdpcmWords(dir: File) : WordSource {
    private val blob = RandomAccessFile(File(dir, "words.adpcm"), "r")
    private val index: Map<String, LongArray> = File(dir, "index.tsv").readLines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 3) p[0] to longArrayOf(p[1].toLong(), p[2].toLong()) else null
    }.toMap()

    override val sampleRate = 22050
    override fun contains(word: String) = word in index

    override fun get(word: String): FloatArray? {
        val e = index[word] ?: return null
        val samples = e[1].toInt()
        val b = ByteArray((samples + 1) / 2)
        synchronized(blob) { blob.seek(e[0]); blob.readFully(b) }
        return decode(b, samples)
    }

    private companion object {
        private val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767,
        )
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

        fun decode(b: ByteArray, samples: Int): FloatArray {
            val out = FloatArray(samples)
            var pred = 0
            var idx = 0
            for (i in 0 until samples) {
                val byte = b[i shr 1].toInt() and 0xFF
                val code = if (i and 1 == 0) byte and 0x0F else byte shr 4
                val step = STEPS[idx]
                var vp = step shr 3
                if (code and 4 != 0) vp += step
                if (code and 2 != 0) vp += step shr 1
                if (code and 1 != 0) vp += step shr 2
                pred = (if (code and 8 != 0) pred - vp else pred + vp).coerceIn(-32768, 32767)
                idx = (idx + INDEX[code]).coerceIn(0, 88)
                out[i] = pred / 32768f
            }
            return out
        }
    }
}
