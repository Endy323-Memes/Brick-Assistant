package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What the user asked about a place's hours. */
enum class HoursAsk { IS_OPEN, OPENS, CLOSES, HOURS }

/** What kind of photography spot the user wants. */
enum class SpotKind(val spoken: String) { ANY("photography spots"), WATERFALL("waterfalls"), FLORA("gardens and wildflower spots"), LANDSCAPE("scenic landscapes") }

private const val UA = "BrickAssistant/1.0 (Android personal assistant app)"

private fun httpText(url: String, readTimeoutMs: Int = 10_000): String? = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = readTimeoutMs
    c.setRequestProperty("User-Agent", UA)
    c.setRequestProperty("Accept", "application/json")
    val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
    c.disconnect()
    body
} catch (e: Exception) {
    null
}

/** Great-circle distance in miles. */
fun milesBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 3958.8
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

// ==================================================================== Opening hours

/**
 * OpenStreetMap `opening_hours` values ("Mo-Fr 08:00-18:00; Sa 09:00-14:00; Su off", "24/7").
 * Times are minutes after midnight; a close past midnight is above 1440.
 */
class OpeningHours private constructor(private val week: Map<DayOfWeek, List<IntRange>>) {
    fun on(day: DayOfWeek): List<IntRange> = week[day].orEmpty()

    /** Open interval containing [at], or null when closed then (late-night spans from the day before count). */
    fun openSpan(at: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
        val m = at.hour * 60 + at.minute
        for (r in on(at.dayOfWeek)) if (m >= r.first && m < r.last) return at.toLocalDate().atStartOfDay().plusMinutes(r.first.toLong()) to at.toLocalDate().atStartOfDay().plusMinutes(r.last.toLong())
        val yesterday = at.minusDays(1)
        for (r in on(yesterday.dayOfWeek)) if (r.last > 1440 && m < r.last - 1440) {
            val d = yesterday.toLocalDate().atStartOfDay()
            return d.plusMinutes(r.first.toLong()) to d.plusMinutes(r.last.toLong())
        }
        return null
    }

    /** The next opening after [at] within a week. */
    fun nextOpen(at: LocalDateTime): LocalDateTime? {
        for (d in 0..7) {
            val date = at.toLocalDate().plusDays(d.toLong())
            for (r in on(date.dayOfWeek).sortedBy { it.first }) {
                val t = date.atStartOfDay().plusMinutes(r.first.toLong())
                if (t.isAfter(at)) return t
            }
        }
        return null
    }

    val alwaysOpen: Boolean get() = DayOfWeek.entries.all { d -> on(d).any { it.first == 0 && it.last >= 1440 } }

