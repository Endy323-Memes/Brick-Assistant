package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One movie in a series, with its release date (null when unknown). */
data class Film(
    val title: String,
    val date: LocalDate?,
    val popularity: Double = 0.0,
    /** In the series by TMDB's collection, even when the title doesn't say so ("Rogue One"). */
    val collection: Boolean = false,
    /** False when only the year is known. */
    val precise: Boolean = true,
    /** Its Wikipedia article ("Godzilla (2014 film)"), the exact thing to look up. */
    val page: String? = null,
    /** Its Wikidata item ("Q17738"), for review scores. */
    val qid: String? = null,
    /** TMDB viewers' rating out of 10, with a TMDB key. */
    val rating: Double? = null,
) {
    val year: Int? get() = date?.year
}

data class Season(val number: Int, val premiere: LocalDate?, val episodes: Int?)
data class ShowSeasons(val name: String, val seasons: List<Season>, val running: Boolean) {
    fun released(today: LocalDate) = seasons.filter { it.premiere != null && !it.premiere.isAfter(today) }
    fun upcoming(today: LocalDate) = seasons.filter { it.premiere != null && it.premiere.isAfter(today) }
}

/**
 * Series of movies ("Avengers", "Godzilla") and shows' seasons: the newest one, and "which one?" when a request
 * names a series rather than one movie.
 */
object Franchise {
    // ------------------------------------------------------------------ "what's the newest godzilla movie?"

    private val RECENT = Patterns.get("\\b(?:most recent|more recent|latest|newest|new|newer|last|recent|current|next|upcoming|coming|future|sequel|sequels|another|brand new|most current)\\b")
    private val NEXT = Patterns.get("\\b(?:next|upcoming|coming out|coming soon|future|sequel|sequels|another|will there be|is there going to be|gonna be|when (?:is|does|will) .* (?:come out|release|premiere|drop|be out|start))\\b")
    private val MOVIE_WORD = Patterns.get("\\b(?:movie|movies|film|films|flick|flicks|sequel|sequels|installment|instalment|entry|chapter|part)\\b")
    private val SHOW_WORD = Patterns.get("\\b(?:season|seasons|series|show|shows|tv show|episodes?|tv)\\b")
    /** Asking where to watch, buy or what it's rated: the media guide answers those (with the newest one). */
    private val MEDIA_VERB = Patterns.get("\\b(?:where|buy|rent|watch|stream|purchase|rating|rated|reviews?|rotten tomatoes|imdb|any good|worth|play|put on|download|lyrics|song|soundtrack|trailer|news|headlines)\\b")
    private val LATEST_FILLER = Regex(
        "\\b(?:what'?s|what is|what was|whats|which is|which|what|tell me|do you know|is there|are there|is|are|was|were|will there be|will|there|be|gonna|going to|" +
            "when|does|do|did|has|have|had|come out|came out|coming out|comes out|coming|released|release|releases|release date|premiere|premieres|premiered|drop|drops|dropped|start|starts|out|" +
            "the|a|an|of|in|from|for|on|to|about|any|yet|so far|currently|right now|now|soon|called|named|title|name|franchise|saga|series|universe|cinematic universe|" +
            "most recent|more recent|latest|newest|new|newer|last|recent|current|next|upcoming|future|another|brand new|most current|one|" +
            "movie|movies|film|films|flick|flicks|sequel|sequels|installment|instalment|entry|chapter|part|season|seasons|show|shows|tv show|tv|episodes?|" +
            "i|me|you|please|brick|hey|know|think|heard|gonna be)\\b",
    )

    /** "what's the newest godzilla movie", "new avengers movie?", "latest season of the bear", "when's the next dune movie". */
    fun parseLatest(s: String): Request.Latest? {
        val t = s.lowercase(Locale.US).replace('’', '\'').trim()
        if (!RECENT.containsMatchIn(t) || MEDIA_VERB.containsMatchIn(t)) return null
        if (t.split(' ').size > 14 || Patterns.get("^(?:play|open|set|remind|text|call|turn|add|show me the trailer)\\b").containsMatchIn(t)) return null
        val movie = MOVIE_WORD.containsMatchIn(t)
        val show = SHOW_WORD.containsMatchIn(t) && !Patterns.get("\\b(?:movie|film) series\\b").containsMatchIn(t)
        if (!movie && !show) return null
        val name = subject(t) ?: return null
        val kind = when {
            Patterns.get("\\bseasons?\\b|\\bepisodes?\\b").containsMatchIn(t) -> LookupKind.TV_SHOW
            movie -> LookupKind.MOVIE
            else -> LookupKind.TV_SHOW
        }
        return Request.Latest(name, kind, NEXT.containsMatchIn(t))
    }

