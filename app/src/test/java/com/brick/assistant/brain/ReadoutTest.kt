package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The infographic boxes, math's worked steps, and the stopwatch requests. */
class ReadoutTest {
    @Test
    fun boxIsSquareAndAscii() {
        val rows = listOf("Chicago, Illinois", "") + Box.wrap("Albert Einstein was a German-born theoretical physicist who is best known for developing the theory of relativity.", max = 9) +
            Box.kv("NOW", "47F  partly cloudy") + Box.kv("RAIN", Box.percent(40)) + "Café Montréal — “quoted” ×÷°"
        val box = Box.build("WEATHER", rows)
        val lines = box.lines()
        assertTrue(lines.all { it.length == lines[0].length })
        assertTrue(box.all { it.code < 128 })
        assertTrue(box.contains("Cafe Montreal - \"quoted\" x/o"))
        assertTrue(lines[0].startsWith("+-[ WEATHER ]"))
    }

    @Test
    fun wrapping() {
        val w = Box.wrap("one two three four five six seven eight nine ten eleven twelve thirteen", "> ")
        assertTrue(w.all { it.length <= Box.W })
        assertTrue(w[0].startsWith("> one"))
        assertTrue(w.drop(1).all { it.startsWith("  ") })
        // Cut to [max] rows with an ellipsis.
        val long = Box.wrap("word ".repeat(200), max = 3)
        assertEquals(3, long.size)
        assertTrue(long.last().endsWith("..."))
        assertEquals("[####......] 40%", Box.percent(40))
    }

    @Test
    fun mathShowsItsWork() {
        val r = MathEvaluator.tryParse("what is 12 times 8 plus 3")!!
        assertEquals(listOf("12 x 8 = 96", "96 + 3 = 99"), r.steps)
        assertEquals("12 x 8 + 3", r.expression)
        val p = MathEvaluator.tryParse("2 + 3 * 4")!!
        assertEquals(listOf("3 x 4 = 12", "2 + 12 = 14"), p.steps)
        val s = MathEvaluator.tryParse("square root of 144 plus 3")!!
        assertEquals("sqrt(144) = 12", s.steps.first())
        assertEquals(15.0, s.value, 0.0)
        assertEquals(listOf("2 ^ 10 = 1024"), MathEvaluator.tryParse("2 to the power of 10")!!.steps)
    }

    @Test
    fun stopwatchRequests() {
        fun op(t: String) = (IntentParser.parse(t) as? Request.Stopwatch)?.op
        assertEquals(StopwatchOp.START, op("start a stopwatch"))
        assertEquals(StopwatchOp.START, op("start the stop watch"))
        assertEquals(StopwatchOp.STOP, op("stop the stopwatch"))
        assertEquals(StopwatchOp.STOP, op("pause the stopwatch"))
        assertEquals(StopwatchOp.RESET, op("reset the stopwatch"))
        assertEquals(StopwatchOp.LAP, op("stopwatch lap"))
        assertEquals(StopwatchOp.STATUS, op("how long has the stopwatch been running"))
        assertEquals(StopwatchOp.STATUS, op("check the stopwatch"))
        assertTrue(IntentParser.parse("set a timer for 5 minutes") is Request.Timer)
    }
}
