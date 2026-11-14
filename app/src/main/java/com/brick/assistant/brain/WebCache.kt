package com.brick.assistant.brain

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Web answers saved on the phone (in Brick's cache folder, which Android may clear when space runs low), so a
 * repeat question answers right away instead of waiting on the network. Each answer is kept for a time that
 * suits it: prices an hour, news 10 minutes, everything else (lookups, lists, ratings, where to watch) a day.
 * When the network fails, a copy up to a week old is used. Only public answers are saved, keyed by their address;
 * request headers (API tokens) are never written.
 */
object WebCache {
    const val NEWS_MS = 10 * 60_000L
    const val PRICE_MS = 60 * 60_000L
    const val LOOKUP_MS = 24 * 3600_000L
    const val STALE_MS = 7 * 24 * 3600_000L
    private const val MAX_BYTES = 24L * 1024 * 1024

    /** Hosts whose answers are prices (shorter saved time). */
    private val PRICE_HOSTS = listOf(
        "store.steampowered.com", "cheapshark.com", "api.ec.nintendo.com", "displaycatalog.mp.microsoft.com",
        "cheapcharts.com", "googleapis.com/books", "itunes.apple.com",
    )

    @Volatile private var dir: File? = null
    /** Brick's "Save web answers" setting. */
    @Volatile var enabled = true
    @Volatile private var writes = 0

    fun init(context: Context) {
        dir = File(context.cacheDir, "web").apply { mkdirs() }
    }

    fun ttl(url: String): Long = when {
        url.contains("news.google.com") -> NEWS_MS
        PRICE_HOSTS.any { url.contains(it) } -> PRICE_MS
        else -> LOOKUP_MS
    }

    private fun fileFor(url: String): File? {
        val d = dir ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "$hash.txt")
    }

    /** The saved answer for [url] when it's younger than [maxAge], else null. */
    fun read(url: String, maxAge: Long): String? {
        if (!enabled) return null
        val f = fileFor(url) ?: return null
        return try {
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge) null else f.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun write(url: String, body: String) {
        if (!enabled) return
        val f = fileFor(url) ?: return
        try {
            val tmp = File(f.parentFile, f.name + "." + Thread.currentThread().id + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            if (++writes % 40 == 0) trim()
        } catch (_: Exception) { }
    }

    /** Deletes answers older than a week, then the oldest until the folder is under its size cap. */
    fun trim() {
        val files = dir?.listFiles()?.sortedBy { it.lastModified() } ?: return
        val now = System.currentTimeMillis()
        var total = 0L
        val kept = ArrayList<File>()
        for (f in files) if (now - f.lastModified() > STALE_MS) f.delete() else { kept += f; total += f.length() }
        for (f in kept) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** Deletes every saved answer (when the setting is turned off). */
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }
}