    fun subject(t: String): String? {
        var r = " $t "
        repeat(3) { r = r.replace(LATEST_FILLER, " ") }
        r = r.replace(Patterns.get("'s\\b"), " ").replace(Patterns.get("[^a-z0-9:&' ]"), " ").replace(Patterns.get("\\s+"), " ").trim()
        return r.ifEmpty { null }?.takeIf { it.split(' ').size <= 6 }
    }

    // ------------------------------------------------------------------ which movies belong to a series

    /** Lowercase, "&" as "and", no "the"/"marvel's" in front, punctuation as spaces (":" kept, it marks a subtitle). */
    fun core(title: String): String = title.lowercase(Locale.US)
        .replace("&", " and ").replace(Patterns.get("[’']"), "")
        .replace(Patterns.get("[^a-z0-9: ]"), " ").replace(Patterns.get("\\s*:\\s*"), ": ").replace(Patterns.get("\\s+"), " ").trim()
        .replace(Patterns.get("^(?:the |marvel studios |marvels |marvel |disneys |disney |pixars |dreamworks )+"), "").trim()

    private val SEQUEL = Patterns.get("^(?::| -| \\d+\\b| (?:ii|iii|iv|v|vi|vii|viii|part|chapter|vol|volume|vs|versus|x|minus|returns|reloaded|rises|begins|forever|resurgence|legacy|reborn|unleashed|origins|revenge|strikes|awakens|resurrection|the next|endgame|doomsday)\\b)")

    /** A sequel-style title in the series ("Avengers: Endgame", "Godzilla vs. Kong", "Shin Godzilla"). */
    fun sequelish(q: String, title: String): Boolean {
        val cq = core(q).replace(":", "")
        val ct = core(title)
        if (ct.replace(":", "") == cq) return false
        if (ct.endsWith(" $cq")) return true
        return ct.startsWith(cq) && SEQUEL.containsMatchIn(ct.substring(cq.length))
    }

    /** The movies of the series named [q] (sorted oldest first): its exact-title movies and its sequel-style ones. */
    fun members(q: String, films: List<Film>): List<Film> {
        val cq = core(q).replace(":", "")
        return films.filter { it.collection || core(it.title).replace(":", "") == cq || sequelish(q, it.title) }
            .distinctBy { core(it.title) + "|" + it.year }
            .sortedWith(compareBy(nullsLast()) { it.date })
    }

    /** True when [q] names a series rather than one movie: several movies with that name, or the name and its sequels. */
    fun isSeries(q: String, members: List<Film>): Boolean {
        if (members.size < 2) return false
        val exact = members.count { !sequelish(q, it.title) }
        return exact >= 2 || members.any { it.collection || sequelish(q, it.title) }
    }

    /** What to search for to find exactly this movie (the year, when several share its title). */
    fun lookupTitle(f: Film, among: List<Film>): String = f.page
        ?: if (among.count { core(it.title) == core(f.title) } > 1 && f.year != null) "${f.title} (${f.year} film)" else f.title

    // ------------------------------------------------------------------ reading "which one?" answers

    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth")
    private val NEWEST = Patterns.get("\\b(?:newest|latest|most recent|last|new one|recent one|the new|current|the recent|final)\\b")
    private val OLDEST = Patterns.get("\\b(?:first|original|oldest|earliest|og)\\b")

