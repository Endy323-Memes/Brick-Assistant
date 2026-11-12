package com.brick.assistant.brain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real lookups against the no-key sources (Chicago): gradlew testDebugUnitTest -PliveNet=true */
class WeatherLiveTest {
    @Test
    fun liveWeek() {
        if (System.getProperty("liveNet") != "true") return
        runBlocking {
            val nws = WeatherProvider.NWS.fetch(WeatherProvider.TEST_PLACE, true, "")
            println("NWS: ${nws?.place} " + nws?.days?.joinToString { "${it.date} ${it.high}/${it.low} ${it.conditions} r${it.rainChance} s${it.snowChance}" })
            assertTrue(!nws?.days.isNullOrEmpty())
            for (source in listOf(WeatherConfig.OPEN_METEO, "nws")) {
                val w = WeatherSource.fetchWeek(WeatherProvider.TEST_PLACE, true, WeatherConfig(source))
                println("$source week: ${w?.source} ${w?.days?.size} days")
                println(w?.let { WeatherSource.speakWeek(it, WeatherSpan.WEEK, WeatherAspect.GENERAL) })
                assertTrue((w?.days?.size ?: 0) >= 7)
            }
        }
    }
}
