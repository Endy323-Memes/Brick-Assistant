package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberTest {
    private fun ask(s: String): Request.PlaceNumber {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.PlaceNumber)
        return r as Request.PlaceNumber
    }

    @Test fun asking() {
        assertEquals("target", ask("what's the phone number for target").place)
        assertEquals("olive garden", ask("olive garden phone number").place)
        assertEquals("walmart", ask("walmart's number").place)
        assertEquals("walmart", ask("whats the number for the walmart near me").place)
        assertEquals("pizza hut", ask("give me the number for pizza hut").place)
        assertEquals("home depot", ask("what's home depot's phone number").place)
        assertEquals("cvs on main street", ask("phone number of the cvs on main street").place)
        assertEquals("starbucks", ask("can you find the phone number for the closest starbucks").place)
        assertEquals("comcast", ask("how do i contact comcast").place)
        assertEquals("dmv", ask("how do i reach the dmv by phone").place)
        assertEquals("best buy", ask("how can i call best buy").place)
        assertEquals("irs", ask("what number do i call for the irs").place)
        ask("amazon customer service number").let { assertEquals("amazon", it.place); assertTrue(it.support) }
        ask("customer service number for verizon").let { assertEquals("verizon", it.place); assertTrue(it.support) }
        assertEquals("chipotle", ask("contact info for chipotle").place)
    }

    @Test fun notBusinessNumbers() {
        for (s in listOf("what's my number", "what's mom's number", "call mom", "call target", "what's my phone number", "what's the lucky number",
                "how do i reach the summit", "how do i call a function in python", "what's 5 times a number", "text dad i'm on my way",
                "what number is the song", "what's the jersey number of lebron james", "turn the number down")) {
            val r = IntentParser.parse(s)
            assertFalse("\"$s\" -> $r", r is Request.PlaceNumber)
        }
    }

    @Test fun saying() {
        assertEquals("(215) 555-0134", PlaceHours.prettyPhone("+1 215-555-0134"))
        assertEquals("(215) 555-0134", PlaceHours.prettyPhone("215.555.0134"))
        assertEquals("(800) 555-0199", PlaceHours.prettyPhone("+1-800-555-0199;+1 215 555 0100"))
        assertEquals("+44 20 7946 0958", PlaceHours.prettyPhone("+44 20 7946 0958"))
        assertEquals("mcdonald's", PlaceHours.possessive("mcdonalds"))
        assertEquals("lowe's on route 59", PlaceHours.possessive("lowes on route 59"))
        assertEquals(null, PlaceHours.possessive("target"))
        assertEquals(null, PlaceHours.possessive("kohl's"))
    }
}
