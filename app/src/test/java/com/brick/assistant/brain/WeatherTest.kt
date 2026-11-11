package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private fun w(s: String) = IntentParser.parse(s).also { assertTrue("'$s' -> $it", it is Request.Weather) } as Request.Weather

    @Test
    fun aspects() {
        assertEquals(WeatherAspect.GENERAL, w("what's the weather").aspect)
        assertEquals(WeatherAspect.HUMIDITY, w("how humid is it").aspect)
        assertEquals(WeatherAspect.HUMIDITY, w("what's the humidity in Miami").aspect)
        assertEquals(WeatherAspect.UV, w("what's the uv index today").aspect)
        assertEquals(WeatherAspect.UV, w("do I need sunscreen tomorrow").aspect)
        assertEquals(WeatherAspect.WIND, w("how windy is it").aspect)
        assertEquals(WeatherAspect.WIND, w("what's the wind speed in Chicago").aspect)
        assertEquals(WeatherAspect.RAIN_CHANCE, w("will it rain tomorrow").aspect)
        assertEquals(WeatherAspect.RAIN_CHANCE, w("what's the chance of rain").aspect)
        assertEquals(WeatherAspect.RAIN_AMOUNT, w("how much rain will we get today").aspect)
        assertEquals(WeatherAspect.SNOW_CHANCE, w("is it going to snow tomorrow").aspect)
        assertEquals(WeatherAspect.SNOW_AMOUNT, w("how much snow is coming tomorrow").aspect)
        assertEquals(WeatherAspect.SNOW_AMOUNT, w("how many inches of snow tomorrow in Denver").aspect)
        assertTrue(w("how much snow is coming tomorrow").tomorrow)
        assertEquals("Denver", w("how many inches of snow tomorrow in Denver").city)
    }

    private val sample = WeatherData(
        place = "Chicago", tomorrow = false, imperial = true, temp = 72, high = 75, low = 60,
        conditions = "partly cloudy skies", conditionsAreNouns = true, humidity = 64, uvIndex = 6,
        windSpeed = 12, windGust = 25, windFrom = "the northwest", rainChance = 40, rainAmount = 0.3,
        snowChance = 0, snowAmount = 0.0, source = "test",
    )

    @Test
    fun shortAnswers() {
        assertEquals(WeatherAspect.TEMPERATURE, w("what is the temperature").aspect)
        assertEquals(WeatherAspect.TEMPERATURE, w("how cold is it").aspect)
        val home = sample.copy(place = null)
        assertEquals("72 degrees.", WeatherSource.speak(home, WeatherAspect.TEMPERATURE))
        assertEquals("64 percent humidity.", WeatherSource.speak(home, WeatherAspect.HUMIDITY))
        assertEquals("72 degrees and partly cloudy. High 75, low 60.", WeatherSource.speak(home, WeatherAspect.GENERAL))
        assertEquals("In Chicago, 64 percent humidity.", WeatherSource.speak(sample, WeatherAspect.HUMIDITY))
        assertEquals("In Chicago, UV index 6, high.", WeatherSource.speak(sample, WeatherAspect.UV))
        assertEquals("In Chicago, wind 12 miles per hour from the northwest, gusts to 25.", WeatherSource.speak(sample, WeatherAspect.WIND))
        assertEquals("In Chicago, 40 percent chance of rain.", WeatherSource.speak(sample, WeatherAspect.RAIN_CHANCE))
        assertEquals("In Chicago, about 0.3 inches of rain.", WeatherSource.speak(sample, WeatherAspect.RAIN_AMOUNT))
        assertEquals("In Chicago, no snow expected.", WeatherSource.speak(sample, WeatherAspect.SNOW_CHANCE))
        val snowy = sample.copy(tomorrow = true, snowChance = 80, snowAmount = 3.2)
        assertEquals("In Chicago, about 3.2 inches of snow tomorrow.", WeatherSource.speak(snowy, WeatherAspect.SNOW_AMOUNT))
        assertEquals("In Chicago, high of 75, low of 60 tomorrow.", WeatherSource.speak(snowy, WeatherAspect.TEMPERATURE))
    }}
