package com.brick.history.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val day = Rules.DAY_MS
    private val now = 1_000 * day

    @Test fun choices() {
        // At least 12 ways to move chats to the trash (plus "Never"), from 30 days to 2 years.
        val deleting = Rules.AgeRule.entries.filter { it.ms != null }
        assertTrue(deleting.size >= 12)
        assertEquals(30, deleting.first().days)
        assertEquals(730, deleting.last().days)
        // 20 presets from 5 to unlimited, plus Custom: 21 choices.
        assertEquals(20, Rules.COUNT_PRESETS.size)
        assertEquals(5, Rules.COUNT_PRESETS.first())
        assertEquals(0, Rules.COUNT_PRESETS.last())
        assertEquals(Rules.COUNT_CHOICES, Rules.COUNT_PRESETS.size + 1)
        assertEquals("Unlimited", Rules.countLabel(0))
    }

    @Test fun ageRule() {
        val items = listOf(Rules.Item("old", now - 31 * day), Rules.Item("new", now - 29 * day))
        assertEquals(setOf("old"), Rules.toTrash(items, now, Rules.AgeRule.D30, 0))
        assertEquals(emptySet<String>(), Rules.toTrash(items, now, Rules.AgeRule.NEVER, 0))
    }

    @Test fun countRuleKeepsTheMostRecent() {
        val items = (1..8).map { Rules.Item("c$it", now - it * 3600_000L) }
        assertEquals(setOf("c6", "c7", "c8"), Rules.toTrash(items, now, Rules.AgeRule.NEVER, 5))
    }

    @Test fun bothRulesAtOnce() {
        val items = listOf(
            Rules.Item("a", now - 1 * day),
            Rules.Item("b", now - 2 * day),
            Rules.Item("c", now - 3 * day),
            Rules.Item("ancient", now - 400 * day),
        )
        // The year rule takes "ancient"; the count rule then keeps 2 of the 3 left.
        assertEquals(setOf("ancient", "c"), Rules.toTrash(items, now, Rules.AgeRule.Y1, 2))
    }

    @Test fun trashLasts30Days() {
        val purge = Rules.toPurge(mapOf("gone" to now - 30 * day, "kept" to now - 29 * day), now)
        assertEquals(setOf("gone"), purge)
        assertEquals(30, Rules.daysLeft(now, now))
        assertEquals(1, Rules.daysLeft(now - 29 * day - 1, now))
        assertEquals(0, Rules.daysLeft(now - 31 * day, now))
    }

    @Test fun customCountUnderstandsLooseInput() {
        assertEquals(60, Rules.parseCount("60"))
        assertEquals(1200, Rules.parseCount("1,200"))
        assertEquals(1500, Rules.parseCount("1.5k"))
        assertEquals(2000, Rules.parseCount("2 thousand"))
        assertEquals(80, Rules.parseCount("80 chats"))
        assertEquals(40, Rules.parseCount("40 queries"))
        assertEquals(40, Rules.parseCount("40 quarries"))
        assertEquals(0, Rules.parseCount("unlimited"))
        assertNull(Rules.parseCount("4"))
        assertNull(Rules.parseCount("lots"))
    }
}
