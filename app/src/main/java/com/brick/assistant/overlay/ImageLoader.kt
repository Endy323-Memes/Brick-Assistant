package com.brick.assistant.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Loads a readout's picture (a search result's image) off the main thread, shrunk to fit, with a small cache. */
object ImageLoader {
    private val cache = LruCache<String, Bitmap>(8)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun cached(url: String): Bitmap? = cache.get(url)

    /** [done] runs on the main thread with the picture, or null when it couldn't be loaded. */
    fun load(url: String, done: (Bitmap?) -> Unit) {
        cache.get(url)?.let { done(it); return }
        pool.execute {
            val b = fetch(url)
            if (b != null) cache.put(url, b)
            main.post { done(b) }
        }
    }

    /** Blocking; call off the main thread. */
    fun fetch(url: String, maxSide: Int = 720): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "BrickAssistant/1.0 (Android personal assistant app)")
        val bytes = if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        c.disconnect()
        bytes?.let {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) {
        null
    }
}
