package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    @Test fun duckDuckGoIsASearchWord() {
        for (said in listOf("duckduckgo how tall is the eiffel tower", "duck duck go how tall is the eiffel tower", "search duckduckgo for how tall is the eiffel tower")) {
            val r = IntentParser.parse(said)
            assertTrue("$said -> $r", r is Request.Search && r.explicit && r.query == "how tall is the eiffel tower")
        }
    }


    private inline fun <reified T> parse(s: String): T {
        val r = IntentParser.parse(s)
        assertTrue("'$s' parsed as $r", r is T)
        return r as T
    }

    @Test
    fun alarms() {
        val a = parse<Request.Alarm>("set an alarm for 6:30 am")
        assertEquals(6, a.time.dateTime!!.hour)
        assertEquals(30, a.time.dateTime!!.minute)

        val w = parse<Request.Alarm>("wake me up at 7")
        assertEquals(7, w.time.dateTime!!.hour) // morning, not 7 PM

        val d = parse<Request.Alarm>("set an alarm for 5:45 am every weekday")
        assertEquals(listOf(2, 3, 4, 5, 6), d.days)

        val l = parse<Request.Alarm>("set an alarm at 8 pm called take medicine")
        assertEquals(20, l.time.dateTime!!.hour)
        assertEquals("take medicine", l.label)

        val r = parse<Request.Alarm>("wake me up in 20 minutes")
        assertEquals(1200L, r.time.relativeSeconds)

        assertTrue(IntentParser.parse("set a timer for 10 minutes") is Request.Timer)
    }

    @Test
    fun navigation() {
        val n = parse<Request.Navigate>("navigate to the airport")
        assertEquals("airport", n.destination)
        assertTrue(n.directions)
        assertEquals("Central Park", parse<Request.Navigate>("give me directions to Central Park").destination)
        assertEquals("Costco", parse<Request.Navigate>("how do I get to Costco").destination)
        val g = parse<Request.Navigate>("where is the nearest gas station")
        assertEquals("gas station near me", g.destination)
        assertTrue(!g.directions)
        assertEquals("coffee shops near me", FindPlaces.query(parse<Request.FindPlace>("find coffee shops near me")))
        assertEquals("home", parse<Request.Navigate>("take me home").destination)
        // still weather / search, not maps
        assertTrue(IntentParser.parse("what's the weather in Chicago") is Request.Weather)
        assertTrue(IntentParser.parse("who is the president of France") is Request.Search)
    }

    @Test
    fun driverEffects() {
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.ON), parse<Request.Effect>("turn on reverb"))
        assertEquals(Request.Effect(AudioEffect.REVERB, EffectState.OFF), parse<Request.Effect>("turn the reverb off"))
        assertEquals(Request.Effect(AudioEffect.LOW_PASS, EffectState.ON), parse<Request.Effect>("enable the low-pass filter"))
        assertEquals(Request.Effect(AudioEffect.HIGH_PASS, EffectState.OFF), parse<Request.Effect>("disable HPF"))
        assertEquals(Request.Effect(AudioEffect.EQUALIZER, EffectState.TOGGLE), parse<Request.Effect>("toggle the equalizer"))
        assertEquals(Request.Effect(AudioEffect.SPATIAL, EffectState.ON), parse<Request.Effect>("hey brick, please turn on spatial audio"))
        // plain media controls still work
        assertEquals(MusicCommand.PAUSE, parse<Request.Music>("stop the music").command)
    }

    @Test
    fun driverPresets() {
        assertEquals(Request.EffectPreset(AudioEffect.REVERB, "Lush Cathedral"), parse<Request.EffectPreset>("use the Lush Cathedral reverb preset"))
        assertEquals(Request.EffectPreset(AudioEffect.REVERB, "cathedral"), parse<Request.EffectPreset>("set the reverb to cathedral"))
        assertEquals(Request.EffectPreset(AudioEffect.EQUALIZER, "Bass Heavy"), parse<Request.EffectPreset>("set the EQ preset to Bass Heavy"))
        assertEquals(Request.EffectPreset(null, "Late Night"), parse<Request.EffectPreset>("apply my Late Night preset"))
        assertEquals(Request.EffectPreset(AudioEffect.LOW_PASS, "Underwater"), parse<Request.EffectPreset>("use the preset Underwater for the low pass filter"))
    }

    @Test
    fun driverLyricsAndNowPlaying() {
        assertEquals(Request.Lyrics(null, download = true), parse<Request.Lyrics>("download lyrics"))
        assertEquals(Request.Lyrics(null), parse<Request.Lyrics>("get the lyrics for this song"))
        assertEquals(Request.Lyrics("Starblessed", download = true), parse<Request.Lyrics>("download the lyrics for Starblessed"))
        assertTrue(IntentParser.parse("what's playing") is Request.NowPlaying)
        assertTrue(IntentParser.parse("what song is this?") is Request.NowPlaying)
    }

    @Test
    fun driverPlayKinds() {
        parse<Request.Music>("play the album Madeira by Delayde").let {
            assertEquals(MediaKind.ALBUM, it.kind); assertEquals("Madeira", it.query); assertEquals("Delayde", it.artist)
        }
        parse<Request.Music>("play my road trip playlist").let { assertEquals(MediaKind.PLAYLIST, it.kind); assertEquals("road trip", it.query) }
        parse<Request.Music>("play the playlist called Gym").let { assertEquals(MediaKind.PLAYLIST, it.kind); assertEquals("Gym", it.query) }
        parse<Request.Music>("play songs by No Spirit").let { assertEquals(MediaKind.ARTIST, it.kind); assertEquals("No Spirit", it.query) }
        parse<Request.Music>("play Starblessed by No Spirit on Driver").let {
            assertEquals(MediaKind.SONG, it.kind); assertEquals("Starblessed", it.query); assertEquals("No Spirit", it.artist)
        }
        assertEquals(MediaKind.LIKED, parse<Request.Music>("play my liked songs").kind)
        parse<Request.Music>("play ladybugs").let { assertEquals(MediaKind.AUTO, it.kind); assertEquals("ladybugs", it.query) }
        assertEquals(MusicCommand.PLAY_ANY, parse<Request.Music>("play some music").command)
    }

    @Test
    fun musicAppTarget() {
        parse<Request.Music>("play the prince by madeon in driver").let {
            assertEquals(MusicApp.DRIVER, it.app)
            assertEquals(MediaKind.SONG, it.kind)
            assertEquals("the prince", it.query) // leading "the" is part of the title
            assertEquals("madeon", it.artist)
        }
        parse<Request.Music>("play the prince by madeon on spotify").let {
            assertEquals(MusicApp.SPOTIFY, it.app)
            assertEquals("the prince", it.query)
            assertEquals("madeon", it.artist)
        }
        parse<Request.Music>("play the album Adventure on Spotify").let {
            assertEquals(MusicApp.SPOTIFY, it.app); assertEquals(MediaKind.ALBUM, it.kind); assertEquals("Adventure", it.query)
        }
        assertEquals(MusicApp.DEFAULT, parse<Request.Music>("play the beatles").app)
        assertEquals("the beatles", parse<Request.Music>("play the beatles").query)
        parse<Request.Music>("play music on spotify").let { assertEquals(MusicCommand.PLAY_ANY, it.command); assertEquals(MusicApp.SPOTIFY, it.app) }
    }
}
