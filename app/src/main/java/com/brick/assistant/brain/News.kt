package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A news section, with the words that ask for it and its Google News feed (or a search when it has none). */
enum class NewsTopic(val label: String, val feed: String?, val search: String?, words: String) {
    TOP("top stories", null, null, ""),
    BREAKING("breaking news", null, null, "breaking|just happened|happening right now|developing|urgent|big news|major news"),
    WORLD("world news", "WORLD", null,
        "world|worldwide|international|international affairs|foreign|foreign affairs|foreign policy|global|globally|geopolitics|geopolitical|abroad|overseas"),
    NATION("U.S. news", "NATION", null, "us|u s|usa|national|domestic|american|america|the country|the us|the u s|the states|nationwide"),
    POLITICS("politics", null, "politics", "politics|political|politic|government|congress|the white house|white house|elections?|senate|washington|policy"),
    BUSINESS("business and economy", "BUSINESS", null,
        "business|businesses|economy|economics|economic|economical|finance|financial|markets?|the market|stock market|stocks|wall street|money|trade|jobs|inflation|banking|corporate"),
    TECH("tech", "TECHNOLOGY", null,
        "tech|technology|technological|gadgets?|computers?|computing|ai|a i|artificial intelligence|silicon valley|startups?|software|smartphones?|phones?|internet|cyber|cybersecurity|gaming|video games?"),
    SCIENCE("science", "SCIENCE", null,
        "science|scientific|space|nasa|astronomy|physics|biology|chemistry|research|discoveries|discovery|climate|environment|environmental|nature"),
    HEALTH("health", "HEALTH", null, "health|medical|medicine|healthcare|health care|fitness|wellness|diseases?|covid|vaccines?"),
    SPORTS("sports", "SPORTS", null, "sports?|sporting|nfl|nba|mlb|nhl|soccer|football|basketball|baseball|hockey|tennis|golf|f1|formula one|olympics"),
    ENTERTAINMENT("entertainment", "ENTERTAINMENT", null,
        "entertainment|celebrity|celebrities|celeb|hollywood|movies?|films?|tv|television|music|pop culture|showbiz|show business"),
    ;

    val rx: Regex? = if (words.isEmpty()) null else Regex("\\b(?:$words)\\b")
}

data class Headline(val title: String, val source: String?, val link: String, val published: ZonedDateTime?)

object News {
    // ------------------------------------------------------------------ understanding the request

    /** Words that mean news. */
    private val NEWS = Patterns.get("\\b(?:news|headlines?|top stories|the stories|current events|breaking (?:news|stories|headlines|story)|anything breaking|what'?s breaking|newsflash|news flash|briefing|news briefing|updates? on|latest on|the latest (?:on|in|with|about|from)|what'?s happening|what is happening|what'?s going on|what is going on|anything happening|anything new (?:in|with|on)|what'?s new (?:in|with|on)|what is new (?:in|with|on))\\b")
    /** Requests that say "news" but mean something else. */
    private val NOT_NEWS = Patterns.get("^(?:open|launch|start|play|close|turn|text|send|call|tell \\w+ )|\\b(?:news app|google news app|good news|bad news|news anchor|newscaster|newsletter|newspaper (?:delivery|subscription)|notifications?|emails?|e mails?|inbox|messages|texts|missed calls?|voicemails?|watch|stream|buy|rent|where can)\\b|\\bwhat'?s going on (?:with you|with me|here)$|\\bnew (?:movie|film|season|show|episode|album|song)s?\\b|\\b(?:most recent|newest|latest) (?:\\w+ )?(?:movie|film|season|show|episode)\\b")
    /** A word that clearly asks for news (not "what's going on with my phone"). */
    private val NEWS_WORD = Patterns.get("\\b(?:news|headlines?|stories|current events|breaking|briefing|newsflash|news flash|updates?|the latest)\\b")
    /** Words that aren't a subject ("tell me the latest news please"). */
    private val FILLER = Regex(
        "\\b(?:what'?s|what is|what are|whats|tell me|give me|read me|read|show me|show|get me|get|catch me up on|catch me up|fill me in on|fill me in|brief me on|brief me|update me on|update me|" +
            "i want|i'd like|i would like|can you|could you|would you|any|anything|some|the|a|an|me|my|today'?s|todays|today|tonight|this morning|this afternoon|this evening|right now|now|currently|" +
            "latest|newest|recent|most recent|current|new|top|main|biggest|big|important|major|trending|hot|headlines?|news|stories|story|updates?|happening|going on|in|on|about|with|from|for|regarding|around|of|" +
            "is|are|there|please|brick|hey|events|current events|briefing|did i miss|what did i miss|lately|this week|in the world of|world of|so far|real quick|quick|happened|been|has|have)\\b",
    )

