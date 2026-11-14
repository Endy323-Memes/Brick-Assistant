package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** Which part of a ranked list was asked for. */
enum class Tier(val word: String) { GOOD("good"), MEDIOCRE("so-so"), BAD("bad"), ALL("") }

/** What's being ranked, and what its filter means (a franchise, an artist, an outlet). */
enum class RankKind(val one: String, val many: String) {
    MOVIE("movie", "movies"), SHOW("show", "shows"), SONG("song", "songs"), NEWS("story", "stories"),
    GAME("game", "games"), BOARD_GAME("board game", "board games"), BOOK("book", "books"),
}

/** One rated thing on a list: its 0..100 score, how that score reads ("RT 87% · MC 74"), and where to open it. */
data class Rated(val title: String, val sub: String?, val score: Double, val shown: String, val link: String?, val tier: Tier = Tier.ALL)

/**
 * Good, so-so and bad movies, shows, songs and news: "best godzilla movies", "worst star trek shows", "mid taylor
 * swift songs", "give me some good news from the verge", "rank the alien movies".
 *
 * Movies go by critics (Rotten Tomatoes and Metacritic, from Wikidata; TMDB viewers with a key), shows by TVmaze
 * viewers, songs by how much they're played (Deezer: songs have no public ratings), news by the tone of the headline.
 */
object Ranked {
    // ------------------------------------------------------------------ understanding the request

    private val NEWS_KIND = Patterns.get("\\b(?:news|articles?|headlines?|reporting|coverage|news stories)\\b")
    private val MOVIE_KIND = Patterns.get("\\b(?:movies?|films?|flicks?|cinema|blockbusters?|sequels?)\\b")
    private val SHOW_KIND = Patterns.get("\\b(?:tv shows?|shows?|tv series|series|tv|television|sitcoms?|dramas?|anime|cartoons?|miniseries)\\b")
    private val SONG_KIND = Patterns.get("\\b(?:songs?|tracks?|music|singles?|tunes?|bangers?|records|jams|hits)\\b")
    private val STORY_KIND = Patterns.get("\\b(?:stories|story)\\b")
    /** Game and book words, left out of a game or book list's filter. */
    private val WORK_KIND = Patterns.get("\\b(?:video ?games?|videogames?|games?|gaming|titles|rpgs?|jrpgs?|exclusives?|board ?games?|boardgames?|card games?|tabletop(?: games?)?|books?|novels?|novellas?|audiobooks?|reads|book series|series|trilogy|memoirs?|fiction|non-?fiction|written|developed|published|designed|made|released|came out|available|playable)\\b")

