package com.brick.assistant.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class CountUnit { DAYS, WEEKS, MONTHS, HOURS, MINUTES, SLEEPS }

/**
 * "How many days until Christmas", "how long until Friday", "countdown to Halloween", "days since New Year's":
 * a countdown (or count-up) to a date, told apart from an ordinary search ("the twelve days of christmas")
 * by its phrasing *and* by the target being something with a date: a holiday, a date, a weekday, a time,
 * or, if nothing else fits, an event on the user's calendar (looked up when the answer is made).
 */
object Countdown {
    private val us = Locale.US

    /** A target resolved to a date: [name] as spoken ("Christmas", "Friday, March 3"), [time] when it's a clock time. */
    data class Target(val name: String, val date: LocalDate, val time: LocalTime? = null, val holiday: Boolean = false, val event: Boolean = false)

    private const val UNITS = "(days?|weeks?|months?|hours?|minutes?|mins?|sleeps?|nights?)"
    private const val UNTIL = "(?:until|till|til|'til|to|before|for)"
    private const val TAIL = "(?:\\s+(?:from now|from today|again|this year|is it|are there|are left|left|away))*"

    private val FORMS: List<Pair<Regex, Boolean>> = listOf(
        // since / ago
        "^how (?:many $UNITS|long) (?:has it been |is it |have we been |ago )?(?:since|from) (.+?)$TAIL$" to true,
        "^how long ago (?:was|is) (.+?)$TAIL$" to true,
        "^$UNITS since (.+?)$TAIL$" to true,
        // until
        "^how (?:many|much) $UNITS (?:are |is )?(?:there |it |left |remaining |do (?:i|we) have |till |until |to |before )*(?:left )?$UNTIL (.+?)$TAIL$" to false,
        "^how (?:long|much longer|much time|soon)(?: is it| is there| is left| do (?:i|we) have| left)*(?: left)? (?:until|till|til|'til|before|to) (.+?)$TAIL$" to false,
        "^how (?:long|soon) (?:is it )?(?:until|till|til|before) (.+?)$TAIL$" to false,
        "^(?:count ?down|days|time|weeks|hours|sleeps)(?: left| remaining)? (?:to|until|till|til|before) (.+?)$TAIL$" to false,
        "^(?:start |show |give me |set )?(?:a |the )?count ?down (?:for|to|until|till) (.+?)$" to false,
        "^(.+?) count ?down$" to false,
        "^is (?:it|today) (.+?) (?:yet|today)$" to false,
    ).map { Regex(it.first) to it.second }

    private val STRONG = Regex("\\b(?:until|till|til|before|since|ago|count ?down)\\b")

    /** "when is thanksgiving", "when's easter this year": only for holidays, so "when is the game" stays a search. */
    private val WHEN = Regex("^when(?:'s| is| does| do)? (.+?)(?: this year| next year| start| begin| fall| land)*$")

    private fun unitOf(u: String?) = when {
        u == null -> CountUnit.DAYS
        u.startsWith("week") -> CountUnit.WEEKS
        u.startsWith("month") -> CountUnit.MONTHS
        u.startsWith("hour") -> CountUnit.HOURS
        u.startsWith("min") -> CountUnit.MINUTES
        u.startsWith("sleep") || u.startsWith("night") -> CountUnit.SLEEPS
        else -> CountUnit.DAYS
    }

