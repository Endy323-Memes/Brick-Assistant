package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.Period
import java.util.Locale

/**
 * What a lookup is about. [hint] is how you'd say it ("the movie Baywatch", "the actor who...", "the app Discord");
 * [describes] matches Wikipedia's one-line description of a page of that kind ("2017 American film").
 */
enum class LookupKind(val label: String, val hint: String, val describes: String, val searchWord: String) {
    PERSON("Public figure",
        "actor|actress|singer|rapper|musician|artist|celebrity|famous person|public figure|politician|president|senator|" +
            "athlete|player|footballer|youtuber|you tuber|streamer|influencer|tiktoker|comedian|author|writer|director|producer|" +
            "ceo|founder|billionaire|businessman|businesswoman|entrepreneur|scientist|model|dj|chef|host|presenter|person|guy|woman|man",
        "\\b(?:actor|actress|singer|rapper|musician|songwriter|politician|president|senator|governor|footballer|player|athlete|" +
            "youtuber|streamer|comedian|author|writer|novelist|director|producer|businessman|businesswoman|entrepreneur|" +
            "executive|scientist|physicist|model|television personality|media personality|internet personality|influencer|" +
            "presenter|host|journalist|chef|dj|record producer|philosopher|painter|monarch|king|queen|prince|princess|" +
            "boxer|wrestler|golfer|swimmer|sprinter|skater|engineer|inventor|investor|activist|lawyer|judge|astronaut|" +
            "\\(born \\d{4}\\)|\\d{4}[–-]\\d{4}|born \\d{4})\\b",
        ""),
    MOVIE("Movie", "movie|film|flick|motion picture",
        "\\b(?:film|movie|motion picture|animated feature)\\b", "film"),
    TV_SHOW("TV show",
        "tv show|television show|tv series|show|series|sitcom|cartoon|anime|miniseries|docuseries|reality show|soap opera|season",
        "\\b(?:television series|tv series|sitcom|web series|animated series|anime series|miniseries|soap opera|reality (?:television|tv)|talk show|game show|television program(?:me)?|streaming series|docuseries)\\b",
        "TV series"),
    PLATFORM("Platform or app",
        "app|application|website|web site|site|platform|social media|social network|streaming service|service|software|" +
            "search engine|messaging app|program",
        "\\b(?:social (?:media|network(?:ing)?)|website|web ?site|platform|streaming service|video[- ]sharing|online service|" +
            "mobile app|messaging|search engine|instant messaging|software|web browser|operating system|online marketplace|" +
            "e-commerce|voip|application|video game digital distribution|subscription video)\\b",
        ""),
    COMPANY("Company or brand", "company|brand|business|corporation|store|chain|restaurant chain|manufacturer|label",
        "\\b(?:company|corporation|conglomerate|manufacturer|retailer|brand|multinational|restaurant chain|fast food|" +
            "automaker|airline|bank|record label|holding company|subsidiary)\\b",
        "company"),
    GAME("Video game", "video game|game",
        "\\b(?:video game|game franchise|battle royale|role-playing game|mobile game|esports)\\b", "video game"),
    MUSIC("Music", "song|album|band|track|single|group|record",
        "\\b(?:song|single|album|band|rock group|musical group|boy band|girl group|duo|ep|mixtape|soundtrack)\\b", ""),
    BOOK("Book", "book|novel|comic|manga|series of books",
        "\\b(?:novel|book|comic|manga|graphic novel|short story|poem|play by)\\b", "novel"),
    PLACE("Place", "city|country|state|town|place|island|landmark|mountain|river|lake|national park|park|building",
        "\\b(?:city|country|state|town|village|capital|island|mountain|river|lake|national park|province|county|region|" +
            "landmark|building|skyscraper|stadium|bridge|desert|ocean|sea|continent|municipality)\\b", ""),
    ;

    val hintRegex: Regex by lazy { Patterns.get("\\b(?:$hint)\\b", true) }
    val describesRegex: Regex by lazy { Regex(describes, RegexOption.IGNORE_CASE) }

    companion object {
        /** The kind a page's description says ("American actor (born 1956)" is a person). */
        fun classify(description: String?): LookupKind? {
            val d = description?.takeIf { it.isNotBlank() } ?: return null
            // Order matters: "2017 film" before PERSON's "director"; "video game" before PLATFORM's "software".
            for (k in listOf(MOVIE, TV_SHOW, GAME, MUSIC, BOOK, PLATFORM, COMPANY, PERSON, PLACE)) if (k.describesRegex.containsMatchIn(d)) return k
            return null
        }
    }
}

