package com.brick.assistant.brain

import org.json.JSONObject
import java.util.Locale

/**
 * YouTubers, streamers and other online creators: what they're known for, their channel and subscriber count
 * (Wikidata), and the controversies they've been in (their Wikipedia article's Controversy / Criticism / Legal
 * sections). Controversies work for anyone with an article, not only creators.
 */
object Creators {
    private val CREATOR = Regex("\\b(?:youtuber|you tuber|youtube (?:personality|star|creator|channel|vlogger)|streamer|twitch|internet (?:personality|celebrity|star)|" +
        "online (?:personality|streamer|creator)|content creator|vlogger|influencer|tiktoker|tiktok (?:personality|star)|social media (?:personality|influencer|star)|let's player|podcaster|gamer)\\b",
        RegexOption.IGNORE_CASE)

    /** Whether a page's description (or opening) says they're an online creator. */
    fun isCreator(description: String?, opening: String? = null): Boolean =
        listOfNotNull(description, opening?.take(300)).any { CREATOR.containsMatchIn(it) }

    /** A YouTube channel from Wikidata: its id, and the latest subscriber count with when it was counted. */
    data class Channel(val id: String, val subscribers: Long?, val asOf: String?) {
        val url get() = "https://www.youtube.com/channel/$id"
    }

