package com.brick.assistant.brain

import android.content.Context

/** Brick's own stopwatch, kept in preferences so it survives Brick closing. Times are wall-clock milliseconds. */
object Stopwatch {
    private fun prefs(c: Context) = c.getSharedPreferences("stopwatch", Context.MODE_PRIVATE)

    /** Running since (null = stopped), time banked before that, laps (elapsed at each lap). */
    class State(val since: Long?, val banked: Long, val firstStart: Long?, val laps: List<Long>) {
        val running get() = since != null
        fun elapsed(now: Long = System.currentTimeMillis()) = banked + (since?.let { now - it } ?: 0)
    }

    fun state(c: Context): State {
        val p = prefs(c)
        return State(
            p.getLong("since", -1).takeIf { it > 0 },
            p.getLong("banked", 0),
            p.getLong("first", -1).takeIf { it > 0 },
            p.getString("laps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        )
    }

    private fun save(c: Context, s: State) = prefs(c).edit()
        .putLong("since", s.since ?: -1).putLong("banked", s.banked).putLong("first", s.firstStart ?: -1)
        .putString("laps", s.laps.joinToString(",")).apply()

    fun start(c: Context): State {
        val s = state(c)
        if (s.running) return s
        val now = System.currentTimeMillis()
        return State(now, s.banked, s.firstStart ?: now, s.laps).also { save(c, it) }
    }

    fun stop(c: Context): State {
        val s = state(c)
        if (!s.running) return s
        return State(null, s.elapsed(), s.firstStart, s.laps).also { save(c, it) }
    }

    fun reset(c: Context): State = State(null, 0, null, emptyList()).also { save(c, it) }

    fun lap(c: Context): State {
        val s = state(c)
        if (!s.running) return s
        return State(s.since, s.banked, s.firstStart, s.laps + s.elapsed()).also { save(c, it) }
    }
}