    /** Cleans the target: "the next christmas?" -> "christmas". */
    fun cleanTarget(t: String) = t.trim().trimEnd('?', '!', '.').trim()
        .replace(Regex("^(?:the |next |this |upcoming |coming )+"), "")
        .replace(Regex("\\s+(?:comes?|gets? here|arrives?|starts?|begins?|is here|happens?)$"), "")
        .trim()

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): Request.Countdown? {
        val s = text.lowercase(us).trim().trimEnd('?', '!', '.').replace('’', '\'').replace(Regex("\\s+"), " ")
        if (Regex("\\b(?:timer|alarm|stopwatch)s?\\b").containsMatchIn(s)) return null
        for ((rx, since) in FORMS) {
            val m = rx.find(s) ?: continue
            val g = m.groupValues.drop(1).filter { it.isNotEmpty() }
            val unitWord = g.firstOrNull { Regex("^$UNITS$").matches(it) }
            val target = cleanTarget(g.last())
            if (target.isBlank() || Regex("^$UNITS$").matches(target)) continue
            val t = resolve(target, now, since)
            // Something with a date: a holiday, a date, a weekday, a time. Otherwise it may be a calendar event
            // ("my vacation", "the dentist"), which only the answer can check, but only for clear countdown
            // wording: "how long to cook a turkey" is a search.
            if (t == null && (!STRONG.containsMatchIn(s) || rx.pattern.startsWith("^(.+?)"))) continue
            return Request.Countdown(target, t, unitOf(unitWord), since, text.trim())
        }
        WHEN.find(s)?.let { m ->
            val target = cleanTarget(m.groupValues[1])
            val t = resolve(target, now, false)
            if (t != null && t.holiday) return Request.Countdown(target, t, CountUnit.DAYS, false, text.trim(), whenAsk = true)
        }
        return null
    }

    // ---- What has a date

    private class Holiday(val name: String, val rx: Regex, val on: (Int) -> LocalDate?)

    private fun nth(year: Int, month: Month, day: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    private fun last(year: Int, month: Month, day: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(day))

    /** Western Easter Sunday (anonymous Gregorian computus). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }

    private fun thanksgiving(y: Int) = nth(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)

    private val HOLIDAYS: List<Holiday> = listOf(
        Holiday("Christmas Eve", Regex("^(?:christmas|xmas|x-mas) eve$")) { LocalDate.of(it, 12, 24) },
        Holiday("Christmas", Regex("^(?:christmas|xmas|x-mas)(?: day)?$")) { LocalDate.of(it, 12, 25) },
        Holiday("Boxing Day", Regex("^boxing day$")) { LocalDate.of(it, 12, 26) },
        Holiday("New Year's Eve", Regex("^new ?year'?s eve$")) { LocalDate.of(it, 12, 31) },
        Holiday("New Year's Day", Regex("^(?:new ?year'?s?(?: day)?|the new year|next year)$")) { LocalDate.of(it, 1, 1) },
        Holiday("Martin Luther King Jr. Day", Regex("^(?:mlk|martin luther king(?: jr\\.?| junior)?)(?:'s)? day$")) { nth(it, Month.JANUARY, DayOfWeek.MONDAY, 3) },
        Holiday("Groundhog Day", Regex("^groundhog'?s? day$")) { LocalDate.of(it, 2, 2) },
        Holiday("Valentine's Day", Regex("^(?:saint |st\\.? )?valentine'?s?(?: day)?$")) { LocalDate.of(it, 2, 14) },
        Holiday("Presidents' Day", Regex("^(?:presidents'?|president'?s|washington'?s birthday)(?: day)?$")) { nth(it, Month.FEBRUARY, DayOfWeek.MONDAY, 3) },
        Holiday("Leap Day", Regex("^leap day$")) { y -> (y until y + 8).firstOrNull { java.time.Year.isLeap(it.toLong()) }?.let { LocalDate.of(it, 2, 29) } },
        Holiday("Pi Day", Regex("^pi day$")) { LocalDate.of(it, 3, 14) },
        Holiday("St. Patrick's Day", Regex("^(?:saint |st\\.? )?(?:patrick|paddy|patty)'?s?(?: day)?$")) { LocalDate.of(it, 3, 17) },
        Holiday("Mardi Gras", Regex("^(?:mardi gras|fat tuesday|shrove tuesday|pancake day)$")) { easter(it).minusDays(47) },
        Holiday("Ash Wednesday", Regex("^ash wednesday$")) { easter(it).minusDays(46) },
        Holiday("Good Friday", Regex("^good friday$")) { easter(it).minusDays(2) },
        Holiday("Easter", Regex("^easter(?: sunday| day)?$")) { easter(it) },
        Holiday("April Fools' Day", Regex("^april fool'?s'?(?: day)?$")) { LocalDate.of(it, 4, 1) },
        Holiday("Tax Day", Regex("^tax day$")) { LocalDate.of(it, 4, 15) },
        Holiday("Earth Day", Regex("^earth day$")) { LocalDate.of(it, 4, 22) },
        Holiday("Star Wars Day", Regex("^(?:star wars day|may the fourth|may the 4th)$")) { LocalDate.of(it, 5, 4) },
        Holiday("Cinco de Mayo", Regex("^cinco de mayo$")) { LocalDate.of(it, 5, 5) },
        Holiday("Mother's Day", Regex("^mother'?s'? day$")) { nth(it, Month.MAY, DayOfWeek.SUNDAY, 2) },
        Holiday("Memorial Day", Regex("^memorial day$")) { last(it, Month.MAY, DayOfWeek.MONDAY) },
        Holiday("Father's Day", Regex("^father'?s'? day$")) { nth(it, Month.JUNE, DayOfWeek.SUNDAY, 3) },
        Holiday("Juneteenth", Regex("^juneteenth$")) { LocalDate.of(it, 6, 19) },
        Holiday("the Fourth of July", Regex("^(?:independence day|(?:the )?(?:fourth|4th) of july|july (?:fourth|4th)|july 4)$")) { LocalDate.of(it, 7, 4) },
        Holiday("Labor Day", Regex("^labou?r day$")) { nth(it, Month.SEPTEMBER, DayOfWeek.MONDAY, 1) },
        Holiday("Columbus Day", Regex("^(?:columbus|indigenous peoples'?) day$")) { nth(it, Month.OCTOBER, DayOfWeek.MONDAY, 2) },
        Holiday("Halloween", Regex("^(?:halloween|hallowe'en|all hallows'? eve)$")) { LocalDate.of(it, 10, 31) },
        Holiday("Election Day", Regex("^election day$")) { nth(it, Month.NOVEMBER, DayOfWeek.MONDAY, 1).plusDays(1) },
        Holiday("Veterans Day", Regex("^veterans'? day$")) { LocalDate.of(it, 11, 11) },
        Holiday("Thanksgiving", Regex("^thanksgiving(?: day)?$")) { thanksgiving(it) },
        Holiday("Black Friday", Regex("^black friday$")) { thanksgiving(it).plusDays(1) },
        Holiday("Cyber Monday", Regex("^cyber monday$")) { thanksgiving(it).plusDays(4) },
        Holiday("the first day of spring", Regex("^(?:the )?(?:first day of )?spring$")) { LocalDate.of(it, 3, 20) },
        Holiday("the first day of summer", Regex("^(?:the )?(?:first day of )?summer$")) { LocalDate.of(it, 6, 21) },
        Holiday("the first day of fall", Regex("^(?:the )?(?:first day of )?(?:fall|autumn)$")) { LocalDate.of(it, 9, 22) },
        Holiday("the first day of winter", Regex("^(?:the )?(?:first day of )?winter$")) { LocalDate.of(it, 12, 21) },
    )

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d", us)

    /** The next (or, for [since], the latest) date of [target], or null when it isn't something with a date. */
    fun resolve(target: String, now: LocalDateTime = LocalDateTime.now(), since: Boolean = false): Target? {
        val today = now.toLocalDate()
        val t = target.removeSuffix(" day off").trim()
        for (h in HOLIDAYS) {
            if (!h.rx.matches(t)) continue
            if (h.name == "New Year's Day" && !since) return Target(h.name, LocalDate.of(today.year + 1, 1, 1), holiday = true)
            val y = today.year
            val d = if (since) {
                listOf(y, y - 1, y - 4, y - 8).firstNotNullOfOrNull { yy -> h.on(yy)?.takeIf { !it.isAfter(today) } }
            } else {
                listOf(y, y + 1).firstNotNullOfOrNull { yy -> h.on(yy)?.takeIf { !it.isBefore(today) } }
            } ?: continue
            return Target(h.name, d, holiday = true)
        }
        when {
            Regex("^(?:the )?weekend$").matches(t) -> {
                val d = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today
                else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                return Target("the weekend", d)
            }
            Regex("^(?:the )?end of (?:the )?year$").matches(t) -> return Target("the end of the year", LocalDate.of(today.year, 12, 31))
            Regex("^(?:the )?end of (?:the )?month$").matches(t) -> return Target("the end of the month", today.with(TemporalAdjusters.lastDayOfMonth()))
            Regex("^(?:the )?end of (?:the )?week$").matches(t) -> return Target("the end of the week", today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
            Regex("^(?:the year )?(20\\d\\d)$").matches(t) -> {
                val y = Regex("20\\d\\d").find(t)!!.value.toInt()
                return Target(y.toString(), LocalDate.of(y, 1, 1))
            }
        }
        // A plain date, weekday or time: "march 3rd", "friday", "12/25", "tomorrow", "5 pm".
        val p = TimeParser.parse(t, now)
        val rest = p.remainder.lowercase(us).replace(Regex("\\b(?:on|at|the|of|this|next|o'clock|oclock)\\b"), " ").trim()
        if (p.dateTime != null && rest.isEmpty()) {
            var d = p.dateTime.toLocalDate()
            if (since && d.isAfter(today)) d = d.minusYears(1)
            return when {
                p.hasTime && !p.hasDate -> Target(TimeParser.speakTime(p.dateTime.toLocalTime()), d, p.dateTime.toLocalTime())
                p.hasTime -> Target(d.format(dayFmt) + " at " + TimeParser.speakTime(p.dateTime.toLocalTime()), d, p.dateTime.toLocalTime())
                else -> Target(d.format(dayFmt), d)
            }
        }
        return null
    }

    // ---- Answers

    private fun plural(n: Long, word: String) = "%,d".format(us, n) + " " + if (n == 1L) word else word + "s"

    /** "6 weeks and 1 day", "1 month and 13 days", "1,030 hours", "43 days". */
    fun amount(from: LocalDateTime, to: LocalDateTime, unit: CountUnit): String {
        val a = if (to.isBefore(from)) to else from
        val b = if (to.isBefore(from)) from else to
        val days = ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate())
        return when (unit) {
            CountUnit.DAYS -> plural(days, "day")
            CountUnit.SLEEPS -> plural(days, "sleep")
            CountUnit.WEEKS -> {
                val w = days / 7; val r = days % 7
                if (w == 0L) plural(r, "day") else plural(w, "week") + if (r > 0) " and " + plural(r, "day") else ""
            }
            CountUnit.MONTHS -> {
                val p = Period.between(a.toLocalDate(), b.toLocalDate())
                val m = p.toTotalMonths()
                if (m == 0L) plural(p.days.toLong(), "day") else plural(m, "month") + if (p.days > 0) " and " + plural(p.days.toLong(), "day") else ""
            }
            CountUnit.HOURS -> {
                val mins = ChronoUnit.MINUTES.between(a, b)
                if (mins < 120) plural(mins, "minute") else "about " + plural((mins + 30) / 60, "hour")
            }
            CountUnit.MINUTES -> plural(ChronoUnit.MINUTES.between(a, b), "minute")
        }
    }

    /** When the target starts: its clock time, or the start of its day. */
    fun moment(t: Target) = LocalDateTime.of(t.date, t.time ?: LocalTime.MIDNIGHT)

    fun speak(t: Target, unit: CountUnit, since: Boolean, whenAsk: Boolean = false, now: LocalDateTime = LocalDateTime.now()): String {
        val today = now.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, t.date)
        val name = t.name.replaceFirstChar { it.uppercase() }
        val on = t.date.format(dayFmt) + if (t.date.year != today.year) ", ${t.date.year}" else ""
        // A clock time today: "It's 2 hours and 10 minutes until 5 PM."
        if (t.time != null && days == 0L) {
            val left = java.time.Duration.between(now, moment(t))
            if (left.isNegative) return "$name has already passed today."
            val h = left.toHours(); val m = left.toMinutesPart().toLong()
            val parts = listOfNotNull(h.takeIf { it > 0 }?.let { plural(it, "hour") }, m.takeIf { it > 0 || h == 0L }?.let { plural(it, "minute") })
            return "${parts.joinToString(" and ")} until ${t.name}."
        }
        if (since) {
            return when (days) {
                0L -> "$name is today."
                else -> "It's been ${amount(moment(t), now.toLocalDate().atStartOfDay(), unit)} since ${t.name}, on $on."
            }
        }
        val hours = unit == CountUnit.HOURS || unit == CountUnit.MINUTES
        return when {
            days == 0L && t.name == "the weekend" -> "It's the weekend!"
            days == 0L -> "$name is today!"
            days == 1L && !hours -> "$name is tomorrow" + if (t.holiday) ", $on." else if (t.time != null && t.event) " at ${TimeParser.speakTime(t.time)}." else "."
            whenAsk -> "$name is on $on, " + amount(now.toLocalDate().atStartOfDay(), moment(t), unit) + " from now."
            t.holiday || t.event || t.name == "the weekend" || t.name.startsWith("the end") -> "There " + (if (days == 1L) "is " else "are ") +
                amount(if (hours) now else now.toLocalDate().atStartOfDay(), moment(t), unit) + " until ${t.name}, on $on."
            else -> "There are " + amount(if (hours) now else now.toLocalDate().atStartOfDay(), moment(t), unit) + " until $on."
        }
    }
}
