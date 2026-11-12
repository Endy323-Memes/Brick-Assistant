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

    // ---------------------------------------------------------------- Brick settings

    /** One of Brick's settings: its state, a switch that changes it, and a button to Brick's Settings tab. */
    fun brickSetting(label: String, get: () -> Boolean, set: (Boolean) -> Unit, onWord: String = "on", offWord: String = "off"): Readout {
        val on = get()
        val rows = listOf(Box.wrap(label, max = 2), listOf("", Box.kv("STATE", if (on) onWord else offWord), Box.kv("", Box.bar(if (on) 10 else 0, 10)))).flatten()
        return Readout(Box.build("BRICK SETTING", rows), controls = listOf(Control.Toggle(label, get, set), brickSettings()), refresh = { brickSetting(label, get, set, onWord, offWord) })
    }
}
