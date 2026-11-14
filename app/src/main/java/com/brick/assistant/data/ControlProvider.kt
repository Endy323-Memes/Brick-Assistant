package com.brick.assistant.data

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.brick.assistant.BrickApp
import com.brick.assistant.overlay.AssistEntryActivity
import com.brick.assistant.wake.WakeWordService

/**
 * Lets Battery (com.brick.edge) change Brick's settings and pull up the floating card.
 * Same caller check as [PaletteProvider]: Brick itself, apps signed with Brick's key, or Battery.
 *
 * `call(URI, method, arg, extras)`:
 *  - `card`      arg "type" (default) or "voice". Opens the floating card ready to type, or listening. → {ok, reason?}
 *  - `wake_word` arg "on" / "off" / "toggle" / null (just ask). → {ok, on, reason?}
 *  - `get`       → {ok, <every key in [KEYS]>: Boolean, wakeStatus: String}
 *  - `set`       arg = a key from [KEYS], extras {"value": Boolean} (missing = toggle). → {ok, key, value, reason?}
 *
 * Any settings change (from here or inside Brick) notifies [URI] and its descendants, e.g. [WAKE_WORD_URI].
 */
class ControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!trusted(ctx)) return null
        val app = ctx.applicationContext as? BrickApp ?: return null
        if (!app.ready) return Bundle().apply { putBoolean("ok", false); putString("reason", "Brick is still starting. Try again.") }
        val settings = app.container.settings
        // Binder calls run with the caller's identity; Brick's own work below must run as Brick.
        val id = Binder.clearCallingIdentity()
        try {
            return when (method) {
                METHOD_CARD -> openCard(ctx, voice = arg == "voice")
                METHOD_ASK_TOKEN -> {
                    val text = arg?.trim()?.takeIf { it.isNotEmpty() } ?: return result(false, "Nothing to ask")
                    result(true).apply { putString("token", askToken(text)) }
                }
                METHOD_WAKE_WORD -> {
                    val now = settings.current.wakeWordEnabled
                    val want = when (arg) { "on" -> true; "off" -> false; "toggle" -> !now; else -> null }
                    if (want == null) result(true).apply { putBoolean("on", now) } else setBool(ctx, settings, KEY_WAKE_WORD, want).apply { putBoolean("on", settings.current.wakeWordEnabled) }
                }
                METHOD_GET -> result(true).apply {
                    val s = settings.current
                    KEYS.forEach { putBoolean(it, read(s, it)) }
                    putString("wakeStatus", WakeWordService.status)
                }
                METHOD_SET -> {
                    val key = arg ?: return result(false, "Missing setting name")
                    if (key !in KEYS) return result(false, "Unknown setting: $key")
                    val value = if (extras?.containsKey("value") == true) extras.getBoolean("value") else !read(settings.current, key)
                    setBool(ctx, settings, key, value)
                }
                else -> null
            }
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    private fun openCard(ctx: Context, voice: Boolean): Bundle {
        if (voice && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return result(false, "Brick needs the microphone permission. Open Brick once to allow it.")
        }
        return try {
            ctx.startActivity(cardIntent(ctx, voice))
            result(true)
        } catch (e: Exception) {
            result(false, "Brick couldn't open its card from the background (${e.javaClass.simpleName}). Start ${AssistEntryActivity::class.java.name} directly.")
        }
    }

    private fun setBool(ctx: Context, settings: SettingsStore, key: String, value: Boolean): Bundle {
        if (key == KEY_WAKE_WORD && value && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return result(false, "Hey Brick needs the microphone permission. Open Brick once to allow it.").apply { putString("key", key); putBoolean("value", false) }
        }
        settings.update {
            when (key) {
                KEY_WAKE_WORD -> it.copy(wakeWordEnabled = value)
                KEY_POCKET_PAUSE -> it.copy(pocketPause = value)
                KEY_HEADSET_MIC -> it.copy(headsetMic = value)
                KEY_OVERLAY -> it.copy(useOverlay = value)
                KEY_BRAND_GLOW -> it.copy(brandGlow = value)
                KEY_ROBOT_VOICE -> it.copy(unitStyle = if (value) "robot" else "natural")
                KEY_FAHRENHEIT -> it.copy(useFahrenheit = value)
                KEY_AUTO_SEND -> it.copy(autoSendTexts = value)
                else -> it
            }
        }
        var reason: String? = null
        if (key == KEY_WAKE_WORD) {
            try {
                if (value) WakeWordService.start(ctx) else WakeWordService.stop(ctx)
            } catch (_: Exception) {
                reason = "Hey Brick is on. It starts listening the next time you open Brick."
            }
        }
        return result(true, reason).apply { putString("key", key); putBoolean("value", read(settings.current, key)) }
    }

    private fun result(ok: Boolean, reason: String? = null) = Bundle().apply {
        putBoolean("ok", ok)
        if (reason != null) putString("reason", reason)
    }

    private fun trusted(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        val pm = ctx.packageManager
        if (runCatching { pm.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)) return true
        return runCatching { pm.getPackagesForUid(uid)?.any { it == PaletteProvider.EDGE_PACKAGE || it == PaletteProvider.HISTORY_PACKAGE } == true }.getOrDefault(false)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.brick.assistant.control"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        val WAKE_WORD_URI: Uri = Uri.parse("content://$AUTHORITY/wake_word")

        const val METHOD_CARD = "card"
        const val METHOD_WAKE_WORD = "wake_word"
        const val METHOD_GET = "get"
        const val METHOD_SET = "set"
        /** arg = a request to run. → {ok, token}: start [AssistEntryActivity] with [AssistEntryActivity.EXTRA_ASK] = token. */
        const val METHOD_ASK_TOKEN = "ask_token"

        /** Requests waiting for their card, by one-time token (a trusted app asked; only it knows the token). */
        private val asks = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

        private fun askToken(text: String): String {
            val now = System.currentTimeMillis()
            asks.entries.removeIf { now - it.value.second > 60_000L }
            val token = java.util.UUID.randomUUID().toString()
            asks[token] = text to now
            return token
        }

        /** The request for [token] (used once, within a minute), or null. */
        fun redeemAsk(token: String): String? {
            val a = asks.remove(token) ?: return null
            return a.first.takeIf { System.currentTimeMillis() - a.second < 60_000L }
        }

        const val KEY_WAKE_WORD = "wakeWord"
        const val KEY_POCKET_PAUSE = "pocketPause"
        const val KEY_HEADSET_MIC = "headsetMic"
        const val KEY_OVERLAY = "overlay"
        const val KEY_BRAND_GLOW = "brandGlow"
        const val KEY_ROBOT_VOICE = "robotVoice"
        const val KEY_FAHRENHEIT = "fahrenheit"
        const val KEY_AUTO_SEND = "autoSendTexts"
        val KEYS = listOf(KEY_WAKE_WORD, KEY_POCKET_PAUSE, KEY_HEADSET_MIC, KEY_OVERLAY, KEY_BRAND_GLOW, KEY_ROBOT_VOICE, KEY_FAHRENHEIT, KEY_AUTO_SEND)

        fun read(s: AppSettings, key: String): Boolean = when (key) {
            KEY_WAKE_WORD -> s.wakeWordEnabled
            KEY_POCKET_PAUSE -> s.pocketPause
            KEY_HEADSET_MIC -> s.headsetMic
            KEY_OVERLAY -> s.useOverlay
            KEY_BRAND_GLOW -> s.brandGlow
            KEY_ROBOT_VOICE -> s.unitStyle == "robot"
            KEY_FAHRENHEIT -> s.useFahrenheit
            KEY_AUTO_SEND -> s.autoSendTexts
            else -> false
        }

        /** The floating card, ready to type (the default) or already listening. */
        fun cardIntent(ctx: Context, voice: Boolean): Intent =
            Intent(ctx, AssistEntryActivity::class.java)
                .putExtra(AssistEntryActivity.EXTRA_SOURCE, "battery")
                .putExtra(AssistEntryActivity.EXTRA_MODE, if (voice) "voice" else "type")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Tells watchers (Battery) a setting changed. Called by [SettingsStore] on every change. */
        fun notifyChanged(context: Context, wakeWordChanged: Boolean) {
            // A change on a child URI reaches observers of the root too (with notifyForDescendants), and
            // a change on the root reaches every observer below it, so one notification per change is enough.
            runCatching { context.contentResolver.notifyChange(if (wakeWordChanged) WAKE_WORD_URI else URI, null) }
        }
    }
}
