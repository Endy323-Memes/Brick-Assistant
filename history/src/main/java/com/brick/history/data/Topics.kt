package com.brick.history.data

/**
 * Files each request in a chat about the same thing. Brick tells History what kind of request it was ("Weather",
 * "Search", "Music"...) and what it named ("tokyo", "taylor swift"). A request joins the chat that best matches:
 *  - the same kind and a similar subject ("weather in tokyo" and "tokyo weather tomorrow");
 *  - the same kind with no subject at all ("what's the weather", "set a timer": one chat each);
 *  - another kind with the same subject words ("who is taylor swift" and "play taylor swift").
 * Anything else starts a new chat. A reply to Brick's question stays with the chat just used.
 */
object Topics {
    /** Chats keep at most this many subject words (the newest). */
    const val MAX_WORDS = 80
    /** A reply ("yes", a note's text) joins the last chat only when it was used this recently. */
    const val REPLY_MS = 10 * 60_000L

    private val STOP = (
        "a an the and or but of to in on at for from by with about into over near nearby around me my mine i im i'm " +
            "you your yours we us our it its it's is are was were be been am do does did doing can could would should will " +
            "what whats what's which who whos who's whom where wheres where's when whens how hows how's why " +
            "this that these those there here some any all much many more most less please pls hey brick ok okay " +
            "show tell find search look looking lookup up get give let lets let's know see check open pull bring " +
            "best top good great new latest today tonight now right just like also really very " +
            "s t ll re ve d m"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    /** A subject's words: lower case, no filler words, simple plurals folded ("places" -> "place"). */
    fun words(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return text.lowercase()
            .replace('’', '\'')
            .split(Regex("[^a-z0-9']+"))
            .map { it.removeSuffix("'s").trim('\'') }
            .filter { it.isNotEmpty() && it !in STOP }
            .map { stem(it) }
            .toSet()
    }

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
        else -> w
    }

    /** The stand-in word for a request with no subject ("what's the weather"), so they gather by kind. */
    fun marker(kind: String) = "~" + kind.lowercase()

    /** The words a request adds to its chat: its subject's words, or its kind's marker when it named nothing. */
    fun key(kind: String, subjectWords: Set<String>): Set<String> = subjectWords.ifEmpty { setOf(marker(kind)) }

    /** A chat as the matcher sees it. */
    data class Candidate(val id: String, val kinds: Set<String>, val words: Set<String>, val lastUsed: Long)

    /** How well a request of [kind] with [key] words fits chat [c] (0 = not at all). */
    fun score(kind: String, key: Set<String>, c: Candidate): Double {
        if (key.isEmpty()) return 0.0
        val overlap = key.count { it in c.words }
        if (overlap == 0) return 0.0
        val share = overlap.toDouble() / key.size
        val sameKind = kind in c.kinds
        return when {
            sameKind && share >= 0.5 -> 0.6 + 0.4 * share
            // Another kind of request about the same thing: needs a strong, specific match.
            !sameKind && key.none { it.startsWith("~") } &&
                (overlap >= 2 || (key.size == 1 && key.first().length >= 5)) && share >= 0.5 -> 0.3 + 0.3 * share
            else -> 0.0
        }
    }

    /**
     * The chat for a new request, or null for a new chat. [chats] are the chats in the list; [follow]: the request
     * answers Brick's question.
     */
    fun pick(kind: String, key: Set<String>, follow: Boolean, chats: List<Candidate>, now: Long): String? {
        if (follow) return chats.maxByOrNull { it.lastUsed }?.takeIf { now - it.lastUsed < REPLY_MS }?.id
        var best: Candidate? = null
        var bestScore = 0.0
        for (c in chats) {
            val s = score(kind, key, c)
            if (s > bestScore || (s == bestScore && s > 0 && c.lastUsed > (best?.lastUsed ?: 0))) { best = c; bestScore = s }
        }
        return best?.id
    }
}
