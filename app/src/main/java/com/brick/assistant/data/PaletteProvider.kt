package com.brick.assistant.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Shares Brick's active color palette with companion apps (Edge) so they look the same.
 * `call("palette")` returns {"json": <palette JSON in Brick's export format>}. When the palette changes, Brick
 * notifies [URI] so watchers can re-read it. Only apps signed with Brick's key, or Edge, get an answer.
 */
class PaletteProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_PALETTE) return null
        val ctx = context ?: return null
        if (!trusted(ctx)) return null
        if (!Palettes.isReady) Palettes.init(ctx)
        return Bundle().apply { putString("json", Palettes.current.toJson().toString()) }
    }

    private fun trusted(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        val pm = ctx.packageManager
        if (runCatching { pm.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)) return true
        return runCatching { pm.getPackagesForUid(uid)?.any { it == EDGE_PACKAGE } == true }.getOrDefault(false)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.brick.assistant.palette"
        const val METHOD_PALETTE = "palette"
        const val EDGE_PACKAGE = "com.brick.edge"
        val URI: Uri = Uri.parse("content://$AUTHORITY")

        fun notifyChanged(context: Context) {
            runCatching { context.contentResolver.notifyChange(URI, null) }
        }
    }
}
