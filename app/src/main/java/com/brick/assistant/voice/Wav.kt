package com.brick.assistant.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono floating point audio in the range -1..1. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int)

/** Minimal WAV reader/writer (PCM 8/16/24/32-bit and 32-bit float in, 16-bit mono out). */
object Wav {
    fun read(file: File): PcmAudio? = try { read(file.readBytes()) } catch (e: Exception) { null }

    fun read(bytes: ByteArray): PcmAudio? {
        if (bytes.size < 12) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var format = 1
        var channels = 1
        var rate = 22050
        var bits = 16
        var dataStart = -1
        var dataLen = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt().coerceAtLeast(1)
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
                if (format == 0xFFFE && size >= 26 && body + 26 <= bytes.size) {
                    format = bb.getShort(body + 24).toInt() and 0xFFFF // WAVE_FORMAT_EXTENSIBLE sub-format
                }
            } else if (id == "data") {
                dataStart = body
                // Some engines write 0 / bogus sizes for streamed files: fall back to "rest of file".
                dataLen = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                break
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || rate <= 0) return null

        val bytesPerSample = (bits / 8).coerceAtLeast(1)
        val frameSize = bytesPerSample * channels
        val frames = dataLen / frameSize
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (ch in 0 until channels) {
                val p = dataStart + f * frameSize + ch * bytesPerSample
                acc += when {
                    format == 3 && bits == 32 -> bb.getFloat(p)
                    bits == 16 -> bb.getShort(p) / 32768f
                    bits == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                    bits == 24 -> {
                        val v = (bytes[p].toInt() and 0xFF) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            (bytes[p + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    bits == 32 -> bb.getInt(p) / 2147483648f
                    else -> 0f
                }
            }
            out[f] = acc / channels
        }
        return PcmAudio(out, rate)
    }

    fun write(file: File, audio: PcmAudio) {
        val n = audio.samples.size
        val dataBytes = n * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                      // PCM
        bb.putShort(1)                      // mono
        bb.putInt(audio.sampleRate)
        bb.putInt(audio.sampleRate * 2)     // byte rate
        bb.putShort(2)                      // block align
        bb.putShort(16)                     // bits
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        for (s in audio.samples) {
            bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        file.writeBytes(bb.array())
    }
}
