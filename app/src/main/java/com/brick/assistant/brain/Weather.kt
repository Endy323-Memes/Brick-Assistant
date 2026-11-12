package com.brick.assistant.brain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Which part of the weather the user asked about. */
enum class WeatherAspect { GENERAL, TEMPERATURE, HUMIDITY, UV, WIND, RAIN_CHANCE, RAIN_AMOUNT, SNOW_CHANCE, SNOW_AMOUNT }

/** One day, or the week ahead ([WEEK], 7 days from today), or the coming Saturday and Sunday ([WEEKEND]). */
enum class WeatherSpan { DAY, WEEK, WEEKEND }

/** One day of a [WeekForecast]. */
data class DayForecast(
    val date: java.time.LocalDate,
    val high: Int?,
    val low: Int?,
    val conditions: String,
    val rainChance: Int?,
    val snowChance: Int?,
    val source: String,
)

/** Day-by-day forecast, today first. AccuWeather's free key covers 5 days; Open-Meteo fills in the rest. */
data class WeekForecast(val place: String?, val imperial: Boolean, val days: List<DayForecast>) {
    val source: String get() = days.map { it.source }.distinct().joinToString(" + ")
}

/** One day's weather, from AccuWeather or Open-Meteo. Numbers are already in the user's units. */
data class WeatherData(
    val place: String?,
    val tomorrow: Boolean,
    val imperial: Boolean,
    val temp: Int?,              // current temperature (today only)
    val high: Int?,
    val low: Int?,
    val conditions: String,      // e.g. "partly cloudy skies" (Open-Meteo) or "partly sunny" (AccuWeather)
    /** True when [conditions] is a noun phrase ("clear skies") rather than an adjective ("sunny"). */
    val conditionsAreNouns: Boolean,
    val humidity: Int?,          // percent
    val uvIndex: Int?,
    val windSpeed: Int?,         // mph or km/h
    val windGust: Int?,
    val windFrom: String?,       // e.g. "the northwest"
    val rainChance: Int?,        // percent
    val rainAmount: Double?,     // inches or mm
    val snowChance: Int?,
    val snowAmount: Double?,     // inches or cm
    val source: String,
)

object WeatherSource {
    private const val TAG = "BrickWeather"
    private val locationKeys = HashMap<String, Pair<String, String?>>()

    /**
     * One day's weather from the source picked in Settings: "auto" is AccuWeather when its key is set, else
     * Open-Meteo. Any source that fails (or has no key) falls back to Open-Meteo.
     */
    suspend fun fetch(place: WeatherService.Place, imperial: Boolean, tomorrow: Boolean, config: WeatherConfig): WeatherData? =
        withContext(Dispatchers.IO) {
            val accuKey = config.key(WeatherConfig.ACCUWEATHER)
            when (val src = config.source) {
                WeatherConfig.OPEN_METEO -> {}
                WeatherConfig.AUTO, WeatherConfig.ACCUWEATHER -> if (accuKey.isNotBlank()) {
                    accuWeather(place, imperial, tomorrow, accuKey)?.let { return@withContext it }
                    Log.w(TAG, "AccuWeather failed; using Open-Meteo")
                }
                else -> WeatherProvider.of(src)?.let { p ->
                    val key = config.key(p.id)
                    if (!p.needsKey || key.isNotBlank()) {
                        p.fetch(place, imperial, key)?.let { f -> toWeatherData(f, p.label, place, imperial, tomorrow) }?.let { return@withContext it }
                    }
                    Log.w(TAG, "${p.label} failed or has no key; using Open-Meteo")
                }
            }
            openMeteo(place, imperial, tomorrow)
        }

