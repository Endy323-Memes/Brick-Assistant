package com.brick.assistant.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import com.brick.assistant.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Optional connection to the user's Spotify account (Spotify Web API, Authorization Code + PKCE, no client
 * secret). Lets Brick find the user's own playlists by name and use Spotify's search for exact tracks,
 * albums and artists. Read-only scopes; tokens stay in Brick's private storage.
 */
object SpotifyAccount {
    const val REDIRECT_URI = "brick://spotify-callback"
    private const val PREFS = "spotify_account"
    // user-library-modify: "I like this song" saves to your Liked Songs.
    // playlist-modify-*: "add this song to my <name> playlist".
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read user-library-modify " +
        "playlist-modify-public playlist-modify-private"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(c: Context): String = prefs(c).getString("client_id", "").orEmpty()
    fun setClientId(c: Context, id: String) = prefs(c).edit().putString("client_id", id.trim()).apply()
    fun isConnected(c: Context) = prefs(c).getString("refresh", null) != null
    fun displayName(c: Context): String? = prefs(c).getString("name", null)
    fun userId(c: Context): String? = prefs(c).getString("user_id", null)?.takeIf { it.isNotBlank() }

    fun disconnect(c: Context) = prefs(c).edit().remove("access").remove("refresh").remove("expires").remove("name").apply()

    /** Opens Spotify's sign-in page in the browser. Returns false if no client ID is set. */
    fun startConnect(c: Context): Boolean {
        val id = clientId(c)
        if (id.isBlank()) return false
        val verifier = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { b64(it) }
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        prefs(c).edit().putString("verifier", verifier).apply()
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()
        c.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Exchanges the sign-in [code] for tokens. Returns an error message, or null on success. */
    suspend fun finishConnect(c: Context, code: String): String? = withContext(Dispatchers.IO) {
        val verifier = prefs(c).getString("verifier", null) ?: return@withContext "Sign-in expired. Try Connect again."
        val res = token(
            c,
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId(c), "code_verifier" to verifier,
        ) ?: return@withContext "Spotify refused the sign-in. Check the client ID and the redirect URI ($REDIRECT_URI)."
        save(c, res)
        api(c, "https://api.spotify.com/v1/me")?.let { me ->
            prefs(c).edit().putString("name", me.optString("display_name")).putString("user_id", me.optString("id")).apply()
        }
        null
    }

    private fun save(c: Context, o: JSONObject) {
        val e = prefs(c).edit()
            .putString("access", o.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000)
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh", it) }
        e.apply()
    }

    private fun accessToken(c: Context): String? {
        val p = prefs(c)
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires", 0)) return access
        val refresh = p.getString("refresh", null) ?: return null
        val res = token(c, "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(c)) ?: return null
        save(c, res)
        return res.getString("access_token")
    }

    private fun token(c: Context, vararg form: Pair<String, String>): JSONObject? = try {
        val body = form.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val ok = conn.responseCode == 200
        val text = (if (ok) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        if (ok && text != null) JSONObject(text) else null
    } catch (e: Exception) {
        null
    }

    /** GET a Web API endpoint as the user. */
    fun api(c: Context, url: String): JSONObject? {
        val t = accessToken(c) ?: return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $t")
            val code = conn.responseCode
            val text = if (code == 200) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            if (code != 200) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty().take(200)
                android.util.Log.w("BrickSpotify", "api $code for ${url.substringBefore('?')}: $err")
            }
            conn.disconnect()
            text?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Saves [trackId] to the user's Liked Songs. Returns the HTTP code (403 = reconnect for permission), or null. */
    fun saveTrack(c: Context, trackId: String): Int? {
        val t = accessToken(c) ?: return null
        return try {
            val conn = URL("https://api.spotify.com/v1/me/tracks?ids=$trackId").openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $t")
            conn.setRequestProperty("Content-Length", "0")
            conn.doOutput = true
            conn.outputStream.use { }
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (e: Exception) {
            null
        }
    }

    /** Adds [trackUri] to playlist [playlistId]. Returns the HTTP code (403 = reconnect for permission), or null. */
    fun addToPlaylist(c: Context, playlistId: String, trackUri: String): Int? {
        val t = accessToken(c) ?: return null
        fun post(path: String): Int? = try {
            val conn = URL("https://api.spotify.com/v1/playlists/$playlistId/$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $t")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("{\"uris\":[\"$trackUri\"]}".toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (e: Exception) {
            null
        }
        // Newer API path first, then the older one.
        val first = post("items")
        return if (first == 404 || first == 405 || first == null) post("tracks") ?: first else first
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

/** Receives Spotify's sign-in redirect (brick://spotify-callback?code=...). */
class SpotifyAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val msg = when {
                code != null -> SpotifyAccount.finishConnect(app, code)?.let { it } ?: "Spotify connected" +
                    (SpotifyAccount.displayName(app)?.let { " as $it" } ?: "") + "."
                else -> "Spotify sign-in was cancelled${error?.let { " ($it)" } ?: ""}."
            }
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}
