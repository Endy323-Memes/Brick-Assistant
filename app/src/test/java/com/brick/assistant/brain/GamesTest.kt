package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamesTest {
    private fun info(s: String): GameAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.GameInfo)
        return (r as Request.GameInfo).ask
    }

    private fun find(s: String): GameSearch {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.GameFind)
        return (r as Request.GameFind).search
    }

    @Test fun questionsAboutOneGame() {
        for ((s, title, want) in listOf(
            Triple("is half life alyx vr only", "half life alyx", GameWant.VR),
            Triple("can i play no man's sky in vr", "no man's sky", GameWant.VR),
            Triple("does subnautica support vr", "subnautica", GameWant.VR),
            Triple("is hades on mac", "hades", GameWant.OS),
            Triple("can i run elden ring on linux", "elden ring", GameWant.OS),
            Triple("is balatro steam deck verified", "balatro", GameWant.OS),
            Triple("what operating systems does stardew valley run on", "stardew valley", GameWant.OS),
            Triple("does hollow knight have controller support", "hollow knight", GameWant.INPUT),
            Triple("can i play cyberpunk 2077 with a controller", "cyberpunk 2077", GameWant.INPUT),
            Triple("what languages is hades in", "hades", GameWant.LANGUAGES),
            Triple("is elden ring in spanish", "elden ring", GameWant.LANGUAGES),
            Triple("does baldur's gate 3 have japanese audio", "baldur's gate 3", GameWant.LANGUAGES),
            Triple("what's the art style of cuphead", "cuphead", GameWant.STYLE),
            Triple("is terraria pixel art", "terraria", GameWant.STYLE),
            Triple("what kind of game is hades", "hades", GameWant.GENRE),
            Triple("what genre is rimworld", "rimworld", GameWant.GENRE),
            Triple("is hades a roguelike", "hades", GameWant.TAGS),
            Triple("is it takes two co-op", "it takes two", GameWant.TAGS),
            Triple("what tags does balatro have", "balatro", GameWant.TAGS),
            Triple("who developed hollow knight", "hollow knight", GameWant.DEVELOPER),
            Triple("what studio made celeste", "celeste", GameWant.DEVELOPER),
            Triple("who published hades", "hades", GameWant.PUBLISHER),
            Triple("palworld steam reviews", "palworld", GameWant.REVIEWS),
            Triple("compare prices for hades", "hades", GameWant.PRICE),
            Triple("where is elden ring cheapest", "elden ring", GameWant.PRICE),
            Triple("tell me about the game hades", "hades", GameWant.OVERVIEW),
            Triple("hey brick can you tell me if hades is on linux", "hades", GameWant.OS),
        )) {
            val a = info(s)
            assertEquals(s, title, a.title)
            assertEquals(s, want, a.want)
        }
        assertEquals("spanish", info("is elden ring in spanish").detail)
        assertEquals("mac", info("is hades on mac").detail)
    }

    @Test fun findingGames() {
        val cozy = find("find me cozy pixel art games on mac under \$20")
        assertEquals(listOf("Pixel Graphics", "Cozy"), cozy.tags.map { it.first }.sorted().reversed())
        assertEquals("mac", cozy.os)
        assertEquals(20, cozy.maxPrice)
        assertEquals(401, find("vr only horror games").vr)
        assertEquals(402, find("best vr games").vr)
        assertTrue(find("roguelike games with controller support").controller)
        assertEquals("spanish", find("rpg games in spanish").lang?.second)
        assertTrue(find("free co-op games").free)
        assertTrue(find("steam deck verified roguelikes games").deck)
        assertTrue(find("new metroidvania games").newest)
        assertTrue(Games.searchUrl(cozy, results = false).contains("tags=3964"))
        assertEquals("Cozy pixel art games on Mac under $20", cozy.describe())
    }

    @Test fun leavesOtherThingsAlone() {
        for (s in listOf("is hades on switch", "how much is hades", "best switch games", "knicks game tonight", "play some games",
                "is it vr", "who published harry potter and the sorcerer's stone the book", "what's the weather", "is the office on netflix"))
            assertFalse(s, IntentParser.parse(s).let { it is Request.GameInfo || it is Request.GameFind })
    }

    @Test fun readsSteamData() {
        val langs = Games.languages("English<strong>*</strong>, French, Spanish - Spain<strong>*</strong><br><strong>*</strong>languages with full audio support")
        assertEquals(listOf("English", "French", "Spanish - Spain"), langs.map { it.name })
        assertEquals(listOf(true, false, true), langs.map { it.audio })
        val html = "<a href=\"https://store.steampowered.com/app/413150/\" data-ds-appid=\"413150\" class=\"search_result_row\"><span class=\"title\">Stardew Valley</span>" +
            "<div class=\"col search_released responsive_secondrow\">\n   Feb 26, 2016   </div><span class=\"search_review_summary positive\" data-tooltip-html=\"Overwhelmingly Positive&lt;br&gt;98% of the 397,364 user reviews\"></span>" +
            "<div class=\"discount_final_price\">$14.99</div></a>"
        val h = Games.parseResults(html).single()
        assertEquals("Stardew Valley", h.name)
        assertEquals(98, h.percent)
        assertEquals(397364, h.reviews)
        assertEquals("$14.99", h.price)
        assertEquals("Feb 26, 2016", h.released)
        assertEquals("Overwhelmingly Positive", Games.reviewWord(98, 397364))
        assertEquals("Mixed", Games.reviewWord(61, 2000))
    }

    private fun sample(vrOnly: Boolean = false) = Games.Info(
        "1145360", "Hades", null, "https://store.steampowered.com/app/1145360", "Defy the god of the dead.", listOf("Supergiant Games"), listOf("Supergiant Games"),
        "Sep 17, 2020", listOf("Action", "Indie", "RPG"), listOf("Single-player", "Full controller support") + (if (vrOnly) listOf("VR Only") else emptyList()),
        true, true, false, listOf(Games.Lang("English", true), Games.Lang("French", false), Games.Lang("Spanish - Spain", false)),
        Price("Steam", 24.99, 24.99, "https://store.steampowered.com/app/1145360"), 93,
        listOf("Roguelike", "Action", "Hack and Slash", "Great Soundtrack", "Isometric", "Hand-drawn"), 270000, 5000, 3,
        listOf(Price("GOG", 12.49, 24.99, "https://gog.com")), "full", false,
    )

    @Test fun answersAndCard() {
        val g = sample()
        fun say(want: GameWant, detail: String? = null) = Games.answer(g, GameAsk("hades", want, "x", detail))
        assertEquals("Hades doesn't support VR.", say(GameWant.VR))
        assertEquals("Hades is VR only; you need a headset to play it.", Games.answer(sample(vrOnly = true), GameAsk("hades", GameWant.VR, "x")))
        assertEquals("Yes, Hades runs on Mac.", say(GameWant.OS, "mac"))
        assertTrue(say(GameWant.OS, "linux"), say(GameWant.OS, "linux").contains("Steam Deck Verified"))
        assertEquals("Yes, Hades has full controller support.", say(GameWant.INPUT, "controller"))
        assertEquals("Yes, Hades is in Spanish: menus and subtitles, not voices.", say(GameWant.LANGUAGES, "spanish"))
        assertEquals("No, Hades isn't in Japanese.", say(GameWant.LANGUAGES, "japanese"))
        assertEquals("Yes, Hades is a roguelike game.", say(GameWant.TAGS, "roguelike"))
        assertTrue(say(GameWant.STYLE), say(GameWant.STYLE).contains("isometric"))
        assertEquals("Hades is cheapest at GOG: \$12.49 (50% off, save \$12.50), versus \$24.99 on Steam.", say(GameWant.PRICE))
        assertTrue(say(GameWant.REVIEWS), say(GameWant.REVIEWS).startsWith("Hades is Overwhelmingly Positive on Steam: 98%"))
        val text = Infographics.gameText(g, say(GameWant.OVERVIEW), g.about)
        for (line in text.lines()) assertEquals(line, Box.W + 4, line.length)
        for (head in listOf("PRICE", "REVIEWS", "TAGS", "ART STYLE", "VR", "INPUT", "OS", "DECK", "LANGUAGES", "DEV", "PUB")) assertTrue(head, text.contains(head))
    }
}
