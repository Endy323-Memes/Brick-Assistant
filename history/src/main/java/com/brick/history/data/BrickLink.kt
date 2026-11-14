package com.brick.history.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens Brick: listening, ready to type, or running a past request again. */
object BrickLink {
    const val PACKAGE = "com.brick.assistant"
    private const val CARD = "com.brick.assistant.overlay.AssistEntryActivity"
    private val CONTROL: Uri = Uri.parse("content://com.brick.assistant.control")

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    private fun card(mode: String) = Intent()
        .setClassName(PACKAGE, CARD)
        .putExtra("brick_source", "history")
        .putExtra("brick_mode", mode)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Brick's floating card. [voice] listens right away; otherwise it opens ready to type. Null when it worked. */
    fun open(context: Context, voice: Boolean): String? = try {
        context.startActivity(card(if (voice) "voice" else "type"))
        null
    } catch (_: Exception) {
        "Brick isn't installed (or needs an update)."
    }

    /**
     * Asks Brick [text] again. Brick hands out a one-time token for the request (only to this app), then the card
     * opens and runs it. Null when it worked, else what went wrong.
     */
    fun ask(context: Context, text: String): String? {
        val r = try {
            context.contentResolver.call(CONTROL, "ask_token", text, null)
        } catch (_: Exception) {
            return "Brick isn't installed (or needs an update)."
        } ?: return "Brick didn't answer. Update Brick and try again."
        val token = r.getString("token") ?: return r.getString("reason") ?: "Brick couldn't take that request."
        return try {
            context.startActivity(card("type").putExtra("brick_ask", token))
            null
        } catch (_: Exception) {
            "Brick couldn't open its card."
        }
    }
}
