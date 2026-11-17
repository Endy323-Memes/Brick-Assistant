package com.brick.assistant.brain

import java.util.Locale

/**
 * Live specs for headphones, earbuds and headsets Brick has no notes on: a gaming headset's specs from its maker's
 * product page, anything else from its Wikipedia article (the infobox and the opening sentence). A Wikipedia lookup
 * is two requests (search, then the article's lead), within Wikimedia's limits.
 */
object HeadgearLive {
    private val us = Locale.US

    // ---------------------------------------------------------------- a maker's headset page

    data class Headset(
        val battery: String? = null, val batteryH: Double? = null, val connections: List<String> = emptyList(), val drivers: String? = null,
        val mic: String? = null, val weightG: Double? = null, val anc: String? = null, val spatial: String? = null, val platforms: String? = null,
    ) {
        val found get() = listOfNotNull(battery, drivers, mic, weightG, anc, spatial, platforms).size + (if (connections.isNotEmpty()) 1 else 0)
    }

    private fun near(t: String, cue: Regex, value: Regex, before: Int = 60, after: Int = 120): List<MatchResult> =
        cue.findAll(t).flatMap { c -> value.findAll(t.substring((c.range.first - before).coerceAtLeast(0), (c.range.last + after).coerceAtMost(t.length))) }.toList()

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /** The specs on a gaming headset's product page. */
    fun readHeadset(html: String): Headset {
        val t = GearLive.clean(html)
        // "up to 60 hours", "60-hour battery", "60hr": the biggest figure near "battery" (a quick charge's "6 hours" is smaller).
        val bat = near(t, Regex("battery"), Regex("(\\d{1,3}(?:\\.\\d+)?)\\+?\\s?-?\\s?(?:hours?|hrs?)\\b"), 80, 100)
            .mapNotNull { m -> m.groupValues[1].toDoubleOrNull() }.filter { it in 4.0..400.0 }
        val batteryH = bat.maxOrNull()
        val connections = listOfNotNull(
            if (Regex("\\b2\\.4\\s?ghz|lightspeed|hyperspeed|slipstream|quantum 2\\.0").containsMatchIn(t)) "2.4 GHz wireless (USB dongle)" else null,
            if (Regex("bluetooth").containsMatchIn(t)) "Bluetooth" else null,
            if (Regex("\\b3\\.5\\s?mm").containsMatchIn(t)) "Wired (3.5 mm)" else null,
            if (Regex("\\busb[- ]?c\\b.{0,40}\\b(?:audio|wired)|\\bwired usb\\b").containsMatchIn(t)) "Wired (USB-C) audio" else null,
        )
        val drivers = near(t, Regex("drivers?"), Regex("\\b(\\d{2})\\s?mm\\b"), 60, 40).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 30..110 }?.let { mm ->
            val what = Regex("\\b(planar magnetic|graphene|neodymium|titanium)\\b").find(t)?.groupValues?.get(1)
            "$mm mm" + (what?.let { " $it" } ?: "")
        }
        val mic = when {
            Regex("retractable (?:boom )?mic").containsMatchIn(t) -> "Retractable mic"
            Regex("detachable (?:boom )?mic|removable (?:boom )?mic").containsMatchIn(t) -> "Detachable mic"
            Regex("flip[- ]to[- ]mute").containsMatchIn(t) -> "Flip-to-mute mic"
            else -> null
        }
        val weight = near(t, Regex("\\bweigh(?:t|s|ing)?\\b"), Regex("\\b(\\d{3}(?:\\.\\d)?)\\s?(?:g|grams)\\b"), 10, 60).mapNotNull { it.groupValues[1].toDoubleOrNull() }.firstOrNull { it in 150.0..600.0 }
        val anc = if (Regex("active noise cancell?(?:ing|ation)|\\banc\\b").containsMatchIn(t)) "Active noise cancelling" else null
        val spatial = listOf("Dolby Atmos" to "dolby atmos", "DTS Headphone:X" to "dts headphone", "THX Spatial Audio" to "thx spatial", "Tempest 3D Audio" to "tempest 3d",
            "Windows Sonic" to "windows sonic", "360° Spatial Audio" to "360 spatial|360° spatial").filter { (_, cue) -> Regex(cue).containsMatchIn(t) }.map { it.first }
            .takeIf { it.isNotEmpty() }?.joinToString(", ")
        val platforms = listOf("PC" to "\\bpc\\b|windows", "PlayStation" to "playstation|\\bps5\\b", "Xbox" to "\\bxbox\\b", "Switch" to "nintendo switch",
            "Mac" to "\\bmac\\b|macos", "phones" to "mobile|android|iphone").filter { (_, cue) -> Regex(cue).containsMatchIn(t) }.map { it.first }
            .takeIf { it.size >= 2 }?.let { l -> if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " and " + l.last() }
        return Headset(batteryH?.let { "up to ${fmt(it)} hours" }, batteryH, connections, drivers, mic, weight, anc, spatial, platforms)
    }

    // ---------------------------------------------------------------- Wikipedia

    data class Wiki(
        val title: String, val url: String, val kind: Headgear.Kind?, val intro: String?, val year: Int?, val price: Double?,
        val battery: String?, val batteryH: Double?, val connections: List<String>, val codecs: String?, val weightG: Double?, val chip: String?,
        val display: String?, val perEye: String?, val eyePixels: Int?, val refresh: String?, val refreshHz: Int?, val fov: String?, val fovDeg: Double?,
        val tracking: String?, val controllers: String?, val ram: String?, val storage: String?, val os: String?, val standalone: Boolean?,
    )

    private val AUDIO_PAGE = Regex("(?i)\\b(?:headphones?|earbuds?|earphones?|in-ear|headsets?|wireless audio)\\b")
    private val VR_PAGE = Regex("(?i)\\b(?:virtual[- ]reality|mixed[- ]reality|vr headset|head-mounted display|extended reality)\\b")

    /** The Wikipedia article for "[maker] [model]", read for specs; null when there's none or it isn't about a headset or headphones. Blocking. */
    fun wiki(maker: String, model: String): Wiki? {
        if (!GearLive.enabled) return null
        val want = model.lowercase(us).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in setOf("the", "headphones", "earbuds", "headset", "wireless", "vr") }
        if (want.isEmpty()) return null
        val q = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=${Http.enc("$maker $model")}") ?: return null
        val arr = q.optJSONObject("query")?.optJSONArray("search") ?: return null
        val titles = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.ifBlank { null } }
        val joined = { s: String -> s.lowercase(us).replace(Regex("[^a-z0-9]"), "") }
        val title = titles.firstOrNull { t -> want.all { joined(t).contains(it) } } ?: return null
        val html = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&prop=text&section=0&redirects=1&page=${Http.enc(title)}")
            ?.optJSONObject("parse")?.optString("text") ?: return null
        val box = Platforms.infoboxRows(html)
        val intro = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { text(it.groupValues[1]) }.firstOrNull { it.length > 40 }
        val all = (box.values.joinToString(" ") + " " + intro.orEmpty())
        val vr = VR_PAGE.containsMatchIn(all)
        if (!vr && !AUDIO_PAGE.containsMatchIn(all)) return null
        fun row(vararg keys: String) = keys.firstNotNullOfOrNull { k -> box.entries.firstOrNull { it.key.startsWith(k) }?.value }
        val kind = when {
            vr -> Headgear.Kind.VR
            Regex("(?i)\\b(?:earbuds?|earphones?|in-ear|true wireless)\\b").containsMatchIn(all) -> Headgear.Kind.EARBUDS
            Regex("(?i)\\bgaming headset\\b").containsMatchIn(all) -> Headgear.Kind.HEADSET
            else -> Headgear.Kind.HEADPHONES
        }
        val year = row("release date", "released", "introduced", "launch")?.let { Regex("\\b(19[89]\\d|20[0-3]\\d)\\b").find(it)?.value?.toIntOrNull() }
        val price = row("introductory price", "launch price", "price", "retail")?.let { Regex("(?:US)?\\$\\s?([\\d,]{2,6}(?:\\.\\d{2})?)").find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }
        val power = row("power", "battery")
        val bat = power?.let { Regex("(?i)(\\d{1,2}(?:\\.\\d)?)(?:\\s?[–-]\\s?(\\d{1,2}(?:\\.\\d)?))?\\s?(?:hours|hrs|h)\\b").find(it) }
        val batteryH = bat?.let { (it.groupValues[2].ifBlank { it.groupValues[1] }).toDoubleOrNull() }
        val battery = bat?.let { m -> if (m.groupValues[2].isNotBlank()) "about ${m.groupValues[1]}-${m.groupValues[2]} hours" else "up to ${m.groupValues[1]} hours" }
        val conn = row("connectivity", "connection", "input")
        val connections = conn?.let { c ->
            listOfNotNull(Regex("(?i)bluetooth(?: (\\d\\.\\d))?").find(c)?.let { "Bluetooth" + (it.groupValues[1].ifBlank { null }?.let { v -> " $v" } ?: "") },
                if (Regex("(?i)wi-?fi").containsMatchIn(c)) Regex("(?i)wi-?fi ?(6e|7|6)?").find(c)?.groupValues?.get(1)?.ifBlank { null }?.let { "Wi-Fi ${it.uppercase(us)}" } ?: "Wi-Fi" else null,
                if (Regex("(?i)usb[- ]?c").containsMatchIn(c)) "USB-C" else null, if (Regex("(?i)lightning").containsMatchIn(c)) "Lightning" else null,
                if (Regex("(?i)3\\.5\\s?mm|headphone jack").containsMatchIn(c)) "Wired (3.5 mm)" else null, if (Regex("(?i)displayport").containsMatchIn(c)) "DisplayPort" else null)
        }.orEmpty()
        val codecs = listOf("SBC", "AAC", "aptX", "LDAC", "LC3").filter { Regex("(?i)\\b$it\\b").containsMatchIn(conn.orEmpty() + " " + row("audio", "sound").orEmpty()) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val weightG = row("mass", "weight")?.let { m ->
            Regex("(\\d{1,4}(?:\\.\\d+)?)\\s?g\\b").find(m)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("(\\d(?:\\.\\d+)?)\\s?kg\\b").find(m)?.groupValues?.get(1)?.toDoubleOrNull()?.times(1000)
        }
        val disp = row("display", "screen")
        val res = disp?.let { Regex("(\\d{3,4})\\s?[×x]\\s?(\\d{3,4})").find(it) }
        val hz = disp?.let { Regex("(\\d{2,3})\\s?hz", RegexOption.IGNORE_CASE).findAll(it).mapNotNull { m -> m.groupValues[1].toIntOrNull() }.filter { h -> h in 50..240 }.toList() }.orEmpty()
        val fovText = row("field of view", "fov") ?: disp?.let { Regex("(\\d{2,3})\\s?°").find(it)?.value }
        val fovDeg = fovText?.let { Regex("(\\d{2,3})\\s?°").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val track = (row("tracking", "input", "camera").orEmpty() + " " + intro.orEmpty())
        val tracking = listOfNotNull(
            if (Regex("(?i)inside-out").containsMatchIn(track)) "Inside-out (cameras on the headset)" else null,
            if (Regex("(?i)lighthouse|base station").containsMatchIn(track)) "Base stations (SteamVR Lighthouse)" else null,
            if (Regex("(?i)eye[- ]tracking").containsMatchIn(track)) "eye tracking" else null,
            if (Regex("(?i)hand[- ]tracking").containsMatchIn(track)) "hand tracking" else null,
        ).takeIf { it.isNotEmpty() }?.joinToString(", ")
        val standalone = when {
            !vr -> null
            Regex("(?i)\\bstandalone\\b").containsMatchIn(intro.orEmpty()) -> true
            Regex("(?i)\\btethered\\b|requires? a (?:pc|computer|playstation|console)|connected to a (?:pc|computer)").containsMatchIn(intro.orEmpty()) -> false
            row("system on a chip", "soc", "cpu") != null -> true
            else -> null
        }
        return Wiki(title, "https://en.wikipedia.org/wiki/${Http.enc(title.replace(' ', '_'))}", kind, intro?.let { Sports.brief(Animals.firstSentence(it) ?: it, 240) }, year, price,
            battery, batteryH, connections, codecs, weightG, row("system on a chip", "soc", "cpu", "processor", "chip"),
            disp?.let { d -> Regex("(?i)micro-?oled|oled|mini-led|lcd|amoled").find(d)?.value?.let { v -> v.uppercase(us).replace("MICRO-OLED", "Micro-OLED").replace("MICROOLED", "Micro-OLED") + " displays" } },
            res?.let { "${it.groupValues[1]} x ${it.groupValues[2]}" }, res?.let { it.groupValues[1].toInt() * it.groupValues[2].toInt() },
            hz.distinct().sorted().takeIf { it.isNotEmpty() }?.let { l -> if (l.size == 1) "${l[0]} Hz" else l.dropLast(1).joinToString(", ") + " and ${l.last()} Hz" }, hz.maxOrNull(),
            fovText?.take(60), fovDeg, tracking, row("controller", "controllers"), row("memory", "ram"), row("storage"), row("operating system", "os"), standalone)
    }

    private fun text(h: String) = h.replace(Regex("<sup[^>]*>.*?</sup>|<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
}
