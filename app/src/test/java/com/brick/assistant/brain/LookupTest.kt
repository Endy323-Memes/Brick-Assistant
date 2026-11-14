package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit conversions, spelling and lookups in everyday wording. */
class LookupTest {
    private fun conv(s: String) = (IntentParser.parse(s) as? Request.Convert)?.conversion

    @Test
    fun conversions() {
        assertEquals(1000.0, conv("convert seconds to milliseconds")!!.result, 1e-9)
        assertEquals(365.0, conv("how many days are in a year")!!.result, 1e-9)
        assertEquals(365.0, conv("days in a year")!!.result, 1e-9)
        assertEquals(366.0, conv("how many days in a leap year")!!.result, 1e-9)
        assertEquals(60.0, conv("5 feet to inches")!!.result, 1e-9)
        assertEquals(12.0, conv("how many inches in a foot")!!.result, 1e-9)
        assertEquals(1e6, conv("kilograms to milligrams")!!.result, 1e-3)
        assertEquals(2500.0, conv("what's 2.5 kg in grams")!!.result, 1e-9)
        assertEquals(16.0, conv("how many ounces make a pound")!!.result, 1e-6)
        assertEquals(5280.0, conv("how many feet is a mile")!!.result, 1e-6)
        assertEquals(15840.0, conv("how many feet are in 3 miles")!!.result, 1e-6)
        assertEquals(24.0, conv("how many hours does a day have")!!.result, 1e-9)
        assertEquals(37.7778, conv("100 fahrenheit to celsius")!!.result, 1e-3)
        assertEquals(16.0, conv("how many cups in a gallon")!!.result, 1e-6)
        assertEquals(1.0, conv("1000 meters in kilometers")!!.result, 1e-9)
        assertEquals(1000.0, conv("1,000 grams to kilograms")!!.amount, 1e-9)
        assertEquals("1 second is 1,000 milliseconds.", conv("seconds to milliseconds")!!.spoken)
        assertEquals("1 year is 365 days.", conv("days in a year")!!.spoken)
        assertEquals("5,280", Units.format(5280.0))
        assertEquals("0.3333333", Units.format(1 / 3.0))
    }

    @Test
    fun notConversions() {
        assertTrue(IntentParser.parse("set a timer for 5 minutes") is Request.Timer)
        assertNull(conv("how many people live in a city"))
        assertNull(conv("5 feet to seconds"))
        assertNull(conv("remind me in 10 minutes to stretch"))
    }

    @Test
    fun spelling() {
        fun sp(s: String) = (IntentParser.parse(s) as? Request.Spell)?.word
        assertEquals("necessary", sp("how do you spell necessary"))
        assertEquals("rhythm", sp("how is rhythm spelled"))
        assertEquals("definitely", sp("spell definitely for me"))
        assertEquals("colonel", sp("what's the spelling of colonel"))
        assertEquals("bureaucracy", sp("can you spell bureaucracy"))
        assertEquals("receive", sp("how to spell receive"))
        assertEquals("right", sp("how do you spell right"))
        assertEquals("queue", sp("what are the letters in queue"))
        assertEquals(null, sp("how is the weather"))
        assertEquals("N, E, C, E, S, S, A, R, Y", SpellResult("necessary", "necessary", false, emptyList()).spokenLetters)
        assertEquals("Their is spelled T, H, E, I, R. Not to be confused with there or they're.",
            Spelling.spoken(SpellResult("their", "their", false, listOf("there", "they're"))))
    }

