package com.brick.assistant.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Notification access lets Brick control other apps' media sessions (Spotify) and press the Pause /
 * Resume / Cancel buttons on the Clock app's timer notification. Brick doesn't store notifications.
 */
class BrickNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: BrickNotificationListener? = null
    }
}

/**
 * Plays music in Spotify.
 *
 * Spotify only opens its search screen for Android's standard "play from search" request, and it
 * ignores search commands sent to its media session by apps it doesn't recognize. Opening a Spotify
 * link for the exact track does start playback, so Brick finds the track's Spotify ID with a quick web
 * search, confirms the title with Spotify's public page-info endpoint, and opens that link.
 */
object SpotifyPlayer {
    const val PACKAGE = "com.spotify.music"
    private const val TAG = "BrickSpotify"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) BrickAssistant"

    enum class Kind(val path: String) { TRACK("track"), ALBUM("album"), ARTIST("artist"), PLAYLIST("playlist") }

    /** Spotify's own link for the Liked Songs collection. */
    const val LIKED_SONGS = "spotify:collection:tracks"

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isConnected(context: Context) = SpotifyAccount.isConnected(context)

    /**
     * The Spotify URI for [title] (+ optional [artist]), or null when nothing convincing was found. Uses the
     * connected Spotify account's search when available, otherwise a web lookup checked against Spotify.
     */
    suspend fun resolve(kind: Kind, title: String, artist: String?, context: Context? = null): String? {
        if (context != null && SpotifyAccount.isConnected(context)) apiSearch(context, kind, title, artist)?.let { return it }
        return webResolve(kind, title, artist)
    }

    /** One of the user's playlists by name (needs a connected account), else a public playlist. */
    suspend fun resolvePlaylist(context: Context, name: String): String? {
        ownPlaylist(context, name, 0.6)?.let { return it }
        return resolve(Kind.PLAYLIST, name, null, context)
    }

    /** One of the user's own playlists whose name matches [name] at least [minScore] (0..1), or null. */
    suspend fun ownPlaylist(context: Context, name: String, minScore: Double): String? {
        if (SpotifyAccount.isConnected(context)) {
            val mine = withContext(Dispatchers.IO) {
                var best: Pair<String, Double>? = null
                var offset = 0
                while (offset < 400) {
                    val page = SpotifyAccount.api(context, "https://api.spotify.com/v1/me/playlists?limit=50&offset=$offset") ?: break
                    val items = page.optJSONArray("items") ?: break
                    for (i in 0 until items.length()) {
                        val p = items.optJSONObject(i) ?: continue
                        val s = similarity(p.optString("name"), name)
                        if (best == null || s > best!!.second) best = p.optString("uri") to s
                    }
                    if (page.isNull("next") || items.length() == 0) break
                    offset += 50
                }
                Log.d(TAG, "best own playlist for '$name': $best")
                best?.takeIf { it.second >= minScore }?.first
            }
            if (mine != null) return mine
        }
        return null
    }

    /** One of your own (or collaborative) playlists by name: (id, name), or null. Needs a connected account. */
    suspend fun ownEditablePlaylist(context: Context, name: String, minScore: Double = 0.6): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!SpotifyAccount.isConnected(context)) return@withContext null
        val me = SpotifyAccount.userId(context)
        var best: Triple<String, String, Double>? = null
        var offset = 0
        while (offset < 400) {
            val page = SpotifyAccount.api(context, "https://api.spotify.com/v1/me/playlists?limit=50&offset=$offset") ?: break
            val items = page.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val owner = p.optJSONObject("owner")?.optString("id")
                if (me != null && owner != me && !p.optBoolean("collaborative")) continue
                val s = similarity(p.optString("name"), name)
                if (best == null || s > best.third) best = Triple(p.optString("id"), p.optString("name"), s)
            }
            if (page.isNull("next") || items.length() == 0) break
            offset += 50
        }
        best?.takeIf { it.third >= minScore }?.let { it.first to it.second }
    }

    /** Spotify's URI for a track ("spotify:track:..."), from the connected account's search. */
    suspend fun trackUri(context: Context, title: String, artist: String?): String? = apiSearch(context, Kind.TRACK, title, artist)

    /** Spotify's ID for a track (to like it), from the connected account's search. */
    suspend fun trackId(context: Context, title: String, artist: String?): String? =
        apiSearch(context, Kind.TRACK, title, artist)?.substringAfterLast(':')?.takeIf { it.isNotBlank() }

    private suspend fun apiSearch(context: Context, kind: Kind, title: String, artist: String?): String? = withContext(Dispatchers.IO) {
        val q = if (artist.isNullOrBlank() || kind == Kind.ARTIST) title else "$title artist:$artist"
        val o = SpotifyAccount.api(context, "https://api.spotify.com/v1/search?type=${kind.path}&limit=8&q=" + URLEncoder.encode(q, "UTF-8"))
            ?: return@withContext null
        val items = o.optJSONObject(kind.path + "s")?.optJSONArray("items") ?: return@withContext null
        var best: String? = null
        var bestScore = -1.0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            var s = similarity(it.optString("name"), title)
            val artists = it.optJSONArray("artists")
            if (!artist.isNullOrBlank() && artists != null &&
                (0 until artists.length()).any { a -> similarity(artists.optJSONObject(a)?.optString("name").orEmpty(), artist) >= 0.6 }
            ) s += 0.2
            if (s > bestScore) { bestScore = s; best = it.optString("uri") }
        }
        Log.d(TAG, "api ${kind.path} '$q' -> $best ($bestScore)")
        if (bestScore >= 0.5) best else null
    }

    private suspend fun webResolve(kind: Kind, title: String, artist: String?): String? = withContext(Dispatchers.IO) {
        val q = listOfNotNull(title, artist).joinToString(" ") + " spotify " + kind.path
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")) ?: return@withContext null
        val ids = Regex("open\\.spotify\\.com/${kind.path}/([A-Za-z0-9]{22})")
            .findAll(Uri.decode(html)).map { it.groupValues[1] }.distinct().take(4).toList()
        Log.d(TAG, "candidates for '$q': $ids")
        if (ids.isEmpty()) return@withContext null
        // Pick the candidate whose real Spotify title matches what was asked for.
        var best: String? = null
        var bestScore = -1.0
        for (id in ids) {
            val name = oembedTitle(kind, id) ?: continue
            val score = similarity(name, if (kind == Kind.ARTIST) (artist ?: title) else title)
            Log.d(TAG, "  $id = '$name' ($score)")
            if (score > bestScore) { bestScore = score; best = id }
            if (score >= 0.99) break
        }
        val pick = if (bestScore >= 0.5) best else null
        pick?.let { "spotify:${kind.path}:$it" }
    }

    /** Opens [uri] in Spotify; if it doesn't start on its own, nudges Spotify's session to play. */
    suspend fun open(context: Context, uri: String): Boolean {
        // Album, playlist, artist and Liked Songs links only show a page in Spotify, but a track link plays.
        // So open the collection's first track *in the collection's context*: the whole collection plays.
        val link = withContext(Dispatchers.IO) { playableLink(context, uri) }
        Log.d(TAG, "opening $link")
        val ok = withContext(Dispatchers.Main) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (e: Exception) {
                false
            }
        }
        if (!ok) return false
        if (hasAccess(context)) {
            delay(3500)
            val s = findSession(context)
            if (s != null && s.playbackState?.state != PlaybackState.STATE_PLAYING) {
                Log.d(TAG, "not playing after opening $uri; sending play")
                withContext(Dispatchers.Main) { s.transportControls.play() }
            }
        }
        return true
    }

    private fun findSession(context: Context): MediaController? = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, BrickNotificationListener::class.java))
            .firstOrNull { it.packageName == PACKAGE }
    } catch (e: SecurityException) {
        null
    }

    /**
     * A link that starts playback: the track itself, or a *random* track of the album, playlist, artist or
     * Liked Songs, opened in that collection's context so the rest of the collection follows.
     */
    private fun playableLink(context: Context, uri: String): String {
        val parts = uri.split(":")
        val connected = SpotifyAccount.isConnected(context)
        fun inContext(trackId: String?, ctx: String) =
            trackId?.let { "https://open.spotify.com/track/$it?context=" + URLEncoder.encode(ctx, "UTF-8") } ?: uri
        return when {
            parts.size == 3 && parts[1] == "track" -> uri
            uri == LIKED_SONGS -> {
                val user = SpotifyAccount.userId(context)
                val pick = if (connected) randomFromPaged(context, "https://api.spotify.com/v1/me/tracks") else null
                if (user != null && pick != null) inContext(pick, "spotify:user:$user:collection") else uri
            }
            parts.size == 3 && parts[1] in setOf("album", "playlist", "artist") -> {
                val (type, id) = parts[1] to parts[2]
                val fromApi: String? = if (connected) when (type) {
                    "album" -> randomFromPaged(context, "https://api.spotify.com/v1/albums/$id/tracks")
                    "playlist" -> randomFromPaged(context, "https://api.spotify.com/v1/playlists/$id/items")
                        ?: randomFromPaged(context, "https://api.spotify.com/v1/playlists/$id/tracks")
                    else -> SpotifyAccount.api(context, "https://api.spotify.com/v1/artists/$id/top-tracks?market=from_token")
                        ?.optJSONArray("tracks")?.let { a -> if (a.length() == 0) null else a.optJSONObject((0 until a.length()).random())?.optString("id") }
                } else null
                // Without an account, the public page lists the tracks: pick one at random.
                val pick = fromApi?.takeIf { it.isNotBlank() } ?: get("https://open.spotify.com/$type/$id")?.let { html ->
                    Regex("music:song\" content=\"https://open\\.spotify\\.com/track/([A-Za-z0-9]{22})").findAll(html)
                        .map { it.groupValues[1] }.distinct().toList().randomOrNull()
                        ?: Regex("open\\.spotify\\.com/track/([A-Za-z0-9]{22})").find(html)?.groupValues?.get(1)
                } ?: if (type == "artist") artistTrackFromWeb(id) else null
                Log.d(TAG, "random start for $uri: $pick")
                inContext(pick, uri)
            }
            else -> uri
        }
    }

    /** A random track ID from a paged Web API list (reads the total, then one item at a random offset). */
    private fun randomFromPaged(context: Context, base: String): String? {
        fun idOf(o: JSONObject?): String? =
            (o?.optJSONObject("item") ?: o?.optJSONObject("track") ?: o)?.optString("id")?.takeIf { it.isNotBlank() }
        val head = SpotifyAccount.api(context, "$base?limit=1") ?: return null
        val total = head.optInt("total", 0)
        if (total <= 1) return head.optJSONArray("items")?.optJSONObject(0)?.let { idOf(it) }
        val offset = (0 until total).random()
        return SpotifyAccount.api(context, "$base?limit=1&offset=$offset")?.optJSONArray("items")?.optJSONObject(0)?.let { idOf(it) }
            ?: head.optJSONArray("items")?.optJSONObject(0)?.let { idOf(it) }
    }

    /** Any track by the artist (found with a web lookup), to start the artist's songs. */
    private fun artistTrackFromWeb(artistId: String): String? {
        val name = oembedTitle(Kind.ARTIST, artistId) ?: return null
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("$name spotify track", "UTF-8")) ?: return null
        return Regex("open\\.spotify\\.com/track/([A-Za-z0-9]{22})").find(Uri.decode(html))?.groupValues?.get(1)
    }

    private fun oembedTitle(kind: Kind, id: String): String? {
        val url = "https://open.spotify.com/oembed?url=" + URLEncoder.encode("https://open.spotify.com/${kind.path}/$id", "UTF-8")
        return try { get(url)?.let { JSONObject(it).optString("title").ifBlank { null } } } catch (e: Exception) { null }
    }

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]|\\s-\\s.*$"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("^the\\s+"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** 0..1 word-overlap similarity, 1 for an exact (normalized) match. */
    fun similarity(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.replace(" ", "") == y.replace(" ", "")) return 0.97
        val xs = x.split(" ").toSet()
        val ys = y.split(" ").toSet()
        return xs.intersect(ys).size.toDouble() / (xs union ys).size
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        c.disconnect()
        body
    } catch (e: Exception) {
        null
    }
}