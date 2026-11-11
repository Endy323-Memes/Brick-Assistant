package com.brick.assistant.voice

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.system.Os

/** Real David word recordings stored uncompressed in the APK (assets/david_words). */
class AssetWordSource(context: Context) : AdpcmWordSource() {
    private val assets = context.applicationContext.assets
    private val fd: AssetFileDescriptor? by lazy {
        try { assets.openFd("david_words/words.adpcm") } catch (e: Exception) { null }
    }

    override fun readIndex(): String = try {
        assets.open("david_words/index.tsv").bufferedReader().use { it.readText() }
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
}
