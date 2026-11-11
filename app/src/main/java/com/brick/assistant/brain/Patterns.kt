package com.brick.assistant.brain

import java.util.concurrent.ConcurrentHashMap

/**
 * Compiled regular expressions, shared and reused. Patterns written inline in functions that run for every
 * request (or every spoken line) are compiled once here instead of on every call.
 */
internal object Patterns {
    private val cache = ConcurrentHashMap<String, Regex>()

    fun get(pattern: String, ignoreCase: Boolean = false): Regex =
        cache.getOrPut(if (ignoreCase) "i:$pattern" else "c:$pattern") {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        }
}