    companion object {
        private val dayCodes = mapOf(
            "mo" to DayOfWeek.MONDAY, "tu" to DayOfWeek.TUESDAY, "we" to DayOfWeek.WEDNESDAY, "th" to DayOfWeek.THURSDAY,
            "fr" to DayOfWeek.FRIDAY, "sa" to DayOfWeek.SATURDAY, "su" to DayOfWeek.SUNDAY,
        )
        private val time = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\+?")

        /** Null when the value can't be understood (seasonal rules, "sunrise", comments ...). */
        fun parse(raw: String?): OpeningHours? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value == "24/7") return OpeningHours(DayOfWeek.entries.associateWith { listOf(0..1440) })
            val week = HashMap<DayOfWeek, List<IntRange>>()
            var understood = false
            for (ruleRaw in value.split(';', '|')) {
                val rule = ruleRaw.trim()
                if (rule.isEmpty()) continue
                val firstTime = Regex("\\d{1,2}:\\d{2}|\\boff\\b|\\bclosed\\b", RegexOption.IGNORE_CASE).find(rule)
                val selector = (if (firstTime != null) rule.substring(0, firstTime.range.first) else rule).trim().trimEnd(':').trim()
                val timesPart = if (firstTime != null) rule.substring(firstTime.range.first) else ""
                // Days: empty selector = every day; skip rules for holidays, months, weeks ...
                val days: Set<DayOfWeek> = if (selector.isEmpty()) DayOfWeek.entries.toSet() else {
                    val out = LinkedHashSet<DayOfWeek>()
                    var ok = true
                    for (part in selector.split(',')) {
                        val p = part.trim().lowercase()
                        when {
                            p.isEmpty() -> {}
                            p == "ph" || p == "sh" -> {}
                            Regex("^[a-z]{2}-[a-z]{2}$").matches(p) -> {
                                val a = dayCodes[p.take(2)]; val b = dayCodes[p.takeLast(2)]
                                if (a == null || b == null) { ok = false } else {
                                    var d: DayOfWeek = a
                                    while (true) { out.add(d); if (d == b) break; d = d.plus(1) }
                                }
                            }
                            dayCodes.containsKey(p) -> out += dayCodes.getValue(p)
                            else -> ok = false
                        }
                    }
                    if (!ok) continue
                    out
                }
                if (days.isEmpty()) continue
                val closed = Regex("^(?:off|closed)\\b", RegexOption.IGNORE_CASE).containsMatchIn(timesPart.trim())
                val spans = if (closed) emptyList() else time.findAll(timesPart).map { m ->
                    val (h1, m1, h2, m2) = m.destructured
                    val open = h1.toInt() * 60 + m1.toInt()
                    var close = h2.toInt() * 60 + m2.toInt()
                    if (close <= open) close += 1440
                    open..close
                }.toList()
                if (!closed && spans.isEmpty()) continue
                understood = true
                for (d in days) week[d] = spans
            }
            return if (understood) OpeningHours(week) else null
        }
    }
}

object PlaceHours {
    class Place(val name: String, val lat: Double, val lon: Double, val hours: OpeningHours?, val rawHours: String?)

    /** Finds [query] near the user (OpenStreetMap / Nominatim) and its posted opening hours. */
    suspend fun find(query: String, near: WeatherService.Place?): Place? = withContext(Dispatchers.IO) {
        fun search(bounded: Boolean): JSONArray? {
            var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&extratags=1&q=${Http.enc(query)}"
            if (near != null) {
                val d = 0.6
                url += "&viewbox=${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}"
                if (bounded) url += "&bounded=1"
            }
            return httpText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }
        }
        val arr = (if (near != null) search(true)?.takeIf { it.length() > 0 } else null) ?: search(false) ?: return@withContext null
        val places = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val raw = o.optJSONObject("extratags")?.optString("opening_hours")?.takeIf { it.isNotBlank() }
            val name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }.ifBlank { query }
            Place(name, o.optDouble("lat"), o.optDouble("lon"), OpeningHours.parse(raw), raw)
        }
        fun dist(p: Place) = near?.let { milesBetween(it.lat, it.lon, p.lat, p.lon) } ?: 0.0
        places.filter { it.hours != null }.minByOrNull(::dist) ?: places.minByOrNull(::dist)
    }

    private fun t(dt: LocalDateTime) = TimeParser.speakTime(dt.toLocalTime())
    private fun t(minutes: Int) = TimeParser.speakTime(LocalTime.of((minutes / 60) % 24, minutes % 60))

    private fun dayWord(dt: LocalDateTime, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), dt.toLocalDate())
        return when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "on " + dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        }
    }

    /** The robot's answer about [p]'s hours. */
    fun speak(p: Place, ask: HoursAsk, tomorrow: Boolean, now: LocalDateTime = LocalDateTime.now()): String {
        val h = p.hours ?: return "I couldn't find posted hours for ${p.name}, so I'll show it in Google Maps."
        val name = p.name
        if (h.alwaysOpen) return "$name is open 24 hours a day."
        if (tomorrow) {
            val day = now.plusDays(1)
            val spans = h.on(day.dayOfWeek)
            if (spans.isEmpty()) return "$name is closed tomorrow."
            return when (ask) {
                HoursAsk.OPENS -> "$name opens at ${t(spans.first().first)} tomorrow."
                HoursAsk.CLOSES -> "$name closes at ${t(spans.last().last)} tomorrow."
                else -> "Tomorrow, $name is open " + spans.joinToString(" and ") { "from ${t(it.first)} to ${t(it.last)}" } + "."
            }
        }
        val span = h.openSpan(now)
        val next = h.nextOpen(now)
        val opensLine = next?.let { "It opens at ${t(it)} ${dayWord(it, now)}." } ?: ""
        return when (ask) {
            HoursAsk.IS_OPEN, HoursAsk.CLOSES ->
                if (span != null) "$name is open right now and closes at ${t(span.second)}${if (span.second.toLocalDate() != now.toLocalDate()) " tonight" else ""}."
                else "$name is closed right now. $opensLine".trim()
            HoursAsk.OPENS ->
                if (span != null) "$name is already open, until ${t(span.second)}."
                else next?.let { "$name opens at ${t(it)} ${dayWord(it, now)}." } ?: "$name doesn't list an opening time this week."
            HoursAsk.HOURS -> {
                val spans = h.on(now.dayOfWeek)
                val today = if (spans.isEmpty()) "$name is closed today." else "Today, $name is open " + spans.joinToString(" and ") { "from ${t(it.first)} to ${t(it.last)}" } + "."
                if (span == null && spans.isNotEmpty() && next != null) "$today It's closed right now. $opensLine" else today
            }
        }
    }
}

