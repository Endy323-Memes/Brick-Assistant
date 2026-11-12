package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bortle / light-pollution requests and the sky-quality wording. */
class SkyTest {
    private fun sky(text: String) = IntentParser.parse(text) as? Request.Sky
    private val B = SkyAspect.BORTLE
    private val S = SkyAspect.STARS
    private val M = SkyAspect.MILKY_WAY

    @Test
    fun hereAndPlaces() {
        assertEquals(Request.Sky(aspect = B), sky("what's the bortle level of my location"))
        assertEquals(Request.Sky(aspect = B), sky("what is the bortle scale here"))
        assertEquals(Request.Sky(aspect = B), sky("how bad is the light pollution here"))
        assertEquals(Request.Sky(aspect = M), sky("can i see the milky way tonight"))
        assertEquals(Request.Sky(aspect = S), sky("how many stars can i see here"))
        assertEquals(Request.Sky(place = "moab", aspect = B), sky("what's the bortle level in moab"))
        assertEquals(Request.Sky(place = "joshua tree", aspect = B), sky("light pollution at joshua tree"))
    }

    @Test
    fun placesAroundTheWorld() {
        fun place(t: String) = sky(t)?.place
        assertEquals("tokyo", place("night sky report for tokyo"))
        assertEquals("paris france", place("how many stars can i see in paris, france"))
        assertEquals("the atacama desert".removePrefix("the "), place("can i see the milky way in the atacama desert"))
        assertEquals("death valley national park", place("what's the bortle level at death valley national park"))
        assertEquals("reykjavik iceland", place("bortle scale in reykjavik iceland tonight"))
        assertEquals("cape town", place("what's the light pollution like in cape town"))
        assertEquals("mauna kea", place("how dark is the sky on mauna kea"))
        assertEquals("namibia", place("give me a night sky report for namibia"))
        assertEquals(SkyAspect.STARS, sky("how many stars can i see in paris, france")?.aspect)
        assertEquals(SkyAspect.REPORT, sky("night sky report for tokyo")?.aspect)
        // A search around another place.
        assertEquals(Request.Sky(darkest = true, radiusMiles = 50, place = "denver", aspect = SkyAspect.REPORT), sky("darkest sky within 50 miles of denver"))
    }

    @Test
    fun hideCoordinates() {
        val endings = listOf(
            "hide cords", "hide coords", "hide coordinates", "hide location", "hide my location", "no cords",
            "no coordinates", "no coords please", "without coordinates", "without the lat long", "hide latitude and longitude",
            "don't show my location", "don't include coordinates", "cords off", "coordinates off", "keep my location private",
            "privately", "hide chords", "no gps", "omit the coordinates", "skip coords", "minus the coordinates",
        )
        for (e in endings) {
            val r = sky("night sky report for tokyo $e")
            assertEquals(e, true, r?.hideCoords)
            assertEquals(e, "tokyo", r?.place)
        }
        // Here: "hide location" must not turn the request into a place search.
        assertEquals(Request.Sky(aspect = SkyAspect.BORTLE, hideCoords = true), sky("bortle level here, hide location"))
        assertEquals(Request.Sky(aspect = SkyAspect.STARS, hideCoords = true), sky("how many stars can i see no cords"))
        assertEquals(true, sky("darkest sky within 100 miles no coordinates")?.hideCoords)
        assertEquals(100, sky("darkest sky within 100 miles no coordinates")?.radiusMiles)
        assertEquals(true, (IntentParser.parse("bortal level hide cords") as? Request.Sky)?.hideCoords)
        assertEquals(false, sky("night sky report for tokyo")?.hideCoords)
    }

    @Test
    fun obscureLocation() {
        val endings = listOf(
            "obscure my location", "obscure location", "fake my location", "use a fake location", "spoof my gps",
            "scramble my coordinates", "randomize my location", "random location", "decoy location", "decoy mode",
            "disguise my position", "mask my location", "pretend i'm somewhere else", "give me a fake location please",
            "my location obscured", "abscure my location", "fake cords",
        )
        for (e in endings) {
            val r = sky("how many stars can i see $e")
            assertEquals(e, true, r?.obscure)
            assertEquals(e, null, r?.obscureMiles)
            assertEquals(e, null, r?.place)
            assertEquals(e, SkyAspect.STARS, r?.aspect)
        }
        assertEquals(500, sky("bortle level here obscure my location by 500 miles")?.obscureMiles)
        assertEquals(1000, sky("night sky report, fake location 1,000 miles away")?.obscureMiles)
        assertEquals(621, sky("bortle level obscure my location 1000 km")?.obscureMiles)
        // Clamped to 100..3000.
        assertEquals(100, sky("bortle level obscure my location by 20 miles")?.obscureMiles)
        assertEquals(3000, sky("bortle level obscure my location by 9000 miles")?.obscureMiles)
        // With "hide cords" too, either order.
        val both = sky("night sky report obscure my location hide cords")
        assertEquals(true, both?.obscure); assertEquals(true, both?.hideCoords); assertNull(both?.place)
        val both2 = sky("night sky report no cords, fake location")
        assertEquals(true, both2?.obscure); assertEquals(true, both2?.hideCoords)
        assertEquals(true, (IntentParser.parse("bortal level obscure my location") as? Request.Sky)?.obscure)
        assertEquals(false, sky("night sky report")?.obscure)
        assertEquals(false, sky("best place to see the milky way within 200 miles")?.obscure)
    }

