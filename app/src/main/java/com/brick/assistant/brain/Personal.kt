package com.brick.assistant.brain

import android.Manifest
import android.app.Notification
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.service.notification.StatusBarNotification
import com.brick.assistant.media.BrickNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// ==================================================================== Notifications and email

/** One notification, as Brick reads it. */
data class NoticeItem(val app: String, val title: String, val text: String, val time: Long, val email: Boolean)

object NotificationReader {
    private val emailApps = setOf(
        "com.google.android.gm", "com.samsung.android.email.provider", "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "com.fsck.k9", "com.google.android.apps.inbox",
        "me.proton.android.mail", "com.readdle.spark", "com.edison.android.mail",
    )

    private val SPACES = Regex("\\s+")

    fun hasAccess(): Boolean = BrickNotificationListener.instance != null

    /** Readable notifications, newest first (no ongoing ones, group headers, or Brick's own). */
    fun current(context: Context): List<NoticeItem> {
        val l = BrickNotificationListener.instance ?: return emptyList()
        val all = try { l.activeNotifications.orEmpty().toList() } catch (e: Exception) { emptyList() }
        return all.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) == 0 }
            .mapNotNull { toItem(context, it) }
            .distinctBy { it.app + it.title + it.text }
            .sortedByDescending { it.time }
            .toList()
    }

    private fun toItem(context: Context, sbn: StatusBarNotification): NoticeItem? {
        val e = sbn.notification.extras ?: return null
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
            ?.replace(SPACES, " ")?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val app = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val email = sbn.packageName in emailApps || sbn.notification.category == Notification.CATEGORY_EMAIL
        return NoticeItem(app, title, text, sbn.postTime, email)
    }

    private fun short(s: String, max: Int = 90): String =
        if (s.length <= max) s else s.take(max).substringBeforeLast(' ').trimEnd(',', '.', ';', ':') + "…"

    /** The robot's summary: "You have 3 notifications. Messages, Mom: on my way. …" */
    fun speak(items: List<NoticeItem>, email: Boolean, from: String?): String {
        val what = if (email) "email" else "notification"
        val list = if (from != null) {
            items.filter { it.app.contains(from, ignoreCase = true) || it.title.contains(from, ignoreCase = true) || it.text.contains(from, ignoreCase = true) }
        } else {
            items
        }
        if (list.isEmpty()) {
            return when {
                from != null -> "I don't see any ${what}s from $from."
                email -> "You have no new emails in your notifications."
                else -> "You have no notifications."
            }
        }
        val shown = list.take(5)
        val head = (if (list.size == 1) "You have one $what." else "You have ${list.size} ${what}s.") +
            if (list.size > shown.size) " Here are the latest ${shown.size}." else ""
        val body = shown.joinToString(" ") { n ->
            when {
                email -> "From ${n.title.ifBlank { n.app }}: ${short(n.text.ifBlank { "no subject" })}."
                n.title.isNotBlank() && n.text.isNotBlank() -> "${n.app}, ${n.title}: ${short(n.text)}."
                else -> "${n.app}: ${short(n.title.ifBlank { n.text })}."
            }
        }
        return "$head $body"
    }
}

// ==================================================================== Calendar

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

object CalendarReader {
    fun canRead(context: Context) = context.hasPermission(Manifest.permission.READ_CALENDAR)

    fun events(context: Context, range: CalendarRange, now: LocalDateTime = LocalDateTime.now()): List<CalEvent> {
        val zone = ZoneId.systemDefault()
        val today = now.toLocalDate()
        val (from, to) = when (range) {
            CalendarRange.TODAY -> now to today.plusDays(1).atStartOfDay()
            CalendarRange.TOMORROW -> today.plusDays(1).atStartOfDay() to today.plusDays(2).atStartOfDay()
            CalendarRange.WEEK -> now to today.plusDays(7).atStartOfDay()
            CalendarRange.NEXT -> now to now.plusDays(60)
        }
        val out = between(context, from.atZone(zone).toInstant().toEpochMilli(), to.atZone(zone).toInstant().toEpochMilli()) ?: return emptyList()
        return if (range == CalendarRange.NEXT) out.filter { !it.allDay }.take(1).ifEmpty { out.take(1) } else out
    }

