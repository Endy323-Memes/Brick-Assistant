package com.brick.assistant.brain

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/** Which weather source to use, and the API keys you've entered (by [WeatherProvider.id]). */
data class WeatherConfig(val source: String = AUTO, val keys: Map<String, String> = emptyMap()) {
    fun key(id: String): String = keys[id].orEmpty().trim()

    companion object {
        /** AccuWeather when its key is set, else Open-Meteo (Brick's original behavior). */
        const val AUTO = "auto"
        const val ACCUWEATHER = "accuweather"
        const val OPEN_METEO = "open_meteo"
    }
}

/** A day from any source, with everything a one-day answer can use. Numbers in the user's units. */
internal data class DailyDetail(
    val date: LocalDate,
    val high: Int?,
    val low: Int?,
    val conditions: String,
    val humidity: Int? = null,
    val uv: Int? = null,
    val wind: Int? = null,
    val gust: Int? = null,
    val windFrom: String? = null, // compass letters ("NW") or degrees as text
    val rainChance: Int? = null,
    val rainAmount: Double? = null,
    val snowChance: Int? = null,
    val snowAmount: Double? = null,
)

/** Right now, when the source reports it. */
internal data class CurrentDetail(
    val temp: Int?,
    val conditions: String?,
    val humidity: Int? = null,
    val uv: Int? = null,
    val wind: Int? = null,
    val gust: Int? = null,
    val windFrom: String? = null,
)

internal data class ProviderForecast(val place: String?, val current: CurrentDetail?, val days: List<DailyDetail>)

/**
 * The extra weather sources you can pick in Settings → Weather (AccuWeather and Open-Meteo are built into
 * [WeatherSource]). Each one fetches today plus the days ahead in one call; Brick fills any missing days from
 * Open-Meteo.
 */