    @Test
    fun weatherPrivacy() {
        fun w(t: String) = IntentParser.parse(t) as? Request.Weather
        val o = w("what's the weather tomorrow obscure my location")
        assertEquals(true, o?.obscure); assertEquals(true, o?.tomorrow); assertNull(o?.city); assertNull(o?.obscureMiles)
        assertEquals(800, w("how hot is it, fake my location by 800 miles")?.obscureMiles)
        assertEquals(WeatherAspect.TEMPERATURE, w("how hot is it, fake my location by 800 miles")?.aspect)
        val h = w("will it rain today hide my location")
        assertEquals(true, h?.hidePlace); assertNull(h?.city); assertEquals(WeatherAspect.RAIN_CHANCE, h?.aspect)
        assertEquals(true, w("weather no location please")?.hidePlace ?: w("weather hide location")?.hidePlace)
        assertEquals(true, w("forecast, pretend i'm somewhere else")?.obscure)
        assertEquals("chicago", w("weather in chicago hide my location")?.city?.lowercase())
        assertEquals(false, w("what's the weather in boston")?.obscure)
        assertEquals(false, w("what's the weather in boston")?.hidePlace)
    }

    @Test
    fun decoyDistance() {
        for (bearing in listOf(0.0, 77.0, 180.0, 300.0)) {
            val (lat, lon) = SkyMap.offset(41.1, -74.15, 500.0, bearing)
            assertEquals(500.0, SkyGrid.miles(41.1, -74.15, lat, lon), 0.5)
        }
        val (_, lon) = SkyMap.offset(10.0, 179.0, 300.0, 90.0)
        assertTrue(lon in -180.0..-170.0)
    }

    @Test
    fun darkestWithinRadius() {
        assertEquals(Request.Sky(darkest = true, radiusMiles = 50, aspect = B), sky("find the best bortle level within 50 miles"))
        assertEquals(Request.Sky(darkest = true, radiusMiles = 100), sky("where's the darkest sky in a 100 mile radius"))
        assertEquals(Request.Sky(darkest = true, radiusMiles = 62), sky("darkest skies within 100 km"))
        assertEquals(Request.Sky(darkest = true, radiusMiles = IntentParser.DEFAULT_SKY_RADIUS), sky("find me a dark sky spot near me"))
        assertEquals(Request.Sky(darkest = true, radiusMiles = 200, aspect = M), sky("best place to see the milky way within 200 miles"))
    }

    @Test
    fun worldwide() {
        assertEquals(Request.Sky(darkest = true, worldwide = true, aspect = B), sky("where is the best bortle level in the world"))
        assertEquals(Request.Sky(darkest = true, worldwide = true), sky("darkest sky worldwide"))
        assertEquals(Request.Sky(darkest = true, worldwide = true), sky("best stargazing anywhere on earth"))
    }

    @Test
    fun looseKeywords() {
        // Misheard or half-phrased: the keyword still gets a sky answer instead of a web search.
        assertTrue(IntentParser.parse("bortle") is Request.Sky)
        assertTrue(IntentParser.parse("bortal level") is Request.Sky)
        assertTrue(IntentParser.parse("what's the portal level") is Request.Sky)
        assertEquals(SkyAspect.BORTLE, sky("what's the Border level")?.aspect)
        assertEquals(SkyAspect.BORTLE, sky("bottle scale here")?.aspect)
        assertTrue(IntentParser.parse("light pollution") is Request.Sky)
        assertTrue(IntentParser.parse("yo how's the stargazing") is Request.Sky)
        assertTrue(IntentParser.parse("milky way visibility") is Request.Sky)
        assertEquals(true, sky("any dark sky places around")?.darkest)
    }

    @Test
    fun leavesOtherRequestsAlone() {
        assertFalse(IntentParser.parse("play milky way by the band") is Request.Sky)
        assertFalse(IntentParser.parse("what's the weather tomorrow") is Request.Sky)
        assertTrue(IntentParser.parse("show me photography spots in a 30 mile radius") is Request.PhotoSpots)
    }

    @Test
    fun bortleFromSqm() {
        assertEquals(1.0, SkyQuality.bortle(22.1), 0.0)
        assertEquals(2.0, SkyQuality.bortle(21.99), 0.0)
        assertEquals(4.0, SkyQuality.bortle(21.69), 0.0)
        assertEquals(5.5, SkyQuality.bortle(20.0), 0.05)
        assertEquals(9.0, SkyQuality.bortle(17.0), 0.0)
        // Darker sky, lower class, more stars.
        assertTrue(SkyQuality.visibleStars(21.9) > SkyQuality.visibleStars(20.0))
        assertTrue(SkyQuality.visibleStars(20.0) > SkyQuality.visibleStars(18.0))
        assertTrue(SkyQuality.visibleStars(21.9) in 2000..5000)
        assertTrue(SkyQuality.visibleStars(18.0) <= 400)
    }

