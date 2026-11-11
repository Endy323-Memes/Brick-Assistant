package com.brick.assistant.voice

import com.brick.assistant.data.AppSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns any base voice into the "robot unit" voice.
 *
 * The tuning comes from measuring 85 tutorial-bot reference clips (about 5 minutes of audio).
 * Those clips are a clean, expressive voice: no ring modulation, frequency shifting, chorus, comb
 * or reverb. What sets them apart is (1) a median pitch of about 176 Hz with natural intonation and
 * (2) a distinctive long-term spectrum: a heavy 150-400 Hz body, a steep fall through 1-5 kHz and a
 * bright 5-10 kHz sheen. So the chain is:
 *
 *   1. PSOLA pitch shift: moves the voice's median pitch to the target and keeps its intonation
 *      and formants.
 *   2. Matching EQ: measures each utterance's long-term spectrum and reshapes it toward the
 *      reference curve.
 *   3. Optional extras (off by default): ring modulation, metallic comb, small-speaker filter,
 *      bit crush.
 *
 * Only the summary numbers below come from the reference clips. No audio is stored.
 */
object RobotFx {
    const val REFERENCE_F0 = 176f

    /**
     * The robot unit is an original female voice: a female base voice at its own pitch (215 Hz median,
     * slightly lively intonation) shaped by the reference tone curve, which gives it the synthetic
     * "tutorial TTS" feel without copying anyone's voice.
     */
    const val UNIT_F0 = 215f

    /** Band edges (Hz) and reference long-term spectrum (dB relative to the loudest band). */
    private val BAND_EDGES = floatArrayOf(
        50f, 100f, 150f, 200f, 300f, 400f, 600f, 800f, 1000f, 1500f, 2000f, 2500f,
        3000f, 3500f, 4000f, 5000f, 6000f, 7000f, 8000f, 10000f, 12000f, 16000f, 20000f,
    )
    private val REFERENCE_DB = floatArrayOf(
        -30f, -12f, -1f, 0f, -2f, -4f, -8f, -11f, -15f, -19f, -22f,
        -24f, -26f, -29f, -29f, -27f, -27f, -25f, -27f, -32f, -37f, -67f,
    )

    /** Microsoft David Desktop, measured the same way from 10 reference sentences (46 s). */
    const val DAVID_F0 = 96f
    private val DAVID_DB = floatArrayOf(
        -6f, -1f, -3f, 0f, 0f, -1f, -3f, -8f, -8f, -13f, -16f,
        -21f, -21f, -24f, -17f, -22f, -28f, -35f, -47f, -76f, -110f, -105f,
    )

    /** Makes any voice resemble David: his pitch (natural intonation) and his long-term tone curve. */
    fun davidMatch(input: PcmAudio): PcmAudio {
        if (input.samples.size < input.sampleRate / 20) return input
        val x = matchVoice(input.samples.copyOf(), input.sampleRate, DAVID_F0, 1f, 1f, DAVID_DB)
        normalize(x, 0.9f)
        return PcmAudio(x, input.sampleRate)
    }

    /**
     * A natural voice with calmer, more even intonation (the classic assistant delivery): pitch movement
     * around the median is scaled by [expression] (1 = untouched, 0 = monotone). No effects or EQ. A
     * [question] keeps its own final rise.
     */
    fun calm(input: PcmAudio, expression: Float, question: Boolean): PcmAudio {
        val sr = input.sampleRate
        val x = input.samples
        if (x.size < sr / 10 || expression >= 0.98f) return input
        val k = expression.coerceIn(0f, 1f).toDouble()
        val keep = if (question) minOf((sr * 0.55f).toInt(), x.size / 3) else 0
        val head = x.copyOfRange(0, x.size - keep)
        val track = PitchTracker.track(head, sr)
        val median = track.median() ?: return input
        val y = Psola.shift(head, sr, track) { f0 ->
            val wanted = median * (f0 / median).toDouble().pow(k).toFloat()
            (wanted / f0).coerceIn(0.6f, 1.6f)
        }
        val out = FloatArray(x.size)
        val n = minOf(y.size, head.size)
        for (i in 0 until n) out[i] = y[i]
        if (keep > 0) {
            // Blend back into the original just before the kept question ending.
            val fade = minOf(sr * 30 / 1000, n)
            for (j in 0 until fade) {
                val i = n - fade + j
                val t = (j + 1f) / fade
                out[i] = y[i] * (1 - t) + head[i] * t
            }
            for (i in head.size until x.size) out[i] = x[i]
        }
        return PcmAudio(out, sr)
    }

