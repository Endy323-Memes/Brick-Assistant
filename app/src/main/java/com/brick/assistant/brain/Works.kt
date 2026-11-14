package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Video games, board games and books: what the media guide does for movies, shows and songs. */
enum class WorkKind(val one: String, val many: String, val maker: String) {
    GAME("game", "games", "studio"), BOARD_GAME("board game", "board games", "designer"), BOOK("book", "books", "author"),
}

/** What was asked about a game or book. */
enum class WorkAskKind { RATING, BUY, PLAY, READ }

/**
 * A video game list's filters, any mix: devices ("Switch 2", "phone"), a store ("Steam"), and years ("from 2023",
 * "90s"). A franchise or studio is the list's free-text filter.
 */
data class GameFilter(
    /** Wikidata items of the platforms ("Q19610114" Nintendo Switch); any of them counts. */
    val platforms: List<String> = emptyList(),
    /** How the device or brand reads in an answer ("Switch", "PlayStation", "phone"). */
    val device: String? = null,
    /** Wikidata property of the store's ID ("P1733" Steam), when a store was named. */
    val store: String? = null,
    val storeName: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
) {
    val isEmpty get() = platforms.isEmpty() && store == null && yearFrom == null && yearTo == null

    /** "on Switch from 2023", "on Steam from the 1990s". */
    fun describe(): String = listOfNotNull(
        (device ?: storeName)?.let { "on $it" },
        when {
            yearFrom != null && yearFrom == yearTo -> "from $yearFrom"
            yearFrom != null && yearTo != null && yearFrom % 10 == 0 && yearTo == yearFrom + 9 -> "from the ${yearFrom}s"
            yearFrom != null && yearTo != null -> "from $yearFrom to $yearTo"
            yearFrom != null -> "since $yearFrom"
            yearTo != null -> "before ${yearTo + 1}"
            else -> null
        },
    ).joinToString(" ")
}

/** One game or book in a series or by a maker, with its release date. */
data class Work(val title: String, val date: LocalDate?, val qid: String? = null, val ordinal: Double? = null, val bgg: String? = null, val author: String? = null)

/** A series ("The Legend of Zelda") or a maker ("Brandon Sanderson") and its games or books, oldest first. */
data class WorkSet(val name: String, val qid: String, val bySeries: Boolean, val works: List<Work>) {
    fun released(today: LocalDate) = works.filter { it.date != null && !it.date.isAfter(today) }
    fun upcoming(today: LocalDate) = works.filter { it.date != null && it.date.isAfter(today) }
}

/** BoardGameGeek's numbers for one game. */
data class BggStats(val id: String, val name: String, val year: Int?, val average: Double?, val bayes: Double?, val rank: Int?, val voters: Int?,
    val minPlayers: Int?, val maxPlayers: Int?, val minutes: Int?, val weight: Double?, val image: String?)

/** Everything Brick found about one game or book. */
data class WorkInfo(
    val kind: WorkKind,
    val qid: String?,
    val title: String,
    val year: Int?,
    val description: String?,
    val image: String?,
    val wiki: String?,
    /** Wikidata platform items (games). */
    val platforms: List<String> = emptyList(),
    val platformNames: List<String> = emptyList(),
    /** Developers, authors or designers. */
    val makers: List<String> = emptyList(),
    /** Review scores: (who, value, how) - "OpenCritic", "96/100", "top critic average". */
    val scores: List<Triple<String, String, String?>> = emptyList(),
    /** The 0..100 score used to call it good, so-so or bad, and how it reads ("OC 96 · MC 94"). */
    val score: Double? = null,
    val scoreShown: String? = null,
    val steamId: String? = null,
    val steamPrice: String? = null,
    val epicId: String? = null,
    val gogId: String? = null,
    val bggId: String? = null,
    val bgg: BggStats? = null,
    val olKey: String? = null,
    val olRating: Double? = null,
    val olCount: Int = 0,
    /** Open Library's lending ("borrowable", "public"). */
    val ebook: String? = null,
    /** Games: Wikidata distribution formats (P437) and publisher names. */
    val formats: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    /** The Nintendo eShop page id (Wikidata P8084, "hades-switch"). */
    val eshop: String? = null,
    /** Live prices from the stores Brick can check (filled in just before showing where to buy). */
    val prices: List<Price> = emptyList(),
)

/** A place to buy, play or borrow it. [section] groups them on the card ("DIGITAL", "ON DISC", "FREE"). */
data class Shop(val section: String, val name: String, val url: String, val note: String? = null, val price: String? = null, val sale: Boolean = false)

object Works {
    // ================================================================== understanding requests

    /** Titles with "game" or "book" in them that aren't asking about games or books. */
    private val TITLE_GUARD = Patterns.get("\\b(?:game of thrones|squid game|(?:the )?hunger games|the imitation game|ender'?s game|end ?game|molly'?s game|the game(?=$| movie| film| with michael douglas)|game night|gamers?|game changer|games people play|book of (?:eli|boba fett|mormon|henry)|the jungle book|the book thief|the notebook|the good book|facebook|macbook|chromebook|book club|booking|booked|yearbook|playbook|textbook)\\b")
    private val BOARD_WORD = Patterns.get("\\b(?:board ?games?|tabletop(?: games?)?|card games?|deck ?building games?|dice games?|party games?|euro ?games?|boardgames?|war ?games?|cooperative games?|co-?op board games?|bgg|boardgamegeek)\\b")
    private val GAME_WORD = Patterns.get("\\b(?:video ?games?|videogames?|games?|gaming|titles for|rpgs?|jrpgs?|shooters?|fps|platformers?|metroidvanias?|roguelikes?|roguelites?|soulslikes?|souls-?likes?|indie games?|steam|opencritic|open critic|metacritic score)\\b")
    private val BOOK_WORD = Patterns.get("\\b(?:books?|novels?|novellas?|audiobooks?|audio books?|e-?books?|kindle|paperbacks?|hardcovers?|hardbacks?|reads|series of books|book series|trilogy|memoirs?|biograph(?:y|ies)|autobiograph(?:y|ies)|non-?fiction|fiction|manga|comics?|graphic novels?|goodreads|storygraph)\\b")

    /** Which kind a phrase names: board games before video games ("board game" says "game" too). */
    fun kindOf(t: String): WorkKind? {
        val s = t.replace(TITLE_GUARD, " ")
        return when {
            BOARD_WORD.containsMatchIn(s) -> WorkKind.BOARD_GAME
            BOOK_WORD.containsMatchIn(s) && !Patterns.get("\\bread (?:the )?(?:news|headlines|messages?|texts?|emails?|notifications?)\\b").containsMatchIn(s) -> WorkKind.BOOK
            GAME_WORD.containsMatchIn(s) -> WorkKind.GAME
            else -> null
        }
    }

    // ------------------------------------------------------------------ devices, stores, years

    private class Device(words: String, val label: String, val qids: List<String>, val store: String? = null, val storeName: String? = null) {
        val rx = Regex("\\b(?:$words)\\b")
    }

    private val NINTENDO = listOf("Q172742", "Q183259", "Q184839", "Q182172", "Q8079", "Q56942", "Q19610114", "Q122761124", "Q186437", "Q203992", "Q188642", "Q170323", "Q203597", "Q17679679")
    private val PLAYSTATION = listOf("Q10677", "Q10680", "Q10683", "Q5014725", "Q63184502", "Q170325", "Q188808")
    private val XBOX = listOf("Q132020", "Q48263", "Q13361286", "Q64513817", "Q98973368")
    private const val WINDOWS = "Q1406"
    private const val MAC = "Q14116"
    private const val LINUX = "Q388"
    private const val ANDROID = "Q94"
    private const val IOS = "Q48493"
    private const val IPADOS = "Q64350339"

    /** Most specific first: "switch 2" before "switch", "new 3ds" before "3ds", "xbox one" before "xbox". */
    private val DEVICES = listOf(
        Device("(?:nintendo )?switch (?:2|two|ii)|ns ?2|nintendo switch 2|the new switch", "Switch 2", listOf("Q122761124")),
        Device("(?:nintendo )?switch(?: 1| one| oled| lite| v1)?|og switch|original switch", "Switch", listOf("Q19610114")),
        Device("(?:nintendo )?game ?cube|gamecube|gcn|ngc", "GameCube", listOf("Q182172")),
        Device("wii ?u", "Wii U", listOf("Q56942")),
        Device("wii", "Wii", listOf("Q8079")),
        Device("new (?:nintendo )?3ds(?: xl| ll)?|new three ds", "New 3DS", listOf("Q17679679")),
        Device("(?:nintendo )?3ds(?: xl| ll)?|2ds|three ds|three d s", "3DS", listOf("Q203597", "Q17679679")),
        Device("(?:nintendo )?dsi?|nintendo ds lite|ds lite", "DS", listOf("Q170323")),
        Device("n64|nintendo 64|nintendo sixty four", "Nintendo 64", listOf("Q184839")),
        Device("snes|super nintendo|super nes|super famicom", "SNES", listOf("Q183259")),
        Device("nes|nintendo entertainment system|famicom|original nintendo", "NES", listOf("Q172742")),
        Device("game ?boy advance|gba", "Game Boy Advance", listOf("Q188642")),
        Device("game ?boy colou?r|gbc", "Game Boy Color", listOf("Q203992")),
        Device("game ?boy|gameboy", "Game Boy", listOf("Q186437", "Q203992")),
        Device("ps ?5|playstation ?5|play station 5|ps five|playstation five", "PS5", listOf("Q63184502")),
        Device("ps ?4|playstation ?4|play station 4|ps four|playstation four", "PS4", listOf("Q5014725")),
        Device("ps ?3|playstation ?3|play station 3|ps three|playstation three", "PS3", listOf("Q10683")),
        Device("ps ?2|playstation ?2|play station 2|ps two|playstation two", "PS2", listOf("Q10680")),
        Device("ps ?1|psx|ps one|playstation ?1|playstation one|original playstation|psone", "PS1", listOf("Q10677")),
        Device("psp|playstation portable", "PSP", listOf("Q170325")),
        Device("(?:ps |playstation )?vita", "PS Vita", listOf("Q188808")),
        Device("xbox series (?:x|s|x and s|x or s|x s)|series [xs]|xsx|xss", "Xbox Series X/S", listOf("Q64513817", "Q98973368")),
        Device("xbox one|xb1|xbone", "Xbox One", listOf("Q13361286")),
        Device("xbox 360|360", "Xbox 360", listOf("Q48263")),
        Device("original xbox|og xbox|xbox classic|first xbox", "the original Xbox", listOf("Q132020")),
        Device("dreamcast", "Dreamcast", listOf("Q184198")),
        Device("steam deck|steamdeck|deck verified|steam ?os", "Steam Deck", emptyList(), "P1733", "Steam"),
        Device("steam machine|steam box", "Steam Machine", emptyList(), "P1733", "Steam"),
        Device("steam frame", "Steam Frame", emptyList(), "P1733", "Steam"),
        Device("steam", "Steam", emptyList(), "P1733", "Steam"),
        Device("gog|gog\\.com|gog dot com|good old games", "GOG", emptyList(), "P2725", "GOG"),
        Device("epic(?: games)?(?: store)?|egs", "Epic Games Store", emptyList(), "P6278", "Epic Games Store"),
        Device("playstation store|ps store|psn|playstation network", "PlayStation", PLAYSTATION),
        Device("(?:nintendo )?e ?shop", "Nintendo eShop", listOf("Q19610114", "Q122761124", "Q56942", "Q203597")),
        Device("xbox store|microsoft store|game ?pass|xbox game pass", "Xbox", XBOX),
        Device("playstations?|ps|sony consoles?|sony", "PlayStation", PLAYSTATION),
        Device("xbox(?:es)?|microsoft consoles?", "Xbox", XBOX),
        Device("nintendo(?: consoles?| systems?| handhelds?)?", "Nintendo consoles", NINTENDO),
        Device("macs?|macbooks?|macos|mac os|os x|imacs?", "Mac", listOf(MAC)),
        Device("linux", "Linux", listOf(LINUX)),
        Device("pcs?|windows|computers?|laptops?|desktops?|gaming pcs?", "PC", listOf(WINDOWS)),
        Device("ipads?|ipados", "iPad", listOf(IPADOS, IOS)),
        Device("iphones?|ios|apple phones?|apple arcade", "iPhone", listOf(IOS)),
        Device("android(?: phones?| tablets?)?|google play", "Android", listOf(ANDROID)),
        Device("tablets?", "tablet", listOf(ANDROID, IPADOS, IOS)),
        Device("phones?|mobile|smartphones?|cell ?phones?|my phone", "phone", listOf(ANDROID, IOS)),
    )

