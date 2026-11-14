package com.brick.assistant.brain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The terminal infographics for each kind of answer (weather, music, clock, reminders, calendar, texts, calls,
 * search, math, device and Brick settings, notifications). Settings readouts carry live controls and a button to
 * the full setting.
 */
class Infographics(private val context: Context) {
    private val us = Locale.US
    private val clock = DateTimeFormatter.ofPattern("h:mm a", us)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d", us)

    private fun open(i: Intent): () -> Unit = {
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    /** Brick's own Settings tab. */
    fun brickSettings(): Control.Button = Control.Button("Brick settings", open(
        Intent(context, com.brick.assistant.HomeActivity::class.java)
            .putExtra(com.brick.assistant.HomeActivity.EXTRA_TAB, 2)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    ))

    private fun settingsButton(label: String, action: String) = Control.Button(label, open(Intent(action)))

    // ---------------------------------------------------------------- weather

    fun weather(w: WeatherData, where: String): Readout {
        val deg = if (w.imperial) "F" else "C"
        val rows = ArrayList<String>()
        rows += where
        rows += (if (w.tomorrow) "tomorrow" else "today") + ", " + LocalDate.now().plusDays(if (w.tomorrow) 1 else 0).format(dayFmt).lowercase(us)
        rows += ""
        if (!w.tomorrow && w.temp != null) rows += Box.kv("NOW", "${w.temp}$deg  ${w.conditions.removeSuffix(" skies")}")
        else rows += Box.kv("SKY", w.conditions.removeSuffix(" skies"))
        if (w.high != null || w.low != null) rows += Box.kv("HI/LO", "${w.high ?: "-"}$deg / ${w.low ?: "-"}$deg")
        w.rainChance?.let { rows += Box.kv("RAIN", Box.percent(it)) }
        w.rainAmount?.takeIf { it >= 0.01 }?.let { rows += Box.kv("", "about %.2f %s".format(us, it, if (w.imperial) "in" else "mm")) }
        w.snowChance?.takeIf { it > 0 }?.let { rows += Box.kv("SNOW", Box.percent(it)) }
        w.snowAmount?.takeIf { it >= 0.05 }?.let { rows += Box.kv("", "about %.1f %s".format(us, it, if (w.imperial) "in" else "cm")) }
        w.windSpeed?.let { sp ->
            val gust = w.windGust?.takeIf { it > sp + 3 }?.let { " g$it" } ?: ""
            rows += Box.kv("WIND", "$sp ${if (w.imperial) "mph" else "km/h"}$gust" + (w.windFrom?.let { " " + compass(it) } ?: ""))
        }
        w.humidity?.let { rows += Box.kv("HUMID", Box.percent(it)) }
        w.uvIndex?.let { rows += Box.kv("UV", Box.bar(it.coerceAtMost(11), 11) + " $it") }
        rows += Box.kv("SRC", w.source)
        return Readout(Box.build("WEATHER", rows))
    }

    /** The week (or weekend) as a table: day, high/low, rain or snow chance, sky. */
    fun weatherWeek(w: WeekForecast, span: WeatherSpan, where: String): Readout {
        val deg = if (w.imperial) "F" else "C"
        val days = WeatherSource.daysFor(w, span)
        val rows = ArrayList<String>()
        rows += where
        rows += (if (span == WeatherSpan.WEEKEND) "this weekend" else "${days.size}-day forecast") + ", hi/lo $deg"
        rows += ""
        val today = LocalDate.now()
        for (d in days) {
            val name = if (d.date == today) "TOD" else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, us).uppercase(us)
            val hl = "${d.high ?: "-"}/${d.low ?: "-"}".padEnd(7)
            val snow = (d.snowChance ?: 0) >= 30
            val pct = (if (snow) d.snowChance else d.rainChance)?.let { "${it}%".padStart(4) + if (snow) "*" else " " } ?: "     "
            rows += "$name $hl$pct ${d.conditions.removeSuffix(" skies")}"
        }
        rows += ""
        rows += Box.kv("KEY", "% rain, *snow")
        rows += Box.kv("SRC", w.source)
        return Readout(Box.build(if (span == WeatherSpan.WEEKEND) "WEEKEND" else "WEEK", rows))
    }

    /** "the northwest" -> "NW". */
    private fun compass(from: String): String {
        val f = from.removePrefix("the ").lowercase(us)
        return listOf("north" to "N", "south" to "S", "east" to "E", "west" to "W")
            .fold(f) { acc, (w, c) -> acc.replace(w, c) }.replace("-", "").replace(" ", "").uppercase(us)
    }

    // ---------------------------------------------------------------- music

