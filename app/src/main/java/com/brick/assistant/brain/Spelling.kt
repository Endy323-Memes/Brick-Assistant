package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A word to spell: as heard, the spelling Brick settled on, and words that sound the same. */
data class SpellResult(val asked: String, val word: String, val corrected: Boolean, val soundsLike: List<String>) {
    val letters: List<Char> get() = word.filter { it.isLetterOrDigit() || it == '\'' || it == '-' || it == ' ' }.toList()

    /** "N, E, C, E, S, S, A, R, Y" (a space between words reads as "space"; a hyphen as "dash"). */
    val spokenLetters: String get() = letters.joinToString(", ") {
        when (it) {
            ' ' -> "space"
            '-' -> "dash"
            '\'' -> "apostrophe"
            else -> it.uppercaseChar().toString()
        }
    }

    val letterCount: Int get() = word.count { it.isLetter() }
}

/** "How do you spell necessary": the letters, plus a spelling check and sound-alikes from Datamuse when online. */
object Spelling {
    private val WORD = "([a-z][a-z' -]{0,40}?)"

    /**
     * The word asked about, or null: "how do you spell necessary", "how is rhythm spelled", "spell definitely for me",
     * "what's the spelling of colonel", "can you spell bureaucracy", "how to spell receive", "spell check recieve",
     * "is it spelled tomorrow", "spelling of their", "what are the letters in queue".
     */
    fun parse(raw: String): String? {
        val s = raw.lowercase(Locale.US).replace(Patterns.get("[?!.,\"]"), " ").replace(Patterns.get("\\s+"), " ").trim()
            .replace(Patterns.get("^(?:hey brick |brick |ok |okay |so |um |uh |quick question )+"), "")
            .replace(Patterns.get("\\s+(?:please|for me|real quick|again|letter by letter)+$"), "")
            .trim()
        val patterns = listOf(
            "^(?:how (?:do|would|should|can|did) (?:you|i|we|they|one) spell|how (?:to|you) spell) $WORD(?: spelled| spelt)?$",
            "^(?:how's|how is) $WORD (?:spelled|spelt)$",
            "^how (?:is|do you|do i|does one) spell $WORD$",
            "^how (?:is|are|was) $WORD (?:spelled|spelt)$",
            "^(?:can|could|would) you (?:please )?spell(?: out)?(?: the word)? $WORD$",
            "^(?:please )?spell(?: out)?(?: the word)? $WORD$",
            "^(?:what(?:'s| is) the |the )?(?:correct |right |proper )?spelling (?:of|for) (?:the word )?$WORD$",
            "^spell ?check(?: the word)? $WORD$",
            "^(?:is it|is this|is that) spelled $WORD$",
            "^(?:what are |what's |what is )?the letters (?:in|of) (?:the word )?$WORD$",
            "^(?:i (?:don't|do not|can't|cannot) (?:know how to|remember how to) spell|i forgot how to spell|help me spell) $WORD$",
        )
        for (p in patterns) {
            Patterns.get(p).find(s)?.let { m ->
                val w = m.groupValues[1].trim().removePrefix("the word ").trim()
                if (w.isNotEmpty() && w.split(' ').size <= 4) return w
            }
        }
        return null
    }

    private fun get(url: String): JSONArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 5000
        val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        c.disconnect()
        body?.let { JSONArray(it) }
    } catch (_: Exception) {
        null
    }

    /** The spelling (corrected when [asked] looks misspelled) and sound-alike words; offline, just the letters. */
    suspend fun lookup(asked: String): SpellResult = withContext(Dispatchers.IO) {
        val w = asked.trim()
        val enc = java.net.URLEncoder.encode(w, "UTF-8")
        var word = w
        var corrected = false
        if (!w.contains(' ')) {
            // Datamuse's spelling match: the word itself when it's a word, else the closest real one.
            get("https://api.datamuse.com/words?sp=$enc&max=1")?.optJSONObject(0)?.optString("word")?.let { exact ->
                if (exact.isNotBlank() && !exact.equals(w, true)) {
                    get("https://api.datamuse.com/sug?s=$enc&max=1")?.optJSONObject(0)?.optString("word")?.takeIf { it.isNotBlank() }?.let {
                        if (!it.equals(w, true)) { word = it; corrected = true }
                    }
                }
            } ?: get("https://api.datamuse.com/sug?s=$enc&max=1")?.optJSONObject(0)?.optString("word")?.takeIf { it.isNotBlank() && !it.contains(' ') }?.let {
                if (!it.equals(w, true)) { word = it; corrected = true }
            }
        }
        val homophones = get("https://api.datamuse.com/words?rel_hom=${java.net.URLEncoder.encode(word, "UTF-8")}&max=4")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("word") } }
            ?.filter { it.isNotBlank() && !it.equals(word, true) && !it.contains(' ') }
            .orEmpty()
        SpellResult(w, word, corrected, homophones)
    }

    /** What Brick says: "Necessary is spelled N, E, C, E, S, S, A, R, Y." */
    fun spoken(r: SpellResult): String {
        val name = r.word.replaceFirstChar { it.uppercase() }
        val lead = if (r.corrected) "I think you mean ${r.word}. " else ""
        val also = r.soundsLike.takeIf { it.isNotEmpty() }?.let { " Not to be confused with ${it.take(2).joinToString(" or ")}." } ?: ""
        return "$lead$name is spelled ${r.spokenLetters}.$also"
    }
}