    /** Cuts natural speech off just before its end, mid-word (responses ending in "-"). */
    fun cutEnd(input: PcmAudio): PcmAudio {
        val sr = input.sampleRate
        val x = input.samples
        var end = x.size - 1
        while (end > 0 && kotlin.math.abs(x[end]) < 0.02f) end--
        val cut = (end - sr * 18 / 100).coerceAtLeast(sr / 10).coerceAtMost(x.size)
        val out = x.copyOf(cut)
        val fade = minOf(sr * 8 / 1000, out.size)
        for (j in 0 until fade) out[out.size - fade + j] *= 1f - (j + 1f) / fade
        return PcmAudio(out, sr)
    }

    /** PSOLA pitch move (median -> [targetF0], intonation scaled by [expressiveness]) then matching EQ. */
    private fun matchVoice(
        input: FloatArray, sr: Int, targetF0: Float, expressiveness: Float, eqStrength: Float, refDb: FloatArray,
    ): FloatArray {
        var x = input
        val track = PitchTracker.track(x, sr)
        val median = track.median()
        if (median != null) {
            val k = expressiveness.coerceIn(0.3f, 3f)
            x = Psola.shift(x, sr, track) { f0 ->
                val wanted = targetF0 * (f0 / median).toDouble().pow(k.toDouble()).toFloat()
                (wanted.coerceIn(60f, 450f) / f0).coerceIn(0.4f, 3f)
            }
        }
        if (eqStrength > 0.01f) x = matchEq(x, sr, eqStrength, refDb)
        return x
    }

    fun process(input: PcmAudio, s: AppSettings): PcmAudio {
        val sr = input.sampleRate
        if (input.samples.size < sr / 20) return input

        // 1-2. Pitch and tone match toward the tutorial-bot reference.
        var x = matchVoice(
            input.samples.copyOf(), sr, s.robotTargetF0, s.robotExpressiveness, s.robotEqStrength, REFERENCE_DB,
        )

        // 3. Optional extras
        if (s.ringMix > 0.001f) {
            val mix = s.ringMix.coerceIn(0f, 1f)
            val w = 2.0 * PI * s.ringFreq / sr
            for (i in x.indices) x[i] *= (1f - mix) + mix * sin(w * i).toFloat()
        }
        if (s.combFeedback > 0.001f) {
            val d = (s.combDelayMs * sr / 1000f).roundToInt().coerceAtLeast(1)
            val fb = s.combFeedback.coerceIn(0f, 0.92f)
            val y = FloatArray(x.size + min(d * 20, sr / 4))
            for (i in y.indices) y[i] = (if (i < x.size) x[i] else 0f) + if (i >= d) fb * y[i - d] else 0f
            x = y
        }
        if (s.speakerFilter) x = lowPass(highPass(x, 300f, sr), 4500f, sr)

        normalize(x, 0.9f)
        val bits = s.bitDepth.coerceIn(2, 16)
        if (bits < 16) {
            val levels = 2f.pow(bits - 1)
            for (i in x.indices) x[i] = (x[i] * levels).roundToInt() / levels
        }
        return PcmAudio(x, sr)
    }

    // ------------------------------------------------------------------ matching EQ