    /** Checks a source's key against a test city: null when it works, else what went wrong. */
    suspend fun testKey(sourceId: String, key: String): String? = withContext(Dispatchers.IO) {
        if (sourceId == WeatherConfig.ACCUWEATHER) return@withContext testAccuWeatherKey(key)
        val p = WeatherProvider.of(sourceId) ?: return@withContext "Unknown weather source."
        val f = p.fetch(WeatherProvider.TEST_PLACE, true, key.trim())
        when {
            f == null -> "${p.label} rejected the key or is unreachable."
            f.days.isEmpty() -> "${p.label} answered but returned no forecast."
            else -> null
        }
    }

    private fun windWords(v: String?): String? = v?.let { s -> s.toDoubleOrNull()?.let { compassWords(degreesToCompass(it)) } ?: compassWords(s) }

    private fun toWeatherData(f: ProviderForecast, label: String, place: WeatherService.Place, imperial: Boolean, tomorrow: Boolean): WeatherData? {
        val today = java.time.LocalDate.now()
        val d = f.days.firstOrNull { it.date == today.plusDays(if (tomorrow) 1 else 0) } ?: f.days.getOrNull(if (tomorrow) 1 else 0) ?: return null
        val c = if (tomorrow) null else f.current
        return WeatherData(
            place = place.name ?: f.place,
            tomorrow = tomorrow,
            imperial = imperial,
            temp = c?.temp,
            high = d.high,
            low = d.low,
            conditions = (c?.conditions ?: d.conditions).ifBlank { "mixed conditions" },
            conditionsAreNouns = false,
            humidity = c?.humidity ?: d.humidity,
            uvIndex = c?.uv ?: d.uv,
            windSpeed = c?.wind ?: d.wind,
            windGust = c?.gust ?: d.gust,
            windFrom = windWords(c?.windFrom ?: d.windFrom),
            rainChance = d.rainChance,
            rainAmount = d.rainAmount,
            snowChance = d.snowChance,
            snowAmount = d.snowAmount,
            source = label,
        )
    }

    // ---------------------------------------------------------------- AccuWeather

    private const val ACCU = "https://dataservice.accuweather.com"

