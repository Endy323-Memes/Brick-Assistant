package com.brick.assistant.brain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Sends each request and Brick's answers to the Brick History app (com.brick.history) when it's installed.
 * History files each request in the chat with the same kind of request and a similar subject (it keeps the
 * topics); Brick tells it what was asked: the request type ([Request] class, e.g. "Weather"), its category and
 * its subject ("tokyo", "taylor swift"). Answers to Brick's questions ("yes", a note's text) follow the request
 * they answer. Lyrics are never sent. Device controls (timers, lights, music, phone
 * settings, opening apps) and current events (live scores, injuries, headlines) aren't saved at all; the infographics of saved requests get a "Don't save" button
 * that takes the request back out.
 */
object HistoryLink {
    const val PACKAGE = "com.brick.history"
    private val URI: Uri by lazy { Uri.parse("content://com.brick.history.log") }
    const val DONT_SAVE = "Don't save"
    const val SAVE = "Save this"

    @Volatile private var app: Context? = null
    @Volatile private var installedAt = 0L
    @Volatile private var installedNow = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "brick-history").apply { isDaemon = true } }

    // The last request wasn't saved, so answers to its questions aren't either.
    @Volatile private var skippedLast = false

    /** False for requests that only work the phone (switch, play, set, open), which History has no use for. */
    fun worthSaving(req: Request): Boolean = when (req) {
        is Request.Timer, is Request.Stopwatch, is Request.TimerControl, is Request.Alarm,
        is Request.Setting, is Request.Music, is Request.Effect, is Request.EffectPreset,
        is Request.LikeSong, is Request.NowPlaying, is Request.AddToPlaylist, is Request.Visualizer,
        is Request.ReadNotifications, is Request.TellTime, is Request.Device,
        is Request.PhoneSettingReq, is Request.SettingsPageReq, is Request.ScreenTimeoutReq, is Request.AppSettingsReq,
        is Request.SmartHome, is Request.OpenApp -> false
        // Work done in the phone's own apps (texts, calls, calendar, notes, maps, YouTube, Discord): nothing History could answer faster.
        is Request.Text, is Request.Call, is Request.Reminder, is Request.CalendarEvent, is Request.ReadCalendar,
        is Request.Note, is Request.Discord, is Request.YouTube, is Request.Navigate -> false
        // "Days until Christmas" is different tomorrow.
        is Request.Countdown -> false
        // Current events change by the minute (live scores, injury reports, headlines); only past results and champions keep.
        is Request.News -> false
        is Request.Sports -> req.ask.want == SportsWant.CHAMPION || req.ask.want == SportsWant.RESULT && req.ask.year != null
        else -> true
    }

    /** What each recent request sent, so "Save this" can put it back after "Don't save". */
    private val sent = ConcurrentHashMap<String, MutableList<Bundle>>()
    private val forgotten = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /**
     * [installed], asked on every request: a yes is trusted for 20 seconds, a no is checked again each time (so
     * requests right after History is installed or updated are kept).
     */
    private fun installedCached(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (!installedNow || now - installedAt > 20_000L) { installedNow = installed(ctx); installedAt = now }
        return installedNow
    }

    fun open(context: Context) {
        val i = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** What a request is about, for grouping: the place, person, title or search words it names. */
    fun subject(req: Request?): String? = when (req) {
        null -> null
        is Request.Search -> req.query
        is Request.Sports -> (req.ask.player ?: req.ask.event ?: req.ask.teams.joinToString(" ").ifBlank { null } ?: req.ask.league?.label)?.lowercase()
        is Request.History -> req.ask.subject
        is Request.Character -> req.ask.name
        is Request.Animal -> Animals.singular(req.ask.animal)
        is Request.Spell -> req.word
        is Request.News -> req.query ?: req.topic.name.lowercase()
        is Request.Ranked -> listOfNotNull(req.kind.name.lowercase(), req.filter).joinToString(" ")
        is Request.WorkAsk -> req.title
        is Request.Deals -> listOfNotNull(req.store, req.console).joinToString(" ").ifBlank { null }
        is Request.LatestWork -> req.name
        is Request.Latest -> req.name
        is Request.MediaAsk -> listOfNotNull(req.title, req.artist).joinToString(" ").ifBlank { null }
        is Request.Weather -> req.city
        is Request.Music -> listOfNotNull(req.query, req.artist).joinToString(" ").ifBlank { null }
        is Request.Lyrics -> listOfNotNull(req.song, req.artist).joinToString(" ").ifBlank { null }
        is Request.YouTube -> req.query
        is Request.Navigate -> req.destination
        is Request.FindPlace -> listOfNotNull(req.what, req.area).joinToString(" ")
        is Request.PlaceHours -> req.place
        is Request.Text -> req.recipient
        is Request.Call -> req.who
        is Request.Reminder -> req.task
        is Request.CalendarEvent -> req.title
        is Request.TellTime -> req.place
        is Request.OpenApp -> req.name
        is Request.AppSettingsReq -> req.app
        is Request.SettingsPageReq -> req.page.title
        is Request.PhoneSettingReq -> req.setting?.name?.lowercase()?.replace('_', ' ')
        is Request.SmartHome -> req.target
        is Request.Discord -> req.who
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Saves [input] as a new request and returns what to do with each of Brick's answers to it (it may add the
     * "Don't save" button to an infographic), or null when there's nothing to save. [follow]: the input answers
     * Brick's question, so it stays with the request before it.
     */
    fun turn(input: String, req: Request?, follow: Boolean): ((LogEntry) -> LogEntry)? {
        val ctx = app ?: return null
        val text = input.trim()
        if (text.isEmpty() || !installedCached(ctx)) return null
        if (follow) { if (skippedLast) return null } else { skippedLast = req != null && !worthSaving(req); if (skippedLast) return null }
        val turn = UUID.randomUUID().toString()
        sent[turn] = ArrayList()
        // Keep the last few requests' copies only (for "Save this").
        if (sent.size > 40) sent.keys.toList().take(sent.size - 40).forEach { sent.remove(it); forgotten.remove(it) }
        send(ctx, turn, "you", text, null, req?.category?.label, Bundle().apply {
            putString("kind", if (follow) "Reply" else req?.let { it::class.java.simpleName } ?: "Request")
            req?.category?.label?.let { putString("label", it) }
            subject(req)?.let { putString("subject", it) }
            putBoolean("follow", follow)
        })
        return { e ->
            when {
                // A search a phobia filter stopped: nothing happened, so there's nothing to keep (and no "Don't save").
                Phobias.isBlocked(e.readout?.text ?: e.text) -> { if (turn !in forgotten) forget(turn); e }
                e.panel -> {
                    val r = e.readout
                    // A lyrics card keeps only its heading; the lyric lines themselves are never saved.
                    val box = (r?.text ?: e.text).trim()
                    if (box.isNotEmpty()) send(ctx, turn, "box", box, r?.image, e.category?.label)
                    e.copy(readout = withButton(r ?: Readout(e.text), turn))
                }
                e.speaker == Speaker.DAVID -> { send(ctx, turn, "brick", e.text, null, e.category?.label); e }
                e.speaker == Speaker.ROBOT -> { send(ctx, turn, "unit", e.text, null, e.category?.label); e }
                else -> e
            }
        }
    }

    /** [r] with a "Don't save" / "Save this" button, kept through its refreshes. */
    private fun withButton(r: Readout, turn: String): Readout {
        val b = Control.Button(if (turn in forgotten) SAVE else DONT_SAVE) { if (turn in forgotten) save(turn) else forget(turn) }
        return Readout(
            r.text, r.image, r.controls + b,
            refresh = { withButton(r.refresh?.invoke() ?: r, turn) },
            live = r.live, lyrics = r.lyrics,
        )
    }

    /** Takes request [turn] (and Brick's answers to it) back out of History. */
    fun forget(turn: String) {
        val ctx = app ?: return
        forgotten += turn
        io.execute { call(ctx, "forget", Bundle().apply { putString("turn", turn) }) }
    }

    /** Puts a request taken out with [forget] back in. */
    fun save(turn: String) {
        val ctx = app ?: return
        forgotten -= turn
        val copies = sent[turn]?.toList() ?: return
        io.execute { copies.forEach { call(ctx, "add", it) } }
    }

    private fun send(ctx: Context, turn: String, speaker: String, text: String, image: String?, category: String?, extra: Bundle? = null) {
        val b = Bundle().apply {
            putString("turn", turn)
            putLong("time", System.currentTimeMillis())
            putString("speaker", speaker)
            putString("text", text)
            if (image != null) putString("image", image)
            if (category != null) putString("category", category)
            if (extra != null) putAll(extra)
        }
        sent[turn]?.add(b)
        // Answers arriving after "Don't save" (a slow lookup) aren't saved either.
        if (turn in forgotten) return
        io.execute { if (turn !in forgotten) call(ctx, "add", b) }
    }

    private fun call(ctx: Context, method: String, b: Bundle) {
        try {
            ctx.contentResolver.call(URI, method, null, b)
        } catch (_: Exception) {
            // History isn't installed (or is being updated): nothing to save to.
        }
    }
}
