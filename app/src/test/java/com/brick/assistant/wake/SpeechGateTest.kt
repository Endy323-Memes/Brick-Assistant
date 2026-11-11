package com.brick.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SpeechGateTest {
    private fun quiet() = ShortArray(1600) { (if (it % 2 == 0) 20 else -20).toShort() }
    private fun speech() = ShortArray(1600) { (3000 * sin(it * 0.3)).toInt().toShort() }

    @Test
    fun skipsSilenceAndKeepsLeadIn() {
        val g = SpeechGate(preRoll = 4, hangover = 8)
        var decoded = 0
        repeat(50) { decoded += g.offer(quiet(), 1600).chunks.size }
        assertEquals("silence is never decoded", 0, decoded)
        val first = g.offer(speech(), 1600)
        assertEquals("lead-in (4) plus the loud chunk", 5, first.chunks.size)
        repeat(5) { assertEquals(1, g.offer(speech(), 1600).chunks.size) }
        // After the sound: 8 more chunks so the model can finish, then the end is reported.
        var ended = false
        repeat(8) { val o = g.offer(quiet(), 1600); assertEquals(1, o.chunks.size); ended = o.ended }
        assertTrue(ended)
        assertEquals(0, g.offer(quiet(), 1600).chunks.size)
    }

    @Test
    fun steadyNoiseIsIgnored() {
        val g = SpeechGate()
        val hum = ShortArray(1600) { (120 * sin(it * 0.05)).toInt().toShort() }
        var decoded = 0
        repeat(200) { decoded += g.offer(hum, 1600).chunks.size }
        assertEquals(0, decoded)
    }
}
