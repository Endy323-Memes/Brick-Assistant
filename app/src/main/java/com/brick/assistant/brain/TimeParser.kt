package com.brick.assistant.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class ParsedTime(
    val dateTime: LocalDateTime?,
    val hasDate: Boolean,
    val hasTime: Boolean,
    /** Set for "in 10 minutes" style phrases. */
    val relativeSeconds: Long?,
    /** The input with the time phrases removed. */
    val remainder: String,
)

object TimeParser {
    private val IC = setOf(RegexOption.IGNORE_CASE)
    private const val UNIT = "(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?)"
    private const val QTY = "(\\d+(?:\\.\\d+)?|an?|half an?)"
    private val durRegex = Regex("\\b$QTY\\s*$UNIT\\b(\\s+and\\s+a\\s+half)?", IC)
    private val andAHalfRegex = Regex("\\b(\\d+)\\s+and\\s+a\\s+half\\s+$UNIT\\b", IC)
    private val relativeRegex = Regex(
        "\\bin\\s+((?:(?:\\d+(?:\\.\\d+)?|an?|half an?)(?:\\s+and\\s+a\\s+half)?\\s*$UNIT(?:\\s+and\\s+a\\s+half)?\\b(?:\\s*(?:and|,)\\s*)?)+)",
        IC,
    )

    private fun unitSeconds(u: String): Long {
        val x = u.lowercase()
        return when {
            x.startsWith("s") -> 1
            x.startsWith("mi") -> 60
            x.startsWith("h") -> 3600
            x.startsWith("d") -> 86_400
            x.startsWith("w") -> 604_800
            else -> 60
        }
    }

    /** Total seconds in phrases like "1 hour and 30 minutes", "half an hour", "5 mins". */
    fun parseDuration(text: String): Long? {
        var total = 0.0
        var found = false
        var rest = text
        andAHalfRegex.findAll(rest).toList().forEach { m ->
            total += (m.groupValues[1].toDouble() + 0.5) * unitSeconds(m.groupValues[2])
            found = true
        }
        rest = andAHalfRegex.replace(rest, " ")
        for (m in durRegex.findAll(rest)) {
            val q = m.groupValues[1].lowercase()
            val n = when {
                q.startsWith("half") -> 0.5
                q == "a" || q == "an" -> 1.0
                else -> q.toDoubleOrNull() ?: continue
            }
            val extra = if (m.groupValues[3].isNotEmpty()) 0.5 else 0.0
            total += (n + extra) * unitSeconds(m.groupValues[2])
            found = true
        }
        return if (found) total.toLong() else null
    }

