package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoveeScaleTest {
    private fun dev(id: String) = GoveeDevice("H612D", id, "Room Light", true, true)

    @Test fun lightThatReportsWhatWasSetIsTakenAsIs() {
        val d = dev("exact")
        d.sent = 54
        assertEquals(54, Govee.readBrightness(d, 54))
        assertEquals(80, Govee.readBrightness(d, 80)) // changed in the Govee app later
    }

    @Test fun lightOnTheOtherScaleIsLearnedAndConverted() {
        // Measured on an H612D: 3 -> 12, 7 -> 22, 11 -> 32, 26 -> 69, 36 -> 94, 58 -> 100.
        val d = dev("raw")
        d.sent = 36
        assertEquals(36, Govee.readBrightness(d, 94))
        assertEquals(11, Govee.readBrightness(d, 32))
        assertEquals(25, Govee.readBrightness(d, 69)) // within a point of the 26 that was set
        assertEquals(3, Govee.readBrightness(d, 12))
        d.brightness = 58
        assertEquals(58, Govee.readBrightness(d, 100)) // capped report keeps what Brick knows
    }

    @Test fun cappedReportAfterAHighValueTeachesTheScale() {
        val d = dev("raw-high")
        d.sent = 58
        assertEquals(58, Govee.readBrightness(d, 100))
        assertEquals(6, Govee.readBrightness(d, 20))
    }

    @Test fun deviceControlsArentSavedToHistory() {
        assertFalse(HistoryLink.worthSaving(Request.SmartHome(target = null, on = true)))
        assertFalse(HistoryLink.worthSaving(Request.NowPlaying))
        assertTrue(HistoryLink.worthSaving(Request.Search("who won best picture")))
    }
}
