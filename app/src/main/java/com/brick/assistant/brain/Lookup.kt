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
enum class LookupAspect {
    GENERAL, AGE, BIRTHDAY, CAST, RELEASE, DIRECTOR, FOUNDER, FOUNDED, NET_WORTH, SEASONS, RUNTIME, OWNER, HEADQUARTERS,
    /** "what awards did dune win", "did oppenheimer win an oscar", "how many grammys does taylor swift have". */
    AWARDS,
    /** "who are the main characters in breaking bad", "characters in the hobbit". */
    CHARACTERS,
    /** "who plays walter white in breaking bad", "who voiced mario". */
    ROLE,
    /** "when did abraham lincoln die", "how did jfk die", "is queen elizabeth still alive". */
    DIED,
    /** "why is mlk important", "what is einstein known for", "what did tesla invent", "key facts about napoleon". */
    KNOWN_FOR,
    /** "what controversies has mrbeast been in", "why is logan paul controversial", "pewdiepie drama". */
    CONTROVERSY,
}

/**
 * A lookup worked out from the wording: the subject, its kind (when the wording says) and the fact asked for.
 * [role] is the character asked about ("walter white"); [award] the award named ("oscar", "best picture").
 */
data class LookupQuery(val subject: String, val kind: LookupKind?, val aspect: LookupAspect, val role: String? = null, val award: String? = null, val album: Boolean = false)

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
    /** Where the facts came from ("Wikipedia", "Wikidata", "TVmaze"...). */
    val sources: List<String> = listOf("Wikipedia", "Wikidata"),
    /** A longer list for the card (awards, characters, cast with roles), under [listTitle]. */
    val list: List<String> = emptyList(),
    val listTitle: String? = null,
    /** Extra buttons: label to address ("HowLongToBeat" for a game's length). */
    val links: List<Pair<String, String>> = emptyList(),
    /** The card's heading when it isn't the kind ("HISTORY"). */
    val heading: String? = null,
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
            "(?:search (?:the web |the internet |online |google |duckduckgo |duck duck go )?for |look up |lookup |google |duckduckgo |duck duck go |ddg |find out |find info on |find |search |" +
            "who(?:'s| is| was| are| were) |what(?:'s| is| was| are| were) |tell me about |give me info (?:on|about) |info on |information (?:on|about) |" +
            "what do you know about |do you know |have you heard of |who the heck is |who tf is |what the heck is )?",
        RegexOption.IGNORE_CASE,
    )

    /** "the movie baywatch" -> MOVIE "baywatch"; "who is keanu reeves" -> PERSON; "how old is zendaya" -> AGE. */
    fun understand(raw: String): LookupQuery {
        val s = raw.lowercase(Locale.US).replace('’', '\'').replace(Patterns.get("[?!]"), " ").replace(Patterns.get("\\s+"), " ").trim()
        special(s)?.let { return it }
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
        kindSaid(t)?.let { (k, subject) -> kind = k; t = subject }
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

    /** The kind said with a subject ("the movie baywatch", "baywatch the movie") and the subject without it. */
    private fun kindSaid(t: String): Pair<LookupKind, String>? {
        for (k in LookupKind.entries) {
            val m = Patterns.get("^(?:the |a |an |that )?(?:${k.hint})\\s+(?:called |named |titled |by the name of )?(.+)$|^(.+?)\\s+(?:the |a )?(?:${k.hint})$", true).find(t)
            if (m != null) {
                val subject = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
                if (subject.isNotEmpty() && subject.split(' ').size <= 8) return k to subject
            }
        }
        return null
    }

    // ------------------------------------------------------------------ awards, characters, who plays whom, how long

    /** An award as it's said ("oscars") and as Wikidata names it ("Academy Award for Best Picture"). */
    private class AwardName(said: String, label: String, val name: String) {
        val said = Regex("\\b(?:$said)\\b", RegexOption.IGNORE_CASE)
        val label = Regex(label, RegexOption.IGNORE_CASE)
    }

    private val AWARD_NAMES = listOf(
        AwardName("oscars?|academy awards?", "Academy Award", "Oscar"),
        AwardName("emmys?|emmy awards?", "Emmy", "Emmy"),
        AwardName("grammys?|grammy awards?", "Grammy", "Grammy"),
        AwardName("golden globes?", "Golden Globe", "Golden Globe"),
        AwardName("tonys?|tony awards?", "Tony Award", "Tony"),
        AwardName("baftas?|bafta awards?", "BAFTA|British Academy", "BAFTA"),
        AwardName("game awards?|game of the year|goty", "Game Award|Game of the Year|Golden Joystick|D\\.I\\.C\\.E\\.|BAFTA Games|Game Developers Choice", "Game of the Year"),
        AwardName("pulitzers?|pulitzer prizes?", "Pulitzer", "Pulitzer"),
        AwardName("hugos?|hugo awards?", "Hugo Award", "Hugo"),
        AwardName("nebulas?|nebula awards?", "Nebula Award", "Nebula"),
        AwardName("booker(?: prizes?)?", "Booker", "Booker Prize"),
        AwardName("nobels?|nobel prizes?", "Nobel", "Nobel Prize"),
        AwardName("vmas?|mtv (?:video music |movie )?awards?", "MTV", "MTV award"),
        AwardName("billboard (?:music )?awards?", "Billboard", "Billboard award"),
        AwardName("brits|brit awards?", "Brit Award", "Brit"),
        AwardName("amas|american music awards?", "American Music Award", "American Music Award"),
        AwardName("bet awards?", "BET", "BET award"),
        AwardName("saturn awards?", "Saturn Award", "Saturn"),
        AwardName("sag awards?|screen actors guild awards?", "Screen Actors Guild", "SAG award"),
        AwardName("critics'? choice(?: awards?)?", "Critics' Choice|Critics Choice", "Critics' Choice award"),
        AwardName("annies?|annie awards?", "Annie Award", "Annie"),
        AwardName("peabodys?|peabody awards?", "Peabody", "Peabody"),
        AwardName("kids'? choice(?: awards?)?", "Kids' Choice", "Kids' Choice award"),
        AwardName("palme d'or|cannes", "Palme d'Or|Cannes", "Cannes award"),
        AwardName("razzies?|golden raspberr(?:y|ies)", "Golden Raspberry", "Razzie"),
    )

    private const val AW = "awards?|accolades|honou?rs|prizes?|trophies|nominations?|oscars?|academy awards?|emmys?|grammys?|golden globes?|" +
        "tonys?|baftas?|game awards?|game of the year|goty|pulitzers?|hugos?|nebulas?|booker(?: prizes?)?|nobels?|nobel prizes?|vmas?|" +
        "brits|amas|razzies?|annies?|peabodys?|cannes|palme d'or|(?:sag|bet|mtv|billboard|brit|saturn|annie|peabody|hugo|nebula|tony|grammy|emmy|" +
        "kids'? choice|critics'? choice|american music|screen actors guild|golden raspberry)(?: music| video music| movie)? awards?"

    private const val WIN = "won|win|wins|gotten|got|get|gets|received|receive|earned|earn|take home|taken home|took home|been nominated for|nominated for|" +
        "been up for|up for|have|has|had|bagged|scored|picked up"

    // Polite and search lead-ins, cut before the forms below are matched.
    private val ASK_LEAD = Regex(
        "^(?:(?:hey |ok |okay )?brick[, ]+)?(?:(?:can you |could you |please |would you |do you know |i want to know |i'd like to know |tell me |" +
            "let me know |i was wondering |quick question |look up |lookup |search (?:for )?|google |find out |find |check )+)?",
    )

    private val ROLE_NOT_CHARACTER = Regex("^(?:the )?(?:main|lead|leading|star|main star|stars|actors?|actress(?:es)?|cast|leads?|main actors?|main characters?|characters?|" +
        "starring|acting|appearing|featured|playing|all)$")

    /** The forms for the newer questions: awards, characters, who plays whom, the main stars, and how long (or how many pages). */
    private fun special(s0: String): LookupQuery? {
        val s = s0.replace(ASK_LEAD, "").trim()
            // "what's the runtime of x" reads as "runtime of x".
            .replace(Patterns.get("^(?:what|which)(?:'s| is| are| was| were) (?:the )?(?=(?:duration|runtime|run time|running time|length|page count|number of pages|playtime|play time|" +
                "cast|cast list|characters|main characters|main cast|awards|accolades|nominations)\\b)"), "")
        fun g(p: String) = Patterns.get(p).find(s)?.groupValues
        fun q(subject: String, aspect: LookupAspect, default: LookupKind? = null, role: String? = null, award: String? = null): LookupQuery? {
            var t = subject.trim().replace(Patterns.get("\\s+(?:please|for me|real quick|right now|again|so far|ever|all time|in total|total|altogether|overall)$"), "").trim()
            var kind: LookupKind? = null
            val albumSaid = Patterns.get("\\b(?:album|ep|mixtape|record|lp|tracks|songs)\\b").containsMatchIn(s)
            kindSaid(t)?.let { (k, sub) -> kind = k; t = sub }
            t = t.replace(Patterns.get("^(?:the|a|an) (?=\\S+ \\S)"), "").trim().removeSuffix("'s").removeSuffix("s'").trim()
            if (t.isEmpty() || t.split(' ').size > 10) return null
            return LookupQuery(t, kind ?: default, aspect, role?.trim()?.ifBlank { null }, award?.trim()?.ifBlank { null }, album = albumSaid)
        }

        // When and how someone died: "when did lincoln die", "how did jfk die", "is queen elizabeth still alive".
        val pronoun = Patterns.get("^(?:i|you|we|they|he|she|it|me|us|them|my \\w+|your \\w+|this|that|everyone|anyone|someone|somebody|nobody)$")
        (g("^when did (.+?) (?:die|pass away|pass|passed away|get killed|get assassinated|get shot|kick the bucket)$")
            ?: g("^how did (.+?) (?:die|pass away|pass|get killed|get assassinated)$")
            ?: g("^(?:is|was) (.+?) (?:still )?(?:dead|alive|living|deceased)(?: or (?:dead|alive))?(?: still| yet| now)?$")
            ?: g("^did (.+?) (?:die|pass away)(?: yet)?$")
            ?: g("^when (?:is|was) (.+?)(?:'s|s') death$")
            ?: g("^what (?:did|was) (.+?) (?:die of|die from)$")
            ?: g("^(?:what was |what's |what is )?(?:the )?(?:cause of death|date of death|death date|time of death) (?:of|for) (.+)$")
            ?: g("^(.+?)(?:'s|s') (?:cause of death|death date|date of death)$")
            ?: g("^where did (.+?) die$"))?.let { m ->
            if (!pronoun.matches(m[1].trim())) return q(m[1], LookupAspect.DIED, LookupKind.PERSON)
        }
        // Why someone matters: "why is mlk important", "what is einstein known for", "what did tesla invent", "short bio of marie curie".
        (g("^why (?:is|was|are|were) (.+?) (?:so )?(?:important|famous|significant|notable|well known|known|remembered|a big deal|historic|historical|relevant|celebrated|honou?red|iconic|influential)$")
            ?: g("^what (?:is|was|are|were) (.+?) (?:best |most |mostly |mainly )?(?:known|famous|remembered|notable|noted) for$")
            ?: g("^what did (.+?) (?:do|accomplish|achieve|invent|discover|contribute|create|change|fight for|stand for|do for (?:the world|history|us|america|society|science))$")
            ?: g("^what (?:were|are|was|is) (.+?)(?:'s|s') (?:biggest |main |greatest |major |most important )?(?:accomplishments|achievements|contributions|legacy|claim to fame|inventions|discoveries)$")
            ?: g("^(?:what are )?(?:some )?(?:key|important|main|interesting|fun|cool) (?:points|facts|things|details) (?:about|on|of) (.+)$")
            ?: g("^(?:give me |tell me )?(?:a )?(?:short |brief |quick )?(?:bio|biography|life story) (?:of|on|for|about) (.+)$")
            ?: g("^(?:some )?facts about (.+)$")
            ?: g("^(.+?)(?:'s|s') (?:legacy|accomplishments|achievements|contributions|biography|bio|claim to fame|life story|inventions|discoveries)$")
            ?: g("^who (?:is|was) (.+?) and (?:why|what) (?:is|was|are|were|did) (?:he|she|they|it) (?:important|famous|do|known for)$"))?.let { m ->
            if (!pronoun.matches(m[1].trim())) return q(m[1], LookupAspect.KNOWN_FOR)
        }

        // Controversies: "what controversies has mrbeast been in", "why is logan paul controversial", "pewdiepie drama", "why was james charles cancelled".
        // ("drama" and "beef" only where they can't be a genre or a food: "pewdiepie's drama", "what drama has x been in".)
        val trouble = "controvers(?:y|ies)|scandals?|feuds?|backlash|allegations|accusations|lawsuits?|legal (?:issues|troubles|problems)"
        (g("^(?:what|which) (?:$trouble|drama|beef) (?:has|have|did|was|were|is) (.+?) (?:been |gotten |got )?(?:in|into|involved in|part of|had|caused|faced|start(?:ed)?|have)$")
            ?: g("^(?:has|have|did|was|is) (.+?) (?:ever )?(?:been )?(?:in|involved in|had|faced|caused|got into|gotten into|done) (?:any |some |a )?(?:$trouble|drama)$")
            ?: g("^(?:what(?:'s| is| are| were| was)? )?(?:the |all )?(?:$trouble) (?:of|about|around|with|involving|surrounding) (.+)$")
            ?: g("^(?:what(?:'s| is| was) )?(?:the )?(?:drama|beef) (?:with|between|around|surrounding) (.+)$")
            ?: g("^(?:tell me about |what(?:'s| is| are| were| was) |list )?(.+?)(?:'s|s')? (?:$trouble)$")
            ?: g("^(?:tell me about |what(?:'s| is| are| were| was) )?(.+?)(?:'s|s') (?:drama|beef)$")
            ?: g("^why (?:is|was|are|were) (.+?) (?:so )?(?:controversial|hated|disliked|cancell?ed|canceled|problematic|in trouble|banned|sued|getting hate|getting backlash|being sued)$")
            ?: g("^why do (?:people|so many people|fans|some people|everyone) (?:hate|dislike|not like|criticize|have a problem with) (.+)$")
            ?: g("^(?:is|was|did) (.+?) (?:get )?(?:cancell?ed|canceled|problematic|controversial)$")
            ?: g("^what did (.+?) do (?:wrong|bad)$")
            ?: g("^what (?:bad )?things (?:has|did) (.+?) done$"))?.let { m ->
            if (!pronoun.matches(m[1].trim()) && !Patterns.get("^(?:the|my|your|our|this|that|any|some|it)$").matches(m[1].trim())) return q(m[1], LookupAspect.CONTROVERSY, LookupKind.PERSON)
        }

        // Who plays whom: "who plays walter white in breaking bad", "who's the voice of mario in the mario movie", "who played neo".
        val notRole = Patterns.get("^(?:in|on|for|with|at|against|as|by|guitar|drums|bass|piano|keys|keyboard|the (?:guitar|drums|bass|piano)|it|that|this|music|songs?|games?|sports?|football|basketball|baseball|hockey|soccer|" +
            "born|living|alive|dead|famous|popular|better|best|worse|worst|winning|ranked|elected|president|ceo|king|queen|number|first|last|richest|tallest|oldest|youngest)\\b")
        (g("^who (?:plays|played|portrays|portrayed|voices|voiced|acts as|acted as|did the voice of|does the voice of|is the voice of|was the voice of|is the actor for|was the actor for|is the actor (?:who|that) (?:plays|played)|was the actor (?:who|that) played) (?:the (?:role|part|character) of )?(.+?) (?:in|on|from) (.+)$")
            ?: g("^(?:what actor|which actor|what actress|which actress|who) (?:plays|played|voices|voiced) (.+?) (?:in|on|from) (.+)$")
            // "who is walter white in breaking bad" (not "who is the best player in the nba").
            ?: g("^who(?:'s| is| was) (?!the |a |an )(.+?) (?:in|on|from) (.+)$"))?.let { m ->
            val role = m[1]
            if (role.split(' ').size <= 5 && !notRole.containsMatchIn(role)) {
                return if (ROLE_NOT_CHARACTER.matches(role)) q(m[2], LookupAspect.CAST) else q(m[2], LookupAspect.ROLE, role = role)
            }
        }
        g("^(?:who|what actor|which actor|what actress|which actress) (?:plays|played|portrays|portrayed|voices|voiced|does the voice of|did the voice of) (.+)$")?.let { m ->
            if (m[1].split(' ').size <= 5 && !notRole.containsMatchIn(m[1])) return q(m[1], LookupAspect.ROLE, role = m[1])
        }
        g("^who(?:'s| is| was) the (?:voice|voice actor|actor|actress) (?:of|for|behind) (.+)$")?.let { m ->
            if (m[1].split(' ').size <= 5) return q(m[1], LookupAspect.ROLE, role = m[1])
        }

        // The main stars: "who's the main star of top gun", "main actors in the office", "the office cast", "who was in jaws".
        (g("^who(?:'s| is| was| are| were) the (?:main |lead |leading |big |biggest )?(?:stars?|actors?|actress(?:es)?|cast|leads?|cast members) (?:of|in|on|for|from) (.+)$")
            ?: g("^(?:the )?(?:main |lead |leading |top |big )?(?:actors|actresses|stars|cast members|cast list|leads) (?:of|in|on|for|from) (.+)$")
            ?: g("^who (?:was|were|is|are) (?:the )?(?:main |lead )?(?:stars?|actors?) in (.+)$")
            ?: g("^(?:who (?:was|were) in|who acted in|who stars in|who starred in|who's starring in|who is starring in) (.+)$")
            ?: g("^(.+?)(?:'s|s')? (?:main |full |whole )?(?:cast|cast list)$"))?.let { m ->
            return q(m[1], LookupAspect.CAST)
        }

        // Characters: "who are the main characters in breaking bad", "characters in the hobbit", "zelda characters".
        (g("^(?:who|what) (?:are|were|is|was) (?:all )?(?:the )?(?:names of the )?(?:main |major |lead |key |important |big |central |primary )?characters? (?:in|of|from|on) (.+)$")
            ?: g("^(?:list|name|show me) (?:all )?(?:of )?(?:the )?(?:main |major )?characters (?:in|of|from|on) (.+)$")
            ?: g("^(?:all )?(?:the )?(?:main |major |lead |key |important )?characters? (?:in|of|from|on) (.+)$")
            ?: g("^how many characters (?:are )?(?:there )?(?:in|are in|does) (.+?)(?: have)?$")
            ?: g("^who(?:'s| is| was) the (?:main character|protagonist|hero|villain|antagonist|bad guy|narrator) (?:in|of) (.+)$")
            ?: g("^(.+?)(?:'s|s')? (?:main |major )?characters?(?: list)?$"))?.let { m ->
            return q(m[1], LookupAspect.CHARACTERS)
        }

        // Awards: "what awards did dune win", "did oppenheimer win best picture", "breaking bad awards", "how many grammys does taylor swift have".
        if (Patterns.get("\\b(?:$AW)\\b|\\bnominat|^(?:did|has|have) .+ (?:win|won) best |^what (?:has|did|have) .+ (?:won|win)$").containsMatchIn(s)) {
            val said = AWARD_NAMES.firstOrNull { it.said.containsMatchIn(s) }?.name
                ?: Patterns.get("\\b(best (?:[a-z']+ ?){1,4})$").find(s)?.groupValues?.get(1)?.trim()
            (g("^(?:what |which |how many |list (?:the |all )?(?:of )?|name (?:the )?|show me (?:the )?)(?:of the )?(?:big |major |main |top )?(?:$AW)(?: and (?:$AW))?(?: (?:has|have|did|does|do|was|were|is|are))? (.+?)(?: (?:$WIN))+(?: (?:in total|so far|ever|overall))?$")
                ?: g("^(?:what |which |how many |list (?:the |all )?|name (?:the )?)(?:big |major |main )?(?:$AW)(?: and (?:$AW))? (?:for|of|won by|did|does|has|have) (.+)$")
                ?: g("^(?:did|has|have|does|do|was|were|is) (.+?) (?:ever )?(?:$WIN|win|won)(?: .*)?$")
                ?: g("^what (?:was|is|were|are|has|have) (.+?) (?:been )?nominated for$")
                ?: g("^what (?:has|did|have) (.+?) (?:won|win)$")
                ?: g("^(?:the |all |all the )?(?:$AW)(?: and (?:$AW))? (?:for|of|won by|that|received by|given to|for the) (.+)$")
                ?: g("^(.+?)(?:'s|s')? (?:$AW)(?: and (?:$AW))?(?: (?:won|list|count|history|record))?$"))?.let { m ->
                return q(m[1], LookupAspect.AWARDS, award = said)
            }
        }

        // How long, in pages, minutes or play time.
        (g("^how many pages (?:is|are|does|do|in|has|have|was|were|long is)(?: there in)? (.+?)(?: (?:have|has|got|long))?$")
            ?: g("^(?:the )?(?:page count|number of pages|pages|word count) (?:of|for|in) (.+)$")
            ?: g("^(.+?)(?:'s|s')? (?:page count|number of pages)$"))?.let { m ->
            return q(m[1], LookupAspect.RUNTIME, LookupKind.BOOK)
        }
        (g("^how long (?:are|is) (?:the |an |each |a single )?episodes? (?:of|in|for|on) (.+)$")
            ?: g("^how long (?:are|is) (.+?) episodes?$"))?.let { m ->
            return q(m[1], LookupAspect.RUNTIME, LookupKind.TV_SHOW)
        }
        (g("^how (?:long|many hours) (?:does it take|would it take|will it take|is it|does it last|to) (?:to )?(?:beat|finish|complete|play through|playthrough|100%|platinum|get through) (.+)$")
            ?: g("^how (?:long|many hours) (?:is|does) (.+?) (?:take )?(?:to beat|to finish|to complete|to play through|playtime|gameplay|long to beat)$")
            ?: g("^(?:the )?(?:playtime|play time|time to beat|hours to beat|length to beat|game length|how long to beat) (?:of|for|in)? ?(.+)$")
            ?: g("^(.+?)(?:'s|s')? (?:playtime|play time|time to beat|game length|how long to beat)$"))?.let { m ->
            return q(m[1], LookupAspect.RUNTIME, LookupKind.GAME)
        }
        g("^how many (?:songs|tracks) (?:are )?(?:on|in|does) (.+?)(?: have)?$")?.let { m ->
            return q(m[1], LookupAspect.RUNTIME, LookupKind.MUSIC)
        }
        (g("^how (?:long|many minutes|many hours) (?:is|was|are|were|does|do) (?:the )?(.+?)(?: (?:run|last|go for|go|take))?$")
            ?: g("^(?:the )?(?:duration|runtime|run time|running time|length|play length|total length|play time|how long) (?:of|for) (.+)$")
            ?: g("^(.+?)(?:'s|s')? (?:runtime|run time|running time|duration|length)$"))?.let { m ->
            // Not "how long is left on my timer" or "how long is the drive to work".
            if (!Patterns.get("^(?:it|you|i|we|they|he|she|my|your|this|that)\\b|\\bto (?:get|go|drive|walk|fly|cook|make|bake|boil|grill|roast|charge)\\b|" +
                    "\\b(?:left|until|till|ago|since|drive|walk|flight|ride|trip|commute|wait|line|queue|timer|alarm|stopwatch|battery|charge)\\b").containsMatchIn(m[1])) {
                return q(m[1], LookupAspect.RUNTIME, LookupKind.MOVIE)
            }
        }
        return null
    }

    /** The award's Wikidata name pattern for what was said ("oscar" -> "Academy Award"), or the words ("best picture"). */
    private fun awardLabel(award: String): Regex =
        AWARD_NAMES.firstOrNull { it.name.equals(award, true) || it.said.containsMatchIn(award) }?.label
            ?: Regex(Regex.escape(award), RegexOption.IGNORE_CASE)

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

    private val WORKS = setOf(LookupKind.MOVIE, LookupKind.TV_SHOW, LookupKind.GAME, LookupKind.BOOK, LookupKind.MUSIC)
    private val CHARACTER_PAGE = Regex("\\b(?:fictional character|character (?:in|from|of)|protagonist|antagonist|video game character|comic book character)\\b", RegexOption.IGNORE_CASE)
    private val ALBUM_PAGE = Regex("\\b(?:album|ep|mixtape|soundtrack|compilation|extended play|live album)\\b", RegexOption.IGNORE_CASE)

    /**
     * Finds the page for [q]: the first search hit of the right kind (by its description), skipping disambiguation pages,
     * with Wikidata's facts. When Wikipedia or Wikidata don't have what was asked (a show's cast with their characters,
     * a book's pages, an album's length), [LookupExtras] fills it in; with no Wikipedia page at all, the other sources
     * answer on their own. [tmdbKey] (from settings) lets TMDB answer for movies.
     */
    suspend fun find(q: LookupQuery, tmdbKey: String = ""): LookupResult? = withContext(Dispatchers.IO) {
        val noShow = q.aspect == LookupAspect.ROLE && q.role == q.subject
        val workAsk = q.aspect in setOf(LookupAspect.CAST, LookupAspect.CHARACTERS, LookupAspect.ROLE) && !noShow
        val accept = setOfNotNull(q.kind).ifEmpty { if (workAsk) WORKS else emptySet() }
        val p = page(q.subject, accept, listOfNotNull(q.kind?.searchWord))
        if (p == null || (workAsk && q.kind == null && p.kind !in WORKS)) {
            // No page (or not a show, movie, game...): the other sources on their own.
            val kinds = q.kind?.let { listOf(it) } ?: if (workAsk || q.aspect == LookupAspect.RUNTIME) listOf(LookupKind.TV_SHOW, LookupKind.MOVIE, LookupKind.BOOK, LookupKind.GAME) else emptyList()
            var first: MediaExtra? = null
            var firstKind: LookupKind? = null
            for (k in kinds) {
                val e = LookupExtras.forKind(k, q.subject, null, null, q.album, tmdbKey) ?: continue
                // "Who plays neo in the matrix": the title whose cast has that character (the film, not a 1993 show called Matrix).
                if (q.aspect == LookupAspect.ROLE && q.role != null && matchRole(q.role, e.cast) == null && k != kinds.last()) {
                    if (first == null) { first = e; firstKind = k }
                    continue
                }
                return@withContext build(q, e.title, e.description, k, e.summary ?: "", e.image, e.url ?: "", null, emptyMap(), e, listOf(e.source))
            }
            first?.let { e -> return@withContext build(q, e.title, e.description, firstKind, e.summary ?: "", e.image, e.url ?: "", null, emptyMap(), e, listOf(e.source)) }
            if (p == null) return@withContext null
        }
        val isCharacter = p.description?.let { CHARACTER_PAGE.containsMatchIn(it) } == true
        val kind = if (isCharacter) null else p.kind ?: q.kind
        val claims = p.qid?.let { claims(it) }
        val more = when (q.aspect) {
            LookupAspect.CHARACTERS -> listOf("P674")
            LookupAspect.RUNTIME, LookupAspect.SEASONS -> listOf("P2047", "P1104", "P1113", "P2437", "P175", "P50")
            else -> emptyList()
        } + if (isCharacter) listOf("P175", "P725", "P1441") else emptyList()
        val facts = if (claims != null) facts(claims, kind, more) else emptyMap()
        val name = p.title.substringBefore(" (").trim()
        // Another source, when the question needs one Wikidata often lacks.
        val extra = if (!isCharacter && kind in WORKS && q.aspect in setOf(LookupAspect.CAST, LookupAspect.CHARACTERS, LookupAspect.ROLE, LookupAspect.RUNTIME, LookupAspect.SEASONS)) {
            LookupExtras.forKind(kind, name, (facts["P175"] ?: facts["P50"])?.firstOrNull(), year(facts["P577"]?.firstOrNull() ?: facts["P580"]?.firstOrNull()),
                q.album || p.description?.let { ALBUM_PAGE.containsMatchIn(it) } == true, tmdbKey)
        } else null
        build(q, p.title, p.description, kind, shorten(p.extract), p.image, p.url, claims, facts, extra, listOf("Wikipedia", "Wikidata"), isCharacter)
    }

    private fun Credit.said() = if (character != null) "$actor as $character" else actor

    /** How long, said: "2 hours and 35 minutes", "22 minutes", "4 minutes and 3 seconds". */
    fun spokenLength(secs: Int, music: Boolean = false): String {
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        fun u(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
        return when {
            h > 0 -> u(h, "hour") + if (m > 0) " and " + u(m, "minute") else ""
            music && s > 0 -> (if (m > 0) u(m, "minute") + " and " else "") + u(s, "second")
            else -> u(maxOf(m, 1), "minute")
        }
    }

    private fun shortLength(secs: Int, music: Boolean = false): String {
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            music -> "%d:%02d".format(Locale.US, m, s)
            else -> "$m min"
        }
    }

    /** Seconds from a Wikidata length ("8580 sec"). */
    private fun secs(v: String?) = v?.removeSuffix(" sec")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }

    /** The character that best matches [role] ("neo" -> "Thomas A. Anderson / Neo"). */
    private fun matchRole(role: String, cast: List<Credit>): Credit? {
        fun words(s: String) = s.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in setOf("the", "of", "mr", "mrs", "dr", "jr", "sr") }.toSet()
        val want = words(role).ifEmpty { return null }
        return cast.filter { it.character != null }
            .map { it to (words(it.character!!) intersect want).size }
            .filter { (_, n) -> n >= minOf(want.size, 2) || n == want.size }
            .maxWithOrNull(compareBy<Pair<Credit, Int>> { it.second }.thenBy { -it.first.character!!.length })?.first
    }

    /** The result card and answer for [q], from Wikipedia/Wikidata ([claims], [facts]) and/or another source ([extra]). */
    private suspend fun build(
        q: LookupQuery, title: String, description: String?, kind: LookupKind?, summary0: String, image: String?, url: String,
        claims: JSONObject?, facts: Map<String, List<String>>, extra: MediaExtra?, sources0: List<String>, isCharacter: Boolean = false,
    ): LookupResult {
        val rows = factRows(kind, facts).toMutableList()
        if (claims != null && (kind == LookupKind.MOVIE || kind == LookupKind.TV_SHOW || kind == LookupKind.GAME)) {
            MediaGuide.shortScores(reviewScores(claims))?.let { rows += "RATED" to it }
        }
        val name = title.substringBefore(" (").trim()
        val sources = sources0.toMutableList()
        fun used(e: MediaExtra?) { if (e != null && e.source !in sources) sources += e.source }
        fun row(label: String, v: String?) { if (!v.isNullOrBlank() && rows.none { it.first == label }) rows += label to v }
        var answer = answerFor(q.aspect, name, kind, facts)
        var summary = summary0
        var list = emptyList<String>()
        var listTitle: String? = null
        val links = ArrayList<Pair<String, String>>()
        // With no Wikipedia page, the other source's facts make the rows.
        if (claims == null && extra != null) {
            row("YEAR", extra.year)
            row("BY", list(extra.by, 2))
            row("ON", extra.network)
            row("SEASONS", extra.seasons?.toString())
            row("EPS", extra.episodes?.toString())
            row("GENRE", list(extra.genres, 2))
        }
        val wdCast = if (claims != null && q.aspect in setOf(LookupAspect.CAST, LookupAspect.CHARACTERS, LookupAspect.ROLE)) credits(claims) else emptyList()
        // TVmaze's main cast and TMDB's billing order beat Wikidata's unordered list; Wikidata beats Apple and Steam (no cast).
        val cast = when {
            extra != null && extra.cast.isNotEmpty() && (extra.source == "TVmaze" || extra.source == "TMDB" || wdCast.none { it.character != null }) -> extra.cast.also { used(extra) }
            wdCast.isNotEmpty() -> wdCast
            else -> emptyList()
        }
        when (q.aspect) {
            LookupAspect.CAST -> if (cast.isNotEmpty()) {
                answer = "$name stars ${list(cast.take(4).map { it.said() }, 4)}."
                list = cast.take(12).map { it.said() }
                listTitle = "CAST"
            } else if (answer == null) answer = "I couldn't find the cast of $name."
            LookupAspect.CHARACTERS -> {
                var chars = facts["P674"].orEmpty()
                if (chars.isEmpty()) chars = cast.mapNotNull { it.character }.distinct()
                if (chars.isEmpty() && extra != null && extra.characters.isNotEmpty()) { chars = extra.characters; used(extra) }
                if (chars.isNotEmpty()) {
                    answer = "The main characters in $name include ${list(chars.take(6), 6)}."
                    list = chars.take(15).map { c -> cast.firstOrNull { it.character == c }?.let { "$c - ${it.actor}" } ?: c }
                    listTitle = "CHARACTERS"
                } else {
                    val page = LookupExtras.characterList(name)
                    answer = if (page != null) {
                        links += "Characters" to page.second
                        if ("Wikipedia" !in sources) sources += "Wikipedia"
                        "Wikipedia has a full ${page.first.removePrefix("List of ")} page. Tap Characters to see it."
                    } else "I couldn't find a list of characters for $name."
                }
            }
            LookupAspect.ROLE -> {
                val role = q.role ?: q.subject
                if (isCharacter) {
                    val by = facts["P175"] ?: facts["P725"]
                    val work = facts["P1441"]?.firstOrNull()
                    answer = by?.let { "${title.substringBefore(" (")} is played by ${list(it, 3)}" + (work?.let { w -> " in $w" } ?: "") + "." }
                        ?: "I couldn't find who plays ${title.substringBefore(" (")}."
                } else {
                    val hit = matchRole(role, cast)
                    answer = if (hit != null) "In $name, ${hit.character} is played by ${hit.actor}." else "I couldn't find who plays ${titled(role)} in $name."
                    if (cast.isNotEmpty()) { list = cast.take(12).map { it.said() }; listTitle = "CAST" }
                }
            }
            LookupAspect.RUNTIME, LookupAspect.SEASONS -> {
                val wdSecs = secs(facts["P2047"]?.firstOrNull())
                val len = wdSecs ?: extra?.seconds?.also { used(extra) }
                when (kind) {
                    LookupKind.MOVIE -> len?.let {
                        answer = "$name runs ${spokenLength(it)}."
                        row("LENGTH", shortLength(it))
                    }
                    LookupKind.TV_SHOW -> {
                        val seasons = facts["P2437"]?.firstOrNull()?.substringBefore('.')?.toIntOrNull() ?: extra?.seasons?.also { used(extra) }
                        val eps = facts["P1113"]?.firstOrNull()?.substringBefore('.')?.toIntOrNull() ?: extra?.episodes?.also { used(extra) }
                        val count = seasons?.let { s -> "$s season${if (s == 1) "" else "s"}" + (eps?.let { " and $it episodes" } ?: "") }
                        if (q.aspect == LookupAspect.RUNTIME && len != null) {
                            answer = "Episodes of $name run about ${spokenLength(len)}." + (count?.let { " It has $it." } ?: "")
                        } else if (count != null) answer = "$name has $count."
                        row("SEASONS", seasons?.toString()); row("EPS", eps?.toString())
                        len?.let { row("EPISODE", shortLength(it)) }
                    }
                    LookupKind.MUSIC -> {
                        val tracks = extra?.tracks?.also { used(extra) }
                        val album = q.album || description?.let { ALBUM_PAGE.containsMatchIn(it) } == true || tracks != null && tracks > 1
                        len?.let { answer = "$name is ${spokenLength(it, music = true)} long" + (tracks?.takeIf { album }?.let { t -> ", with $t tracks" } ?: "") + "." }
                        len?.let { row("LENGTH", shortLength(it, music = true)) }
                        if (album) row("TRACKS", tracks?.toString())
                    }
                    LookupKind.BOOK -> {
                        val pages = facts["P1104"]?.firstOrNull()?.substringBefore('.')?.toIntOrNull() ?: extra?.pages?.also { used(extra) }
                        pages?.let { answer = "$name is about ${"%,d".format(Locale.US, it)} pages long. It varies a little by edition."; row("PAGES", "%,d".format(Locale.US, it)) }
                    }
                    LookupKind.GAME -> {
                        answer = "I don't have play-time numbers for $name. HowLongToBeat keeps track of how long games take to finish."
                        links += "HowLongToBeat" to "https://howlongtobeat.com/?q=${Http.enc(name)}"
                    }
                    else -> {}
                }
                if (answer == null && kind in WORKS) answer = "I couldn't find how long $name is."
            }
            LookupAspect.AWARDS -> {
                val (wins, noms) = if (claims != null) awards(claims) else emptyList<String>() to emptyList()
                val acc = LookupExtras.accolades(name)
                // Wikipedia's list says the counts in words ("received thirteen nominations ... winning seven: Best Picture, ...").
                val accSentences = acc?.second?.let { Patterns.get("(?<=[.!?])\\s+(?=[A-Z])").split(it) }.orEmpty()
                    .map {
                        it.replace(Patterns.get("\\s*\\([^)]*\\)"), "")
                            // Side notes: ", surpassing ... to become Nolan's most Oscar-nominated film, winning seven" -> ", winning seven".
                            .replace(Patterns.get(", (?:surpassing|tying|breaking|matching|making it|becoming|which|setting|the most|a record)\\b.*?(?=, (?:winning|and won|won|including|and winning)\\b)", true), "")
                            .trim()
                    }
                val counting = Patterns.get("\\b(?:won|wins|winning|received|receiving|nominat\\w*|awarded|earned|earning)\\b", true)
                val prizeWord = Patterns.get("\\b(?:awards?|nominations?|oscars?|emmys?|grammys?|globes?|prizes?)\\b", true)
                val accCounts = accSentences.filter { counting.containsMatchIn(it) && prizeWord.containsMatchIn(it) }
                val accSaid = accCounts.take(2).joinToString(" ").ifBlank { null } ?: acc?.let { shorten(it.second) }
                val award = q.award
                answer = if (award != null) {
                    val rx = awardLabel(award)
                    val known = AWARD_NAMES.firstOrNull { it.name.equals(award, true) }
                    val w = wins.filter { rx.containsMatchIn(it) }
                    val n = noms.filter { rx.containsMatchIn(it) && it !in w }
                    fun cat(s: String) = if (known != null) s.substringAfter(" for ", s) else s
                    val accAward = accCounts.firstOrNull { rx.containsMatchIn(it) || known?.said?.containsMatchIn(it) == true }
                    when {
                        // Wikipedia's list is more complete than Wikidata (7 Oscars for Oppenheimer, where Wikidata has 4).
                        accAward != null -> accAward
                        w.isNotEmpty() && known != null -> "$name won ${w.size} ${known.name}${if (w.size == 1) "" else "s"}" +
                            (w.map(::cat).distinct().filter { it.isNotBlank() }.take(3).takeIf { it.isNotEmpty() }?.let { ", including ${list(it)}" } ?: "") + "."
                        w.isNotEmpty() -> "$name won ${list(w.distinct(), 3)}."
                        n.isNotEmpty() -> "$name was nominated for ${list(n.map(::cat).distinct(), 3)}, but Wikidata doesn't list a win."
                        accSaid != null -> accSaid
                        wins.isNotEmpty() -> "I don't see ${known?.name?.let { if (it.first().lowercaseChar() in "aeiou") "an $it" else "a $it" } ?: "a ${titled(award)}"} win for $name. " +
                            "Wikidata lists ${wins.size} other award${if (wins.size == 1) "" else "s"}."
                        else -> "I couldn't find any awards listed for $name."
                    }
                } else when {
                    accCounts.isNotEmpty() -> accSaid
                    wins.isNotEmpty() -> "Wikidata lists ${wins.size} award${if (wins.size == 1) "" else "s"} for $name, including ${list(wins.distinct().take(3))}" +
                        (noms.size.takeIf { it > 0 }?.let { " and $it more nomination${if (it == 1) "" else "s"}" } ?: "") + "."
                    accSaid != null -> accSaid
                    noms.isNotEmpty() -> "$name was nominated for ${list(noms.distinct(), 3)}."
                    else -> "I couldn't find any awards listed for $name."
                }
                if (wins.isNotEmpty()) {
                    val counted = wins.groupingBy { it }.eachCount()
                    list = counted.entries.take(12).map { (a, c) -> if (c > 1) "$a (x$c)" else a }
                    listTitle = "AWARDS"
                    row("AWARDS", wins.size.toString())
                }
                if (noms.isNotEmpty()) row("NOMS", noms.size.toString())
                if (acc != null) {
                    accCounts.filter { it != answer && answer?.contains(it) != true }.take(3).joinToString(" ").ifBlank { null }?.let { summary = it }
                    if ("Wikipedia" !in sources) sources += "Wikipedia"
                    links += "All awards" to acc.third
                }
            }
            LookupAspect.KNOWN_FOR -> {
                // The whole intro says what they did; the sentences that say why they matter answer.
                val intro = Sports.intro(title)?.second
                val all = intro?.let { t -> Patterns.get("(?<=[.!?])\\s+(?=[A-Z])").split(t.replace(Patterns.get("\\s*\\([^)]*\\)"), "")).map { it.trim() }.filter { it.length > 25 } }.orEmpty()
                val why = Patterns.get("\\b(?:known for|best known|famous for|remembered (?:for|as)|regarded as|considered|one of the (?:most|greatest|first|leading)|widely|first|pioneer\\w*|led|founded|invented|discovered|developed|won|helped|played a (?:key|major|central|leading) role|credited|legacy|influen\\w*|landmark|instrumental)\\b", true)
                val picked = all.drop(1).filter { why.containsMatchIn(it) }.take(2)
                val lead = all.firstOrNull() ?: summary
                // Said briefly: the first sentence (and one reason when it's short); the card lists the rest.
                answer = Sports.brief(if (lead.length < 90 && picked.isNotEmpty()) "$lead ${picked.first()}" else lead, 230)
                if (all.size > 1) { list = all.drop(1).filter { it.length <= 260 }.take(6); listTitle = "KEY POINTS" }
            }
            else -> {}
        }
        // YouTubers, streamers and other creators: who they are, what they're known for, their channel and controversies.
        val creator = Creators.isCreator(description, summary0)
        if (q.aspect == LookupAspect.CONTROVERSY || creator && q.aspect in setOf(LookupAspect.GENERAL, LookupAspect.KNOWN_FOR)) {
            val names = Characters.tokens(name)
            val said = Creators.controversies(Characters.wikiText(title).orEmpty(), names)
            val channel = if (creator) Creators.channel(claims) else null
            channel?.subscribers?.let { row("SUBS", Creators.bigCount(it) + (channel.asOf?.take(4)?.let { y -> " ($y)" } ?: "")) }
            channel?.let { links += "YouTube" to it.url }
            if (said.isNotEmpty()) {
                list = said.map { c -> c.topic?.let { t -> "$t: ${Sports.brief(c.said, 200)}" } ?: Sports.brief(c.said, 220) }
                listTitle = "CONTROVERSIES"
            }
            answer = if (q.aspect == LookupAspect.CONTROVERSY) Creators.spoken(name, said) else {
                // "Who is mrbeast": who they are and what they're known for, then the controversies in a line.
                val intro = Sports.intro(title)?.second ?: summary0
                val all = Patterns.get("(?<=[.!?])\\s+(?=[A-Z])").split(intro.replace(Patterns.get("\\s*\\([^)]*\\)"), "")).map { it.trim() }.filter { it.length > 25 }
                val known = all.drop(1).firstOrNull { Patterns.get("\\b(?:known for|best known|famous for|popular for|rose to (?:fame|prominence)|gained (?:fame|popularity|prominence)|recognized for|noted for|videos|channel|content)\\b", true).containsMatchIn(it) }
                // Said short: what they're known for and the subscribers; the card has the rest (and the controversies).
                val subs = channel?.subscribers?.let { " About ${Creators.bigCount(it)} subscribers." } ?: ""
                (known ?: all.firstOrNull())?.let { Sports.brief(it, 130) }.orEmpty() + subs
            }
        }
        return LookupResult(title, description, kind, summary, image ?: extra?.image, url.ifBlank { extra?.url.orEmpty() }, rows, answer, sources, list, listTitle, links)
    }

    private fun titled(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }

    /** The cast (P161) with each actor's character (qualifier P453, or the role's name P4633), in Wikidata's order. */
    private suspend fun credits(claims: JSONObject): List<Credit> {
        val arr = claims.optJSONArray("P161") ?: return emptyList()
        data class Raw(val actor: String, val charId: String?, val charName: String?)
        val raws = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i)?.takeIf { it.optString("rank") != "deprecated" } ?: return@mapNotNull null
            val actor = c.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id") ?: return@mapNotNull null
            val qual = c.optJSONObject("qualifiers")
            Raw(actor,
                qual?.optJSONArray("P453")?.optJSONObject(0)?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id"),
                qual?.optJSONArray("P4633")?.optJSONObject(0)?.optJSONObject("datavalue")?.optString("value"))
        }.take(20)
        if (raws.isEmpty()) return emptyList()
        val names = labels(raws.flatMap { listOfNotNull(it.actor, it.charId) })
        return raws.mapNotNull { r -> names[r.actor]?.let { Credit(it, r.charName ?: r.charId?.let { id -> names[id] }) } }
            .distinctBy { it.actor }
    }

    /** Awards won (P166) and nominations (P1411), by name, one entry per statement. */
    private suspend fun awards(claims: JSONObject): Pair<List<String>, List<String>> {
        fun ids(p: String) = claims.optJSONArray(p)?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.takeIf { it.optString("rank") != "deprecated" }?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")
            }
        }.orEmpty()
        val won = ids("P166")
        val nom = ids("P1411")
        if (won.isEmpty() && nom.isEmpty()) return emptyList<String>() to emptyList()
        val names = labels((won + nom).distinct())
        return won.mapNotNull { names[it] } to nom.mapNotNull { names[it] }
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
        LookupKind.PERSON -> listOf("P569", "P570", "P106", "P19", "P27", "P20", "P509")
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
    private suspend fun facts(all: JSONObject, kind: LookupKind?, more: List<String> = emptyList()): Map<String, List<String>> = coroutineScope {
        val claims = (props(kind) + more).distinct().map { p -> p to all.optJSONArray(p) }
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
                        // Lengths in seconds ("8580 sec"), whatever unit Wikidata used.
                        val n = amount.toDoubleOrNull()
                        when {
                            n != null && unit == "Q7727" -> "${(n * 60).toInt()} sec"
                            n != null && unit == "Q11574" -> "${n.toInt()} sec"
                            n != null && unit == "Q25235" -> "${(n * 3600).toInt()} sec"
                            unit == "Q712226" -> "$amount km2"
                            else -> amount
                        }
                    }
                    "string" -> v.optString("value")
                    else -> null
                }
            }
            if (values.isNotEmpty()) raw[p] = values.take(when (p) { "P161" -> 6; "P674" -> 15; else -> 4 })
        }
        val labels = labels(itemIds.take(80))
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

    /** Wikidata's facts for [props], by property (for [History]). */
    suspend fun factsFor(claims: JSONObject, props: List<String>): Map<String, List<String>> = facts(claims, null, props)
    fun prettyDate(d: String?): String? = pretty(d)
    fun dateOf(d: String?): LocalDate? = date(d)
    fun listSaid(v: List<String>?, n: Int = 3): String? = list(v, n)

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
                add("LENGTH", secs(f["P2047"]?.firstOrNull())?.let { shortLength(it) })
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
        LookupAspect.RUNTIME -> secs(f["P2047"]?.firstOrNull())?.let { "$name runs ${spokenLength(it, kind == LookupKind.MUSIC)}." }
        LookupAspect.HEADQUARTERS -> f["P159"]?.firstOrNull()?.let { "$name is based in $it." }
        LookupAspect.OWNER -> f["P127"]?.firstOrNull()?.let { "$name is owned by $it." }
        LookupAspect.DIED -> {
            val born = f["P569"]?.firstOrNull()
            val died = f["P570"]?.firstOrNull()
            if (died == null) {
                if (kind == LookupKind.PERSON) age(born, null)?.let { "$name is still alive, at $it years old." } ?: "Wikidata doesn't list a death date for $name." else null
            } else pretty(died)?.let { d ->
                "$name died " + (if (d.contains(',')) "on $d" else "in $d") + (f["P20"]?.firstOrNull()?.let { " in $it" } ?: "") +
                    (age(born, died)?.let { ", at the age of $it" } ?: "") + "." + (f["P509"]?.firstOrNull()?.let { " Cause of death: $it." } ?: "")
            }
        }
        LookupAspect.NET_WORTH, LookupAspect.GENERAL, LookupAspect.AWARDS, LookupAspect.CHARACTERS, LookupAspect.ROLE, LookupAspect.KNOWN_FOR, LookupAspect.CONTROVERSY -> null
    }
}
