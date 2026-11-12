package com.brick.assistant.brain

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a night sky's brightness means for you: the Bortle class, about how many stars you can see at once,
 * and how the Milky Way looks. Works from SQM (sky brightness at the zenith, magnitudes per square arcsecond;
 * higher is darker, 22.0 is a pristine sky).
 */
object SkyQuality {
    /** The darkest SQM of each Bortle class, 2 through 9 (the standard SQM-to-Bortle table). */
    private val classTops = listOf(21.99 to 2, 21.89 to 3, 21.69 to 4, 20.49 to 5, 19.50 to 6, 18.94 to 7, 18.38 to 8, 17.80 to 9)

    /**
     * Bortle 1-9 with one decimal: within a class it rises from the class number at its dark edge to the next
     * one at its bright edge (20.0 SQM is about Bortle 5.5). Class 1 covers 21.99 and darker.
     */
    fun bortle(sqm: Double): Double {
        val b = when {
            sqm >= 21.99 -> (2 - (sqm - 21.99) / 0.1).coerceAtLeast(1.0)
            sqm <= 17.80 -> 9.0
            else -> {
                val i = classTops.indexOfLast { it.first >= sqm }
                val (top, cls) = classTops[i]
                val next = classTops[i + 1].first
                cls + (top - sqm) / (top - next)
            }
        }
        return (b.coerceIn(1.0, 9.0) * 10).roundToInt() / 10.0
    }

    /**
     * Faintest star you can see overhead (naked-eye limiting magnitude), from sky brightness. A standard
     * approximation: NELM = 7.93 - 5 log10(10^(4.316 - SQM/5) + 1).
     */
    fun limitingMagnitude(sqm: Double): Double = 7.93 - 5 * log10(10.0.pow(4.316 - sqm / 5) + 1)

    /** Stars in the whole sky brighter than each magnitude (bright-star catalog counts). */
    private val starCounts = listOf(1.0 to 22, 2.0 to 70, 3.0 to 230, 4.0 to 740, 5.0 to 2300, 5.5 to 4000, 6.0 to 6500, 6.5 to 9100, 7.0 to 15000)

    /**
     * About how many stars you can see at once on a clear moonless night: the stars brighter than your limiting
     * magnitude, half of them above the horizon, fewer near the horizon where haze dims them.
     */
    fun visibleStars(sqm: Double): Int {
        val m = limitingMagnitude(sqm)
        val total = when {
            m <= starCounts.first().first -> starCounts.first().second.toDouble() * m.coerceAtLeast(0.0)
            m >= starCounts.last().first -> starCounts.last().second.toDouble()
            else -> {
                val i = starCounts.indexOfLast { it.first <= m }
                val (m0, n0) = starCounts[i]
                val (m1, n1) = starCounts[i + 1]
                // Counts grow geometrically with magnitude: interpolate in log space.
                10.0.pow(log10(n0.toDouble()) + (m - m0) / (m1 - m0) * (log10(n1.toDouble()) - log10(n0.toDouble())))
            }
        }
        val atOnce = total / 2 * 0.8
        return roundNicely(atOnce)
    }

    private fun roundNicely(n: Double): Int = when {
        n >= 1000 -> (n / 100).roundToInt() * 100
        n >= 100 -> (n / 50).roundToInt() * 50
        n >= 20 -> (n / 10).roundToInt() * 10
        else -> n.roundToInt()
    }

    /** How the Milky Way looks under a sky of this Bortle class. */
    fun milkyWay(bortle: Double): String = when {
        bortle < 2.5 -> "The Milky Way is spectacular, detailed enough to cast faint shadows, with dark dust lanes you can trace by eye."
        bortle < 3.5 -> "The Milky Way is clearly visible, with structure and dust lanes overhead."
        bortle < 4.5 -> "The Milky Way is visible but washed out near the horizon; the core still shows on summer nights."
        bortle < 5.5 -> "The Milky Way is faint, only a hint of it overhead on the best nights."
        bortle < 6.5 -> "The Milky Way is barely there, if at all, even straight up."
        else -> "The Milky Way isn't visible here."
    }

    /** "rural sky", "bright suburban sky"... */
    fun label(bortle: Double): String = when {
        bortle < 1.5 -> "an excellent dark sky"
        bortle < 2.5 -> "a truly dark sky"
        bortle < 3.5 -> "a rural sky"
        bortle < 4.5 -> "a rural to suburban transition sky"
        bortle < 5.5 -> "a suburban sky"
        bortle < 6.5 -> "a bright suburban sky"
        bortle < 7.5 -> "a suburban to city transition sky"
        bortle < 8.5 -> "a city sky"
        else -> "an inner-city sky"
    }

    /** "Bortle 4.2, a rural to suburban transition sky. About 1,500 stars ... The Milky Way ..." */
    fun describe(sqm: Double): String {
        val b = bortle(sqm)
        return "Bortle %.1f, %s, with a sky brightness of %.2f. On a clear, moonless night you can see about %,d stars at once. %s"
            .format(Locale.US, b, label(b), sqm, visibleStars(sqm), milkyWay(b))
    }

