package com.brick.assistant.voice

import com.brick.assistant.brain.Patterns
import kotlin.math.abs
import kotlin.math.sqrt

/** Source of real David word recordings (the Opus bank on the phone, a stand-in in tests). */
interface WordSource {
    val sampleRate: Int
    fun contains(word: String): Boolean
    fun get(word: String): FloatArray?
}

/**
 * Builds new David sentences from real David word recordings: natural word gaps, pauses at
 * punctuation, matched loudness, one smooth pitch contour per sentence (falling for statements, rising for\n * questions), light quick function words, and crossfades between words.
 */
object DavidStitcher {
    class Result(val audio: PcmAudio, val lastWordStart: Int, val lastWordEnd: Int)

    private val numberish = Regex("[0-9]")

    /** Lower-case words with a pause marker (0 none, 1 comma, 2 sentence end, 3 question end) after each. */
    fun tokenize(text: String): List<Pair<String, Int>> {
        val spoken = NumberSpeller.expand(text)
        val out = ArrayList<Pair<String, Int>>()
        for (raw in spoken.split(Patterns.get("\\s+"))) {
            if (raw.isBlank()) continue
            val pause = when {
                raw.endsWith(",") || raw.endsWith(";") || raw.endsWith(":") -> 1
                raw.endsWith("?") -> 3
                raw.endsWith(".") || raw.endsWith("!") -> 2
                else -> 0
            }
            val w = raw.lowercase().replace('’', '\'').replace(Patterns.get("[^a-z']"), "").trim('\'')
            if (w.isEmpty()) {
                if (pause > 0 && out.isNotEmpty()) out[out.size - 1] = out.last().first to maxOf(out.last().second, pause)
                continue
            }
            out += w to pause
        }
        return out
    }

    /** Share of words available as real David recordings. */
    fun coverage(text: String, src: WordSource): Float {
        val t = tokenize(text)
        if (t.isEmpty()) return 0f
        return t.count { src.contains(it.first) }.toFloat() / t.size
    }

    /**
     * [fallback] renders a single missing word (e.g. with the David match voice) at [WordSource.sampleRate].
     * Returns null when nothing could be built.
     */
    /** Short words people say quickly and lightly ("the", "to", "a" ...). */
    private val functionWords = setOf(
        "a", "an", "the", "to", "of", "and", "or", "in", "on", "at", "for", "is", "it", "its", "it's", "i", "i'm", "i'll",
        "you", "your", "my", "me", "we", "be", "are", "was", "that", "this", "with", "as", "but", "so", "do", "just",
        "from", "by", "if", "can", "will", "have", "has", "he", "she", "they", "them", "his", "her", "our", "up", "am",
    )

    fun stitch(text: String, src: WordSource, fallback: (String) -> FloatArray?): Result? {
        val tokens = tokenize(text)
        if (tokens.isEmpty() || numberish.containsMatchIn(tokens.joinToString(" ") { it.first })) return null
        val sr = src.sampleRate
        val raw = tokens.map { (w, _) -> src.get(w) ?: fallback(w) }
        if (raw.all { it == null }) return null

        // Loudness: bring every word to the median word RMS (limited); function words a bit softer.
        val rmsList = raw.mapNotNull { it?.let(::rms) }.filter { it > 1e-4f }.sorted()
        val target = if (rmsList.isEmpty()) 0.1f else rmsList[rmsList.size / 2]

        // One sentence melody: measure each word's pitch, then tune it onto a smooth contour.
        val tracks = raw.map { it?.let { c -> PitchTracker.track(c, sr) } }
        val medians = tracks.map { it?.median() }
        val base = medians.filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        // Sentence boundaries for the contour (pause 2 = statement end, 3 = question end).
        val sentenceOf = IntArray(tokens.size)
        var sIdx = 0
        for (i in tokens.indices) { sentenceOf[i] = sIdx; if (tokens[i].second >= 2) sIdx++ }

        val gapComma = sr * 150 / 1000
        val gapSentence = sr * 280 / 1000
        val xfade = sr * 22 / 1000
        val out = ArrayList<Float>(sr * 4)
        var lastStart = 0
        var lastEnd = 0
        var joinNext = false // true when the previous word flows straight into this one
        for (i in tokens.indices) {
            var clip = raw[i] ?: continue
            val (word, pause) = tokens[i]
            val isLast = i == tokens.lastIndex
            val endsSentence = pause >= 2 || isLast
            val question = pause == 3 || (isLast && text.trimEnd().endsWith("?"))
            val inSentence = tokens.indices.filter { sentenceOf[it] == sentenceOf[i] }
            val pos = if (inSentence.size <= 1) 0f else (inSentence.indexOf(i).toFloat() / (inSentence.size - 1))
            val light = word in functionWords && !endsSentence

            // Loudness
            val r = rms(clip)
            if (r > 1e-4f) {
                val g = (target / r).coerceIn(0.5f, 2f) * if (light) 0.82f else 1f
                clip = FloatArray(clip.size) { clip[it] * g }
            }
            // Pitch onto the contour: gentle declination; statements fall at the end, questions rise.
            val m = medians[i]
            val trk = tracks[i]
            if (base != null && m != null && trk != null) {
                val goal = when {
                    endsSentence && question -> base // level here; it glides up just below
                    endsSentence -> base * 0.9f
                    else -> base * (1.05f - 0.1f * pos)
                }
                val factor = (goal / m).coerceIn(0.8f, 1.25f)
                if (abs(factor - 1f) > 0.03f) clip = Psola.shift(clip, sr, trk) { factor }
            }
            // A question's last word climbs through the word, the way people ask ("...the details?").
            if (endsSentence && question) clip = RobotFx.questionRise(PcmAudio(clip, sr), from = 0).samples
            // Function words are said quickly.
            if (light && clip.size > sr / 8) clip = Wsola.speed(clip, sr, 1.3f)

            // Join: crossfade into the previous word inside a phrase, otherwise just append.
            val overlap = if (joinNext) minOf(xfade, clip.size / 4, out.size) else 0
            val start = out.size - overlap
            for (k in 0 until overlap) {
                val t = (k + 1f) / (overlap + 1f)
                val a = kotlin.math.cos(t * Math.PI / 2).toFloat()
                val b = kotlin.math.sin(t * Math.PI / 2).toFloat()
                out[start + k] = out[start + k] * a + clip[k] * b
            }
            for (k in overlap until clip.size) out += clip[k]
            lastStart = start
            lastEnd = out.size
            joinNext = false
            when {
                isLast -> {}
                pause >= 2 -> repeat(gapSentence) { out += 0f }
                pause == 1 -> repeat(gapComma) { out += 0f }
                else -> joinNext = true
            }
        }
        if (out.isEmpty()) return null
        return Result(PcmAudio(out.toFloatArray(), sr), lastStart, lastEnd)
    }
    private fun rms(x: FloatArray): Float {
        var e = 0.0
        var n = 0
        for (v in x) if (abs(v) > 0.003f) { e += v * v; n++ }
        return if (n == 0) 0f else sqrt(e / n).toFloat()
    }