    /**
     * The next event (or, for [past], the latest one) within a year whose title has every word of [what]:
     * "my vacation" finds "Vacation in Maine", "dentist" finds "Dentist appointment".
     */
    fun find(context: Context, what: String, past: Boolean = false, now: LocalDateTime = LocalDateTime.now()): CalEvent? {
        val words = what.lowercase().split(Regex("[^a-z0-9']+"))
            .filter { it.length > 1 && it !in setOf("my", "the", "our", "a", "an", "next", "upcoming", "appointment", "event") }
            .map { it.removeSuffix("'s") }
        if (words.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val year = 366L * 86_400_000L
        val events = (if (past) between(context, nowMs - year, nowMs) else between(context, nowMs, nowMs + year)) ?: return null
        val hits = events.filter { e -> val t = e.title.lowercase(); words.all { it in t } }
        return if (past) hits.lastOrNull { it.begin <= nowMs } else hits.firstOrNull()
    }

    private fun between(context: Context, start: Long, end: Long): List<CalEvent>? {
        val zone = ZoneId.systemDefault()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start - 86_400_000L) // all-day events are stored in UTC
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.VISIBLE,
        )
        val out = ArrayList<CalEvent>()
        try {
            context.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0) continue
                    val allDay = c.getInt(3) == 1
                    var b = c.getLong(1)
                    var e = c.getLong(2)
                    if (allDay) { // UTC midnight -> local midnight of the same date
                        b = Instant.ofEpochMilli(b).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                        e = Instant.ofEpochMilli(e).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    // Keep events that overlap the range (and haven't ended yet).
                    if (e <= start || b >= end) continue
                    out += CalEvent(c.getString(0)?.trim().orEmpty().ifBlank { "an untitled event" }, b, e, allDay, c.getString(4)?.trim()?.ifBlank { null })
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    private fun time(ms: Long) = TimeParser.speakTime(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime())

    private fun day(ms: Long, today: LocalDate): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (d) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        }
    }

    private fun one(e: CalEvent, withDay: Boolean, today: LocalDate): String {
        val at = when {
            e.allDay -> "all day"
            else -> "at ${time(e.begin)}"
        }
        val d = if (withDay) " ${day(e.begin, today)}" else ""
        return "${e.title}$d $at".replace("  ", " ")
    }

    fun speak(events: List<CalEvent>, range: CalendarRange, today: LocalDate = LocalDate.now()): String {
        val whenWord = when (range) {
            CalendarRange.TODAY -> "today"
            CalendarRange.TOMORROW -> "tomorrow"
            CalendarRange.WEEK -> "this week"
            CalendarRange.NEXT -> "coming up"
        }
        if (events.isEmpty()) return if (range == CalendarRange.NEXT) "You have nothing coming up on your calendar." else "Your calendar is clear $whenWord."
        if (range == CalendarRange.NEXT) {
            val e = events[0]
            return "Your next event is ${one(e, true, today)}" + (e.location?.let { ", at $it." } ?: ".")
        }
        val withDay = range == CalendarRange.WEEK
        val shown = events.take(6).map { one(it, withDay, today) }
        val list = if (shown.size == 1) shown[0] else shown.dropLast(1).joinToString(", ") + ", and " + shown.last()
        val count = if (events.size == 1) "one event" else "${events.size} events"
        return "You have $count $whenWord: $list." + if (events.size > shown.size) " Plus ${events.size - shown.size} more." else ""
    }
}

// ==================================================================== Notes

object NotesWriter {
    const val SAMSUNG_NOTES = "com.samsung.android.app.notes"
    private const val KEEP = "com.google.android.keep"

