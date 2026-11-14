package com.brick.history.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Brick's color palette (the same roles and presets as Brick). Colors are ARGB ints. */
data class Palette(
    val name: String,
    /** Brick's amber: prompts and highlights. */
    val brick: Int,
    /** The cyan "unit" color: active controls. */
    val unit: Int,
    val background: Int,
    val panel: Int,
    val line: Int,
    val text: Int,
    val dim: Int,
    val alert: Int,
) {
    companion object {
        private fun hex(s: String): Int? {
            val h = s.trim().removePrefix("#")
            if (!Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(h)) return null
            return (0xFF000000 or (h.toLong(16) and 0xFFFFFF)).toInt()
        }

        /** Parses Brick's palette JSON ({"name", "colors": {"brick": "#FFB74D", ...}}). */
        fun fromBrickJson(o: JSONObject): Palette? = runCatching {
            val c = o.getJSONObject("colors")
            fun f(k: String) = hex(c.getString(k))!!
            Palette(o.optString("name", "Brick"), f("brick"), f("unit"), f("background"), f("panel"), f("line"), f("text"), f("dim"), f("alert"))
        }.getOrNull()

        fun fromBrickJson(json: String): Palette? = runCatching { fromBrickJson(JSONObject(json)) }.getOrNull()
    }
}

/**
 * The app's colors. By default History follows the palette picked in Brick (read from Brick's PaletteProvider and
 * updated live when it changes there). Otherwise you pick one of Brick's palettes here: its presets and any you
 * made in Brick (the built-in presets when Brick can't be reached).
 */
object Palettes {
    private fun rgb(v: Long) = (0xFF000000 or v).toInt()

    val builtIn = listOf(
        Palette("Brick", rgb(0xFFB74D), rgb(0x4DD0E1), rgb(0x06080A), rgb(0x0A0D10), rgb(0x22303A), rgb(0xDDE6EE), rgb(0x7F8D99), rgb(0xFF6B6B)),
        Palette("Phosphor", rgb(0xB8F28A), rgb(0x5CF29A), rgb(0x050806), rgb(0x0A0E0B), rgb(0x1F3326), rgb(0xD5F5DD), rgb(0x6F8F78), rgb(0xFF6B6B)),
        Palette("Amber CRT", rgb(0xFFB000), rgb(0xFFD27A), rgb(0x0A0700), rgb(0x120D03), rgb(0x3A2A0C), rgb(0xFFE3B0), rgb(0x9A7A45), rgb(0xFF5A3C)),
        Palette("Synthwave", rgb(0xFF5FD2), rgb(0x46E8FF), rgb(0x0B0614), rgb(0x130B22), rgb(0x3A2257), rgb(0xF2E6FF), rgb(0x9C86B8), rgb(0xFF4D6D)),
        Palette("Ice", rgb(0xE8F1FF), rgb(0x6AA8FF), rgb(0x05080F), rgb(0x0A101C), rgb(0x22324D), rgb(0xE3ECF8), rgb(0x7C8BA3), rgb(0xFF6B6B)),
        Palette("Ember", rgb(0xFF7A45), rgb(0xFFC56B), rgb(0x0C0605), rgb(0x150B09), rgb(0x3D2219), rgb(0xF5E2DA), rgb(0x9C7F74), rgb(0xFF4040)),
    )

    private val BRICK_URI: Uri = Uri.parse("content://com.brick.assistant.palette")

    /** The palette in use. */
    var current by mutableStateOf(builtIn[0])
        private set

    /** Brick's selected palette, or null when Brick isn't installed / reachable. */
    var brick by mutableStateOf<Palette?>(null)
        private set

    /** Every palette Brick offers (built-in and your own), or the built-in ones when Brick can't be reached. */
    var choices by mutableStateOf(builtIn)
        private set

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
        // Last known palettes from Brick, so the first frame already has the right colors.
        brick = prefs?.getString("brick", null)?.let { Palette.fromBrickJson(it) }
        prefs?.getString("choices", null)?.let { parseList(it) }?.let { choices = it }
        recompute()
        try {
            context.contentResolver.registerContentObserver(BRICK_URI, false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = refreshFromBrick()
            })
        } catch (_: Exception) {}
        refreshFromBrick()
    }

    private fun parseList(json: String): List<Palette>? = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { Palette.fromBrickJson(a.getJSONObject(it)) }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Re-reads Brick's palette (and its list of palettes) in the background. */
    fun refreshFromBrick() {
        val ctx = appContext ?: return
        io.execute {
            val cr = ctx.contentResolver
            val json = try { cr.call(BRICK_URI, "palette", null, null)?.getString("json") } catch (_: Exception) { null }
            val list = try { cr.call(BRICK_URI, "palettes", null, null)?.getString("json") } catch (_: Exception) { null }
            val p = json?.let { Palette.fromBrickJson(it) }
            val all = list?.let { parseList(it) }
            main.post {
                if (p != null) prefs?.edit()?.putString("brick", json)?.apply()
                if (all != null) prefs?.edit()?.putString("choices", list)?.apply()
                brick = p ?: brick
                // An older Brick without the list: its presets plus the one it's using.
                choices = all ?: if (p != null && builtIn.none { it.name == p.name }) builtIn + p else choices
                recompute()
            }
        }
    }

    /** Call after changing the "Follow Brick's colors" setting or the palette pick. */
    fun recompute() {
        val s = if (Store.isReady) Store.settings.value else Settings()
        val b = brick
        current = if (s.followBrick && b != null) b
        else choices.firstOrNull { it.name == s.palette } ?: builtIn.firstOrNull { it.name == s.palette } ?: builtIn[0]
    }
}
