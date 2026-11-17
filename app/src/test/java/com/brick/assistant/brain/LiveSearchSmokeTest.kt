package com.brick.assistant.brain

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The live half of [SearchRoutingTest]: one question per kind of search, answered from the real sources (Wikipedia,
 * Wikidata, ESPN). Off by default, since it needs the internet; run it with BRICK_LIVE=1 set:
 *
 *     BRICK_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveSearchSmokeTest*'
 *
 * Wikimedia allows Brick about 10 requests a minute and a lookup takes 5 to 8, so the questions are spaced 40 seconds
 * apart (about 6 minutes in all). A failure names each question that came back empty, and says when Wikimedia was
 * throttling at the time (then it's the rate limit, not Brick).
 */
class LiveSearchSmokeTest {
    private val questions = listOf(
        "sports" to "who won the super bowl in 2020", "books" to "who wrote the great gatsby", "movies" to "who directed inception",
        "tv" to "who plays eleven in stranger things", "music" to "tell me about the album abbey road", "plants" to "what is a venus flytrap",
        "trees" to "tell me about the baobab tree", "bugs" to "how many legs does a centipede have", "insects" to "what do grasshoppers eat",
    )

    /** What Brick would show or say, or null when it found nothing. */
    private suspend fun answer(q: String): String? = when (val r = IntentParser.parse(q)) {
        is Request.Sports -> SportsDesk.answer(r.ask) {}?.line
        is Request.Animal -> Animals.find(r.ask)?.let { it.answer ?: it.summary }
        is Request.Search -> Lookup.understand(r.query).let { lq -> Lookup.find(lq, "")?.let { it.answer ?: it.summary } ?: SearchService.answer(r.query)?.text }
        else -> "routed to ${r::class.java.simpleName}"
    }?.takeIf { it.isNotBlank() }

    @Test fun everyKindAnswers() = runBlocking {
        assumeTrue("set BRICK_LIVE=1 to run the live search checks", System.getenv("BRICK_LIVE") == "1")
        val empty = ArrayList<String>()
        for ((i, pair) in questions.withIndex()) {
            val (kind, q) = pair
            if (i > 0) delay(40_000)
            val a = runCatching { answer(q) }.getOrNull()
            println("$kind | $q -> ${a?.take(120)}")
            if (a == null) empty += "$kind: \"$q\"" + if (Http.wikiBusy()) " (Wikimedia was throttling)" else ""
            // The grasshopper's diet once came back as what eats grasshoppers.
            if (q == "what do grasshoppers eat" && a != null && Regex("(?i)predat|parasit").containsMatchIn(a)) empty += "$kind: \"$q\" answered with its predators: $a"
        }
        assertTrue("No answer for:\n" + empty.joinToString("\n"), empty.isEmpty())
    }
}