// ==================================================================== Photography spots

data class PhotoSpot(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val miles: Double,
    val description: String?,
    val score: Double,
)


object PhotoSpots {
    const val MAX_MILES = 100

    fun query(lat: Double, lon: Double, meters: Int, kind: SpotKind): String {
        val a = "(around:$meters,$lat,$lon)"
        val parts = buildList {
            if (kind == SpotKind.ANY || kind == SpotKind.WATERFALL) add("nwr[\"waterway\"=\"waterfall\"][\"name\"]$a;")
            if (kind == SpotKind.ANY || kind == SpotKind.FLORA) {
                add("nwr[\"leisure\"=\"garden\"][\"name\"][\"garden:type\"!~\"residential|community|private\"][\"access\"!=\"private\"]$a;")
                add("nwr[\"leisure\"=\"nature_reserve\"][\"name\"]$a;")
            }
            if (kind == SpotKind.ANY || kind == SpotKind.LANDSCAPE) {
                add("nwr[\"tourism\"=\"viewpoint\"][\"name\"]$a;")
                add("nwr[\"natural\"~\"^(peak|cliff|arch|gorge|beach|glacier|valley|rock)$\"][\"name\"][\"wikidata\"]$a;")
                add("nwr[\"natural\"=\"water\"][\"water\"=\"lake\"][\"name\"][\"wikidata\"]$a;")
            }
            if (kind != SpotKind.WATERFALL) add("nwr[\"boundary\"=\"national_park\"][\"name\"]$a;")
        }
        return "[out:json][timeout:40];(${parts.joinToString("")});out center tags 800;"
    }

