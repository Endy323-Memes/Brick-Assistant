package com.brick.assistant.brain

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/** How finely the lyrics are timed. */
enum class LyricsSync { PLAIN, LINE, WORD }

/** Where the lyrics came from. */
enum class LyricsSource(val label: String) { DRIVER("Driver"), PHONE("lyric file on the phone"), LRCLIB("lrclib.net") }

data class LyricWord(val timeMs: Long, val text: String)

/** One line: [timeMs] is null for plain (unsynced) lyrics; [words] is filled for word-synced ones. */
data class LyricLine(val timeMs: Long?, val text: String, val words: List<LyricWord> = emptyList())

data class LyricsDoc(
    val title: String,
    val artist: String?,
    val lines: List<LyricLine>,
    val source: LyricsSource,
    val instrumental: Boolean = false,
) {
    val sync: LyricsSync = when {
        lines.any { it.words.isNotEmpty() } -> LyricsSync.WORD
        lines.any { it.timeMs != null } -> LyricsSync.LINE
        else -> LyricsSync.PLAIN
    }
}

/**
 * The current song's lyrics, from the best place that has them: Driver's own copy, then a .lrc file on the
 * phone, then lrclib.net (plain, line-synced, or word-synced when the LRC has <mm:ss.xx> word tags).
 */
object Lyrics {
    private val us = Locale.US
    private val LINE_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val WORD_TAG = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val META_TAG = Regex("^\\[(?:ti|ar|al|au|by|length|offset|re|ve|#)[^]]*]\\s*$", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)

    private fun ms(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val f = m.groupValues[3]
        val frac = when (f.length) { 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.take(3).toLong() }
        return min * 60_000 + sec * 1000 + frac
    }