/** Which fact you want: "how old is", "who's in", "when did it come out", "who directed", "who founded"... */
enum class LookupAspect { GENERAL, AGE, BIRTHDAY, CAST, RELEASE, DIRECTOR, FOUNDER, FOUNDED, NET_WORTH, SEASONS, RUNTIME, OWNER, HEADQUARTERS }

/** A lookup worked out from the wording: the subject, its kind (when the wording says) and the fact asked for. */
data class LookupQuery(val subject: String, val kind: LookupKind?, val aspect: LookupAspect)

/** What Brick found: Wikipedia's summary plus Wikidata facts for the kind. */
data class LookupResult(
    val title: String,
    val description: String?,
    val kind: LookupKind?,
    val summary: String,
    val image: String?,
    val url: String,
    val facts: List<Pair<String, String>>,
    /** The spoken answer to the fact you asked for, when there is one. */
    val answer: String?,
)

object Lookup {
    // Wording that says what's being asked, matched before the subject is cut out.
    private val ASPECTS: List<Pair<LookupAspect, Regex>> = listOf(
        LookupAspect.AGE to "^how old (?:is|was|are|were) |\\b(?:'s|s) age$|^what(?:'s| is) the age of |^age of ",
        LookupAspect.BIRTHDAY to "^when (?:is|was) .+? born$|^when (?:is|was) (?:the )?birthday of |\\b(?:'s|s) birthday$|^what(?:'s| is) .+?(?:'s|s) birthday$|^(?:where|when) was .+ born$",
        LookupAspect.CAST to "^who(?:'s| is| was| are| were) (?:in|starring in|starred in|the (?:cast|actors|stars) (?:of|in)) |^(?:the )?cast (?:of|for) |^who (?:plays|played|acts|acted|stars|starred) in |^who (?:stars|starred|acts|acted|plays|played)$|^actors (?:in|from) ",
        LookupAspect.RELEASE to "^when (?:did|does|will) .+? (?:come out|release|premiere|air|launch|drop|debut|start)$|^when (?:was|is) .+? (?:released|made|out|launched|created|filmed)$|^what year (?:did|was|is) |^release date (?:of|for) |\\b(?:'s|s) release date$",
        LookupAspect.DIRECTOR to "^who (?:directed|made|directs) |^(?:the )?director of |^who(?:'s| is| was) the director of ",
        LookupAspect.FOUNDER to "^who (?:founded|created|started|invented|made|owns|runs|built) |^(?:the )?(?:founder|creator|inventor|ceo) of |^who(?:'s| is| was) the (?:founder|creator|ceo|owner|inventor) of ",
        LookupAspect.FOUNDED to "^when (?:was|were) .+? (?:founded|started|created|established|invented)$|^when did .+? (?:start|launch|begin)$",
        LookupAspect.NET_WORTH to "\\bnet worth\\b|^how rich is |^how much (?:money )?(?:is|does) .+? (?:worth|make|have)$",
        LookupAspect.SEASONS to "^how many (?:seasons|episodes) (?:does|did|of|are there of|are in|is) |\\bhow many seasons\\b",
        LookupAspect.RUNTIME to "^how long is (?:the movie |the film )?|\\b(?:runtime|run time|length) of ",
        LookupAspect.HEADQUARTERS to "^where (?:is|are) .+? (?:headquartered|based|located)$|\\bheadquarters of |\\bheadquarters$",
    ).map { (a, p) -> a to Regex(p, RegexOption.IGNORE_CASE) }

    private val LEAD = Regex(
        "^(?:(?:hey )?brick[, ]+)?(?:(?:can you |could you |please |would you |do you know |i want to know |tell me |let me know |i was wondering |quick question )+)?" +
            "(?:search (?:the web |the internet |online |google )?for |look up |lookup |google |find out |find info on |find |search |" +
            "who(?:'s| is| was| are| were) |what(?:'s| is| was| are| were) |tell me about |give me info (?:on|about) |info on |information (?:on|about) |" +
            "what do you know about |do you know |have you heard of |who the heck is |who tf is |what the heck is )?",
        RegexOption.IGNORE_CASE,
    )

