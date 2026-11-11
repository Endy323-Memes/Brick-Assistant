package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optic's edge visualizer by voice. */
class VisualizerTest {
    private fun v(text: String) = IntentParser.parse(text) as? Request.Visualizer

    @Test
    fun onAndOff() {
        assertEquals(Request.Visualizer(on = true), v("turn on the visualizer"))
        assertEquals(Request.Visualizer(on = false), v("turn off optic"))
        assertEquals(Request.Visualizer(on = false), v("hide the music visualizer"))
        assertEquals(Request.Visualizer(on = true), v("visualizer on"))
        assertEquals(Request.Visualizer(on = false), v("can you switch the visualiser off please"))
        assertEquals(Request.Visualizer(on = true), v("enable optic's visualizer"))
    }

    @Test
    fun designs() {
        assertEquals(VizDesign.EDGE_GLOW, v("change the visualizer design to edge glow")?.design)
        assertEquals(VizDesign.EDGE_GLOW, v("use the glow visualizer")?.design)
        assertEquals(VizDesign.EDGE_GLOW, v("switch optic to edge glow")?.design)
    }

    @Test
    fun colors() {
        assertEquals(VizColors.ALBUM, v("make the visualizer use the album art colors")?.colors)
        assertEquals(VizColors.SPECTRUM, v("set the visualizer colors to rainbow")?.colors)
        assertEquals(VizColors.BRICK, v("make optic match my colors")?.colors)
        assertEquals(VizColors.GRADIENT, v("change the visualizer colors to a gradient")?.colors)
    }

    @Test
    fun sensitivity() {
        assertEquals(1, v("make the visualizer more sensitive")?.sensitive)
        assertEquals(-1, v("make optic less reactive")?.sensitive)
        assertEquals(1, v("turn up the visualizer sensitivity")?.sensitive)
        assertEquals(-1, v("lower optic's sensitivity")?.sensitive)
    }

    @Test
    fun onlyWhilePlaying() {
        assertEquals(true, v("only show the visualizer when music is playing")?.onlyMedia)
        assertEquals(false, v("show the visualizer all the time")?.onlyMedia)
    }

    @Test
    fun status() {
        assertEquals(Request.Visualizer(), v("is the visualizer on"))
        assertEquals(Request.Visualizer(), v("what's optic set to"))
        assertEquals(Request.Visualizer(), v("what design is the visualizer using"))
    }

    @Test
    fun leavesOtherRequestsAlone() {
        assertTrue(IntentParser.parse("open optic") is Request.OpenApp)
        assertFalse(IntentParser.parse("turn on the flashlight") is Request.Visualizer)
        assertFalse(IntentParser.parse("turn on reverb") is Request.Visualizer)
        assertTrue(IntentParser.parse("disable the floating card") is Request.Setting)
    }
}