    fun formatDuration(totalSeconds: Long): String {
        val d = totalSeconds / 86_400
        val h = (totalSeconds % 86_400) / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        fun add(v: Long, unit: String) { if (v > 0) parts += "$v $unit" + if (v == 1L) "" else "s" }
        add(d, "day"); add(h, "hour"); add(m, "minute"); add(s, "second")
        return when (parts.size) {
            0 -> "0 seconds"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    private val months = listOf(
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )
    private const val MONTH_RX =
        "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"

    private fun monthIndex(name: String): Int {
        val n = name.lowercase()
        return months.indexOfFirst { it.startsWith(n.take(3)) } + 1
    }

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): ParsedTime {
        var rest = text
        fun cut(r: Regex): MatchResult? {
            val m = r.find(rest) ?: return null
            rest = rest.removeRange(m.range).replace(Patterns.get("\\s+"), " ").trim()
            return m
        }

        // Relative: "in 10 minutes"
        cut(relativeRegex)?.let { m ->
            val secs = parseDuration(m.groupValues[1])
            if (secs != null && secs > 0) {
                return ParsedTime(now.plusSeconds(secs), true, true, secs, rest)
            }
        }

        val today = now.toLocalDate()
        var date: LocalDate? = null
        var defaultTime: LocalTime? = null
        var pmHint = false
        var amHint = false

        when {
            cut(Patterns.get("\\b(?:the\\s+)?day after tomorrow\\b", true)) != null -> date = today.plusDays(2)
            cut(Patterns.get("\\btomorrow\\b", true)) != null -> date = today.plusDays(1)
            cut(Patterns.get("\\btonight\\b", true)) != null -> { date = today; defaultTime = LocalTime.of(20, 0); pmHint = true }
            cut(Patterns.get("\\btoday\\b", true)) != null -> date = today
        }

        if (date == null) {
            cut(Patterns.get("\\b(?:(?:on|for|this|next|by)\\s+)*(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b", true))?.let { m ->
                val target = DayOfWeek.valueOf(m.groupValues[1].uppercase())
                var diff = (target.value - today.dayOfWeek.value + 7) % 7
                if (diff == 0) diff = 7
                date = today.plusDays(diff.toLong())
            }
        }
        if (date == null) {
            cut(Patterns.get("\\b(?:(?:on|for|by)\\s+)?(?:the\\s+)?$MONTH_RX\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b", true))?.let { m ->
                date = safeDate(today, monthIndex(m.groupValues[1]), m.groupValues[2].toInt())
            }
        }
        if (date == null) {
            cut(Patterns.get("\\b(?:(?:on|for|by)\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+of\\s+$MONTH_RX\\b", true))?.let { m ->
                date = safeDate(today, monthIndex(m.groupValues[2]), m.groupValues[1].toInt())
            }
        }
        if (date == null) {
            cut(Patterns.get("\\b(?:(?:on|for|by)\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", true))?.let { m ->
                date = safeDate(today, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }
        if (date == null) {
            cut(Patterns.get("\\b(?:on|for)\\s+the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b", true))?.let { m ->
                val day = m.groupValues[1].toInt()
                var d = safeDate(today, today.monthValue, day, rollYear = false)
                if (d != null && d.isBefore(today)) d = safeDate(today.plusMonths(1), today.plusMonths(1).monthValue, day, rollYear = false)
                date = d
            }
        }

        // Part of day
        cut(Patterns.get("\\b(?:in\\s+the\\s+|this\\s+)?(morning|afternoon|evening)\\b", true))?.let { m ->
            when (m.groupValues[1].lowercase()) {
                "morning" -> { defaultTime = LocalTime.of(9, 0); amHint = true }
                "afternoon" -> { defaultTime = LocalTime.of(15, 0); pmHint = true }
                "evening" -> { defaultTime = LocalTime.of(19, 0); pmHint = true }
            }
        }

        // Clock time
        var time: LocalTime? = null
        var ambiguous = false
        val ampm = cut(Patterns.get("\\b(?:at\\s+|by\\s+|around\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", true))
        if (ampm != null) {
            var h = ampm.groupValues[1].toInt() % 12
            if (ampm.groupValues[3].equals("pm", true)) h += 12
            val min = ampm.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
        } else {
            val hm = cut(Patterns.get("\\b(?:at\\s+|by\\s+|around\\s+)?(\\d{1,2}):(\\d{2})\\b", true))
                ?: cut(Patterns.get("\\b(?:at|by|around)\\s+(\\d{1,2})\\b(?!\\s*(?:%|percent|$UNIT))", true))
            if (hm != null) {
                val h = hm.groupValues[1].toInt()
                val min = hm.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                if (h in 0..23 && min in 0..59) {
                    var hour = h
                    if (hour in 1..11 && !amHint) {
                        if (pmHint || hour <= 6) hour += 12
                        ambiguous = !pmHint
                    }
                    time = LocalTime.of(hour % 24, min)
                }
            } else if (cut(Patterns.get("\\b(?:at\\s+)?noon\\b", true)) != null) {
                time = LocalTime.NOON
            } else if (cut(Patterns.get("\\b(?:at\\s+)?midnight\\b", true)) != null) {
                time = LocalTime.MIDNIGHT
                if (date == null) date = today.plusDays(1)
            }
        }

        val hasDate = date != null
        val hasTime = time != null || defaultTime != null
        if (!hasDate && !hasTime) return ParsedTime(null, false, false, null, rest)

        val t = time ?: defaultTime ?: LocalTime.of(9, 0)
        var dt = LocalDateTime.of(date ?: today, t)
        if (!hasDate && !dt.isAfter(now)) {
            // "at 8" said at 9 AM -> 8 PM today if that's still ahead, otherwise tomorrow.
            dt = if (ambiguous && t.hour < 12 && dt.plusHours(12).isAfter(now)) dt.plusHours(12) else dt.plusDays(1)
        }
        return ParsedTime(dt, hasDate, hasTime, null, rest)
    }

    private fun safeDate(today: LocalDate, month: Int, day: Int, rollYear: Boolean = true): LocalDate? {
        if (month !in 1..12) return null
        return try {
            var d = LocalDate.of(today.year, month, day)
            if (rollYear && d.isBefore(today)) d = d.plusYears(1)
            d
        } catch (e: Exception) {
            null
        }
    }

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val longFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)

    fun speakTime(t: LocalTime): String = t.format(timeFmt).replace(":00", "")

    /** "today at 5 PM", "tomorrow at 9:30 AM", "on Friday at 3 PM", "on Friday, December 4". */
    fun describe(dt: LocalDateTime, includeTime: Boolean = true, now: LocalDateTime = LocalDateTime.now()): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), dt.toLocalDate())
        val day = when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days in 2..6 -> "on " + dt.format(dayFmt)
            else -> "on " + dt.format(longFmt)
        }
        return if (includeTime) "$day at ${speakTime(dt.toLocalTime())}" else day
    }
}