    /**
     * Interrupted ending for responses that end with "-": stops abruptly partway through the last
     * word ([lastStart]..[lastEnd] when known, otherwise the last ~0.22 s of speech).
     */
    fun cutOff(audio: PcmAudio, lastStart: Int = -1, lastEnd: Int = -1): PcmAudio {
        val x = audio.samples
        val sr = audio.sampleRate
        val cut = if (lastStart in 0 until lastEnd && lastEnd <= x.size) {
            lastStart + ((lastEnd - lastStart) * 0.5f).toInt()
        } else {
            var peak = 0f
            for (v in x) if (abs(v) > peak) peak = abs(v)
            var end = x.size - 1
            while (end > 0 && abs(x[end]) < peak * 0.03f) end--
            val speechStart = x.indexOfFirst { abs(it) >= peak * 0.03f }.coerceAtLeast(0)
            val back = minOf(sr * 220 / 1000, ((end - speechStart) * 0.35f).toInt())
            (end - back).coerceAtLeast(speechStart + 1)
        }.coerceIn(1, x.size)
        val fade = minOf(sr * 4 / 1000, cut)
        val y = x.copyOf(cut)
        for (i in 0 until fade) y[cut - 1 - i] *= i / fade.toFloat()
        return PcmAudio(y, sr)
    }

    fun endsInterrupted(text: String): Boolean {
        val t = text.trimEnd()
        return t.endsWith("-") || t.endsWith("—") || t.endsWith("–")
    }

    fun withoutDash(text: String): String = text.trimEnd().trimEnd('-', '—', '–').trimEnd()
}

/** Spells out digits so they can be stitched from recorded words ("7:30" -> "seven thirty"). */
object NumberSpeller {
    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ordinals = mapOf(1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth", 6 to "sixth",
        7 to "seventh", 8 to "eighth", 9 to "ninth", 10 to "tenth", 11 to "eleventh", 12 to "twelfth", 20 to "twentieth", 30 to "thirtieth")

    fun words(n: Long): String {
        if (n < 0) return "minus " + words(-n)
        if (n < 20) return ones[n.toInt()]
        if (n < 100) return tens[(n / 10).toInt()] + if (n % 10 != 0L) " " + ones[(n % 10).toInt()] else ""
        if (n < 1000) return ones[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + words(n % 100) else ""
        for ((size, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")) {
            if (n >= size) return words(n / size) + " " + name + if (n % size != 0L) " " + words(n % size) else ""
        }
        return n.toString()
    }

    fun expand(text: String): String {
        var t = text
        t = t.replace(Patterns.get("\\b(\\d{1,2}):(\\d{2})\\b")) { m ->
            val h = m.groupValues[1].toLong(); val mm = m.groupValues[2].toInt()
            words(h) + when { mm == 0 -> ""; mm < 10 -> " oh " + ones[mm]; else -> " " + words(mm.toLong()) }
        }
        t = t.replace(Patterns.get("\\b(\\d+)(st|nd|rd|th)\\b")) { m ->
            val n = m.groupValues[1].toInt()
            ordinals[n] ?: (words(n.toLong()) + "th")
        }
        t = t.replace(Patterns.get("(\\d+)%")) { words(it.groupValues[1].toLong()) + " percent" }
        t = t.replace(Patterns.get("\\b(\\d+)\\.(\\d+)\\b")) { m ->
            words(m.groupValues[1].toLong()) + " point " + m.groupValues[2].map { ones[it - '0'] }.joinToString(" ")
        }
        t = t.replace(Patterns.get("\\b\\d{1,12}\\b")) { words(it.value.toLong()) }
        t = t.replace("&", " and ")
        return t
    }
}
