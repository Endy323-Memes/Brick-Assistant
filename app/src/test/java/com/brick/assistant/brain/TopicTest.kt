package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery's one-purpose gestures: what you say is read for that topic only. */
class TopicTest {
    @Test
    fun spotifyAndDriverSearch() {
        val s = Topic.SPOTIFY.request("bohemian rhapsody") as Request.Music
        assertEquals(MusicApp.SPOTIFY, s.app)
        assertEquals(MusicCommand.PLAY_QUERY, s.command)
        assertTrue(s.query!!.contains("bohemian rhapsody"))
        // Words that would otherwise read as another command still search.
        val w = Topic.SPOTIFY.request("the weather") as Request.Music
        assertEquals(MusicApp.SPOTIFY, w.app)
        val d = Topic.DRIVER.request("play thriller by michael jackson") as Request.Music
        assertEquals(MusicApp.DRIVER, d.app)
        assertEquals("michael jackson", d.artist)
    }

    @Test
    fun youtubeSearch() {
        assertEquals(Request.YouTube("cat videos", play = false, music = false), Topic.YOUTUBE.request("cat videos"))
        assertEquals(Request.YouTube("lofi beats", play = true, music = false), Topic.YOUTUBE.request("play lofi beats"))
    }

    @Test
    fun directions() {
        assertEquals(Request.Navigate("walmart", true), Topic.DIRECTIONS.request("walmart"))
        assertEquals(Request.Navigate("airport", true), Topic.DIRECTIONS.request("take me to the airport"))
    }

    @Test
    fun instantTopics() {
        assertTrue(Topic.NIGHT_SKY.request(Topic.NIGHT_SKY.instant!!) is Request.Sky)
        assertTrue(Topic.PHOTO_SPOTS.request(Topic.PHOTO_SPOTS.instant!!) is Request.PhotoSpots)
        assertEquals(Topic.SPOTIFY, Topic.of("spotify"))
    }
}
