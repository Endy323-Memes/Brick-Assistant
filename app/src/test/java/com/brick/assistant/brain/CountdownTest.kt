package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CountdownTest {
    // Thursday, November 12, 2026, 11:00.
    private val now = LocalDateTime.of(2026, 11, 12, 11, 0)

    private fun cd(s: String) = Countdown.parse(s, now)

    @Test
    fun holidays() {
        val c = cd("how many days until christmas")!!
        assertEquals(LocalDate.of(2026, 12, 25), c.target!!.date)
        assertEquals("There are 43 days until Christmas, on Friday, December 25.", Countdown.speak(c.target!!, c.unit, c.since, now = now))
        assertEquals(LocalDate.of(2026, 11, 26), cd("how long till thanksgiving")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 3, 28), cd("days until easter")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 5, 9), cd("how many sleeps until mother's day")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 10, 31), cd("halloween countdown")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 1, 1), cd("how many days until the new year")!!.target!!.date)
        assertEquals(LocalDate.of(2026, 11, 27), cd("how many days until black friday")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 2, 14), cd("countdown to valentines day")!!.target!!.date)
        assertEquals(CountUnit.WEEKS, cd("how many weeks until christmas")!!.unit)
        assertEquals("6 weeks and 1 day", Countdown.amount(now.toLocalDate().atStartOfDay(), LocalDate.of(2026, 12, 25).atStartOfDay(), CountUnit.WEEKS))
        assertEquals(LocalDate.of(2026, 12, 25), cd("is it christmas yet")!!.target!!.date)
    }

    @Test
    fun whenIsHoliday() {
        val c = cd("when is thanksgiving")!!
        assertTrue(c.whenAsk)
        assertEquals("Thanksgiving is on Thursday, November 26, 14 days from now.", Countdown.speak(c.target!!, c.unit, c.since, c.whenAsk, now))
        assertNull(cd("when is the next iron man movie"))
        assertNull(cd("when is the game"))
    }

    @Test
    fun since() {
        val c = cd("how many days since halloween")!!
        assertTrue(c.since)
        assertEquals(LocalDate.of(2026, 10, 31), c.target!!.date)
        assertEquals(LocalDate.of(2026, 1, 1), cd("how long has it been since new years")!!.target!!.date)
    }

    @Test
    fun datesWeekdaysAndTimes() {
        assertEquals(LocalDate.of(2026, 11, 13), cd("how many days until friday")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 3, 3), cd("how many days until march 3rd")!!.target!!.date)
        assertEquals(LocalDate.of(2026, 11, 14), cd("how long until the weekend")!!.target!!.date)
        assertEquals(LocalDate.of(2027, 6, 21), cd("days till summer")!!.target!!.date)
        val t = cd("how long until 5 pm")!!.target!!
        assertEquals(LocalTime.of(17, 0), t.time)
        assertEquals("6 hours until 5 PM.", Countdown.speak(t, CountUnit.DAYS, false, now = now).replace("5:00 PM", "5 PM"))
    }

    @Test
    fun calendarEventsNeedClearWording() {
        // No date, but clear countdown wording: looked up on the calendar, else searched as said.
        val v = cd("how many days until my vacation")!!
        assertNull(v.target)
        assertEquals("my vacation", v.name)
        assertEquals("how many days until my vacation", v.query)
        // Ordinary questions stay searches.
        assertNull(cd("the twelve days of christmas"))
        assertNull(cd("how many days are in a week"))
        assertNull(cd("how long to cook a turkey"))
        assertNull(cd("how many days of christmas are there"))
        assertNull(cd("play the final countdown"))
        assertNull(cd("how long is left on my timer"))
        assertNull(cd("is it raining yet"))
    }

    @Test
    fun parserRoutesCountdowns() {
        val p = IntentParser.parse("how many days until christmas")
        assertTrue(p.toString(), p is Request.Countdown)
        assertNotNull((p as Request.Countdown).target)
        val s = IntentParser.parse("the twelve days of christmas")
        assertEquals("the twelve days of christmas", (s as Request.Search).query)
        assertTrue(IntentParser.parse("set a countdown for 10 minutes") is Request.Timer)
    }

    @Test
    fun easterDates() {
        assertEquals(LocalDate.of(2025, 4, 20), Countdown.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), Countdown.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), Countdown.easter(2027))
    }
}
