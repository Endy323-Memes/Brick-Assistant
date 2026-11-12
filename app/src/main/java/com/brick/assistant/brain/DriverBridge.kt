package com.brick.assistant.brain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brick's link to the Driver music player (com.lostf1sh.pixelplayeross.local).
 *
 * Driver exposes a command endpoint (its AssistantControlProvider). Brick sends a small Bundle, and Driver
 * performs the action and replies with a status plus a sentence the robot voice can read out. When Driver
 * isn't running, Brick opens it with the same command and Driver runs it once its library has loaded.
 *
 * The string values below must match Driver's AssistantProtocol.
 */
object DriverBridge {
    const val PACKAGE = "com.lostf1sh.pixelplayeross.local"
    private val COMMAND_URI: Uri = Uri.parse("content://com.lostf1sh.pixelplayeross.local.assistant")
    private const val METHOD_COMMAND = "command"
    private const val ACTION_ASSISTANT_COMMAND = "com.lostf1sh.pixelplayeross.local.ASSISTANT_COMMAND"

    const val STATUS_OK = "ok"
    const val STATUS_NOT_FOUND = "not_found"
    const val STATUS_APP_NOT_RUNNING = "app_not_running"
    const val STATUS_DENIED = "denied"

    data class Reply(val status: String, val message: String)

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Sends [command] to Driver. Returns null if Driver is missing or the call failed. */
    suspend fun send(context: Context, command: Bundle): Reply? = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.call(COMMAND_URI, METHOD_COMMAND, null, command) ?: return@withContext null
            Reply(out.getString("status").orEmpty(), out.getString("message").orEmpty())
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking version of [send] for callers that aren't coroutines. Call it off the main thread. */
    fun sendBlocking(context: Context, command: Bundle): Reply? = try {
        context.contentResolver.call(COMMAND_URI, METHOD_COMMAND, null, command)
            ?.let { Reply(it.getString("status").orEmpty(), it.getString("message").orEmpty()) }
    } catch (e: Exception) {
        null
    }

    /** Opens Driver and hands it [command] (used when Driver isn't running). */
    fun launch(context: Context, command: Bundle): Boolean = try {
        context.startActivity(
            Intent(ACTION_ASSISTANT_COMMAND)
                .setPackage(PACKAGE)
                .putExtras(command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    // ---------------------------------------------------------------- command builders

    private fun command(action: String, fill: Bundle.() -> Unit = {}) = Bundle().apply {
        putString("action", action)
        fill()
    }

    fun play(kind: MediaKind?, query: String, artist: String?) = command("play") {
        putString(
            "kind",
            when (kind) {
                null -> "any"
                MediaKind.AUTO -> "auto"
                MediaKind.SONG -> "song"
                MediaKind.ALBUM -> "album"
                MediaKind.ARTIST -> "artist"
                MediaKind.PLAYLIST -> "playlist"
                MediaKind.LIKED -> "liked"
            },
        )
        putString("query", query)
        if (!artist.isNullOrBlank()) putString("artist", artist)
    }

    private fun effectKey(effect: AudioEffect) = when (effect) {
        AudioEffect.REVERB -> "reverb"
        AudioEffect.LOW_PASS -> "lpf"
        AudioEffect.HIGH_PASS -> "hpf"
        AudioEffect.EQUALIZER -> "eq"
        AudioEffect.SPATIAL -> "spatialize"
    }

    fun effect(effect: AudioEffect, state: EffectState) = command("effect") {
        putString("effect", effectKey(effect))
        putString(
            "state",
            when (state) {
                EffectState.ON -> "on"
                EffectState.OFF -> "off"
                EffectState.TOGGLE -> "toggle"
            },
        )
    }

    fun preset(effect: AudioEffect?, name: String) = command("preset") {
        if (effect != null) putString("effect", effectKey(effect))
        putString("preset", name)
    }

    fun lyrics(song: String?) = command("lyrics") { putString("query", song.orEmpty()) }

    /** Read-only: the lyrics Driver already has for a song, as LRC text (needs a Driver that knows "get_lyrics"). */
    fun getLyrics(title: String, artist: String) = command("get_lyrics") { putString("query", title); putString("artist", artist) }

    fun nowPlaying() = command("now_playing")

    /**
     * Playback controls run by Driver itself, so Driver plays its own artwork animations for them
     * (grow on play, shrink on pause, dissolve on next / previous). [action] is pause, resume, next or previous.
     */
    fun transport(action: String) = command(action)

    /** Adds the playing song to Driver's favorites (needs a Driver build with this command). */
    fun favorite() = command("favorite")

    /** Adds the playing song to the playlist called [name] (needs a Driver build with this command). */
    fun addToPlaylist(name: String) = command("add_to_playlist") { putString("query", name) }
}
