package com.brick.assistant.voice

import com.brick.assistant.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RobotFxTest {

    /** Synthetic voiced signal: harmonic stack at a gliding pitch around [f0]. */
    private fun voiced(f0: Float, sr: Int, seconds: Float): FloatArray {
        val n = (sr * seconds).toInt()
        var phase = 0.0
        return FloatArray(n) { i ->
            val f = f0 * (1f + 0.1f * sin(2 * PI * i / n).toFloat())
            phase += 2 * PI * f / sr
            var v = 0.0
            for (h in 1..12) v += sin(phase * h) / h
            (0.3 * v).toFloat()
        }
    }

    @Test
    fun pitchTrackerFindsMedian() {
        val sr = 24000
        val med = PitchTracker.track(voiced(110f, sr, 1.5f), sr).median()
        assertNotNull(med)
        assertTrue("median was $med", abs(med!! - 110f) < 6f)
    }

    @Test
    fun psolaMovesPitchToTargetAndKeepsLength() {
        val sr = 24000
        val x = voiced(100f, sr, 1.5f)
        val out = RobotFx.process(PcmAudio(x, sr), AppSettings())
        assertEquals(x.size, out.samples.size)
        val med = PitchTracker.track(out.samples, sr).median()
        assertNotNull(med)
        assertTrue("median was $med", abs(med!! - AppSettings().robotTargetF0) < 14f)
    }

    @Test
    fun davidMatchMovesPitchToDavid() {
        val sr = 22050
        val x = voiced(200f, sr, 1.5f)
        val out = RobotFx.davidMatch(PcmAudio(x, sr))
        assertEquals(x.size, out.samples.size)
        val med = PitchTracker.track(out.samples, sr).median()
        assertNotNull(med)
        assertTrue("median was $med", abs(med!! - RobotFx.DAVID_F0) < 8f)
    }

    /** Batch-process real voice files: -PfxIn=<dir of wav> -PfxOut=<dir>. Skipped when not given. */
    @Test
    fun processDirectory() {
        val inDir = System.getProperty("fxIn") ?: return
        val outDir = File(System.getProperty("fxOut") ?: return).apply { mkdirs() }
        File(inDir).listFiles { f -> f.extension == "wav" }!!.sorted().forEach { f ->
            val pcm = Wav.read(f)!!
            val before = PitchTracker.track(pcm.samples, pcm.sampleRate).median()
            val t0 = System.nanoTime()
            val out = RobotFx.process(pcm, AppSettings())
            val ms = (System.nanoTime() - t0) / 1e6
            val after = PitchTracker.track(out.samples, out.sampleRate).median()
            Wav.write(File(outDir, f.name), out)
            println("FX ${f.name}: sr=${pcm.sampleRate} f0 ${before?.toInt()} -> ${after?.toInt()} Hz, ${"%.0f".format(ms)} ms for ${"%.1f".format(pcm.samples.size / pcm.sampleRate.toFloat())} s")
        }
    }
}
