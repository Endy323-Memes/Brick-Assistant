package com.brick.history.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Brick sends each line here: `call(URI, "add", null, {turn, time, speaker, text, image?, category?})` → {ok}.
 * A request ("you") also carries {kind, label?, subject?, follow} so it's filed by topic; its answers follow its
 * turn. `call(URI, "forget", null, {turn})` takes a request and its answers back out (Brick's "Don't save").
 * Only Brick itself (or an app signed with this app's key) is let in, so other apps can't add or read chats.
 */
class LogProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!trusted(ctx)) return null
        Store.init(ctx)
        return when (method) {
            "add" -> {
                val b = extras ?: return ok(false)
                val turn = b.getString("turn")?.take(80) ?: return ok(false)
                val text = b.getString("text")?.take(20_000) ?: return ok(false)
                val speaker = b.getString("speaker")?.takeIf { it in SPEAKERS } ?: return ok(false)
                if (!Store.settings.value.saving) return ok(false)
                val image = b.getString("image")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                val info = if (speaker != "you") null else RequestInfo(
                    kind = b.getString("kind")?.take(40)?.filter { it.isLetterOrDigit() }?.ifEmpty { null } ?: "Request",
                    label = b.getString("label")?.take(60),
                    subject = b.getString("subject")?.take(300),
                    follow = b.getBoolean("follow", false),
                )
                Store.add(turn, Entry(b.getLong("time", System.currentTimeMillis()), speaker, text, image, b.getString("category")), info)
                ok(true)
            }
            "forget" -> {
                val turn = extras?.getString("turn") ?: return ok(false)
                Store.forget(turn)
                ok(true)
            }
            "saving" -> ok(Store.settings.value.saving)
            else -> null
        }
    }

    private fun ok(v: Boolean) = Bundle().apply { putBoolean("ok", v) }

    private fun trusted(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        val pm = ctx.packageManager
        if (runCatching { pm.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)) return true
        return runCatching { pm.getPackagesForUid(uid)?.any { it == BrickLink.PACKAGE } == true }.getOrDefault(false)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        val SPEAKERS = setOf("you", "brick", "unit", "box")
    }
}