    private fun matchEq(x: FloatArray, sr: Int, strength: Float, refDb: FloatArray): FloatArray {
        val n = 1024
        val hop = n / 4
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val half = n / 2
        val binHz = sr.toFloat() / n

        // Pass 1: long-term spectrum of the louder frames.
        var peakRms = 0f
        val frameCount = max(0, (x.size - n) / hop + 1)
        val rms = FloatArray(frameCount) { f ->
            var e = 0.0
            for (k in 0 until n) { val v = x[f * hop + k]; e += v * v }
            sqrt(e / n).toFloat().also { if (it > peakRms) peakRms = it }
        }
        if (frameCount == 0 || peakRms <= 0f) return x
        val ltas = DoubleArray(half)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (f in 0 until frameCount) {
            if (rms[f] < peakRms * 0.1f) continue
            for (k in 0 until n) { re[k] = (x[f * hop + k] * win[k]).toDouble(); im[k] = 0.0 }
            Fft.transform(re, im)
            for (k in 0 until half) ltas[k] += re[k] * re[k] + im[k] * im[k]
        }

        // Band gains: reference shape minus input shape.
        val nyq = sr / 2f
        val bandCount = refDb.size
        val inDb = FloatArray(bandCount) { Float.NaN }
        for (b in 0 until bandCount) {
            val lo = (BAND_EDGES[b] / binHz).toInt()
            val hi = min(half, (BAND_EDGES[b + 1] / binHz).toInt())
            if (BAND_EDGES[b + 1] > nyq * 0.98f || hi <= lo) continue
            var sum = 0.0
            for (k in lo until hi) sum += ltas[k]
            inDb[b] = (10 * log10(sum / (hi - lo) + 1e-20)).toFloat()
        }
        val valid = (0 until bandCount).filter { !inDb[it].isNaN() }
        if (valid.size < 4) return x
        val inMax = valid.maxOf { inDb[it] }
        val refMax = valid.maxOf { refDb[it] }
        val gainDb = FloatArray(bandCount)
        for (b in valid) {
            gainDb[b] = (((refDb[b] - refMax) - (inDb[b] - inMax)) * strength).coerceIn(-24f, 20f)
        }
        val centers = valid.map { sqrt(BAND_EDGES[it] * BAND_EDGES[it + 1]) }
        val gains = valid.map { gainDb[it] }
        val binGain = FloatArray(half + 1) { k ->
            val f = max(k * binHz, 1f)
            val db = when {
                f <= centers.first() -> gains.first()
                f >= centers.last() -> gains.last()
                else -> {
                    var i = 0
                    while (centers[i + 1] < f) i++
                    val t = (ln(f / centers[i]) / ln(centers[i + 1] / centers[i]))
                    gains[i] + (gains[i + 1] - gains[i]) * t
                }
            }
            10f.pow(db / 20f)
        }

        // Pass 2: filter with STFT and overlap-add. Hann^2 at 75% overlap sums to 1.5.
        val out = FloatArray(x.size)
        val padded = x.copyOf(x.size + n)
        var pos = 0
        while (pos < x.size) {
            for (k in 0 until n) { re[k] = (padded[pos + k] * win[k]).toDouble(); im[k] = 0.0 }
            Fft.transform(re, im)
            for (k in 0..half) {
                val g = binGain[k]
                re[k] *= g; im[k] *= g
                if (k in 1 until half) { re[n - k] *= g; im[n - k] *= g }
            }
            Fft.inverse(re, im)
            for (k in 0 until n) {
                val i = pos + k
                if (i < out.size) out[i] += (re[k] * win[k] / 1.5).toFloat()
            }
            pos += hop
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun normalize(x: FloatArray, target: Float) {
        var peak = 0f
        for (v in x) if (abs(v) > peak) peak = abs(v)
        if (peak > 1e-6f) {
            val g = target / peak
            for (i in x.indices) x[i] *= g
        }
    }

    private fun lowPass(x: FloatArray, cutoff: Float, sr: Int): FloatArray {
        val a = (1.0 - exp(-2.0 * PI * cutoff / sr)).toFloat()
        val out = FloatArray(x.size)
        var prev = 0f
        for (i in x.indices) { prev += a * (x[i] - prev); out[i] = prev }
        return out
    }

    private fun highPass(x: FloatArray, cutoff: Float, sr: Int): FloatArray {
        val rc = 1f / (2f * PI.toFloat() * cutoff)
        val dt = 1f / sr
        val alpha = rc / (rc + dt)
        val out = FloatArray(x.size)
        var prevIn = 0f
        var prevOut = 0f
        for (i in x.indices) {
            prevOut = alpha * (prevOut + x[i] - prevIn)
            prevIn = x[i]
            out[i] = prevOut
        }
        return out
    }
}

/** Per-hop pitch (Hz, 0 = unvoiced) from YIN on a decimated copy of the signal. */
class PitchTrack(val f0: FloatArray, val hop: Int) {
    fun at(sample: Int): Float = f0[(sample / hop).coerceIn(0, f0.size - 1)]
    fun median(): Float? {
        val v = f0.filter { it > 0f }.sorted()
        return if (v.size < 5) null else v[v.size / 2]
    }
}

object PitchTracker {
    fun track(x: FloatArray, sr: Int): PitchTrack {
        val d = max(1, sr / 8000)
        val srd = sr.toFloat() / d
        val xd = FloatArray(x.size / d) { i -> var s = 0f; for (k in 0 until d) s += x[i * d + k]; s / d }
        val hop = (sr * 0.005f).toInt().coerceAtLeast(1)          // 5 ms, in original samples
        val len = (srd * 0.03f).toInt()
        val minLag = (srd / 450f).toInt()
        val maxLag = (srd / 60f).toInt()
        val frames = x.size / hop + 1

        var peak = 0f
        for (v in x) if (abs(v) > peak) peak = abs(v)
        val diff = FloatArray(maxLag + 2)
        val raw = FloatArray(frames)
        for (f in 0 until frames) {
            val start = (f * hop) / d - len / 2
            if (start < 0 || start + len + maxLag + 1 >= xd.size) continue
            var e = 0f
            for (i in 0 until len) e += xd[start + i] * xd[start + i]
            if (sqrt(e / len) < peak * 0.03f) continue
            for (tau in 1..maxLag + 1) {
                var s = 0f
                for (i in 0 until len) { val v = xd[start + i] - xd[start + i + tau]; s += v * v }
                diff[tau] = s
            }
            var run = 0f
            var found = -1
            var tau = 1
            val cm = FloatArray(maxLag + 2)
            while (tau <= maxLag + 1) {
                run += diff[tau]
                cm[tau] = if (run > 0f) diff[tau] * tau / run else 1f
                tau++
            }
            tau = minLag
            while (tau <= maxLag) {
                if (cm[tau] < 0.25f) {
                    while (tau + 1 <= maxLag && cm[tau + 1] < cm[tau]) tau++
                    found = tau
                    break
                }
                tau++
            }
            if (found > 0) {
                val a = cm[found - 1]; val b = cm[found]; val c = cm[found + 1]
                val den = a - 2 * b + c
                val off = if (abs(den) > 1e-9f) ((a - c) / (2 * den)).coerceIn(-0.5f, 0.5f) else 0f
                raw[f] = srd / (found + off)
            }
        }
        // Median-of-3 smoothing, and drop isolated voiced frames.
        val f0 = FloatArray(frames) { i ->
            val a = raw.getOrElse(i - 1) { 0f }; val b = raw[i]; val c = raw.getOrElse(i + 1) { 0f }
            if (b == 0f) 0f else if (a == 0f && c == 0f) 0f else listOf(a, b, c).filter { it > 0f }.sorted().let { it[it.size / 2] }
        }
        // Bridge short gaps (creaky or breathy frames inside a voiced stretch) so they get shifted too.
        var i = 0
        while (i < frames) {
            if (f0[i] == 0f && i > 0 && f0[i - 1] > 0f) {
                var j = i
                while (j < frames && f0[j] == 0f) j++
                if (j < frames && j - i <= 6) {
                    val a = f0[i - 1]; val b = f0[j]
                    for (k in i until j) f0[k] = a + (b - a) * (k - i + 1) / (j - i + 1)
                }
                i = j
            } else {
                i++
            }
        }
        return PitchTrack(f0, hop)
    }
}

/** Time-domain pitch-synchronous overlap-add: changes pitch, keeps duration and formants. */
object Psola {
    /** [factorFor] maps a frame's pitch (Hz) to its pitch-change ratio. */
    fun shift(x: FloatArray, sr: Int, track: PitchTrack, factorFor: (Float) -> Float): FloatArray {
        // Analysis marks, one per pitch period, snapped to local peaks for consistent alignment.
        val marks = ArrayList<Int>()
        val factors = ArrayList<Float>()
        val periods = ArrayList<Int>()
        val voiced = ArrayList<Boolean>()
        val unvoicedStep = (sr * 0.01f).toInt()
        var t = 0
        var prevVoiced = false
        while (t < x.size) {
            val f0 = track.at(t)
            if (f0 > 0f) {
                val p = (sr / f0).toInt().coerceAtLeast(16)
                val lo: Int
                val hi: Int
                if (!prevVoiced) { lo = t; hi = min(x.size - 1, t + p) } else {
                    lo = max(0, t - p / 4); hi = min(x.size - 1, t + p / 4)
                }
                var best = lo
                for (i in lo..hi) if (x[i] > x[best]) best = i
                marks += best; periods += p; voiced += true; factors += factorFor(f0)
                t = best + p
                prevVoiced = true
            } else {
                marks += t; periods += unvoicedStep; voiced += false; factors += 1f
                t += unvoicedStep
                prevVoiced = false
            }
        }
        if (marks.isEmpty()) return x

        val maxP = periods.max()
        val out = FloatArray(x.size + 2 * maxP)
        val wsum = FloatArray(out.size)
        var ts = marks[0].toFloat()
        var ai = 0
        while (ts < x.size) {
            while (ai + 1 < marks.size && abs(marks[ai + 1] - ts) <= abs(marks[ai] - ts)) ai++
            val m = marks[ai]
            val p = periods[ai]
            val center = ts.roundToInt()
            for (k in -p until p) {
                val src = m + k
                val dst = center + k + maxP
                if (src < 0 || src >= x.size || dst < 0 || dst >= out.size) continue
                val w = (0.5 + 0.5 * cos(PI * k / p)).toFloat()
                out[dst] += x[src] * w
                wsum[dst] += w
            }
            ts += if (voiced[ai]) p / factors[ai] else p.toFloat()
        }
        val y = FloatArray(x.size)
        for (i in y.indices) {
            val ws = wsum[i + maxP]
            y[i] = out[i + maxP] / max(ws, 1f)
        }
        return y
    }
}

/** In-place radix-2 FFT (size must be a power of two). */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val p = i + k; val q = p + len / 2
                    val vr = re[q] * cr - im[q] * ci
                    val vi = re[q] * ci + im[q] * cr
                    re[q] = re[p] - vr; im[q] = im[p] - vi
                    re[p] += vr; im[p] += vi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in im.indices) im[i] = -im[i]
        transform(re, im)
        val n = re.size
        for (i in re.indices) { re[i] /= n; im[i] = -im[i] / n }
    }
}
