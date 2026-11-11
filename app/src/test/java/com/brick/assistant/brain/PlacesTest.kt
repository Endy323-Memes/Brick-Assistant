package com.brick.assistant.brain

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class PlacesTest {
    @Test
    fun parsesHoursQuestions() {
        assertEquals(Request.PlaceHours("target", HoursAsk.CLOSES, false), IntentParser.parse("When does the nearest Target close?").let { (it as Request.PlaceHours).copy(place = it.place.lowercase()) })
        assertEquals(HoursAsk.OPENS, (IntentParser.parse("what time does Home Depot open tomorrow") as Request.PlaceHours).ask)
        assertTrue((IntentParser.parse("what time does Home Depot open tomorrow") as Request.PlaceHours).tomorrow)
        assertEquals(Request.PlaceHours("Walmart", HoursAsk.IS_OPEN, false), IntentParser.parse("Hey Brick, is Walmart open right now?"))
        assertEquals(Request.PlaceHours("Costco", HoursAsk.CLOSES, false), IntentParser.parse("how late is Costco open"))
        assertEquals(Request.PlaceHours("Public Library", HoursAsk.HOURS, false), IntentParser.parse("what are the hours for the Public Library"))
        // Not hours requests
        assertTrue(IntentParser.parse("set a timer for 2 hours") is Request.Timer)
        assertTrue(IntentParser.parse("play 24 hours") is Request.Music)
    }

    @Test
    fun parsesPhotoSpots() {
        assertEquals(Request.PhotoSpots(50, SpotKind.ANY), IntentParser.parse("hey brick, show me photography spots in a 50 mile radius"))
        assertEquals(Request.PhotoSpots(25, SpotKind.ANY), IntentParser.parse("find good photo spots near me"))
        assertEquals(Request.PhotoSpots(30, SpotKind.WATERFALL), IntentParser.parse("show me waterfalls within 30 miles"))
        assertEquals(Request.PhotoSpots(40, SpotKind.FLORA), IntentParser.parse("photography locations with lots of flowers in a 40-mile radius"))
        assertEquals(SpotKind.LANDSCAPE, (IntentParser.parse("where can I take good pictures of landscapes within 20 miles") as Request.PhotoSpots).kind)
        assertEquals(Request.PhotoSpots(31, SpotKind.ANY), IntentParser.parse("photo spots within 50 km"))
    }

    @Test
    fun openingHours() {
        val h = OpeningHours.parse("Mo-Fr 08:00-18:00; Sa 09:00-14:00; Su off")
        assertNotNull(h)
        assertEquals(listOf(480..1080), h!!.on(DayOfWeek.WEDNESDAY))
        assertTrue(h.on(DayOfWeek.SUNDAY).isEmpty())
        assertTrue(OpeningHours.parse("24/7")!!.alwaysOpen)
        assertNull(OpeningHours.parse("sunrise-sunset"))
        // Late night across midnight
        val bar = OpeningHours.parse("Mo-Su 17:00-02:00")!!
        assertNotNull(bar.openSpan(LocalDateTime.of(2026, 11, 10, 1, 0)))

        val wed10am = LocalDateTime.of(2026, 11, 11, 10, 0) // Wednesday
        val p = PlaceHours.Place("Target", 0.0, 0.0, h, null)
        assertEquals("Target is open right now and closes at 6 PM.", PlaceHours.speak(p, HoursAsk.IS_OPEN, false, wed10am))
        assertEquals("Target is already open, until 6 PM.", PlaceHours.speak(p, HoursAsk.OPENS, false, wed10am))
        val sat8pm = LocalDateTime.of(2026, 11, 14, 20, 0)
        assertEquals("Target is closed right now. It opens at 8 AM on Monday.", PlaceHours.speak(p, HoursAsk.IS_OPEN, false, sat8pm))
        assertEquals("Target is closed tomorrow.", PlaceHours.speak(p, HoursAsk.HOURS, true, sat8pm))
    }

    @Test
    fun ranksPhotoSpots() {
        val els = JSONArray(
            """[
              {"type":"node","lat":35.01,"lon":-85.0,"tags":{"waterway":"waterfall","name":"Big Falls","wikipedia":"en:Big Falls"}},
              {"type":"node","lat":35.01,"lon":-85.0,"tags":{"waterway":"waterfall","name":"Big Falls"}},
              {"type":"way","center":{"lat":35.2,"lon":-85.0},"tags":{"tourism":"viewpoint","name":"Ridge Overlook"}},
              {"type":"node","lat":37.0,"lon":-85.0,"tags":{"tourism":"viewpoint","name":"Too Far"}}
            ]""",
        )
        val spots = PhotoSpots.rank(els, 35.0, -85.0, 50.0, SpotKind.ANY)
        assertEquals(listOf("Big Falls", "Ridge Overlook"), spots.map { it.name })
        val line = PhotoSpots.speak(spots, 50, SpotKind.ANY)
        assertTrue(line, line.startsWith("I found 2 photography spots within 50 miles. Top picks: Big Falls, a waterfall under a mile away; and Ridge Overlook, a scenic viewpoint 13 miles away."))
    }

    @Test
    fun ranksWikidataSpots() {
        val rows = JSONArray(
            """[
              {"item":{"value":"http://www.wikidata.org/entity/Q1"},"itemLabel":{"value":"Ruby Falls"},"type":{"value":"http://www.wikidata.org/entity/Q34038"},"coord":{"value":"Point(-85.34 35.02)"},"image":{"value":"x.jpg"},"links":{"value":"6"},"desc":{"value":"underground waterfall"}},
              {"item":{"value":"http://www.wikidata.org/entity/Q2"},"itemLabel":{"value":"Some Hill"},"type":{"value":"http://www.wikidata.org/entity/Q8502"},"coord":{"value":"Point(-85.30 35.06)"}},
              {"item":{"value":"http://www.wikidata.org/entity/Q3"},"itemLabel":{"value":"Q3"},"type":{"value":"http://www.wikidata.org/entity/Q34038"},"coord":{"value":"Point(-85.30 35.06)"}}
            ]""",
        )
        val spots = PhotoSpots.rankWikidata(rows, 35.05, -85.31, 50.0)
        assertEquals(listOf("Ruby Falls", "Some Hill"), spots.map { it.name })
        assertEquals("Underground waterfall", spots[0].description)
    }

    /** Real network lookups: gradlew testDebugUnitTest -PliveNet=true */
    @Test
    fun liveLookups() {
        if (System.getProperty("liveNet") != "true") return
        kotlinx.coroutines.runBlocking {
            val spots = PhotoSpots.search(35.05, -85.31, 50, SpotKind.ANY)
            println("photo spots: " + spots?.joinToString { "${it.name} (${it.type}, ${PhotoSpots.miles(it.miles)})" })
            assertTrue(!spots.isNullOrEmpty())
            println(PhotoSpots.speak(spots!!, 50, SpotKind.ANY))
            val falls = PhotoSpots.search(35.05, -85.31, 30, SpotKind.WATERFALL)
            println("waterfalls: " + falls?.joinToString { it.name })
            val p = PlaceHours.find("Walmart", WeatherService.Place(35.05, -85.31, null))
            println("hours: ${p?.name} ${p?.rawHours} -> ${p?.let { PlaceHours.speak(it, HoursAsk.IS_OPEN, false) }}")
            assertNotNull(p)
        }
    }

    @Test
    fun parsesTimerControls() {
        assertEquals(Request.TimerControl(TimerOp.PAUSE, false), IntentParser.parse("Hey Brick, pause the timer"))
        assertEquals(Request.TimerControl(TimerOp.RESUME, false), IntentParser.parse("resume my timer"))
        assertEquals(Request.TimerControl(TimerOp.RESUME, false), IntentParser.parse("unpause the timer"))
        assertEquals(Request.TimerControl(TimerOp.CANCEL, true), IntentParser.parse("cancel all timers"))
        assertEquals(Request.TimerControl(TimerOp.CANCEL, false), IntentParser.parse("stop the timer"))
        assertEquals(Request.TimerControl(TimerOp.STATUS, false), IntentParser.parse("how much time is left on the timer?"))
        assertTrue(IntentParser.parse("set a timer for 5 minutes") is Request.Timer)
        assertEquals(Request.Music(MusicCommand.PAUSE, null), IntentParser.parse("pause"))
    }

    @Test
    fun describesOneSpot() {
        val s = PhotoSpot("Ruby Falls", "waterfall", 0.0, 0.0, 2.2, "Underground waterfall", 1.0)
        assertEquals("The top spot is Ruby Falls, an underground waterfall, 2 miles away.", PhotoSpots.describe(s, true))
        assertEquals("Next up, Lookout Mountain, a mountain, 13 miles away.", PhotoSpots.describe(PhotoSpot("Lookout Mountain", "mountain", 0.0, 0.0, 13.4, null, 1.0), false))
    }
}