enum class WeatherProvider(
    val id: String,
    val label: String,
    val needsKey: Boolean,
    val keyUrl: String?,
    /** Shown under the key field in Settings. */
    val note: String,
) {
    PIRATE("pirate_weather", "Pirate Weather", true, "https://pirate-weather.apiable.io/", "Free key, 8 days, worldwide."),
    VISUAL_CROSSING("visual_crossing", "Visual Crossing", true, "https://www.visualcrossing.com/sign-up", "Free key (1,000 calls a day), 15 days, worldwide."),
    WEATHER_API("weatherapi", "WeatherAPI.com", true, "https://www.weatherapi.com/signup.aspx", "Free key; the free plan gives 3 days, Open-Meteo fills in the rest."),
    OPEN_WEATHER("openweathermap", "OpenWeatherMap", true, "https://openweathermap.org/api/one-call-3", "One Call 3.0 key: 1,000 free calls a day, but OpenWeatherMap asks for a card to turn it on. 8 days."),
    NWS("nws", "US National Weather Service", false, null, "No key. United States only, 7 days."),
    ;

    internal fun fetch(place: WeatherService.Place, imperial: Boolean, key: String): ProviderForecast? = try {
        when (this) {
            PIRATE -> pirate(place, imperial, key)
            VISUAL_CROSSING -> visualCrossing(place, imperial, key)
            WEATHER_API -> weatherApi(place, imperial, key)
            OPEN_WEATHER -> openWeather(place, imperial, key)
            NWS -> nws(place, imperial)
        }
    } catch (e: Exception) {
        android.util.Log.w("BrickWeather", "$label failed: ${e.message}")
        null
    }

    companion object {
        fun of(id: String): WeatherProvider? = entries.firstOrNull { it.id == id }

        /** Chicago, for testing a key. */
        val TEST_PLACE = WeatherService.Place(41.8781, -87.6298, "Chicago")

        private fun pct(v: Double?) = v?.takeIf { !it.isNaN() }?.let { (it * 100).roundToInt() }
        private fun JSONObject.num(name: String) = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
        private fun JSONObject.int(name: String) = num(name)?.roundToInt()

        /** Splits one precipitation chance between rain and snow by what's expected to fall. */
        private fun split(chance: Int?, snowy: Boolean, rainy: Boolean): Pair<Int?, Int?> = when {
            snowy && !rainy -> 0 to chance
            snowy -> chance to chance
            else -> chance to 0
        }

        // ---------------------------------------------------------------- Pirate Weather (Dark Sky format)
        private fun pirate(place: WeatherService.Place, imperial: Boolean, key: String): ProviderForecast? {
            // "us": °F, mph, inches. "ca": °C, km/h, accumulation in cm.
            val o = Http.getJson("https://api.pirateweather.net/forecast/${Http.enc(key)}/${place.lat},${place.lon}?units=${if (imperial) "us" else "ca"}&exclude=minutely,hourly,alerts")
                ?: return null
            val zone = runCatching { ZoneId.of(o.optString("timezone")) }.getOrDefault(ZoneId.systemDefault())
            val cur = o.optJSONObject("currently")?.let { c ->
                CurrentDetail(c.int("temperature"), c.optString("summary").ifBlank { null }?.lowercase(Locale.US), pct(c.num("humidity")), c.int("uvIndex"),
                    c.int("windSpeed"), c.int("windGust"), c.num("windBearing")?.toString())
            }
            val arr = o.optJSONObject("daily")?.optJSONArray("data") ?: return null
            val days = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                val type = d.optString("precipType")
                val chance = pct(d.num("precipProbability"))
                val (rainC, snowC) = split(chance, type == "snow", type != "snow")
                val acc = d.num("precipAccumulation") ?: 0.0
                // Accumulation is inches (us) or cm (ca); Brick wants inches, or mm of rain / cm of snow.
                val rain = if (type == "snow") 0.0 else if (imperial) acc else acc * 10
                val snow = if (type == "snow") acc else 0.0
                DailyDetail(
                    date = Instant.ofEpochSecond(d.optLong("time")).atZone(zone).toLocalDate(),
                    high = (d.num("temperatureHigh") ?: d.num("temperatureMax"))?.roundToInt(),
                    low = (d.num("temperatureLow") ?: d.num("temperatureMin"))?.roundToInt(),
                    conditions = d.optString("summary").ifBlank { d.optString("icon").replace('-', ' ') }.lowercase(Locale.US).removeSuffix("."),
                    humidity = pct(d.num("humidity")), uv = d.int("uvIndex"), wind = d.int("windSpeed"), gust = d.int("windGust"),
                    windFrom = d.num("windBearing")?.toString(), rainChance = rainC, rainAmount = rain, snowChance = snowC, snowAmount = snow,
                )
            }
            return ProviderForecast(null, cur, days)
        }

        // ---------------------------------------------------------------- Visual Crossing
        private fun visualCrossing(place: WeatherService.Place, imperial: Boolean, key: String): ProviderForecast? {
            // "us": °F, mph, inches. "metric": °C, km/h, mm (snow in cm).
            val o = Http.getJson(
                "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline/${place.lat},${place.lon}" +
                    "?unitGroup=${if (imperial) "us" else "metric"}&include=days,current&contentType=json&key=${Http.enc(key)}",
            ) ?: return null
            val cur = o.optJSONObject("currentConditions")?.let { c ->
                CurrentDetail(c.int("temp"), c.optString("conditions").ifBlank { null }?.lowercase(Locale.US), c.int("humidity"), c.int("uvindex"),
                    c.int("windspeed"), c.int("windgust"), c.num("winddir")?.toString())
            }
            val arr = o.optJSONArray("days") ?: return null
            val days = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                val types = d.optJSONArray("preciptype")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
                val snowAmt = d.num("snow") ?: 0.0
                val (rainC, snowC) = split(d.int("precipprob"), "snow" in types || snowAmt > 0, "rain" in types || "freezingrain" in types || types.isEmpty())
                DailyDetail(
                    date = LocalDate.parse(d.getString("datetime")),
                    high = d.int("tempmax"), low = d.int("tempmin"),
                    conditions = d.optString("conditions").lowercase(Locale.US).replace("partially", "partly").ifBlank { "mixed conditions" },
                    humidity = d.int("humidity"), uv = d.int("uvindex"), wind = d.int("windspeed"), gust = d.int("windgust"),
                    windFrom = d.num("winddir")?.toString(), rainChance = rainC, rainAmount = if (snowAmt > 0 && "rain" !in types) 0.0 else d.num("precip"),
                    snowChance = snowC, snowAmount = snowAmt,
                )
            }
            return ProviderForecast(null, cur, days)
        }

        // ---------------------------------------------------------------- WeatherAPI.com
        private fun weatherApi(place: WeatherService.Place, imperial: Boolean, key: String): ProviderForecast? {
            val o = Http.getJson("https://api.weatherapi.com/v1/forecast.json?key=${Http.enc(key)}&q=${place.lat},${place.lon}&days=7&aqi=no&alerts=no")
                ?: return null
            val f = if (imperial) "f" else "c"
            val sp = if (imperial) "mph" else "kph"
            val cur = o.optJSONObject("current")?.let { c ->
                CurrentDetail(c.int("temp_$f"), c.optJSONObject("condition")?.optString("text")?.lowercase(Locale.US), c.int("humidity"), c.int("uv"),
                    c.int("wind_$sp"), c.int("gust_$sp"), c.optString("wind_dir").ifBlank { null })
            }
            val arr = o.optJSONObject("forecast")?.optJSONArray("forecastday") ?: return null
            val days = (0 until arr.length()).map { i ->
                val fd = arr.getJSONObject(i)
                val d = fd.getJSONObject("day")
                val snowCm = d.num("totalsnow_cm") ?: 0.0
                DailyDetail(
                    date = LocalDate.parse(fd.getString("date")),
                    high = d.int("maxtemp_$f"), low = d.int("mintemp_$f"),
                    conditions = d.optJSONObject("condition")?.optString("text").orEmpty().lowercase(Locale.US).ifBlank { "mixed conditions" },
                    humidity = d.int("avghumidity"), uv = d.int("uv"), wind = d.int("maxwind_$sp"),
                    rainChance = d.int("daily_chance_of_rain"), rainAmount = d.num(if (imperial) "totalprecip_in" else "totalprecip_mm"),
                    snowChance = d.int("daily_chance_of_snow"), snowAmount = if (imperial) snowCm / 2.54 else snowCm,
                )
            }
            return ProviderForecast(o.optJSONObject("location")?.optString("name")?.ifBlank { null }, cur, days)
        }

        // ---------------------------------------------------------------- OpenWeatherMap One Call 3.0
        private fun openWeather(place: WeatherService.Place, imperial: Boolean, key: String): ProviderForecast? {
            // "imperial": °F, mph. "metric": °C, m/s. Rain and snow are always mm.
            val o = Http.getJson("https://api.openweathermap.org/data/3.0/onecall?lat=${place.lat}&lon=${place.lon}&exclude=minutely,hourly,alerts&units=${if (imperial) "imperial" else "metric"}&appid=${Http.enc(key)}")
                ?: return null
            fun speed(v: Double?) = v?.let { if (imperial) it else it * 3.6 }?.roundToInt()
            val offset = ZoneOffset.ofTotalSeconds(o.optInt("timezone_offset"))
            val cur = o.optJSONObject("current")?.let { c ->
                CurrentDetail(c.int("temp"), c.optJSONArray("weather")?.optJSONObject(0)?.optString("description")?.lowercase(Locale.US), c.int("humidity"),
                    c.int("uvi"), speed(c.num("wind_speed")), speed(c.num("wind_gust")), c.num("wind_deg")?.toString())
            }
            val arr = o.optJSONArray("daily") ?: return null
            val days = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                val rainMm = d.num("rain") ?: 0.0
                val snowMm = d.num("snow") ?: 0.0
                val (rainC, snowC) = split(pct(d.num("pop")), snowMm > 0, rainMm > 0 || snowMm == 0.0)
                DailyDetail(
                    date = Instant.ofEpochSecond(d.optLong("dt")).atOffset(offset).toLocalDate(),
                    high = d.optJSONObject("temp")?.int("max"), low = d.optJSONObject("temp")?.int("min"),
                    conditions = d.optJSONArray("weather")?.optJSONObject(0)?.optString("description").orEmpty().lowercase(Locale.US).ifBlank { "mixed conditions" },
                    humidity = d.int("humidity"), uv = d.int("uvi"), wind = speed(d.num("wind_speed")), gust = speed(d.num("wind_gust")),
                    windFrom = d.num("wind_deg")?.toString(), rainChance = rainC, rainAmount = if (imperial) rainMm / 25.4 else rainMm,
                    snowChance = snowC, snowAmount = if (imperial) snowMm / 25.4 else snowMm / 10,
                )
            }
            return ProviderForecast(null, cur, days)
        }

        // ---------------------------------------------------------------- US National Weather Service
        private fun nws(place: WeatherService.Place, imperial: Boolean): ProviderForecast? {
            val p = Http.getJson("https://api.weather.gov/points/${"%.4f".format(Locale.US, place.lat)},${"%.4f".format(Locale.US, place.lon)}")
                ?.optJSONObject("properties") ?: return null
            val url = p.optString("forecast").ifBlank { return null }
            val city = p.optJSONObject("relativeLocation")?.optJSONObject("properties")?.optString("city")?.ifBlank { null }
            val periods = Http.getJson(url + if (imperial) "" else "?units=si")?.optJSONObject("properties")?.optJSONArray("periods") ?: return null
            data class Acc(var high: Int? = null, var low: Int? = null, var sky: String? = null, var chance: Int? = null, var snow: Boolean = false,
                           var wind: Int? = null, var from: String? = null, var humidity: Int? = null)
            val byDay = LinkedHashMap<LocalDate, Acc>()
            for (i in 0 until periods.length()) {
                val q = periods.getJSONObject(i)
                val date = LocalDate.parse(q.getString("startTime").take(10))
                val a = byDay.getOrPut(date) { Acc() }
                val t = q.int("temperature")
                val sky = q.optString("shortForecast").lowercase(Locale.US)
                val chance = q.optJSONObject("probabilityOfPrecipitation")?.int("value") ?: 0
                if (q.optBoolean("isDaytime")) { a.high = t; a.sky = sky } else { a.low = t; if (a.sky == null) a.sky = sky }
                a.chance = maxOf(a.chance ?: 0, chance)
                if ("snow" in sky || "flurries" in sky) a.snow = true
                // "10 to 20 mph": the top of the range.
                Regex("(\\d+)(?!.*\\d)").find(q.optString("windSpeed"))?.let { m -> a.wind = maxOf(a.wind ?: 0, m.value.toInt()) }
                if (a.from == null) a.from = q.optString("windDirection").ifBlank { null }
                q.optJSONObject("relativeHumidity")?.int("value")?.let { a.humidity = it }
            }
            val days = byDay.map { (date, a) ->
                val (rainC, snowC) = split(a.chance, a.snow, !a.snow || a.sky?.contains("rain") == true)
                DailyDetail(date, a.high, a.low, a.sky ?: "mixed conditions", humidity = a.humidity, wind = a.wind, windFrom = a.from,
                    rainChance = rainC, snowChance = snowC)
            }
            return ProviderForecast(city, null, days)
        }
    }
}
