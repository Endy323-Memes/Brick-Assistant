package com.brick.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSpeechTest {
    @Test
    fun languageMatching() {
        assertTrue(OfflineSpeech.matches(listOf("de-DE", "en-US"), "en-US"))
        assertTrue(OfflineSpeech.matches(listOf("en-us"), "en-US"))
        assertTrue(OfflineSpeech.matches(listOf("en"), "en-US"))
        assertFalse(OfflineSpeech.matches(listOf("en-GB"), "en-US"))
        assertFalse(OfflineSpeech.matches(emptyList(), "en-US"))
    }
}
