package com.brick.assistant.media

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Keeps lyrics in time with Spotify. Android's media session reports Spotify's position only now and then, and
 * it can lag; Spotify's own "currently playing" answer has the exact place. This asks Spotify now and then and
 * corrects the session's position by the difference (an offset), so the lyric lines land on time.
 *
 * Gentle on Spotify's rate limit: one request at a time, only while the song plays and the lyrics are on screen
 * (it's asked from the drawing), every [NORMAL_MS] (stretching to [STEADY_MS] once the timing holds steady), a
 * quick check after a seek or play/pause, and when Spotify says 429 it waits as long as Retry-After says and
 * then asks half as often. Without permission (an account connected before Brick asked for it) it stops asking
 * and the session's position is used as is.
 */
class SpotifySync(
    private val fetch: () -> Reading?,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val runInBackground: (() -> Unit) -> Unit = { job -> kotlin.concurrent.thread(name = "brick-spotify-sync") { job() } },
    private val log: (String) -> Unit = {},
) {
    /** One answer from Spotify. */
    sealed class Reading {
        /** [progressMs] into [trackName], which is [playing]. */
        data class Now(val progressMs: Long, val playing: Boolean, val trackName: String?) : Reading()
        /** Nothing is playing on the account (204). */
        object Idle : Reading()
        /** The connection lacks the permission (401/403): stop asking. */
        object NoPermission : Reading()
        /** 429: wait [retryAfterSec] seconds. */
        data class SlowDown(val retryAfterSec: Long?) : Reading()
    }

    @Volatile var offsetMs = 0L
        private set
    @Volatile var disabled = false
        private set
    @Volatile private var synced = false
    @Volatile private var nextAt = 0L
    @Volatile private var interval = NORMAL_MS
    @Volatile private var steady = 0
    @Volatile private var lastAskAt = -MIN_GAP_MS
    private val asking = AtomicBoolean(false)

    /**
     * The corrected position for [sessionPos] (the media session's position now), asking Spotify in the background
     * when it's time. [playing] is whether the session says it's playing; [title] is the song the lyrics are for
     * (an answer about another song is ignored); [positionNow] reads the session position again after the answer.
     */
    fun position(sessionPos: Long?, playing: Boolean, title: String, positionNow: () -> Long?): Long? {
        if (sessionPos == null) return null
        val now = clock()
        if (!disabled && playing && now >= nextAt && now - lastAskAt >= MIN_GAP_MS && asking.compareAndSet(false, true)) {
            lastAskAt = now
            runInBackground { try { ask(title, positionNow) } finally { asking.set(false) } }
        }
        return if (synced) (sessionPos + offsetMs).coerceAtLeast(0) else sessionPos
    }

    /** After a seek or play/pause: check again soon (still no sooner than [MIN_GAP_MS] after the last ask). */
    fun nudge() {
        steady = 0
        if (interval < NORMAL_MS || interval == STEADY_MS) interval = NORMAL_MS // a 429 backoff stays as it is
        nextAt = clock() + AFTER_SEEK_MS
    }

    private fun ask(title: String, positionNow: () -> Long?) {
        val t0 = clock()
        val r = fetch()
        val t1 = clock()
        log("sync answer ${r?.javaClass?.simpleName ?: "none"} in ${t1 - t0} ms, offset $offsetMs ms, next in ${interval / 1000} s")
        when (r) {
            null -> nextAt = t1 + FAILED_MS
            is Reading.NoPermission -> disabled = true
            is Reading.Idle -> nextAt = t1 + interval
            is Reading.SlowDown -> {
                interval = (interval * 2).coerceAtMost(MAX_MS)
                nextAt = t1 + maxOf((r.retryAfterSec ?: 30L) * 1000, interval)
            }
            is Reading.Now -> {
                nextAt = t1 + interval
                val rtt = t1 - t0
                if (!r.playing || rtt > MAX_RTT_MS) return
                if (r.trackName != null && SpotifyPlayer.similarity(r.trackName, title) < 0.5) return
                // Spotify's progress is from about halfway through the request.
                val pos = positionNow() ?: return
                val est = r.progressMs - (pos - rtt / 2)
                if (abs(est) > MAX_OFFSET_MS) return // a seek in flight, or not the same moment: wait for the next one
                if (!synced) { offsetMs = est; synced = true; return }
                val change = est - offsetMs
                offsetMs += change / 2
                // Holding steady: ask less often. Drifting: back to the usual pace.
                if (abs(change) < STEADY_WITHIN_MS) { if (++steady >= 3) interval = STEADY_MS } else { steady = 0; interval = NORMAL_MS }
            }
        }
    }

    companion object {
        const val NORMAL_MS = 20_000L
        const val STEADY_MS = 45_000L
        const val MAX_MS = 120_000L
        const val FAILED_MS = 30_000L
        const val AFTER_SEEK_MS = 1_500L
        const val MIN_GAP_MS = 5_000L
        const val MAX_RTT_MS = 1_500L
        const val MAX_OFFSET_MS = 15_000L
        const val STEADY_WITHIN_MS = 250L
    }
}