    /** "what's the tech news", "economy headlines", "any news about the election", "what's happening in ukraine". */
    fun parse(s: String): Request.News? {
        val said = s.lowercase(Locale.US).replace('’', '\'').trim()
        if (!NEWS.containsMatchIn(said) || NOT_NEWS.containsMatchIn(said)) return null
        if (said.split(' ').size > 16) return null
        // "what's going on" about you or your phone isn't news.
        if (!NEWS_WORD.containsMatchIn(said) && Patterns.get("\\b(?:my|mine|this|that|it|you|me|here|i)\\b").containsMatchIn(said)) return null
        // An outlet ("from the verge", "reuters headlines") narrows the search; it isn't the subject.
        val pub = Publications.find(said) ?: Publications.named(said)
        val source = pub?.first
        val t = if (pub == null) said else said.removeRange(pub.second).replace(Patterns.get("\\s+"), " ").trim()
        val breaking = NewsTopic.BREAKING.rx!!.containsMatchIn(t)
        // A section by its words; the most specific wins (politics over world, tech over business).
        val order = listOf(NewsTopic.POLITICS, NewsTopic.TECH, NewsTopic.SCIENCE, NewsTopic.HEALTH, NewsTopic.SPORTS, NewsTopic.ENTERTAINMENT,
            NewsTopic.BUSINESS, NewsTopic.WORLD, NewsTopic.NATION)
        val rest = subject(t)
        val topic = order.firstOrNull { it.rx!!.containsMatchIn(t) && (rest == null || it.rx.matches(rest)) }
        // Anything else that's left is a subject to search ("news about ukraine", "taylor swift news").
        if (topic == null && rest != null) return Request.News(if (breaking) NewsTopic.BREAKING else NewsTopic.TOP, rest, source)
        // "what's happening" / "what's going on" alone are news only with a news-ish word or place ("in the world").
        if (topic == null && !NEWS_WORD.containsMatchIn(t) && source == null) return null
        return Request.News(topic ?: if (breaking) NewsTopic.BREAKING else NewsTopic.TOP, null, source)
    }

    /** What's left once the news words and filler are gone: a subject, or null. */
    fun subject(t: String): String? {
        var r = " $t "
        repeat(3) { r = r.replace(FILLER, " ") }
        r = r.replace(NewsTopic.BREAKING.rx!!, " ").replace(Patterns.get("\\s+"), " ").trim()
        r = r.replace(Patterns.get("^(?:'s|s|and|or|to|for|at|of)\\b\\s*"), "").replace(Patterns.get("\\s*\\b(?:and|or|to|for|at|of)$"), "").trim()
        return r.ifEmpty { null }
    }

    // ------------------------------------------------------------------ fetching

    private const val LOCALE = "hl=en-US&gl=US&ceid=US:en"

