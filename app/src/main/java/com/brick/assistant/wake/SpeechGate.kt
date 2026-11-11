package com.brick.assistant.wake

import kotlin.math.sqrt

/**
 * Saves battery for "Hey Brick": decides which 100 ms chunks of audio are worth running the speech model on.
 * Quiet chunks are skipped (that's most of the day); when sound starts, the last [preRoll] chunks are decoded
 * first so the start of "Hey Brick" isn't lost, and decoding continues for [hangover] chunks after the sound
 * stops so the model can finish the phrase. It follows the room's noise floor, so steady background noise
 * doesn't count as sound.
 */
class SpeechGate(private val preRoll: Int = 4, private val hangover: Int = 8) {
    class Out(val chunks: List<Pair<ShortArray, Int>>, val ended: Boolean)

    private var noise = 150.0
    private val pre = ArrayDeque<ShortArray>()
    private var hold = 0

    val active: Boolean get() = hold > 0

    fun rms(x: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) { val v = x[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    /** Offers one chunk; returns what to decode now and whether a stretch of sound just ended. */
    fun offer(chunk: ShortArray, n: Int): Out {
        val level = rms(chunk, n)
        val loud = level > maxOf(noise * 2.2, MIN_LEVEL)
        if (loud) {
            val feed = ArrayList<Pair<ShortArray, Int>>(pre.size + 1)
            if (hold == 0) pre.forEach { feed += it to it.size }
            pre.clear()
            feed += chunk to n
            hold = hangover
            return Out(feed, ended = false)
        }
        if (hold > 0) {
            hold--
            return Out(listOf(chunk to n), ended = hold == 0)
        }
        // Quiet: learn the noise floor and remember the chunk as lead-in.
        noise = noise * 0.97 + level * 0.03
        pre.addLast(chunk.copyOf(n))
        while (pre.size > preRoll) pre.removeFirst()
        return Out(emptyList(), ended = false)
    }

    fun reset() { pre.clear(); hold = 0 }

    private companion object {
        /** About -45 dBFS: softer than any speech aimed at the phone. */
        const val MIN_LEVEL = 180.0
    }
}