    /**
     * Parses LRC text (or plain text when there are no time tags). Lines with several time tags repeat; enhanced
     * LRC word tags become [LyricWord]s; [offset:] is applied; metadata tags are skipped.
     */
    fun parse(text: String): List<LyricLine> {
        val raw = text.replace("\r", "").lines()
        val offset = raw.firstNotNullOfOrNull { OFFSET_TAG.find(it.trim())?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val timed = raw.any { LINE_TAG.containsMatchIn(it) }
        if (!timed) {
            // Plain lyrics: keep stanza breaks as one blank line, drop the rest of the empty lines.
            val out = ArrayList<LyricLine>()
            for (l in raw.map { it.trim() }) {
                if (META_TAG.matches(l)) continue
                if (l.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) continue
                out += LyricLine(null, l)
            }
            while (out.isNotEmpty() && out.last().text.isEmpty()) out.removeAt(out.lastIndex)
            return out
        }
        val out = ArrayList<LyricLine>()
        for (l in raw) {
            val line = l.trim()
            if (line.isEmpty() || META_TAG.matches(line)) continue
            var rest = line
            val times = ArrayList<Long>()
            while (true) {
                val m = LINE_TAG.find(rest) ?: break
                if (m.range.first != 0) break
                times += ms(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            val words = ArrayList<LyricWord>()
            val tags = WORD_TAG.findAll(rest).toList()
            for ((i, m) in tags.withIndex()) {
                val end = if (i + 1 < tags.size) tags[i + 1].range.first else rest.length
                val w = rest.substring(m.range.last + 1, end).trim()
                if (w.isNotEmpty()) words += LyricWord(ms(m) - offset, w)
            }
            val clean = WORD_TAG.replace(rest, " ").replace(Regex("\\s+"), " ").trim()
            for (t in times) out += LyricLine(t - offset, clean, if (times.size == 1) words else emptyList())
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line playing at [posMs] (the last line that has started), or -1 before the first. */
    fun lineAt(lines: List<LyricLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = lines[mid].timeMs ?: return -1
            if (posMs >= t) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    // ---- Sources

    /** Song names often carry extras the lyric sites don't: "Title (Remastered 2011)", "Title - Radio Edit". */
    fun cleanTitle(t: String) = t
        .replace(Regex("\\s*[(\\[][^)\\]]*\\b(?:remaster(?:ed)?|live|radio edit|edit|version|mono|stereo|deluxe|bonus|explicit|clean|feat\\.?|ft\\.?|with)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+-\\s+(?:\\d{4} )?(?:remaster(?:ed)?|live|radio edit|single version|mono|stereo)\\b.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    fun mainArtist(a: String?) = a?.split(Regex("\\s*(?:,|&| feat\\.? | ft\\.? | x | and )\\s*", RegexOption.IGNORE_CASE))?.firstOrNull()?.trim()?.ifBlank { null }

    /** Driver's own lyrics (read-only "get_lyrics"); null when Driver doesn't have them or can't answer that yet. */
    suspend fun fromDriver(context: Context, title: String, artist: String?): LyricsDoc? {
        if (!DriverBridge.isInstalled(context)) return null
        val reply = DriverBridge.send(context, DriverBridge.getLyrics(title, artist.orEmpty())) ?: return null
        if (reply.status != DriverBridge.STATUS_OK || !LINE_TAG.containsMatchIn(reply.message) && reply.message.lines().size < 3) return null
        val lines = parse(reply.message)
        return if (lines.isEmpty()) null else LyricsDoc(title, artist, lines, LyricsSource.DRIVER)
    }

    /** Folders music apps keep .lrc files in. */
    private fun lyricDirs(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf("Music", "Download", "Lyrics", "Music/Lyrics", "Documents/Lyrics", "Android/media").map { File(root, it) }.filter { it.isDirectory }
    }

    private fun norm(s: String) = s.lowercase(us).replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * A .lrc (or .txt) file on the phone named after the song ("Title.lrc", "Artist - Title.lrc"). Only when Brick
     * has been allowed to read all files; otherwise there's nothing it can see, and this returns null.
     */
    fun fromPhone(title: String, artist: String?): LyricsDoc? {
        if (android.os.Build.VERSION.SDK_INT < 30 || !Environment.isExternalStorageManager()) return null
        val t = norm(cleanTitle(title))
        val a = mainArtist(artist)?.let(::norm)
        if (t.isEmpty()) return null
        val hits = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            val kids = try { dir.listFiles() } catch (e: SecurityException) { null } ?: return
            for (f in kids) {
                if (hits.size > 20) return
                if (f.isDirectory) { if (depth < 4 && !f.name.startsWith(".")) walk(f, depth + 1); continue }
                val ext = f.extension.lowercase(us)
                if (ext != "lrc" && ext != "txt") continue
                val n = norm(f.nameWithoutExtension)
                if (n == t || n.endsWith(" $t") && (a == null || n.contains(a)) || n.startsWith("$t ") && (a == null || n.contains(a))) hits += f
            }
        }
        lyricDirs().forEach { walk(it, 0) }
        // Prefer an exact name, then one with the artist, then a timed file.
        val best = hits.sortedWith(compareByDescending<File> { norm(it.nameWithoutExtension) == t }
            .thenByDescending { a != null && norm(it.nameWithoutExtension).contains(a) }
            .thenByDescending { it.extension.equals("lrc", true) }).firstOrNull() ?: return null
        val lines = try { parse(best.readText()) } catch (e: Exception) { return null }
        return if (lines.isEmpty()) null else LyricsDoc(title, artist, lines, LyricsSource.PHONE)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fromLrclibJson(o: org.json.JSONObject, title: String, artist: String?): LyricsDoc? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val name = o.optString("trackName").ifBlank { title }
        val by = o.optString("artistName").ifBlank { artist.orEmpty() }.ifBlank { null }
        if (o.optBoolean("instrumental")) return LyricsDoc(name, by, emptyList(), LyricsSource.LRCLIB, instrumental = true)
        val lines = parse(synced ?: plain ?: return null)
        return if (lines.isEmpty()) null else LyricsDoc(name, by, lines, LyricsSource.LRCLIB)
    }

    /** lrclib.net: an exact match by title, artist and length first, then its search. */
    fun fromLrclib(title: String, artist: String?, album: String?, durationSec: Int?): LyricsDoc? {
        val t = cleanTitle(title)
        val a = mainArtist(artist)
        val base = "https://lrclib.net/api"
        if (a != null) {
            val q = "track_name=${enc(t)}&artist_name=${enc(a)}" +
                (album?.takeIf { it.isNotBlank() }?.let { "&album_name=${enc(it)}" } ?: "") +
                (durationSec?.takeIf { it > 0 }?.let { "&duration=$it" } ?: "")
            Http.getJsonCached("$base/get?$q")?.let { o -> fromLrclibJson(o, title, artist)?.let { return it } }
        }
        val arr = Http.getJsonArray("$base/search?track_name=${enc(t)}" + (a?.let { "&artist_name=${enc(it)}" } ?: "")) ?: return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        // Closest length wins among the ones with timed lyrics, then plain ones.
        val pick = all.sortedWith(
            compareByDescending<org.json.JSONObject> { it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" } }
                .thenBy { durationSec?.let { d -> kotlin.math.abs(it.optDouble("duration", 0.0) - d) } ?: 0.0 },
        )
        for (o in pick) fromLrclibJson(o, title, artist)?.let { return it }
        return null
    }
}
