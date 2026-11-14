package com.brick.assistant.brain

import com.brick.assistant.data.ResponseRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsePoolsTest {
    private fun has(key: String) = assertTrue("no pool $key", ResponseRules.get(key)?.defaults?.isNotEmpty() == true)

    @Test fun everyKindHasItsOwnPool() {
        NewsTopic.entries.forEach { has("search.news.${it.name.lowercase()}") }
        RankKind.entries.forEach { has("search.ranked.${it.name.lowercase()}") }
        WorkKind.entries.forEach {
            has("search.latest.${it.name.lowercase()}")
            has("media.buy.${it.name.lowercase()}")
            has("media.rating.${it.name.lowercase()}")
        }
        listOf("movie", "tv_show").forEach { has("search.latest.$it") }
        listOf("games", "movies", "tv", "console").forEach { has("search.deals.$it") }
        has("media.sale")
        listOf("switch", "status", "page", "timeout", "app", "where").forEach { has("device.settings.$it") }
    }

    @Test fun poolsFallBackToTheirParent() {
        assertEquals(listOf("search.news.sports", "search.news"), ResponseRules.chain("search.news.sports").map { it.key })
        assertEquals(listOf("device.settings.timeout", "device.settings"), ResponseRules.chain("device.settings.timeout").map { it.key })
    }
}
