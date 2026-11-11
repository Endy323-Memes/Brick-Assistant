package com.brick.assistant.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * A color palette for everything Brick draws: the app, the floating card, the in-app listening card and the
 * intro animation. Colors are ARGB ints.
 */
data class Palette(
    val name: String,
    /** Brick's own color (main voice, highlights). */
    val brick: Int,
    /** The unit's color, also used for active controls. */
    val unit: Int,
    val background: Int,
    val panel: Int,
    val line: Int,
    val text: Int,
    val dim: Int,
    val alert: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", "brick-palette")
        .put("version", 1)
        .put("name", name)
        .put("colors", JSONObject().apply { FIELDS.forEach { put(it, hex(this@Palette.get(it))) } })

    fun get(field: String): Int = when (field) {
        "brick" -> brick; "unit" -> unit; "background" -> background; "panel" -> panel
        "line" -> line; "text" -> text; "dim" -> dim; "alert" -> alert
        else -> error("unknown color $field")
    }

    fun with(field: String, color: Int): Palette = when (field) {
        "brick" -> copy(brick = color); "unit" -> copy(unit = color); "background" -> copy(background = color)
        "panel" -> copy(panel = color); "line" -> copy(line = color); "text" -> copy(text = color)
        "dim" -> copy(dim = color); "alert" -> copy(alert = color)
        else -> this
    }

    companion object {
        /** Editable colors, in the order Settings shows them, with their labels. */
        val FIELDS = listOf("brick", "unit", "background", "panel", "line", "text", "dim", "alert")
        val LABELS = mapOf(
            "brick" to "Brick (main voice)", "unit" to "Unit and controls", "background" to "Background",
            "panel" to "Panels", "line" to "Borders", "text" to "Text", "dim" to "Dim text", "alert" to "Alerts",
        )

        fun hex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

        /** "#4DD0E1", "4dd0e1" or "#FF4DD0E1" -> opaque ARGB, or null. */
        fun parseHex(s: String): Int? {
            val h = s.trim().removePrefix("#")
            if (!Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(h)) return null
            val v = h.toLong(16)
            return if (h.length == 6) (0xFF000000 or v).toInt() else (0xFF000000 or (v and 0xFFFFFF)).toInt()
        }

        /** Throws IllegalArgumentException when [json] isn't a palette file. */
        fun fromJson(json: JSONObject): Palette {
            val colors = json.optJSONObject("colors") ?: throw IllegalArgumentException("That file isn't a Brick color palette.")
            fun c(f: String) = parseHex(colors.optString(f)) ?: throw IllegalArgumentException("The palette is missing a valid \"$f\" color.")
            return Palette(
                json.optString("name").ifBlank { "Imported" }.take(40),
                c("brick"), c("unit"), c("background"), c("panel"), c("line"), c("text"), c("dim"), c("alert"),
            )
        }
    }
}

/** Built-in presets, the chosen palette and your saved ones. Changes apply right away everywhere. */
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

    var current by mutableStateOf(builtIn[0])
        private set
    val custom = mutableStateListOf<Palette>()

    private const val PREFS = "palettes"
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    val isReady: Boolean get() = prefs != null

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        appContext = context.applicationContext
        custom.clear()
        try {
            val a = JSONArray(p.getString("custom", "[]"))
            for (i in 0 until a.length()) custom += Palette.fromJson(a.getJSONObject(i))
        } catch (_: Exception) {}
        current = p.getString("current", null)?.let { runCatching { Palette.fromJson(JSONObject(it)) }.getOrNull() } ?: builtIn[0]
    }

    val all: List<Palette> get() = builtIn + custom

    fun select(p: Palette) {
        current = p
        prefs?.edit()?.putString("current", p.toJson().toString())?.apply()
        // Apps that follow Brick's colors (Edge) watch this.
        appContext?.let {
            PaletteProvider.notifyChanged(it)
            // And Brick on the watch.
            com.brick.assistant.wear.WatchBridgeService.pushPalette(it)
        }
    }

    /** Changes one color: built-in presets are copied into a custom "<name> (edited)" preset first. */
    fun edit(field: String, color: Int) {
        val base = current
        val isBuiltIn = builtIn.any { it.name == base.name }
        val name = if (isBuiltIn) uniqueName("${base.name} (edited)") else base.name
        val next = base.with(field, color).copy(name = name)
        saveCustom(next)
        select(next)
    }

    fun saveCustom(p: Palette) {
        val i = custom.indexOfFirst { it.name == p.name }
        if (i >= 0) custom[i] = p else custom += p
        persist()
    }

    fun delete(p: Palette) {
        custom.removeAll { it.name == p.name }
        persist()
        if (current.name == p.name) select(builtIn[0])
    }

    fun exportJson(p: Palette = current): String = p.toJson().toString(2)

    /** Adds the palette in [json] as a custom preset (renamed if the name is taken) and selects it. */
    fun importJson(json: String): Palette {
        val obj = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("That file isn't valid JSON.") }
        var p = Palette.fromJson(obj)
        if (all.any { it.name == p.name && it != p }) p = p.copy(name = uniqueName(p.name))
        saveCustom(p)
        select(p)
        return p
    }

    private fun uniqueName(base: String): String {
        if (all.none { it.name == base }) return base
        var n = 2
        while (all.any { it.name == "$base $n" }) n++
        return "$base $n"
    }

    private fun persist() {
        val a = JSONArray()
        custom.forEach { a.put(it.toJson()) }
        prefs?.edit()?.putString("custom", a.toString())?.apply()
    }
}
