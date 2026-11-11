package com.brick.assistant.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * The watch's side of the conversation with Brick on the phone (Wear OS Data Layer messages):
 *  - watch -> phone  [ASK]   what you said or typed (UTF-8)
 *  - phone -> watch  [LINE]  {"who": "you|brick|unit|system", "text": "..."} as each line is spoken
 *  - phone -> watch  [DONE]  {"awaiting": "confirm|dictate|", "palette": {"brick": "#RRGGBB", ...}}
 *  - watch -> phone  [HELLO] and phone -> watch [PALETTE] {"palette": {...}}: the phone's colors
 */
object PhoneLink {
    const val ASK = "/brick/ask"
    const val LINE = "/brick/line"
    const val DONE = "/brick/done"
    /** watch -> phone: "send me your colors" (on opening). */
    const val HELLO = "/brick/hello"
    /** phone -> watch: {"palette": {...}} on hello and whenever the phone's palette changes. */
    const val PALETTE = "/brick/palette"

    /** Asks the phone for its current colors; they arrive as a [PALETTE] message. */
    suspend fun hello(context: Context) {
        val node = phone(context) ?: return
        try {
            Wearable.getMessageClient(context).sendMessage(node.id, HELLO, ByteArray(0)).await()
        } catch (e: Exception) {
        }
    }
    /** Declared by the phone app (res/values/wear.xml). */
    const val CAPABILITY = "brick_phone"

    /** The phone running Brick, nearest first; null when it can't be reached. */
    suspend fun phone(context: Context): Node? = try {
        Wearable.getCapabilityClient(context)
            .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.sortedByDescending { it.isNearby }.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Sends a request to Brick on the phone. False when the phone can't be reached. */
    suspend fun ask(context: Context, text: String): Boolean {
        val node = phone(context) ?: return false
        return try {
            Wearable.getMessageClient(context).sendMessage(node.id, ASK, text.toByteArray(Charsets.UTF_8)).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun json(data: ByteArray): JSONObject? = try { JSONObject(String(data, Charsets.UTF_8)) } catch (e: Exception) { null }
}

/** Brick's colors on the watch: the phone's current palette, remembered between launches. */
data class WatchColors(
    val brick: Int = 0xFFFFB74D.toInt(),
    val unit: Int = 0xFF4DD0E1.toInt(),
    val background: Int = 0xFF06080A.toInt(),
    val panel: Int = 0xFF0A0D10.toInt(),
    val line: Int = 0xFF22303A.toInt(),
    val text: Int = 0xFFDDE6EE.toInt(),
    val dim: Int = 0xFF7F8D99.toInt(),
    val alert: Int = 0xFFFF6B6B.toInt(),
) {
    companion object {
        private const val PREFS = "brick_watch"

        private fun parse(hex: String?): Int? = hex?.removePrefix("#")?.takeIf { it.length == 6 }
            ?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

        fun from(o: JSONObject?, base: WatchColors = WatchColors()): WatchColors {
            if (o == null) return base
            return WatchColors(
                parse(o.optString("brick")) ?: base.brick, parse(o.optString("unit")) ?: base.unit,
                parse(o.optString("background")) ?: base.background, parse(o.optString("panel")) ?: base.panel,
                parse(o.optString("line")) ?: base.line, parse(o.optString("text")) ?: base.text,
                parse(o.optString("dim")) ?: base.dim, parse(o.optString("alert")) ?: base.alert,
            )
        }

        fun load(context: Context): WatchColors =
            from(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("palette", null)?.let { runCatching { JSONObject(it) }.getOrNull() })

        fun save(context: Context, palette: JSONObject) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("palette", palette.toString()).apply()
        }

        /** Brick's last answer, shown on the tile. */
        fun lastReply(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)

        fun saveLastReply(context: Context, text: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", text).apply()
        }
    }
}
