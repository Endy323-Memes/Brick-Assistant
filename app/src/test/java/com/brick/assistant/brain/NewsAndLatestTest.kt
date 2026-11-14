package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NewsAndLatestTest {
    private fun news(s: String) = IntentParser.parse(s).let { assertTrue("'$s' -> $it", it is Request.News); it as Request.News }
    private fun latest(s: String) = IntentParser.parse(s).let { assertTrue("'$s' -> $it", it is Request.Latest); it as Request.Latest }
    private fun ask(s: String) = IntentParser.parse(s).let { assertTrue("'$s' -> $it", it is Request.MediaAsk); it as Request.MediaAsk }

    @Test fun newsBySection() {
        for (s in listOf("what's the news", "news", "headlines", "give me the headlines", "what's going on in the news", "read me today's top stories", "catch me up on the news"))
            assertEquals(s, NewsTopic.TOP, news(s).topic)
        for (s in listOf("tech news", "what's the latest in tech news", "any technology news today", "ai news", "news about tech"))
            assertEquals(s, NewsTopic.TECH, news(s).topic)
        for (s in listOf("economics news", "economy news", "what's happening with the economy", "business headlines", "stock market news"))
            assertEquals(s, NewsTopic.BUSINESS, news(s).topic)
        for (s in listOf("science news", "anything new in science", "space news")) assertEquals(s, NewsTopic.SCIENCE, news(s).topic)
        for (s in listOf("politics news", "political news", "what's the latest in politics", "news on politics")) assertEquals(s, NewsTopic.POLITICS, news(s).topic)
        for (s in listOf("international affairs news", "world news", "what's happening in the world", "global headlines", "foreign news"))
            assertEquals(s, NewsTopic.WORLD, news(s).topic)
        for (s in listOf("breaking news", "any breaking news", "what's the breaking news right now")) assertEquals(s, NewsTopic.BREAKING, news(s).topic)
        assertEquals(NewsTopic.SPORTS, news("sports headlines").topic)
        assertEquals(NewsTopic.HEALTH, news("health news").topic)
    }

    @Test fun newsAboutAnything() {
        assertEquals("ukraine", news("news about ukraine").query)
        assertEquals("ukraine", news("what's happening in ukraine news").query)
        assertEquals("taylor swift", news("taylor swift news").query)
        assertEquals(NewsTopic.POLITICS, news("any updates on the election").topic)
        assertEquals("olympics opening ceremony", news("any updates on the olympics opening ceremony").query)
        assertEquals("spacex", news("latest news on spacex").query)
    }

    @Test fun notNews() {
        assertFalse(IntentParser.parse("open google news") is Request.News)
        assertFalse(IntentParser.parse("what's going on with my phone") is Request.News)
        assertFalse(IntentParser.parse("what's the newest godzilla movie") is Request.News)
        assertNull(News.parse("i have good news"))
    }

    @Test fun readsGoogleNewsRss() {
        val xml = """<rss><channel><item><title>Big thing happens &amp; more - The Paper</title><link>https://news.example/a</link>
            <pubDate>Thu, 12 Nov 2026 18:00:00 GMT</pubDate><source url="https://paper.example">The Paper</source></item>
            <item><title><![CDATA[Second story - Wire]]></title><link>https://news.example/b</link><source url="x">Wire</source></item></channel></rss>"""
        val h = News.parseRss(xml)
        assertEquals(2, h.size)
        assertEquals("Big thing happens & more", h[0].title)
        assertEquals("The Paper", h[0].source)
        assertEquals("Second story", h[1].title)
        assertEquals("Here's the top tech stories. They're on the card above.", News.line(NewsTopic.TECH, null, h))
    }

    @Test fun newestMovieAndSeasonQuestions() {
        for (s in listOf("what's the most recent godzilla movie", "whats the newest godzilla movie?", "latest godzilla film", "newest godzilla movie", "godzilla latest movie", "what is the last godzilla movie"))
            latest(s).let { assertEquals(s, "godzilla", it.name); assertEquals(LookupKind.MOVIE, it.kind); assertFalse(s, it.next) }
        latest("what's the new avengers movie").let { assertEquals("avengers", it.name); assertEquals(LookupKind.MOVIE, it.kind) }
        latest("when is the next avengers movie coming out").let { assertEquals("avengers", it.name); assertTrue(it.next) }
        latest("is there a new dune movie").let { assertEquals("dune", it.name) }
        latest("what's the latest season of the bear").let { assertEquals("bear", it.name); assertEquals(LookupKind.TV_SHOW, it.kind) }
        latest("the bear newest season").let { assertEquals(LookupKind.TV_SHOW, it.kind) }
        latest("when does the next season of severance come out").let { assertEquals("severance", it.name); assertTrue(it.next) }
    }

    @Test fun buyingAndWatchingAskAboutSeriesAndSeasons() {
        ask("where can i buy avengers").let { assertEquals(MediaAskKind.BUY_VIDEO, it.kind); assertEquals("avengers", it.title); assertFalse(it.newest) }
        ask("where can i buy the newest avengers movie").let { assertEquals("avengers", it.title); assertTrue(it.newest) }
        ask("where can i buy season 2 of the bear").let { assertEquals(2, it.season); assertEquals(LookupKind.TV_SHOW, it.video) }
        ask("where can i buy the second season of the bear").let { assertEquals(2, it.season) }
        ask("where can i buy the latest season of the bear").let { assertTrue(it.newest); assertEquals(LookupKind.TV_SHOW, it.video) }
        ask("where can i buy every season of the bear").let { assertEquals(0, it.season) }
        ask("where can i watch the newest godzilla movie").let { assertEquals("godzilla", it.title); assertTrue(it.newest) }
    }

    private fun d(y: Int, m: Int = 6) = LocalDate.of(y, m, 1)

    @Test fun seriesMembersAndPicking() {
        val films = listOf(
            Film("The Avengers", d(2012)), Film("Avengers: Age of Ultron", d(2015)), Film("Avengers: Infinity War", d(2018)),
            Film("Avengers: Endgame", d(2019)), Film("Up in the Air", d(2009)), Film("The Avengers", d(1998)),
        )
        val m = Franchise.members("avengers", films)
        assertEquals(5, m.size)
        assertTrue(Franchise.isSeries("avengers", m))
        assertEquals("Avengers: Endgame", Franchise.pickFilm("the newest one", m)!!.title)
        assertEquals("Avengers: Age of Ultron", Franchise.pickFilm("age of ultron", m)!!.title)
        assertEquals("Avengers: Infinity War", Franchise.pickFilm("2018", m)!!.title)
        assertEquals(1998, Franchise.pickFilm("the first one", m)!!.year)
        assertEquals("Endgame", Franchise.short("avengers", "Avengers: Endgame"))
        assertEquals("The Avengers (2012 film)", Franchise.lookupTitle(m[1], m))
        // One movie, not a series.
        val up = Franchise.members("up", listOf(Film("Up", d(2009)), Film("Up in the Air", d(2009))))
        assertFalse(Franchise.isSeries("up", up))
        assertFalse(Franchise.isSeries("avengers age of ultron", Franchise.members("avengers age of ultron", films)))
        val gz = Franchise.members("godzilla", listOf(Film("Godzilla", d(2014)), Film("Godzilla vs. Kong", d(2021)), Film("Shin Godzilla", d(2016)), Film("Godzilla x Kong: The New Empire", d(2024))))
        assertEquals(4, gz.size)
        assertTrue(Franchise.isSeries("godzilla", gz))
    }

    @Test fun pickingSeasons() {
        assertEquals(2, Franchise.pickSeason("season 2", 4))
        assertEquals(3, Franchise.pickSeason("the third one", 4))
        assertEquals(4, Franchise.pickSeason("the newest", 4))
        assertEquals(0, Franchise.pickSeason("all of them", 4))
        assertEquals(1, Franchise.pickSeason("the first", 4))
        assertNull(Franchise.pickSeason("season 9", 4))
    }

    @Test fun newestLines() {
        val today = LocalDate.of(2026, 11, 12)
        val films = listOf(Film("Avengers: Endgame", LocalDate.of(2019, 4, 26)), Film("Avengers: Doomsday", LocalDate.of(2026, 12, 18)))
        assertEquals("The newest Avengers movie is Avengers: Endgame, from April 2019. The next one, Avengers: Doomsday, comes out December 18, 2026.",
            Franchise.latestMovieLine("avengers", films, false, today))
        val show = ShowSeasons("The Bear", listOf(Season(1, LocalDate.of(2022, 6, 23), 8), Season(2, LocalDate.of(2023, 6, 22), 10)), running = true)
        assertEquals("The Bear's newest season is season 2, which premiered June 22, 2023. There are 2 seasons out.", Franchise.latestSeasonLine(show, false, today))
    }
}
