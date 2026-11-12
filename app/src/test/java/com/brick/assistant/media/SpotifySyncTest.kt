package com.brick.assistant.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifySyncTest {
    private var now = 100_000L
    private val answers = ArrayDeque<SpotifySync.Reading?>()
    private var asks = 0
    private var sessionPos = 60_000L

    private fun sync() = SpotifySync(
        fetch = { asks++; answers.removeFirstOrNull() },
        clock = { now },
        runInBackground = { it() },
    )

    private fun SpotifySync.at() = position(sessionPos, true, "Placeholder Song") { sessionPos }

    @Test fun firstAnswerSetsTheOffsetAndLaterOnesSmoothIt() {
        val s = sync()
        answers += SpotifySync.Reading.Now(60_800, true, "Placeholder Song")
        assertEquals(60_800L, s.at()) // asked and answered at once here (the test runs the ask in line)
        assertEquals(800L, s.offsetMs)
        // The next ask waits for the interval.
        now += 1_000; s.at(); assertEquals(1, asks)
        now += SpotifySync.NORMAL_MS
        answers += SpotifySync.Reading.Now(61_000, true, "Placeholder Song")
        s.at()
        assertEquals(2, asks)
        assertEquals(900L, s.offsetMs) // halfway from 800 to 1000
    }

    @Test fun slowDownWaitsForRetryAfterAndAsksLessOften() {
        val s = sync()
        answers += SpotifySync.Reading.SlowDown(90)
        s.at()
        now += 60_000; s.at()
        assertEquals(1, asks)
        now += 31_000
        answers += SpotifySync.Reading.Now(60_500, true, null)
        s.at()
        assertEquals(2, asks)
        // The pace is halved after a 429.
        now += SpotifySync.NORMAL_MS; s.at()
        assertEquals(2, asks)
    }

    @Test fun noPermissionStopsAsking() {
        val s = sync()
        answers += SpotifySync.Reading.NoPermission
        s.at()
        repeat(5) { now += SpotifySync.MAX_MS; s.at() }
        assertEquals(1, asks)
        assertTrue(s.disabled)
        assertEquals(sessionPos, s.at())
    }

    @Test fun anotherSongOrAHugeJumpIsIgnored() {
        val s = sync()
        answers += SpotifySync.Reading.Now(10_000, true, "Some Other Track Entirely")
        s.at()
        assertEquals(sessionPos, s.at())
        now += SpotifySync.NORMAL_MS
        answers += SpotifySync.Reading.Now(sessionPos + 40_000, true, "Placeholder Song")
        s.at()
        assertEquals(sessionPos, s.at())
    }

    @Test fun pausedDoesNotAskAndSeekChecksSoonButNotTooSoon() {
        val s = sync()
        s.position(sessionPos, false, "Placeholder Song") { sessionPos }
        assertEquals(0, asks)
        answers += SpotifySync.Reading.Now(60_300, true, null)
        s.at()
        assertEquals(1, asks)
        s.nudge()
        now += SpotifySync.AFTER_SEEK_MS; s.at()
        assertEquals(1, asks) // still within the minimum gap
        now += SpotifySync.MIN_GAP_MS; s.at()
        assertEquals(2, asks)
    }
}
