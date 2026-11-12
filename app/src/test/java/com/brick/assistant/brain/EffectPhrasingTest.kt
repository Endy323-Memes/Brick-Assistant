package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Effect, preset and lyrics requests must reach Driver even when the user names the app. */
class EffectPhrasingTest {
    private fun effect(s: String) = IntentParser.parse(s).also { assertTrue("'$s' -> $it", it is Request.Effect) } as Request.Effect

    @Test
    fun effectsWithDriverMentioned() {
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), effect("turn on reverb in driver"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), effect("enable reverb on driver"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), effect("turn the reverb on in Driver"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), effect("enable driver's reverb"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), effect("reverb on"))
        assertEquals(Request.Effect(AudioEffect.LOW_PASS, EffectState.OFF), effect("switch off the low pass filter in driver"))
        assertEquals(Request.Effect(AudioEffect.SPATIAL, EffectState.OFF), effect("disable the spatial audio in driver"))
        assertEquals(Request.Effect(AudioEffect.EQUALIZER, EffectState.ON), effect("turn on the equalizer in the driver app"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.OFF), effect("turn off the driver reverb"))
    }

    @Test
    fun presetsAndLyricsWithDriverMentioned() {
        assertEquals(Request.EffectPreset(null, "cathedral"), IntentParser.parse("use the cathedral preset in driver"))
        assertEquals(Request.EffectPreset(AudioEffect.REVERB, "cathedral"), IntentParser.parse("set the reverb to cathedral in driver"))
        assertEquals(Request.Lyrics(null, download = true), IntentParser.parse("download the lyrics in driver"))
        assertTrue(IntentParser.parse("turn on bass boost") is Request.Chat)
    }

    @Test
    fun driverMentionDoesNotBreakOtherRequests() {
        val m = IntentParser.parse("play The Prince by Madeon in driver") as Request.Music
        assertEquals(MusicApp.DRIVER, m.app)
        assertEquals("The Prince", m.query)
        assertTrue(IntentParser.parse("navigate to the driver's license office") is Request.Navigate)
    }
    @Test
    fun loosePhrasing() {
        val both = effect("turn on the lpf and spatialize audio effects")
        assertEquals(EffectState.ON, both.state)
        assertEquals(listOf(AudioEffect.LOW_PASS, AudioEffect.SPATIAL), both.all)
        assertEquals(listOf(AudioEffect.LOW_PASS, AudioEffect.SPATIAL), effect("enable the lpf and spatialize audio effects").all)
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.OFF), effect("can you kill the reverb"))
        assertEquals(Request.Effect(AudioEffect.SPATIAL, EffectState.ON), effect("I want spatial audio on"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), effect("add some echo"))
        assertEquals(EffectState.OFF, effect("get rid of the low pass and the reverb").state)
        assertEquals(Request.Effect(AudioEffect.SPATIAL, EffectState.ON), effect("spatialize on"))
        assertEquals(Request.Effect(AudioEffect.EQUALIZER, EffectState.OFF), effect("turn the eq off please"))
    }
}