    /** "the movie baywatch" -> MOVIE "baywatch"; "who is keanu reeves" -> PERSON; "how old is zendaya" -> AGE. */
    fun understand(raw: String): LookupQuery {
        val s = raw.lowercase(Locale.US).replace(Patterns.get("[?!]"), " ").replace(Patterns.get("\\s+"), " ").trim()
        val aspect = ASPECTS.firstOrNull { it.second.containsMatchIn(s) }?.first ?: LookupAspect.GENERAL
        var t = s
        // Cut the asking words around the subject.
        t = t.replace(Patterns.get("^how old (?:is|was|are|were) |^what(?:'s| is) the age of |^age of |^when (?:is|was) |^when (?:did|does|will) |^what year (?:did|was|is) |" +
            "^who(?:'s| is| was| are| were) (?:in|starring in|starred in|the (?:cast|actors|stars) (?:of|in)) |^(?:the )?cast (?:of|for) |" +
            "^who (?:plays|played|acts|acted|stars|starred) in |^actors (?:in|from) |^who (?:directed|made|directs|founded|created|started|invented|owns|runs|built) |" +
            "^(?:the )?(?:director|founder|creator|inventor|ceo|owner) of |^who(?:'s| is| was) the (?:director|founder|creator|ceo|owner|inventor) of |" +
            "^release date (?:of|for) |^how rich is |^how many (?:seasons|episodes) (?:does|did|of|are there of|are in|is) |^how long is |^(?:runtime|run time|length) of |" +
            "^where (?:is|are) |^how much (?:money )?(?:is|does) "), "")
        t = t.replace(LEAD, "")
        t = t.replace(Patterns.get("\\s+(?:born|come out|came out|released|release|premiere|air|launch|launched|drop|debut|start|made|out|created|filmed|founded|" +
            "started|established|invented|headquartered|based|located|worth|make|have|net worth|'s net worth|s net worth|'s age|'s birthday|s birthday|" +
            "'s release date|have|run|go)$"), "")
        t = t.replace(Patterns.get("\\s+(?:please|for me|real quick|right now|again|on (?:the )?(?:web|internet|google|wikipedia)|online)$"), "")
        t = t.replace(Patterns.get("^(?:about|on|of) "), "").trim()
        // A kind said with the subject: "the movie baywatch", "baywatch the movie", "the app discord", "actor tom hanks".
        var kind: LookupKind? = null
        for (k in LookupKind.entries) {
            val m = Patterns.get("^(?:the |a |an |that )?(?:${k.hint})\\s+(?:called |named |titled |by the name of )?(.+)$|^(.+?)\\s+(?:the |a )?(?:${k.hint})$", true).find(t)
            if (m != null) {
                val subject = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
                if (subject.isNotEmpty() && subject.split(' ').size <= 8) { kind = k; t = subject; break }
            }
        }
        if (kind == null && Patterns.get("^(?:who(?:'s| is| was| are| were)|how old|when was .+ born)\\b").containsMatchIn(s) && aspect !in setOf(LookupAspect.CAST, LookupAspect.DIRECTOR, LookupAspect.FOUNDER)) {
            kind = LookupKind.PERSON
        }
        if (kind == null) kind = when (aspect) {
            LookupAspect.AGE, LookupAspect.BIRTHDAY, LookupAspect.NET_WORTH -> LookupKind.PERSON
            LookupAspect.SEASONS -> LookupKind.TV_SHOW
            LookupAspect.RUNTIME, LookupAspect.DIRECTOR -> LookupKind.MOVIE
            else -> null
        }
        t = t.replace(Patterns.get("^(?:the|a|an) (?=\\S+ \\S)"), "").trim().removeSuffix("'s").removeSuffix("’s")
        return LookupQuery(t.ifBlank { s }, kind, aspect)
    }

    /** Whether a question reads like a lookup of someone or something (versus "what is photosynthesis"-style questions). */
    fun isEntityLookup(raw: String, q: LookupQuery): Boolean =
        q.kind != null || q.aspect != LookupAspect.GENERAL ||
            Patterns.get("^(?:tell me about|what do you know about|have you heard of|do you know|info on|information (?:on|about))\\b", true).containsMatchIn(raw.trim())

    // ------------------------------------------------------------------ fetching

    private fun wiki(url: String): JSONObject? = Http.getJsonCached(url)

    /** A Wikipedia page Brick settled on, with its Wikidata item. */
    data class Page(val title: String, val description: String?, val kind: LookupKind?, val extract: String,
        val image: String?, val url: String, val qid: String?)