    fun feedUrl(topic: NewsTopic, query: String?, source: Publication? = null): String = when {
        source != null -> "https://news.google.com/rss/search?q=${Http.enc(listOfNotNull(query, topic.search, source.domain?.let { "site:$it" } ?: "\"${source.name}\"").joinToString(" ") + " when:2d")}&$LOCALE"
        query != null -> "https://news.google.com/rss/search?q=${Http.enc("$query when:3d")}&$LOCALE"
        topic.search != null -> "https://news.google.com/rss/search?q=${Http.enc("${topic.search} when:1d")}&$LOCALE"
        topic.feed != null -> "https://news.google.com/rss/headlines/section/topic/${topic.feed}?$LOCALE"
        else -> "https://news.google.com/rss?$LOCALE"
    }

    /** Where to read more (the same feed's web page). */
    fun pageUrl(topic: NewsTopic, query: String?, source: Publication? = null): String = when {
        source != null -> "https://news.google.com/search?q=${Http.enc(listOfNotNull(query, topic.search, source.domain?.let { "site:$it" } ?: "\"${source.name}\"").joinToString(" "))}&$LOCALE"
        query != null -> "https://news.google.com/search?q=${Http.enc(query)}&$LOCALE"
        topic.search != null -> "https://news.google.com/search?q=${Http.enc(topic.search)}&$LOCALE"
        topic.feed != null -> "https://news.google.com/headlines/section/topic/${topic.feed}?$LOCALE"
        else -> "https://news.google.com/home?$LOCALE"
    }

    suspend fun fetch(topic: NewsTopic, query: String?, now: ZonedDateTime = ZonedDateTime.now(), source: Publication? = null): List<Headline> = withContext(Dispatchers.IO) {
        val xml = Http.getTextCached(feedUrl(topic, query, source)) ?: return@withContext emptyList()
        var items = parseRss(xml)
        // An outlet known only by name: keep its own stories when the search found some.
        if (source != null && source.domain == null) items.filter { Publications.matches(it, source) }.takeIf { it.isNotEmpty() }?.let { items = it }
        if (topic == NewsTopic.BREAKING || source != null) items = items.sortedByDescending { it.published }
        // Breaking: the newest stories first, from the last few hours when there are some.
        if (topic == NewsTopic.BREAKING) {
            items = items.sortedByDescending { it.published }
            val recent = items.filter { h -> h.published?.let { Duration.between(it, now).toHours() < 4 } == true }
            if (recent.size >= 3) items = recent
        }
        items
    }

    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private fun tag(item: String, name: String) =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let { unwrap(it) }

    private fun unwrap(s: String) = s.trim().removePrefix("<![CDATA[").removeSuffix("]]>").trim()

    fun parseRss(xml: String): List<Headline> = ITEM.findAll(xml).mapNotNull { m ->
        val item = m.groupValues[1]
        val source = tag(item, "source")?.let { decode(it) }?.ifBlank { null }
        var title = tag(item, "title")?.let { decode(it) }?.ifBlank { null } ?: return@mapNotNull null
        // Google News titles end with " - Source".
        if (source != null && title.endsWith(" - $source")) title = title.removeSuffix(" - $source").trim()
        val link = tag(item, "link")?.let { decode(it) } ?: return@mapNotNull null
        val date = tag(item, "pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull() }
        Headline(title, source, link, date)
    }.distinctBy { it.title.lowercase(Locale.US) }.toList()

    fun decode(s: String): String = s
        .replace(Patterns.get("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace(Patterns.get("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    // ------------------------------------------------------------------ answering

    fun ago(published: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val d = Duration.between(published ?: return null, now)
        return when {
            d.isNegative || d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }

    fun heading(topic: NewsTopic, query: String?, source: Publication? = null): String = when {
        source != null -> (query?.let { "news about $it" } ?: if (topic == NewsTopic.TOP || topic == NewsTopic.BREAKING) "the latest" else "the latest ${topic.label} stories") + " from ${source.name}"
        query != null -> "news about $query"
        topic == NewsTopic.TOP -> "the top stories"
        topic == NewsTopic.BREAKING -> "the latest breaking news"
        else -> "the top ${topic.label} stories"
    }

    /** The spoken answer: the first three headlines with their sources. */
    fun line(topic: NewsTopic, query: String?, items: List<Headline>, source: Publication? = null): String {
        if (items.isEmpty() && source != null) return "I couldn't find any recent stories from ${source.name}."
        if (items.isEmpty()) return if (query != null) "I couldn't find any recent news about $query." else "I couldn't get the news right now."
        // The headlines are on the card; reading them all out takes too long.
        return "Here's ${heading(topic, query, source)}. ${Ranked.CARD}"
    }
}

/** A news outlet asked for by name ("from the verge", "reuters headlines"): its site, when known, narrows the search. */
data class Publication(val name: String, val domain: String?)

object Publications {
    /** Spoken names (as a regex) -> display name and site. */
    private val KNOWN = listOf(
        "reuters" to ("Reuters" to "reuters.com"),
        "(?:the )?associated press|the ap|ap news|\\bap\\b" to ("AP" to "apnews.com"),
        "(?:the )?bbc(?: news)?" to ("BBC" to "bbc.com"),
        "cnn" to ("CNN" to "cnn.com"),
        "fox(?: news)?" to ("Fox News" to "foxnews.com"),
        "nbc(?: news)?" to ("NBC News" to "nbcnews.com"),
        "abc(?: news)?" to ("ABC News" to "abcnews.go.com"),
        "cbs(?: news)?" to ("CBS News" to "cbsnews.com"),
        "npr" to ("NPR" to "npr.org"),
        "(?:the )?new york times|(?:the )?ny times|(?:the )?nyt" to ("The New York Times" to "nytimes.com"),
        "(?:the )?washington post|wapo" to ("The Washington Post" to "washingtonpost.com"),
        "(?:the )?wall street journal|(?:the )?wsj" to ("The Wall Street Journal" to "wsj.com"),
        "(?:the )?guardian" to ("The Guardian" to "theguardian.com"),
        "bloomberg" to ("Bloomberg" to "bloomberg.com"),
        "cnbc" to ("CNBC" to "cnbc.com"),
        "politico" to ("Politico" to "politico.com"),
        "(?:the )?verge" to ("The Verge" to "theverge.com"),
        "techcrunch|tech crunch" to ("TechCrunch" to "techcrunch.com"),
        "ars technica|ars" to ("Ars Technica" to "arstechnica.com"),
        "wired" to ("Wired" to "wired.com"),
        "engadget" to ("Engadget" to "engadget.com"),
        "axios" to ("Axios" to "axios.com"),
        "usa today" to ("USA Today" to "usatoday.com"),
        "(?:the )?la times|(?:the )?los angeles times" to ("Los Angeles Times" to "latimes.com"),
        "(?:the )?atlantic" to ("The Atlantic" to "theatlantic.com"),
        "(?:the )?economist" to ("The Economist" to "economist.com"),
        "(?:the )?financial times|(?:the )?ft" to ("Financial Times" to "ft.com"),
        "al jazeera" to ("Al Jazeera" to "aljazeera.com"),
        "espn" to ("ESPN" to "espn.com"),
        "variety" to ("Variety" to "variety.com"),
        "(?:the )?hollywood reporter" to ("The Hollywood Reporter" to "hollywoodreporter.com"),
        "deadline" to ("Deadline" to "deadline.com"),
        "9 ?to ?5 ?mac|nine to five mac" to ("9to5Mac" to "9to5mac.com"),
        "9 ?to ?5 ?google|nine to five google" to ("9to5Google" to "9to5google.com"),
        "android authority" to ("Android Authority" to "androidauthority.com"),
        "android police" to ("Android Police" to "androidpolice.com"),
        "mashable" to ("Mashable" to "mashable.com"),
        "gizmodo" to ("Gizmodo" to "gizmodo.com"),
        "forbes" to ("Forbes" to "forbes.com"),
        "business insider|insider" to ("Business Insider" to "businessinsider.com"),
        "newsweek" to ("Newsweek" to "newsweek.com"),
        "time magazine|time\\.com" to ("TIME" to "time.com"),
        "(?:the )?hill" to ("The Hill" to "thehill.com"),
        "vox" to ("Vox" to "vox.com"),
        "polygon" to ("Polygon" to "polygon.com"),
        "ign" to ("IGN" to "ign.com"),
        "kotaku" to ("Kotaku" to "kotaku.com"),
        "(?:the )?independent" to ("The Independent" to "independent.co.uk"),
        "sky news" to ("Sky News" to "news.sky.com"),
        "space\\.com|space dot com" to ("Space.com" to "space.com"),
        "scientific american" to ("Scientific American" to "scientificamerican.com"),
        "(?:the )?daily mail" to ("Daily Mail" to "dailymail.co.uk"),
        "huffpost|huffington post|(?:the )?huff post" to ("HuffPost" to "huffpost.com"),
        "msnbc|ms now" to ("MSNBC" to "msnbc.com"),
        "pbs(?: news)?" to ("PBS News" to "pbs.org"),
    ).map { (rx, v) -> Regex("\\b(?:$rx)\\b") to Publication(v.first, v.second) }

    /** Words that introduce an outlet: "from", "by", "on", "in", "at", "according to". */
    private const val INTRO = "(?:from|by|on|in|at|according to|published by|written by|reported by|over at)"

    /** A known outlet anywhere in [t], or one named after [INTRO] near the end ("news from the daily planet"). */
    fun find(t: String): Pair<Publication, IntRange>? {
        for ((rx, p) in KNOWN) {
            val m = rx.find(t) ?: continue
            // Bare short names (ap, ft, ars, hill, insider, ign, vox) count only after an intro word or right before "news" or "articles".
            // Names that are also everyday words need an outlet-ish neighbor.
            val short = m.value in setOf("ap", "the ap", "ft", "the ft", "ars", "hill", "the hill", "insider", "ign", "vox", "fox", "abc", "nbc", "cbs", "atlantic",
                "independent", "the independent", "verge", "guardian", "deadline", "variety", "wired", "polygon", "economist", "axios", "politico")
            val before = t.substring(0, m.range.first).trimEnd()
            val after = t.substring(m.range.last + 1).trimStart()
            if (short && !Regex("$INTRO$").containsMatchIn(before) && !Patterns.get("^(?:news|articles?|stories|headlines|reports?|reporting|coverage)\\b").containsMatchIn(after) &&
                !before.endsWith("the") && !m.value.startsWith("the ")) continue
            val intro = Regex("\\s*$INTRO\\s*$").find(before)
            return p to ((intro?.range?.first ?: m.range.first)..m.range.last)
        }
        return null
    }

    /**
     * An outlet not in the list, named at the end: after "published by" / "according to", or after "from"/"by" when its
     * name sounds like one ("from the daily planet", "by the springfield herald"). "News from ukraine" stays a subject.
     */
    fun named(t: String): Pair<Publication, IntRange>? {
        val m = Patterns.get("\\b(from|by|published by|written by|reported by|according to)\\s+((?:the )?[a-z0-9.&' ]{2,30}?)\\s*$").find(t) ?: return null
        val name = m.groupValues[2].trim()
        val outletish = Patterns.get("\\b(?:times|post|news|journal|herald|tribune|gazette|daily|magazine|mag|report|reporter|wire|press|chronicle|observer|telegraph|review|insider|dispatch|bulletin|sun|star|globe|courier|examiner|register|standard|mirror|express|\\.com|dot com)$")
        if (m.groupValues[1] in setOf("from", "by") && !outletish.containsMatchIn(name)) return null
        if (name.isEmpty() || Patterns.get("^(?:today|yesterday|this|last|the last|now|recently|lately|me|you|them|him|her|it)\\b").containsMatchIn(name)) return null
        return Publication(name.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }, null) to m.range
    }

    /** True when [h] came from [p] (by its name in the feed). */
    fun matches(h: Headline, p: Publication): Boolean {
        val s = h.source?.lowercase(Locale.US) ?: return false
        val n = p.name.lowercase(Locale.US).removePrefix("the ")
        return s.contains(n) || p.domain != null && s.contains(p.domain.substringBefore('.'))
    }
}
