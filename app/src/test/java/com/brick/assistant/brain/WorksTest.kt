package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorksTest {
    private fun p(s: String) = IntentParser.parse(s)
    private fun ranked(s: String) = p(s) as? Request.Ranked ?: error("\"$s\" -> ${p(s)}")
    private fun ask(s: String) = p(s) as? Request.WorkAsk ?: error("\"$s\" -> ${p(s)}")
    private fun latest(s: String) = p(s) as? Request.LatestWork ?: error("\"$s\" -> ${p(s)}")

    @Test fun gameListFilters() {
        ranked("best zelda games on switch").let {
            assertEquals(RankKind.GAME, it.kind); assertEquals(Tier.GOOD, it.tier); assertEquals("zelda", it.filter)
            assertEquals(listOf("Q19610114"), it.game!!.platforms); assertEquals("Switch", it.game!!.device)
        }
        ranked("worst switch 2 games").let { assertEquals(listOf("Q122761124"), it.game!!.platforms); assertNull(it.filter); assertEquals(Tier.BAD, it.tier) }
        ranked("good gamecube games").let { assertEquals(listOf("Q182172"), it.game!!.platforms) }
        ranked("what are some mid wii u games").let { assertEquals(listOf("Q56942"), it.game!!.platforms); assertEquals(Tier.MEDIOCRE, it.tier) }
        ranked("best new 3ds games").let { assertEquals(listOf("Q17679679"), it.game!!.platforms) }
        ranked("top ds games").let { assertEquals(listOf("Q170323"), it.game!!.platforms) }
        ranked("best games on steam deck").let { assertEquals("P1733", it.game!!.store); assertEquals("Steam Deck", it.game!!.storeName) }
        ranked("good steam games from 2023").let { assertEquals("P1733", it.game!!.store); assertEquals(2023, it.game!!.yearFrom); assertEquals(2023, it.game!!.yearTo) }
        ranked("best gog games").let { assertEquals("P2725", it.game!!.store) }
        ranked("best epic games store games").let { assertEquals("P6278", it.game!!.store) }
        ranked("best playstation games").let { assertTrue(it.game!!.platforms.containsAll(listOf("Q63184502", "Q5014725"))) }
        ranked("worst xbox games").let { assertTrue("Q13361286" in it.game!!.platforms) }
        ranked("best games for my phone").let { assertEquals(listOf("Q94", "Q48493"), it.game!!.platforms) }
        ranked("good ipad games").let { assertTrue("Q64350339" in it.game!!.platforms) }
        ranked("best mac games").let { assertEquals(listOf("Q14116"), it.game!!.platforms) }
        ranked("best ps5 games of the year").let { assertEquals(listOf("Q63184502"), it.game!!.platforms) }
        ranked("best rpgs from the 90s").let { assertEquals(1990, it.game!!.yearFrom); assertEquals(1999, it.game!!.yearTo) }
        ranked("best fromsoftware games").let { assertEquals("fromsoftware", it.filter); assertNull(it.game) }
        ranked("best games made by nintendo").let { assertEquals("nintendo", it.filter); assertNull(it.game) }
        ranked("best nintendo games").let { assertTrue("Q19610114" in it.game!!.platforms); assertNull(it.filter) }
        ranked("rank the mario games on wii").let { assertEquals(Tier.ALL, it.tier); assertEquals("mario", it.filter); assertEquals(listOf("Q8079"), it.game!!.platforms) }
        ranked("best pokemon games released between 2010 and 2015").let { assertEquals("pokemon", it.filter); assertEquals(2010, it.game!!.yearFrom); assertEquals(2015, it.game!!.yearTo) }
        ranked("good indie games since 2020 on pc").let { assertEquals(2020, it.game!!.yearFrom); assertEquals(listOf("Q1406"), it.game!!.platforms) }
        assertEquals("on Switch from 2023", GameFilter(listOf("Q19610114"), "Switch", yearFrom = 2023, yearTo = 2023).describe())
    }

    @Test fun bookAndBoardLists() {
        ranked("best brandon sanderson books").let { assertEquals(RankKind.BOOK, it.kind); assertEquals("brandon sanderson", it.filter) }
        ranked("worst books by stephen king").let { assertEquals(Tier.BAD, it.tier); assertEquals("stephen king", it.filter) }
        ranked("rank the stormlight archive books").let { assertEquals(Tier.ALL, it.tier); assertEquals("stormlight archive", it.filter) }
        ranked("good fantasy book series").let { assertEquals(RankKind.BOOK, it.kind) }
        ranked("best board games").let { assertEquals(RankKind.BOARD_GAME, it.kind); assertNull(it.filter) }
        ranked("worst catan board games").let { assertEquals(RankKind.BOARD_GAME, it.kind); assertEquals("catan", it.filter) }
        // TV shows and movies about games stay shows and movies.
        assertEquals(RankKind.SHOW, ranked("best tv shows based on video games").kind)
        assertEquals(RankKind.MOVIE, ranked("best hunger games movies").kind)
    }

    @Test fun asks() {
        ask("where can i buy the zelda game").let { assertEquals(WorkKind.GAME, it.kind); assertEquals(WorkAskKind.BUY, it.ask); assertEquals("zelda", it.title) }
        ask("where can i buy elden ring for ps5").let { assertEquals("elden ring", it.title); assertEquals(listOf("Q63184502"), it.platformIds) }
        ask("where can i play hollow knight").let { assertEquals(WorkAskKind.PLAY, it.ask); assertEquals("hollow knight", it.title); assertTrue(it.guess) }
        ask("what can i play minecraft on").let { assertEquals(WorkAskKind.PLAY, it.ask); assertEquals("minecraft", it.title) }
        ask("is stardew valley on switch").let { assertEquals("stardew valley", it.title); assertEquals("Switch", it.platform) }
        ask("is hades on steam deck").let { assertEquals("hades", it.title); assertEquals("Steam Deck", it.platform) }
        ask("is the way of kings book any good").let { assertEquals(WorkKind.BOOK, it.kind); assertEquals(WorkAskKind.RATING, it.ask); assertEquals("way of kings", it.title) }
        ask("should i read project hail mary").let { assertEquals(WorkKind.BOOK, it.kind); assertEquals("project hail mary", it.title) }
        ask("is elden ring worth playing").let { assertEquals(WorkKind.GAME, it.kind) }
        ask("where can i read dune").let { assertEquals(WorkAskKind.READ, it.ask); assertEquals("dune", it.title) }
        ask("where can i get the hobbit audiobook").let { assertTrue(it.audio); assertEquals("hobbit", it.title) }
        ask("where can i buy a used copy of wingspan board game").let { assertEquals(WorkKind.BOARD_GAME, it.kind); assertTrue(it.used); assertEquals("wingspan", it.title) }
        ask("how much is the catan board game").let { assertEquals(WorkKind.BOARD_GAME, it.kind); assertEquals("catan", it.title) }
        ask("where can i get dune in paperback").let { assertEquals(BuyFormat.PHYSICAL, it.format) }
        ask("buy the kindle version of the martian").let { assertEquals(BuyFormat.DIGITAL, it.format); assertEquals("martian", it.title) }
        ask("what's elden ring's metacritic score").let { assertEquals(WorkAskKind.RATING, it.ask); assertEquals("elden ring", it.title) }
        ask("wingspan bgg rating").let { assertEquals(WorkKind.BOARD_GAME, it.kind) }
        ask("where can i buy the game elden ring").let { assertEquals(WorkKind.GAME, it.kind); assertEquals(WorkAskKind.BUY, it.ask); assertEquals("elden ring", it.title) }
        ask("where can i buy the game beat saber").let { assertEquals(WorkKind.GAME, it.kind); assertEquals("beat saber", it.title) }
        ask("where do i get the video game hades").let { assertEquals(WorkKind.GAME, it.kind); assertEquals("hades", it.title) }
        ask("is the game hollow knight any good").let { assertEquals(WorkKind.GAME, it.kind); assertEquals("hollow knight", it.title) }
        ask("where can i buy the game beat saber on disc").let { assertEquals("beat saber", it.title); assertEquals(BuyFormat.PHYSICAL, it.format) }
        ask("buy the book dune").let { assertEquals(WorkKind.BOOK, it.kind); assertEquals("dune", it.title) }
    }

    @Test fun notWorks() {
        for (s in listOf("how do I get to Costco", "where can i listen to blinding lights", "where can i stream breaking bad", "is game of thrones any good",
                "read me today's top stories", "where can i buy the hunger games movie", "is dune good", "play some games", "where can i buy tacos")) {
            val r = p(s)
            assertFalse("$s -> $r", r is Request.WorkAsk || r is Request.LatestWork || (r is Request.Ranked && r.kind in setOf(RankKind.GAME, RankKind.BOOK, RankKind.BOARD_GAME)))
        }
    }

    @Test fun latest() {
        latest("what's the newest zelda game").let { assertEquals("zelda", it.name); assertEquals(WorkKind.GAME, it.kind) }
        latest("when does the next stormlight archive book come out").let { assertEquals("stormlight archive", it.name); assertTrue(it.next) }
        latest("latest brandon sanderson novel").let { assertEquals("brandon sanderson", it.name); assertEquals(WorkKind.BOOK, it.kind) }
        latest("new catan board game").let { assertEquals(WorkKind.BOARD_GAME, it.kind) }
        // A device alone: the newest games on it, ranked.
        (p("what are the newest switch games") as Request.Ranked).let { assertEquals(RankKind.GAME, it.kind); assertTrue(it.newest) }
    }

    @Test fun years() {
        assertEquals(2023 to 2023, Works.years("from 2023", 2026)!!.first)
        assertEquals(2000 to 2009, Works.years("the 2000s", 2026)!!.first)
        assertEquals(1990 to 1993, Works.years("early 90s", 2026)!!.first)
        assertEquals(2016 to null, Works.years("after 2015", 2026)!!.first)
        assertEquals(null to 2004, Works.years("before 2005", 2026)!!.first)
        assertEquals(2026 to 2026, Works.years("this year", 2026)!!.first)
        assertEquals(2010 to 2019, Works.years("the 10s", 2026)!!.first)
        assertNull(Works.years("cyberpunk 2077", 2026))
    }

    @Test fun sales() {
        fun deals(s: String) = p(s) as? Request.Deals ?: error("\"$s\" -> ${p(s)}")
        deals("what games are on sale").let { assertNull(it.store); assertNull(it.under); assertFalse(it.good) }
        deals("any good steam deals under \$10").let { assertEquals("steam", it.store); assertEquals(10.0, it.under!!, 0.01); assertTrue(it.good) }
        deals("epic games store sales").let { assertEquals("epic", it.store) }
        deals("show me gog deals").let { assertEquals("gog", it.store) }
        deals("what's on sale on steam right now").let { assertEquals("steam", it.store) }
        deals("cheap games for 5 bucks or less").let { assertEquals(5.0, it.under!!, 0.01) }
        deals("best switch game deals").let { assertEquals("switch", it.console) }
        deals("are there any video game sales going on").let { assertNull(it.store) }
        // Movies and TV (CheapCharts).
        deals("movie deals").let { assertEquals(LookupKind.MOVIE, it.video); assertNull(it.store) }
        deals("any good horror movies on sale under \$5").let { assertEquals("Horror", it.genre); assertEquals(5.0, it.under!!, 0.01); assertTrue(it.good) }
        deals("tv show sales on apple tv").let { assertEquals(LookupKind.TV_SHOW, it.video); assertEquals("itunes", it.store) }
        deals("what movies are on sale on vudu").let { assertEquals("vudu", it.store) }
        deals("prime video movie deals").let { assertEquals("amazon", it.store) }
        deals("80s action movies on sale").let { assertEquals("ActionAdventure", it.genre); assertEquals(1980, it.years!!.first) }
        deals("cheap kids movies for 5 bucks or less on google play").let { assertEquals("KidsFamily", it.genre); assertEquals("googlePlay", it.store) }
        assertFalse(p("tv deals") is Request.Deals)
        assertFalse(p("black friday tv deals") is Request.Deals)
        (p("is hades on sale") as Request.WorkAsk).let { assertTrue(it.sale); assertEquals("hades", it.title); assertEquals(WorkAskKind.BUY, it.ask) }
        (p("is elden ring on sale on ps5") as Request.WorkAsk).let { assertEquals("elden ring", it.title); assertEquals(listOf("Q63184502"), it.platformIds) }
        (p("any deals on the game hollow knight") as Request.WorkAsk).let { assertEquals(WorkKind.GAME, it.kind); assertEquals("hollow knight", it.title) }
        (p("is the book dune discounted") as Request.WorkAsk).let { assertEquals(WorkKind.BOOK, it.kind); assertEquals("dune", it.title) }
        (p("cheapest price for stardew valley") as Request.WorkAsk).let { assertEquals("stardew valley", it.title) }
        (p("is the movie the batman on sale") as Request.MediaAsk).let { assertEquals(MediaAskKind.BUY_VIDEO, it.kind); assertEquals("batman", it.title!!.removePrefix("the ")) }
        (p("is the song blinding lights by the weeknd on sale") as Request.MediaAsk).let { assertEquals(MediaAskKind.BUY_SONG, it.kind); assertEquals("the weeknd", it.artist) }
        // Not sales.
        for (s in listOf("i have a deal for you", "what's the deal with airline food", "remind me about the sale tomorrow", "is it on sale"))
            assertFalse(s, p(s) is Request.Deals || (p(s) as? Request.WorkAsk)?.sale == true)
        // Money.
        val price = Price("Steam", 12.49, 24.99)
        assertTrue(price.onSale); assertEquals(50, price.off); assertEquals(12.50, price.saved, 0.001)
        assertEquals("\$12.49 (50% off, save \$12.50)", price.shown())
        assertFalse(Price("Steam", 24.99, 24.99).onSale)
        assertEquals("Free", Price("Steam", 0.0).shown())
        assertEquals("Hades is on sale at Steam: \$12.49, 50% off, so you save \$12.50, until Dec 1. The Nintendo eShop has it 20% off.",
            Prices.saleLine("Hades", listOf(Price("Steam", 12.49, 24.99, ends = "2026-12-01"), Price("Nintendo eShop", 19.99, 24.99), Price("Xbox Store", 24.99, 24.99))))
        assertEquals("Hades isn't on sale anywhere I can check. The cheapest is \$24.99 at Steam.", Prices.noSaleLine("Hades", listOf(Price("Steam", 24.99, 24.99))))
        // Prices flow onto the store list and lead the answer.
        val w = WorkInfo(WorkKind.GAME, "Q1", "Hades", 2020, null, null, null, platforms = listOf("Q1406", "Q19610114"), platformNames = listOf("PC", "Switch"), steamId = "1",
            prices = listOf(Price("Steam", 12.49, 24.99, "https://store.steampowered.com/app/1"), Price("Humble Store", 10.0, 24.99)))
        val shops = Works.gameShops(w, BuyFormat.DIGITAL, false)
        assertEquals("\$12.49 (50% off, save \$12.50)", shops.first { it.name == "Steam" }.price)
        assertTrue(shops.any { it.name == "Humble Store" && it.sale })
        assertTrue(Works.buyLine(w, shops).startsWith("Hades is on sale at the Humble Store: \$10.00, 60% off"))
    }

    @Test fun digitalOnly() {
        fun game(title: String, platforms: List<String>, names: List<String>, formats: List<String> = emptyList(), publishers: List<String> = emptyList(),
                 makers: List<String> = emptyList(), year: Int = 2020, price: String? = null) =
            WorkInfo(WorkKind.GAME, "Q1", title, year, null, null, null, platforms = platforms, platformNames = names, makers = makers,
                formats = formats, publishers = publishers, steamId = "1", steamPrice = price)
        val ps = listOf("Q1406", "Q5014725", "Q63184502")
        // Beat Saber: VR, self-published, downloads only.
        val beat = game("Beat Saber", ps + "Q999", listOf("PC", "PS4", "PS5", "Meta Horizon OS"), listOf("Q269415"), listOf("Beat Games"), listOf("Beat Games"), 2018)
        assertTrue(Works.digitalConfidence(beat) >= Works.DIGITAL_ONLY)
        Works.gameShops(beat, BuyFormat.ANY, false).let { shops ->
            assertTrue(shops.none { it.section == "ON DISC" })
            assertTrue(Works.buyLine(beat, shops).contains("so I left out disc stores."))
        }
        // Asking for a disc or a used copy still lists them.
        assertTrue(Works.gameShops(beat, BuyFormat.PHYSICAL, false).any { it.section == "ON DISC" })
        assertTrue(Works.gameShops(beat, BuyFormat.ANY, true).any { it.section == "USED" })
        // Hollow Knight and Hades: Wikidata lists only downloads, but they got boxed releases. Not sure enough.
        val hk = game("Hollow Knight", ps + "Q19610114", listOf("PC", "PS4", "PS5", "Switch"), listOf("Q269415", "Q54820071"), listOf("Team Cherry"), listOf("Team Cherry"), 2017)
        assertTrue(Works.digitalConfidence(hk) < Works.DIGITAL_ONLY)
        assertTrue(Works.gameShops(hk, BuyFormat.ANY, false).any { it.section == "ON DISC" })
        // A disc or cartridge on record, a boxed-release publisher, or an old game: discs.
        assertTrue(Works.digitalConfidence(game("Stardew Valley", ps, listOf("PC", "PS4"), listOf("Q269415", "Q234870"))) < 0.1)
        assertTrue(Works.digitalConfidence(game("Undertale", ps, listOf("PC", "PS4"), listOf("Q269415"), listOf("8-4", "Fangamer"), listOf("Toby Fox"))) < 0.2)
        assertTrue(Works.digitalConfidence(game("Old", ps, listOf("PC", "PS4"), listOf("Q269415"), year = 2006)) < Works.DIGITAL_ONLY)
        // Free to play, or PC and phones only: downloads.
        assertTrue(Works.digitalConfidence(game("Free", ps, listOf("PC", "PS4"), price = "Free")) >= Works.DIGITAL_ONLY)
        assertTrue(Works.digitalConfidence(game("Indie", listOf("Q1406", "Q94"), listOf("PC", "Android"))) >= Works.DIGITAL_ONLY)
    }

    @Test fun scoresAndLines() {
        val facts = listOf(Ranked.Fact("Q21039459", "x", "96/100"), Ranked.Fact("Q21039459", "y", "96%"), Ranked.Fact("Q150248", null, "94/100"))
        val (s, shown) = Works.gameScore(facts)!!
        assertEquals(95.0, s, 0.01)
        assertEquals("OC 96 · MC 94", shown)
        assertEquals(Tier.GOOD, Works.tierOf(WorkKind.GAME, 80.0))
        assertEquals(Tier.MEDIOCRE, Works.tierOf(WorkKind.BOOK, 74.0))
        assertEquals(Tier.BAD, Works.tierOf(WorkKind.BOARD_GAME, 55.0))
        val today = java.time.LocalDate.of(2026, 11, 12)
        val set = WorkSet("The Legend of Zelda", "Q12393", true, listOf(
            Work("Breath of the Wild", java.time.LocalDate.of(2017, 3, 3)), Work("Tears of the Kingdom", java.time.LocalDate.of(2023, 5, 12)),
            Work("Next Zelda", java.time.LocalDate.of(2027, 1, 1)),
        ))
        assertEquals("The newest The Legend of Zelda game is Tears of the Kingdom, from May 2023. The next one, Next Zelda, is due in 2027.",
            Works.latestLine(set, WorkKind.GAME, false, today))
        val w = WorkInfo(WorkKind.GAME, "Q1", "Elden Ring", 2022, null, null, null, platforms = listOf("Q1406", "Q63184502"), platformNames = listOf("PC", "PS5"), steamId = "1245620", steamPrice = "$59.99")
        val shops = Works.gameShops(w, BuyFormat.ANY, false)
        assertEquals(listOf("Steam", "PlayStation Store", "Amazon", "GameStop", "Best Buy", "Walmart", "Target"), shops.map { it.name })
        assertEquals(listOf("Steam"), Works.gameShops(w, BuyFormat.ANY, false, listOf("Q1406")).map { it.name })
        assertEquals("Yes, Elden Ring is on PS5.", Works.playLine(w, "PS5", listOf("Q63184502")))
        assertTrue(Works.playLine(w, "Switch", listOf("Q19610114")).startsWith("No, I don't see Elden Ring on Switch."))
        val book = WorkInfo(WorkKind.BOOK, null, "Dune", 1965, null, null, null, makers = listOf("Frank Herbert"), olKey = "/works/OL1W", ebook = "borrowable")
        assertEquals("FREE", Works.bookShops(book, BuyFormat.ANY, false, false, read = true).first().section)
        assertTrue(Works.bookShops(book, BuyFormat.ANY, true, false, false).all { it.section in setOf("AUDIOBOOK", "FREE") })
    }
}
