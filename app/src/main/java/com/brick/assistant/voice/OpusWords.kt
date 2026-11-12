package com.brick.assistant.voice

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaFormat
import android.system.Os
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * David word library as 16 kHz mono Opus (assets/david_words/words16.opus + index16.tsv).
 * Each word is its own little Opus stream started from a fresh encoder: packets stored as
 * [2-byte little-endian length][packet]. index16.tsv starts with "#opus\t<rate>\t<delay>" (delay = encoder
 * lookahead in output samples to drop), then lines of "word, byte offset, byte length, sample count".
 */
abstract class OpusWordSource : WordSource {
    private class Entry(val offset: Long, val bytes: Int, val samples: Int)

    private var rate = 16000
    private var delay = 0
    private val index: Map<String, Entry> by lazy {
        val m = HashMap<String, Entry>(32768)
        for (line in readIndex().lineSequence()) {
            val p = line.split('\t')
            if (p[0] == "#opus" && p.size >= 3) {
                rate = p[1].toInt(); delay = p[2].toInt()
            } else if (p.size == 4) {
                m[p[0]] = Entry(p[1].toLong(), p[2].toInt(), p[3].toInt())
            }
        }
        m
    }

    override val sampleRate: Int get() { index; return rate }

    protected abstract fun readIndex(): String
    protected abstract fun readBytes(offset: Long, length: Int): ByteArray?

    override fun contains(word: String) = word in index

    override fun get(word: String): FloatArray? {
        val e = index[word] ?: return null
        val b = readBytes(e.offset, e.bytes) ?: return null
        return synchronized(this) { OpusCodec.decode(b, rate, delay, e.samples) }
    }
}

/** Real David word recordings stored as Opus, uncompressed inside the APK. */
class AssetOpusWordSource(context: Context) : OpusWordSource() {
    private val assets = context.applicationContext.assets
    private val fd: AssetFileDescriptor? by lazy {
        try { assets.openFd("david_words/words16.opus") } catch (e: Exception) { null }
    }

    override fun readIndex(): String = try {
        assets.open("david_words/index16.tsv").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    override fun readBytes(offset: Long, length: Int): ByteArray? {
        val afd = fd ?: return null
        val out = ByteArray(length)
        var done = 0
        return try {
            while (done < length) {
                val r = Os.pread(afd.fileDescriptor, out, done, length - done, afd.startOffset + offset + done)
                if (r <= 0) break
                done += r
            }
            if (done == length) out else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun available(context: Context) = try {
            context.assets.openFd("david_words/words16.opus").close(); true
        } catch (e: Exception) {
            false
        }
    }
}

/** Opus through Android's own codecs, one short word stream at a time. */
object OpusCodec {
    private const val TIMEOUT = 10_000L

    private fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).also { it.flip() }

    /** OpusHead for mono with no pre-skip (the delay is dropped by hand so it is exact). */
    private fun head(rate: Int): ByteBuffer {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray()).put(1).put(1).putShort(0).putInt(rate).putShort(0).put(0)
        b.flip()
        return b
    }

    private var decoder: MediaCodec? = null
    private var decoderRate = 0
    private var outRate = 48000

    private fun create(): MediaCodec {
        // The in-process decoder skips a trip to the media process for every word.
        try { return MediaCodec.createByCodecName("c2.android.inproc.opus.decoder") } catch (e: Exception) {}
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    }

    private fun decoderFor(rate: Int): MediaCodec {
        val d = decoder
        if (d != null && decoderRate == rate) { d.flush(); return d }
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        f.setByteBuffer("csd-0", head(rate))
        f.setByteBuffer("csd-1", le64(0))
        f.setByteBuffer("csd-2", le64(80_000_000))
        val c = d?.apply { stop() } ?: create()
        decoder = c
        decoderRate = rate
        c.configure(f, null, null, 0)
        c.start()
        return c
    }

    fun release() {
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }

    /** Splits a word blob back into its packets. */
    fun packets(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 2 <= b.size) {
            val n = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            if (i + n > b.size) break
            out += b.copyOfRange(i, i + n)
            i += n
        }
        return out
    }

    /** Decodes one word to [samples] floats at [rate], dropping the first [delay] samples. Not thread-safe. */
    fun decode(b: ByteArray, rate: Int, delay: Int, samples: Int): FloatArray? = try {
        decodeWith(decoderFor(rate), packets(b), rate, delay, samples)
    } catch (e: Exception) {
        release()
        null
    }

    /**
     * Feeds the packets and keeps every (output rate / [rate])th sample after the delay. It stops as soon as the
     * word is complete rather than sending end-of-stream, so the same decoder can be flushed and reused.
     */
    private fun decodeWith(c: MediaCodec, packets: List<ByteArray>, rate: Int, delay: Int, samples: Int): FloatArray {
        val out = FloatArray(samples)
        var got = 0
        var pos = 0L // position in output-rate samples
        val info = MediaCodec.BufferInfo()
        var next = 0
        var pts = 0L
        var idle = 0
        while (got < samples) {
            if (next < packets.size) {
                val ib = c.dequeueInputBuffer(0)
                if (ib >= 0) {
                    val p = packets[next++]
                    c.getInputBuffer(ib)!!.apply { clear(); put(p) }
                    c.queueInputBuffer(ib, 0, p.size, pts, 0)
                    pts += 20_000
                }
            }
            val ob = c.dequeueOutputBuffer(info, if (next < packets.size) 0 else TIMEOUT)
            if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outRate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else if (ob >= 0) {
                idle = 0
                if (info.size > 0) {
                    val s = c.getOutputBuffer(ob)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val k = (outRate / rate).coerceAtLeast(1)
                    val skip = delay.toLong() * k
                    val n = info.size / 2
                    // First output-rate sample at or after skip that lands on a kept position.
                    var q = if (pos >= skip) pos + ((k - (pos - skip) % k) % k) else skip
                    while (q < pos + n) {
                        val i = ((q - skip) / k).toInt()
                        if (i >= samples) break
                        out[i] = s.get((q - pos).toInt()) / 32768f
                        got = i + 1
                        q += k
                    }
                    pos += n
                }
                c.releaseOutputBuffer(ob, false)
            } else if (next >= packets.size && ++idle > 3) {
                break // ran out of audio: keep what we have (the rest stays silent)
            }
        }
        return out
    }
}