    /** "the newest", "the first one", "2015", "the third", "age of ultron", "endgame" -> the movie, or null. */
    fun pickFilm(answer: String, films: List<Film>): Film? {
        if (films.isEmpty()) return null
        val a = " " + answer.lowercase(Locale.US).replace(Patterns.get("[^a-z0-9 ]"), " ").replace(Patterns.get("\\s+"), " ").trim() + " "
        // A name first ("the last jedi" names a movie, not the last one).
        val stop = setOf("the", "a", "an", "one", "movie", "film", "of", "and", "in", "on", "with", "i", "mean", "meant", "want", "that", "this", "please", "part")
        val said = a.trim().split(' ').filter { it.length > 1 && it !in stop }.toSet()
        val scored = films.map { f ->
            val words = core(f.title).replace(":", " ").split(' ').filter { it.isNotEmpty() && it !in stop }
            f to words.count { it in said && !films.all { o -> it in core(o.title) } } // words every title shares don't count
        }
        val best = scored.maxOf { it.second }
        if (best > 0) scored.filter { it.second == best }.let { if (it.size == 1) return it[0].first }
        Patterns.get("\\b(19\\d\\d|20\\d\\d)\\b").find(a)?.let { m -> films.lastOrNull { it.year == m.value.toInt() }?.let { return it } }
        if (NEWEST.containsMatchIn(a)) return films.last()
        ORDINALS.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(a) }.takeIf { it >= 0 }?.let { return films.getOrNull(it) }
        if (OLDEST.containsMatchIn(a)) return films.first()
        Patterns.get("\\b(\\d{1,2})(?:st|nd|rd|th)?\\b").find(a)?.let { m -> films.getOrNull(m.groupValues[1].toInt() - 1)?.let { return it } }
        return null
    }

    /** "season 2", "the second", "the latest", "all of them" -> the season number, 0 for all, or null. */
    fun pickSeason(answer: String, count: Int): Int? {
        val a = " " + answer.lowercase(Locale.US).replace(Patterns.get("[^a-z0-9 ]"), " ").replace(Patterns.get("\\s+"), " ").trim() + " "
        if (Patterns.get("\\b(?:all|every|everything|whole|entire|complete|full|each|both|all of them|the show|the series|series)\\b").containsMatchIn(a)) return 0
        Patterns.get("\\b(?:season|s) ?(\\d{1,2})\\b").find(a)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 1..count } }
        if (NEWEST.containsMatchIn(a)) return count
        ORDINALS.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(a) }.takeIf { it >= 0 }?.let { return (it + 1).takeIf { n -> n <= count } }
        if (OLDEST.containsMatchIn(a)) return 1
        Patterns.get("\\b(\\d{1,2})\\b").find(a)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 1..count } }
        return null
    }

    // ------------------------------------------------------------------ fetching

    /** Movies whose titles match [q]: TMDB (with a key, including its collections) or Apple's movie store. */
    suspend fun films(q: String, tmdbKey: String): List<Film> = withContext(Dispatchers.IO) {
        val out = ArrayList<Film>()
        if (tmdbKey.isNotBlank()) {
            val bearer = tmdbKey.length > 40
            val headers = if (bearer) mapOf("Authorization" to "Bearer $tmdbKey") else emptyMap()
            fun url(path: String) = "https://api.themoviedb.org/3$path" + (if (bearer) "" else (if ('?' in path) "&" else "?") + "api_key=${Http.enc(tmdbKey)}")
            fun add(arr: org.json.JSONArray?) {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.optJSONObject(i) ?: continue
                    val date = runCatching { LocalDate.parse(o.optString("release_date")) }.getOrNull()
                    val future = date == null || date.isAfter(LocalDate.now())
                    // Leave out obscure entries (no votes), but keep announced ones.
                    if (o.optInt("vote_count") < 25 && !(future && o.optDouble("popularity") > 4)) continue
                    out += Film(o.optString("title").ifBlank { continue }, date, o.optDouble("popularity"), rating = tmdbRating(o))
                }
            }
            add(Http.getJsonCached(url("/search/movie?query=${Http.enc(q)}"), headers)?.optJSONArray("results"))
            val coll = Http.getJsonCached(url("/search/collection?query=${Http.enc(q)}"), headers)?.optJSONArray("results")?.optJSONObject(0)
            if (coll != null && core(coll.optString("name")).contains(core(q))) {
                val parts = Http.getJsonCached(url("/collection/${coll.optInt("id")}"), headers)?.optJSONArray("parts")
                // Collection parts are the series even when the titles don't say so ("Rogue One").
                for (i in 0 until (parts?.length() ?: 0)) {
                    val o = parts!!.optJSONObject(i) ?: continue
                    val date = runCatching { LocalDate.parse(o.optString("release_date")) }.getOrNull()
                    val title = o.optString("title").ifBlank { continue }
                    out.removeAll { core(it.title) == core(title) && it.year == date?.year }
                    out += Film(title, date, o.optDouble("popularity"), collection = true, rating = tmdbRating(o))
                }
            }
        }
        if (out.isEmpty()) out += wikipediaFilms(q)
        out
    }

    /** TMDB's viewer rating, when enough people voted for it to mean something. */
    private fun tmdbRating(o: org.json.JSONObject): Double? =
        o.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 && o.optInt("vote_count") >= 50 }

    /** "Godzilla (2014 film)" -> "Godzilla". */
    private val PAGE_SUFFIX = Patterns.get("\\s*\\((?:\\d{4} )?(?:[A-Z][a-z]+ )?(?:film|movie|animated film)\\)$")
    private val NOT_A_FILM = Patterns.get("(?i)\\b(?:soundtrack|score|album|song|single|video game|television film|tv film|pornographic|short film|film series|franchise|character|novel|comic|book|episode|documentary about|making-of|re-release|rerelease|re-issue|reissue|remaster|concert film|fan film)\\b")

    /**
     * Without a TMDB key: Wikipedia's film articles whose titles match (with the year from the article title or its
     * description), and exact dates from Wikidata for the newest
     * and upcoming ones.
     */
    private fun wikipediaFilms(q: String): List<Film> {
        val api = "https://en.wikipedia.org/w/api.php?format=json&formatversion=2&action=query"
        val info = "prop=description%7Cpageprops&ppprop=wikibase_item"
        fun pagesOf(j: org.json.JSONObject?) = j?.optJSONObject("query")?.optJSONArray("pages")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        // Every article whose title starts with the name ("Avengers: Doomsday", "The Avengers (2012 film)"). Few requests:
        // Wikipedia turns away quick repeats.
        val found = LinkedHashMap<String, org.json.JSONObject>()
        val name = q.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val prefixed = listOf(name, "The $name").flatMap { p ->
            Http.getJsonCached("$api&list=prefixsearch&psnamespace=0&pslimit=40&pssearch=${Http.enc(p)}")?.optJSONObject("query")?.optJSONArray("prefixsearch")
                ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("title") } }.orEmpty()
        }.filter { members(q, listOf(Film(it.replace(PAGE_SUFFIX, ""), null))).isNotEmpty() }.distinct()
        for (chunk in prefixed.chunked(50)) for (o in pagesOf(Http.getJsonCached("$api&$info&titles=${Http.enc(chunk.joinToString("|"))}")))
            found[o.optString("title")] = o
        if (found.isEmpty()) return emptyList()
        data class Page(val page: String, val film: Film, val qid: String?, val upcoming: Boolean)
        val pages = found.values.mapNotNull { o ->
            val page = o.optString("title").ifBlank { return@mapNotNull null }
            val desc = o.optString("description")
            val titled = PAGE_SUFFIX.containsMatchIn(page)
            if (!titled && !desc.contains("film", ignoreCase = true) || NOT_A_FILM.containsMatchIn(desc)) return@mapNotNull null
            val upcoming = desc.startsWith("upcoming", ignoreCase = true)
            val year = Patterns.get("\\((\\d{4})[^)]*\\)$").find(page)?.groupValues?.get(1)?.toInt()
                ?: Patterns.get("\\b(19\\d\\d|20\\d\\d)\\b").find(desc)?.value?.toInt()
            if (year == null && !upcoming) return@mapNotNull null
            val title = page.replace(PAGE_SUFFIX, "")
            // An upcoming film known only by this year's number: not out yet, date unknown.
            val date = year?.let { LocalDate.of(it, 1, 1) }?.takeUnless { upcoming && it.year <= LocalDate.now().year }
            o.optJSONObject("pageprops")?.optString("wikibase_item")?.ifBlank { null }.let { q -> Page(page, Film(title, date, precise = false, page = page, qid = q), q, upcoming) }
        }
        val inSeries = pages.filter { p -> members(q, listOf(p.film)).isNotEmpty() }
        if (inSeries.isEmpty()) return emptyList()
        val kept = inSeries.sortedWith(compareBy(nullsLast()) { it.film.date })
        // Exact dates for the newest released one and the upcoming ones ("from April 2019", "comes out December 18").
        val exact = (kept.filter { !it.upcoming }.takeLast(1) + kept.filter { it.upcoming }.take(1)).mapNotNull { p -> p.qid?.let { p to it } }
        val dated = HashMap<String, LocalDate>()
        for ((i, pair) in exact.withIndex()) {
            val (p, qid) = pair
            // Wikidata turns away quick repeats (busier on shared VPN addresses): space them out, and try again once.
            if (i > 0) Thread.sleep(400)
            val url = "https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P577&entity=$qid"
            val c = (Http.getJsonCached(url) ?: run { Thread.sleep(1200); Http.getJsonCached(url) })
                ?.optJSONObject("claims")?.optJSONArray("P577") ?: continue
            // Each country's release date: the U.S. one when listed, else the earliest.
            val dates = (0 until c.length()).mapNotNull { i ->
                val claim = c.optJSONObject(i) ?: return@mapNotNull null
                val v = claim.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value") ?: return@mapNotNull null
                if (v.optInt("precision") < 11) return@mapNotNull null // only full dates
                val us = claim.optJSONObject("qualifiers")?.optJSONArray("P291")?.let { a ->
                    (0 until a.length()).any { a.optJSONObject(it)?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id") == "Q30" }
                } == true
                runCatching { LocalDate.parse(v.optString("time").removePrefix("+").take(10)) }.getOrNull()?.let { it to us }
            }
            (dates.filter { it.second }.minOfOrNull { it.first } ?: dates.minOfOrNull { it.first })?.let { dated[p.page] = it }
        }
        return kept.map { p -> dated[p.page]?.let { p.film.copy(date = it, precise = true) } ?: p.film }
    }

    /** A show's seasons with their premiere dates (TVmaze, free). */
    suspend fun seasons(name: String): ShowSeasons? = withContext(Dispatchers.IO) {
        val j = Http.getJsonCached("https://api.tvmaze.com/singlesearch/shows?q=${Http.enc(name)}&embed=seasons") ?: return@withContext null
        val arr = j.optJSONObject("_embedded")?.optJSONArray("seasons") ?: return@withContext null
        val seasons = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Season(o.optInt("number"), runCatching { LocalDate.parse(o.optString("premiereDate")) }.getOrNull(), o.optInt("episodeOrder").takeIf { it > 0 })
        }.filter { it.number > 0 }.sortedBy { it.number }
        ShowSeasons(j.optString("name").ifBlank { name }, seasons, j.optString("status") in setOf("Running", "In Development", "To Be Determined"))
    }

    // ------------------------------------------------------------------ answers

    private val DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    fun pretty(name: String): String = name.split(' ').joinToString(" ") { w -> if (w.length <= 2 && w in setOf("of", "vs", "x", "and", "a", "in", "on")) w else w.replaceFirstChar { it.uppercase() } }

    fun latestMovieLine(name: String, films: List<Film>, next: Boolean, today: LocalDate): String {
        val label = pretty(name)
        val released = films.filter { it.date != null && !it.date.isAfter(today) }
        val coming = films.filter { it.date == null || it.date.isAfter(today) }
        val last = released.lastOrNull()
        val sb = StringBuilder()
        if (last != null && !(next && coming.isNotEmpty())) sb.append("The newest $label movie is ${last.title}, from ${if (last.precise) last.date!!.format(MONTH) else last.year.toString()}. ")
        coming.firstOrNull()?.let { c ->
            val first = if (sb.isEmpty()) "The next $label movie" else "The next one"
            sb.append(when {
                c.date == null -> "$first, ${c.title}, is in the works, with no release date yet. "
                c.precise -> "$first, ${c.title}, comes out ${c.date.format(DAY)}. "
                else -> "$first, ${c.title}, is due in ${c.year}. "
            })
        }
        if (coming.isEmpty() && next) sb.append("I don't know of a new $label movie on the way yet.")
        if (last == null && coming.isEmpty()) return "I couldn't find a $label movie."
        return sb.toString().trim()
    }

    fun latestSeasonLine(show: ShowSeasons, next: Boolean, today: LocalDate): String {
        val released = show.released(today)
        val coming = show.upcoming(today)
        val last = released.lastOrNull()
        val sb = StringBuilder()
        if (last != null && !(next && coming.isNotEmpty())) {
            val count = released.size
            sb.append("${show.name}'s newest season is season ${last.number}, which premiered ${last.premiere!!.format(DAY)}. ")
            if (count > 1 && !next) sb.append("There are $count seasons out. ")
        }
        coming.firstOrNull()?.let { sb.append("Season ${it.number} premieres ${it.premiere!!.format(DAY)}. ") }
        if (coming.isEmpty() && next) sb.append(if (show.running) "${show.name} is still going, but there's no date for the next season yet." else "${show.name} has ended, so no new seasons are coming.")
        if (last == null && coming.isEmpty()) return "I couldn't find any seasons of ${show.name}."
        return sb.toString().trim()
    }

    /** "Which Avengers movie? ..." with the newest few by name. */
    fun whichMovie(name: String, films: List<Film>): String {
        val label = pretty(name)
        val shown = films.takeLast(4).joinToString(", ") { f -> short(name, f.title) + (f.year?.let { " ($it)" } ?: "") }
        val older = if (films.size > 4) ", or an older one" else ""
        return "Which $label movie? There's $shown$older."
    }

    /** "Avengers: Age of Ultron" -> "Age of Ultron" when the series name is already said. */
    fun short(name: String, title: String): String {
        val i = title.indexOf(':')
        return if (i > 0 && core(title.substring(0, i)).replace(":", "") == core(name).replace(":", "")) title.substring(i + 1).trim() else title
    }

    fun whichSeason(show: ShowSeasons, today: LocalDate): String =
        "Which season of ${show.name}? There are ${show.released(today).size}. Say a number, the newest, or all of them."
}
