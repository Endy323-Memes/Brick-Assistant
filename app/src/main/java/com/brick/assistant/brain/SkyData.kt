package com.brick.assistant.brain

import android.content.Context
import java.io.DataInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Links into lightpollutionmap.app: its coordinate deep links center the map and open that spot's details. */
object SkyMap {
    fun link(lat: Double, lon: Double, zoom: Int): String =
        String.format(Locale.US, "https://lightpollutionmap.app/?lat=%.4f&lng=%.4f&zoom=%d", lat, lon, zoom)

    /** A zoom that fits about [miles] around the center on a phone screen (60 mi is about zoom 8). */
    fun zoomForRadius(miles: Int): Int = (ln(24000.0 / miles.coerceAtLeast(1)) / ln(2.0) - 0.6).roundToInt().coerceIn(3, 12)

    /** The point [miles] from (lat, lon) along [bearing] degrees (0 = north), on a sphere. Returns (lat, lon). */
    fun offset(lat: Double, lon: Double, miles: Double, bearing: Double): Pair<Double, Double> {
        val d = miles / 3958.8
        val b = bearing * PI / 180
        val p1 = lat * PI / 180
        val l1 = lon * PI / 180
        val p2 = asin((sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b)).coerceIn(-1.0, 1.0))
        val l2 = l1 + kotlin.math.atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        val lonDeg = ((l2 * 180 / PI + 540) % 360) - 180
        return p2 * 180 / PI to lonDeg
    }
}

/** A dark-sky spot found by [SkyGrid.darkest]. */
class DarkSpot(val lat: Double, val lon: Double, val sqm: Double, val miles: Int, val name: String? = null)

/**
 * Night-sky brightness for the whole world, from the World Atlas of Artificial Night Sky Brightness (Falchi et
 * al. 2016, CC BY-NC 4.0), shrunk to one byte per cell (assets/sky/sky_grid.bin.gz, built by
 * tools/build-sky-grid). Each byte is the cell's zenith brightness: 0 = a pristine 22.0 mag/arcsec² sky, each
 * step 0.025 brighter. 255 marks sea (no observing site there).
 *
 * File: "BSKY", version, width, height (Int), north edge lat, west edge lon, cell size in degrees (Double), then
 * width x height bytes, row by row from the north.
 */