    /** The codes Wikipedia's tables use for each platform ("NS", "PS5", "WIN"). */
    private val CODES = mapOf(
        WINDOWS to listOf("WIN", "PC"), MAC to listOf("MAC", "OSX", "macOS"), LINUX to listOf("LIN", "Linux"), ANDROID to listOf("AND", "Android", "DROID"),
        IOS to listOf("iOS"), IPADOS to listOf("iOS", "iPadOS"), "Q19610114" to listOf("NS"), "Q122761124" to listOf("NS2"), "Q63184502" to listOf("PS5"),
        "Q5014725" to listOf("PS4"), "Q10683" to listOf("PS3"), "Q13361286" to listOf("XBO", "XB1"), "Q64513817" to listOf("XSX/S", "XSX", "XS"),
        "Q98973368" to listOf("XSX/S", "XSX", "XS"), "Q48263" to listOf("X360"), "Q56942" to listOf("WiiU"), "Q203597" to listOf("3DS"),
    )

    /** Short names for answers ("PS5", "Switch", "PC"). */
    private val SHORT = mapOf(
        WINDOWS to "PC", MAC to "Mac", LINUX to "Linux", ANDROID to "Android", IOS to "iPhone", IPADOS to "iPad",
        "Q19610114" to "Switch", "Q122761124" to "Switch 2", "Q182172" to "GameCube", "Q8079" to "Wii", "Q56942" to "Wii U", "Q170323" to "DS",
        "Q203597" to "3DS", "Q17679679" to "New 3DS", "Q184839" to "Nintendo 64", "Q183259" to "SNES", "Q172742" to "NES", "Q188642" to "Game Boy Advance",
        "Q203992" to "Game Boy Color", "Q186437" to "Game Boy", "Q63184502" to "PS5", "Q5014725" to "PS4", "Q10683" to "PS3", "Q10680" to "PS2",
        "Q10677" to "PS1", "Q170325" to "PSP", "Q188808" to "PS Vita", "Q64513817" to "Xbox Series X/S", "Q98973368" to "Xbox Series X/S",
        "Q13361286" to "Xbox One", "Q48263" to "Xbox 360", "Q132020" to "Xbox", "Q184198" to "Dreamcast",
    )

    fun platformName(qid: String, label: String?): String = SHORT[qid] ?: label?.removePrefix("Microsoft ")?.ifBlank { null } ?: qid

    /** Years said loosely: "2023", "from the 90s", "early 2000s", "since 2015", "between 2010 and 2015", "this year". */
    fun years(t: String, now: Int = LocalDate.now().year): Pair<Pair<Int?, Int?>, IntRange?>? {
        fun y(s: String): Int? = s.toIntOrNull()?.takeIf { it in 1950..now + 3 }
        Patterns.get("\\b(?:between|from) ((?:19|20)\\d\\d) (?:and|to|through|thru|till|until|-) ((?:19|20)\\d\\d)\\b").find(t)?.let { m ->
            val a = y(m.groupValues[1]); val b = y(m.groupValues[2]); if (a != null && b != null) return (minOf(a, b) to maxOf(a, b)) to m.range
        }
        Patterns.get("\\b((?:19|20)\\d\\d) ?- ?((?:19|20)\\d\\d)\\b").find(t)?.let { m ->
            val a = y(m.groupValues[1]); val b = y(m.groupValues[2]); if (a != null && b != null) return (minOf(a, b) to maxOf(a, b)) to m.range
        }
        Patterns.get("\\b(?:since|after|newer than|post) ((?:19|20)\\d\\d)\\b").find(t)?.let { m -> y(m.groupValues[1])?.let { return ((if (m.value.startsWith("after") || m.value.startsWith("newer") || m.value.startsWith("post")) it + 1 else it) to null) to m.range } }
        Patterns.get("\\b(?:before|older than|pre) ((?:19|20)\\d\\d)\\b").find(t)?.let { m -> y(m.groupValues[1])?.let { return (null to it - 1) to m.range } }
        Patterns.get("\\b(?:(?:from|in|of) )?(?:the )?(early|late|mid|mid-)? ?((?:19|20)?\\d)0'?s\\b").find(t)?.let { m ->
            val d = m.groupValues[2]
            val start = when {
                d.length == 3 -> d.toInt() * 10
                d.toInt() <= (now % 100) / 10 -> 2000 + d.toInt() * 10
                else -> 1900 + d.toInt() * 10
            }
            if (start in 1950..now) {
                return when (m.groupValues[1]) {
                    "early" -> start to start + 3
                    "late" -> start + 6 to start + 9
                    "mid", "mid-" -> start + 3 to start + 6
                    else -> start to start + 9
                } to m.range
            }
        }
        Patterns.get("\\b(?:(?:from|in|of|released in|came out in|that came out in) )?this year\\b").find(t)?.let { return (now to now) to it.range }
        Patterns.get("\\b(?:(?:from|in|of|released in|came out in|that came out in) )?last year\\b").find(t)?.let { return (now - 1 to now - 1) to it.range }
        Patterns.get("\\b(?:(?:from|in|of|released in|came out in|that came out in) )?((?:19|20)\\d\\d)\\b").find(t)?.let { m -> y(m.groupValues[1])?.let { return (it to it) to m.range } }
        Patterns.get("\\b(?:new|newer|recent|newest|latest|modern|current|current gen|current-gen|this gen)\\b").find(t)?.let { return (now - 1 to now) to it.range }
        Patterns.get("\\b(?:retro|old school|old-school|classic era|older|old)\\b").find(t)?.let { return (null to 2005) to it.range }
        return null
    }

