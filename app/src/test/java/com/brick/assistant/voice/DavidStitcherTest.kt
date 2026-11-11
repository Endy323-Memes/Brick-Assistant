package com.brick.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class DavidStitcherTest {
    private val dir = File("src/main/assets/david_words")

    private val source = object : AdpcmWordSource() {
        override fun readIndex() = File(dir, "index.tsv").readText()
        override fun readBytes(offset: Long, length: Int): ByteArray? = RandomAccessFile(File(dir, "words.adpcm"), "r").use {
            it.seek(offset)
            ByteArray(length).also { b -> it.readFully(b) }
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
        assertTrue(r3.audio.samples.maxOf { kotlin.math.abs(it) } > 0.05f) // decoded audio isn't silent

        val out = File(System.getProperty("stitchOut") ?: return).apply { mkdirs() }
        Wav.write(File(out, "david-stitched_of-course.wav"), full)
        Wav.write(File(out, "david-stitched_interrupted.wav"), cut)
        Wav.write(File(out, "david-stitched_casual.wav"), r3.audio)
        val q = DavidStitcher.stitch("Do you want me to play some music?", source) { null }!!
        Wav.write(File(out, "david-stitched_question.wav"), q.audio)
        // What the phone plays now: custom responses at the default 1.2x speed.
        Wav.write(File(out, "david-stitched_of-course_1.2x.wav"), PcmAudio(Wsola.speed(full.samples, full.sampleRate, 1.2f), full.sampleRate))
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