    /** Opens a new note containing [text] in the phone's notes app (Samsung Notes first, then Keep). */
    fun open(context: Context, text: String): Boolean {
        fun go(i: Intent): Boolean = try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
        val title = text.lineSequence().first().take(40)
        // Body text only: a subject would become the note's title instead of its content.
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        // Samsung Notes: its "new note" composer accepts shared text.
        if (go(Intent(send).setClassName(SAMSUNG_NOTES, "$SAMSUNG_NOTES.nativecomposer.NativeComposerActionNewActivity"))) return true
        if (go(Intent(send).setPackage(SAMSUNG_NOTES))) return true
        // Google's "create note" action (Keep and other notes apps).
        if (go(Intent("com.google.android.gms.actions.CREATE_NOTE").putExtra(Intent.EXTRA_TEXT, text).putExtra("android.intent.extra.TITLE", title))) return true
        if (go(Intent(send).setPackage(KEEP))) return true
        return go(Intent.createChooser(send, "Save note"))
    }
}

// ==================================================================== YouTube

object YouTubeSearch {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val videoRenderer = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    /** The top regular video (not an ad or a short) for [query], or null. */
    suspend fun firstVideoId(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://www.youtube.com/results?search_query=${Http.enc(query)}&hl=en").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            c.disconnect()
            body?.let { firstIdIn(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun firstIdIn(html: String): String? = videoRenderer.find(html)?.groupValues?.get(1)

    /** A well-known YouTube channel or video that a search plainly means ("markiplier"). */
    data class Popular(val name: String, val id: String, val isChannel: Boolean)

    /** The popular channel or video [query] names, or null when YouTube isn't what it's about. */
    suspend fun popular(query: String): Popular? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://www.youtube.com/results?search_query=${Http.enc(query)}&hl=en").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 7000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            c.disconnect()
            body?.let { popularIn(it, query) }
        } catch (e: Exception) {
            null
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** "39M subscribers" / "1.2K" -> 39000000 / 1200. */
    fun count(text: String): Long {
        val m = Regex("([\\d.,]+)\\s*([KMB])?", RegexOption.IGNORE_CASE).find(text) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (n * mult).toLong()
    }

    fun popularIn(html: String, query: String): Popular? {
        val q = norm(query)
        if (q.isEmpty()) return null
        // YouTube fixes misspellings ("markipler" -> "markiplier"): match against what it searched.
        val corrected = Regex("\"correctedQuery\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { norm(it) }
        val names = listOfNotNull(q, corrected)
        fun same(a: String, b: String): Boolean {
            val x = a.replace(" ", "")
            val y = b.replace(" ", "")
            return x == y || (x.length >= 5 && AppLauncher.similarity(x, y) >= 0.8)
        }
        // A big channel whose name is what you said.
        val ci = html.indexOf("\"channelRenderer\":{")
        if (ci >= 0) {
            val chunk = html.substring(ci, minOf(html.length, ci + 6000))
            val id = Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(chunk)?.groupValues?.get(1)
            val title = Regex("\"title\":\\{\"simpleText\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            val subs = Regex("\"(?:videoCountText|subscriberCountText)\":\\{(?:\"accessibility\":\\{\"accessibilityData\":\\{\"label\":\"[^\"]*\"\\}\\},)?\"simpleText\":\"([^\"]*subscribers?)\"").find(chunk)?.groupValues?.get(1)
            val handle = Regex("\"canonicalBaseUrl\":\"/@([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            if (id != null && title != null && subs != null && count(subs) >= 1_000_000 &&
                names.any { n -> same(n, norm(title)) || (handle != null && same(n, norm(handle))) }
            ) return Popular(title, id, true)
        }
        // A hugely watched video whose title is what you said ("never gonna give you up").
        val vi = html.indexOf("\"videoRenderer\":{")
        if (vi >= 0 && q.split(" ").size >= 2) {
            val chunk = html.substring(vi, minOf(html.length, vi + 9000))
            val id = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(chunk)?.groupValues?.get(1)
            val title = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            val views = Regex("\"viewCountText\":\\{\"simpleText\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            if (id != null && title != null && views != null && count(views) >= 5_000_000) {
                val t = norm(title)
                if (names.any { n -> t == n || t.startsWith("$n ") || t.contains(" $n ") || t.startsWith(n) && n.length >= 12 }) return Popular(title, id, false)
            }
        }
        return null
    }
}