    private fun accuGet(url: String, key: String): String? = try {
        val sep = if (url.contains("?")) "&" else "?"
        val c = URL("$url${sep}apikey=$key").openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        val code = c.responseCode
        val body = if (code == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        if (code != 200) Log.w(TAG, "AccuWeather HTTP $code for ${url.substringBefore('?')}")
        c.disconnect()
        body
    } catch (e: Exception) {
        null
    }

    /** Checks a key: returns null when it works, else a short problem description. */
    suspend fun testAccuWeatherKey(key: String): String? = withContext(Dispatchers.IO) {
        val r = accuGet("$ACCU/locations/v1/cities/search?q=Chicago", key.trim()) ?: return@withContext "AccuWeather rejected the key or is unreachable."
        if (JSONArray(r).length() == 0) "AccuWeather answered but returned no data." else null
    }

    private fun accuWeather(place: WeatherService.Place, imperial: Boolean, tomorrow: Boolean, key: String): WeatherData? {
        val geo = "%.3f,%.3f".format(Locale.US, place.lat, place.lon)
        val (loc, locName) = locationKeys[geo] ?: run {
            val o = accuGet("$ACCU/locations/v1/cities/geoposition/search?q=$geo", key)?.let { JSONObject(it) } ?: return null
            (o.optString("Key") to o.optString("LocalizedName").ifBlank { null }).also { locationKeys[geo] = it }
        }
        if (loc.isBlank()) return null
        val daily = accuGet("$ACCU/forecasts/v1/daily/5day/$loc?details=true&metric=${!imperial}", key)
            ?.let { JSONObject(it).optJSONArray("DailyForecasts")?.optJSONObject(if (tomorrow) 1 else 0) } ?: return null
        val day = daily.optJSONObject("Day")
        val night = daily.optJSONObject("Night")
        fun maxOf2(f: (JSONObject) -> Int?) = listOfNotNull(day?.let(f), night?.let(f)).maxOrNull()
        fun sumOf2(field: String) = listOfNotNull(day, night).sumOf { it.optJSONObject(field)?.optDouble("Value", 0.0) ?: 0.0 }
        val uv = daily.optJSONArray("AirAndPollen")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("Name") == "UVIndex" }?.optInt("Value")
        }
        var temp: Int? = null
        var humidity: Int? = day?.optJSONObject("RelativeHumidity")?.optInt("Average")
        var windSpeed = day?.optJSONObject("Wind")?.optJSONObject("Speed")?.optDouble("Value")?.roundToInt()
        var windGust = day?.optJSONObject("WindGust")?.optJSONObject("Speed")?.optDouble("Value")?.roundToInt()
        var windFrom = day?.optJSONObject("Wind")?.optJSONObject("Direction")?.optString("English")
        var uvNow: Int? = uv
        var conditions = day?.optString("IconPhrase").orEmpty()
        if (!tomorrow) {
            accuGet("$ACCU/currentconditions/v1/$loc?details=true", key)?.let { JSONArray(it).optJSONObject(0) }?.let { cur ->
                val sys = if (imperial) "Imperial" else "Metric"
                temp = cur.optJSONObject("Temperature")?.optJSONObject(sys)?.optDouble("Value")?.roundToInt()
                humidity = cur.optInt("RelativeHumidity", humidity ?: 0)
                uvNow = if (cur.has("UVIndex")) cur.optInt("UVIndex") else uv
                cur.optJSONObject("Wind")?.let { w ->
                    windSpeed = w.optJSONObject("Speed")?.optJSONObject(sys)?.optDouble("Value")?.roundToInt() ?: windSpeed
                    windFrom = w.optJSONObject("Direction")?.optString("English") ?: windFrom
                }
                windGust = cur.optJSONObject("WindGust")?.optJSONObject("Speed")?.optJSONObject(sys)?.optDouble("Value")?.roundToInt() ?: windGust
                conditions = cur.optString("WeatherText").ifBlank { conditions }
            }
        }
        val temps = daily.optJSONObject("Temperature")
        return WeatherData(
            place = place.name ?: locName,
            tomorrow = tomorrow,
            imperial = imperial,
            temp = temp,
            high = temps?.optJSONObject("Maximum")?.optDouble("Value")?.roundToInt(),
            low = temps?.optJSONObject("Minimum")?.optDouble("Value")?.roundToInt(),
            conditions = conditions.lowercase(Locale.US).replace("w/", "with").ifBlank { "mixed conditions" },
            conditionsAreNouns = false,
            humidity = humidity,
            uvIndex = uvNow,
            windSpeed = windSpeed,
            windGust = windGust,
            windFrom = windFrom?.let(::compassWords),
            rainChance = maxOf2 { it.optInt("RainProbability") },
            rainAmount = sumOf2("Rain"),
            snowChance = maxOf2 { it.optInt("SnowProbability") },
            snowAmount = sumOf2("Snow"),
            source = "AccuWeather",
        )
    }

    // ---------------------------------------------------------------- Open-Meteo (no key needed)

    private fun openMeteo(place: WeatherService.Place, imperial: Boolean, tomorrow: Boolean): WeatherData? {
        val o = Http.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,rain_sum,showers_sum," +
                "snowfall_sum,uv_index_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,relative_humidity_2m_mean" +
                "&temperature_unit=${if (imperial) "fahrenheit" else "celsius"}" +
                "&wind_speed_unit=${if (imperial) "mph" else "kmh"}" +
                "&precipitation_unit=${if (imperial) "inch" else "mm"}" +
                "&timezone=auto&forecast_days=2",
        ) ?: return null
        val d = o.optJSONObject("daily") ?: return null
        val i = if (tomorrow) 1 else 0
        fun dd(name: String) = d.optJSONArray(name)?.optDouble(i)?.takeIf { !it.isNaN() }
        val cur = if (tomorrow) null else o.optJSONObject("current")
        fun cd(name: String) = cur?.optDouble(name)?.takeIf { !it.isNaN() }
        val code = cur?.optInt("weather_code") ?: d.optJSONArray("weather_code")?.optInt(i) ?: 0
        val precipChance = dd("precipitation_probability_max")?.roundToInt()
        val rain = (dd("rain_sum") ?: 0.0) + (dd("showers_sum") ?: 0.0)
        // Open-Meteo reports snowfall in cm even with inch precipitation units.
        val snow = (dd("snowfall_sum") ?: 0.0).let { if (imperial) it / 2.54 else it }
        val snowy = snow > 0.0 || code in setOf(71, 73, 75, 77, 85, 86)
        val rainy = rain > 0.0 || code in setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
        return WeatherData(
            place = place.name,
            tomorrow = tomorrow,
            imperial = imperial,
            temp = cd("temperature_2m")?.roundToInt(),
            high = dd("temperature_2m_max")?.roundToInt(),
            low = dd("temperature_2m_min")?.roundToInt(),
            conditions = WeatherService.describe(code),
            conditionsAreNouns = true,
            humidity = (cd("relative_humidity_2m") ?: dd("relative_humidity_2m_mean"))?.roundToInt(),
            uvIndex = (cd("uv_index") ?: dd("uv_index_max"))?.roundToInt(),
            windSpeed = (cd("wind_speed_10m") ?: dd("wind_speed_10m_max"))?.roundToInt(),
            windGust = (cd("wind_gusts_10m") ?: dd("wind_gusts_10m_max"))?.roundToInt(),
            windFrom = (cd("wind_direction_10m") ?: dd("wind_direction_10m_dominant"))?.let { compassWords(degreesToCompass(it)) },
            rainChance = if (snowy && !rainy) 0 else precipChance,
            rainAmount = rain,
            snowChance = if (snowy) precipChance else 0,
            snowAmount = snow,
            source = "Open-Meteo",
        )
    }

    // ---------------------------------------------------------------- the week ahead

    /**
     * Seven days from today. With an AccuWeather key its 5-day forecast (all the free key allows) comes first and
     * Open-Meteo (no key, 16 days) fills in the days after it; without a key it's all Open-Meteo.
     */
    suspend fun fetchWeek(place: WeatherService.Place, imperial: Boolean, config: WeatherConfig, days: Int = 7): WeekForecast? =
        withContext(Dispatchers.IO) {
            val accuKey = config.key(WeatherConfig.ACCUWEATHER)
            val picked = WeatherProvider.of(config.source)
            var name: String? = null
            // The chosen source first (AccuWeather in "auto" when it has a key); Open-Meteo fills the rest.
            val accu = when {
                picked != null -> config.key(picked.id).takeIf { !picked.needsKey || it.isNotBlank() }
                    ?.let { picked.fetch(place, imperial, it) }
                    ?.also { name = it.place }
                    ?.days?.map { DayForecast(it.date, it.high, it.low, it.conditions, it.rainChance, it.snowChance, picked.label) }
                config.source == WeatherConfig.OPEN_METEO -> null
                accuKey.isNotBlank() -> accuWeek(place, imperial, accuKey)
                else -> null
            }
            val meteo = if ((accu?.size ?: 0) < days) openMeteoWeek(place, imperial, days) else null
            val merged = (accu.orEmpty() + meteo.orEmpty().filter { m -> accu.orEmpty().none { it.date == m.date } })
                .sortedBy { it.date }
                .filter { !it.date.isBefore(java.time.LocalDate.now()) }
                .take(days)
            if (merged.isEmpty()) null else WeekForecast(place.name ?: name ?: accuName(place), imperial, merged)
        }

    private fun accuName(place: WeatherService.Place): String? = locationKeys["%.3f,%.3f".format(Locale.US, place.lat, place.lon)]?.second

    private fun accuWeek(place: WeatherService.Place, imperial: Boolean, key: String): List<DayForecast>? {
        val geo = "%.3f,%.3f".format(Locale.US, place.lat, place.lon)
        val (loc, _) = locationKeys[geo] ?: run {
            val o = accuGet("$ACCU/locations/v1/cities/geoposition/search?q=$geo", key)?.let { JSONObject(it) } ?: return null
            (o.optString("Key") to o.optString("LocalizedName").ifBlank { null }).also { locationKeys[geo] = it }
        }
        if (loc.isBlank()) return null
        val arr = accuGet("$ACCU/forecasts/v1/daily/5day/$loc?details=true&metric=${!imperial}", key)
            ?.let { JSONObject(it).optJSONArray("DailyForecasts") } ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val date = d.optString("Date").take(10).let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val day = d.optJSONObject("Day")
            val night = d.optJSONObject("Night")
            fun most(f: String) = listOfNotNull(day, night).maxOfOrNull { it.optInt(f) }
            val t = d.optJSONObject("Temperature")
            DayForecast(
                date = date,
                high = t?.optJSONObject("Maximum")?.optDouble("Value")?.roundToInt(),
                low = t?.optJSONObject("Minimum")?.optDouble("Value")?.roundToInt(),
                conditions = day?.optString("IconPhrase").orEmpty().lowercase(Locale.US).replace("w/", "with").ifBlank { "mixed conditions" },
                rainChance = most("RainProbability"),
                snowChance = most("SnowProbability"),
                source = "AccuWeather",
            )
        }
    }

    private fun openMeteoWeek(place: WeatherService.Place, imperial: Boolean, days: Int): List<DayForecast>? {
        val o = Http.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,snowfall_sum" +
                "&temperature_unit=${if (imperial) "fahrenheit" else "celsius"}" +
                "&timezone=auto&forecast_days=${days.coerceIn(1, 16)}",
        ) ?: return null
        val d = o.optJSONObject("daily") ?: return null
        val time = d.optJSONArray("time") ?: return null
        fun num(name: String, i: Int) = d.optJSONArray(name)?.optDouble(i)?.takeIf { !it.isNaN() }
        return (0 until time.length()).mapNotNull { i ->
            val date = runCatching { java.time.LocalDate.parse(time.getString(i)) }.getOrNull() ?: return@mapNotNull null
            val code = d.optJSONArray("weather_code")?.optInt(i) ?: 0
            val chance = num("precipitation_probability_max", i)?.roundToInt()
            val snowy = (num("snowfall_sum", i) ?: 0.0) > 0.0 || code in setOf(71, 73, 75, 77, 85, 86)
            DayForecast(
                date = date,
                high = num("temperature_2m_max", i)?.roundToInt(),
                low = num("temperature_2m_min", i)?.roundToInt(),
                conditions = WeatherService.describe(code).removeSuffix(" skies"),
                rainChance = if (snowy) 0 else chance,
                snowChance = if (snowy) chance else 0,
                source = "Open-Meteo",
            )
        }
    }

    /** "Today", "Tomorrow", "Friday". */
    fun dayName(date: java.time.LocalDate, today: java.time.LocalDate = java.time.LocalDate.now()): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
    }

    /** The days a [WeatherSpan] covers. */
    fun daysFor(w: WeekForecast, span: WeatherSpan, today: java.time.LocalDate = java.time.LocalDate.now()): List<DayForecast> = when (span) {
        WeatherSpan.WEEKEND -> {
            // The coming Saturday and Sunday (today and tomorrow when it's already the weekend).
            val sat = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) today.minusDays(1) else today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
            w.days.filter { !it.date.isBefore(today) && !it.date.isBefore(sat) && !it.date.isAfter(sat.plusDays(1)) }
        }
        else -> w.days
    }

    /**
     * The week (or weekend) out loud. A rain / snow / temperature question gets just that ("Rain is likely Thursday
     * and Saturday."); anything else gets each day's sky and high.
     */
    fun speakWeek(w: WeekForecast, span: WeatherSpan, aspect: WeatherAspect, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val days = daysFor(w, span, today)
        val inPlace = w.place?.let { "In $it, " } ?: ""
        val period = if (span == WeatherSpan.WEEKEND) "this weekend" else "this week"
        if (days.isEmpty()) return "I don't have a forecast for $period yet."
        fun cap(s: String) = if (inPlace.isEmpty()) s.replaceFirstChar { it.uppercase() } else inPlace + s
        // "today" / "tomorrow" / "Friday" inside a sentence, and "today" / "tomorrow" / "on Friday".
        fun inline(d: DayForecast) = dayName(d.date, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }
        fun on(d: DayForecast) = inline(d).let { if (it == "today" || it == "tomorrow") it else "on $it" }
        fun night(d: DayForecast) = inline(d).let { if (it == "today") "tonight" else "$it night" }
        fun list(xs: List<String>) = when (xs.size) { 1 -> xs[0]; 2 -> "${xs[0]} and ${xs[1]}"; else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last() }
        fun chances(pick: (DayForecast) -> Int?, what: String): String {
            val likely = days.filter { (pick(it) ?: 0) >= 40 }
            if (likely.isEmpty()) {
                val top = days.maxByOrNull { pick(it) ?: 0 }!!
                val best = pick(top) ?: 0
                return if (best <= 10) cap("no $what expected $period.") else cap("$what is unlikely $period; the best chance is $best percent ${on(top)}.")
            }
            return cap("$what is likely " + list(likely.map { "${inline(it)} (${pick(it)} percent)" }) + ".")
        }
        return when (aspect) {
            WeatherAspect.RAIN_CHANCE, WeatherAspect.RAIN_AMOUNT -> chances({ it.rainChance }, "rain")
            WeatherAspect.SNOW_CHANCE, WeatherAspect.SNOW_AMOUNT -> chances({ it.snowChance }, "snow")
            WeatherAspect.TEMPERATURE -> {
                val withHigh = days.filter { it.high != null }
                val warm = withHigh.maxByOrNull { it.high!! }
                val cold = days.filter { it.low != null }.minByOrNull { it.low!! }
                if (warm == null || cold == null) cap("no temperatures in the forecast $period.")
                else cap("highs from ${withHigh.minOf { it.high!! }} to ${warm.high} $period, warmest ${on(warm)}. The coldest is ${night(cold)}, at ${cold.low} degrees.")
            }
            else -> {
                val parts = days.map { d ->
                    val rain = (d.snowChance ?: 0).takeIf { it >= 30 }?.let { ", $it percent chance of snow" }
                        ?: (d.rainChance ?: 0).takeIf { it >= 30 }?.let { ", $it percent chance of rain" } ?: ""
                    // Late in the day some sources only have tonight: give its low.
                    val temp = d.high?.let { "high $it" } ?: d.low?.let { "low $it" } ?: "no temperature yet"
                    "${dayName(d.date, today).let { if (it == "Today" && d.high == null) "Tonight" else it }}, ${d.conditions}, $temp$rain."
                }
                (if (inPlace.isEmpty()) "Here's $period. " else "Here's $period in ${w.place}. ") + parts.joinToString(" ")
            }
        }
    }

    private fun degreesToCompass(deg: Double): String {
        val dirs = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return dirs[((deg % 360 + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    }

    private fun compassWords(c: String): String {
        val w = mapOf('N' to "north", 'S' to "south", 'E' to "east", 'W' to "west")
        val s = c.uppercase(Locale.US).filter { it in "NSEW" }
        if (s.isEmpty()) return c
        val words = when (s.length) {
            1 -> w[s[0]]!!
            2 -> w[s[0]]!! + w[s[1]]!!
            else -> w[s[0]]!! + "-" + w[s[1]]!! + w[s[2]]!!
        }
        return "the $words"
    }

    // ---------------------------------------------------------------- what the robot says

    /** "an" before numbers spoken with a vowel sound (8, 11, 18, 80-89), else "a". */
    private fun article(n: Int) = if (n == 8 || n == 11 || n == 18 || n in 80..89) "an" else "a"

    fun speak(w: WeatherData, aspect: WeatherAspect): String {
        val where = w.place?.let { " in $it" } ?: ""
        val day = if (w.tomorrow) "tomorrow" else "today"
        val speedUnit = if (w.imperial) "miles per hour" else "kilometers per hour"
        fun amount(v: Double, snow: Boolean): String {
            val unit = if (w.imperial) "inches" else if (snow) "centimeters" else "millimeters"
            val r = if (w.imperial) (v * 10).roundToInt() / 10.0 else v.roundToInt().toDouble()
            val n = if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString()
            return if (n == "1") "1 ${unit.removeSuffix("es").removeSuffix("s")}" else "$n $unit"
        }
        // Short answers: just what you asked for. A named city is mentioned first ("In Chicago, ...").
        val inPlace = w.place?.let { "In $it, " } ?: ""
        fun cap(s: String) = if (inPlace.isEmpty()) s.replaceFirstChar { it.uppercase() } else inPlace + s
        val tmr = if (w.tomorrow) " tomorrow" else ""
        return when (aspect) {
            WeatherAspect.TEMPERATURE ->
                if (w.tomorrow || w.temp == null) cap("high of ${w.high}, low of ${w.low}$tmr.")
                else cap("${w.temp} degrees.")
            WeatherAspect.HUMIDITY -> w.humidity?.let { cap("$it percent humidity$tmr.") } ?: cap("no humidity reading$tmr.")
            WeatherAspect.UV -> w.uvIndex?.let {
                val level = when {
                    it <= 2 -> "low"
                    it <= 5 -> "moderate"
                    it <= 7 -> "high"
                    it <= 10 -> "very high"
                    else -> "extreme"
                }
                cap("UV index $it, $level$tmr.")
            } ?: cap("no UV reading$tmr.")
            WeatherAspect.WIND -> w.windSpeed?.let { sp ->
                val from = w.windFrom?.let { " from $it" } ?: ""
                val gust = w.windGust?.takeIf { g -> g > sp + 3 }?.let { g -> ", gusts to $g" } ?: ""
                cap("wind $sp $speedUnit$from$gust$tmr.")
            } ?: cap("no wind reading$tmr.")
            WeatherAspect.RAIN_CHANCE -> when (val c = w.rainChance) {
                null -> cap("no rain forecast$tmr.")
                0 -> cap("no rain expected$tmr.")
                else -> cap("$c percent chance of rain$tmr.")
            }
            WeatherAspect.RAIN_AMOUNT -> {
                val a = w.rainAmount ?: 0.0
                if (a < 0.01) cap("no rain expected$tmr.") else cap("about ${amount(a, false)} of rain$tmr.")
            }
            WeatherAspect.SNOW_CHANCE -> when (val c = w.snowChance) {
                null -> cap("no snow forecast$tmr.")
                0 -> cap("no snow expected$tmr.")
                else -> cap("$c percent chance of snow$tmr.")
            }
            WeatherAspect.SNOW_AMOUNT -> {
                val a = w.snowAmount ?: 0.0
                if (a < 0.05) cap("no snow expected$tmr.") else cap("about ${amount(a, true)} of snow$tmr.")
            }
            WeatherAspect.GENERAL -> {
                val sky = w.conditions.removeSuffix(" skies")
                if (w.tomorrow || w.temp == null) cap("$sky$tmr, high ${w.high}, low ${w.low}.")
                else cap("${w.temp} degrees and $sky. High ${w.high}, low ${w.low}.")
            }
        }    }
}
