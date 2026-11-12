package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** "What's the weather this week": the day-by-day forecast. */
class WeatherWeekTest {
    private fun span(text: String) = (IntentParser.parse(text) as Request.Weather).span

    @Test
    fun parsesWeekAndWeekend() {
        assertEquals(WeatherSpan.WEEK, span("what's the weather this week"))
        assertEquals(WeatherSpan.WEEK, span("what's this week's weather"))
        assertEquals(WeatherSpan.WEEK, span("7 day forecast"))
        assertEquals(WeatherSpan.WEEK, span("weather for the next few days"))
        assertEquals(WeatherSpan.WEEKEND, span("will it rain this weekend"))
        assertEquals(WeatherSpan.DAY, span("what's the weather tomorrow"))
        val chicago = IntentParser.parse("what's the weather in chicago this week") as Request.Weather
        assertEquals("chicago", chicago.city?.lowercase())
        assertEquals(WeatherSpan.WEEK, chicago.span)
        assertEquals(null, (IntentParser.parse("weather for the week") as Request.Weather).city)
        assertEquals(WeatherAspect.RAIN_CHANCE, (IntentParser.parse("will it rain this week") as Request.Weather).aspect)
    }

    // Wednesday, Nov 11 2026.
    private val today = LocalDate.of(2026, 11, 11)
    private fun day(n: Long, hi: Int, lo: Int, sky: String, rain: Int = 0, snow: Int = 0) =
        DayForecast(today.plusDays(n), hi, lo, sky, rain, snow, if (n < 5) "AccuWeather" else "Open-Meteo")
    private val week = WeekForecast(
        null, true,
        listOf(
            day(0, 52, 38, "partly sunny"), day(1, 48, 40, "rain", rain = 70), day(2, 55, 41, "cloudy", rain = 20),
            day(3, 58, 44, "sunny"), day(4, 50, 33, "showers", rain = 45), day(5, 47, 30, "clear"), day(6, 45, 29, "snow", snow = 60),
        ),
    )

    @Test
    fun speaksTheWeek() {
        val all = WeatherSource.speakWeek(week, WeatherSpan.WEEK, WeatherAspect.GENERAL, today)
        assertTrue(all, all.startsWith("Here's this week. Today, partly sunny, high 52. Tomorrow, rain, high 48, 70 percent chance of rain."))
        assertTrue(all, all.contains("Tuesday, snow, high 45, 60 percent chance of snow."))
        assertEquals("AccuWeather + Open-Meteo", week.source)
        assertEquals("Rain is likely tomorrow (70 percent) and Sunday (45 percent).", WeatherSource.speakWeek(week, WeatherSpan.WEEK, WeatherAspect.RAIN_CHANCE, today))
        assertEquals("Snow is likely Tuesday (60 percent).", WeatherSource.speakWeek(week, WeatherSpan.WEEK, WeatherAspect.SNOW_CHANCE, today))
        assertEquals(
            "Highs from 45 to 58 this week, warmest on Saturday. The coldest is Tuesday night, at 29 degrees.",
            WeatherSource.speakWeek(week, WeatherSpan.WEEK, WeatherAspect.TEMPERATURE, today),
        )
    }

    @Test
    fun speaksTheWeekend() {
        val weekend = WeatherSource.daysFor(week, WeatherSpan.WEEKEND, today)
        assertEquals(listOf(today.plusDays(3), today.plusDays(4)), weekend.map { it.date })
        assertEquals("Rain is likely Sunday (45 percent).", WeatherSource.speakWeek(week, WeatherSpan.WEEKEND, WeatherAspect.RAIN_CHANCE, today))
        val named = week.copy(place = "Chicago")
        assertTrue(WeatherSource.speakWeek(named, WeatherSpan.WEEKEND, WeatherAspect.GENERAL, today).startsWith("Here's this weekend in Chicago. Saturday, sunny"))
    }
}
