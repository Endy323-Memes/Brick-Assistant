package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopsTest {
    private fun fixture(): Laptops.Gen {
        val html = org.json.JSONObject(javaClass.getResource("/laptops/macbook-air-m5.json")!!.readText()).getJSONObject("parse").getString("text")
        return Laptops.parse(Laptops.Mac(false, 5, null, null), 5, null, html)!!
    }

    @Test fun readsTheNameAsked() {
        assertEquals(Laptops.Mac(false, null, null, null), Laptops.match("macbook air"))
        assertEquals(Laptops.Mac(false, 5, null, null), Laptops.match("MacBook Air M5"))
        assertEquals(Laptops.Mac(true, 5, "max", 16), Laptops.match("macbook pro m5 max 16 inch"))
        assertEquals(Laptops.Mac(true, null, null, null), Laptops.match("newest macbook pro"))
        assertNull(Laptops.match("macbook air charger"))
        assertNull(Laptops.match("macbook pro 2019"))
        assertNull(Laptops.match("dell xps 13"))
    }

    @Test fun readsTheSpecsTable() {
        val g = fixture()
        assertEquals(listOf("13-inch, M5", "15-inch, M5"), g.groups)
        val f = Laptops.facts(g).toMap()
        assertEquals("March 11, 2026", f["OUT"])
        assertEquals("\$1,299 to \$1,999", f["PRICE"])
        assertEquals("Apple M5", f["CHIP"])
        assertEquals("16 GB / 24 GB, up to 32 GB", f["RAM"])
        assertTrue(f["SCREEN"], f["SCREEN"]!!.startsWith("13.6 in, 2560 x 1664 or 15.3 in"))
        val said = Laptops.answer(g, TechWant.OVERVIEW, Laptops.Mac(false, null, null, null))
        assertTrue(said, said.startsWith("The newest MacBook Air is the M5 model, out March 11, 2026."))
        assertTrue(Laptops.answer(g, TechWant.CHIP, Laptops.Mac(false, 5, null, null)).startsWith("The MacBook Air M5 runs the Apple M5"))
    }

    @Test fun routesMacBooksToTheLaptopReader() {
        for (s in listOf("what is a macbook air", "what is a macbook air m5", "macbook pro m5 specs", "macbook air m5 vs macbook pro m5"))
            assertTrue(s, (IntentParser.parse(s) as Request.Tech).ask.devices.all { Laptops.match(it) != null })
    }
}