    /** The YouTube channel (P2397) and its subscribers (P8687 "social media followers" counted for that channel). */
    fun channel(claims: JSONObject?): Channel? {
        val c = claims ?: return null
        val ids = c.optJSONArray("P2397")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { s -> s.optString("rank") != "deprecated" }?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifBlank { null } }
        }.orEmpty()
        val id = ids.firstOrNull() ?: return null
        var best: Pair<Long, String?>? = null
        c.optJSONArray("P8687")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val q = s.optJSONObject("qualifiers") ?: continue
                val ch = q.optJSONArray("P2397")?.optJSONObject(0)?.optJSONObject("datavalue")?.optString("value") ?: continue
                if (ch !in ids) continue
                val n = s.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("amount")?.removePrefix("+")?.substringBefore('.')?.toLongOrNull() ?: continue
                val at = q.optJSONArray("P585")?.optJSONObject(0)?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")?.removePrefix("+")?.take(10)
                if (best == null || (at ?: "") > (best!!.second ?: "")) best = n to at
            }
        }
        return Channel(id, best?.first, best?.second)
    }

    /** "286 million", "4.2 million", "850 thousand". */
    fun bigCount(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1f billion".format(Locale.US, n / 1e9).replace(".0 ", " ")
        n >= 10_000_000 -> "${n / 1_000_000} million"
        n >= 1_000_000 -> "%.1f million".format(Locale.US, n / 1e6).replace(".0 ", " ")
        n >= 1_000 -> "${n / 1_000} thousand"
        else -> n.toString()
    }

    // ------------------------------------------------------------------ controversies

    private val HEADINGS = Regex("controvers|criticism|scandal|allegation|accusation|lawsuit|litigation|legal (?:issue|trouble|dispute|proceeding|action|case|matter)|" +
        "\\blegal\\b|incident|feud|backlash|misconduct|arrest|conviction|investigation|racism|antisemit|plagiarism|disput|cancel", RegexOption.IGNORE_CASE)
    private val SAID = Regex("\\b(?:controvers\\w*|critici[sz]\\w*|backlash|scandal\\w*|accus\\w+|alleg\\w+|lawsuits?|sued|suing|apologi[sz]\\w*|condemn\\w*|outrage|" +
        "boycott\\w*|banned|suspended|demoneti[sz]\\w*|arrested|charged with|convicted|feud\\w*|slur|racist|antisemit\\w*|misconduct|harass\\w*|scam\\w*|fraud\\w*|plagiari[sz]\\w*)\\b",
        RegexOption.IGNORE_CASE)

    /** One controversy: its [topic] (a section heading, when the article has one) and what was said about it. */
    data class Controversy(val topic: String?, val said: String)

    /**
     * The controversies in [text] (an article's plain text): each subsection of a Controversy / Criticism / Legal
     * section, or each such section itself, with its first sentence; with no such section, the sentences elsewhere
     * that tell of one ("was criticized for", "faced backlash", "was sued").
     */
    fun controversies(text: String, names: List<String>): List<Controversy> {
        val hits = Regex("^(=+)\\s*(.+?)\\s*\\1\\s*$", RegexOption.MULTILINE).findAll(text).toList()
        fun body(i: Int): String = text.substring(hits[i].range.last + 1, hits.getOrNull(i + 1)?.range?.first ?: text.length).trim()
        fun first(s: String): String? = s.split('\n').flatMap { it.split(Regex("(?<=[.!?])\\s+(?=[A-Z\"“])")) }
            .map { it.replace(Regex("\\s*\\([^)]*\\)"), "").trim() }.firstOrNull { it.length in 30..500 && it.last() in ".!?\"”" }
        val out = ArrayList<Controversy>()
        var i = 0
        while (i < hits.size) {
            val level = hits[i].groupValues[1].length
            val head = hits[i].groupValues[2]
            if (HEADINGS.containsMatchIn(head) && !Regex("^(?:references|see also|notes)$", RegexOption.IGNORE_CASE).matches(head)) {
                // Its subsections are the controversies ("2017 anti-Semitism controversy", "Forest video").
                var j = i + 1
                val subs = ArrayList<Controversy>()
                while (j < hits.size && hits[j].groupValues[1].length > level) {
                    // Only the parts that tell of one ("2019–2020: Minecraft series, milestones" is career news).
                    val h = hits[j].groupValues[2]
                    val b = body(j)
                    val s = first(b)
                    if (s != null && (HEADINGS.containsMatchIn(h) || SAID.containsMatchIn(b.take(600)))) subs += Controversy(h, if (SAID.containsMatchIn(s)) s else
                        b.split('\n').flatMap { it.split(Regex("(?<=[.!?])\\s+(?=[A-Z\"“])")) }.map { it.replace(Regex("\\s*\\([^)]*\\)"), "").trim() }
                            .firstOrNull { it.length in 30..500 && SAID.containsMatchIn(it) && it.last() in ".!?\"”" } ?: s)
                    j++
                }
                if (subs.isNotEmpty()) {
                    out += subs
                    // Its own opening (before the subsections) can tell of more.
                    first(body(i))?.let { s -> if (out.none { it.said == s }) out.add(out.size - subs.size, Controversy(null, s)) }
                } else {
                    // One section: each paragraph is usually one controversy.
                    body(i).split('\n').mapNotNull { p -> first(p) }.filter { SAID.containsMatchIn(it) || out.isEmpty() }.take(5)
                        .forEach { out += Controversy(focus(head), it) }
                }
                i = j
                continue
            }
            i++
        }
        if (out.isNotEmpty()) return out.distinctBy { it.said }.take(8)
        // No such section: the sentences anywhere that tell of one, said of them.
        // (Said of them: their name, or "He/She/They" opening the sentence; "faced backlash" is theirs.)
        val them = Regex("^(?:he|she|they|his|her|their)\\b", RegexOption.IGNORE_CASE)
        return Characters.sections(text).flatMap { it.sentences }
            .filter { s -> SAID.containsMatchIn(s) && s.length <= 400 && (them.containsMatchIn(s) || names.any { n -> Regex("\\b${Regex.escape(n)}", RegexOption.IGNORE_CASE).containsMatchIn(s) }) }
            .distinct().take(5).map { Controversy(null, it) }
    }

    /**
     * The controversy part of a section heading: "2017–2018: Media controversies and formatted shows" → "Media controversies";
     * null when nothing specific is left ("Controversies", "Minecraft series, milestones, and other controversies").
     */
    fun focus(head: String): String? {
        val parts = topic(head).split(Regex(",\\s*(?:and\\s+)?|\\s+and\\s+")).map { it.trim() }.filter { HEADINGS.containsMatchIn(it) }
        return parts.filterNot { Regex("^(?:other |more |further |various |some )?(?:controvers\\w*|criticisms?|incidents?|legal issues?)$", RegexOption.IGNORE_CASE).matches(it) }
            .joinToString(" and ").ifBlank { null }?.replaceFirstChar { it.uppercase(Locale.US) }
    }

    /** A heading said as a topic ("Forest video", "2017 anti-Semitism controversy"). */
    fun topic(h: String): String = h.trim().trimEnd('.').replace(Regex("^(?:\\d{4}(?:s)?(?:\\s*[–-]\\s*(?:\\d{2,4}|present))?)\\s*:\\s*"), "").replaceFirstChar { it.uppercase(Locale.US) }

    /** What Brick says about [name]'s controversies. */
    fun spoken(name: String, list: List<Controversy>): String {
        if (list.isEmpty()) return "I couldn't find any major controversies about $name."
        val topics = list.mapNotNull { it.topic }.distinct()
        val lead = list.first().said
        // Said short; the card lists each one.
        return if (topics.size >= 2) "$name's controversies include ${Lookup.listSaid(topics.take(3).map { topic(it) }, 3)}."
        else said(lead, 150)
    }
}
