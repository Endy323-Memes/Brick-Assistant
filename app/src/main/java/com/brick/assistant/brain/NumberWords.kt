package com.brick.assistant.brain

/** Turns spelled-out numbers ("twenty five") into digits ("25"). Keeps the rest of the text unchanged. */
object NumberWords {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private fun isNumberWord(w: String) = w.lowercase() in units || w.lowercase() in tens

    fun convert(text: String): String {
        val tokens = text.split(" ").filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val (value, consumed) = parseAt(tokens, i)
            if (consumed > 0) {
                out += value.toString()
                i += consumed
            } else {
                out += tokens[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    private fun parseAt(t: List<String>, start: Int): Pair<Long, Int> {
        var total = 0L
        var current = 0L
        var any = false
        var i = start
        var end = start
        while (i < t.size) {
            val w = t[i].lowercase()
            when {
                w in units -> { current += units.getValue(w); any = true; end = i + 1 }
                w in tens -> { current += tens.getValue(w); any = true; end = i + 1 }
                w == "hundred" && any -> { current = (if (current == 0L) 1 else current) * 100; end = i + 1 }
                w == "thousand" && any -> { total += (if (current == 0L) 1 else current) * 1000; current = 0; end = i + 1 }
                w == "and" && any && i + 1 < t.size && isNumberWord(t[i + 1]) -> {}
                else -> break
            }
            i++
        }
        return if (any) (total + current) to (end - start) else 0L to 0
    }
}