    @Test
    fun lookupWording() {
        fun u(s: String) = Lookup.understand(s)
        assertEquals(LookupQuery("baywatch", LookupKind.MOVIE, LookupAspect.GENERAL), u("what is the movie baywatch"))
        assertEquals(LookupQuery("baywatch", LookupKind.TV_SHOW, LookupAspect.GENERAL), u("tell me about the show baywatch"))
        assertEquals(LookupQuery("keanu reeves", LookupKind.PERSON, LookupAspect.GENERAL), u("who is keanu reeves"))
        assertEquals(LookupQuery("zendaya", LookupKind.PERSON, LookupAspect.AGE), u("how old is zendaya"))
        assertEquals(LookupQuery("dune", LookupKind.MOVIE, LookupAspect.CAST), u("who's in the movie dune"))
        assertEquals(LookupQuery("breaking bad", LookupKind.TV_SHOW, LookupAspect.SEASONS), u("how many seasons does breaking bad have"))
        assertEquals(LookupQuery("discord", LookupKind.PLATFORM, LookupAspect.GENERAL), u("what is the app discord"))
        assertEquals(LookupQuery("twitch", LookupKind.PLATFORM, LookupAspect.FOUNDER), u("who founded the platform twitch"))
        assertEquals(LookupQuery("inception", LookupKind.MOVIE, LookupAspect.DIRECTOR), u("who directed inception"))
        assertEquals(LookupAspect.RELEASE, u("when did oppenheimer come out").aspect)
        assertEquals("oppenheimer", u("when did oppenheimer come out").subject)
        assertEquals(LookupKind.PERSON, u("look up the actor who played neo").kind)
    }

    @Test
    fun awardsWording() {
        fun u(s: String) = Lookup.understand(s)
        for ((s, subject) in listOf(
            "what awards did dune win" to "dune", "breaking bad awards" to "breaking bad", "what awards has zendaya won" to "zendaya",
            "how many awards did the last of us win" to "last of us", "what was elden ring nominated for" to "elden ring",
            "can you tell me what awards oppenheimer won" to "oppenheimer", "awards for the album thriller" to "thriller",
            "what are the awards for succession" to "succession",
        )) {
            assertEquals(s, LookupAspect.AWARDS, u(s).aspect)
            assertEquals(s, subject, u(s).subject)
        }
        assertEquals("Oscar", u("did oppenheimer win an oscar").award)
        assertEquals("oppenheimer", u("did oppenheimer win an oscar").subject)
        assertEquals("Grammy", u("how many grammys does taylor swift have").award)
        assertEquals("taylor swift", u("how many grammys does taylor swift have").subject)
        assertEquals("Emmy", u("has the bear won an emmy").award)
        assertEquals("best picture", u("did oppenheimer win best picture").award)
        assertEquals(LookupKind.MUSIC, u("awards for the album thriller").kind)
    }

    @Test
    fun charactersCastAndRoles() {
        fun u(s: String) = Lookup.understand(s)
        for ((s, subject) in listOf(
            "who are the main characters in breaking bad" to "breaking bad", "characters in the hobbit" to "the hobbit",
            "zelda characters" to "zelda", "list the characters in stranger things" to "stranger things",
            "who's the main character in the witcher" to "the witcher", "characters in the last of us game" to "last of us",
        )) {
            assertEquals(s, LookupAspect.CHARACTERS, u(s).aspect)
            assertEquals(s, subject, u(s).subject)
        }
        // Not "the newest last of us game".
        assertTrue(IntentParser.parse("characters in the last of us game") is Request.Search)
        for ((s, subject) in listOf(
            "who stars in dune" to "dune", "main actors in the office" to "the office", "who's the main star of top gun" to "top gun",
            "the office cast" to "the office", "who are the stars of the bear" to "the bear", "who is in dune" to "dune",
            "who's starring in barbie" to "barbie",
        )) {
            assertEquals(s, LookupAspect.CAST, u(s).aspect)
            assertEquals(s, subject, u(s).subject)
        }
        u("who plays walter white in breaking bad").let { assertEquals(LookupAspect.ROLE, it.aspect); assertEquals("walter white", it.role); assertEquals("breaking bad", it.subject) }
        u("who played neo in the matrix").let { assertEquals("neo", it.role); assertEquals("the matrix", it.subject) }
        u("who is walter white in breaking bad").let { assertEquals("walter white", it.role) }
        u("who played the joker in the dark knight").let { assertEquals("the joker", it.role); assertEquals("dark knight", it.subject) }
        u("who did the voice of shrek").let { assertEquals(LookupAspect.ROLE, it.aspect); assertEquals("shrek", it.subject) }
        // Not roles.
        assertTrue(u("who is the best player in the nba").aspect != LookupAspect.ROLE)
        assertTrue(u("who was born in 1990").aspect != LookupAspect.ROLE)
        assertTrue(u("who plays for the lakers").aspect != LookupAspect.ROLE)
    }