    /**
     * Only what you asked about, for a sky at [where] ("your location", "Moab"): the Bortle class, the star
     * count, the Milky Way, or all three for a night sky report.
     */
    /**
     * The short spoken answer, only what was asked: "Bortle 6.0, bright suburban.", "About 1,100 stars.",
     * "Milky Way barely visible." A report says all three. [place] ("Moab") goes first when it isn't here.
     */
    fun answer(aspect: SkyAspect, sqm: Double, place: String? = null): String {
        val b = bortle(sqm)
        val bortleLine = "Bortle %.1f, %s.".format(Locale.US, b, spokenLabel(b))
        val starsLine = "About %,d stars.".format(Locale.US, visibleStars(sqm))
        val milkyLine = "Milky Way ${milkySpoken(milkyWayStars(b))}."
        val body = when (aspect) {
            SkyAspect.BORTLE -> bortleLine
            SkyAspect.STARS -> starsLine
            SkyAspect.MILKY_WAY -> milkyLine
            SkyAspect.REPORT -> "$bortleLine $starsLine $milkyLine"
        }
        return place?.let { "$it: $body" } ?: body
    }

    /** "bright suburban", "rural": the Bortle class in a couple of words. */
    fun spokenLabel(b: Double) = when {
        b < 1.5 -> "pristine"
        b < 2.5 -> "truly dark"
        b < 3.5 -> "rural"
        b < 4.5 -> "rural to suburban"
        b < 5.5 -> "suburban"
        b < 6.5 -> "bright suburban"
        b < 7.5 -> "suburban to city"
        b < 8.5 -> "city"
        else -> "inner city"
    }

    private fun milkySpoken(stars: Int) =
        listOf("not visible", "barely visible", "faint", "visible but washed out", "clearly visible", "spectacular")[stars]

    /** 0 (not visible) to 5 (spectacular). */
    fun milkyWayStars(bortle: Double): Int = when {
        bortle < 2.5 -> 5
        bortle < 3.5 -> 4
        bortle < 4.5 -> 3
        bortle < 5.5 -> 2
        bortle < 6.5 -> 1
        else -> 0
    }

    private fun shortLabel(b: Double) = when {
        b < 1.5 -> "pristine"
        b < 2.5 -> "truly dark"
        b < 3.5 -> "rural"
        b < 4.5 -> "rural/suburb"
        b < 5.5 -> "suburban"
        b < 6.5 -> "bright suburb"
        b < 7.5 -> "suburb/city"
        b < 8.5 -> "city"
        else -> "inner city"
    }

    private fun milkyShort(stars: Int) = listOf("not visible", "barely", "faint", "washed out", "structured", "spectacular")[stars]

    private fun bar(filled: Int, size: Int) = "[" + "#".repeat(filled.coerceIn(0, size)) + ".".repeat(size - filled.coerceIn(0, size)) + "]"

    /** The narrowest [panel]; it widens to the card with [Box.W]. */
    const val PANEL_WIDTH = 32

    /**
     * The terminal readout shown under a sky answer (not spoken). ASCII only, so it lines up in any monospace
     * font. Shows only the rows for what you asked, all of them for a report.
     *
     *   +-[ NIGHT SKY ]--------------------+
     *   | your location                    |
     *   | BORTLE 4.2 [####.....] rural/sub |
     *   ...
     */
    fun panel(aspect: SkyAspect, sqm: Double, where: String, title: String = "NIGHT SKY", note: String? = null): String {
        val b = bortle(sqm)
        val w = Box.W
        fun row(s: String) = "| " + s.take(w).padEnd(w) + " |"
        val out = mutableListOf<String>()
        out += ("+-[ $title ]").padEnd(w + 3, '-') + "+"
        out += row(where)
        note?.let { out += row(it) }
        out += row("")
        if (aspect == SkyAspect.BORTLE || aspect == SkyAspect.REPORT) {
            out += row("BORTLE %.1f %s".format(Locale.US, b, bar(b.roundToInt(), 9)))
            out += row("       %s".format(shortLabel(b)))
            out += row("SQM    %.2f mag/arcsec2".format(Locale.US, sqm))
        }
        if (aspect == SkyAspect.STARS || aspect == SkyAspect.REPORT) {
            val n = visibleStars(sqm)
            out += row("STARS  %s ~%,d".format(Locale.US, bar((n / 300.0).roundToInt(), 10), n))
            out += row("NELM   %.1f mag".format(Locale.US, limitingMagnitude(sqm)))
        }
        if (aspect == SkyAspect.MILKY_WAY || aspect == SkyAspect.REPORT) {
            val m = milkyWayStars(b)
            out += row("MILKY  %s %s".format(bar(m, 5), milkyShort(m)))
        }
        out += "+" + "-".repeat(w + 2) + "+"
        return out.joinToString("\n")
    }
}

/** What a sky question is about: just the Bortle class, just the stars, just the Milky Way, or all three. */
enum class SkyAspect { BORTLE, STARS, MILKY_WAY, REPORT }