    /**
     * Finds the page for [subject]: the first search hit whose description is one of [accept] (any kind when empty),
     * skipping disambiguation pages. [searchWords] are tried with the subject first ("dune film", "dune TV series").
     */
    suspend fun page(subject: String, accept: Set<LookupKind>, searchWords: List<String> = emptyList()): Page? = withContext(Dispatchers.IO) {
        val terms = searchWords.filter { it.isNotEmpty() }.map { "$subject $it" } + subject
        var pick: Pair<String, String?>? = null
        for (term in terms) {
            val r = wiki("https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=8&prop=description&gsrsearch=${Http.enc(term)}")
            val pages = r?.optJSONObject("query")?.optJSONObject("pages") ?: continue
            val list = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }
                .sortedBy { it.optInt("index", 99) }
                .map { it.optString("title") to it.optString("description").ifBlank { null } }
                .filter { (_, d) -> d == null || !Patterns.get("disambiguation|topics referred to|wikimedia list", true).containsMatchIn(d) }
                .toList()
            // Prefer the exact title ("Dune (2021 film)" over "Dune: Part Two" for "dune").
            val want = subject.lowercase(Locale.US).replace(Patterns.get("[^a-z0-9]+"), "")
            fun exact(t: String) = t.substringBefore(" (").lowercase(Locale.US).replace(Patterns.get("[^a-z0-9]+"), "") == want
            val fits = if (accept.isNotEmpty()) list.filter { (_, d) -> LookupKind.classify(d) in accept } else list
            pick = fits.firstOrNull { exact(it.first) } ?: fits.firstOrNull() ?: pick ?: list.firstOrNull()
            if (pick != null && (accept.isEmpty() || LookupKind.classify(pick.second) in accept)) break
        }
        val title = pick?.first ?: return@withContext null
        val sum = wiki("https://en.wikipedia.org/api/rest_v1/page/summary/${Http.enc(title.replace(' ', '_'))}") ?: return@withContext null
        val extract = sum.optString("extract").ifBlank { return@withContext null }
        val desc = sum.optString("description").ifBlank { pick.second }
        val url = sum.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")?.ifBlank { null }
            ?: "https://en.wikipedia.org/wiki/${Http.enc(title.replace(' ', '_'))}"
        Page(sum.optString("title", title), desc, LookupKind.classify(desc), extract,
            sum.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }, url,
            sum.optString("wikibase_item").ifBlank { null })
    }

    /** Finds the page for [q]: the first search hit of the right kind (by its description), skipping disambiguation pages. */
    suspend fun find(q: LookupQuery): LookupResult? = withContext(Dispatchers.IO) {
        val p = page(q.subject, setOfNotNull(q.kind), listOfNotNull(q.kind?.searchWord)) ?: return@withContext null
        val kind = p.kind ?: q.kind
        val claims = p.qid?.let { claims(it) }
        val facts = if (claims != null) facts(claims, kind) else emptyMap()
        val rows = factRows(kind, facts).toMutableList()
        if (claims != null && (kind == LookupKind.MOVIE || kind == LookupKind.TV_SHOW || kind == LookupKind.GAME)) {
            MediaGuide.shortScores(reviewScores(claims))?.let { rows += "RATED" to it }
        }
        LookupResult(p.title, p.description, kind, shorten(p.extract), p.image, p.url, rows, answerFor(q.aspect, p.title, kind, facts))
    }

    private fun shorten(text: String): String {
        val clean = text.replace(Patterns.get("\\s*\\([^)]*\\)"), "").replace(Patterns.get("\\s+"), " ").trim()
        val sentences = Patterns.get("(?<=[.!?])\\s+(?=[A-Z])").split(clean)
        var out = ""
        for (s in sentences) {
            if (out.isNotEmpty() && out.length + s.length > 300) break
            out = if (out.isEmpty()) s else "$out $s"
            if (out.length > 150) break
        }
        return out.ifBlank { clean.take(300) }
    }

    // ------------------------------------------------------------------ Wikidata facts

    /** Wikidata properties Brick reads for each kind. */
    private fun props(kind: LookupKind?): List<String> = when (kind) {
        LookupKind.PERSON -> listOf("P569", "P570", "P106", "P19", "P27")
        LookupKind.MOVIE -> listOf("P577", "P57", "P161", "P2047", "P136", "P1657")
        LookupKind.TV_SHOW -> listOf("P580", "P582", "P161", "P2437", "P1113", "P449", "P170")
        LookupKind.PLATFORM -> listOf("P571", "P112", "P127", "P159", "P856", "P170")
        LookupKind.COMPANY -> listOf("P571", "P112", "P169", "P159", "P452", "P856")
        LookupKind.GAME -> listOf("P577", "P178", "P123", "P400", "P136")
        LookupKind.MUSIC -> listOf("P577", "P175", "P571", "P136", "P264")
        LookupKind.BOOK -> listOf("P50", "P577", "P136")
        LookupKind.PLACE -> listOf("P17", "P1082", "P2046", "P36")
        null -> listOf("P571", "P577", "P112")
    }

    /** All of an item's Wikidata statements, in one request (Wikidata rate-limits many small ones). */
    suspend fun claims(qid: String): JSONObject? = withContext(Dispatchers.IO) {
        wiki("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims&ids=$qid")
            ?.optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("claims")
    }

    /** English names for Wikidata items, 50 per request. */
    suspend fun labels(ids: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String>()
        ids.distinct().take(100).chunked(50).forEach { chunk ->
            wiki("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=en&ids=${chunk.joinToString("|")}")
                ?.optJSONObject("entities")?.let { e ->
                    for (id in chunk) e.optJSONObject(id)?.optJSONObject("labels")?.optJSONObject("en")?.optString("value")?.let { out[id] = it }
                }
        }
        out
    }

    /** The plain string values of [prop] ("70131314" for a Netflix ID), skipping deprecated ones. */
    fun strings(claims: JSONObject, prop: String): List<String> {
        val arr = claims.optJSONArray(prop) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i)?.takeIf { it.optString("rank") != "deprecated" } ?: return@mapNotNull null
            c.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.takeIf { it.optString("type") == "string" }?.optString("value")
        }
    }

    /** A review score from Wikidata: "87%" by Rotten Tomatoes, measured as the Tomatometer score. */
    data class ReviewScore(val value: String, val by: String, val method: String?)

    /** Review scores (P444) with who gave them, the newest per site and method. */
    suspend fun reviewScores(claims: JSONObject): List<ReviewScore> {
        val arr = claims.optJSONArray("P444") ?: return emptyList()
        data class Raw(val value: String, val by: String?, val method: String?, val at: String)
        fun qual(c: JSONObject, p: String) = c.optJSONObject("qualifiers")?.optJSONArray(p)?.optJSONObject(0)?.optJSONObject("datavalue")
        val raws = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i)?.takeIf { it.optString("rank") != "deprecated" } ?: return@mapNotNull null
            val v = c.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value") ?: return@mapNotNull null
            Raw(v, qual(c, "P447")?.optJSONObject("value")?.optString("id"), qual(c, "P459")?.optJSONObject("value")?.optString("id"),
                qual(c, "P585")?.optJSONObject("value")?.optString("time").orEmpty())
        }.filter { it.by != null }
        if (raws.isEmpty()) return emptyList()
        val names = labels(raws.flatMap { listOfNotNull(it.by, it.method) })
        return raws.groupBy { it.by to it.method }.values.map { g -> g.maxBy { it.at } }
            .map { ReviewScore(it.value, names[it.by] ?: it.by!!, it.method?.let { m -> names[m] }) }
    }

    /** Property -> its values as text (dates as yyyy-mm-dd, items as their English names), most important first. */
    private suspend fun facts(all: JSONObject, kind: LookupKind?): Map<String, List<String>> = coroutineScope {
        val claims = props(kind).map { p -> p to all.optJSONArray(p) }
        val raw = LinkedHashMap<String, List<Any>>()
        val itemIds = LinkedHashSet<String>()
        for ((p, arr) in claims) {
            if (arr == null) continue
            val values = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                if (c.optString("rank") == "deprecated") return@mapNotNull null
                val v = c.optJSONObject("mainsnak")?.optJSONObject("datavalue") ?: return@mapNotNull null
                when (v.optString("type")) {
                    "time" -> v.optJSONObject("value")?.optString("time")
                    "wikibase-entityid" -> v.optJSONObject("value")?.optString("id")?.also { itemIds += it }?.let { "Q:$it" }
                    "quantity" -> v.optJSONObject("value")?.let { q ->
                        val amount = q.optString("amount").removePrefix("+")
                        val unit = q.optString("unit").substringAfterLast('/')
                        if (unit == "Q7727") "$amount min" else if (unit == "Q712226") "$amount km2" else amount
                    }
                    "string" -> v.optString("value")
                    else -> null
                }
            }
            if (values.isNotEmpty()) raw[p] = values.take(if (p == "P161") 6 else 4)
        }
        val labels = labels(itemIds.take(50))
        raw.mapValues { (_, vs) ->
            vs.mapNotNull { v ->
                val s = v as String
                when {
                    s.startsWith("Q:") -> labels[s.removePrefix("Q:")]
                    s.startsWith("+") || s.startsWith("-") && s.length > 11 && s[5] == '-' -> s.removePrefix("+").take(10)
                    else -> s
                }
            }.distinct()
        }.filterValues { it.isNotEmpty() }
    }

    private fun date(d: String?): LocalDate? = d?.let {
        val parts = it.take(10).split('-')
        val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: 1
        val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 } ?: 1
        runCatching { LocalDate.of(y, m, day) }.getOrNull()
    }

    private fun year(d: String?) = d?.take(4)?.trimStart('0')

    /** "September 1, 1996", "September 1996" or "1996", as precise as Wikidata has it ("1996-00-00" is a year). */
    private fun pretty(d: String?): String? {
        val dt = date(d) ?: return null
        val month = dt.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
        return when {
            d!!.length < 10 || d.substring(5, 7) == "00" -> dt.year.toString()
            d.substring(8, 10) == "00" -> "$month ${dt.year}"
            else -> "$month ${dt.dayOfMonth}, ${dt.year}"
        }
    }

    private fun age(born: String?, died: String?): Int? {
        val b = date(born) ?: return null
        if (born!!.substring(5, 10) == "00-00") return null
        val end = date(died) ?: LocalDate.now()
        return Period.between(b, end).years
    }

    private fun list(v: List<String>?, n: Int = 3) = v?.take(n)?.let { l ->
        when (l.size) { 0 -> null; 1 -> l[0]; 2 -> "${l[0]} and ${l[1]}"; else -> l.dropLast(1).joinToString(", ") + ", and " + l.last() }
    }

    /** The readout rows for the kind: BORN/AGE/JOB for people, YEAR/DIR/CAST for movies... */
    private fun factRows(kind: LookupKind?, f: Map<String, List<String>>): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        fun add(label: String, v: String?) { if (!v.isNullOrBlank()) rows += label to v }
        when (kind) {
            LookupKind.PERSON -> {
                val born = f["P569"]?.firstOrNull()
                val died = f["P570"]?.firstOrNull()
                add("BORN", pretty(born)?.let { b -> age(born, died)?.let { a -> if (died == null) "$b (age $a)" else b } ?: b })
                add("DIED", pretty(died)?.let { d -> age(born, died)?.let { "$d (aged $it)" } ?: d })
                add("FROM", f["P19"]?.firstOrNull() ?: f["P27"]?.firstOrNull())
                add("KNOWN", list(f["P106"]?.let { j -> j.filter { o -> j.none { it != o && it.endsWith(" $o") } } }))
            }
            LookupKind.MOVIE -> {
                add("YEAR", year(f["P577"]?.firstOrNull()))
                add("DIR", list(f["P57"], 2))
                add("CAST", list(f["P161"], 5))
                add("LENGTH", f["P2047"]?.firstOrNull()?.let { m -> m.removeSuffix(" min").toDoubleOrNull()?.toInt()?.let { "${it / 60}h ${it % 60}m" } })
                add("AGES", f["P1657"]?.firstOrNull())
                add("GENRE", list(f["P136"], 2))
            }
            LookupKind.TV_SHOW -> {
                val start = year(f["P580"]?.firstOrNull())
                val end = year(f["P582"]?.firstOrNull())
                add("AIRED", start?.let { if (end != null && end != start) "$it-$end" else if (end == null) "$it-" else it })
                add("ON", f["P449"]?.firstOrNull())
                add("SEASONS", f["P2437"]?.firstOrNull()?.substringBefore('.'))
                add("EPS", f["P1113"]?.firstOrNull()?.substringBefore('.'))
                add("CAST", list(f["P161"], 5))
                add("BY", list(f["P170"], 2))
            }
            LookupKind.PLATFORM, LookupKind.COMPANY -> {
                add("FOUNDED", year(f["P571"]?.firstOrNull()))
                add("BY", list(f["P112"] ?: f["P170"], 3))
                add("OWNER", f["P127"]?.firstOrNull())
                add("CEO", f["P169"]?.firstOrNull())
                add("HQ", f["P159"]?.firstOrNull())
                add("FIELD", list(f["P452"], 2))
                add("SITE", f["P856"]?.firstOrNull()?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")?.trimEnd('/'))
            }
            LookupKind.GAME -> {
                add("YEAR", year(f["P577"]?.firstOrNull()))
                add("DEV", list(f["P178"], 2))
                add("PUB", list(f["P123"], 2))
                add("ON", list(f["P400"], 4))
            }
            LookupKind.MUSIC -> {
                add("YEAR", year(f["P577"]?.firstOrNull() ?: f["P571"]?.firstOrNull()))
                add("BY", list(f["P175"], 2))
                add("GENRE", list(f["P136"], 2))
                add("LABEL", f["P264"]?.firstOrNull())
            }
            LookupKind.BOOK -> {
                add("BY", list(f["P50"], 2))
                add("YEAR", year(f["P577"]?.firstOrNull()))
                add("GENRE", list(f["P136"], 2))
            }
            LookupKind.PLACE -> {
                add("IN", f["P17"]?.firstOrNull())
                add("PEOPLE", f["P1082"]?.firstOrNull()?.substringBefore('.')?.toLongOrNull()?.let { "%,d".format(Locale.US, it) })
                add("CAPITAL", f["P36"]?.firstOrNull())
            }
            null -> {
                add("SINCE", year(f["P571"]?.firstOrNull() ?: f["P577"]?.firstOrNull()))
                add("BY", list(f["P112"], 2))
            }
        }
        return rows
    }

    /** The answer to the fact asked for ("Zendaya is 30 years old."), or null for a general lookup. */
    private fun answerFor(aspect: LookupAspect, name: String, kind: LookupKind?, f: Map<String, List<String>>): String? = when (aspect) {
        LookupAspect.AGE -> {
            val born = f["P569"]?.firstOrNull()
            val died = f["P570"]?.firstOrNull()
            age(born, died)?.let { if (died != null) "$name died in ${year(died)} at $it." else "$name is $it years old." }
        }
        LookupAspect.BIRTHDAY -> pretty(f["P569"]?.firstOrNull())?.let { b ->
            "$name was born on $b" + (f["P19"]?.firstOrNull()?.let { " in $it" } ?: "") + "."
        }
        LookupAspect.CAST -> list(f["P161"], 5)?.let { "$name stars $it." }
        LookupAspect.RELEASE -> (year(f["P577"]?.firstOrNull()) ?: year(f["P580"]?.firstOrNull()))?.let {
            pretty(f["P577"]?.firstOrNull())?.let { d -> "$name came out on $d." } ?: "$name came out in $it."
        }
        LookupAspect.DIRECTOR -> list(f["P57"], 2)?.let { "$name was directed by $it." } ?: list(f["P170"], 2)?.let { "$name was created by $it." }
        LookupAspect.FOUNDER -> list(f["P112"] ?: f["P170"] ?: f["P178"] ?: f["P50"], 3)?.let { "$name was founded by $it." }
        LookupAspect.FOUNDED -> year(f["P571"]?.firstOrNull() ?: f["P577"]?.firstOrNull())?.let { "$name was founded in $it." }
        LookupAspect.SEASONS -> f["P2437"]?.firstOrNull()?.substringBefore('.')?.let { s ->
            "$name has $s season${if (s == "1") "" else "s"}" + (f["P1113"]?.firstOrNull()?.substringBefore('.')?.let { " and $it episodes" } ?: "") + "."
        }
        LookupAspect.RUNTIME -> f["P2047"]?.firstOrNull()?.removeSuffix(" min")?.toDoubleOrNull()?.toInt()?.let {
            "$name runs ${it / 60} hour${if (it / 60 == 1) "" else "s"} and ${it % 60} minutes."
        }
        LookupAspect.HEADQUARTERS -> f["P159"]?.firstOrNull()?.let { "$name is based in $it." }
        LookupAspect.OWNER -> f["P127"]?.firstOrNull()?.let { "$name is owned by $it." }
        LookupAspect.NET_WORTH, LookupAspect.GENERAL -> null
    }
}
