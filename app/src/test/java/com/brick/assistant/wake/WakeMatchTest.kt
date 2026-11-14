package com.brick.assistant.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMatchTest {
    private fun result(vararg words: Pair<String, Double>) =
        """{"result":[${words.joinToString(",") { (w, c) -> """{"word":"$w","conf":$c}""" }}],"text":"${words.joinToString(" ") { it.first }}"}"""

    @Test fun normallyOnlyTheWholePhraseCounts() {
        assertTrue(WakeWordService.heard(result("hey" to 0.9, "brick" to 0.8)))
        assertFalse(WakeWordService.heard(result("[unk]" to 1.0, "hey" to 0.9, "brick" to 0.8)))
        assertFalse(WakeWordService.heard(result("hey" to 0.9, "brick" to 0.4)))
    }

    @Test fun whileBrickTalksThePhraseCountsAnywhere() {
        assertTrue(WakeWordService.heard(result("[unk]" to 1.0, "hey" to 0.9, "brick" to 0.8, "[unk]" to 1.0), anywhere = true))
        assertFalse(WakeWordService.heard(result("hey" to 0.9, "[unk]" to 1.0, "brick" to 0.8), anywhere = true))
        assertFalse(WakeWordService.heard(result("[unk]" to 1.0, "hey" to 0.3, "brick" to 0.9), anywhere = true))
        // While Brick talks, a so-so match (its own voice or a stray sound) doesn't count.
        assertFalse(WakeWordService.heard(result("[unk]" to 1.0, "hey" to 0.7, "brick" to 0.7), anywhere = true))
        assertTrue(WakeWordService.heard(result("hey" to 0.7, "brick" to 0.7)))
    }

    @Test fun afterTalkingOverBrickBrickAloneCounts() {
        assertFalse(WakeWordService.heard(result("[unk]" to 1.0, "brick" to 0.9), anywhere = true))
        assertTrue(WakeWordService.heard(result("[unk]" to 1.0, "brick" to 0.9), anywhere = true, loose = true))
        assertFalse(WakeWordService.heard(result("[unk]" to 1.0, "brick" to 0.5), anywhere = true, loose = true))
    }

    @Test fun bricksOwnLinesCantSetItOff() {
        WakeWordGate.speaking = "Say hey Brick to ask again."
        assertFalse(WakeWordGate.mayTrigger())
        WakeWordGate.speaking = "I am Brick."
        assertTrue(WakeWordGate.mayTrigger())
        assertFalse(WakeWordGate.mayTrigger(loose = true))
        WakeWordGate.speaking = null
        assertTrue(WakeWordGate.mayTrigger(loose = true))
    }
}
