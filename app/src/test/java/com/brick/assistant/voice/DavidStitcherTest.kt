package com.brick.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DavidStitcherTest {
    private val dir = File("src/main/assets/david_words")

    /** The real word list and lengths from the Opus bank, with a stand-in tone for the audio (JVM tests can't decode Opus). */
    private val source = object : WordSource {
        private val index: Map<String, Int> by lazy {
            File(dir, "index16.tsv").readLines().mapNotNull { l ->
                l.split('\t').takeIf { it.size == 4 }?.let { it[0] to it[3].toInt() }
            }.toMap()
        }
        override val sampleRate = 16000
        override fun contains(word: String) = word in index
        override fun get(word: String): FloatArray? = index[word]?.let { n ->
            FloatArray(n) { i -> (0.3 * kotlin.math.sin(2 * Math.PI * 140.0 * i / sampleRate)).toFloat() }
        }
    }

    @Test
    fun wsolaChangesSpeedNotPitch() {
        val sr = 22050
        val x = FloatArray(sr * 2) { i -> (0.4 * kotlin.math.sin(2 * Math.PI * 120.0 * i / sr)).toFloat() }
        val y = Wsola.speed(x, sr, 1.25f)
        assertTrue("length ${y.size}", kotlin.math.abs(y.size - x.size / 1.25f) < sr * 0.02f)
        val f0 = PitchTracker.track(y, sr).median()
        assertNotNull(f0)
        assertTrue("pitch $f0", kotlin.math.abs(f0!! - 120f) < 5f)
    }

    @Test
    fun numbersAreSpelledOut() {
        assertEquals("seven thirty and twenty five percent", NumberSpeller.expand("7:30 and 25%"))
        assertEquals("the third of one hundred twelve", NumberSpeller.expand("the 3rd of 112"))
    }

    @Test
    fun tokenizeMarksPauses() {
        val t = DavidStitcher.tokenize("Okay, I'm on it. Done!")
        assertEquals(listOf("okay" to 1, "i'm" to 0, "on" to 0, "it" to 2, "done" to 2), t)
    }

    @Test
    fun dashMeansInterrupted() {
        assertTrue(DavidStitcher.endsInterrupted("Hold on, let me just-"))
        assertEquals("Hold on, let me just", DavidStitcher.withoutDash("Hold on, let me just-"))
    }

    @Test
    fun stitchesRealDavidAndCutsOff() {
        if (!dir.exists()) return
        val line = "Of course, I will take care of that for you right now."
        assertEquals(1f, DavidStitcher.coverage(line, source))
        val r = DavidStitcher.stitch(line, source) { null }
        assertNotNull(r)
        val full = r!!.audio
        assertTrue(full.samples.size > full.sampleRate) // over a second

        val cutLine = "Let me just check the weather-"
        val r2 = DavidStitcher.stitch(DavidStitcher.withoutDash(cutLine), source) { null }!!
        val cut = DavidStitcher.cutOff(r2.audio, r2.lastWordStart, r2.lastWordEnd)
        val expected = r2.lastWordStart + (r2.lastWordEnd - r2.lastWordStart) / 2
        assertTrue(abs(cut.samples.size - expected) <= 1)

        // Casual / profane words the user asked for are real David recordings too.
        val casual = "Holy shit, that was fucking awesome. You sped right past it, bro."
        assertEquals(1f, DavidStitcher.coverage(casual, source))
        val r3 = DavidStitcher.stitch(casual, source) { null }!!
        assertTrue(r3.audio.samples.maxOf { kotlin.math.abs(it) } > 0.05f) // stitched audio isn't silent

    }

    /** A flat, voice-like buzz at [f0] Hz. */
    private fun buzz(f0: Double, sr: Int, secs: Double) = FloatArray((sr * secs).toInt()) { i ->
        var v = 0.0
        for (h in 1..6) v += kotlin.math.sin(2 * Math.PI * f0 * h * i / sr) / h
        (0.25 * v).toFloat()
    }

    private fun endPitch(x: FloatArray, sr: Int): Float {
        val tail = x.copyOfRange(x.size - sr / 8, x.size)
        return PitchTracker.track(tail, sr).median()!!
    }

    @Test
    fun questionsEndOnARise() {
        val sr = 16000
        val flat = PcmAudio(buzz(110.0, sr, 1.2), sr)
        val asked = RobotFx.questionRise(flat)
        assertEquals(flat.samples.size, asked.samples.size)
        assertTrue("start stays put", kotlin.math.abs(PitchTracker.track(asked.samples.copyOfRange(0, sr / 3), sr).median()!! - 110f) < 6f)
        val end = endPitch(asked.samples, sr)
        assertTrue("ending rises: $end Hz", end > 125f)
        // An ending that already rises enough is left alone.
        val again = RobotFx.questionRise(asked)
        assertTrue("no double rise: ${endPitch(again.samples, sr)} vs $end", endPitch(again.samples, sr) < end * 1.06f)
        // Stitched questions rise on the last word; statements don't.
        val q = DavidStitcher.stitch("Do you want me to read the details?", source) { null }!!.audio
        val st = DavidStitcher.stitch("I will read the details.", source) { null }!!.audio
        assertTrue("question ${endPitch(q.samples, q.sampleRate)} vs statement ${endPitch(st.samples, st.sampleRate)}",
            endPitch(q.samples, q.sampleRate) > endPitch(st.samples, st.sampleRate) * 1.15f)
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
