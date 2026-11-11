package com.brick.assistant.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * WSOLA time stretching: changes speaking speed without changing pitch. Each 30 ms frame is taken
 * from near its nominal position, shifted by up to 8 ms so its waveform lines up with the previous
 * frame, then overlap-added. [speed] > 1 is faster.
 */
object Wsola {
    fun speed(x: FloatArray, sr: Int, speed: Float): FloatArray {
        if (abs(speed - 1f) < 0.02f || x.size < sr / 10) return x
        val n = (sr * 0.03f).toInt().let { it + (it and 1) }
        val hs = n / 2
        val ha = hs * speed
        val tol = (sr * 0.008f).toInt()
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val outLen = (x.size / speed).roundToInt()
        val out = FloatArray(outLen + n)
        val wsum = FloatArray(outLen + n)
        var prev = -1
        var k = 0
        while (true) {
            val so = k * hs
            if (so + n > out.size) break
            val nominal = (k * ha).roundToInt()
            if (nominal + n >= x.size) break
            var best = nominal
            if (prev >= 0) {
                // The previous frame's natural continuation is what this frame should resemble.
                val target = prev + hs
                var bestC = Double.NEGATIVE_INFINITY
                for (dlt in -tol..tol) {
                    val c = nominal + dlt
                    if (c < 0 || c + n >= x.size || target + hs >= x.size) continue
                    var s = 0.0
                    var i = 0
                    while (i < hs) { s += x[target + i] * x[c + i]; i += 2 }
                    if (s > bestC) { bestC = s; best = c }
                }
            }
            for (i in 0 until n) {
                out[so + i] += x[best + i] * win[i]
                wsum[so + i] += win[i]
            }
            prev = best
            k++
        }
        return FloatArray(outLen) { i -> out[i] / max(wsum[i], 0.05f) }
    }
}
