package com.brick.assistant.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PaletteTest {
    @Test
    fun hexParsing() {
        assertEquals(0xFF4DD0E1.toInt(), Palette.parseHex("#4DD0E1"))
        assertEquals(0xFF4DD0E1.toInt(), Palette.parseHex("4dd0e1"))
        assertEquals(0xFF4DD0E1.toInt(), Palette.parseHex("#804DD0E1")) // alpha is ignored: colors are opaque
        assertNull(Palette.parseHex("#12345"))
        assertNull(Palette.parseHex("blue"))
        assertEquals("#4DD0E1", Palette.hex(0xFF4DD0E1.toInt()))
    }

    @Test
    fun exportImportRoundTrip() {
        val p = Palettes.builtIn[3].copy(name = "Mine")
        val back = Palette.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        assertEquals("brick-palette", p.toJson().getString("format"))
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Palette.fromJson(JSONObject("{\"pools\":{}}")) }
        assertThrows(IllegalArgumentException::class.java) {
            Palette.fromJson(JSONObject("{\"colors\":{\"brick\":\"#FFFFFF\"}}")) // missing the other colors
        }
    }
}
