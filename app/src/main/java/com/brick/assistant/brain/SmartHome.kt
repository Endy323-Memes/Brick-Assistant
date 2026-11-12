package com.brick.assistant.brain

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A smart-home app Brick can open ("open Google Home", or when it can't switch a device itself). */
enum class HomeApp(val label: String, val pkg: String, val words: String) {
    GOOGLE_HOME("Google Home", "com.google.android.apps.chromecast.app", "google home|google nest|\\bnest\\b"),
    GOVEE("Govee Home", "com.govee.home", "govee"),
    SMARTTHINGS("SmartThings", "com.samsung.android.oneconnect", "smart ?things|samsung home"),
    ALEXA("Amazon Alexa", "com.amazon.dee.app", "alexa|echo"),
    HUE("Philips Hue", "com.philips.lighting.hue2", "\\bhue\\b|philips"),
    KASA("Kasa Smart", "com.tplink.kasa_android", "\\bkasa\\b|tp-?link|tapo"),
    HOME_ASSISTANT("Home Assistant", "io.homeassistant.companion.android", "home assistant"),
    ;

    val regex: Regex by lazy { Regex("\\b(?:$words)", RegexOption.IGNORE_CASE) }

    fun installed(context: Context) = context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun open(context: Context): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    companion object {
        fun named(said: String): HomeApp? = entries.firstOrNull { it.regex.containsMatchIn(said) }
    }
}

/** One Govee light or plug, with what Brick last knew about it. */
data class GoveeDevice(
    val sku: String,
    val id: String,
    val name: String,
    val canDim: Boolean,
    val canColor: Boolean,
    var on: Boolean? = null,
    var brightness: Int? = null,
)

/** Named colors for "make the lights blue". */
object HomeColors {
    val named = linkedMapOf(
        "red" to 0xFF0000, "orange" to 0xFF7F00, "yellow" to 0xFFD000, "green" to 0x00FF00, "teal" to 0x00B3A0,
        "cyan" to 0x00FFFF, "blue" to 0x0040FF, "purple" to 0x8000FF, "violet" to 0x8F00FF, "pink" to 0xFF4FA0,
        "magenta" to 0xFF00FF, "white" to 0xFFFFFF, "warm white" to 0xFFD8A0, "cool white" to 0xE0F0FF,
    )

    /** The color named in [said] (the longest name, so "warm white" beats "white"). */
    fun find(said: String): Pair<String, Int>? = named.entries
        .filter { Regex("\\b${it.key}\\b").containsMatchIn(said.lowercase()) }
        .maxByOrNull { it.key.length }?.toPair()
}

/**
 * Govee lights through Govee's open API (developer.govee.com), with the API key you get in the Govee Home app
 * (Profile > Settings > Apply for API Key). Calls run off the main thread; state is kept so the readout's
 * switches can draw without waiting on the network.
 */
object Govee {
    private const val BASE = "https://openapi.api.govee.com/router/api/v1"

    @Volatile var devices: List<GoveeDevice> = emptyList()
        private set
    @Volatile private var loadedKey: String? = null

    private fun call(key: String, path: String, body: JSONObject?): JSONObject? = try {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Govee-API-Key", key)
        c.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val text = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        c.disconnect()
        text?.let { JSONObject(it) }
    } catch (_: Exception) {
        null
    }

    /** Your Govee devices (cached for this key), or null when the key doesn't work. Network: call off the main thread. */
    fun load(key: String, force: Boolean = false): List<GoveeDevice>? {
        if (!force && loadedKey == key && devices.isNotEmpty()) return devices
        val r = call(key, "/user/devices", null) ?: return null
        val list = parseDevices(r.optJSONArray("data") ?: JSONArray())
        devices = list
        loadedKey = key
        list.forEach { refresh(key, it) }
        return list
    }

    internal fun parseDevices(data: JSONArray): List<GoveeDevice> = (0 until data.length()).mapNotNull { i ->
        val d = data.optJSONObject(i) ?: return@mapNotNull null
        val caps = d.optJSONArray("capabilities") ?: JSONArray()
        val instances = (0 until caps.length()).mapNotNull { caps.optJSONObject(it)?.optString("instance") }
        if ("powerSwitch" !in instances) return@mapNotNull null
        GoveeDevice(
            sku = d.optString("sku"), id = d.optString("device"),
            name = d.optString("deviceName").ifBlank { d.optString("sku") },
            canDim = "brightness" in instances, canColor = "colorRgb" in instances,
        )
    }

    /** Reads one device's power and brightness. */
    fun refresh(key: String, d: GoveeDevice) {
        val body = JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("payload", JSONObject().put("sku", d.sku).put("device", d.id))
        val caps = call(key, "/device/state", body)?.optJSONObject("payload")?.optJSONArray("capabilities") ?: return
        for (i in 0 until caps.length()) {
            val c = caps.optJSONObject(i) ?: continue
            val v = c.optJSONObject("state")?.opt("value")
            when (c.optString("instance")) {
                "powerSwitch" -> (v as? Number)?.let { d.on = it.toInt() == 1 }
                "brightness" -> (v as? Number)?.let { d.brightness = it.toInt() }
            }
        }
    }

    private fun control(key: String, d: GoveeDevice, type: String, instance: String, value: Any): Boolean {
        val body = JSONObject().put("requestId", UUID.randomUUID().toString()).put(
            "payload", JSONObject().put("sku", d.sku).put("device", d.id)
                .put("capability", JSONObject().put("type", type).put("instance", instance).put("value", value)),
        )
        return call(key, "/device/control", body)?.optInt("code", 0) == 200
    }

    fun power(key: String, d: GoveeDevice, on: Boolean) =
        control(key, d, "devices.capabilities.on_off", "powerSwitch", if (on) 1 else 0).also { if (it) d.on = on }

    fun brightness(key: String, d: GoveeDevice, pct: Int) =
        control(key, d, "devices.capabilities.range", "brightness", pct.coerceIn(1, 100)).also { if (it) { d.brightness = pct; d.on = true } }

    fun color(key: String, d: GoveeDevice, rgb: Int) =
        control(key, d, "devices.capabilities.color_setting", "colorRgb", rgb).also { if (it) d.on = true }

    /**
     * The devices [target] names ("bedroom lamp", "the strip"), or all of them for "the lights" / nothing named.
     * Close spellings count.
     */
    fun match(target: String?, list: List<GoveeDevice>): List<GoveeDevice> {
        val t = target?.lowercase()?.replace(Regex("\\b(?:my|the|all|of|govee|smart|lights?|lamps?|bulbs?|devices?|strips?|led|leds|plugs?)\\b"), " ")
            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return list
        val words = t.split(" ")
        val scored = list.map { d ->
            val dw = d.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            d to words.count { w -> dw.any { it == w || it.startsWith(w) || AppLauncher.similarity(it, w) >= 0.75 } }
        }
        val best = scored.maxOfOrNull { it.second } ?: 0
        return if (best == 0) emptyList() else scored.filter { it.second == best }.map { it.first }
    }
}
