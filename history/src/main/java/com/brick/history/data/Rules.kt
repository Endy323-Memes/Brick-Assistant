package com.brick.history.data

/**
 * When chats move to the trash. Both rules work together: a chat goes to the trash when it's older than [AgeRule]
 * allows, or when there are more chats than [CountRule] allows (the least recently used go first). A chat's age
 * counts from when it was last used (or restored from the trash). The trash keeps chats [TRASH_DAYS] days.
 */
object Rules {
    const val DAY_MS = 24 * 3600_000L
    const val TRASH_DAYS = 30
    const val TRASH_MS = TRASH_DAYS * DAY_MS
    const val CUSTOM_MIN = 5
    const val CUSTOM_MAX = 1_000_000

    /** How long chats stay before moving to the trash ([days] 0 = keep them). */
    enum class AgeRule(val days: Int, val label: String) {
        NEVER(0, "Never"),
        D30(30, "30 days"),
        D45(45, "45 days"),
        D60(60, "60 days"),
        D90(90, "90 days"),
        M4(122, "4 months"),
        M6(183, "6 months"),
        M9(274, "9 months"),
        Y1(365, "1 year"),
        M15(456, "15 months"),
        M18(548, "18 months"),
        M21(639, "21 months"),
        Y2(730, "2 years"),
        ;

        val ms: Long? get() = if (days == 0) null else days * DAY_MS

        companion object {
            fun of(name: String?) = entries.firstOrNull { it.name == name } ?: NEVER
        }
    }

    /** The preset limits on how many chats stay (0 = unlimited). A custom number is also allowed. */
    val COUNT_PRESETS = listOf(5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 300, 500, 750, 1000, 2500, 5000, 0)

    /** Every count choice shown: the 20 presets and Custom. */
    const val COUNT_CHOICES = 21

    fun countLabel(n: Int) = if (n <= 0) "Unlimited" else "%,d queries".format(n)

    /** A custom limit typed in ("40", "1,200", "1.5k", "2 thousand"), or null when it isn't a number. */
    fun parseCount(text: String): Int? {
        val t = text.trim().lowercase().replace(",", "").replace("_", "")
        if (t.isEmpty()) return null
        if (t in setOf("unlimited", "no limit", "none", "infinite", "all", "any")) return 0
        val m = Regex("""^(\d+(?:\.\d+)?)\s*(k|thousand|m|million)?(?:\s*(?:quer(?:y|ies)|quarr(?:y|ies)|chats?|searche?s?))?$""").find(t) ?: return null
        val base = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2]) { "k", "thousand" -> 1_000; "m", "million" -> 1_000_000; else -> 1 }
        val n = base * mult
        if (n < CUSTOM_MIN || n > CUSTOM_MAX) return null
        return n.toInt()
    }

    /** A chat as the rules see it. [lastUsed] is the later of its last message and when it was restored. */
    data class Item(val id: String, val lastUsed: Long)

    /** The chats to move to the trash under [age] and [maxCount] (0 = unlimited), most recent kept first. */
    fun toTrash(items: List<Item>, now: Long, age: AgeRule, maxCount: Int): Set<String> {
        val out = LinkedHashSet<String>()
        val ageMs = age.ms
        if (ageMs != null) items.filter { now - it.lastUsed > ageMs }.forEach { out += it.id }
        if (maxCount > 0) {
            val left = items.filter { it.id !in out }.sortedByDescending { it.lastUsed }
            if (left.size > maxCount) left.drop(maxCount).forEach { out += it.id }
        }
        return out
    }

    /** Trashed chats past their [TRASH_DAYS] days. */
    fun toPurge(trashedAt: Map<String, Long>, now: Long): Set<String> =
        trashedAt.filterValues { now - it >= TRASH_MS }.keys

    /** Whole days a chat trashed at [trashedAt] has left in the trash (at least 0). */
    fun daysLeft(trashedAt: Long, now: Long): Int {
        val left = TRASH_MS - (now - trashedAt)
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }
}