    /** Asking for the whole list, ranked. */
    private val ALL_WORDS = Patterns.get("\\b(?:rank|ranked|ranking|rankings|tier list|best to worst|worst to best|from best|from worst|good and (?:the )?bad|good or bad|good to bad|bad to good|rate (?:all|every|each)|ratings? (?:of|for) (?:all|every|each)|how (?:good|bad) (?:are|were)|sorted|in order of)\\b")
    private val MID_WORDS = Patterns.get("\\b(?:not bad|not great|not terrible|mediocre|okay|ok|average|mid|meh|so so|so-so|middling|decent|alright|all right|fine|mixed|forgettable|passable|neutral|neither good nor bad|in the middle|middle of the road|lukewarm|ordinary|boring|nothing special|run of the mill|watchable|listenable)\\b")
    private val BAD_WORDS = Patterns.get("\\b(?:bad|worst|worse|terrible|awful|horrible|horrid|garbage|trash|trashy|lowest rated|lowest-rated|low rated|poorly rated|badly rated|badly reviewed|poorly reviewed|panned|flops?|duds?|overrated|skip|skippable|to avoid|avoid|not worth|sucks?|crap|crappy|weakest|negative|depressing|sad|grim|disappointing|disappointments?|not good|unwatchable|cringe|cringey|stinkers?|rotten|bombs?|upsetting|scary|grim|tragic|bleak)\\b")
    private val GOOD_WORDS = Patterns.get("\\b(?:good|great|best|better|top|top rated|top-rated|highest rated|highest-rated|well reviewed|well rated|critically acclaimed|acclaimed|worth|must see|must-see|must watch|must-watch|must listen|recommended|recommend|recommendations?|favorites?|favourites?|excellent|amazing|awesome|solid|fire|bangers?|hits|biggest hits|greatest|positive|uplifting|happy|cheerful|feel good|feel-good|heartwarming|wholesome|hopeful|encouraging|strongest|underrated|classics?|masterpieces?|peak|goated|goat|legendary|fantastic|incredible|brilliant|fresh|certified fresh|nice|cool|fun)\\b")
    /** A singular kind counts with these ("the best godzilla movie", "recommend a good show"). */
    private val LIST_CUE = Patterns.get("\\b(?:list|lists|some|any|few|couple|which|what are|what were|name|names|recommend|recommendations?|suggest|suggestions?|ideas?|give me|show me|tell me|best|worst|top|greatest|highest|lowest|all|every|ranked|rank|ranking)\\b")
    private val NOT_A_LIST = Patterns.get("^(?:play|put on|queue|shuffle|open|launch|start|text|send|call|remind|set|add|turn|skip to|stop)\\b|\\b(?:where (?:can|do|to|is)|buy|rent|lyrics|trailer|showtimes?|tickets?)\\b")
    /** "top 5", "top ten". */
    private val COUNT = Patterns.get("\\b(?:top|best|worst|bottom|first)\\s+(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty)\\b|\\b(\\d{1,2}|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty) (?:of the )?(?:best|worst|good|great|bad|top)\\b")
    private val NUMBERS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "twelve" to 12, "fifteen" to 15, "twenty" to 20)

    /** Words left out when finding the franchise, artist or outlet. */
    private val FILLER = Regex(
        "\\b(?:what'?s|what is|what are|what were|whats|which are|which is|which|what|who|tell me|give me|show me|get me|find me|find|list|lists|list of|list out|name|names|name some|" +
            "i want|i'd like|i would like|i need|can you|could you|would you|will you|do you know|do you have|you got|got any|are there|is there|there|are|is|were|was|" +
            "recommend|recommendations?|suggest|suggestions?|ideas?|some|any|a few|few|couple|a couple of|of|a|an|the|me|my|us|for|to|please|brick|hey|ok|okay so|so|" +
            "all|every|each|ever|of all time|all time|ever made|made|right now|now|currently|today|this week|this year|lately|recently|recent|out there|out|these days|" +
            "to watch|to see|to listen to|to hear|to read|to stream|watching|listening|reading|worth watching|worth seeing|worth listening to|worth reading|worth|" +
            "rated|rating|ratings|reviewed|reviews|critics|according to critics|on rotten tomatoes|rotten tomatoes|imdb|metacritic|by critics|" +
            "rank|ranked|ranking|rankings|tier list|sorted|in order|order|from|and|or|that are|that were|that is|which are|ones|one|in|on|by|at|about|regarding|" +
            "franchise|series|saga|universe|cinematic universe|collection|discography|catalog|catalogue|career|entries|entry|installments?|" +
            "biggest|most popular|popular|famous|well known|known|most played|least played|most|least|highest|lowest|" +
            "how good|how bad|good|bad|watch|see|listen|hear|read|stream|check out|binge|play)\\b",
    )

    /** "best godzilla movies", "worst star trek shows", "mid taylor swift songs", "good news from the verge", "rank the alien films". */
    fun parse(s: String): Request.Ranked? {
        val t = s.lowercase(Locale.US).replace('’', '\'').replace(Patterns.get("[?!.,]"), " ").replace(Patterns.get("\\s+"), " ").trim()
            // "Okay, ..." and "show me ..." aren't "okay movies" or TV shows.
            .replace(Patterns.get("^(?:(?:hey )?brick|ok|okay|alright|all right|so|um|uh|yo)\\s+"), "")
            .replace(Patterns.get("\\bshow (me|us)\\b"), "give $1")
        if (t.split(' ').size > 18 || NOT_A_LIST.containsMatchIn(t)) return null
        // Telling, not asking ("i have bad news", "that's good news").
        if (Patterns.get("^(?:i|i've|i have|i got|we|we've|that's|thats|that is|it's|its|this is|what a|such)\\b").containsMatchIn(t)) return null
        val work = Works.kindOf(t)
        // "Book series" and "game series" aren't TV series.
        val showText = if (work != null) t.replace(Patterns.get("\\b(?:book|novel|game|video game) series\\b|\\bseries\\b"), " ") else t
        val kind = when {
            NEWS_KIND.containsMatchIn(t) -> RankKind.NEWS
            MOVIE_KIND.containsMatchIn(t) -> RankKind.MOVIE
            work == WorkKind.BOARD_GAME -> RankKind.BOARD_GAME
            SHOW_KIND.containsMatchIn(showText) && !Patterns.get("\\b(?:movie|film) series\\b").containsMatchIn(t) -> RankKind.SHOW
            work == WorkKind.BOOK -> RankKind.BOOK
            work == WorkKind.GAME -> RankKind.GAME
            SONG_KIND.containsMatchIn(t) -> RankKind.SONG
            STORY_KIND.containsMatchIn(t) && Patterns.get("\\b(?:good|bad|positive|negative|happy|sad|uplifting|depressing|wholesome|heartwarming)\\b").containsMatchIn(t) -> RankKind.NEWS
            else -> return null
        }
        val tier = tier(t, kind) ?: return null
        // "is dune good" is a rating question, not a list: a list needs more than one thing or a list word.
        val plural = when (kind) {
            RankKind.MOVIE -> Patterns.get("\\b(?:movies|films|flicks|blockbusters|sequels)\\b")
            RankKind.SHOW -> Patterns.get("\\b(?:shows|series|sitcoms|dramas|cartoons|tv|television|anime)\\b")
            RankKind.SONG -> Patterns.get("\\b(?:songs|tracks|music|singles|tunes|bangers|hits|records|jams)\\b")
            RankKind.NEWS -> Patterns.get(".")
            RankKind.GAME -> Patterns.get("\\b(?:games|titles|rpgs|jrpgs|shooters|platformers|metroidvanias|roguelikes|exclusives)\\b")
            RankKind.BOARD_GAME -> Patterns.get("\\b(?:games|boardgames)\\b")
            RankKind.BOOK -> Patterns.get("\\b(?:books|novels|novellas|reads|audiobooks|series|memoirs|comics|manga)\\b")
        }
        if (!plural.containsMatchIn(t) && !LIST_CUE.containsMatchIn(t) && tier != Tier.ALL) return null
        // "Is the show the bear any good?" asks about one thing: its rating, not a list.
        if (kind != RankKind.NEWS && Patterns.get("^(?:is|was|isn't|wasn't) ").containsMatchIn(t)) return null
        val count = COUNT.find(t)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let { it.toIntOrNull() ?: NUMBERS[it] } }
        // Yes/no about a whole set ("are the alien movies good?"): show all of them, ranked.
        val yesNo = Patterns.get("^(?:are|were|is|was) (?!there\\b)").containsMatchIn(t) && count == null
        // Games: devices, stores and years come out first ("best zelda games on switch from 2023").
        if (kind == RankKind.GAME) {
            val (gf, rest) = Works.gameFilter(t)
            return Request.Ranked(kind, if (yesNo) Tier.ALL else tier, filter(rest, kind), count?.coerceIn(1, 20), gf.takeUnless { it.isEmpty })
        }
        return Request.Ranked(kind, if (yesNo) Tier.ALL else tier, filter(t, kind), count?.coerceIn(1, 20))
    }

    private fun tier(t: String, kind: RankKind): Tier? {
        if (ALL_WORDS.containsMatchIn(t)) return Tier.ALL
        // "Not bad" and "not great" are the middle; take them out before looking for good and bad.
        val mid = MID_WORDS.containsMatchIn(t)
        val rest = t.replace(MID_WORDS, " ")
        val bad = BAD_WORDS.containsMatchIn(rest)
        // For news "top stories" is just the news, not good news.
        val goodRx = if (kind == RankKind.NEWS) Patterns.get("\\b(?:good|great|positive|uplifting|happy|cheerful|feel good|feel-good|heartwarming|wholesome|hopeful|encouraging|nice|best)\\b") else GOOD_WORDS
        val good = goodRx.containsMatchIn(rest)
        val count = listOf(mid, bad, good).count { it }
        return when {
            count > 1 -> Tier.ALL
            mid -> Tier.MEDIOCRE
            bad -> Tier.BAD
            good -> Tier.GOOD
            else -> null
        }
    }

    /** The franchise, artist or outlet the list is narrowed to, or null for "anything". */
    fun filter(t: String, kind: RankKind): String? {
        var r = " $t "
        if (kind == RankKind.NEWS) {
            Publications.find(t)?.let { return it.first.name }
            Publications.named(t)?.let { return it.first.name }
        }
        // "songs by drake", "music from daft punk": the artist is what follows.
        Patterns.get("\\b(?:by|from)\\s+(.+)$").find(t)?.let { m ->
            val who = clean(" ${m.groupValues[1]} ")
            if (who != null && kind != RankKind.NEWS) return who
        }
        r = r.replace(Patterns.get("'s\\b"), " ")
        val lists = if (kind == RankKind.GAME || kind == RankKind.BOOK || kind == RankKind.BOARD_GAME) listOf(WORK_KIND) else listOf(NEWS_KIND, MOVIE_KIND, SHOW_KIND, SONG_KIND, STORY_KIND)
        for (rx in lists + listOf(ALL_WORDS, MID_WORDS, BAD_WORDS, GOOD_WORDS, COUNT)) r = r.replace(rx, " ")
        return clean(r)
    }

    private fun clean(s: String): String? {
        var r = s
        repeat(3) { r = r.replace(FILLER, " ") }
        r = r.replace(Patterns.get("\\b\\d{1,2}\\b"), " ").replace(Patterns.get("\\s+"), " ").trim()
        return r.ifEmpty { null }?.takeIf { it.length > 1 }
    }

    // ------------------------------------------------------------------ scores

    /** One review score on Wikidata: the site ([by]), how it measured ([method]), the value ("87%"), and when ([at]). */
    data class Fact(val by: String, val method: String?, val value: String, val at: String = "")

    private const val ROTTEN_TOMATOES = "Q105584"
    private const val TOMATOMETER = "Q108403393"
    private const val RT_AUDIENCE = "Q108403540"
    private const val METACRITIC = "Q150248"
    private const val IMDB = "Q37312"

    /** "87%" -> 87, "74/100" -> 74, "8,1/10" -> 81, "4.5/5" -> 90. */
    fun normalize(v: String): Double? {
        val s = v.replace(',', '.').trim()
        Patterns.get("^(\\d+(?:\\.\\d+)?)\\s*%$").find(s)?.let { return it.groupValues[1].toDouble() }
        Patterns.get("^(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)$").find(s)?.let {
            val b = it.groupValues[2].toDouble()
            if (b > 0) return it.groupValues[1].toDouble() / b * 100
        }
        return null
    }

    /**
     * A movie's score from its review facts: the critics (Rotten Tomatoes' Tomatometer and Metacritic, averaged) when
     * listed, else IMDb or Rotten Tomatoes' audience. Null with none.
     */
    fun combine(facts: List<Fact>): Pair<Double, String>? {
        fun newest(f: (Fact) -> Boolean) = facts.filter(f).sortedByDescending { it.at }.firstNotNullOfOrNull { normalize(it.value) }
        val rt = newest { it.by == ROTTEN_TOMATOES && (it.method == TOMATOMETER || it.method == null && it.value.trim().endsWith("%")) }
        val mc = newest { it.by == METACRITIC }
        val critics = listOfNotNull(rt, mc)
        if (critics.isNotEmpty()) {
            val shown = listOfNotNull(rt?.let { "RT ${it.roundToInt()}%" }, mc?.let { "MC ${it.roundToInt()}" }).joinToString(" · ")
            return critics.average() to shown
        }
        newest { it.by == IMDB }?.let { return it to "IMDb %.1f".format(Locale.US, it / 10) }
        newest { it.by == ROTTEN_TOMATOES && it.method == RT_AUDIENCE }?.let { return it to "RT audience ${it.roundToInt()}%" }
        return null
    }

    /** Good, so-so or bad by a 0..100 score: movies by critics, shows by viewers (who rate higher). */
    fun tierOf(kind: RankKind, score: Double): Tier = when (kind) {
        RankKind.SHOW -> if (score >= 75) Tier.GOOD else if (score >= 65) Tier.MEDIOCRE else Tier.BAD
        else -> if (score >= 70) Tier.GOOD else if (score >= 50) Tier.MEDIOCRE else Tier.BAD
    }

    /** Thirds by play count (songs): the most played third is "good", the least played "bad". */
    fun byThirds(items: List<Rated>): List<Rated> {
        val sorted = items.sortedByDescending { it.score }
        val n = sorted.size
        return sorted.mapIndexed { i, r -> r.copy(tier = if (i < (n + 2) / 3) Tier.GOOD else if (i < (2 * n + 2) / 3) Tier.MEDIOCRE else Tier.BAD) }
    }

    // ------------------------------------------------------------------ news tone

    private val POSITIVE = Patterns.get("\\b(?:wins?|won|winning|winner|victory|rescued?|rescues|saves?|saved|saving|cure|cured|cures|breakthrough|breakthroughs|celebrates?|celebrated|celebration|reunited?|reunion|recovers?|recovered|recovery|rebounds?|rises|rising|surges?|soars?|soaring|gains?|boosts?|boosted|hope|hopeful|heartwarming|donates?|donated|donation|awards?|awarded|honou?red|honou?rs|discovers?|discovered|discovery|success|successful|succeeds|beats|approves?|approved|launch(?:es|ed)?|peace|ceasefire|truce|agreement|deal|reached|restored?|reopens?|opens|thriving|thrives|best|first ever|milestone|historic|unveils|improves?|improved|improving|growth|grows|hired|hiring|adopted|adoption|volunteers?|kindness|generous|inspiring|joy|happy|wholesome|smiles?|thanks|released from|freed|survives?|survived|survivor|returns? home|good news|bright|clean energy|protects?|protected|conservation|endangered species rebound)\\b", true)
    private val NEGATIVE = Patterns.get("\\b(?:dies|died|dead|death|deaths|deadly|kills?|killed|killing|murder(?:ed|s)?|shooting|shot|gunman|stabbing|crash(?:es|ed)?|war|wars|attack(?:s|ed)?|strikes?|bomb(?:s|ing|ed)?|explosion|fire|fires|wildfire|blaze|flood(?:s|ing)?|storm|hurricane|tornado|earthquake|quake|arrest(?:s|ed)?|charged|indicted|lawsuit|sues|sued|fraud|scandal|crisis|layoffs?|laid off|cuts|slashes|falls|fell|plunges?|plunged|slumps?|tumbles?|recession|warns?|warning|threats?|threatens?|injured|injuries|missing|collapse[sd]?|bans?|banned|fined|protests?|riots?|violence|violent|outbreak|recalls?|recalled|hack(?:ed|ers)?|breach|fired|resigns?|convicted|guilty|sentenced|loses|lost|losing|delayed|delays|cancel(?:s|ed|led)|shutdown|tariffs?|abuse|victims?|hostages?|terror|terrorist|sanctions|fears?|feared|panic|chaos|disaster|tragedy|tragic|mourns?|funeral|suicide|overdose|famine|drought|poverty|eviction|bankrupt(?:cy)?|debt|deficit|inflation|scam|leak|exposed|accused|alleged|probe|investigation|controversy|backlash|slams?|blasts?|feud|condemns?|bad news|worst|\\d+-year low|record-high (?:prices|rates|costs)|price hikes?|hikes?|soaring (?:prices|costs|rents))\\b", true)

    /** How a headline reads: above 0 good news, below 0 bad, 0 neither (or both). */
    fun tone(title: String): Int = POSITIVE.findAll(title).count() - NEGATIVE.findAll(title).count()

    /** Good news has good words and no bad ones, bad news the other way round; mixed or plain headlines are neither. */
    fun newsTier(title: String): Tier {
        val pos = POSITIVE.findAll(title).count()
        val neg = NEGATIVE.findAll(title).count()
        return if (pos > 0 && neg == 0) Tier.GOOD else if (neg > 0 && pos == 0) Tier.BAD else Tier.MEDIOCRE
    }

    // ------------------------------------------------------------------ fetching

    /** Rotten Tomatoes, Metacritic and IMDb scores for these Wikidata items (one query). */
    private fun scoresFor(qids: List<String>): Map<String, Pair<Double, String>> {
        if (qids.isEmpty()) return emptyMap()
        val values = qids.distinct().take(60).joinToString(" ") { "wd:$it" }
        val q = "SELECT ?f ?v ?by ?m ?at WHERE { VALUES ?f { $values } ?f p:P444 ?s . ?s ps:P444 ?v . ?s pq:P447 ?by . " +
            "OPTIONAL { ?s pq:P459 ?m } OPTIONAL { ?s pq:P585 ?at } }"
        val url = "https://query.wikidata.org/sparql?format=json&query=${Http.enc(q)}"
        val rows = (Http.getJsonCached(url) ?: run { Thread.sleep(1200); Http.getJsonCached(url) })
            ?.optJSONObject("results")?.optJSONArray("bindings") ?: return emptyMap()
        fun id(o: org.json.JSONObject, k: String) = o.optJSONObject(k)?.optString("value")?.substringAfterLast('/')
        val facts = (0 until rows.length()).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val f = id(o, "f") ?: return@mapNotNull null
            f to Fact(id(o, "by") ?: return@mapNotNull null, id(o, "m"), o.optJSONObject("v")?.optString("value") ?: return@mapNotNull null,
                o.optJSONObject("at")?.optString("value").orEmpty())
        }
        return facts.groupBy({ it.first }, { it.second }).mapNotNull { (f, list) -> combine(list)?.let { f to it } }.toMap()
    }

    private fun rtSearch(title: String) = "https://www.rottentomatoes.com/search?search=${Http.enc(title)}"

    /** A franchise's released movies, rated. Empty when the franchise isn't found. */
    suspend fun movies(filter: String?, tmdbKey: String, today: LocalDate = LocalDate.now()): List<Rated> = withContext(Dispatchers.IO) {
        val films: List<Film> = if (filter != null) {
            Franchise.members(filter, Franchise.films(filter, tmdbKey)).filter { it.date != null && !it.date.isAfter(today) }
        } else boxOffice(today)
        val scores = scoresFor(films.mapNotNull { it.qid })
        films.mapNotNull { f ->
            val (score, shown) = f.qid?.let { scores[it] } ?: f.rating?.let { it * 10 to "TMDB %.1f".format(Locale.US, it) } ?: return@mapNotNull null
            Rated(f.title, f.year?.toString(), score, shown, rtSearch(f.title), tierOf(RankKind.MOVIE, score))
        }.distinctBy { it.title.lowercase(Locale.US) + it.sub }
    }

    /** No franchise named: this year's and last year's biggest movies (Wikipedia's "<year> in film" box office tables). */
    private fun boxOffice(today: LocalDate): List<Film> {
        val api = "https://en.wikipedia.org/w/api.php?format=json&formatversion=2"
        val titles = LinkedHashMap<String, Int>()
        for (year in listOf(today.year, today.year - 1)) {
            val text = Http.getJsonCached("$api&action=parse&prop=wikitext&section=1&page=${year}_in_film")
                ?.optJSONObject("parse")?.optString("wikitext") ?: continue
            val table = text.substringBefore("\n|}")
            for (m in Patterns.get("''\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?\\]\\]''").findAll(table).take(10)) titles.putIfAbsent(m.groupValues[1], year)
            Thread.sleep(300)
        }
        if (titles.isEmpty()) return emptyList()
        val pages = Http.getJsonCached("$api&action=query&redirects=1&prop=pageprops&ppprop=wikibase_item&titles=${Http.enc(titles.keys.joinToString("|"))}")
            ?.optJSONObject("query") ?: return emptyList()
        // Redirected titles ("Obsession (2025 film)") keep their year from the table.
        val redirects = HashMap<String, String>()
        pages.optJSONArray("redirects")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { redirects[it.optString("to")] = it.optString("from") } }
        val arr = pages.optJSONArray("pages") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val page = o.optString("title")
            val qid = o.optJSONObject("pageprops")?.optString("wikibase_item")?.ifBlank { null } ?: return@mapNotNull null
            val year = titles[page] ?: titles[redirects[page]] ?: return@mapNotNull null
            Film(page.replace(Patterns.get("\\s*\\([^)]*(?:film|movie)\\)$"), ""), LocalDate.of(year, 1, 1), precise = false, page = page, qid = qid)
        }
    }

    /** Shows rated by TVmaze viewers: those named like [filter] ("star trek"), or what's airing and streaming now. */
    suspend fun shows(filter: String?, today: LocalDate = LocalDate.now()): List<Rated> = withContext(Dispatchers.IO) {
        val found = ArrayList<org.json.JSONObject>()
        if (filter != null) {
            val arr = Http.getJsonArray("https://api.tvmaze.com/search/shows?q=${Http.enc(filter)}")
            for (i in 0 until (arr?.length() ?: 0)) arr!!.optJSONObject(i)?.optJSONObject("show")?.let { found += it }
            fun words(s: String) = Franchise.core(s).replace(":", "").split(' ').filter { it.isNotBlank() }
            val want = words(filter)
            val named = found.filter { s -> words(s.optString("name")).containsAll(want) }
            if (named.isNotEmpty()) { found.clear(); found += named }
        } else {
            for (url in listOf("https://api.tvmaze.com/schedule/web?date=$today&country=US", "https://api.tvmaze.com/schedule?date=$today&country=US",
                "https://api.tvmaze.com/schedule/web?date=$today")) {
                val arr = Http.getJsonArray(url) ?: continue
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    (ep.optJSONObject("show") ?: ep.optJSONObject("_embedded")?.optJSONObject("show"))?.let { found += it }
                }
            }
            // Shows people follow (TVmaze's weight), not every local news hour.
            found.retainAll { it.optInt("weight") >= 70 && it.optString("type") !in setOf("News", "Talk Show", "Sports", "Game Show") }
        }
        found.distinctBy { it.optInt("id") }.mapNotNull { s ->
            val r = s.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() } ?: return@mapNotNull null
            val year = s.optString("premiered").take(4).ifBlank { null }
            val net = (s.optJSONObject("webChannel") ?: s.optJSONObject("network"))?.optString("name")
            Rated(s.optString("name"), listOfNotNull(year, net).joinToString(", ").ifEmpty { null }, r * 10, "TVmaze %.1f".format(Locale.US, r),
                s.optString("url").ifBlank { null }, tierOf(RankKind.SHOW, r * 10))
        }
    }

    /** An artist's songs (or today's chart) by how much they're played on Deezer, in thirds. Null name = the chart. */
    suspend fun songs(artist: String?): Pair<String?, List<Rated>> = withContext(Dispatchers.IO) {
        var name: String? = null
        val arr = if (artist != null) {
            val a = Http.getJsonCached("https://api.deezer.com/search/artist?q=${Http.enc(artist)}&limit=1")?.optJSONArray("data")?.optJSONObject(0)
                ?: return@withContext null to emptyList<Rated>()
            name = a.optString("name")
            Http.getJsonCached("https://api.deezer.com/artist/${a.optLong("id")}/top?limit=60")?.optJSONArray("data")
        } else Http.getJsonCached("https://api.deezer.com/chart/0/tracks?limit=60")?.optJSONArray("data")
        val tracks = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.map { o ->
            val title = o.optString("title_short").ifBlank { o.optString("title") }
            val by = o.optJSONObject("artist")?.optString("name")
            Rated(title, if (artist == null) by else o.optJSONObject("album")?.optString("title"), o.optDouble("rank", 0.0), "", o.optString("link").ifBlank { null })
        }.distinctBy { it.title.lowercase(Locale.US).replace(Patterns.get("\\s*[(\\[-].*$"), "") }
        val max = tracks.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        name to byThirds(tracks.map { it.copy(score = it.score / max * 100, shown = "popularity ${(it.score / max * 100).roundToInt()}") })
    }

    /** Headlines by tone, from one outlet when named. */
    suspend fun news(source: String?, tier: Tier = Tier.ALL): List<Rated> {
        val pub = source?.let { s -> Publications.find(s.lowercase(Locale.US))?.first ?: Publication(s, null) }
        val now = java.time.ZonedDateTime.now()
        var items = News.fetch(NewsTopic.TOP, null, now, pub)
        // Good news is thin in the top stories: add a search for uplifting ones (any outlet).
        if (tier == Tier.GOOD && pub == null) items = (News.fetch(NewsTopic.TOP, "heartwarming OR uplifting OR rescued OR breakthrough OR \"good news\"", now) + items)
            .distinctBy { it.title.lowercase(Locale.US) }
        return items.map { h ->
            val t = tone(h.title)
            Rated(h.title, listOfNotNull(h.source.takeIf { pub == null }, News.ago(h.published, now)).joinToString(" · ").ifEmpty { null },
                50.0 + t * 10, "", h.link, newsTier(h.title))
        }
    }

    // ------------------------------------------------------------------ answers

    /** "good Godzilla movies", "so-so Taylor Swift songs", "bad news". */
    fun heading(kind: RankKind, tier: Tier, filter: String?, game: GameFilter? = null): String {
        val what = when (kind) {
            RankKind.NEWS -> if (tier == Tier.ALL) "news" else "${tier.word} news"
            // "good Zelda games on Switch from 2023"
            RankKind.GAME -> (listOfNotNull(tier.word.ifEmpty { null }, filter?.let { Franchise.pretty(it) }, kind.many) +
                listOfNotNull(game?.describe()?.ifEmpty { null })).joinToString(" ")
            else -> listOfNotNull(tier.word.ifEmpty { null }, if (kind == RankKind.SONG) null else filter?.let { Franchise.pretty(it) }, kind.many).joinToString(" ") +
                if (kind == RankKind.SONG && filter != null) " by ${Franchise.pretty(filter)}" else ""
        }
        return if (kind == RankKind.NEWS && filter != null) "$what from $filter" else what
    }

    /** Where the scores come from, said once. */
    fun basis(kind: RankKind, items: List<Rated>): String = when (kind) {
        RankKind.MOVIE -> when {
            items.any { it.shown.startsWith("RT") || it.shown.startsWith("MC") } -> "going by Rotten Tomatoes and Metacritic critics"
            items.any { it.shown.startsWith("TMDB") } -> "going by TMDB viewers"
            else -> "going by IMDb viewers"
        }
        RankKind.SHOW -> "going by TVmaze viewer ratings"
        RankKind.SONG -> "Songs don't get public ratings, so this goes by how much they're played on Deezer"
        RankKind.NEWS -> "going by how the headlines read"
        RankKind.GAME -> if (items.all { it.shown.startsWith("MC") }) "going by Metacritic critics" else "going by OpenCritic and Metacritic critics"
        RankKind.BOOK -> "going by Open Library readers"
        RankKind.BOARD_GAME -> "going by BoardGameGeek ratings"
    }

    /** The spoken answer. [all] is everything found; the list asked for is the [tier] part of it. */
    fun line(kind: RankKind, tier: Tier, filter: String?, all: List<Rated>, count: Int?, game: GameFilter? = null): String {
        val label = heading(kind, tier, filter, game)
        if (all.isEmpty()) return when (kind) {
            RankKind.GAME -> "I couldn't find rated ${heading(kind, Tier.ALL, filter, game)}."
            RankKind.BOOK -> if (filter != null) "I couldn't find rated books for ${Franchise.pretty(filter)}." else "I couldn't get book ratings right now."
            RankKind.BOARD_GAME -> "I couldn't find rated ${heading(kind, Tier.ALL, filter)}."
            RankKind.MOVIE -> if (filter != null) "I couldn't find rated ${Franchise.pretty(filter)} movies." else "I couldn't get movie scores right now."
            RankKind.SHOW -> if (filter != null) "I couldn't find rated shows called ${Franchise.pretty(filter)}." else "I couldn't get TV ratings right now."
            RankKind.SONG -> if (filter != null) "I couldn't find songs by ${Franchise.pretty(filter)}." else "I couldn't get the music chart right now."
            RankKind.NEWS -> if (filter != null) "I couldn't find recent stories from $filter." else "I couldn't get the news right now."
        }
        val picked = pick(kind, tier, all, count)
        val basis = basis(kind, all)
        val names = picked.take(if (kind == RankKind.NEWS) 3 else 5)
        fun say(r: Rated) = when (kind) {
            RankKind.NEWS -> r.title.trimEnd('.')
            RankKind.SONG -> r.title + (if (filter == null && r.sub != null) " by ${r.sub}" else "")
            else -> r.title + (r.sub?.takeIf { kind == RankKind.MOVIE && it.length == 4 && all.count { o -> o.title == r.title } > 1 }?.let { " ($it)" } ?: "")
        }
        fun join(xs: List<String>) = when (xs.size) { 0 -> ""; 1 -> xs[0]; 2 -> "${xs[0]} and ${xs[1]}"; else -> xs.dropLast(1).joinToString(", ") + ", and " + xs.last() }
        if (tier == Tier.ALL) {
            val g = all.count { it.tier == Tier.GOOD }
            val m = all.count { it.tier == Tier.MEDIOCRE }
            val b = all.count { it.tier == Tier.BAD }
            val best = all.maxBy { it.score }
            val worst = all.minBy { it.score }
            val split = "$g good, $m so-so and $b bad"
            return if (kind == RankKind.NEWS) "Of the latest ${if (filter != null) "stories from $filter" else "headlines"}, $split, $basis. $CARD"
            else if (kind == RankKind.SONG) "Here are the ${heading(kind, Tier.ALL, filter, game)} ranked, ${basis.replaceFirstChar { it.lowercase() }}: $split. The best is ${say(best)}" +
                (if (best != worst) ", and the worst is ${say(worst)}." else ".")
            else "Here are the ${heading(kind, Tier.ALL, filter, game)} ranked, ${basis.replaceFirstChar { it.lowercase() }}: $split. The best is ${say(best)}. $CARD"
        }
        if (picked.isEmpty()) {
            val near = if (tier == Tier.BAD) all.minBy { it.score } else all.maxBy { it.score }
            return if (kind == RankKind.NEWS) "None of the latest headlines read as ${tier.word} news right now."
            else "None of the ${heading(kind, Tier.ALL, filter, game)} I found are ${tier.word}, ${basis.replaceFirstChar { it.lowercase() }}. The ${if (tier == Tier.BAD) "lowest" else "highest"} is ${say(near)}."
        }
        // Movies, shows, news, games and books: say how many and leave the list to the card.
        return if (kind == RankKind.NEWS) "Here's some $label: ${picked.size} ${if (picked.size == 1) "story" else "stories"}. $CARD"
        else if (kind == RankKind.SONG) "$basis. The ${if (tier == Tier.GOOD) "most played" else if (tier == Tier.BAD) "least played" else "middle of the pack"} ${if (filter != null) "${Franchise.pretty(filter)} songs" else "songs on the chart"} are ${join(names.map(::say))}."
        else "I found ${picked.size} $label, ${basis.replaceFirstChar { it.lowercase() }}. $CARD"
    }

    /** Said after a list instead of reading it out. */
    const val CARD = "They're on the card above."

    /** The [tier] part, best first (worst first for "bad"), cut to [count]. */
    fun pick(kind: RankKind, tier: Tier, all: List<Rated>, count: Int?): List<Rated> {
        val part = if (tier == Tier.ALL) all.sortedByDescending { it.score } else all.filter { it.tier == tier }
        val ordered = when {
            tier == Tier.BAD && kind != RankKind.NEWS -> part.sortedBy { it.score }
            tier == Tier.ALL || kind == RankKind.NEWS -> part
            else -> part.sortedByDescending { it.score }
        }
        return count?.let { ordered.take(it) } ?: ordered
    }
}