    /** Ranks Overpass [elements]: well-known places (Wikipedia/Wikidata) and the best subjects first, nearer breaks ties. */
    fun rank(elements: JSONArray, lat: Double, lon: Double, radiusMiles: Double, kind: SpotKind, limit: Int = 15): List<PhotoSpot> {
        val out = ArrayList<PhotoSpot>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name")
            if (name.isBlank()) continue
            val c = e.optJSONObject("center")
            val la = if (e.has("lat")) e.optDouble("lat") else c?.optDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.optDouble("lon") else c?.optDouble("lon") ?: continue
            if (la.isNaN() || lo.isNaN()) continue
            val miles = milesBetween(lat, lon, la, lo)
            if (miles > radiusMiles * 1.05) continue
            val (type, base) = classify(tags) ?: continue
            if (kind == SpotKind.FLORA && type == "national park") continue
            var score = base
            if (tags.has("wikipedia")) score += 4
            if (tags.has("wikidata")) score += 2
            if (tags.has("image") || tags.has("wikimedia_commons")) score += 1.5
            if (tags.has("website") || tags.has("description")) score += 0.5
            if (tags.has("height")) score += 0.5
            score -= 2.5 * (miles / radiusMiles.coerceAtLeast(1.0))
            val desc = tags.optString("description").takeIf { it.isNotBlank() && it.length < 220 }
            out += PhotoSpot(name, type, la, lo, miles, desc, score)
        }
        // One entry per name (a waterfall is often mapped as several nodes).
        return out.sortedByDescending { it.score }.distinctBy { it.name.lowercase() }.take(limit)
    }

    private fun classify(t: JSONObject): Pair<String, Double>? {
        fun is_(k: String, v: String) = t.optString(k) == v
        return when {
            is_("waterway", "waterfall") -> "waterfall" to 5.0
            is_("boundary", "national_park") -> "national park" to 5.0
            is_("leisure", "garden") -> if (t.optString("garden:type") == "botanical") "botanical garden" to 4.5 else "garden" to 2.5
            is_("leisure", "nature_reserve") -> "nature reserve" to 3.0
            is_("tourism", "viewpoint") -> "scenic viewpoint" to 3.0
            is_("natural", "water") -> "lake" to 2.5
            t.has("natural") -> t.optString("natural").replace('_', ' ') to 3.0
            else -> null
        }
    }

    // ---------------------------------------------------------------- Wikidata (primary: fast, notable places, has photos)

    /** Wikidata classes -> (spoken type, base score). */
    private val wikidataTypes = mapOf(
        "Q34038" to ("waterfall" to 5.0),
        "Q46169" to ("national park" to 5.0),
        "Q1761072" to ("state park" to 4.0),
        "Q167346" to ("botanical garden" to 4.5),
        "Q272231" to ("arboretum" to 4.0),
        "Q6017969" to ("scenic viewpoint" to 4.0),
        "Q150784" to ("canyon" to 4.0),
        "Q179049" to ("nature reserve" to 3.0),
        "Q40080" to ("beach" to 3.0),
        "Q1107656" to ("garden" to 2.5),
        "Q23397" to ("lake" to 2.5),
        "Q8502" to ("mountain" to 2.0),
        "Q207326" to ("summit" to 2.0),
    )

    private fun typesFor(kind: SpotKind): List<String> = when (kind) {
        SpotKind.ANY -> wikidataTypes.keys.toList()
        SpotKind.WATERFALL -> listOf("Q34038")
        SpotKind.FLORA -> listOf("Q167346", "Q272231", "Q1107656", "Q179049")
        SpotKind.LANDSCAPE -> listOf("Q6017969", "Q150784", "Q46169", "Q1761072", "Q8502", "Q207326", "Q23397", "Q40080")
    }

    fun wikidataQuery(lat: Double, lon: Double, km: Double, kind: SpotKind): String = """
        SELECT ?item ?itemLabel ?type ?coord ?image ?links ?desc WHERE {
          SERVICE wikibase:around { ?item wdt:P625 ?coord. bd:serviceParam wikibase:center "Point($lon $lat)"^^geo:wktLiteral; wikibase:radius "${"%.1f".format(java.util.Locale.US, km)}". }
          VALUES ?type { ${typesFor(kind).joinToString(" ") { "wd:$it" }} }
          ?item wdt:P31/wdt:P279? ?type.
          OPTIONAL { ?item wdt:P18 ?image }
          OPTIONAL { ?item wikibase:sitelinks ?links }
          SERVICE wikibase:label { bd:serviceParam wikibase:language "en". ?item rdfs:label ?itemLabel. ?item schema:description ?desc. }
        } LIMIT 4000
    """.trimIndent()

    /** Ranks Wikidata SPARQL [bindings]: photogenic types, places with photos and well-known places first. */
    fun rankWikidata(bindings: JSONArray, lat: Double, lon: Double, radiusMiles: Double, limit: Int = 15): List<PhotoSpot> {
        val best = HashMap<String, PhotoSpot>()
        for (i in 0 until bindings.length()) {
            val b = bindings.optJSONObject(i) ?: continue
            fun v(k: String) = b.optJSONObject(k)?.optString("value")?.takeIf { it.isNotBlank() }
            val id = v("item") ?: continue
            val name = v("itemLabel") ?: continue
            if (Regex("^Q\\d+$").matches(name)) continue // no English label
            val (type, base) = wikidataTypes[v("type")?.substringAfterLast('/')] ?: continue
            val m = Regex("Point\\(([-\\d.eE]+) ([-\\d.eE]+)\\)").find(v("coord") ?: continue) ?: continue
            val lo = m.groupValues[1].toDouble()
            val la = m.groupValues[2].toDouble()
            val miles = milesBetween(lat, lon, la, lo)
            if (miles > radiusMiles * 1.05) continue
            var score = base
            if (v("image") != null) score += 3
            score += (v("links")?.toIntOrNull() ?: 0).coerceAtMost(10) * 0.3
            score -= 2.5 * (miles / radiusMiles.coerceAtLeast(1.0))
            val desc = v("desc")?.takeIf { !it.equals(type, true) && it.length < 220 }?.replaceFirstChar { it.uppercase() }
            val spot = PhotoSpot(name, type, la, lo, miles, desc, score)
            if ((best[id]?.score ?: Double.NEGATIVE_INFINITY) < score) best[id] = spot
        }
        return best.values.sortedByDescending { it.score }.distinctBy { it.name.lowercase() }.take(limit)
    }

    /** A mix of waterfalls, gardens and landscapes: each repeat of a type counts a little less. */
    fun diversify(spots: List<PhotoSpot>, limit: Int = 15): List<PhotoSpot> {
        val left = spots.toMutableList()
        val seen = HashMap<String, Int>()
        val out = ArrayList<PhotoSpot>()
        fun group(t: String) = when (t) {
            "botanical garden", "arboretum", "garden", "nature reserve" -> "flora"
            "mountain", "summit" -> "mountain"
            else -> t
        }
        while (out.size < limit && left.isNotEmpty()) {
            val next = left.maxBy { it.score - 1.5 * (seen[group(it.type)] ?: 0) }
            left.remove(next)
            out += next
            seen.merge(group(next.type), 1, Int::plus)
        }
        return out
    }

    suspend fun search(lat: Double, lon: Double, radiusMiles: Int, kind: SpotKind): List<PhotoSpot>? = withContext(Dispatchers.IO) {
        val miles = radiusMiles.coerceIn(1, MAX_MILES)
        val wq = wikidataQuery(lat, lon, miles * 1.609, kind)
        httpText("https://query.wikidata.org/sparql?format=json&query=${Http.enc(wq)}", readTimeoutMs = 30_000)?.let { body ->
            runCatching { JSONObject(body).getJSONObject("results").getJSONArray("bindings") }.getOrNull()?.let { rows ->
                val spots = rankWikidata(rows, lat, lon, miles.toDouble(), limit = 60)
                if (spots.isNotEmpty()) return@withContext if (kind == SpotKind.ANY) diversify(spots) else spots.take(15)
            }
        }
        // Fallback: OpenStreetMap (Overpass) also knows small local spots, but its servers are often busy.
        val q = query(lat, lon, (miles * 1609.34).toInt(), kind)
        for (host in listOf("https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")) {
            val body = httpText("$host?data=${Http.enc(q)}", readTimeoutMs = 25_000) ?: continue
            val els = runCatching { JSONObject(body).optJSONArray("elements") }.getOrNull() ?: continue
            return@withContext rank(els, lat, lon, miles.toDouble(), kind)
        }
        null
    }

    fun miles(m: Double): String = when {
        m < 1 -> "under a mile"
        m < 1.5 -> "1 mile"
        else -> "${m.toInt()} miles"
    }

    private fun article(type: String) = if (type.first().lowercaseChar() in "aeiou") "an $type" else "a $type"

    /** A short line about one spot: "The top spot is Ruby Falls, an underground waterfall, 2 miles away." */
    fun describe(s: PhotoSpot, first: Boolean): String {
        val d = s.description?.takeIf { it.length <= 60 }?.replaceFirstChar { it.lowercase() }
        val what = when {
            d == null -> article(s.type)
            Regex("^(?:a|an|the)\\s", RegexOption.IGNORE_CASE).containsMatchIn(d) -> d
            // Plural descriptions ("waterfalls on the Passaic River") take no article.
            Regex("^\\S*[^s]s\\b").containsMatchIn(d) -> d
            else -> article(d)
        }
        return (if (first) "The top spot is " else "Next up, ") + "${s.name}, $what, ${miles(s.miles)} away."
    }

    fun speak(spots: List<PhotoSpot>, radiusMiles: Int, kind: SpotKind): String {
        if (spots.isEmpty()) return "I couldn't find any ${kind.spoken} within $radiusMiles miles. Try a bigger radius."
        val top = spots.take(3).map { "${it.name}, ${article(it.type)} ${miles(it.miles)} away" }
        val list = if (top.size == 1) top[0] else top.dropLast(1).joinToString("; ") + "; and " + top.last()
        val count = if (spots.size == 1) "one spot" else "${spots.size} ${kind.spoken}"
        return "I found $count within $radiusMiles miles. Top picks: $list. The full list is on screen."
    }
}

