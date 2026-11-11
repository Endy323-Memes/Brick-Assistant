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

    /** AccuWeather when a key is set (falls back to Open-Meteo if AccuWeather fails), else Open-Meteo. */
    suspend fun fetch(place: WeatherService.Place, imperial: Boolean, tomorrow: Boolean, accuKey: String): WeatherData? =
        withContext(Dispatchers.IO) {
            if (accuKey.isNotBlank()) {
                accuWeather(place, imperial, tomorrow, accuKey.trim())?.let { return@withContext it }
                Log.w(TAG, "AccuWeather failed; using Open-Meteo")
            }
            openMeteo(place, imperial, tomorrow)
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
            conditions = conditions.lowercase(Locale.US).ifBlank { "mixed conditions" },
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