    private fun session(): MediaController? = try {
        context.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(context, com.brick.assistant.media.BrickNotificationListener::class.java))
            ?.firstOrNull()
    } catch (_: Exception) {
        null
    }

    private fun appName(pkg: String?): String? = pkg?.let {
        when (it) {
            DriverBridge.PACKAGE -> "Driver"
            "com.spotify.music" -> "Spotify"
            "com.apple.android.music" -> "Apple Music"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "com.google.android.youtube" -> "YouTube"
            else -> try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
            } catch (_: Exception) {
                it
            }
        }
    }

    private fun mediaVolume(): Control.Slider {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
        return Control.Slider(
            "Media volume",
            get = { (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) * 100f / max },
            range = 0f..100f,
            format = { "${it.roundToInt()}%" },
            set = { v -> am?.setStreamVolume(AudioManager.STREAM_MUSIC, (v / 100f * max).roundToInt(), 0) },
        )
    }

    /**
     * What's playing (from the active media session, when Brick has notification access) and what you asked for,
     * with transport buttons and the media volume. [action] is the request ("PLAY  lofi beats"), [app] the app
     * it went to.
     */
    fun music(action: String?, app: String?): Readout {
        val s = session()
        val meta = s?.metadata
        val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
        val playing = s?.playbackState?.state == PlaybackState.STATE_PLAYING
        val rows = ArrayList<String>()
        action?.let { rows += Box.wrap(it, max = 2) }
        (app ?: appName(s?.packageName))?.let { rows += Box.kv("APP", it) }
        if (title != null) {
            rows += ""
            rows += Box.wrap(title, Box.kv("TRACK", ""), max = 2)
            artist?.let { rows += Box.wrap(it, Box.kv("ARTIST", ""), max = 1) }
            album?.takeIf { it != title }?.let { rows += Box.wrap(it, Box.kv("ALBUM", ""), max = 1) }
            val dur = meta.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
            val pos = s.playbackState?.position ?: -1
            if (dur > 0 && pos >= 0) rows += Box.kv(if (playing) "PLAY" else "PAUSE", Box.bar((pos * 10 / dur).toInt(), 10) + " " + mmss(pos) + "/" + mmss(dur))
            else rows += Box.kv("STATE", if (playing) "playing" else "paused")
        } else if (rows.isEmpty()) {
            rows += "nothing playing"
        }
        val controls = ArrayList<Control>()
        s?.transportControls?.let { t ->
            controls += Control.Button("|<< prev") { t.skipToPrevious() }
            controls += Control.Button(if (playing) "|| pause" else "> play") { if (s.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play() }
            controls += Control.Button("next >>|") { t.skipToNext() }
        }
        controls += mediaVolume()
        return Readout(Box.build("MUSIC", rows), controls = controls, refresh = { music(action, app) }, live = playing)
    }

    private fun mmss(ms: Long): String = "%d:%02d".format(us, ms / 60000, ms / 1000 % 60)

    // ---------------------------------------------------------------- clock

    fun time(zone: ZoneId, place: String?): Readout {
        val there = ZonedDateTime.now(zone)
        val here = ZonedDateTime.now()
        val rows = ArrayList<String>()
        rows += place?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } ?: "your location"
        rows += ""
        rows += Box.kv("TIME", there.format(clock))
        rows += Box.kv("DATE", there.format(dayFmt))
        rows += Box.kv("ZONE", zone.id.substringAfterLast('/').replace('_', ' ') + " " + there.zone.rules.getOffset(there.toInstant()).id.replace("Z", "+00:00"))
        if (place != null) {
            val diff = (there.offset.totalSeconds - here.offset.totalSeconds) / 60
            val h = diff / 60
            val m = kotlin.math.abs(diff % 60)
            rows += Box.kv("VS YOU", when {
                diff == 0 -> "same time"
                else -> (if (diff > 0) "+" else "-") + kotlin.math.abs(h) + (if (m > 0) ":%02d".format(us, m) else "") + "h (" + here.format(clock) + " here)"
            })
        }
        rows += Box.kv("DAY", dayBar(there))
        return Readout(Box.build("CLOCK", rows), controls = listOf(Control.Button("Clock app", open(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)))))
    }

    /** How far through the day: "[#####.....] 52%". */
    private fun dayBar(t: ZonedDateTime) = Box.percent((t.toLocalTime().toSecondOfDay() * 100L / 86_400).toInt())

    fun date(): Readout {
        val d = LocalDate.now()
        val rows = listOf(
            d.dayOfWeek.getDisplayName(TextStyle.FULL, us),
            "",
            Box.kv("DATE", d.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", us))),
            Box.kv("WEEK", "${d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)} of the year"),
            Box.kv("YEAR", Box.percent(((d.dayOfYear - 1) * 100) / d.lengthOfYear())),
        )
        return Readout(Box.build("CALENDAR", rows))
    }

    fun countdown(t: Countdown.Target, since: Boolean): Readout {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, t.date)
        val start = if (t.time != null) now else today.atStartOfDay()
        val at = Countdown.moment(t)
        val rows = ArrayList<String>()
        rows += t.name.replaceFirstChar { it.uppercase() }
        rows += ""
        rows += Box.kv("DATE", t.date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", us)) + (t.time?.let { " " + it.format(clock) } ?: ""))
        rows += Box.kv(if (since) "SINCE" else "LEFT", when {
            days == 0L && t.time == null -> "today"
            t.time != null && kotlin.math.abs(days) < 2 -> Countdown.amount(start, at, CountUnit.HOURS)
            else -> Countdown.amount(start, at, CountUnit.DAYS)
        })
        if (kotlin.math.abs(days) >= 14) rows += Box.kv("WEEKS", Countdown.amount(start, at, CountUnit.WEEKS))
        if (kotlin.math.abs(days) >= 60) rows += Box.kv("MONTHS", Countdown.amount(start, at, CountUnit.MONTHS))
        if (!since && t.holiday && days in 1..365) {
            // How far along the wait is since this holiday last year.
            val span = java.time.temporal.ChronoUnit.DAYS.between(t.date.minusYears(1), t.date).coerceAtLeast(1)
            rows += Box.kv("WAIT", Box.percent((100 - days * 100 / span).toInt()))
        }
        val cal = Intent(Intent.ACTION_VIEW).setData(
            android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                .appendPath(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()).build(),
        )
        return Readout(Box.build("COUNTDOWN", rows), controls = listOf(Control.Button("Calendar", open(cal))))
    }

    fun timer(seconds: Long, label: String = "timer"): Readout {
        val ends = LocalDateTime.now().plusSeconds(seconds)
        val rows = listOf(
            label,
            "",
            Box.kv("LENGTH", hms(seconds)),
            Box.kv("ENDS", ends.format(clock)),
            Box.kv("LEFT", Box.bar(10, 10) + " " + hms(seconds)),
        )
        return Readout(Box.build("TIMER", rows), controls = listOf(Control.Button("Timers", open(Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)))))
    }

    /** Running timers Brick knows about: how much is left of each. */
    fun timers(list: List<Pair<Long, Long>>, paused: List<Boolean>): Readout {
        val rows = ArrayList<String>()
        if (list.isEmpty()) rows += "no timers running"
        list.forEachIndexed { i, (total, left) ->
            if (i > 0) rows += ""
            rows += Box.kv("TIMER", hms(total / 1000) + if (paused.getOrElse(i) { false }) "  paused" else "")
            val done = if (total > 0) ((total - left) * 10 / total).toInt() else 10
            rows += Box.kv("LEFT", Box.bar(10 - done, 10) + " " + hms((left + 999) / 1000))
        }
        return Readout(Box.build("TIMERS", rows), controls = listOf(Control.Button("Timers", open(Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)))))
    }

    fun alarm(at: LocalDateTime, days: String?, label: String?): Readout {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(at.toLocalTime())
        if (!next.isAfter(now)) next = next.plusDays(1)
        val left = Duration.between(now, next).seconds
        val rows = ArrayList<String>()
        rows += label ?: "Brick alarm"
        rows += ""
        rows += Box.kv("TIME", at.format(clock))
        rows += Box.kv("REPEAT", days ?: "once")
        rows += Box.kv("RINGS", if (days == null) (if (next.toLocalDate() == now.toLocalDate()) "today" else "tomorrow") + ", in " + hm(left) else "next in " + hm(left))
        return Readout(Box.build("ALARM", rows), controls = listOf(Control.Button("Alarms", open(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)))))
    }

    fun stopwatch(running: Boolean, elapsedMs: Long, startedAt: Long?, laps: List<Long>, controls: List<Control>): Readout {
        val rows = ArrayList<String>()
        rows += if (running) "running" else if (elapsedMs > 0) "stopped" else "ready"
        rows += ""
        rows += Box.kv("TIME", stopwatchTime(elapsedMs))
        startedAt?.let { rows += Box.kv("SINCE", Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clock)) }
        laps.takeLast(4).forEachIndexed { i, l -> rows += Box.kv("LAP ${laps.size - minOf(4, laps.size) + i + 1}", stopwatchTime(l)) }
        return Readout(Box.build("STOPWATCH", rows), controls = controls)
    }

    fun stopwatchTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val c = ms / 10 % 100
        return if (h > 0) "%d:%02d:%02d.%02d".format(us, h, m, s, c) else "%02d:%02d.%02d".format(us, m, s, c)
    }

    private fun hms(sec: Long): String {
        val h = sec / 3600
        val m = sec / 60 % 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(us, h, m, s) else "%d:%02d".format(us, m, s)
    }

    private fun hm(sec: Long): String {
        val h = sec / 3600
        val m = (sec + 59) / 60 % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // ---------------------------------------------------------------- reminders, calendar

    fun reminder(task: String, at: LocalDateTime): Readout {
        val left = Duration.between(LocalDateTime.now(), at).seconds.coerceAtLeast(0)
        val rows = ArrayList<String>()
        rows += Box.wrap(task, max = 3)
        rows += ""
        rows += Box.kv("WHEN", at.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", us)))
        rows += Box.kv("IN", if (left >= 86_400) "${left / 86_400}d " + hm(left % 86_400) else hm(left))
        return Readout(Box.build("REMINDER", rows))
    }

    fun newEvent(title: String, start: LocalDateTime, allDay: Boolean, saved: Boolean): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(title, max = 3)
        rows += ""
        rows += Box.kv("DATE", start.format(DateTimeFormatter.ofPattern("EEE MMM d", us)))
        rows += Box.kv("TIME", if (allDay) "all day" else start.format(clock) + " - " + start.plusHours(1).format(clock))
        rows += Box.kv("STATUS", if (saved) "added" else "finish in Calendar")
        return Readout(Box.build("NEW EVENT", rows), controls = listOf(calendarButton()))
    }

    private fun calendarButton() = Control.Button("Calendar", open(
        Intent(Intent.ACTION_VIEW).setData(android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()),
    ))

    fun agenda(events: List<CalEvent>, range: CalendarRange): Readout {
        val rows = ArrayList<String>()
        rows += when (range) {
            CalendarRange.TODAY -> "today"
            CalendarRange.TOMORROW -> "tomorrow"
            CalendarRange.WEEK -> "this week"
            CalendarRange.NEXT -> "next up"
        }
        rows += ""
        if (events.isEmpty()) rows += "nothing scheduled"
        for (e in events.take(6)) {
            val start = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault())
            val time = if (e.allDay) "all day" else start.format(DateTimeFormatter.ofPattern("h:mma", us)).lowercase(us)
            val day = if (range == CalendarRange.WEEK || range == CalendarRange.NEXT) start.format(DateTimeFormatter.ofPattern("EEE ", us)).uppercase(us) else ""
            rows += Box.wrap(e.title, (day + time).padEnd(11), max = 2)
        }
        if (events.size > 6) rows += "+ ${events.size - 6} more"
        return Readout(Box.build("CALENDAR", rows), controls = listOf(calendarButton()))
    }

    // ---------------------------------------------------------------- texts, calls

    fun text(to: Contact, message: String, status: String): Readout {
        val rows = ArrayList<String>()
        rows += Box.kv("TO", to.name)
        rows += Box.kv("NUMBER", to.number)
        rows += ""
        rows += Box.wrap(message, "> ", max = 6)
        rows += ""
        rows += Box.kv("STATUS", status)
        return Readout(Box.build("TEXT", rows))
    }

    fun call(to: Contact): Readout {
        val rows = listOf(
            Box.kv("CALL", to.name),
            Box.kv("NUMBER", to.number),
            "",
            Box.kv("STATUS", "dialing"),
        )
        return Readout(Box.build("PHONE", rows))
    }

    // ---------------------------------------------------------------- search, math

    fun search(query: String, answer: SearchService.Answer?, openResults: () -> Unit): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(query, "? ", max = 2)
        rows += ""
        if (answer != null) {
            answer.title?.let { rows += Box.wrap(it.uppercase(us), max = 1) }
            rows += Box.wrap(answer.text, max = 9)
            answer.source?.let { rows += ""; rows += Box.kv("SOURCE", it) }
        } else {
            rows += "no quick answer"
        }
        return Readout(Box.build("SEARCH", rows), image = answer?.image, controls = listOf(Control.Button("Open results", openResults)))
    }

    /** A looked-up person, movie, show, platform...: its kind as the title, its facts, the summary, and its picture. */
    fun lookup(r: LookupResult, openPage: () -> Unit, openResults: () -> Unit, openLink: (String) -> Unit = {}): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(r.title), max = 2)
        r.description?.let { rows += Box.wrap(it, max = 2) }
        if (r.facts.isNotEmpty()) {
            rows += ""
            for ((label, value) in r.facts) rows += Box.wrap(value, first = Box.kv(label, ""), indent = 7, max = 3)
        }
        r.answer?.let { rows += ""; rows += Box.wrap(it, "> ", indent = 2, max = 4) }
        if (r.list.isNotEmpty()) {
            rows += ""
            rows += r.listTitle ?: "MORE"
            for (item in r.list) rows += Box.wrap(Box.ascii(item), "- ", indent = 2, max = 2)
        }
        if (r.summary.isNotBlank()) {
            rows += ""
            rows += Box.wrap(r.summary, max = 7)
        }
        rows += ""
        rows += Box.wrap(r.sources.joinToString(", "), first = Box.kv("SOURCE", ""), indent = 7, max = 2)
        val title = (r.heading ?: r.kind?.label ?: "LOOKUP").uppercase(us)
        val pageName = if (r.url.contains("wikipedia.org")) "Wikipedia" else r.sources.firstOrNull() ?: "Open page"
        val controls = buildList {
            if (r.url.isNotBlank()) add(Control.Button(pageName, openPage))
            r.links.forEach { (label, u) -> add(Control.Button(label) { openLink(u) }) }
            add(Control.Button("Web results", openResults))
        }
        return Readout(Box.build(title, rows), image = r.image, controls = controls)
    }

    /** A unit conversion: the answer, the factor, and the same amount in a few nearby units. */
    fun conversion(c: Conversion): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap("${Units.format(c.amount)} ${c.from.spoken(c.amount)}", max = 2)
        rows += Box.wrap("= ${Units.format(c.result)} ${c.to.spoken(c.result)}", max = 2)
        rows += ""
        rows += Box.kv("KIND", c.from.dim.label)
        if (c.from.dim == Dimension.TEMPERATURE) {
            rows += Box.kv("RULE", when {
                c.from == MeasureUnit.CELSIUS && c.to == MeasureUnit.FAHRENHEIT -> "F = C x 9/5 + 32"
                c.from == MeasureUnit.FAHRENHEIT && c.to == MeasureUnit.CELSIUS -> "C = (F - 32) x 5/9"
                c.to == MeasureUnit.KELVIN -> "K = C + 273.15"
                else -> "C = K - 273.15"
            })
        } else {
            rows += Box.kv("FACTOR", "1 ${c.from.abbr} = ${Units.format(c.from.factor / c.to.factor)} ${c.to.abbr}")
        }
        val more = Units.related(c)
        if (more.isNotEmpty()) {
            rows += ""
            rows += "ALSO"
            for ((u, v) in more) rows += Box.kv("", "${Units.format(v)} ${u.spoken(v)}")
        }
        if (c.from == MeasureUnit.MONTH || c.to == MeasureUnit.MONTH) rows += Box.wrap("A month here is the average, 30.44 days.", max = 2)
        if (c.from == MeasureUnit.YEAR || c.to == MeasureUnit.YEAR) rows += Box.wrap("A year here is 365 days (a leap year is 366).", max = 2)
        return Readout(Box.build("CONVERT", rows))
    }


    /**
     * The card for an answer that has none of its own (directions, apps, YouTube, notes, texts, small talk, errors):
     * what Brick understood from [req] and what it said ([line]), with a button to do it again when there is one ([again]).
     */
    fun summary(req: Request, line: String, again: (() -> Unit)?): Readout {
        val rows = ArrayList<String>()
        val title = when (req) {
            is Request.Navigate -> { rows += Box.kv("TO", req.destination); rows += Box.kv("MODE", if (req.directions) "directions" else "map"); "MAPS" }
            is Request.FindPlace -> { rows += Box.kv("FIND", req.what); req.area?.let { rows += Box.kv("NEAR", it) }; "PLACES" }
            is Request.PlaceHours -> { rows += Box.kv("PLACE", req.place); rows += Box.kv("WHEN", if (req.tomorrow) "tomorrow" else "today"); "HOURS" }
            is Request.YouTube -> { rows += Box.kv("SEARCH", req.query); rows += Box.kv("MODE", if (req.play) "play" else "results"); if (req.music) "YT MUSIC" else "YOUTUBE" }
            is Request.OpenApp -> { rows += Box.kv("APP", req.name); "APP" }
            is Request.Text -> { rows += Box.kv("TO", req.recipient); req.body?.let { rows += Box.wrap(it, Box.kv("SAYS", ""), max = 3) }; "TEXT" }
            is Request.Call -> { rows += Box.kv("CALL", req.who); "CALL" }
            is Request.Discord -> { req.who?.let { rows += Box.kv("WHO", it) }; rows += Box.kv("DO", if (req.reply) "reply" else "read"); "DISCORD" }
            is Request.Note -> { req.text?.let { rows += Box.wrap(it, Box.kv("NOTE", ""), max = 4) }; "NOTE" }
            is Request.Reminder -> { rows += Box.wrap(req.task, Box.kv("TASK", ""), max = 2); "REMINDER" }
            is Request.CalendarEvent -> { rows += Box.wrap(req.title, Box.kv("EVENT", ""), max = 2); "CALENDAR" }
            is Request.ReadNotifications -> { req.from?.let { rows += Box.kv("FROM", it) }; if (req.email) "EMAIL" else "NOTIFY" }
            is Request.ReadCalendar -> "CALENDAR"
            is Request.Search -> { rows += Box.wrap(req.query, Box.kv("QUERY", ""), max = 2); "SEARCH" }
            is Request.Spell -> { rows += Box.kv("WORD", req.word); "SPELL" }
            is Request.Music, is Request.Lyrics, is Request.LikeSong, is Request.NowPlaying, is Request.AddToPlaylist,
            is Request.Effect, is Request.EffectPreset -> "MUSIC"
            is Request.Alarm -> "ALARM"
            is Request.Timer, is Request.TimerControl, is Request.Stopwatch -> "TIMER"
            is Request.Setting -> { rows += Box.kv("SET", req.setting.name.lowercase().replace('_', ' ')); rows += Box.kv("TO", if (req.on) "on" else "off"); "BRICK" }
            is Request.Device, is Request.PhoneSettingReq, is Request.SettingsPageReq, is Request.ScreenTimeoutReq, is Request.AppSettingsReq, is Request.SmartHome -> "DEVICE"
            is Request.Weather -> "WEATHER"
            is Request.Sky, is Request.PhotoSpots -> "SKY"
            is Request.Math, is Request.Convert -> "MATH"
            is Request.Chat -> "BRICK"
            else -> req.category?.name?.replace('_', ' ') ?: "BRICK"
        }
        if (rows.isNotEmpty()) rows += ""
        rows += Box.wrap(line, max = 8)
        val controls = again?.let { listOf(Control.Button(if (req is Request.Navigate || req is Request.OpenApp || req is Request.YouTube) "Open again" else "Again") { it() }) } ?: emptyList()
        return Readout(Box.build(title, rows), controls = controls)
    }
    /** A spelled word: big spaced letters, how many, a correction if it was misspelled, and sound-alikes. */
    fun spelling(r: SpellResult): Readout {
        val rows = ArrayList<String>()
        if (r.corrected) rows += Box.wrap("you said: ${r.asked}", max = 2)
        rows += Box.ascii(r.word).lowercase(us).take(Box.W)
        rows += ""
        rows += Box.wrap(r.letters.joinToString(" ") { if (it == ' ') "_" else it.uppercaseChar().toString() }, max = 4)
        rows += ""
        rows += Box.kv("LETTERS", r.letterCount.toString())
        val vowels = r.word.count { it.lowercaseChar() in "aeiou" }
        rows += Box.kv("VOWELS", vowels.toString())
        val doubles = Patterns.get("([a-z])\\1").findAll(r.word.lowercase(us)).map { it.value }.toList()
        if (doubles.isNotEmpty()) rows += Box.kv("DOUBLE", doubles.joinToString(", "))
        if (r.soundsLike.isNotEmpty()) rows += Box.wrap(r.soundsLike.joinToString(", "), first = Box.kv("NOT", ""), indent = 7, max = 2)
        return Readout(Box.build("SPELL", rows))
    }

    fun math(r: MathEvaluator.Result): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(r.expression.ifBlank { r.spoken }, max = 3)
        rows += ""
        r.steps.take(8).forEachIndexed { i, s -> rows += Box.wrap(s, "${i + 1}) ", max = 2) }
        if (r.steps.size > 8) rows += "   ... ${r.steps.size - 8} more steps"
        rows += ""
        rows += Box.kv("ANSWER", MathEvaluator.plain(r.value))
        return Readout(Box.build("MATH", rows))
    }

    // ---------------------------------------------------------------- notifications

    fun notifications(items: List<NoticeItem>, email: Boolean): Readout {
        val rows = ArrayList<String>()
        if (items.isEmpty()) rows += if (email) "no new emails" else "no notifications"
        val fmt = DateTimeFormatter.ofPattern("h:mma", us)
        for (n in items.take(6)) {
            if (rows.isNotEmpty()) rows += ""
            val t = Instant.ofEpochMilli(n.time).atZone(ZoneId.systemDefault()).format(fmt).lowercase(us)
            rows += Box.ascii(n.app).uppercase(us).take(Box.W - t.length - 1).padEnd(Box.W - t.length) + t
            if (n.title.isNotBlank()) rows += Box.wrap(n.title, max = 1)
            if (n.text.isNotBlank()) rows += Box.wrap(n.text, "  ", max = 2)
        }
        if (items.size > 6) { rows += ""; rows += "+ ${items.size - 6} more" }
        return Readout(Box.build(if (email) "EMAIL" else "NOTIFICATIONS", rows))
    }

    // ---------------------------------------------------------------- device settings

    fun volume(): Readout {
        val am = context.getSystemService(AudioManager::class.java)
        fun pct(stream: Int) = am?.let { (it.getStreamVolume(stream) * 100.0 / it.getStreamMaxVolume(stream).coerceAtLeast(1)).roundToInt() } ?: 0
        val rows = listOf(
            Box.kv("MEDIA", Box.percent(pct(AudioManager.STREAM_MUSIC))),
            Box.kv("RING", Box.percent(pct(AudioManager.STREAM_RING))),
            Box.kv("ALARM", Box.percent(pct(AudioManager.STREAM_ALARM))),
            Box.kv("MODE", ringerName()),
        )
        return Readout(Box.build("VOLUME", rows), controls = listOf(mediaVolume(), settingsButton("Sound settings", Settings.ACTION_SOUND_SETTINGS)), refresh = ::volume)
    }

    private fun ringerName(): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val dnd = nm != null && nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        return when {
            dnd -> "do not disturb"
            am.ringerMode == AudioManager.RINGER_MODE_SILENT -> "silent"
            am.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
    }

    fun ringer(): Readout {
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val access = nm?.isNotificationPolicyAccessGranted == true
        val rows = listOf(Box.kv("MODE", ringerName()), Box.kv("ACCESS", if (access) "granted" else "needs Do Not Disturb access"))
        val controls = ArrayList<Control>()
        if (am != null && access) {
            controls += Control.Toggle("Do Not Disturb",
                get = { (nm.currentInterruptionFilter) > android.app.NotificationManager.INTERRUPTION_FILTER_ALL },
                set = { on -> nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL) })
            controls += Control.Button("Ring") { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
            controls += Control.Button("Vibrate") { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            controls += Control.Button("Silent") { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
        }
        controls += settingsButton("Sound settings", Settings.ACTION_SOUND_SETTINGS)
        return Readout(Box.build("SOUND MODE", rows), controls = controls, refresh = ::ringer)
    }

    fun flashlight(): Readout {
        val on = DeviceControls.torchOn
        return Readout(
            Box.build("FLASHLIGHT", listOf(Box.kv("STATE", if (on) "on" else "off"), Box.kv("LIGHT", Box.bar(if (on) 10 else 0, 10)))),
            controls = listOf(Control.Toggle("Flashlight", get = { DeviceControls.torchOn }, set = { v -> DeviceControls.flashlight(context, v) })),
            refresh = ::flashlight,
        )
    }

    fun brightness(): Readout {
        val cr = context.contentResolver
        val canWrite = Settings.System.canWrite(context)
        fun level() = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255.0).roundToInt()
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val rows = listOf(Box.kv("LEVEL", Box.percent(level())), Box.kv("AUTO", if (auto) "on" else "off"))
        val controls = ArrayList<Control>()
        if (canWrite) {
            controls += Control.Slider("Brightness", get = { level().toFloat() }, range = 1f..100f, format = { "${it.roundToInt()}%" }) { v ->
                try {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255 / 100).roundToInt().coerceIn(1, 255))
                } catch (_: Exception) { }
            }
            controls += Control.Toggle("Adaptive brightness",
                get = { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC },
                set = { on ->
                    try {
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    } catch (_: Exception) { }
                })
        }
        controls += settingsButton("Display settings", Settings.ACTION_DISPLAY_SETTINGS)
        return Readout(Box.build("BRIGHTNESS", rows), controls = controls, refresh = ::brightness)
    }

    fun battery(): Readout {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val sticky = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val rows = ArrayList<String>()
        rows += Box.kv("PHONE", Box.percent(pct))
        rows += Box.kv("POWER", if (bm?.isCharging == true) "charging" else if (plugged) "plugged in" else "on battery")
        if (temp > 0) rows += Box.kv("TEMP", "%.1fC".format(us, temp))
        return Readout(Box.build("BATTERY", rows), controls = listOf(Control.Button("Battery settings", open(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)))))
    }

    fun btBatteries(devices: List<BtDevice>): Readout {
        val rows = ArrayList<String>()
        if (devices.isEmpty()) rows += "no devices connected"
        for (d in devices) {
            rows += Box.ascii(d.name).take(Box.W)
            rows += Box.kv("", if (d.level >= 0) Box.percent(d.level) else "battery not reported")
        }
        return Readout(Box.build("BLUETOOTH", rows), controls = listOf(settingsButton("Bluetooth settings", Settings.ACTION_BLUETOOTH_SETTINGS)))
    }

    fun connection(wifi: Boolean, panel: Intent): Readout {
        val rows = ArrayList<String>()
        if (wifi) {
            val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            rows += Box.kv("WI-FI", if (wm?.isWifiEnabled == true) "on" else "off")
        } else {
            val on = try { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Exception) { false }
            rows += Box.kv("BT", if (on) "on" else "off")
        }
        rows += "Android only lets you switch"
        rows += "this from its own panel."
        return Readout(Box.build(if (wifi) "WI-FI" else "BLUETOOTH", rows), controls = listOf(Control.Button(if (wifi) "Wi-Fi panel" else "Bluetooth settings", open(Intent(panel)))))
    }

    // ---------------------------------------------------------------- phone settings

    /** How Brick can change [s], for the CAN row. */
    private fun reachText(s: PhoneSetting): String = when (s.reach) {
        SettingReach.DIRECT -> when {
            s == PhoneSetting.DND -> if (context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true) "switch it here" else "needs DND access"
            s == PhoneSetting.SOUND_MODE || s == PhoneSetting.VOLUME -> "change it here"
            PhoneSettings.canWrite(context) -> "switch it here"
            else -> "allow Brick first"
        }
        SettingReach.ASSISTANT -> if (com.brick.assistant.assist.BrickVoiceInteractionService.active != null) "switch it here" else "needs Brick as assistant"
        SettingReach.OPEN -> if (s == PhoneSetting.BLUETOOTH) "turn on here, off in panel" else "opens its panel"
    }

    /** The switch (or buttons) for [s] on a readout, when Brick can change it from there. */
    private fun controlsFor(s: PhoneSetting): List<Control> {
        val c = ArrayList<Control>()
        when (s.reach) {
            SettingReach.DIRECT -> when (s) {
                PhoneSetting.SOUND_MODE -> {
                    c += Control.Button("Sound") { PhoneSettings.setSoundMode(context, AudioManager.RINGER_MODE_NORMAL) }
                    c += Control.Button("Vibrate") { PhoneSettings.setSoundMode(context, AudioManager.RINGER_MODE_VIBRATE) }
                    c += Control.Button("Mute") { PhoneSettings.setSoundMode(context, AudioManager.RINGER_MODE_SILENT) }
                }
                PhoneSetting.VOLUME -> {
                    c += mediaVolume()
                    listOf("Ring" to AudioManager.STREAM_RING, "Notifications" to AudioManager.STREAM_NOTIFICATION, "Alarm" to AudioManager.STREAM_ALARM).forEach { (label, stream) ->
                        streamSlider(label, stream)?.let { c += it }
                    }
                }
                PhoneSetting.DND -> {
                    val nm = context.getSystemService(android.app.NotificationManager::class.java)
                    if (nm?.isNotificationPolicyAccessGranted == true) c += Control.Toggle(s.title, { PhoneSettings.isOn(context, s) == true }) { PhoneSettings.setDirect(context, s, it) }
                    else c += assistantToggle(s)
                }
                else -> if (PhoneSettings.canWrite(context)) {
                    c += Control.Toggle(s.title, { PhoneSettings.isOn(context, s) == true }) { PhoneSettings.setDirect(context, s, it) }
                } else {
                    c += Control.Button("Allow Brick to change it", open(PhoneSettings.writePermission(context)))
                }
            }
            SettingReach.ASSISTANT -> c += assistantToggle(s)
            SettingReach.OPEN -> if (s == PhoneSetting.BLUETOOTH && PhoneSettings.isOn(context, s) == false) {
                c += Control.Button("Turn on") { PhoneSettings.requestBluetoothOn(context) }
            }
        }
        if (s == PhoneSetting.MEDIA_OUTPUT) c += Control.Button("Pick output") { PhoneSettings.open(context, s) }
        else c += Control.Button("Open ${s.title}") { PhoneSettings.open(context, s) }
        return c
    }

    /** Airplane mode, power saving or Do Not Disturb, switched through the assistant session (or its page otherwise). */
    private fun assistantToggle(s: PhoneSetting) = Control.Toggle(s.title, { PhoneSettings.isOn(context, s) == true }) { on ->
        val i = PhoneSettings.assistantIntent(s, on)
        if (i == null || !com.brick.assistant.assist.BrickVoiceInteractionService.runVoiceAction(i)) PhoneSettings.open(context, s)
    }

    private fun streamSlider(label: String, stream: Int): Control.Slider? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return Control.Slider(label, get = { am.getStreamVolume(stream) * 100f / max }, range = 0f..100f, format = { "${it.roundToInt()}%" }) { v ->
            try { am.setStreamVolume(stream, (v * max / 100).roundToInt(), 0) } catch (_: Exception) { } // ring at 0 needs DND access
        }
    }

    /** One phone setting: its state, whether Brick can switch it, and where it is in Settings and the quick panel. */
    fun phoneSetting(s: PhoneSetting): Readout {
        val on = PhoneSettings.isOn(context, s)
        val rows = ArrayList<String>()
        rows += Box.kv("STATE", PhoneSettings.stateText(context, s) + if (on != null) "  " + Box.bar(if (on) 6 else 0, 6) else "")
        rows += Box.kv("CAN", reachText(s))
        rows += ""
        rows += Box.wrap(s.path, first = Box.kv("WHERE", ""), indent = 7, max = 4)
        s.quickLine?.let { rows += Box.wrap(it.removePrefix("Quick panel: "), first = Box.kv("QUICK", ""), indent = 7, max = 3) }
        return Readout(Box.build(Box.ascii(s.title).uppercase(us), rows), controls = controlsFor(s), refresh = { phoneSetting(s) })
    }

    /** A page of the Settings app: where it is, and a button to go there. */
    fun settingsPage(p: SettingsPage): Readout {
        val rows = ArrayList<String>()
        rows += Box.wrap(p.path, first = Box.kv("WHERE", ""), indent = 7, max = 4)
        return Readout(Box.build(Box.ascii(p.title).uppercase(us), rows),
            controls = listOf(Control.Button("Open ${p.title}") { SettingsPages.open(context, p) }))
    }

    /** The screen timeout: how long now, buttons for Samsung's choices, and where it is. */
    fun screenTimeout(): Readout {
        val now = ScreenTimeout.get(context)
        val rows = ArrayList<String>()
        rows += Box.kv("NOW", now?.let { ScreenTimeout.say(it) } ?: "unknown")
        rows += Box.kv("CAN", if (PhoneSettings.canWrite(context)) "change it here" else "allow Brick first")
        rows += ""
        rows += Box.wrap(SettingsPage.SCREEN_TIMEOUT.path, first = Box.kv("WHERE", ""), indent = 7, max = 4)
        val controls = ArrayList<Control>()
        if (PhoneSettings.canWrite(context)) {
            for (ms in ScreenTimeout.STEPS) controls += Control.Button(ScreenTimeout.short(ms)) { ScreenTimeout.set(context, ms) }
        } else {
            controls += Control.Button("Allow Brick to change it", open(PhoneSettings.writePermission(context)))
        }
        controls += Control.Button("Open in Settings") { SettingsPages.open(context, SettingsPage.SCREEN_TIMEOUT) }
        return Readout(Box.build("SCREEN TIMEOUT", rows), controls = controls, refresh = ::screenTimeout)
    }

    /** Every phone setting Brick knows, with its state, the switches Brick can flip, and a way to the rest. */
    fun phoneSettings(): Readout {
        val rows = ArrayList<String>()
        for (s in PhoneSetting.entries.filter { it.inOverview }) rows += Box.kv(s.tag, PhoneSettings.stateText(context, s))
        rows += ""
        rows += Box.wrap("Ask \"where is NFC\" (or any of these) for its menu.", max = 3)
        val controls = ArrayList<Control>()
        if (PhoneSettings.canWrite(context)) {
            listOf(PhoneSetting.AUTO_ROTATE, PhoneSetting.ADAPTIVE_BRIGHTNESS).forEach { s ->
                controls += Control.Toggle(s.title, { PhoneSettings.isOn(context, s) == true }) { PhoneSettings.setDirect(context, s, it) }
            }
        }
        listOf(PhoneSetting.AIRPLANE, PhoneSetting.POWER_SAVING, PhoneSetting.DND).forEach { controls += controlsFor(it).first() }
        controls += mediaVolume()
        listOf(PhoneSetting.WIFI, PhoneSetting.BLUETOOTH, PhoneSetting.MOBILE_DATA, PhoneSetting.HOTSPOT, PhoneSetting.NFC,
            PhoneSetting.QUICK_SHARE, PhoneSetting.MEDIA_OUTPUT, PhoneSetting.AOD).forEach { s ->
            controls += Control.Button(s.title) { PhoneSettings.open(context, s) }
        }
        return Readout(Box.build("PHONE SETTINGS", rows), controls = controls, refresh = ::phoneSettings)
    }

    // ---------------------------------------------------------------- smart home

    /** Govee lights (switch and brightness each) and the home apps on the phone. */
    fun smartHome(key: String, devices: List<GoveeDevice>, shown: List<GoveeDevice> = devices): Readout {
        val rows = ArrayList<String>()
        val controls = ArrayList<Control>()
        if (key.isBlank()) {
            rows += Box.wrap("Brick switches Govee lights once you add a Govee API key.", max = 3)
            rows += Box.wrap("Brick > Settings > Smart home", first = Box.kv("KEY", ""), indent = 7, max = 2)
            rows += Box.wrap("Govee Home > Profile > Settings > Apply for API Key", first = Box.kv("GET IT", ""), indent = 7, max = 3)
        } else if (shown.isEmpty()) {
            rows += "no Govee lights found"
        }
        for (d in shown) {
            rows += Box.ascii(d.name).take(Box.W)
            val b = d.brightness
            rows += Box.kv("", (when (d.on) { true -> "on"; false -> "off"; null -> "?" }) + if (b != null && d.on == true) "  " + Box.percent(b) else "")
            // Changes show at once; Govee gets them in order, a slider's only once the drag settles (see Govee.setBrightness).
            controls += Control.Toggle(d.name, { d.on == true }) { on -> Govee.setPower(key, d, on) }
            if (d.canDim) controls += Control.Slider("${d.name} brightness", get = { (d.brightness ?: 100).toFloat() }, range = 1f..100f,
                format = { "${it.roundToInt()}%" }) { v -> Govee.setBrightness(key, d, v.roundToInt()) }
        }
        val apps = HomeApp.entries.filter { it.installed(context) }
        if (apps.isNotEmpty()) {
            rows += ""
            rows += Box.wrap(apps.joinToString(", ") { it.label }, first = Box.kv("APPS", ""), indent = 7, max = 3)
        }
        apps.forEach { a -> controls += Control.Button(a.label) { a.open(context) } }
        return Readout(Box.build("SMART HOME", rows), controls = controls, refresh = { smartHome(key, Govee.devices.ifEmpty { devices }, shown) })
    }

    // ---------------------------------------------------------------- Brick settings

    /** One of Brick's settings: its state, a switch that changes it, and a button to Brick's Settings tab. */
    fun brickSetting(label: String, get: () -> Boolean, set: (Boolean) -> Unit, onWord: String = "on", offWord: String = "off"): Readout {
        val on = get()
        val rows = listOf(Box.wrap(label, max = 2), listOf("", Box.kv("STATE", if (on) onWord else offWord), Box.kv("", Box.bar(if (on) 10 else 0, 10)))).flatten()
        return Readout(Box.build("BRICK SETTING", rows), controls = listOf(Control.Toggle(label, get, set), brickSettings()), refresh = { brickSetting(label, get, set, onWord, offWord) })
    }
}
