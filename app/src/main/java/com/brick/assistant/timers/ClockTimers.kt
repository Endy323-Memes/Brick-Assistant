package com.brick.assistant.timers

import com.brick.assistant.brain.Patterns

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.service.notification.StatusBarNotification
import com.brick.assistant.media.BrickNotificationListener
import org.json.JSONArray
import org.json.JSONObject

enum class TimerPress { PAUSE, RESUME, CANCEL }

/**
 * Timers live in the phone's default Clock app. Android has no public way to pause or resume them, so
 * Brick presses the Pause / Resume / Cancel buttons on the Clock app's own timer notification (through
 * the notification access Brick already uses for Spotify). Brick also remembers the timers it set, so it
 * can say exactly how much time is left.
 */
object ClockTimers {
    private const val PREFS = "clock_timers"

    // ------------------------------------------------------------------ the Clock app's notification

    fun clockPackage(context: Context): String? = try {
        context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SET_TIMER), 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
    } catch (e: Exception) {
        null
    }

    /** True when Brick can see the Clock app's notifications (notification access is on). */
    fun canControl(): Boolean = BrickNotificationListener.instance != null

    private val words = mapOf(
        TimerPress.PAUSE to Patterns.get("^(?:pause|hold)$", true),
        TimerPress.RESUME to Patterns.get("^(?:resume|continue|start|restart|play)$", true),
        TimerPress.CANCEL to Patterns.get("^(?:cancel|stop|dismiss|reset|delete|end|turn off)$", true),
    )

    /** The Clock app's timer notifications (not the group header), newest first. */
    fun notifications(context: Context): List<StatusBarNotification> {
        val l = BrickNotificationListener.instance ?: return emptyList()
        val pkg = clockPackage(context)
        val all = try { l.activeNotifications.orEmpty().toList() } catch (e: Exception) { emptyList() }
        return all.filter { sbn ->
            (pkg == null || sbn.packageName == pkg) &&
                sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                sbn.notification.actions.orEmpty().any { a -> words.values.any { it.matches(a.title?.toString()?.trim().orEmpty()) } } &&
                isTimer(sbn)
        }.sortedByDescending { it.postTime }
    }

    private fun isTimer(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val text = listOfNotNull(
            n.extras?.getCharSequence(Notification.EXTRA_TITLE), n.channelId, n.group, n.category,
        ).joinToString(" ")
        return Patterns.get("timer|countdown", true).containsMatchIn(text)
    }

    /** Whether a timer notification offers [press] right now (e.g. Resume only when paused). */
    fun offers(sbn: StatusBarNotification, press: TimerPress): Boolean =
        sbn.notification.actions.orEmpty().any { words.getValue(press).matches(it.title?.toString()?.trim().orEmpty()) }

    /** Presses [press] on the newest matching timer, or on every one when [all]. Returns how many were pressed. */
    fun press(context: Context, press: TimerPress, all: Boolean): Int {
        val targets = notifications(context).filter { offers(it, press) }.let { if (all) it else it.take(1) }
        var n = 0
        for (sbn in targets) {
            val a = sbn.notification.actions.first { words.getValue(press).matches(it.title?.toString()?.trim().orEmpty()) }
            try {
                a.actionIntent.send()
                n++
            } catch (e: Exception) {}
        }
        if (n > 0) record(context, press, all)
        return n
    }

    /** "Bricktest / 8:16 AM" -> "8:16 AM" (Samsung Clock shows when the timer ends). */
    fun shownEndTime(sbn: StatusBarNotification): String? {
        val e = sbn.notification.extras ?: return null
        val info = e.keySet().filter { it.contains("secondaryInfo", true) || it == Notification.EXTRA_TEXT || it == Notification.EXTRA_SUB_TEXT }
            .mapNotNull { e.get(it)?.toString() }.joinToString(" ")
        return Patterns.get("\\b(\\d{1,2}:\\d{2}\\s*(?:AM|PM|am|pm)?)").findAll(info).lastOrNull()?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ Brick's own record of the timers it set

    data class Record(val setAt: Long, val durationMs: Long, val endAt: Long?, val remainingMs: Long?) {
        fun left(now: Long = System.currentTimeMillis()) = endAt?.let { (it - now).coerceAtLeast(0) } ?: (remainingMs ?: 0)
        val paused get() = endAt == null
    }

    private fun load(context: Context): MutableList<Record> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]")
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Record(it.getLong("set"), it.getLong("dur"), it.optLong("end", -1).takeIf { v -> v > 0 }, it.optLong("rem", -1).takeIf { v -> v >= 0 })
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(context: Context, list: List<Record>) {
        val now = System.currentTimeMillis()
        val a = JSONArray()
        // Forget timers that finished a while ago.
        list.filter { it.paused || (it.endAt ?: 0) > now - 10 * 60_000 }.takeLast(10).forEach {
            a.put(JSONObject().put("set", it.setAt).put("dur", it.durationMs).put("end", it.endAt ?: -1).put("rem", it.remainingMs ?: -1))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    /** Called when Brick sets a timer in the Clock app. */
    fun started(context: Context, seconds: Long) {
        val now = System.currentTimeMillis()
        save(context, load(context) + Record(now, seconds * 1000, now + seconds * 1000, null))
    }

    private fun record(context: Context, press: TimerPress, all: Boolean) {
        val now = System.currentTimeMillis()
        val list = load(context)
        val idx = when (press) {
            TimerPress.PAUSE -> list.indices.filter { !list[it].paused && list[it].left(now) > 0 }
            TimerPress.RESUME -> list.indices.filter { list[it].paused }
            TimerPress.CANCEL -> list.indices.toList()
        }.sortedByDescending { list[it].setAt }.let { if (all) it else it.take(1) }
        for (i in idx.sortedDescending()) {
            val r = list[i]
            when (press) {
                TimerPress.PAUSE -> list[i] = r.copy(endAt = null, remainingMs = r.left(now))
                TimerPress.RESUME -> list[i] = r.copy(endAt = now + (r.remainingMs ?: 0), remainingMs = null)
                TimerPress.CANCEL -> list.removeAt(i)
            }
        }
        save(context, list)
    }

    /** Timers Brick set that are still running or paused, soonest first. */
    fun known(context: Context): List<Record> {
        val now = System.currentTimeMillis()
        return load(context).filter { it.paused || it.left(now) > 0 }.sortedBy { it.left(now) }
    }

    /** Clears Brick's record when the Clock app shows no timers (they were stopped in the Clock app). */
    fun syncWithClock(context: Context) {
        if (canControl() && notifications(context).isEmpty()) save(context, emptyList())
    }
}
