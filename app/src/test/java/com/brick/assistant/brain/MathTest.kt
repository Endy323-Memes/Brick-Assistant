package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calculator: exact big numbers, functions, everyday wording, and what it must leave alone. */
class MathTest {
    private fun math(q: String) = (IntentParser.parse(q) as? Request.Math)?.result
    private fun shown(q: String) = math(q)?.shown

    @Test fun bigNumbersKeepEveryDigit() {
        val r = math("9000 * 200 - 5 + 300^15")!!
        assertEquals("14,348,907,000,000,000,000,000,000,000,001,799,995", r.shown)
        assertEquals("1.4348907 times 10 to the power of 37", r.formatted)
        assertEquals(38, r.digits)
        assertEquals("1,267,650,600,228,229,401,496,703,205,376", shown("2^100"))
        assertEquals("100,000,000,000,000,000", shown("99999999999999999 + 1"))
        assertEquals("10 to the power of 17", math("99999999999999999 + 1")!!.formatted)
        assertEquals("4.023872601 x 10^2567 (2,568 digits)", shown("1000!"))
        assertTrue(math("2^100000")!!.tooBig)
    }

    @Test fun decimalsAreExact() {
        assertEquals("0.3", shown("0.1+0.2"))
        assertEquals("0.333333333333", shown("1/3"))
        assertEquals("0.33333333", math("1/3")!!.formatted)
        assertEquals("0.25", shown("2^-2"))
        assertEquals("1.41421356237", shown("sqrt(2)"))
    }

    @Test fun functionsAndWords() {
        val cases = mapOf(
            "10!" to "3,628,800", "5 factorial" to "120", "factorial of 6" to "720", "cube root of 27" to "3", "4th root of 16" to "2",
            "sin 30" to "0.5", "cos 90" to "0", "sin pi radians" to "0", "log 1000" to "3", "ln e" to "1", "log base 2 of 1024" to "10",
            "abs(-5)" to "5", "2pi" to "6.28318530718", "what's 300 to the 15th power" to "14,348,907,000,000,000,000,000,000,000,000,000,000",
            "9,000 x 200" to "1,800,000", "nine thousand times two hundred" to "1,800,000", "1e5 * 3" to "300,000", "what's half of 90" to "45",
            "double 45" to "90", "20% off 59.99" to "47.992", "15% of 80" to "12", "3 divided into 12" to "4", "2^3^2" to "512", "-2^2" to "-4",
            "(3+4)*2^3" to "56", "100 - 3 * (4 + 2) / 3" to "94", "what is 7 mod 3" to "1", "what is 4 squared plus 3 cubed" to "43",
            "average of 3, 5 and 10" to "6", "sum of 1 2 3 and 4" to "10", "product of 2, 3 and 4" to "24",
            "round 3.14159 to 2 decimal places" to "3.14", "round 1234 to the nearest hundred" to "1,200", "round 2.5" to "3",
        )
        val wrong = cases.mapNotNull { (q, want) -> shown(q).takeIf { it != want }?.let { "\"$q\" gave $it, not $want" } }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun undefinedAnswers() {
        for (q in listOf("12 / 0", "tan 90", "sqrt -4", "log 0", "5 mod 0")) {
            val r = math(q)
            assertTrue("\"$q\" wasn't math", r != null)
            assertNull("\"$q\" should be undefined", r!!.exact)
            assertFalse(r.tooBig)
        }
    }

    @Test fun showsItsWork() {
        assertEquals(listOf("9000 x 200 = 1800000", "1800000 - 5 = 1799995", "300 ^ 15 = 1.4348907 x 10^37"), math("9000 * 200 - 5 + 300^15")!!.steps.take(3))
        assertEquals(listOf("3 + 5 + 10 = 18", "18 / 3 = 6"), math("average of 3, 5 and 10")!!.steps)
        assertEquals(listOf("sin(30 deg) = 0.5"), math("sin 30")!!.steps)
    }

    @Test fun leavesOtherRequestsAlone() {
        for ((q, want) in listOf("what is 5" to "Search", "what is pi" to "Search", "set a timer for 5 minutes" to "Timer", "what time is it" to "TellTime",
                "call 555-1234" to "Call", "log in to my email" to "Search"))
            assertFalse("\"$q\" went to Math", IntentParser.parse(q) is Request.Math)
    }
}
