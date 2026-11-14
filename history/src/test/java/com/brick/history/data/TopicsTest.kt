package com.brick.history.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicsTest {
    private val now = 1_000_000_000L

    private fun chat(id: String, kind: String, subject: String?, ago: Long = 60_000L) =
        Topics.Candidate(id, setOf(kind), Topics.key(kind, Topics.words(subject)), now - ago)

    private fun pick(kind: String, subject: String?, vararg chats: Topics.Candidate, follow: Boolean = false) =
        Topics.pick(kind, Topics.key(kind, Topics.words(subject)), follow, chats.toList(), now)

    @Test fun wordsDropFillerAndPlurals() {
        assertEquals(setOf("pizza", "place"), Topics.words("Show me the best pizza places near me"))
        assertEquals(setOf("taylor", "swift"), Topics.words("Taylor Swift's"))
        assertEquals(emptySet<String>(), Topics.words("what's the"))
    }

    @Test fun sameKindAndSubjectJoin() {
        val tokyo = chat("tokyo", "Weather", "tokyo")
        val seattle = chat("seattle", "Weather", "seattle")
        assertEquals("tokyo", pick("Weather", "Tokyo", seattle, tokyo))
        assertEquals("seattle", pick("Weather", "seattle", seattle, tokyo))
    }

    @Test fun noSubjectGathersByKind() {
        val math = chat("math", "Math", null)
        val local = chat("local", "Weather", null)
        val tokyo = chat("tokyo", "Weather", "tokyo")
        assertEquals("math", pick("Math", null, math, local, tokyo))
        assertEquals("local", pick("Weather", null, math, local, tokyo))
        assertNull(pick("Timer", null, math, local, tokyo))
    }

    @Test fun differentSubjectsStartNewChats() {
        val swift = chat("swift", "Search", "who is taylor swift")
        assertNull(pick("Search", "who is the president", swift))
        assertEquals("swift", pick("Search", "taylor swift age", swift))
    }

    @Test fun sameSubjectAcrossKinds() {
        val swift = chat("swift", "Search", "taylor swift")
        assertEquals("swift", pick("Music", "taylor swift", swift))
        // One short shared word isn't enough across kinds.
        val car = chat("car", "Search", "car")
        assertNull(pick("Navigate", "car wash", car))
    }

    @Test fun repliesStayWithTheLastChat() {
        val old = chat("old", "Text", "mom", ago = 5 * 60_000L)
        val recent = chat("recent", "Reminder", "dentist", ago = 30_000L)
        assertEquals("recent", pick("Reply", "yes", old, recent, follow = true))
        assertNull(pick("Reply", "yes", chat("stale", "Text", "mom", ago = Topics.REPLY_MS + 1), follow = true))
    }

    @Test fun bestMatchWins() {
        val a = chat("a", "Search", "pizza dough recipe", ago = 10_000L)
        val b = chat("b", "FindPlace", "pizza place", ago = 20_000L)
        assertEquals("b", pick("FindPlace", "pizza places near me", a, b))
    }
}
