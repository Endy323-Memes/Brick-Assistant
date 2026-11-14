package com.brick.assistant.media

import com.brick.assistant.brain.Patterns

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.pm.PackageManager
import com.brick.assistant.brain.DriverBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "I like this song": finds what's playing and which app plays it (from the phone's media sessions), then
 * adds it to that app's likes: Driver's favorites, Spotify's Liked Songs, or any app's own like / heart button.
 */
object LikeSong {
    data class Playing(val pkg: String, val app: String, val title: String?, val artist: String?, val controller: MediaController)

    /** The app playing now (or the most recent one paused), or null. Needs notification access. */
    fun current(context: Context): Playing? {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = try {
            msm.getActiveSessions(ComponentName(context, BrickNotificationListener::class.java))
        } catch (e: SecurityException) {
            return null
        }
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null } ?: return null
        val md = c.metadata
        val app = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            c.packageName
        }
        return Playing(
            c.packageName, app,
            md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
            (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() },
            c,
        )
    }

    private val likeWords = Patterns.get("(?i)\\b(like|love|heart|favou?rite|thumbs? up|save|add to (?:liked|library|collection|your))\\b")
    private val unlikeWords = Patterns.get("(?i)\\b(unlike|dislike|remove|un-?heart|unfavou?rite|thumbs? down|unsave|not interested)\\b")

    /** The app's own "like" button in its media controls, if it has one. */
    private fun pressLikeButton(p: Playing): Boolean {
        val action = p.controller.playbackState?.customActions.orEmpty().firstOrNull { a ->
            val name = "${a.name} ${a.action}"
            likeWords.containsMatchIn(name) && !unlikeWords.containsMatchIn(name)
        } ?: return false
        return try {
            p.controller.transportControls.sendCustomAction(action, action.extras)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** A heart or thumbs-up rating, for apps that rate songs that way. */
    private fun rate(p: Playing): Boolean = try {
        when (p.controller.ratingType) {
            Rating.RATING_HEART -> { p.controller.transportControls.setRating(Rating.newHeartRating(true)); true }
            Rating.RATING_THUMB_UP_DOWN -> { p.controller.transportControls.setRating(Rating.newThumbRating(true)); true }
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    /** Adds the playing song to its app's likes and returns what the robot says. */
    suspend fun like(context: Context): String = withContext(Dispatchers.IO) {
        val p = current(context) ?: return@withContext "I don't see anything playing right now."
        val what = listOfNotNull(p.title, p.artist?.let { "by $it" }).joinToString(" ").ifBlank { "this song" }

        if (p.pkg == DriverBridge.PACKAGE) {
            val reply = DriverBridge.send(context, DriverBridge.favorite())
            return@withContext when {
                reply == null -> "I couldn't reach Driver."
                reply.status == DriverBridge.STATUS_OK || reply.status == DriverBridge.STATUS_NOT_FOUND -> reply.message.ifBlank { "Added $what to your favorites in Driver." }
                else -> "Driver couldn't add it to your favorites. Update Driver so Brick can like songs there."
            }
        }

        if (p.pkg == SpotifyPlayer.PACKAGE && SpotifyAccount.isConnected(context) && p.title != null) {
            val id = SpotifyPlayer.trackId(context, p.title, p.artist)
            if (id != null) {
                when (SpotifyAccount.saveTrack(context, id)) {
                    200, 201, 204 -> return@withContext "Added $what to your Liked Songs on Spotify."
                    401, 403 -> if (!pressLikeButton(p)) {
                        return@withContext "Reconnect Spotify in Brick's Settings once, so I'm allowed to add to your Liked Songs."
                    } else return@withContext "Liked $what on Spotify."
                }
            }
        }

        if (pressLikeButton(p) || rate(p)) return@withContext "Liked $what in ${p.app}."
        "${p.app} doesn't let me like songs from here."
    }

    /** Adds the playing song to the named playlist in the app playing it, and returns what the robot says. */
    suspend fun addToPlaylist(context: Context, playlist: String): String = withContext(Dispatchers.IO) {
        val p = current(context) ?: return@withContext "I don't see anything playing right now."
        val what = p.title ?: "this song"

        if (p.pkg == DriverBridge.PACKAGE) {
            val reply = DriverBridge.send(context, DriverBridge.addToPlaylist(playlist))
            return@withContext when {
                reply == null -> "I couldn't reach Driver."
                reply.status == DriverBridge.STATUS_OK || reply.status == DriverBridge.STATUS_NOT_FOUND -> reply.message.ifBlank { "Added $what to $playlist in Driver." }
                else -> "Driver couldn't add it. Update Driver so Brick can add songs to playlists there."
            }
        }

        if (p.pkg == SpotifyPlayer.PACKAGE) {
            if (!SpotifyAccount.isConnected(context)) return@withContext "Connect Spotify in Brick's Settings so I can add songs to your playlists."
            if (p.title == null) return@withContext "I can't tell which song Spotify is playing."
            val target = SpotifyPlayer.ownEditablePlaylist(context, playlist)
                ?: return@withContext "I couldn't find a playlist of yours called $playlist on Spotify."
            val uri = SpotifyPlayer.trackUri(context, p.title, p.artist)
                ?: return@withContext "I couldn't find $what on Spotify."
            return@withContext when (SpotifyAccount.addToPlaylist(context, target.first, uri)) {
                200, 201 -> "Added $what to your ${target.second} playlist on Spotify."
                401, 403 -> "Reconnect Spotify in Brick's Settings once, so I'm allowed to edit your playlists."
                else -> "Spotify didn't accept that. Try again in a moment."
            }
        }
        "${p.app} doesn't let me add songs to playlists from here."
    }
}