    @Test
    fun lengthWording() {
        fun u(s: String) = Lookup.understand(s)
        for ((s, want) in listOf(
            "how long is the movie dune" to (LookupKind.MOVIE to "dune"),
            "what is the runtime of the godfather" to (LookupKind.MOVIE to "the godfather"),
            "duration of interstellar" to (LookupKind.MOVIE to "interstellar"),
            "how long are episodes of the office" to (LookupKind.TV_SHOW to "the office"),
            "how long is the album thriller" to (LookupKind.MUSIC to "thriller"),
            "how many tracks are on the album thriller" to (LookupKind.MUSIC to "thriller"),
            "how long is the song bohemian rhapsody" to (LookupKind.MUSIC to "bohemian rhapsody"),
            "how many pages is the hobbit" to (LookupKind.BOOK to "the hobbit"),
            "how many pages does the hobbit have" to (LookupKind.BOOK to "the hobbit"),
            "how long is the book dune" to (LookupKind.BOOK to "dune"),
            "how long does it take to beat elden ring" to (LookupKind.GAME to "elden ring"),
            "elden ring playtime" to (LookupKind.GAME to "elden ring"),
        )) {
            val q = u(s)
            assertEquals(s, LookupAspect.RUNTIME, q.aspect)
            assertEquals(s, want.first, q.kind)
            assertEquals(s, want.second, q.subject)
        }
        assertTrue(u("how long is the album thriller").album)
        assertEquals("2 hours and 35 minutes", Lookup.spokenLength(9300))
        assertEquals("22 minutes", Lookup.spokenLength(1320))
        assertEquals("5 minutes and 54 seconds", Lookup.spokenLength(354, music = true))
    }

    @Test
    fun otherSourcesMatchTitlesExactly() {
        assertTrue(LookupExtras.same("The Office (US)", "the office"))
        assertTrue(LookupExtras.same("ELDEN RING", "elden ring"))
        assertTrue(!LookupExtras.same("Dune: Prophecy", "dune"))
    }

    @Test
    fun classifyDescriptions() {
        assertEquals(LookupKind.MOVIE, LookupKind.classify("2017 film by Seth Gordon"))
        assertEquals(LookupKind.TV_SHOW, LookupKind.classify("American action drama television series (1989–2001)"))
        assertEquals(LookupKind.PERSON, LookupKind.classify("Canadian actor (born 1964)"))
        assertEquals(LookupKind.PLATFORM, LookupKind.classify("Instant messaging and VoIP social platform"))
        assertEquals(LookupKind.COMPANY, LookupKind.classify("American multinational technology company"))
        assertEquals(LookupKind.GAME, LookupKind.classify("2017 video game"))
        assertEquals(LookupKind.PLACE, LookupKind.classify("Capital city of France"))
    }

    @Test
    fun poolsExist() {
        val keys = com.brick.assistant.data.ResponseRules.all.map { it.key }.toSet()
        for (k in listOf("search.person", "search.movie", "search.tv_show", "search.platform", "search.spell", "math.convert",
            "weather.week", "weather.weekend", "maps.saved", "stopwatch", "device.settings.where", "search.awards", "search.characters", "search.cast", "search.length")) assertTrue(k, k in keys)
        for (k in LookupKind.entries) assertTrue(k.name, "search.${k.name.lowercase()}" in keys)
    }
}
