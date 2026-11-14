package com.brick.assistant.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorTest {
    private fun lookup(s: String): LookupQuery {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Search)
        return Lookup.understand((r as Request.Search).query)
    }

    @Test fun askingAboutControversies() {
        for ((s, who) in listOf(
            "what controversies has mrbeast been in" to "mrbeast",
            "has logan paul been in any controversies" to "logan paul",
            "why is logan paul controversial" to "logan paul",
            "pewdiepie controversies" to "pewdiepie",
            "pewdiepie's drama" to "pewdiepie",
            "what drama has james charles been in" to "james charles",
            "why was james charles cancelled" to "james charles",
            "why do people hate jake paul" to "jake paul",
            "what scandals has colleen ballinger been involved in" to "colleen ballinger",
            "what did tana mongeau do wrong" to "tana mongeau",
            "the controversy around dream" to "dream",
            "is dream cancelled" to "dream",
        )) {
            val q = lookup(s)
            assertEquals(s, LookupAspect.CONTROVERSY, q.aspect)
            assertEquals(s, who, q.subject)
        }
    }

    @Test fun notControversies() {
        for (s in listOf("best korean drama", "issues with my wifi", "teen drama shows", "what's the beef stew recipe", "who is mrbeast", "what is mrbeast known for")) {
            val r = IntentParser.parse(s)
            val aspect = (r as? Request.Search)?.let { Lookup.understand(it.query).aspect }
            assertNotEquals(s, LookupAspect.CONTROVERSY, aspect)
        }
    }

    @Test fun spottingCreators() {
        assertTrue(Creators.isCreator("American YouTuber (born 1998)"))
        assertTrue(Creators.isCreator("Swedish YouTuber"))
        assertTrue(Creators.isCreator(null, "Felix Kjellberg, known online as PewDiePie, is a Swedish internet personality and former YouTuber."))
        assertTrue(Creators.isCreator("American Twitch streamer"))
        assertFalse(Creators.isCreator("American actor (born 1956)"))
    }

    @Test fun channelFromWikidata() {
        val claims = JSONObject("""{
          "P2397":[{"rank":"normal","mainsnak":{"datavalue":{"value":"UCX6OQ3DkcsbYNE6H8uQQuVA"}}}],
          "P8687":[
            {"mainsnak":{"datavalue":{"value":{"amount":"+100000000"}}},"qualifiers":{"P2397":[{"datavalue":{"value":"UCX6OQ3DkcsbYNE6H8uQQuVA"}}],"P585":[{"datavalue":{"value":{"time":"+2022-07-01T00:00:00Z"}}}]}},
            {"mainsnak":{"datavalue":{"value":{"amount":"+286000000"}}},"qualifiers":{"P2397":[{"datavalue":{"value":"UCX6OQ3DkcsbYNE6H8uQQuVA"}}],"P585":[{"datavalue":{"value":{"time":"+2024-10-01T00:00:00Z"}}}]}},
            {"mainsnak":{"datavalue":{"value":{"amount":"+30000000"}}},"qualifiers":{"P6552":[{"datavalue":{"value":"123"}}],"P585":[{"datavalue":{"value":{"time":"+2025-01-01T00:00:00Z"}}}]}}
          ]}""")
        val c = Creators.channel(claims)!!
        assertEquals(286_000_000L, c.subscribers)
        assertEquals("2024-10-01", c.asOf)
        assertEquals("https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA", c.url)
        assertEquals("286 million", Creators.bigCount(286_000_000))
        assertEquals("4.2 million", Creators.bigCount(4_200_000))
    }

    @Test fun readingControversies() {
        val text = """Jimmy Example is an American YouTuber known for stunt videos.

== Career ==
He started in 2012.

== Controversies ==
Example has faced several controversies over his business dealings.

=== Forest video ===
In 2018, Example was widely criticized for posting a video filmed in a forest.

=== Lawsuit ===
In 2024, contestants sued Example over working conditions.

== References ==
Something."""
        val c = Creators.controversies(text, listOf("jimmy", "example"))
        assertEquals(listOf(null, "Forest video", "Lawsuit"), c.map { it.topic })
        assertTrue(Creators.spoken("Jimmy Example", c).startsWith("Jimmy Example's controversies include Forest video and Lawsuit."))
        // No such section: sentences that tell of one.
        val plain = Creators.controversies("Ann Example is a streamer. In 2021, Ann faced backlash after a stream. She enjoys hiking.", listOf("ann", "example"))
        assertEquals(1, plain.size)
        assertEquals("I couldn't find any major controversies about Bob.", Creators.spoken("Bob", emptyList()))
        assertEquals("Minecraft series", Creators.topic("2019–2020: Minecraft series"))
        assertEquals("Media controversies", Creators.focus("2017–2018: Media controversies and formatted shows"))
        assertEquals(null, Creators.focus("2019–2020: Minecraft series, milestones, and other controversies"))
        assertEquals(null, Creators.focus("Controversies"))
        assertEquals("Lawsuits", Creators.focus("Lawsuits"))
        // Career subsections inside a Controversy section don't count.
        val mixed = Creators.controversies("""== Controversies and career ==
Intro sentence about the career of this person.

=== 2019–2020: Minecraft series, milestones ===
He started a Minecraft series that became very popular with fans.

=== 2017 anti-Semitism controversy ===
In 2017, he was criticized for videos that included anti-Semitic jokes.""", listOf("he"))
        assertEquals(listOf("2017 anti-Semitism controversy"), mixed.mapNotNull { it.topic })
    }
}