    @Test
    fun milkyWayWording() {
        assertTrue(SkyQuality.milkyWay(1.5).contains("spectacular"))
        assertTrue(SkyQuality.milkyWay(8.0).contains("isn't visible"))
        assertTrue(SkyQuality.describe(21.5).startsWith("Bortle 4"))
    }

    @Test
    fun aspects() {
        fun aspect(t: String) = (IntentParser.parse(t) as Request.Sky).aspect
        assertEquals(SkyAspect.REPORT, aspect("night sky report"))
        assertEquals(SkyAspect.REPORT, aspect("give me a sky report"))
        assertEquals(SkyAspect.REPORT, aspect("night sky report for moab"))
        assertEquals(SkyAspect.REPORT, aspect("how's the stargazing here"))
        assertEquals(SkyAspect.REPORT, aspect("bortle level and milky way here"))
        assertEquals(SkyAspect.BORTLE, aspect("bortle level"))
        assertEquals(SkyAspect.BORTLE, aspect("bortal level here"))
        assertEquals(SkyAspect.BORTLE, aspect("how much light pollution is here"))
        assertEquals(SkyAspect.STARS, aspect("how many stars can i see"))
        assertEquals(SkyAspect.STARS, aspect("visible stars here"))
        assertEquals(SkyAspect.MILKY_WAY, aspect("can i see the milky way"))
        assertEquals(SkyAspect.MILKY_WAY, aspect("milky way visibility"))
    }

    @Test
    fun answersOnlyWhatWasAsked() {
        assertEquals("Bortle 4.0, rural to suburban.", SkyQuality.answer(SkyAspect.BORTLE, 21.65))
        assertEquals("About 1,100 stars.", SkyQuality.answer(SkyAspect.STARS, 19.53))
        assertEquals("Milky Way barely visible.", SkyQuality.answer(SkyAspect.MILKY_WAY, 19.53))
        assertEquals("Bortle 6.0, bright suburban. About 1,100 stars. Milky Way barely visible.", SkyQuality.answer(SkyAspect.REPORT, 19.53))
        assertEquals("Moab: Bortle 1.9, truly dark.", SkyQuality.answer(SkyAspect.BORTLE, 22.0, "Moab"))
    }

    @Test
    fun panel() {
        val all = SkyQuality.panel(SkyAspect.REPORT, 21.5, "your location", note = "41.12N 74.15W")
        val lines = all.lines()
        // A square box: every line the same width, ASCII only.
        assertTrue(lines.all { it.length == lines[0].length })
        assertTrue(all.all { it.code < 128 })
        assertTrue(all.contains("BORTLE") && all.contains("STARS") && all.contains("MILKY"))
        val just = SkyQuality.panel(SkyAspect.STARS, 21.5, "your location")
        assertTrue(just.contains("STARS") && !just.contains("BORTLE") && !just.contains("MILKY"))
    }

    @Test
    fun gridReadAndSearch() {
        // 4 x 3 cells of 1 degree from 10N 0E: one sea cell, the darkest land two cells east of the origin.
        val codes = intArrayOf(
            200, 100, 0, 255,
            150, 80, 40, 255,
            200, 200, 200, 200,
        )
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(java.util.zip.GZIPOutputStream(bytes)).use { o ->
            o.writeBytes("BSKY"); o.writeInt(1); o.writeInt(4); o.writeInt(3)
            o.writeDouble(10.0); o.writeDouble(0.0); o.writeDouble(1.0)
            o.write(ByteArray(codes.size) { codes[it].toByte() })
        }
        val g = SkyGrid.read(bytes.toByteArray().inputStream())
        assertEquals(22.0 - 100 * SkyGrid.STEP, g.sqmAt(9.5, 1.5)!!, 1e-9)
        assertNull(g.sqmAt(9.5, 3.5))           // sea
        assertNull(g.sqmAt(20.0, 1.0))          // outside the grid
        val near = g.darkest(8.5, 0.5, 100)!!   // only the 3 x 3 around the start is in range
        assertEquals(22.0 - 80 * SkyGrid.STEP, near.sqm, 1e-9)
        // A point at sea takes the nearest land within range; far from land, nothing.
        assertEquals(22.0, g.sqmNearLand(9.5, 3.5, 100)!!, 1e-9)
        assertNull(g.sqmNearLand(9.5, 3.5, 10))
        assertFalse(g.covers(-5.0))
        val any = g.darkest(8.5, 0.5, null)!!
        assertEquals(22.0, any.sqm, 1e-9)
        assertEquals(2.5, any.lon, 1e-9)
    }

    @Test
    fun mapLinks() {
        assertEquals("https://lightpollutionmap.app/?lat=41.1150&lng=-74.1500&zoom=10", SkyMap.link(41.115, -74.15, 10))
        assertEquals(8, SkyMap.zoomForRadius(60))
        assertTrue(SkyMap.zoomForRadius(500) < SkyMap.zoomForRadius(20))
        assertNull(null)
    }
}
