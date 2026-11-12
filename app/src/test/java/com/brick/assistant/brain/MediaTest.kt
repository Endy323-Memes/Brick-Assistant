package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ratings, where to watch / buy / listen, and song credits, in everyday and loose wording. */
class MediaTest {
    private fun ask(s: String) = IntentParser.parse(s) as? Request.MediaAsk
    private fun kind(s: String) = ask(s)?.kind
    private fun title(s: String) = ask(s)?.title

    @Test
    fun ratings() {
        for (s in listOf(
            "what's the rating of inception", "what is breaking bad rated", "how is the movie dune rated", "is severance worth watching",
            "rotten tomatoes score for barbie", "imdb rating of the office", "how good is oppenheimer", "what do critics think of dune",
            "barbie reviews", "is the show the bear any good", "what did inception get on rotten tomatoes", "should i watch severance",
            "what's the age rating for deadpool", "dune rotten tomatoes", "imdb breaking bad", "oppenheimer ratings",
        )) assertEquals(s, MediaAskKind.RATING, kind(s))
        assertEquals("inception", title("what's the rating of inception"))
        assertEquals("dune", title("how is the movie dune rated"))
        assertEquals(LookupKind.MOVIE, ask("how is the movie dune rated")!!.video)
        assertEquals("the office", title("imdb rating of the office"))
        assertEquals("the bear", title("is the show the bear any good"))
        assertEquals("dune", title("dune rotten tomatoes"))
    }

    @Test
    fun watch() {
        for (s in listOf(
            "where can i watch inception", "where can i stream breaking bad", "where is severance streaming", "where to watch dune",
            "what app has the office", "what streaming service is stranger things on", "is the office on netflix", "how can i watch the bear",
            "where can i watch barbie for free", "which platforms can i watch shrek on", "can i watch dune on max", "netflix severance",
            "stream the movie inception", "where can i see the movie barbie",
        )) assertEquals(s, MediaAskKind.WATCH, kind(s))
        assertEquals("inception", title("where can i watch inception"))
        assertEquals("the office", title("is the office on netflix"))
        assertEquals("netflix", ask("is the office on netflix")!!.service)
        assertTrue(ask("where can i watch barbie for free")!!.free)
        assertEquals("barbie", title("where can i watch barbie for free"))
        assertEquals("stranger things", title("what streaming service is stranger things on"))
    }

    @Test
    fun buyVideo() {
        for (s in listOf(
            "where can i buy the movie dune", "where can i buy inception on blu ray", "where to buy breaking bad on dvd", "where can i rent oppenheimer",
            "who sells the office box set", "where can i get dune digitally", "where can i buy a copy of the matrix on 4k", "dune blu ray price",
        )) assertEquals(s, MediaAskKind.BUY_VIDEO, kind(s))
        assertEquals(BuyFormat.PHYSICAL, ask("where can i buy inception on blu ray")!!.format)
        assertEquals("inception", title("where can i buy inception on blu ray"))
        assertEquals(BuyFormat.DIGITAL, ask("where can i get dune digitally")!!.format)
        assertTrue(ask("where can i rent oppenheimer")!!.rent)
        assertEquals("the matrix", title("where can i buy a copy of the matrix on 4k"))
    }

    @Test
    fun listen() {
        for (s in listOf(
            "where can i listen to blinding lights", "where can i stream the song levitating", "what apps have this song",
            "is blinding lights on spotify", "where can i listen to this song for free", "what platforms can i listen to this on",
            "where to listen to bad guy by billie eilish", "blinding lights spotify", "which app has the song espresso",
        )) assertEquals(s, MediaAskKind.LISTEN, kind(s))
        assertEquals("blinding lights", title("where can i listen to blinding lights"))
        assertNull(title("where can i listen to this song for free"))
        assertTrue(ask("where can i listen to this song for free")!!.free)
        assertEquals("billie eilish", ask("where to listen to bad guy by billie eilish")!!.artist)
        assertEquals("bad guy", title("where to listen to bad guy by billie eilish"))
        assertEquals("spotify", ask("is blinding lights on spotify")!!.service)
    }

    @Test
    fun buySong() {
        for (s in listOf(
            "where can i buy this song", "where can i buy the song blinding lights", "where can i download blinding lights by the weeknd",
            "where to buy the track espresso", "buy this song",
        )) assertEquals(s, MediaAskKind.BUY_SONG, kind(s))
        assertNull(title("where can i buy this song"))
        assertEquals("the weeknd", ask("where can i download blinding lights by the weeknd")!!.artist)
    }

