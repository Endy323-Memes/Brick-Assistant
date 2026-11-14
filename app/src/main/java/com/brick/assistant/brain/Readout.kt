package com.brick.assistant.brain

import java.util.Locale

/**
 * The terminal infographic shown with an answer, in its own card above the Brick card and in the app's log:
 * an ASCII box ([text], never spoken), an optional picture ([image], a URL, e.g. a search result's), and live
 * [controls] for settings answers (switches, sliders and buttons that act right away).
 */
class Readout(
    val text: String,
    val image: String? = null,
    val controls: List<Control> = emptyList(),
    /** Builds the readout again with the current state: called after a control is used, and every second when [live]. */
    val refresh: (() -> Readout)? = null,
    /** Keeps changing on its own (a running stopwatch, a playing song): redrawn every second while shown. */
    val live: Boolean = false,
    /** A lyric view under the box: plain, line-synced or word-synced, following the song as it plays. */
    val lyrics: LyricsPanel? = null,
)

/**
 * How a run of [n] buttons splits into rows of at most three, as evenly as it can (4 is 2+2, 5 is 3+2, 7 is 3+2+2),
 * so every row fills the card and no button sits alone at a third of the width.
 */
fun buttonRows(n: Int): List<Int> {
    if (n <= 0) return emptyList()
    val rows = (n + 2) / 3
    return List(rows) { i -> n / rows + if (i < n % rows) 1 else 0 }
}

/** A live control on a readout. Each reads its current value when drawn, so it shows the real state. */
sealed class Control(val label: String) {
    class Toggle(label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Control(label)
    class Slider(
        label: String,
        val get: () -> Float,
        val range: ClosedFloatingPointRange<Float>,
        val steps: Int = 0,
        val format: (Float) -> String = { "${it.toInt()}" },
        val set: (Float) -> Unit,
    ) : Control(label)
    /** Opens something (a settings screen, an app); [run] is called on the main thread. */
    class Button(label: String, val run: () -> Unit) : Control(label)
}

/**
 * Builds the ASCII boxes, same look as the night sky panel:
 *
 *   +-[ WEATHER ]----------------------+
 *   | Chicago, Illinois                |
 *   |                                  |
 *   | NOW    47F  partly cloudy        |
 *   +----------------------------------+
 *
 * Row labels are short capitals (drawn in the unit's color), bars are "[###...]" (filled in Brick's color).
 */
object Box {
    private val MARKS = Patterns.get("\\p{M}+")

    /** Inner width in characters: as many as fit the card at the readout's text size (see [fit]); 32 until measured. */
    @Volatile var W = SkyQuality.PANEL_WIDTH
        private set

    /** Sets the width to [columns], kept between 32 and 64 so the rows stay readable on a tablet. */
    fun fit(columns: Int) { W = columns.coerceIn(SkyQuality.PANEL_WIDTH, 64) }

    /** A box titled [title] around [rows] (each cut to the width; use [wrap] for long text). */
    fun build(title: String, rows: List<String>): String {
        val out = ArrayList<String>()
        out += ("+-[ $title ]").take(W + 3).padEnd(W + 3, '-') + "+"
        for (r in rows) out += "| " + ascii(r).take(W).padEnd(W) + " |"
        out += "+" + "-".repeat(W + 2) + "+"
        return out.joinToString("\n")
    }

    /** "LABEL  value": the label padded to a 7-character column. */
    fun kv(label: String, value: String) = label.uppercase(Locale.US).let { if (it.length >= 7) "$it " else it.padEnd(7) } + value

    /** [text] broken into rows of the box's width, the rows after the first indented by [indent]. */
    fun wrap(text: String, first: String = "", indent: Int = first.length, max: Int = 8): List<String> {
        val out = ArrayList<String>()
        val pad = " ".repeat(indent)
        var line = first
        var fresh = true // only the prefix so far
        for (raw in ascii(text).split(' ')) {
            if (raw.isEmpty()) continue
            val w = if (raw.length > W - indent) raw.take(W - indent - 1) + "~" else raw
            when {
                fresh -> { line += w; fresh = false }
                line.length + 1 + w.length <= W -> line += " $w"
                else -> { out += line; line = pad + w }
            }
        }
        if (!fresh) out += line
        if (out.size > max) {
            out.subList(max, out.size).clear()
            out[max - 1] = out[max - 1].take(W - 3).trimEnd() + "..."
        }
        return out
    }

    fun bar(filled: Int, size: Int) = "[" + "#".repeat(filled.coerceIn(0, size)) + ".".repeat(size - filled.coerceIn(0, size)) + "]"

    /** A 0..100 value as a 10-wide bar plus the number: "[####......] 40%". */
    fun percent(p: Int) = bar((p + 5) / 10, 10) + " $p%"

    /** Plain ASCII so the box lines up in any monospace font (accents and symbols dropped, dashes kept). */
    fun ascii(s: String): String {
        // Most rows are already plain ASCII: skip the normalizing then.
        if (s.all { it.code in 32..126 }) return s
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(MARKS, "")
        return n.map { c ->
            when {
                c.code in 32..126 -> c
                c == '‘' || c == '’' -> '\''
                c == '“' || c == '”' -> '"'
                c == '–' || c == '—' || c == '−' -> '-'
                c == '×' -> 'x'
                c == '÷' -> '/'
                c == '°' -> 'o'
                c == '…' -> '.'
                else -> ' '
            }
        }.joinToString("")
    }
}

/**
 * Lyrics shown live in the readout card. [position] is the song's playback position in milliseconds now (null
 * when unknown), [playing] whether it's moving, and [seek] jumps the song to a line when you tap it.
 */
class LyricsPanel(
    val doc: LyricsDoc,
    val position: () -> Long?,
    val playing: () -> Boolean,
    val seek: ((Long) -> Unit)? = null,
    /** Play/pause for the full-screen view (null for a named song that isn't playing). */
    val toggle: (() -> Unit)? = null,
    /** Whether these lyrics' song is the one playing now; full screen is only for that song. */
    val sameSong: () -> Boolean = { true },
    /** The song's length in milliseconds, when the player says. */
    val duration: () -> Long? = { null },
    /** The app playing it ("Driver", "Spotify"), shown in the full-screen view. */
    val app: String? = null,
)

/** The lyrics on Brick's card right now, so the full-screen view can pick up the same song and controls. */
object LyricsHolder {
    @Volatile var current: LyricsPanel? = null
}