    /**
     * The devices, store and years in a request about games, and what's left of it ("best switch games from 2023"
     * -> Switch, 2023, "best games").
     */
    fun gameFilter(t: String): Pair<GameFilter, String> {
        var rest = " $t "
        var f = GameFilter()
        // "Made by Nintendo" is a studio, not Nintendo's consoles: leave it for the free-text filter.
        val byMaker = Patterns.get("\\b(?:by|from|made by|developed by|published by|studio) (?:nintendo|sony|microsoft|valve|epic(?: games)?)\\b")
        // Matches are looked for in [guard] (the studio phrases hidden) and blanked out of both.
        var guard = rest.replace(byMaker) { " ".repeat(it.value.length) }
        val names = ArrayList<String>()
        for (d in DEVICES) {
            val m = d.rx.find(guard) ?: continue
            // Bare "ps", "ds", "360" and "mobile" only count next to a games word or after "on/for".
            if (m.value in setOf("ps", "ds", "360", "mobile", "sony", "deck verified") &&
                !Patterns.get("\\b(?:on|for|on the|for the)\\s*$").containsMatchIn(rest.substring(0, m.range.first)) &&
                !Patterns.get("^\\s*(?:games?|titles?|exclusives?)\\b").containsMatchIn(rest.substring(m.range.last + 1))) continue
            if (d.store != null && f.store == null) f = f.copy(store = d.store, storeName = d.storeName)
            if (d.qids.isNotEmpty()) f = f.copy(platforms = (f.platforms + d.qids).distinct())
            names += d.label
            rest = rest.replaceRange(m.range, " ".repeat(m.value.length))
            guard = guard.replaceRange(m.range, " ".repeat(m.value.length))
        }
        if (names.isNotEmpty()) f = f.copy(device = names.distinct().joinToString(" or "))
        // "On Steam Deck": the store is Steam, the device reads as Steam Deck.
        if (f.platforms.isEmpty() && f.store != null) f = f.copy(storeName = names.firstOrNull() ?: f.storeName, device = null)
        years(rest)?.let { (range, span) ->
            f = f.copy(yearFrom = range.first, yearTo = range.second)
            if (span != null) rest = rest.replaceRange(span, " ".repeat(span.last - span.first + 1))
        }
        return f to rest.replace(Patterns.get("\\b(?:on|for|on the|for the|released|came out|available|playable|exclusives?)\\b(?=\\s*$)"), " ")
            .replace(Patterns.get("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------------ "where can I buy zelda", "is elden ring good"

    private val VERB_PLAY = Patterns.get("^(?:where|how) (?:can|do|could|should|would|might) (?:i|we|you|one) play (.+)$")
    private val PLATFORMS_Q = Patterns.get("^(?:what|which) (?:platforms?|consoles?|systems?|devices?|machines?) (?:is|are|can (?:i|you) play|does|do) (.+?)(?: on| for| available on| out on| come out on| playable on| run on)?$|^what (?:can|do) (?:i|you) play (.+?) on$|^(?:what|which) (?:is|are) (.+?) (?:on|available on|out on)$")
    private val IS_ON = Patterns.get("^(?:is|can (?:i|you) play|can i get|does|will) (.+?) (?:on|for|available on|out on|playable on|coming to|come to|come out on|run on|work on) (.+?)$")
    private val VERB_READ = Patterns.get("^(?:where|how) (?:can|do|could|should|would|might) (?:i|we|you|one) (?:read|borrow|check out|listen to (?=.*\\baudio ?books?\\b)) (.+)$")
    private val VERB_BUY = Patterns.get("^(?:(?:where|how) (?:can|do|could|should|would|might|to) (?:i |we |you |one )?(?:buy|get|purchase|order|find|pick up|grab|cop|snag|score|preorder|pre-order|pre order)|(?:i (?:want|need|would like|wanna|'d like) to|i wanna|help me|let me|i'm trying to|i am trying to|trying to) (?:buy|get|purchase|order|find|pick up|grab|preorder|pre-order)|buy|purchase|order|preorder|pre-order|pre order|shop for|find me) (.+)$")
    private val PRICE = Patterns.get("^(?:how much (?:is|are|does|do|would|will|for|costs?)|what(?:'s| is| does) (?:the )?(?:price|cost) (?:of|for)|price (?:of|for|check on|check)|cost of|what does) (.+?)(?: cost| go for| sell for| run| costs?)?$|^(.+?) prices?$")
    private val RATING = Patterns.get("^(?:(?:is|was|are|were) (.+?) (?:any good|good|great|fun|worth (?:it|playing|reading|buying|getting|the money|my time|a read|a play)|overrated|underrated|bad|well reviewed|well rated|recommended|a good (?:game|book|board game|read))|how (?:good|well reviewed|well rated|fun|highly rated|well received) (?:is|was|are|were) (.+?)|what(?:'s| is| was| did) (.+?) (?:rated|get|score|scored|rate|review score|metacritic|opencritic|metascore)|(?:ratings?|reviews?|review scores?|scores?|metacritic|opencritic|goodreads|bgg rating) (?:of|for|on) (.+?)|(.+?)(?:'s)? (?:ratings?|reviews?|review scores?|scores?|metacritic(?: score)?|opencritic(?: score)?|metascore|goodreads(?: rating)?|bgg(?: rating)?|boardgamegeek(?: rating)?)|should i (?:play|read|buy|get|try|pick up) (.+?)|would you recommend (.+?)|do (?:people|critics|reviewers) like (.+?)|what do (?:people|critics|reviewers) think (?:of|about) (.+?))$")

    /** Words around a title that say what it is or which copy ("the game", "the audiobook", "used", "for switch"). */
    private val TITLE_NOISE = Regex(
        "\\b(?:the (?:video ?game|game|book|novel|board ?game|card game|audiobook|audio book|e-?book|series)|video ?games?|the board ?game|board ?games?|card games?|games?|" +
            "books?|novels?|audiobooks?|audio ?books?|e-?books?|kindle(?: edition| version)?|paperback|hardcover|hardback|physical(?: copy| edition)?|digital(?: copy| edition| version)?|" +
            "on (?:a )?(?:disc|disk|cartridge|game card)|a copy of|copy of|a copy|copies of|used|second ?hand|pre-?owned|refurbished|cheap|cheaply|cheapest|online|digitally|physically|in print|in stores?|" +
            "please|brick|for me|right now|today|tonight)\\b",
    )

    fun cleanWorkTitle(raw: String): String? {
        var r = " ${raw.replace(Patterns.get("[?!.]"), " ")} "
        r = r.replace(TITLE_NOISE, " ").replace(Patterns.get("\\s+"), " ").trim()
        r = r.replace(Patterns.get("(?:'s)?\\s+(?:metacritic|opencritic|metascore|goodreads|bgg|boardgamegeek|review|reviews|rating|ratings|critic|critics)(?: scores?| ratings?)?$"), "").removeSuffix("'s")
        // "the kindle version of the martian" leaves "the of the martian".
        r = r.replace(Patterns.get("^(?:the |a |an )?(?:(?:version|edition|copy|copies|one) )?of\\s+"), "")
        r = r.replace(Patterns.get("^(?:a|an|some|any|my|me|us)\\s+"), "").replace(Patterns.get("\\s+(?:on|for|at|from|in|to|as|a)$"), "").trim()
        // No leading "the": Wikidata's search finds "The Legend of Zelda" from "zelda", not from "the zelda".
        r = r.replace(Patterns.get("^(?:the )+"), "").removeSuffix(" the").trim()
        return r.ifEmpty { null }?.takeIf { it.length > 1 && it !in setOf("the", "it", "one", "this", "that", "them") }
    }

    // ------------------------------------------------------------------ sales

    private fun norm(s: String) = s.lowercase(Locale.US).replace('’', '\'').replace(Patterns.get("[?!.,]"), " ").replace(Patterns.get("\\s+"), " ").trim()
        .replace(Patterns.get("^(?:(?:hey )?brick|ok|okay|so|um|uh|yo|hey)\\s+"), "")

    private val SALE_WORD = Patterns.get("\\b(?:on sale|sales?|deals?|discounts?|discounted|specials|bargains?|price drops?|marked down|markdowns?|cheap(?: [a-z-]+){0,2} (?:games|movies|films|shows|seasons))\\b")
    private val DEAL_STORE = Patterns.get("\\b(steam|epic(?: games)?(?: store)?|gog(?:\\.com)?|humble(?: store| bundle)?|fanatical|green man gaming|gmg)\\b")
    private val DEAL_CONSOLE = Patterns.get("\\b(switch|nintendo|eshop|xbox|playstation|ps[45]|psn|ps store)\\b")
    private val UNDER = Patterns.get("(?:under|below|less than|cheaper than|for less than|no more than|at most|max|up to) \\$?(\\d+(?:\\.\\d+)?)(?: dollars| bucks)?|\\$?(\\d+(?:\\.\\d+)?)(?: dollars| bucks)? (?:or less|or under|and under|or cheaper|max)")
    private val GOOD_DEAL = Patterns.get("\\b(?:good|great|best|top|well[- ]reviewed|highly rated|well rated|worth (?:it|buying|getting)|quality|must[- ]buy)\\b")
    // Everything a deals request can say besides a store, a price and "good" (what's left is a title).
    private val DEAL_FILLER = Patterns.get("\\b(?:what|what's|whats|which|is|are|there|any|anything|some|current|currently|right|now|today|tonight|this|week|weekend|month|going|on|at|in|from|for|me|us|show|list|give|find|tell|got|get|see|check|look|up|the|a|an|cheap|cheapest|cheaper|video|pc|computer|i|can|could|you|of|with|reviews?|reviewed|rated|price|prices|games?|gaming|titles?|sale|sales|deals?|discounts?|discounted|specials|bargains?|price drops?|marked|down|markdowns?|store|stores|good|great|best|top|big|biggest|worth|it|buying|getting|quality|must|buy|happening|running|live|out|there|lately|recently|new|newest|latest|to|and|or|please|do|does|have|has|know|about|that|play|digital|online|dollars?|bucks|under|below|less|than|no|more|most|max|ones?|well|highly|steam|epic|gog|humble|bundle|fanatical|green|man|gmg|switch|nintendo|eshop|xbox|playstation|ps4|ps5|psn)\\b")

    // "TV deals" alone is televisions, so "tv" counts only with shows, series or seasons.
    private val VIDEO_WORD = Patterns.get("\\b(?:movies?|films?|flicks?|tv shows?|tv series|tv seasons?|shows?|series|seasons?|apple tv|itunes|prime video|amazon video|vudu|fandango(?: at home)?|google tv|google play movies|4k movies)\\b")
    private val VIDEO_STORE = Patterns.get("\\b(apple tv|apple|itunes|prime video|prime|amazon|vudu|fandango(?: at home)?|google tv|google play|google|youtube)\\b")
    private val VIDEO_FILLER = Patterns.get("\\b(?:movies?|films?|flicks?|tv|shows?|series|seasons?|apple|itunes|amazon|prime|video|vudu|fandango|home|google|play|youtube|4k|hd|uhd|digital|digitally|to own|own|buy|rent|from|since|between|before|after|early|late|mid|old|older|retro|recent|year|years|decade|s)\\b")

    /** "Movie deals", "any good horror movies on sale under $5", "TV show sales on Apple", "80s movies on sale at Vudu". */
    private fun parseVideoDeals(t: String): Request.Deals? {
        if (!VIDEO_WORD.containsMatchIn(t)) return null
        val tv = Patterns.get("\\b(?:tv|shows?|series|seasons?)\\b").containsMatchIn(t) && !Patterns.get("\\b(?:movies?|films?)\\b").containsMatchIn(t)
        val genre = Prices.VIDEO_GENRES.entries.firstOrNull { (words, _) -> Regex("\\b(?:$words)\\b").containsMatchIn(t) }
        val span = years(t)
        val left = t.replace("'s", "").let { x -> span?.second?.let { r -> x.removeRange(r) } ?: x }
            .let { x -> genre?.let { g -> x.replace(Regex("\\b(?:${g.key})\\b"), " ") } ?: x }
            .replace(UNDER, " ").replace(DEAL_FILLER, " ").replace(VIDEO_FILLER, " ").replace(Patterns.get("[^a-z]+"), " ").trim()
        if (left.isNotEmpty()) return null
        val store = VIDEO_STORE.find(t)?.groupValues?.get(1)?.let { st ->
            when {
                st.startsWith("apple") || st == "itunes" -> "itunes"
                st.startsWith("prime") || st == "amazon" -> "amazon"
                st == "vudu" || st.startsWith("fandango") -> "vudu"
                else -> "googlePlay"
            }
        }
        val under = UNDER.find(t)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDoubleOrNull() }
        return Request.Deals(store, under, GOOD_DEAL.containsMatchIn(t), video = if (tv) LookupKind.TV_SHOW else LookupKind.MOVIE,
            genre = genre?.value, years = span?.first)
    }

    /** "What games are on sale", "any good Steam deals under $10", "Epic sales", "best switch game deals" (consoles: Brick says why it can't list them). */
    fun parseDeals(s: String): Request.Deals? {
        val t = norm(s)
        if (!SALE_WORD.containsMatchIn(t)) return null
        val gameish = Patterns.get("\\b(?:games?|gaming)\\b").containsMatchIn(t) || DEAL_STORE.containsMatchIn(t)
        if (!gameish) return parseVideoDeals(t)
        // Something else is named ("is hades on sale"): that's a title's sale, not the list.
        val left = t.replace("'s", "").replace(UNDER, " ").replace(DEAL_FILLER, " ").replace(Patterns.get("[^a-z]+"), " ").trim()
        if (left.isNotEmpty()) return null
        val store = DEAL_STORE.find(t)?.groupValues?.get(1)?.let { st ->
            when {
                st.startsWith("epic") -> "epic"; st.startsWith("gog") -> "gog"; st.startsWith("humble") -> "humble"
                st == "green man gaming" || st == "gmg" -> "gmg"; else -> st
            }
        }
        val console = if (store == null) DEAL_CONSOLE.find(t)?.groupValues?.get(1) else null
        val under = UNDER.find(t)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDoubleOrNull() }
        return Request.Deals(store, under, GOOD_DEAL.containsMatchIn(t), console)
    }

    private val SALE_ASK = listOf(
        Patterns.get("^(?:is|are|was) (?:the (?:game|video ?game|book|novel|movie|film|show|album|song) )?(.+?) (?:on sale|discounted|on discount|marked down|cheaper(?: than usual)?|any cheaper|in a sale|part of a sale|on special)(?: (?:right )?now| today| this week| at the moment)?(?: (on|at|for) (.+))?$"),
        Patterns.get("^(?:are there |is there |do you know of |got |find me |find |show me |get me )?(?:any |a |the )?(?:sales?|deals?|discounts?|specials?|price drops?) (?:on|for) (?:the (?:game|video ?game|book|novel|movie|film|show|album|song) )?(.+?)(?: (on|at) (.+))?$"),
        Patterns.get("^(?:what(?:'s| is) )?(?:the )?(?:cheapest|lowest|best) (?:price|deal) (?:on|for) (?:the (?:game|video ?game|book|novel|movie|film|show|album|song) )?(.+?)(?: (on|at) (.+))?$"),
        Patterns.get("^(?:when (?:is|will) )(.+?) (?:go )?on sale$"),
        Patterns.get("^(?:check|look up|search) (?:the )?(?:price|prices|sale|sales|deals?) (?:on|for) (.+?)$"),
    )

    /** "Is Hades on sale", "any deals on Elden Ring for PS5", "cheapest price for Dune", "is the Batman on sale". */
    fun parseSale(s: String): Request? {
        val t = norm(s)
        for (rx in SALE_ASK) {
            val m = rx.find(t) ?: continue
            val title = m.groupValues[1]
            val where = m.groupValues.getOrNull(3).orEmpty()
            if (title.isBlank() || Patterns.get("^(?:it|that|this|there|anything|something|everything)$").matches(title)) return null
            // "Are games on sale" is the deals list.
            if (Patterns.get("^(?:(?:video |pc |steam |switch |xbox |ps5 )?games|books|movies|ebooks)$").matches(title)) return null
            val raw = listOf(title, where).filter { it.isNotBlank() }.joinToString(" on ")
            // Movies, shows and music go to the movie and song guide (Apple's prices there).
            if (Patterns.get("\\b(?:movie|film|show|series|season|tv)\\b").containsMatchIn(t))
                return Request.MediaAsk(MediaAskKind.BUY_VIDEO, cleanWorkTitle(title) ?: return null,
                    video = if (Patterns.get("\\b(?:show|series|season|tv)\\b").containsMatchIn(t)) LookupKind.TV_SHOW else LookupKind.MOVIE)
            if (Patterns.get("\\b(?:song|album|track|single|ep)\\b").containsMatchIn(t)) {
                val (song, artist) = title.split(" by ", limit = 2).let { it[0] to it.getOrNull(1) }
                return Request.MediaAsk(MediaAskKind.BUY_SONG, cleanWorkTitle(song.replace(Patterns.get("^the (?:song|album|track|single) "), "")) ?: return null, artist = artist)
            }
            val kind = kindOf(t) ?: gameFilter(" $raw ").first.let { f -> if (f.platforms.isNotEmpty() || f.store != null) WorkKind.GAME else null }
            val (gf, rest) = if (kind == WorkKind.GAME || kind == null) gameFilter(" $raw ") else GameFilter() to raw
            val clean = cleanWorkTitle(if (gf.platforms.isNotEmpty() || gf.store != null) rest else raw) ?: return null
            return Request.WorkAsk(kind, WorkAskKind.BUY, clean, platform = gf.device ?: gf.storeName, platformIds = gf.platforms, guess = kind == null, sale = true)
        }
        return null
    }

    /**
     * "where can I buy the zelda game", "is the way of kings worth reading", "what can I play elden ring on", "where can I
     * read dune", "catan board game price". Without a game or book word it's left to the movie and song guide (which
     * falls back here when it finds nothing).
     */
    fun parseAsk(s: String): Request.WorkAsk? {
        val t = s.lowercase(Locale.US).replace('’', '\'').replace(Patterns.get("[?!.,]"), " ").replace(Patterns.get("\\s+"), " ").trim()
            .replace(Patterns.get("^(?:(?:hey )?brick|ok|okay|so|um|uh|yo|hey)\\s+"), "")
        if (t.split(' ').size > 18) return null
        val kind = kindOf(t)
        val audio = Patterns.get("\\b(?:audiobooks?|audio books?|audible|libro\\.?fm|listen to)\\b").containsMatchIn(t)
        val format = when {
            Patterns.get("\\b(?:paperback|hardcover|hardback|physical|in print|print copy|on disc|disc|cartridge|boxed|box copy|in stores?)\\b").containsMatchIn(t) -> BuyFormat.PHYSICAL
            Patterns.get("\\b(?:e-?books?|kindle|digital|digitally|download|downloadable|on steam|eshop|psn|e-?reader)\\b").containsMatchIn(t) -> BuyFormat.DIGITAL
            else -> BuyFormat.ANY
        }
        val used = Patterns.get("\\b(?:used|second ?hand|pre-?owned|refurbished|cheap|cheapest)\\b").containsMatchIn(t)
        fun req(k: WorkKind?, a: WorkAskKind, raw: String, platform: String? = null, guess: Boolean = false): Request.WorkAsk? {
            val (gf, rest) = if (k == WorkKind.GAME || k == null) gameFilter(" $raw ") else GameFilter() to raw
            // "where can I buy zelda for switch": the device chooses the stores; "is minecraft on phone": the question.
            val title = cleanWorkTitle(if (gf.platforms.isNotEmpty() || gf.store != null) rest else raw) ?: return null
            return Request.WorkAsk(k, a, title, format, audio, used, platform = platform ?: gf.device ?: gf.storeName,
                platformIds = gf.platforms, newest = Patterns.get("\\b(?:newest|latest|most recent|new)\\b").containsMatchIn(t), guess = guess)
        }
        IS_ON.find(t)?.let { m ->
            val (gf, _) = gameFilter(" ${m.groupValues[2]} ")
            if (gf.platforms.isNotEmpty() || gf.store != null) {
                val title = cleanWorkTitle(m.groupValues[1]) ?: return null
                return Request.WorkAsk(WorkKind.GAME, WorkAskKind.PLAY, title, format, platform = gf.device ?: gf.storeName, platformIds = gf.platforms)
            }
        }
        PLATFORMS_Q.find(t)?.let { m -> return req(WorkKind.GAME, WorkAskKind.PLAY, m.groupValues.drop(1).first { it.isNotEmpty() }) }
        VERB_PLAY.find(t)?.let { m ->
            // "Where can I play blinding lights": a song. Only games (or no word at all: tried as a game, then a song).
            if (kind == WorkKind.BOOK || Patterns.get("\\b(?:song|music|album|track|playlist|podcast|movie|film|show|episode|video|trailer)\\b").containsMatchIn(t)) return null
            return req(kind ?: WorkKind.GAME, WorkAskKind.PLAY, m.groupValues[1], guess = kind == null)
        }
        VERB_READ.find(t)?.let { m ->
            if (kind == WorkKind.GAME || kind == WorkKind.BOARD_GAME) return null
            if (Patterns.get("\\b(?:news|headlines|messages?|texts?|emails?|notifications?|lyrics|transcript|subtitles|captions)\\b").containsMatchIn(t)) return null
            return req(WorkKind.BOOK, WorkAskKind.READ, m.groupValues[1])
        }
        // "Elden Ring for PS5": a console or game store says it's a game.
        val deviceSays = kind ?: gameFilter(t).first.let { f -> if (f.platforms.isNotEmpty() && f.device !in setOf("PC", "Mac", "iPhone", "iPad", "Android", "phone", "tablet") || f.store != null) WorkKind.GAME else null }
        VERB_BUY.find(t)?.let { m ->
            val kind = deviceSays ?: return null
            if (Patterns.get("\\b(?:tickets?|groceries|near me|gift ?cards?|console|controller|headset|charger|cable)\\b").containsMatchIn(t) && kind != WorkKind.BOOK) return null
            return req(kind, WorkAskKind.BUY, m.groupValues[1])
        }
        PRICE.find(t)?.let { m ->
            val kind = deviceSays ?: return null
            return req(kind, WorkAskKind.BUY, m.groupValues.drop(1).first { it.isNotEmpty() })
        }
        RATING.find(t)?.let { m ->
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            val k = kind ?: when {
                Patterns.get("^should i (?:play)|worth playing|a play\\b|opencritic|metacritic score").containsMatchIn(t) -> WorkKind.GAME
                Patterns.get("^should i read|worth reading|a read\\b|goodreads").containsMatchIn(t) -> WorkKind.BOOK
                Patterns.get("\\bbgg|boardgamegeek").containsMatchIn(t) -> WorkKind.BOARD_GAME
                else -> return null
            }
            // A list ("are the zelda games good") is the ranked list's.
            if (Patterns.get("\\b(?:games|books|novels|board games)\\b").containsMatchIn(raw) && Patterns.get("^(?:are|were) ").containsMatchIn(t)) return null
            return req(k, WorkAskKind.RATING, raw)
        }
        return null
    }

    // ------------------------------------------------------------------ "what's the newest zelda game"

    private val RECENT = Patterns.get("\\b(?:most recent|more recent|latest|newest|new|newer|last|recent|current|next|upcoming|coming|future|sequel|another|brand new|most current)\\b")
    private val NEXT = Patterns.get("\\b(?:next|upcoming|coming out|coming soon|future|sequel|another|will there be|is there going to be|gonna be|when (?:is|does|will) .* (?:come out|release|drop|be out))\\b")
    private val LATEST_FILLER = Regex(
        "\\b(?:what'?s|what is|what was|whats|which is|which|what|tell me|do you know|is there|are there|is|are|was|were|will there be|will|there|be|gonna|going to|" +
            "when|does|do|did|has|have|had|come out|came out|coming out|comes out|coming|released|release|releases|release date|drop|drops|dropped|out|launch|launches|" +
            "the|a|an|of|in|from|for|on|to|about|any|yet|so far|currently|right now|now|soon|called|named|title|name|franchise|saga|series|universe|entry|installment|" +
            "most recent|more recent|latest|newest|new|newer|last|recent|current|next|upcoming|future|another|brand new|most current|one|sequel|part|volume|vol|" +
            "video ?games?|games?|board ?games?|card games?|books?|novels?|novellas?|audiobooks?|by|written by|made by|developed by|from|author|writer|studio|" +
            "i|me|you|please|brick|hey|know|think|heard|gonna be|get|got|release)\\b",
    )

    /**
     * "what's the newest zelda game", "next stormlight book", "latest brandon sanderson novel", "new catan board game".
     * "Newest switch games" (a device, no series) is a list of recent games.
     */
    fun parseLatest(s: String, now: Int = LocalDate.now().year): Request? {
        val t = s.lowercase(Locale.US).replace('’', '\'').replace(Patterns.get("[?!.,]"), " ").replace(Patterns.get("\\s+"), " ").trim()
        if (!RECENT.containsMatchIn(t) || t.split(' ').size > 16) return null
        val kind = kindOf(t) ?: return null
        if (Patterns.get("\\b(?:where|buy|rent|purchase|price|cost|how much|rated|rating|reviews?|worth|good|best|worst|news|headlines|watch|movie|film|show|season|episode|song|album|update|patch|dlc|trailer)\\b").containsMatchIn(t)) return null
        if (Patterns.get("^(?:play|open|launch|start|read|set|remind|text|call|add)\\b").containsMatchIn(t)) return null
        // A question about a title ("characters in the last of us game", "how long is the last of us"), not the newest one.
        if (Patterns.get("\\b(?:characters?|cast|actors?|actress|awards?|accolades|nominat\\w*|oscars?|emmys?|grammys?|how long|pages|length|runtime|duration|playtime|who plays|who played|voices?|voiced)\\b").containsMatchIn(t)) return null
        val next = NEXT.containsMatchIn(t)
        if (kind == WorkKind.GAME) {
            val (gf, rest) = gameFilter(t)
            var left = " $rest "
            repeat(3) { left = left.replace(LATEST_FILLER, " ") }
            if ((gf.platforms.isNotEmpty() || gf.store != null) && left.isBlank()) {
                return Request.Ranked(RankKind.GAME, Tier.ALL, null, null, gf.copy(yearFrom = gf.yearFrom ?: (now - 1), yearTo = gf.yearTo ?: now), newest = true)
            }
        }
        var r = " $t "
        repeat(3) { r = r.replace(LATEST_FILLER, " ") }
        val name = r.replace(Patterns.get("'s\\b"), " ").replace(Patterns.get("\\s+"), " ").trim().ifEmpty { return null }
        return Request.LatestWork(name, kind, next)
    }

    // ================================================================== fetching

    private const val UA = "BrickAssistant/1.0 (Android personal assistant app)"
    private val cache = object : LinkedHashMap<String, Pair<String, Long>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?) = size > 48
    }

    /** A page or API answer as text (kept 10 minutes). [timeout] is long for Wikidata's query service. */
    private fun fetch(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 12000): String? {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[url]?.let { if (now - it.second < 10 * 60_000) return it.first } }
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            c.disconnect()
            body?.also { synchronized(cache) { cache[url] = it to now } }
        } catch (e: Exception) {
            null
        }
    }

    private fun json(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 12000): JSONObject? =
        fetch(url, headers + ("Accept" to "application/json"), timeout)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Wikidata query rows as name -> value (items as their Q-ids). Null when the query service failed or took too long. */
    private fun sparql(q: String, timeout: Int = 30000): List<Map<String, String>>? {
        val j = json("https://query.wikidata.org/sparql?format=json&query=${Http.enc(q)}", mapOf("Accept" to "application/sparql-results+json"), timeout)
            ?: return null
        val rows = j.optJSONObject("results")?.optJSONArray("bindings") ?: return null
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { o ->
            // Item links become their Q-ids; text (even text that starts with a link, like joined scores) stays as it is.
            o.keys().asSequence().associateWith { k ->
                val c = o.optJSONObject(k)
                c?.optString("value").orEmpty().let { v -> if (c?.optString("type") == "uri" && v.startsWith("http://www.wikidata.org/entity/")) v.substringAfterLast('/') else v }
            }
        }
    }

    data class Hit(val id: String, val label: String, val description: String)

    private fun wbsearch(q: String): List<Hit> {
        val j = Http.getJsonCached("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&uselang=en&type=item&limit=12&search=${Http.enc(q)}")
            ?: return emptyList()
        val a = j.optJSONArray("search") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Hit(it.optString("id"), it.optString("label"), it.optString("description")) }
    }

    private val ONE_DESC = mapOf(
        WorkKind.GAME to Patterns.get("(?i)\\b(?:video ?game|computer game|mobile game|browser game|arcade game|online game|visual novel|game for (?:the )?(?:nintendo|playstation|xbox|pc|windows|wii|game ?boy|sega|mobile)|(?:action|role-playing|platform|puzzle|racing|fighting|sports|shooter|strategy|simulation|adventure|survival|horror|sandbox|rhythm|stealth)[a-z -]* game)\\b"),
        WorkKind.BOOK to Patterns.get("(?i)\\b(?:novel|book|novella|memoir|short story|short stories|non-?fiction|poetry collection|poem|graphic novel|manga|comic|children's book|picture book|autobiography|biography|textbook|literary work|written work|essay)\\b"),
        WorkKind.BOARD_GAME to Patterns.get("(?i)\\b(?:board ?game|card game|tabletop game|dice game|party game|deck-?building game|miniatures? game|wargame|role-playing game system|tabletop role-playing game)\\b"),
    )
    private val SERIES_DESC = mapOf(
        WorkKind.GAME to Patterns.get("(?i)\\b(?:video ?game (?:series|franchise)|series of (?:video )?games|game series|media franchise|franchise)\\b"),
        WorkKind.BOOK to Patterns.get("(?i)\\b(?:(?:book|novel|novella|fantasy|epic fantasy|science fiction|sci-fi|mystery|thriller|young adult|children's|manga|comic|literary|horror|romance|detective|crime|series) (?:book |novel )?series|series of (?:novels|books|fantasy novels|children's books)|trilogy|tetralogy|quartet|saga|sequence of novels|novel sequence|book franchise|series)\\b"),
        WorkKind.BOARD_GAME to Patterns.get("(?i)\\b(?:(?:board|card) game (?:series|franchise|family)|series of (?:board|card) games|game series|franchise)\\b"),
    )
    private val MAKER_DESC = mapOf(
        WorkKind.GAME to Patterns.get("(?i)\\b(?:video game (?:developer|publisher|studio|company)|game (?:developer|publisher|studio|company)|developer|publisher|studio|company|corporation|subsidiary|division|game designer|video game designer)\\b"),
        WorkKind.BOOK to Patterns.get("(?i)\\b(?:writer|author|novelist|poet|journalist|essayist|screenwriter|playwright|historian|biographer|cartoonist|mangaka|illustrator|philosopher|columnist)\\b"),
        WorkKind.BOARD_GAME to Patterns.get("(?i)\\b(?:game designer|board game designer|board game publisher|game publisher|designer|publisher)\\b"),
    )

    private fun words(s: String) = Franchise.core(s).replace(":", "").split(' ').filter { it.isNotBlank() && it != "the" && it != "of" && it != "a" }

    /** True when every word of [q] is in [label] ("zelda" in "The Legend of Zelda"). */
    private fun named(q: String, label: String) = words(label).containsAll(words(q))

    private val TYPES = mapOf(
        WorkKind.GAME to "wd:Q7889",
        WorkKind.BOOK to "wd:Q7725634 wd:Q47461344 wd:Q571 wd:Q8261 wd:Q149537 wd:Q49084 wd:Q1279564 wd:Q725377 wd:Q21198342",
        WorkKind.BOARD_GAME to "wd:Q131436 wd:Q142714 wd:Q1272194 wd:Q1783817",
    )
    private val MAKER_PROPS = mapOf(WorkKind.GAME to "wdt:P178|wdt:P123", WorkKind.BOOK to "wdt:P50", WorkKind.BOARD_GAME to "wdt:P287|wdt:P123")

    /** A series or maker named like [q], for [kind] (the series first unless [makerFirst]). */
    private fun resolve(q: String, kind: WorkKind, series: Boolean = true, maker: Boolean = true): Pair<Hit, Boolean>? {
        val hits = wbsearch(q).filter { named(q, it.label) }
        if (series) hits.firstOrNull { SERIES_DESC[kind]!!.containsMatchIn(it.description) && !ONE_DESC[kind]!!.containsMatchIn(it.description.replace(SERIES_DESC[kind]!!, "")) }
            ?.let { return it to true }
        if (series) hits.firstOrNull { SERIES_DESC[kind]!!.containsMatchIn(it.description) }?.let { return it to true }
        if (maker) hits.firstOrNull { MAKER_DESC[kind]!!.containsMatchIn(it.description) }?.let { return it to false }
        return null
    }

    /** The games or books in a series ("zelda") or by a maker ("fromsoftware", "brandon sanderson"), oldest first. */
    suspend fun set(q: String, kind: WorkKind, maker: Boolean = true): WorkSet? = withContext(Dispatchers.IO) {
        val (hit, bySeries) = resolve(q, kind, maker = maker) ?: return@withContext null
        val link = if (bySeries) "?w p:P179 ?st . ?st ps:P179 wd:${hit.id} . OPTIONAL { ?st pq:P1545 ?o }" else "?w ${MAKER_PROPS[kind]} wd:${hit.id} ."
        val rows = sparql(
            "SELECT ?w (SAMPLE(?l) AS ?label) (MIN(?d) AS ?date) (SAMPLE(?o) AS ?ord) (SAMPLE(?bg) AS ?bgg) (SAMPLE(?al) AS ?author) (COUNT(?t) AS ?typed) WHERE { " +
                "$link OPTIONAL { ?w wdt:P31 ?t . VALUES ?t { ${TYPES[kind]} } } OPTIONAL { ?w wdt:P577 ?d } " +
                "OPTIONAL { ?w rdfs:label ?l FILTER(lang(?l) = \"en\") } OPTIONAL { ?w wdt:P2339 ?bg } " +
                "OPTIONAL { ?w wdt:P50 ?a . ?a rdfs:label ?al FILTER(lang(?al) = \"en\") } } GROUP BY ?w LIMIT 400",
        ) ?: return@withContext null
        val typed = rows.filter { (it["typed"]?.toIntOrNull() ?: 0) > 0 }
        val works = (typed.ifEmpty { rows }).mapNotNull { r ->
            val title = r["label"]?.ifBlank { null } ?: return@mapNotNull null
            if (Patterns.get("(?i)\\((?:part|volume|vol\\.?) (?:one|two|three|\\d)\\)|\\b(?:collection|compilation|box set|boxed set|omnibus|soundtrack|bundle)\\b").containsMatchIn(title)) return@mapNotNull null
            Work(title, r["date"]?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, r["w"], r["ord"]?.toDoubleOrNull(), r["bgg"]?.ifBlank { null }, r["author"]?.ifBlank { null })
        }.distinctBy { Franchise.core(it.title) }
        if (works.isEmpty()) return@withContext null
        WorkSet(hit.label, hit.id, bySeries, works.sortedWith(compareBy<Work, LocalDate?>(nullsLast()) { it.date }.thenBy { it.ordinal }))
    }

    /** The one game or book named [title] (any kind when [kind] is null), with its scores and where it's sold. */
    suspend fun find(title: String, kind: WorkKind?, bggToken: String = ""): WorkInfo? = withContext(Dispatchers.IO) {
        val hits = wbsearch(title)
        fun fits(h: Hit, k: WorkKind) = ONE_DESC[k]!!.containsMatchIn(h.description) && !SERIES_DESC[k]!!.containsMatchIn(h.description.replace(Patterns.get("(?i)\\bfranchise\\b"), ""))
        val kinds = kind?.let { listOf(it) } ?: listOf(WorkKind.GAME, WorkKind.BOOK, WorkKind.BOARD_GAME)
        // An exact name first, then the first that fits.
        val pick = hits.firstOrNull { h -> Franchise.core(h.label) == Franchise.core(title) && kinds.any { fits(h, it) } }
            ?: hits.firstOrNull { h -> named(title, h.label) && kinds.any { fits(h, it) } }
            ?: hits.firstOrNull { h -> kinds.any { fits(h, it) } }.takeIf { kind != null }
        if (pick == null) {
            // A book Wikidata doesn't have: Open Library alone.
            if (kind == WorkKind.BOOK) return@withContext olOnly(title)
            if (kind == WorkKind.BOARD_GAME && bggToken.isNotBlank()) return@withContext bggOnly(title, bggToken)
            return@withContext null
        }
        val k = kinds.first { fits(pick, it) }
        val e = Http.getJsonCached("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels%7Cclaims%7Csitelinks&languages=en&sitefilter=enwiki&ids=${pick.id}")
            ?.optJSONObject("entities")?.optJSONObject(pick.id) ?: return@withContext null
        val claims = e.optJSONObject("claims") ?: JSONObject()
        fun items(p: String) = claims.optJSONArray(p)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { c -> c.optString("rank") != "deprecated" }?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id") } }.orEmpty()
        fun strings(p: String) = Lookup.strings(claims, p)
        val year = claims.optJSONArray("P577")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")?.removePrefix("+")?.take(4)?.toIntOrNull() }.minOrNull() }
        val platforms = items("P400")
        val formats = if (k == WorkKind.GAME) items("P437") else emptyList()
        val publisherIds = if (k == WorkKind.GAME) items("P123") else emptyList()
        val makers = when (k) { WorkKind.GAME -> items("P178"); WorkKind.BOOK -> items("P50"); WorkKind.BOARD_GAME -> items("P287") }
        // Review scores with who gave them.
        val raw = claims.optJSONArray("P444")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val c = a.optJSONObject(i)?.takeIf { it.optString("rank") != "deprecated" } ?: return@mapNotNull null
                val v = c.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value") ?: return@mapNotNull null
                fun q(p: String) = c.optJSONObject("qualifiers")?.optJSONArray(p)?.optJSONObject(0)?.optJSONObject("datavalue")
                Ranked.Fact(q("P447")?.optJSONObject("value")?.optString("id") ?: return@mapNotNull null, q("P459")?.optJSONObject("value")?.optString("id"), v,
                    q("P585")?.optJSONObject("value")?.optString("time").orEmpty())
            }
        }.orEmpty()
        val names = Lookup.labels((platforms + makers + publisherIds + raw.flatMap { listOfNotNull(it.by, it.method) }).distinct())
        val scores = raw.groupBy { it.by to it.method }.values.map { g -> g.maxBy { it.at } }
            .map { Triple(names[it.by] ?: it.by, it.value, it.method?.let { m -> names[m] }) }
        val image = strings("P18").firstOrNull()?.let { "https://commons.wikimedia.org/wiki/Special:FilePath/${Http.enc(it.replace(' ', '_'))}?width=480" }
        val wiki = e.optJSONObject("sitelinks")?.optJSONObject("enwiki")?.optString("title")?.let { "https://en.wikipedia.org/wiki/${Http.enc(it.replace(' ', '_'))}" }
        val label = (e.optJSONObject("labels")?.optJSONObject("en")?.optString("value") ?: pick.label).let { l -> l.substringBefore(" — ").takeIf { it != l && Franchise.core(it) == Franchise.core(title) } ?: l }
        var info = WorkInfo(k, pick.id, label, year, pick.description.ifBlank { null }, image, wiki, platforms,
            platforms.map { platformName(it, names[it]) }.distinct(), makers.mapNotNull { names[it] }, scores,
            steamId = strings("P1733").firstOrNull(), epicId = strings("P6278").firstOrNull(), gogId = strings("P2725").firstOrNull(), bggId = strings("P2339").firstOrNull(), formats = formats, publishers = publisherIds.mapNotNull { names[it] }, eshop = if (k == WorkKind.GAME) strings("P8084").firstOrNull() else null)
        info = when (k) {
            WorkKind.GAME -> {
                val (s, shown) = gameScore(raw) ?: (null to null)
                info.copy(score = s, scoreShown = shown, steamPrice = info.steamId?.let { steamPrice(it) })
            }
            WorkKind.BOOK -> olBook(label, info.makers.firstOrNull())?.let { o ->
                info.copy(olKey = o.optString("key").ifBlank { null }, olRating = o.optDouble("ratings_average").takeIf { !it.isNaN() },
                    olCount = o.optInt("ratings_count"), ebook = o.optString("ebook_access").ifBlank { null },
                    image = info.image ?: o.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                    year = info.year ?: o.optInt("first_publish_year").takeIf { it > 0 })
            }?.let { it.copy(score = it.olRating?.let { r -> r * 20 }, scoreShown = it.olRating?.let { r -> "OL %.1f/5".format(Locale.US, r) }) } ?: info
            WorkKind.BOARD_GAME -> {
                val stats = if (bggToken.isBlank()) null else (info.bggId?.let { bggThings(listOf(it), bggToken).firstOrNull() } ?: bggSearch(label, bggToken).firstOrNull()?.let { id -> bggThings(listOf(id), bggToken).firstOrNull() })
                info.copy(bgg = stats, bggId = stats?.id ?: info.bggId, score = stats?.average?.let { it * 10 }, scoreShown = stats?.average?.let { "BGG %.1f".format(Locale.US, it) },
                    image = info.image ?: stats?.image)
            }
        }
        info
    }

    /** A book known only to Open Library. */
    private fun olOnly(title: String): WorkInfo? {
        val o = olBook(title, null) ?: return null
        val r = o.optDouble("ratings_average").takeIf { !it.isNaN() }
        return WorkInfo(WorkKind.BOOK, null, o.optString("title"), o.optInt("first_publish_year").takeIf { it > 0 }, null,
            o.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }, null,
            makers = o.optJSONArray("author_name")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            score = r?.let { it * 20 }, scoreShown = r?.let { "OL %.1f/5".format(Locale.US, it) }, olKey = o.optString("key").ifBlank { null },
            olRating = r, olCount = o.optInt("ratings_count"), ebook = o.optString("ebook_access").ifBlank { null })
    }

    private fun bggOnly(title: String, token: String): WorkInfo? {
        val id = bggSearch(title, token).firstOrNull() ?: return null
        val s = bggThings(listOf(id), token).firstOrNull() ?: return null
        return WorkInfo(WorkKind.BOARD_GAME, null, s.name, s.year, null, s.image, null, bggId = s.id, bgg = s,
            score = s.average?.let { it * 10 }, scoreShown = s.average?.let { "BGG %.1f".format(Locale.US, it) })
    }

    /** What kind [title] is, by Wikidata's description of it (or of a series by that name). */
    suspend fun guessKind(title: String): WorkKind? = withContext(Dispatchers.IO) {
        val hits = wbsearch(title).filter { named(title, it.label) }
        for (h in hits) for (k in listOf(WorkKind.GAME, WorkKind.BOARD_GAME, WorkKind.BOOK)) {
            if (ONE_DESC[k]!!.containsMatchIn(h.description) || SERIES_DESC[k]!!.containsMatchIn(h.description) && k != WorkKind.BOOK) return@withContext k
        }
        null
    }

    // ------------------------------------------------------------------ scores

    private const val OPENCRITIC = "Q21039459"
    private const val METACRITIC = "Q150248"

    /** A game's critic score: OpenCritic and Metacritic (averaged) when listed, else any other out-of-100 critic score. */
    fun gameScore(facts: List<Ranked.Fact>): Pair<Double, String>? {
        fun newest(f: (Ranked.Fact) -> Boolean) = facts.filter(f).sortedByDescending { it.at }.firstNotNullOfOrNull { Ranked.normalize(it.value) }
        val oc = newest { it.by == OPENCRITIC && it.value.contains("/") }
        val mc = newest { it.by == METACRITIC && it.value.trim().endsWith("/100") }
        val critics = listOfNotNull(oc, mc)
        if (critics.isNotEmpty()) {
            val shown = listOfNotNull(oc?.let { "OC ${it.roundToInt()}" }, mc?.let { "MC ${it.roundToInt()}" }).joinToString(" · ")
            return critics.average() to shown
        }
        val other = facts.filter { it.value.trim().endsWith("/100") }.groupBy { it.by }.values.mapNotNull { g -> g.maxBy { it.at }.let { Ranked.normalize(it.value) } }
        if (other.isNotEmpty()) return other.average() to "critics ${other.average().roundToInt()}"
        return null
    }

    private fun steamPrice(id: String): String? = Prices.steam(id)?.shown()

    /** Open Library's best match for a title (and author). */
    private fun olBook(title: String, author: String?): JSONObject? {
        val fields = "key,title,author_name,first_publish_year,ratings_average,ratings_count,cover_i,ebook_access,number_of_pages_median"
        val url = "https://openlibrary.org/search.json?title=${Http.enc(title)}" + (author?.let { "&author=${Http.enc(it)}" } ?: "") + "&fields=$fields&limit=8"
        val docs = json(url)?.optJSONArray("docs") ?: return null
        val list = (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }
        return list.firstOrNull { Franchise.core(it.optString("title")) == Franchise.core(title) && it.optInt("ratings_count") > 0 }
            ?: list.firstOrNull { Franchise.core(it.optString("title")) == Franchise.core(title) } ?: list.firstOrNull()
    }

    // ------------------------------------------------------------------ BoardGameGeek (needs a free token)

    private fun bgg(path: String, token: String) = fetch("https://boardgamegeek.com/xmlapi2/$path", mapOf("Authorization" to "Bearer $token"))

    private fun attr(xml: String, tag: String, name: String = "value") = Regex("<$tag\\b[^>]*\\b$name=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)

    fun bggSearch(q: String, token: String): List<String> {
        val xml = bgg("search?type=boardgame&query=${Http.enc(q)}", token) ?: return emptyList()
        val items = Regex("<item\\b[^>]*id=\"(\\d+)\"[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] to (attr(it.groupValues[2], "name") ?: "") }.toList()
        // Exact names first.
        return (items.filter { Franchise.core(it.second) == Franchise.core(q) } + items.filter { named(q, it.second) } + items).map { it.first }.distinct().take(30)
    }

    fun bggThings(ids: List<String>, token: String): List<BggStats> = ids.distinct().chunked(20).flatMap { chunk ->
        val xml = bgg("thing?stats=1&id=${chunk.joinToString(",")}", token) ?: return@flatMap emptyList()
        Regex("<item\\b[^>]*id=\"(\\d+)\"[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            val x = m.groupValues[2]
            BggStats(m.groupValues[1], Patterns.get("<name type=\"primary\"[^>]*value=\"([^\"]*)\"").find(x)?.groupValues?.get(1)?.let(News::decode) ?: "",
                attr(x, "yearpublished")?.toIntOrNull()?.takeIf { it > 0 }, attr(x, "average")?.toDoubleOrNull()?.takeIf { it > 0 },
                attr(x, "bayesaverage")?.toDoubleOrNull()?.takeIf { it > 0 },
                Patterns.get("<rank[^>]*name=\"boardgame\"[^>]*value=\"(\\d+)\"").find(x)?.groupValues?.get(1)?.toIntOrNull(),
                attr(x, "usersrated")?.toIntOrNull(), attr(x, "minplayers")?.toIntOrNull(), attr(x, "maxplayers")?.toIntOrNull(),
                attr(x, "playingtime")?.toIntOrNull()?.takeIf { it > 0 }, attr(x, "averageweight")?.toDoubleOrNull()?.takeIf { it > 0 },
                Patterns.get("<image>([^<]+)</image>").find(x)?.groupValues?.get(1)?.trim())
        }.toList()
    }

    private fun bggHot(token: String): List<String> {
        val xml = bgg("hot?type=boardgame", token) ?: return emptyList()
        return Patterns.get("<item\\b[^>]*id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList()
    }

    // ------------------------------------------------------------------ ranked lists

    /** Good, so-so and bad by a 0..100 score: games like Metacritic (75+ is good), books by readers' stars, board games by BGG. */
    fun tierOf(kind: WorkKind, score: Double): Tier = when (kind) {
        WorkKind.GAME -> if (score >= 75) Tier.GOOD else if (score >= 60) Tier.MEDIOCRE else Tier.BAD
        WorkKind.BOOK -> if (score >= 80) Tier.GOOD else if (score >= 70) Tier.MEDIOCRE else Tier.BAD
        WorkKind.BOARD_GAME -> if (score >= 70) Tier.GOOD else if (score >= 60) Tier.MEDIOCRE else Tier.BAD
    }

    private fun metacriticSearch(t: String) = "https://www.metacritic.com/search/${Http.enc(t).replace("+", "%20")}/?category=13"

    /**
     * Rated video games for a franchise or studio ([name]) and the [filter]'s devices, store and years. With nothing narrow
     * (only years or a store), Wikipedia's lists of each year's best-reviewed games. Second: what the list is ("Zelda").
     */
    suspend fun rankedGames(name: String?, filter: GameFilter, now: Int = LocalDate.now().year): Pair<String?, List<Rated>> = withContext(Dispatchers.IO) {
        var label: String? = null
        val parts = ArrayList<String>()
        if (name != null) {
            val (hit, bySeries) = resolve(name, WorkKind.GAME) ?: return@withContext name to emptyList<Rated>()
            label = hit.label
            parts += if (bySeries) "?w wdt:P179 wd:${hit.id} ." else "?w ${MAKER_PROPS[WorkKind.GAME]} wd:${hit.id} ."
        }
        if (filter.platforms.isNotEmpty()) parts += "?w wdt:P400 ?p . VALUES ?p { ${filter.platforms.joinToString(" ") { "wd:$it" }} }"
        val narrow = parts.isNotEmpty()
        if (filter.store != null) parts += "?w wdt:${filter.store} [] ."
        val having = listOfNotNull(filter.yearFrom?.let { "YEAR(MIN(?d)) >= $it" }, filter.yearTo?.let { "YEAR(MIN(?d)) <= $it" }).joinToString(" && ")
        val rows = if (!narrow) null else sparql(
            "SELECT ?w (SAMPLE(?l) AS ?label) (MIN(?d) AS ?date) (GROUP_CONCAT(DISTINCT CONCAT(STR(?by), \"|\", COALESCE(STR(?m), \"\"), \"|\", ?v, \"|\", COALESCE(STR(?at), \"\")); separator=\";;\") AS ?scores) WHERE { " +
                "?w wdt:P31 wd:Q7889 . ${parts.joinToString(" ")} ?w p:P444 ?s . ?s ps:P444 ?v . ?s pq:P447 ?by . OPTIONAL { ?s pq:P459 ?m } OPTIONAL { ?s pq:P585 ?at } " +
                "OPTIONAL { ?w wdt:P577 ?d } OPTIONAL { ?w rdfs:label ?l FILTER(lang(?l) = \"en\") } } GROUP BY ?w" + (if (having.isNotEmpty()) " HAVING($having)" else "") + " LIMIT 2000",
            timeout = 35000,
        )
        if (rows != null) {
            val out = rows.mapNotNull { r ->
                val title = r["label"]?.ifBlank { null } ?: return@mapNotNull null
                val facts = r["scores"].orEmpty().split(";;").mapNotNull { s ->
                    val p = s.split('|')
                    if (p.size < 3) null else Ranked.Fact(p[0].substringAfterLast('/'), p[1].substringAfterLast('/').ifBlank { null }, p[2], p.getOrElse(3) { "" })
                }
                val (score, shown) = gameScore(facts) ?: return@mapNotNull null
                Rated(title, r["date"]?.take(4), score, shown, metacriticSearch(title), tierOf(WorkKind.GAME, score))
            }.distinctBy { Franchise.core(it.title) }
            return@withContext label to out
        }
        // Broad (or the query service was too slow): each year's best-reviewed games from Wikipedia, on the asked devices.
        label to acclaimed(filter, now)
    }

    /** Wikipedia's "Critically acclaimed games" tables (Metacritic 90+) for the filter's years (this year and last by default). */
    private fun acclaimed(filter: GameFilter, now: Int): List<Rated> {
        val from = (filter.yearFrom ?: (filter.yearTo?.let { it - 1 } ?: (now - 1))).coerceAtLeast(1995)
        val to = (filter.yearTo ?: filter.yearFrom?.let { maxOf(it, now) } ?: now).coerceAtMost(now)
        val years = (to downTo from).take(6)
        val codes = filter.platforms.flatMap { CODES[it].orEmpty() }.toSet() + if (filter.store == "P1733" || filter.store == "P2725" || filter.store == "P6278") setOf("WIN", "PC") else emptySet()
        val out = ArrayList<Rated>()
        for ((i, y) in years.withIndex()) {
            if (i > 0) Thread.sleep(250)
            val api = "https://en.wikipedia.org/w/api.php?format=json&formatversion=2&action=parse&prop=wikitext&page=${y}_in_video_games"
            // The table is in section 2 most years ("Top-rated games" > "Critically acclaimed games").
            val text = (2 downTo 1).firstNotNullOfOrNull { sec ->
                Http.getJsonCached("$api&section=$sec")?.optJSONObject("parse")?.optString("wikitext")?.takeIf { it.contains("{|") && it.contains("Metacritic", true) }
            } ?: continue
            val table = text.substringAfter("{|").substringBefore("\n|}")
            for (row in table.split("\n|-").drop(1)) {
                val title = Patterns.get("''\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]''").find(row)?.let { it.groupValues[2].ifBlank { it.groupValues[1] } } ?: continue
                val score = Patterns.get("\\|\\s*(\\d{2,3})\\s*(?:<ref|$|\\n)").findAll(row).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (codes.isNotEmpty()) {
                    val on = Patterns.get("\\[\\[[^\\]|]*\\|([^\\]]+)\\]\\]|\\[\\[([^\\]]+)\\]\\]").findAll(row).map { it.groupValues[1].ifBlank { it.groupValues[2] } }.toSet()
                    if (codes.none { it in on }) continue
                }
                out += Rated(title, y.toString(), score, "MC ${score.roundToInt()}", metacriticSearch(title), tierOf(WorkKind.GAME, score))
            }
        }
        return out.distinctBy { Franchise.core(it.title) }
    }

    /** Rated books in a series or by an author (Open Library readers), or this year's and last year's well-read books. */
    suspend fun rankedBooks(name: String?, now: Int = LocalDate.now().year): Pair<String?, List<Rated>> = withContext(Dispatchers.IO) {
        val fields = "key,title,author_name,first_publish_year,ratings_average,ratings_count"
        fun rated(o: JSONObject, sub: String?): Rated? {
            val r = o.optDouble("ratings_average").takeIf { !it.isNaN() && o.optInt("ratings_count") >= 3 } ?: return null
            // Few ratings pull toward a middling 3.6, so a 5.0 from four readers doesn't top the list.
            val n = o.optInt("ratings_count"); val weighted = (r * n + 3.6 * 10) / (n + 10)
            return Rated(o.optString("title"), sub, weighted * 20, "OL %.1f/5 (%d)".format(Locale.US, r, o.optInt("ratings_count")),
                "https://openlibrary.org${o.optString("key")}", tierOf(WorkKind.BOOK, weighted * 20))
        }
        fun docs(url: String) = json(url)?.optJSONArray("docs")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        if (name == null) {
            val list = docs("https://openlibrary.org/search.json?q=${Http.enc("first_publish_year:[${now - 1} TO $now] AND ratings_count:[15 TO *] AND language:eng")}&sort=rating&fields=$fields&limit=60")
            return@withContext null to list.mapNotNull { o -> rated(o, o.optJSONArray("author_name")?.optString(0)) }
        }
        // A series: its books from Wikidata, rated by Open Library (one search by the series' author).
        val series = resolve(name, WorkKind.BOOK, maker = false)?.let { set(name, WorkKind.BOOK, maker = false) }
        if (series != null) {
            val author = series.works.mapNotNull { it.author }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val byTitle = (if (author != null) docs("https://openlibrary.org/search.json?author=${Http.enc(author)}&fields=$fields&limit=200") else emptyList())
                .groupBy { Franchise.core(it.optString("title")) }
            val out = series.works.mapNotNull { w ->
                val o = byTitle[Franchise.core(w.title)]?.maxByOrNull { it.optInt("ratings_count") } ?: olBook(w.title, author) ?: return@mapNotNull null
                rated(o, w.date?.year?.toString())?.copy(title = w.title)
            }
            if (out.isNotEmpty()) return@withContext series.name to out
        }
        // An author (or anything Open Library knows by that name).
        var list = docs("https://openlibrary.org/search.json?author=${Http.enc(name)}&fields=$fields&limit=200&sort=rating")
        var label = list.firstOrNull()?.optJSONArray("author_name")?.optString(0)
        if (list.isEmpty()) { list = docs("https://openlibrary.org/search.json?q=${Http.enc(name)}&fields=$fields&limit=60"); label = Franchise.pretty(name) }
        label to list.mapNotNull { o -> rated(o, o.optInt("first_publish_year").takeIf { it > 0 }?.toString()) }
            .filterNot { Patterns.get("(?i)\\b(?:part (?:one|two|three|[123])|box set|boxed set|collection|omnibus|books \\d|\\d books|series)\\b").containsMatchIn(it.title) }
            .distinctBy { Franchise.core(it.title) }
    }

    /** Rated board games (BoardGameGeek, with a token): a series or designer, a name search, or what's hot now. */
    suspend fun rankedBoardGames(name: String?, token: String): Pair<String?, List<Rated>> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext name to emptyList<Rated>()
        var label: String? = name?.let { Franchise.pretty(it) }
        val ids: List<String> = if (name == null) bggHot(token) else {
            val viaWikidata = set(name, WorkKind.BOARD_GAME)?.also { label = it.name }?.works?.mapNotNull { it.bgg }.orEmpty()
            viaWikidata.ifEmpty { bggSearch(name, token) }
        }
        label to bggThings(ids.take(40), token).filter { (it.voters ?: 0) >= 30 && it.average != null }.map { s ->
            Rated(s.name, s.year?.toString(), s.average!! * 10, "BGG %.1f".format(Locale.US, s.average), "https://boardgamegeek.com/boardgame/${s.id}", tierOf(WorkKind.BOARD_GAME, s.average * 10))
        }.distinctBy { Franchise.core(it.title) }
    }

    // ================================================================== where to buy, play or borrow

    private fun has(w: WorkInfo, vararg q: String) = q.any { it in w.platforms }
    private fun e(s: String) = Http.enc(s)

    /** How sure Brick must be that a game is (almost) only sold as a download before it leaves out disc stores. */
    const val DIGITAL_ONLY = 0.85
    // Wikidata distribution formats that aren't a box on a shelf, and values that aren't formats at all.
    private val DIGITAL_FORMATS = setOf("Q269415", "Q54820071", "Q36774", "Q6368", "Q848991", "Q2014637", "Q10928179", "Q723685")
    private val NOT_FORMATS = setOf("Q844580", "Q478088", "Q4439")
    private val VR = Patterns.get("(?i)\\b(?:quest|oculus|horizon os|rift|vive|steamvr|playstation vr|psvr|pico|windows mixed reality|vr)\\b")
    // Publishers that put games in stores on disc or cartridge (the console makers, big publishers and physical-edition labels).
    private val BOXED_PUBLISHERS = Patterns.get("(?i)\\b(?:nintendo|sony|playstation|microsoft|xbox game studios|bethesda|electronic arts|ea|activision|blizzard|ubisoft|bandai namco|namco|square enix|capcom|sega|atlus|take-two|2k|rockstar|warner bros|konami|cd projekt|thq|plaion|deep silver|koch media|koei tecmo|tecmo|level-5|annapurna|devolver|team17|focus|nacon|505 games|limited run|fangamer|iam8bit|super rare|special reserve|private division|humble|raw fury|tinybuild|kadokawa|nis america|nippon ichi|marvelous|aksys|xseed|spike chunsoft|idea factory|pqube|merge games|skybound|modus|maximum games|u&i|numskull|mastiff|playstack|annapurna)\\b")

    /**
     * 0..1: how sure Brick is that a game is sold mainly or only as a download (no disc or cartridge worth sending you to a store for).
     * Wikidata's format list alone isn't enough (it lists only downloads for games that did get boxed releases), so it's weighed with
     * the platforms, the publisher and the year.
     */
    fun digitalConfidence(w: WorkInfo): Double {
        if (w.kind != WorkKind.GAME) return 0.0
        val platformIds = NINTENDO + PLAYSTATION + XBOX
        // A disc, cartridge or game card on record: it was sold boxed.
        if (w.formats.any { it !in DIGITAL_FORMATS && it !in NOT_FORMATS && it !in platformIds }) return 0.05
        if (w.steamPrice == "Free") return 0.97
        val consoles = w.platforms.any { it in platformIds }
        val old = w.year != null && w.year < 2010
        // PC, phones and headsets only: boxed copies stopped being a thing there years ago.
        if (!consoles) return if (w.year != null && w.year < 2012) 0.35 else 0.92
        if (w.publishers.any { BOXED_PUBLISHERS.containsMatchIn(it) }) return 0.1
        var c = 0.5
        if (w.platformNames.any { VR.containsMatchIn(it) }) c += 0.38          // VR games are nearly all downloads
        if (w.formats.isNotEmpty() && w.formats.all { it in DIGITAL_FORMATS || it in NOT_FORMATS }) c += 0.2
        if (w.publishers.isEmpty() || w.publishers.any { it in w.makers }) c += 0.1   // self-published
        if (old) c -= 0.35
        return c.coerceIn(0.0, 0.97)
    }

    /** Stores for a game: digital ones for its platforms, disc stores for consoles; only the asked device's when one was named. */
    fun gameShops(w: WorkInfo, format: BuyFormat, used: Boolean, only: List<String> = emptyList()): List<Shop> {
        val t = w.title
        val want = only.toSet()
        fun allowed(vararg q: String) = want.isEmpty() || q.any { it in want }
        val pc = (w.platforms.isEmpty() || has(w, WINDOWS, MAC, LINUX) || w.steamId != null) && allowed(WINDOWS, MAC, LINUX)
        val ps = (w.platforms.isEmpty() || has(w, *PLAYSTATION.toTypedArray())) && allowed(*PLAYSTATION.toTypedArray())
        val ns = (w.platforms.isEmpty() || has(w, "Q19610114", "Q122761124", "Q56942", "Q203597", "Q17679679")) && allowed(*NINTENDO.toTypedArray())
        val xb = (w.platforms.isEmpty() || has(w, *XBOX.toTypedArray())) && allowed(*XBOX.toTypedArray())
        val out = ArrayList<Shop>()
        if (format != BuyFormat.PHYSICAL) {
            if (pc) {
                if (w.steamId != null || w.epicId == null) out += Shop("DIGITAL", "Steam", w.steamId?.let { "https://store.steampowered.com/app/$it" } ?: "https://store.steampowered.com/search/?term=${e(t)}",
                    if (w.steamId == null) "check there" else null, w.steamPrice)
                if (w.epicId != null) out += Shop("DIGITAL", "Epic Games Store", "https://store.epicgames.com/en-US/p/${w.epicId}")
                if (w.gogId != null) out += Shop("DIGITAL", "GOG", "https://www.gog.com/en/games?query=${e(t)}", "DRM-free")
            }
            if (ps) out += Shop("DIGITAL", "PlayStation Store", "https://store.playstation.com/en-us/search/${e(t).replace("+", "%20")}")
            if (ns) out += Shop("DIGITAL", "Nintendo eShop", "https://www.nintendo.com/us/search/#q=${e(t).replace("+", "%20")}")
            if (xb) out += Shop("DIGITAL", "Xbox Store", "https://www.xbox.com/en-US/search/results/games?q=${e(t)}")
            if (has(w, IOS, IPADOS) && allowed(IOS, IPADOS)) out += Shop("DIGITAL", "App Store", "https://apps.apple.com/us/search?term=${e(t)}")
            if (has(w, ANDROID) && allowed(ANDROID)) out += Shop("DIGITAL", "Google Play", "https://play.google.com/store/search?q=${e(t)}&c=apps")
            if (want.isEmpty() && w.platformNames.any { Patterns.get("(?i)quest|oculus|horizon os|rift").containsMatchIn(it) })
                out += Shop("DIGITAL", "Meta Quest Store", "https://www.meta.com/experiences/search/?q=${e(t).replace("+", "%20")}")
            if (pc) for (p in w.prices) if (p.onSale && p.store in setOf("Humble Store", "Fanatical", "Green Man Gaming", "Epic Games Store", "GOG") && out.none { it.name == p.store })
                out += Shop("DIGITAL", p.store, p.url ?: Prices.storeUrl(p.store, t))
            // Live prices (and sales) where Brick could check.
            for (i in out.indices) w.prices.firstOrNull { it.store == out[i].name }?.let { p ->
                out[i] = out[i].copy(price = p.shown(), sale = p.onSale, url = p.url ?: out[i].url, note = if (p.onSale) p.ends?.let { "sale ends $it" } ?: out[i].note else out[i].note)
            }
        }
        val console = ps || ns || xb || want.isEmpty() && w.platforms.any { it in NINTENDO || it in PLAYSTATION || it in XBOX }
        // Sold (almost) only as a download: no disc stores, unless a disc or a used copy was asked for.
        val discs = format == BuyFormat.PHYSICAL || used || digitalConfidence(w) < DIGITAL_ONLY
        if (format != BuyFormat.DIGITAL && console && discs) {
            val q = e("$t ${only.firstOrNull()?.let { SHORT[it] } ?: ""}".trim())
            if (used) {
                out += Shop("USED", "GameStop", "https://www.gamestop.com/search/?q=$q&prefn1=condition&prefv1=Pre-Owned", "pre-owned")
                out += Shop("USED", "eBay", "https://www.ebay.com/sch/i.html?_nkw=$q", "used copies")
            }
            out += Shop("ON DISC", "Amazon", "https://www.amazon.com/s?k=$q&i=videogames")
            out += Shop("ON DISC", "GameStop", "https://www.gamestop.com/search/?q=$q")
            out += Shop("ON DISC", "Best Buy", "https://www.bestbuy.com/site/searchpage.jsp?st=$q")
            out += Shop("ON DISC", "Walmart", "https://www.walmart.com/search?q=$q")
            out += Shop("ON DISC", "Target", "https://www.target.com/s?searchTerm=$q")
        }
        return out
    }

    /** Stores for a book: free from a library first when reading, then ebooks, print and audiobooks (or the one asked). */
    fun bookShops(w: WorkInfo, format: BuyFormat, audio: Boolean, used: Boolean, read: Boolean): List<Shop> {
        val q = e(listOfNotNull(w.title, w.makers.firstOrNull()).joinToString(" "))
        val out = ArrayList<Shop>()
        val free = ArrayList<Shop>()
        free += Shop("FREE", "Library (Libby)", "https://www.overdrive.com/search?q=$q", "with a library card")
        if (w.olKey != null && w.ebook in setOf("borrowable", "public")) free += Shop("FREE", "Open Library", "https://openlibrary.org${w.olKey}", if (w.ebook == "public") "free to read" else "borrow online")
        if (read) out += free
        if (!audio && format != BuyFormat.PHYSICAL) {
            out += Shop("EBOOK", "Kindle (Amazon)", "https://www.amazon.com/s?k=$q&i=digital-text")
            out += Shop("EBOOK", "Google Play Books", "https://play.google.com/store/search?q=$q&c=books")
            out += Shop("EBOOK", "Kobo", "https://www.kobo.com/us/en/search?query=$q")
            w.prices.firstOrNull { it.store == "Apple Books" }?.let { p -> out += Shop("EBOOK", "Apple Books", p.url ?: "https://books.apple.com/us/search?term=$q") }
        }
        if (!audio && format != BuyFormat.DIGITAL) {
            if (used) {
                out += Shop("USED", "ThriftBooks", "https://www.thriftbooks.com/browse/?b.search=$q", "used copies")
                out += Shop("USED", "AbeBooks", "https://www.abebooks.com/servlet/SearchResults?kn=$q", "used and rare")
            }
            out += Shop("PRINT", "Amazon", "https://www.amazon.com/s?k=$q&i=stripbooks")
            out += Shop("PRINT", "Barnes & Noble", "https://www.barnesandnoble.com/s/$q")
            out += Shop("PRINT", "Bookshop.org", "https://bookshop.org/search?keywords=$q", "supports local bookstores")
        }
        if (audio || format == BuyFormat.ANY) {
            out += Shop("AUDIOBOOK", "Audible", "https://www.audible.com/search?keywords=$q")
            out += Shop("AUDIOBOOK", "Libro.fm", "https://libro.fm/search?q=$q", "supports local bookstores")
        }
        if (!read) out += free
        for (i in out.indices) w.prices.firstOrNull { it.store == out[i].name }?.let { p -> out[i] = out[i].copy(price = p.shown(), sale = p.onSale, url = p.url ?: out[i].url) }
        return out
    }

    /** Stores for a board game, a local game store, and used copies. */
    fun boardShops(w: WorkInfo, used: Boolean): List<Shop> {
        val q = e("${w.title} board game")
        val out = ArrayList<Shop>()
        if (used) out += Shop("USED", "BoardGameGeek market", w.bggId?.let { "https://boardgamegeek.com/geekmarket/browse?objecttype=thing&objectid=$it" } ?: "https://www.ebay.com/sch/i.html?_nkw=$q", "used copies")
        out += Shop("STORES", "Amazon", "https://www.amazon.com/s?k=$q&i=toys-and-games")
        out += Shop("STORES", "Target", "https://www.target.com/s?searchTerm=$q")
        out += Shop("STORES", "Walmart", "https://www.walmart.com/search?q=$q")
        out += Shop("STORES", "Barnes & Noble", "https://www.barnesandnoble.com/s/$q")
        out += Shop("STORES", "Miniature Market", "https://www.miniaturemarket.com/searchresults?q=${e(w.title)}")
        out += Shop("NEARBY", "Local game store", "https://www.google.com/maps/search/board+game+store", "play-and-buy shops often host game nights")
        if (!used) out += Shop("USED", "eBay", "https://www.ebay.com/sch/i.html?_nkw=$q", "used copies")
        return out
    }

    // ================================================================== answers

    private val DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    fun join(l: List<String>, or: Boolean = false): String = when (l.size) {
        0 -> ""; 1 -> l[0]; 2 -> "${l[0]} ${if (or) "or" else "and"} ${l[1]}"
        else -> l.dropLast(1).joinToString(", ") + ", ${if (or) "or" else "and"} " + l.last()
    }

    /** "Elden Ring scored 96 on OpenCritic and 94 on Metacritic." */
    fun ratingLine(w: WorkInfo): String {
        val t = w.title
        return when (w.kind) {
            WorkKind.GAME -> {
                val parts = w.scores.mapNotNull { (who, v, how) ->
                    val n = Ranked.normalize(v)?.roundToInt() ?: return@mapNotNull null
                    if (v.trim().endsWith("%")) return@mapNotNull if (how?.contains("recommend", true) == true) "$n% of $who critics recommend it" else null
                    "$n out of 100 on $who"
                }.distinct().take(3)
                val verdict = w.score?.let { s -> when (tierOf(WorkKind.GAME, s)) { Tier.GOOD -> " Critics liked it."; Tier.MEDIOCRE -> " Reviews were mixed."; else -> " Critics didn't like it." } } ?: ""
                if (parts.isEmpty()) "I couldn't find review scores for $t." else "$t scored ${join(parts)}.$verdict"
            }
            WorkKind.BOOK -> w.olRating?.let { r ->
                "$t" + (w.makers.firstOrNull()?.let { " by $it" } ?: "") + " averages %.1f out of 5 from %d Open Library readers.".format(Locale.US, r, w.olCount) +
                    when (tierOf(WorkKind.BOOK, r * 20)) { Tier.GOOD -> " Readers love it."; Tier.MEDIOCRE -> " Readers are mixed on it."; else -> " Readers didn't care for it." }
            } ?: "I couldn't find reader ratings for $t."
            WorkKind.BOARD_GAME -> w.bgg?.let { s ->
                listOfNotNull(
                    s.average?.let { "$t rates %.1f out of 10 on BoardGameGeek".format(Locale.US, it) + (s.rank?.let { r -> ", ranked number $r overall" } ?: "") + "." },
                    if (s.minPlayers != null && s.maxPlayers != null) "It's for ${if (s.minPlayers == s.maxPlayers) "${s.minPlayers}" else "${s.minPlayers} to ${s.maxPlayers}"} players" +
                        (s.minutes?.let { m -> ", about $m minutes" } ?: "") + "." else null,
                ).joinToString(" ").ifEmpty { "I couldn't find ratings for $t." }
            } ?: "Board game ratings come from BoardGameGeek, which needs a free API token. Add one in Settings, under Games and books."
        }
    }

    private fun platformsPhrase(w: WorkInfo) = join(w.platformNames.distinct().take(8))

    /** "Elden Ring is on PC, PS4, PS5, Xbox One and Xbox Series X/S. It's $59.99 on Steam." / "Yes, Minecraft is on iPhone." */
    fun playLine(w: WorkInfo, asked: String?, askedIds: List<String>): String {
        val t = w.title
        if (asked != null && askedIds.isNotEmpty() && w.platforms.isNotEmpty()) {
            val yes = askedIds.any { it in w.platforms }
            return if (yes) "Yes, $t is on $asked." + (w.steamPrice?.takeIf { WINDOWS in askedIds }?.let { " It's $it on Steam." } ?: "")
            else "No, I don't see $t on $asked. It's on ${platformsPhrase(w)}."
        }
        if (asked != null && askedIds.isEmpty() && w.steamId != null) return "Yes, $t is on Steam" + (w.steamPrice?.let { " for $it" } ?: "") + "." +
            if (asked != "Steam") " Whether it runs well on the $asked depends on the game; Steam's store page shows its rating for it." else ""
        if (w.platforms.isEmpty()) return "I don't know which systems $t is on. Here's where to look for it."
        return "$t is on ${platformsPhrase(w)}." + (w.steamPrice?.let { " It's $it on Steam." } ?: "")
    }

    /** "You can buy Elden Ring on Steam for $59.99, the PlayStation Store or the Xbox Store. On disc, try Amazon, GameStop or Best Buy." */
    fun buyLine(w: WorkInfo, shops: List<Shop>, read: Boolean = false): String {
        val t = w.title
        fun names(section: String) = shops.filter { it.section == section }.map { s ->
            val n = s.name.substringBefore(" (")
            (if (n.endsWith("Store") || n == "Nintendo eShop") "the $n" else n) + (s.price?.substringBefore(" (")?.let { p -> if (p == "Free") " (free)" else " for $p" } ?: "")
        }
        // A sale leads: "Hades is on sale at Steam: $12.49, 50% off, so you save $12.50."
        val sale = Prices.saleLine(t, w.prices.filter { p -> shops.any { it.name == p.store } })?.let { "$it " } ?: ""
        // A short answer (the cheapest price, the kinds of copies) and the stores left to the card.
        fun has(section: String) = shops.any { it.section == section }
        val cheapest = w.prices.filter { p -> p.now > 0 && shops.any { it.name == p.store } }.minByOrNull { it.now }
        val from = cheapest?.let { " from ${Prices.money(it.now)} at ${Prices.theStore(it.store)}" } ?: ""
        val card = "The stores are on the card above."
        if (shops.isEmpty()) return "I couldn't find a store for $t."
        return sale + when (w.kind) {
            WorkKind.GAME -> {
                val digital = names("DIGITAL")
                val how = when {
                    digital.size == 1 -> "on ${digital[0]}"
                    digital.isNotEmpty() -> "digitally$from"
                    else -> null
                }
                listOfNotNull(
                    listOfNotNull(how, if (has("ON DISC")) "on disc" else null, if (has("USED")) "used" else null).takeIf { it.isNotEmpty() }
                        ?.let { "You can get $t ${join(it, or = true)}." },
                    "It's sold mostly as a download, so I left out disc stores.".takeIf {
                        shops.any { it.name in setOf("PlayStation Store", "Nintendo eShop", "Xbox Store") } && !has("ON DISC") && !has("USED") &&
                            w.platforms.any { it in NINTENDO || it in PLAYSTATION || it in XBOX } && digitalConfidence(w) >= DIGITAL_ONLY
                    },
                    card,
                ).joinToString(" ")
            }
            WorkKind.BOOK -> {
                val kinds = listOfNotNull(
                    if (has("EBOOK")) "as an ebook$from" else null, if (has("PRINT")) "in print" else null,
                    if (has("USED")) "used" else null, if (has("AUDIOBOOK")) "as an audiobook" else null,
                )
                listOfNotNull(
                    names("FREE").takeIf { read && it.isNotEmpty() }?.let { "You can borrow $t free through Libby" + (if (it.size > 1) " or Open Library." else ".") },
                    kinds.takeIf { it.isNotEmpty() }?.let { "${if (read) "Or get it" else "You can get $t"} ${join(it, or = true)}." },
                    if (read) "The rest are on the card above." else card,
                ).joinToString(" ")
            }
            WorkKind.BOARD_GAME -> "You can buy $t online or at a local game store" + (if (has("USED")) ", or used" else "") + ". $card"
        }
    }

    /** The newest released one and the next one ("The newest Zelda game is Tears of the Kingdom, from May 2023."). */
    fun latestLine(set: WorkSet, kind: WorkKind, next: Boolean, today: LocalDate): String {
        val label = set.name
        val what = if (set.bySeries) "$label ${kind.one}" else "${kind.one} ${if (kind == WorkKind.BOOK) "by" else "from"} $label"
        fun whenOf(d: LocalDate) = if (d.dayOfYear == 1) d.year.toString() else d.format(MONTH)
        val last = set.released(today).lastOrNull()
        val coming = set.upcoming(today).firstOrNull()
        val sb = StringBuilder()
        if (last != null && !(next && coming != null)) sb.append("The newest $what is ${last.title}, from ${whenOf(last.date!!)}. ")
        coming?.let { c ->
            val first = if (sb.isEmpty()) "The next $what" else "The next one"
            sb.append(if (c.date!!.dayOfYear == 1) "$first, ${c.title}, is due in ${c.date.year}. " else "$first, ${c.title}, comes out ${c.date.format(DAY)}. ")
        }
        if (coming == null && next) sb.append("I don't know of a new one on the way yet.")
        if (last == null && coming == null) return "I couldn't find a $what with a release date."
        return sb.toString().trim()
    }

    /** "Which Zelda game? The newest is Tears of the Kingdom; others are Breath of the Wild (2017) ..." */
    fun whichLine(set: WorkSet, kind: WorkKind, out: List<Work>): String {
        val shown = out.takeLast(4).reversed().joinToString(", ") { w -> w.title + (w.date?.let { " (${it.year})" } ?: "") }
        return "Which ${set.name} ${kind.one}? The newest ones are $shown${if (out.size > 4) ", or an older one" else ""}. Or say \"the newest\"."
    }

    // ------------------------------------------------------------------ cards

    private fun sub(w: WorkInfo) = listOfNotNull(w.year?.toString(), w.makers.take(2).joinToString(", ").ifEmpty { null },
        w.platformNames.take(6).joinToString(", ").ifEmpty { null }).joinToString(" · ")

    fun shopsCard(heading: String, w: WorkInfo, shops: List<Shop>, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(w.title), max = 2)
        sub(w).takeIf { it.isNotBlank() }?.let { rows += Box.wrap(it, max = 3) }
        for ((section, list) in shops.groupBy { it.section }) {
            rows += ""
            rows += section
            for (s in list) rows += Box.wrap("${if (s.sale) "!" else if (s.price != null) "*" else "?"} ${s.name}" + (s.price?.let { "  $it" } ?: "") + (s.note?.let { "  ($it)" } ?: ""), "  ", 4, 2)
        }
        rows += ""
        rows += Box.kv("KEY", if (shops.any { it.sale }) "! on sale  * price  ? check there" else "* price checked  ? check there")
        return Readout(Box.build(heading, rows), image = w.image, controls = MediaCards.sourceButtons(shops.map { it.name to it.url }, open))
    }

    fun ratingCard(w: WorkInfo, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(w.title), max = 2)
        sub(w).takeIf { it.isNotBlank() }?.let { rows += Box.wrap(it, max = 3) }
        rows += ""
        fun bar(p: Double) = Box.bar(((p + 5) / 10).toInt(), 10)
        val links = ArrayList<Pair<String, String>>()
        when (w.kind) {
            WorkKind.GAME -> {
                for ((who, v, how) in w.scores) {
                    val n = Ranked.normalize(v)
                    rows += Box.kv(who.take(12), (n?.let { bar(it) + " " } ?: "") + v + (how?.let { "  " + it.lowercase(Locale.US).take(22) } ?: ""))
                }
                if (w.scores.isEmpty()) rows += "No review scores listed for this one."
                links += "Metacritic" to metacriticSearch(w.title)
                links += "OpenCritic" to "https://opencritic.com/search?criteria=${e(w.title)}"
                if (w.steamId != null) links += "Steam" to "https://store.steampowered.com/app/${w.steamId}"
            }
            WorkKind.BOOK -> {
                w.olRating?.let { rows += Box.kv("OPEN LIBRARY", bar(it * 20) + " %.1f/5 (%d ratings)".format(Locale.US, it, w.olCount)) }
                    ?: run { rows += "No reader ratings listed for this one." }
                links += "Goodreads" to "https://www.goodreads.com/search?q=${e(w.title)}"
                links += "StoryGraph" to "https://app.thestorygraph.com/browse?search_term=${e(w.title)}"
                w.olKey?.let { links += "Open Library" to "https://openlibrary.org$it" }
            }
            WorkKind.BOARD_GAME -> {
                val s = w.bgg
                if (s == null) rows += Box.wrap("Ratings need a free BoardGameGeek token: Settings > Games and books.", max = 3)
                else {
                    s.average?.let { rows += Box.kv("BGG", bar(it * 10) + " %.1f/10".format(Locale.US, it) + (s.voters?.let { v -> " ($v votes)" } ?: "")) }
                    s.rank?.let { rows += Box.kv("RANK", "#$it of all board games") }
                    if (s.minPlayers != null) rows += Box.kv("PLAYERS", if (s.minPlayers == s.maxPlayers) "${s.minPlayers}" else "${s.minPlayers}-${s.maxPlayers}")
                    s.minutes?.let { rows += Box.kv("TIME", "about $it min") }
                    s.weight?.let { rows += Box.kv("WEIGHT", "%.1f/5 ".format(Locale.US, it) + when { it < 2 -> "(light)"; it < 3 -> "(medium)"; it < 4 -> "(heavy-ish)"; else -> "(heavy)" }) }
                }
                links += "BoardGameGeek" to (w.bggId?.let { "https://boardgamegeek.com/boardgame/$it" } ?: "https://boardgamegeek.com/geeksearch.php?action=search&objecttype=boardgame&q=${e(w.title)}")
            }
        }
        w.wiki?.let { links += "Wikipedia" to it }
        rows += ""
        rows += Box.kv("SOURCE", when (w.kind) { WorkKind.GAME -> "Wikidata"; WorkKind.BOOK -> "Open Library"; WorkKind.BOARD_GAME -> "BoardGameGeek" })
        return Readout(Box.build("RATINGS", rows), image = w.image, controls = MediaCards.sourceButtons(links, open))
    }
}