    @Test
    fun credits() {
        fun role(s: String) = ask(s)?.takeIf { it.kind == MediaAskKind.CREDITS }?.role
        assertEquals(CreditRole.VOCALS, role("who sings this song"))
        assertEquals(CreditRole.VOCALS, role("who's singing"))
        assertEquals(CreditRole.VOCALS, role("who is the singer"))
        assertEquals(CreditRole.PRODUCER, role("who produced this"))
        assertEquals(CreditRole.PRODUCER, role("who made the beat"))
        assertEquals(CreditRole.PRODUCER, role("who's the producer of this song"))
        assertEquals(CreditRole.WRITER, role("who wrote this song"))
        assertEquals(CreditRole.COMPOSER, role("who composed this"))
        assertEquals(CreditRole.ALBUM, role("what album is this from"))
        assertEquals(CreditRole.RELEASE, role("when did this song come out"))
        assertEquals(CreditRole.GENRE, role("what genre is this"))
        assertEquals(CreditRole.ALL, role("song credits"))
        assertEquals(CreditRole.ALL, role("tell me about this song"))
        assertEquals(CreditRole.ALL, role("give me details about the song that's playing"))
        assertEquals(CreditRole.INSTRUMENT, role("who plays guitar on this song"))
        assertEquals("guitar", ask("who plays guitar on this song")!!.instrument)
        assertEquals(CreditRole.PRODUCER, role("producer of this song"))
        assertEquals(CreditRole.PRODUCER, role("this song's producer"))
        assertEquals(CreditRole.VOCALS, role("who sings blinding lights"))
        assertEquals("blinding lights", title("who sings blinding lights"))
        assertEquals(CreditRole.PRODUCER, role("who produced the song levitating"))
        assertNull(title("who sings this song"))
    }

    @Test
    fun leavesOthersAlone() {
        val bare = IntentParser.parse("where can i watch") as Request.MediaAsk
        assertEquals(MediaAskKind.WATCH, bare.kind)
        assertEquals(null, bare.title)
        assertFalse(IntentParser.parse("where can i buy shoes") is Request.MediaAsk)
        assertFalse(IntentParser.parse("where can i find a gas station") is Request.MediaAsk)
        assertFalse(IntentParser.parse("where can i watch the meteor shower") is Request.MediaAsk)
        assertFalse(IntentParser.parse("who wrote harry potter") is Request.MediaAsk)
        assertFalse(IntentParser.parse("who is keanu reeves") is Request.MediaAsk)
        assertFalse(IntentParser.parse("is the weather good") is Request.MediaAsk)
        assertFalse(IntentParser.parse("open netflix") is Request.MediaAsk)
        assertTrue(IntentParser.parse("what's playing") is Request.NowPlaying)
        assertFalse(IntentParser.parse("play blinding lights") is Request.MediaAsk)
    }

    @Test
    fun cleaning() {
        assertEquals("Blinding Lights" to "The Weeknd", MediaGuide.cleanPlaying("Blinding Lights (Official Video)", "TheWeekndVEVO").let { it.first to "The Weeknd" })
        assertEquals("Levitating" to "Dua Lipa", MediaGuide.cleanPlaying("Dua Lipa - Levitating (Official Music Video)", "Dua Lipa"))
        assertEquals("Peaches", MediaGuide.cleanPlaying("Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber").first)
        assertEquals(87, MediaGuide.percent("87%"))
        assertEquals(74, MediaGuide.percent("74/100"))
        assertEquals(88, MediaGuide.percent("8.8/10"))
        val scores = listOf(
            Lookup.ReviewScore("8.1/10", "Rotten Tomatoes", "Rotten Tomatoes average of rated reviews"),
            Lookup.ReviewScore("87%", "Rotten Tomatoes", "Tomatometer score"),
            Lookup.ReviewScore("74/100", "Metacritic", "Metascore"),
            Lookup.ReviewScore("8.8/10", "IMDb", null),
        )
        assertEquals("RT 87% · MC 74 · IMDb 8.8", MediaGuide.shortScores(scores))
    }

    @Test
    fun poolsExist() {
        val keys = com.brick.assistant.data.ResponseRules.all.map { it.key }.toSet()
        for (k in listOf("media.rating", "media.watch", "media.buy", "music.listen", "music.buy", "music.credits")) assertTrue(k, k in keys)
    }
}
