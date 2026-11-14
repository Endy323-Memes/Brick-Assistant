package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SportsHistoryTest {
    private fun sports(s: String): SportsAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Sports)
        return (r as Request.Sports).ask
    }

    private fun history(s: String): HistoryAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.History)
        return (r as Request.History).ask
    }

    private fun lookup(s: String): LookupQuery {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Search)
        return Lookup.understand((r as Request.Search).query)
    }

    @Test fun scores() {
        for (s in listOf("whats the score of the knicks game", "knicks score", "how are the knicks doing", "are the eagles winning",
                "who's winning the lakers game", "how's the yankees game going", "score of the game between the knicks and pacers")) {
            val a = sports(s)
            assertEquals(s, SportsWant.SCORE, a.want)
            assertTrue(s, a.teams.isNotEmpty())
        }
        for (s in listOf("scores for current games", "what games are on right now", "live scores", "any games on tonight", "sports scores")) {
            assertEquals(s, SportsWant.SCORE, sports(s).want)
        }
        assertEquals(League.NBA, sports("nba scores").league)
        assertEquals(listOf("knicks", "pacers"), sports("knicks vs pacers score").teams)
    }

    @Test fun results() {
        val now = LocalDate.now()
        sports("who won the eagles cowboys game in 2023").let { assertEquals(SportsWant.RESULT, it.want); assertEquals(listOf("eagles", "cowboys"), it.teams); assertEquals(2023, it.year) }
        sports("did the eagles beat the cowboys in 2023").let { assertEquals(SportsWant.RESULT, it.want); assertEquals(2023, it.year) }
        sports("who won the lakers game last night").let { assertEquals(SportsWant.RESULT, it.want); assertEquals(now.minusDays(1), it.date) }
        sports("did the yankees win yesterday").let { assertEquals(SportsWant.RESULT, it.want); assertEquals(now.minusDays(1), it.date) }
        assertEquals(SportsWant.RESULT, sports("how did the knicks do last night").want)
        assertEquals(SportsWant.RESULT, sports("eagles cowboys score 2023").want)
        assertEquals(SportsWant.RESULT, sports("what was the score of the chiefs game on sunday").want)
        assertEquals(SportsWant.RESULT, sports("who won between the celtics and heat in 2024").want)
        // A team Brick's own list doesn't have.
        sports("who won the alabama game").let { assertEquals(SportsWant.RESULT, it.want); assertEquals(listOf("alabama"), it.teams) }
    }

    @Test fun champions() {
        sports("who won the super bowl in 1998").let { assertEquals(SportsWant.CHAMPION, it.want); assertEquals("Super Bowl", it.event); assertEquals(1998, it.year) }
        sports("who won the world series last year").let { assertEquals("World Series", it.event); assertEquals(LocalDate.now().year - 1, it.year) }
        assertEquals("World Cup", sports("2010 world cup winner").event)
        assertEquals("NBA Finals", sports("who won the 2019 nba finals").event)
        assertEquals("March Madness", sports("who won march madness in 2019").event)
        assertEquals("Wimbledon", sports("who won wimbledon 2019").event)
        assertEquals("NBA Finals", sports("who are the current nba champions").event)
        assertEquals("Stanley Cup", sports("who won the stanley cup").event)
        sports("did the eagles win the super bowl in 2018").let { assertEquals(SportsWant.CHAMPION, it.want); assertEquals(listOf("eagles"), it.teams) }
        assertEquals("57", sports("who won super bowl 57").number)
        assertEquals("LVII", Sports.roman("57"))
    }

    @Test fun injuriesNewsScheduleRecord() {
        assertEquals(SportsWant.INJURIES, sports("eagles injury report").want)
        assertEquals(SportsWant.INJURIES, sports("whos injured on the lakers").want)
        assertEquals(SportsWant.INJURIES, sports("who's out for the knicks tonight").want)
        sports("conditions of the players in the knicks game").let { assertEquals(SportsWant.INJURIES, it.want); assertTrue(it.aboutGame) }
        sports("is joel embiid playing tonight").let { assertEquals(SportsWant.INJURIES, it.want); assertEquals("joel embiid", it.player) }
        assertEquals("joel embiid", sports("how is joel embiid's knee").player)
        assertEquals("patrick mahomes", sports("is patrick mahomes hurt").player)
        assertEquals("joel embiid", sports("joel embiid injury update").player)
        assertEquals(SportsWant.NEWS, sports("news on the knicks game").want)
        assertEquals(SportsWant.NEWS, sports("eagles news").want)
        assertEquals(SportsWant.NEWS, sports("whats going on with the lakers").want)
        assertEquals(SportsWant.NEXT, sports("when do the eagles play next").want)
        assertEquals(SportsWant.NEXT, sports("whens the next knicks game").want)
        assertEquals(SportsWant.NEXT, sports("who do the lakers play tonight").want)
        assertEquals(SportsWant.NEXT, sports("are the knicks playing tonight").want)
        assertEquals(SportsWant.RECORD, sports("whats the knicks record").want)
        assertEquals(SportsWant.RECORD, sports("how good are the eagles this year").want)
    }

    @Test fun everydayRequestsStayPut() {
        for (s in listOf("pay the bills", "is it going to storm tonight", "best switch games on sale", "play some music", "what's playing",
                "who won the oscar for best picture", "is spotify playing", "turn on the heat", "set a timer for 10 minutes", "what's the weather tonight")) {
            val r = IntentParser.parse(s)
            assertFalse("\"$s\" -> $r", r is Request.Sports || r is Request.History)
        }
    }

    @Test fun historyEvents() {
        history("what happened during world war 2").let { assertEquals(HistoryAspect.OVERVIEW, it.aspect); assertEquals("World War II", History.title(it.subject)) }
        assertEquals("American Civil War", History.title(history("what happened in the civil war").subject))
        assertEquals(HistoryAspect.OVERVIEW, history("tell me about the cold war").aspect)
        assertEquals(HistoryAspect.KEY_POINTS, history("key points of the french revolution").aspect)
        assertEquals(HistoryAspect.KEY_POINTS, history("important events of ww1").aspect)
        history("when did world war 2 start").let { assertEquals(HistoryAspect.WHEN, it.aspect); assertEquals("World War II", History.title(it.subject)) }
        assertEquals(HistoryAspect.WHEN, history("when did the vietnam war end").aspect)
        assertEquals(HistoryAspect.HOW_LONG, history("how long did the hundred years war last").aspect)
        assertEquals(HistoryAspect.WHO_WON, history("who won the war of 1812").aspect)
        assertEquals(HistoryAspect.WHO_FOUGHT, history("who fought in the korean war").aspect)
        assertEquals(HistoryAspect.CAUSE, history("why did world war 1 start").aspect)
        assertEquals(HistoryAspect.CAUSE, history("what caused the great depression").aspect)
        history("how many people died in ww2").let { assertEquals(HistoryAspect.DEATHS, it.aspect); assertEquals("World War II", History.title(it.subject)) }
        assertEquals(HistoryAspect.WHERE, history("where did the battle of gettysburg happen").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("explain the cuban missile crisis").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("what was the bay of pigs").aspect)
        assertEquals(HistoryAspect.SIGNIFICANCE, history("why was the battle of gettysburg important").aspect)
        assertEquals("September 11 attacks", History.title(history("what happened on 9/11").subject))
    }

    @Test fun onThisDay() {
        val now = LocalDate.now()
        history("today in history").let { assertEquals(HistoryAspect.ON_THIS_DAY, it.aspect); assertEquals(now.monthValue, it.month); assertEquals(now.dayOfMonth, it.day) }
        assertEquals(HistoryAspect.ON_THIS_DAY, history("what happened on this day in history").aspect)
        history("what happened on july 20 in history").let { assertEquals(7, it.month); assertEquals(20, it.day) }
    }

    @Test fun people() {
        lookup("when did abraham lincoln die").let { assertEquals(LookupAspect.DIED, it.aspect); assertEquals("abraham lincoln", it.subject) }
        assertEquals(LookupAspect.DIED, lookup("how did jfk die").aspect)
        assertEquals(LookupAspect.DIED, lookup("is queen elizabeth still alive").aspect)
        assertEquals(LookupAspect.DIED, lookup("when did elvis pass away").aspect)
        lookup("why is martin luther king jr important").let { assertEquals(LookupAspect.KNOWN_FOR, it.aspect); assertEquals("martin luther king jr", it.subject) }
        assertEquals(LookupAspect.KNOWN_FOR, lookup("what is albert einstein known for").aspect)
        assertEquals(LookupAspect.KNOWN_FOR, lookup("what did nikola tesla invent").aspect)
        assertEquals(LookupAspect.KNOWN_FOR, lookup("short bio of marie curie").aspect)
        assertEquals(LookupAspect.KNOWN_FOR, lookup("what was gandhi famous for").aspect)
        assertEquals(LookupAspect.BIRTHDAY, lookup("when was abraham lincoln born").aspect)
    }

    @Test fun buttonsShareRowsEvenly() {
        assertEquals(listOf(2), buttonRows(2))
        assertEquals(listOf(3), buttonRows(3))
        assertEquals(listOf(2, 2), buttonRows(4))
        assertEquals(listOf(3, 2), buttonRows(5))
        assertEquals(listOf(3, 2, 2), buttonRows(7))
    }

    @Test fun currentEventsArentSaved() {
        assertFalse(HistoryLink.worthSaving(Request.Sports(SportsAsk(SportsWant.SCORE, listOf("knicks")))))
        assertFalse(HistoryLink.worthSaving(Request.Sports(SportsAsk(SportsWant.INJURIES, player = "joel embiid"))))
        assertFalse(HistoryLink.worthSaving(Request.Sports(SportsAsk(SportsWant.NEXT, listOf("eagles")))))
        assertTrue(HistoryLink.worthSaving(Request.Sports(SportsAsk(SportsWant.CHAMPION, event = "Super Bowl", year = 1998))))
        assertTrue(HistoryLink.worthSaving(Request.Sports(SportsAsk(SportsWant.RESULT, listOf("eagles", "cowboys"), year = 2023))))
        assertTrue(HistoryLink.worthSaving(Request.History(HistoryAsk("ww2", HistoryAspect.OVERVIEW))))
    }

    @Test fun phoneAppsAndSettingsArentSaved() {
        for (s in listOf("turn on wifi", "open bluetooth settings", "turn up the brightness", "open spotify", "text mom i'm on my way",
                "call dad", "remind me to take out the trash at 8pm", "what's on my calendar today", "take a note buy milk",
                "directions to target", "set a timer for 10 minutes", "turn off the room light", "how many days until christmas")) {
            val r = IntentParser.parse(s)
            assertFalse("\"$s\" -> $r", HistoryLink.worthSaving(r))
        }
        for (s in listOf("who is albert einstein", "who won the super bowl in 1998", "what happened during world war 2")) {
            assertTrue(s, HistoryLink.worthSaving(IntentParser.parse(s)))
        }
    }

    @Test fun saidBriefly() {
        assertEquals("The underdog Eagles defeated the Patriots with a score of 41–33 to win their first Super Bowl and their first NFL title since 1960.",
            Sports.brief("The underdog Eagles defeated the Patriots with a score of 41–33 to win their first Super Bowl and their first NFL title since 1960, ending what was the third longest championship drought in the league at the time."))
        assertTrue(Sports.brief("word ".repeat(80), 100).length <= 101)
    }

    @Test fun sayingGames() {
        assertEquals("with 8:21 left in the 3rd", Sports.spokenStatus("8:21 - 3rd"))
        assertEquals("in the top of the 5th", Sports.spokenStatus("Top 5th"))
        assertEquals("at halftime", Sports.spokenStatus("Halftime"))
        assertEquals("about 73 million", History.bigNumber(73_000_000))
        val text = "Super Bowl XXXII was an American football game played between the Green Bay Packers and the Denver Broncos. The Broncos upset the heavily favored Packers by the score of 31–24."
        assertEquals("The Broncos upset the heavily favored Packers by the score of 31–24.", Sports.winnerSentence(text))
    }
}
