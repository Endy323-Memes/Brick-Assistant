package com.brick.assistant.brain

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.roundToInt

fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

internal object Http {
    private const val UA = "BrickAssistant/1.0 (Android personal assistant app)"

    fun getJson(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        c.disconnect()
        body?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// -------------------------------------------------------------------- Contacts

data class Contact(val name: String, val number: String)

object ContactsLookup {
    fun canRead(context: Context) = context.hasPermission(Manifest.permission.READ_CONTACTS)

    fun find(context: Context, rawQuery: String): Contact? {
        val query = rawQuery.trim().replace(Regex("^(?:my|the)\\s+", RegexOption.IGNORE_CASE), "")
        if (query.isEmpty()) return null
        if (query.none { it.isLetter() } && query.count { it.isDigit() } >= 3) {
            return Contact(query, query.filter { it.isDigit() || it == '+' })
        }
        if (!canRead(context)) return null

        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        var best: Contact? = null
        var bestScore = 0.0
        val q = query.lowercase()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val n = name.lowercase()
                    var score = when {
                        n == q -> 4.0
                        n.startsWith("$q ") || n.startsWith(q) -> 3.0
                        n.split(" ").any { it == q } -> 2.0
                        else -> 1.0
                    }
                    if (c.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) score += 0.2
                    if (c.getInt(3) != 0) score += 0.3
                    if (score > bestScore) {
                        bestScore = score
                        best = Contact(name, number)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return best
    }
}

// -------------------------------------------------------------------- Search

object SearchService {
    private val questionPrefix = Regex(
        "^(?:who|what|where|when|which)\\s+(?:is|are|was|were|did|does)\\s+(?:a\\s+|an\\s+|the\\s+)?|^tell\\s+me\\s+about\\s+|^define\\s+|^(?:the\\s+)?meaning\\s+of\\s+",
        RegexOption.IGNORE_CASE,
    )

    /** Short spoken answer from DuckDuckGo Instant Answers, then Wikipedia. Null if nothing useful. */
    suspend fun quickAnswer(query: String): String? = withContext(Dispatchers.IO) {
        Http.getJson("https://api.duckduckgo.com/?q=${Http.enc(query)}&format=json&no_html=1&skip_disambig=1")?.let { o ->
            val text = listOf("Answer", "AbstractText", "Definition")
                .map { o.optString(it, "") }
                .firstOrNull { it.isNotBlank() && it.length > 2 }
            if (text != null) return@withContext shorten(text)
        }
        val topic = query.replace(questionPrefix, "").trim().ifBlank { query }
        val search = Http.getJson(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=${Http.enc(topic)}",
        )
        val title = search?.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")
        if (!title.isNullOrBlank()) {
            val summary = Http.getJson("https://en.wikipedia.org/api/rest_v1/page/summary/${Http.enc(title.replace(' ', '_'))}")
            val extract = summary?.optString("extract", "")
            if (!extract.isNullOrBlank()) return@withContext shorten(extract)
        }
        null
    }

    private fun shorten(text: String): String {
        val clean = text.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        val sentences = Regex("(?<=[.!?])\\s+").split(clean)
        var out = ""
        for (s in sentences) {
            if (out.isNotEmpty() && (out.length + s.length > 320)) break
            out = if (out.isEmpty()) s else "$out $s"
            if (out.length > 160) break
        }
        return out.ifBlank { clean.take(320) }
    }
}

// -------------------------------------------------------------------- Weather

object WeatherService {
    class Place(val lat: Double, val lon: Double, val name: String?)

    suspend fun geocode(city: String): Place? = withContext(Dispatchers.IO) {
        val o = Http.getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=${Http.enc(city)}")
        val r = o?.optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
        Place(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", city))
    }

    @SuppressLint("MissingPermission")
    suspend fun deviceLocation(context: Context): Place? {
        if (!context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        var loc: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            if (loc == null || l.time > loc.time) loc = l
        }
        if (loc == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                loc = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        try {
                            lm.getCurrentLocation(provider, null, context.mainExecutor) { l ->
                                if (cont.isActive) cont.resume(l)
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
            }
        }
        val l = loc ?: return null
        val name = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.US).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.locality
            } catch (e: Exception) {
                null
            }
        }
        return Place(l.latitude, l.longitude, name)
    }

    /** Builds the robot's weather sentence. */
    suspend fun report(place: Place, fahrenheit: Boolean, tomorrow: Boolean): String? = withContext(Dispatchers.IO) {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val o = Http.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
                "&temperature_unit=$unit&timezone=auto&forecast_days=2",
        ) ?: return@withContext null
        val daily = o.optJSONObject("daily") ?: return@withContext null
        val idx = if (tomorrow) 1 else 0
        val hi = daily.optJSONArray("temperature_2m_max")?.optDouble(idx)?.roundToInt()
        val lo = daily.optJSONArray("temperature_2m_min")?.optDouble(idx)?.roundToInt()
        val rain = daily.optJSONArray("precipitation_probability_max")?.optDouble(idx, 0.0)?.takeIf { !it.isNaN() }?.roundToInt() ?: 0
        val where = place.name?.let { " in $it" } ?: ""
        val rainPart = if (rain >= 20) ", with a $rain percent chance of precipitation" else ""

        if (tomorrow) {
            val desc = describe(daily.optJSONArray("weather_code")?.optInt(1) ?: 0)
            "Tomorrow's weather$where calls for $desc, with a high of $hi and a low of $lo$rainPart."
        } else {
            val cur = o.optJSONObject("current")
            val temp = cur?.optDouble("temperature_2m")?.roundToInt()
            val desc = describe(cur?.optInt("weather_code") ?: daily.optJSONArray("weather_code")?.optInt(0) ?: 0)
            "Today's weather$where is $temp degrees with $desc. The high is $hi and the low is $lo$rainPart."
        }
    }

    internal fun describe(code: Int): String = when (code) {
        0 -> "clear skies"
        1 -> "mostly clear skies"
        2 -> "partly cloudy skies"
        3 -> "overcast skies"
        45, 48 -> "fog"
        51 -> "light drizzle"
        53 -> "drizzle"
        55 -> "heavy drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light rain showers"
        81 -> "rain showers"
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "mixed conditions"
    }
}

// -------------------------------------------------------------------- Calendar

object CalendarHelper {
    fun canWrite(context: Context) =
        context.hasPermission(Manifest.permission.WRITE_CALENDAR) && context.hasPermission(Manifest.permission.READ_CALENDAR)

    /** Inserts directly into the primary writable calendar. Returns false if that isn't possible. */
    fun insert(context: Context, title: String, startMillis: Long, endMillis: Long, allDay: Boolean): Boolean {
        if (!canWrite(context)) return false
        val calId = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, null,
            )?.use { c ->
                var first: Long? = null
                var primary: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (first == null) first = id
                    if (primary == null && c.getInt(1) == 1) primary = id
                }
                primary ?: first
            }
        } catch (e: Exception) {
            null
        } ?: return false

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
        }
        return try {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        } catch (e: Exception) {
            false
        }
    }
}
