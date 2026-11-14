package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankedTest {
    private fun p(s: String) = IntentParser.parse(s)
    private fun r(s: String) = p(s) as? Request.Ranked ?: error("\"$s\" -> ${p(s)}")

    @Test fun moviesByTier() {
        r("what are the best godzilla movies").let { assertEquals(RankKind.MOVIE, it.kind); assertEquals(Tier.GOOD, it.tier); assertEquals("godzilla", it.filter) }
        r("worst alien films").let { assertEquals(Tier.BAD, it.tier); assertEquals("alien", it.filter) }
        r("which star wars movies are mid").let { assertEquals(Tier.MEDIOCRE, it.tier); assertEquals("star wars", it.filter) }
        r("give me some good movies to watch").let { assertEquals(Tier.GOOD, it.tier); assertNull(it.filter) }
        r("rank the transformers movies").let { assertEquals(Tier.ALL, it.tier); assertEquals("transformers", it.filter) }
        r("are the jurassic park movies any good").let { assertEquals(Tier.ALL, it.tier); assertEquals("jurassic park", it.filter) }
        r("top 5 marvel movies").let { assertEquals(5, it.count); assertEquals("marvel", it.filter) }
        r("which mission impossible movies are not bad").let { assertEquals(Tier.MEDIOCRE, it.tier) }
        r("ok what are some terrible horror flicks").let { assertEquals(Tier.BAD, it.tier) }
        r("recommend a good movie").let { assertEquals(Tier.GOOD, it.tier); assertNull(it.filter) }
        r("show me the highest rated pixar films").let { assertEquals(RankKind.MOVIE, it.kind); assertEquals("pixar", it.filter) }
    }

    @Test fun showsSongsNews() {
        r("best star trek shows").let { assertEquals(RankKind.SHOW, it.kind); assertEquals("star trek", it.filter) }
        r("any good tv series right now").let { assertEquals(RankKind.SHOW, it.kind); assertNull(it.filter) }
        r("what are taylor swift's best songs").let { assertEquals(RankKind.SONG, it.kind); assertEquals("taylor swift", it.filter) }
        r("mediocre songs by drake").let { assertEquals(Tier.MEDIOCRE, it.tier); assertEquals("drake", it.filter) }
        r("show me good songs").let { assertEquals(RankKind.SONG, it.kind) }
        r("daft punk's biggest hits").let { assertEquals(RankKind.SONG, it.kind); assertEquals(Tier.GOOD, it.tier); assertEquals("daft punk", it.filter) }
        r("give me some good news").let { assertEquals(RankKind.NEWS, it.kind); assertEquals(Tier.GOOD, it.tier); assertNull(it.filter) }
        r("any bad news from reuters").let { assertEquals(Tier.BAD, it.tier); assertEquals("Reuters", it.filter) }
        r("uplifting stories from the verge").let { assertEquals(RankKind.NEWS, it.kind); assertEquals("The Verge", it.filter) }
    }

    @Test fun notLists() {
        for (s in listOf("is dune good", "i have bad news", "that's good news", "play some good music", "where can i buy the best godzilla movie",
                "what's the news", "good morning", "what is inception rated", "what's the newest godzilla movie")) {
            assertFalse(s, p(s) is Request.Ranked)
        }
        // Plain news from an outlet stays news.
        (p("tech news from the verge") as Request.News).let { assertEquals("The Verge", it.source?.name) }
        (p("bbc top stories") as Request.News).let { assertEquals("BBC", it.source?.name) }
        // A place isn't an outlet.
        (p("news from ukraine") as Request.News).let { assertNull(it.source); assertEquals("ukraine", it.query) }
        (p("news from the springfield herald") as Request.News).let { assertEquals("The Springfield Herald", it.source?.name) }
    }

    @Test fun scores() {
        assertEquals(87.0, Ranked.normalize("87%")!!, 0.01)
        assertEquals(81.0, Ranked.normalize("8,1/10")!!, 0.01)
        assertEquals(90.0, Ranked.normalize("4.5/5")!!, 0.01)
        assertNull(Ranked.normalize("movie/inception"))
        val inception = listOf(
            Ranked.Fact("Q105584", "Q108403393", "87%"), Ranked.Fact("Q105584", "Q108403540", "91%"),
            Ranked.Fact("Q150248", "Q106515043", "74/100"), Ranked.Fact("Q37312", null, "8.8/10"),
        )
        val (score, shown) = Ranked.combine(inception)!!
        assertEquals(80.5, score, 0.01)
        assertEquals("RT 87% · MC 74", shown)
        assertEquals("IMDb 6.2", Ranked.combine(listOf(Ranked.Fact("Q37312", null, "6.2/10")))!!.second)
        assertNull(Ranked.combine(emptyList()))
        assertEquals(Tier.GOOD, Ranked.tierOf(RankKind.MOVIE, 80.0))
        assertEquals(Tier.MEDIOCRE, Ranked.tierOf(RankKind.MOVIE, 55.0))
        assertEquals(Tier.BAD, Ranked.tierOf(RankKind.SHOW, 60.0))
        // The newest score wins.
        assertEquals("RT 90%", Ranked.combine(listOf(Ranked.Fact("Q105584", "Q108403393", "80%", "2020"), Ranked.Fact("Q105584", "Q108403393", "90%", "2024")))!!.second)
    }

    @Test fun thirdsAndTone() {
        val songs = (1..9).map { Rated("s$it", null, it * 10.0, "", null) }
        val t = Ranked.byThirds(songs)
        assertEquals(listOf("s9", "s8", "s7"), t.filter { it.tier == Tier.GOOD }.map { it.title })
        assertEquals(listOf("s3", "s2", "s1"), t.filter { it.tier == Tier.BAD }.map { it.title })
        assertEquals(Tier.GOOD, Ranked.newsTier("Firefighters rescue puppy, community celebrates"))
        assertEquals(Tier.BAD, Ranked.newsTier("Storm kills 12 as floods hit coast"))
        assertEquals(Tier.MEDIOCRE, Ranked.newsTier("City council meets on Tuesday"))
    }

    @Test fun lines() {
        val films = listOf(
            Rated("Godzilla Minus One", "2023", 98.0, "RT 98% · MC 81", null, Tier.GOOD),
            Rated("Shin Godzilla", "2016", 86.0, "RT 86%", null, Tier.GOOD),
            Rated("Godzilla", "1998", 30.0, "RT 20% · MC 32", null, Tier.BAD),
        )
        val good = Ranked.line(RankKind.MOVIE, Tier.GOOD, "godzilla", films, null)
        assertEquals("I found 2 good Godzilla movies, going by Rotten Tomatoes and Metacritic critics. They're on the card above.", good)
        val mid = Ranked.line(RankKind.MOVIE, Tier.MEDIOCRE, "godzilla", films, null)
        assertTrue(mid, mid.startsWith("None of the Godzilla movies I found are so-so"))
        val all = Ranked.line(RankKind.MOVIE, Tier.ALL, "godzilla", films, null)
        assertTrue(all, all.contains("2 good, 0 so-so and 1 bad") && all.endsWith("on the card above."))
        assertEquals(listOf("Godzilla"), Ranked.pick(RankKind.MOVIE, Tier.BAD, films, null).map { it.title })
        assertNotNull(Ranked.line(RankKind.SONG, Tier.GOOD, "Drake", emptyList(), null))
    }
}