class SkyGrid(
    val width: Int,
    val height: Int,
    val north: Double,
    val west: Double,
    val cell: Double,
    private val cells: ByteArray,
) {
    val south: Double get() = north - height * cell

    private fun code(row: Int, col: Int): Int = cells[row * width + col].toInt() and 0xFF

    private fun sqmOf(code: Int): Double? = if (code == SEA) null else 22.0 - code * STEP

    private fun rowOf(lat: Double) = floor((north - lat) / cell).toInt()
    private fun colOf(lon: Double) = Math.floorMod(floor((lon - west) / cell).toInt(), width)
    private fun latOf(row: Int) = north - (row + 0.5) * cell
    private fun lonOf(col: Int) = west + (col + 0.5) * cell

    /** Sky brightness (SQM) at a point; null at sea or outside the atlas (beyond about 75°N / 65°S). */
    fun sqmAt(lat: Double, lon: Double): Double? {
        val r = rowOf(lat)
        if (r < 0 || r >= height) return null
        return sqmOf(code(r, colOf(lon)))
    }

    /** True when (lat, lon) is inside the atlas (about 85°N to 60°S). */
    fun covers(lat: Double): Boolean = rowOf(lat) in 0 until height

    /**
     * Sky brightness on the nearest land within [maxMiles] of (lat, lon), for a point that lands on a sea cell (a
     * harbor, an island the grid's cells are too coarse for). Null when there's no land that close.
     */
    fun sqmNearLand(lat: Double, lon: Double, maxMiles: Int = 30): Double? {
        sqmAt(lat, lon)?.let { return it }
        if (!covers(lat)) return null
        val r0 = rowOf(lat)
        val c0 = colOf(lon)
        val reach = (maxMiles / 69.0 / cell).toInt() + 1
        var best: Double? = null
        var bestD = Double.MAX_VALUE
        for (dr in -reach..reach) for (dc in -reach * 3..reach * 3) {
            val r = r0 + dr
            if (r < 0 || r >= height) continue
            val c = Math.floorMod(c0 + dc, width)
            val s = sqmOf(code(r, c)) ?: continue
            val d = miles(lat, lon, latOf(r), lonOf(c))
            if (d <= maxMiles && d < bestD) { bestD = d; best = s }
        }
        return best
    }

    /**
     * The darkest land within [radiusMiles] of (lat, lon), or anywhere when null. Ties (many cells are equally
     * pristine) go to the nearest one, so you get the closest of the best skies.
     */
    fun darkest(lat: Double, lon: Double, radiusMiles: Int?): DarkSpot? {
        var bestCode = Int.MAX_VALUE
        var bestDist = Double.MAX_VALUE
        var bestRow = -1
        var bestCol = -1
        fun consider(row: Int, col: Int) {
            val c = code(row, col)
            if (c == SEA || c > bestCode) return
            val d = miles(lat, lon, latOf(row), lonOf(col))
            if (radiusMiles != null && d > radiusMiles) return
            if (c < bestCode || d < bestDist) { bestCode = c; bestDist = d; bestRow = row; bestCol = col }
        }
        if (radiusMiles == null) {
            for (row in 0 until height) for (col in 0 until width) consider(row, col)
        } else {
            val dLat = radiusMiles / 69.0
            val r0 = rowOf(lat + dLat).coerceIn(0, height - 1)
            val r1 = rowOf(lat - dLat).coerceIn(0, height - 1)
            val cosLat = cos(lat * PI / 180).coerceAtLeast(0.05)
            val dLon = (radiusMiles / (69.0 * cosLat)).coerceAtMost(180.0)
            val span = (dLon / cell).toInt() + 1
            val c0 = colOf(lon)
            for (row in r0..r1) for (k in -span..span) consider(row, Math.floorMod(c0 + k, width))
        }
        if (bestRow < 0) return null
        return DarkSpot(latOf(bestRow), lonOf(bestCol), sqmOf(bestCode)!!, bestDist.roundToInt())
    }

    companion object {
        const val SEA = 255
        const val STEP = 0.025

        fun read(input: java.io.InputStream): SkyGrid {
            // Gzipped or plain (the APK packager un-gzips .gz assets and drops the extension).
            val buf = input.buffered(1 shl 16)
            buf.mark(2)
            val gz = buf.read() == 0x1f && buf.read() == 0x8b
            buf.reset()
            val d = DataInputStream(if (gz) GZIPInputStream(buf, 1 shl 16) else buf)
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "BSKY") { "not a sky grid" }
            require(d.readInt() == 1) { "unknown sky grid version" }
            val w = d.readInt()
            val h = d.readInt()
            val north = d.readDouble()
            val west = d.readDouble()
            val cell = d.readDouble()
            val cells = ByteArray(w * h).also { d.readFully(it) }
            return SkyGrid(w, h, north, west, cell, cells)
        }

        /** Great-circle distance in miles. */
        fun miles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1 * PI / 180
            val p2 = lat2 * PI / 180
            val dp = p2 - p1
            val dl = (lon2 - lon1) * PI / 180
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 3958.8 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

/** Brick's world sky-brightness grid, loaded from the APK the first time it's needed (about 20 MB in memory). */
object SkyData {
    @Volatile private var grid: SkyGrid? = null
    @Volatile private var missing = false

    private fun grid(context: Context): SkyGrid? {
        grid?.let { return it }
        if (missing) return null
        return synchronized(this) {
            grid ?: try {
                val name = context.assets.list("sky")?.firstOrNull { it.startsWith("sky_grid.bin") } ?: error("no sky grid")
                context.assets.open("sky/$name").use { SkyGrid.read(it) }.also { grid = it }
            } catch (e: Exception) {
                missing = true
                null
            }
        }
    }

    /** Modeled zenith sky brightness (SQM) at a point (or the nearest land), or null without data. */
    fun sqmAt(context: Context, lat: Double, lon: Double): Double? = grid(context)?.sqmNearLand(lat, lon)

    /** Whether Brick has a sky grid at all, and whether it reaches this latitude. */
    fun hasGrid(context: Context): Boolean = grid(context) != null
    fun covers(context: Context, lat: Double): Boolean = grid(context)?.covers(lat) == true

    /** The darkest land within [radiusMiles] (null = anywhere), or null without data. */
    fun darkest(context: Context, lat: Double, lon: Double, radiusMiles: Int?): DarkSpot? = grid(context)?.darkest(lat, lon, radiusMiles)
}