/** Words for food and shopping searches ("find me a good sandwich place" -> "best sandwich near me"). */
object FindPlaces {
    private val cuisine = Regex(
        "^(?:chinese|mexican|italian|thai|japanese|indian|korean|vietnamese|greek|mediterranean|french|american|cajun|creole|" +
            "caribbean|jamaican|cuban|spanish|middle eastern|lebanese|turkish|persian|ethiopian|african|peruvian|brazilian|" +
            "filipino|hawaiian|southern|tex mex|asian|german|polish|halal|kosher|vegan|vegetarian|gluten free)$",
        RegexOption.IGNORE_CASE,
    )
    private val placeNoun = Regex(
        "\\b(?:restaurants?|cafes?|caf\u00e9s?|shops?|stores?|bars?|pubs?|bakery|bakeries|diners?|delis?|grill|bistros?|joints?|" +
            "trucks?|steakhouse|breweries|brewery|eatery|eateries|buffets?|malls?|outlets?|boutiques?|markets?|supermarkets?|" +
            "pharmacy|pharmacies|stations?|salons?|gyms?|barbers?|barbershops?|laundromats?|hotels?|motels?|banks?|atms?|" +
            "hospitals?|offices?|chargers?|dealerships?|florists?)$",
        RegexOption.IGNORE_CASE,
    )

    private fun cap(s: String) = s.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    fun query(r: Request.FindPlace): String {
        val what = if (r.kind == FindKind.FOOD && cuisine.matches(r.what)) "${r.what} restaurants"
        else if (r.what.equals("food", true) || r.what.equals("something to eat", true) || r.what.equals("a bite", true)) "restaurants"
        else r.what
        val best = if (r.best) "best " else ""
        return "$best$what " + (r.area?.let { "in $it" } ?: "near me")
    }

    fun line(r: Request.FindPlace): String {
        val w = r.what.lowercase()
        val display = when {
            r.kind == FindKind.FOOD && cuisine.matches(w) -> "${cap(w)} restaurants"
            w == "food" || w == "something to eat" || w == "a bite" || w == "eats" -> "places to eat"
            placeNoun.containsMatchIn(w) -> w
            r.kind == FindKind.FOOD -> "$w spots"
            r.kind == FindKind.SHOPPING -> "places to buy $w"
            else -> w
        }
        val good = if (r.best) "top-rated " else ""
        val where = r.area?.let { "in ${cap(it)}" } ?: "near you"
        return "Here are $good$display $where."
    }
}
