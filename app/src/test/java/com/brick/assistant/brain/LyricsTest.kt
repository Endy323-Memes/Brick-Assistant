package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placeholder text only: these lines are made up for the tests. */
class LyricsTest {
    @Test
    fun plainText() {
        val l = Lyrics.parse("first test line\nsecond test line\n\n\nthird test line\n")
        assertEquals(listOf("first test line", "second test line", "", "third test line"), l.map { it.text })
        assertTrue(l.all { it.timeMs == null })
        assertEquals(LyricsSync.PLAIN, LyricsDoc("t", null, l, LyricsSource.LRCLIB).sync)
    }

    @Test
    fun lineSynced() {
        val l = Lyrics.parse("[ti:Test Song]\n[ar:Test Artist]\n[00:12.50]alpha beta\n[01:02.3]gamma\n[00:05.00][00:30.00]repeated line")
        assertEquals(listOf(5_000L, 12_500L, 30_000L, 62_300L), l.map { it.timeMs })
        assertEquals("alpha beta", l[1].text)
        assertEquals("repeated line", l[2].text)
        assertEquals(LyricsSync.LINE, LyricsDoc("t", null, l, LyricsSource.LRCLIB).sync)
        assertEquals(-1, Lyrics.lineAt(l, 1_000))
        assertEquals(1, Lyrics.lineAt(l, 20_000))
        assertEquals(3, Lyrics.lineAt(l, 99_000))
    }

    @Test
    fun wordSyncedAndOffset() {
        val l = Lyrics.parse("[offset:+200]\n[00:10.00]<00:10.00>one <00:10.50>two <00:11.25>three")
        assertEquals(1, l.size)
        assertEquals(9_800L, l[0].timeMs)
        assertEquals("one two three", l[0].text)
        assertEquals(listOf("one", "two", "three"), l[0].words.map { it.text })
        assertEquals(listOf(9_800L, 10_300L, 11_050L), l[0].words.map { it.timeMs })
        assertEquals(LyricsSync.WORD, LyricsDoc("t", null, l, LyricsSource.DRIVER).sync)
    }

    @Test
    fun cleansSongNames() {
        assertEquals("Test Song", Lyrics.cleanTitle("Test Song (Remastered 2011)"))
        assertEquals("Test Song", Lyrics.cleanTitle("Test Song - Radio Edit"))
        assertEquals("Test Song", Lyrics.cleanTitle("Test Song (feat. Someone)"))
        assertEquals("Main Act", Lyrics.mainArtist("Main Act, Guest"))
        assertEquals("Main Act", Lyrics.mainArtist("Main Act feat. Guest"))
    }

    private fun ly(s: String) = IntentParser.parse(s) as? Request.Lyrics

    @Test
    fun understandsLoosePhrasing() {
        for (s in listOf(
            "lyrics", "show me the lyrics", "show lyrics", "pull up the lyrics", "lyrics please", "what are the lyrics",
            "lyrics to this song", "lyrics for the song that's playing", "what are the words to this song",
            "what is he saying", "what's she singing", "show me the words", "sing along", "karaoke mode",
            "can you show me the lyrics", "song lyrics", "the lyrics", "get the lyrics", "synced lyrics",
            "lyrics for this", "what does this song say",
        )) {
            val r = ly(s)
            assertTrue(s, r != null)
            assertNull(s, r!!.song)
            assertFalse(s, r.download)
        }
        val named = ly("lyrics for test song by test artist")!!
        assertEquals("test song", named.song)
        assertEquals("test artist", named.artist)
        assertEquals("test song", ly("test song lyrics")!!.song)
        assertEquals("test song", ly("show me the lyrics to test song")!!.song)
    }

    @Test
    fun downloadStillGoesToDriver() {
        assertTrue(ly("download the lyrics")!!.download)
        assertTrue(ly("save lyrics for this song")!!.download)
        assertFalse(ly("pull up the lyrics")!!.download)
    }

    @Test
    fun othersAreNotLyrics() {
        assertNull(ly("who wrote the lyrics"))
        assertNull(ly("what do the lyrics mean"))
        assertNull(ly("play test song"))
    }
}
