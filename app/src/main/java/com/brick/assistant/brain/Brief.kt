package com.brick.assistant.brain

/** Keeps what UNIT says short: the card under it carries the full answer. */
object Brief {
    private const val MAX = 140
    // A sentence ends at . ! or ? before a capital or a quote; "3.1" and "Sep 2025)." stay whole.
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[A-Z\"'(])")
    // Abbreviations that end in a period but don't end a sentence.
    private val ABBREV = Regex("(?:\\b(?:Bros|Mr|Mrs|Ms|Dr|St|Jr|Sr|vs|No|Inc|Ltd|Co|U\\.S|e\\.g|i\\.e)|\\b[A-Z])\\.$")

    /** The first sentence, plus the second when both fit; a long first sentence is cut at a comma or semicolon. */
    fun spoken(text: String): String {
        val t = text.trim()
        if (t.length <= MAX) return t
        val parts = ArrayList<String>()
        var cur = StringBuilder()
        for (piece in t.split(SENTENCE_END)) {
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(piece)
            if (!ABBREV.containsMatchIn(cur)) { parts += cur.toString(); cur = StringBuilder() }
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        val first = parts.first()
        if (first.length <= MAX) {
            val two = parts.getOrNull(1)?.let { "$first $it" }
            return if (two != null && two.length <= MAX) two else first
        }
        // One long sentence: end it at the last clause break that fits.
        val cut = Regex("[,;:] ").findAll(first).map { it.range.first }.filter { it in 40..MAX }.lastOrNull()
            ?: return first
        return first.substring(0, cut).trimEnd() + "."
    }
}
