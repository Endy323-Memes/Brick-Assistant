package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechTest {
    private fun tech(s: String): TechAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Tech)
        return (r as Request.Tech).ask
    }

    private fun notTech(s: String) {
        val r = IntentParser.parse(s)
        assertFalse("\"$s\" -> $r", r is Request.Tech)
    }

    @Test fun comparisons() {
        for (s in listOf(
            "what is the difference between the iphone 11 and the pixel 9a?",
            "whats the difference between iphone 11 and pixel 9a",
            "iphone 11 vs pixel 9a",
            "compare the iphone 11 to the pixel 9a",
            "which is better the iphone 11 or the pixel 9a",
            "should i get the iphone 11 or pixel 9a",
            "how does the iphone 11 compare to the pixel 9a",
            "is the pixel 9a better than the iphone 11",
            "iphone 11 versus google pixel 9a",
        )) {
            val a = tech(s)
            assertTrue(s, a.compare)
            assertEquals(s, setOf("iPhone 11", "Pixel 9a"), a.devices.map { Tech.canonical(it) }.toSet())
        }
        assertEquals(TechWant.CAMERA, tech("which has a better camera iphone 16 pro or s25 ultra").want)
        assertEquals(TechWant.BATTERY, tech("s26 ultra vs pixel 10 pro battery").want)
        // Consoles now go to the CONSOLE card; their wording still reads as a device comparison here.
        assertEquals(listOf("ps5", "xbox series x"), Tech.parse("ps5 vs xbox series x").let { (it as Request.Tech).ask.devices })
    }

    @Test fun oneDevice() {
        val a = tech("what is the S26 ultra")
        assertEquals(listOf("s26 ultra"), a.devices)
        assertEquals(TechWant.OVERVIEW, a.want)
        assertEquals(TechWant.BATTERY, tech("how long does the s26 ultra battery last").want)
        assertEquals(TechWant.BATTERY, tech("galaxy s26 ultra battery life").want)
        assertEquals(TechWant.REFRESH, tech("what's the refresh rate on the pixel 9a").want)
        assertEquals(TechWant.UNLOCK, tech("how do you unlock the iphone 16").want)
        assertEquals(TechWant.UNLOCK, tech("does the pixel 9a have a fingerprint reader").want)
        assertEquals(TechWant.CHIP, tech("what chip is in the iphone 11").want)
        assertEquals(TechWant.CHARGING, tech("how fast does the s26 ultra charge").want)
        assertEquals(TechWant.PORT, tech("what usb port does the iphone 15 have").want)
        assertEquals(TechWant.SPEAKERS, tech("are the s25 ultra speakers good").want)
        assertEquals(TechWant.CAMERA, tech("iphone 16 pro max camera specs").want)
        assertEquals(TechWant.SPECS, tech("s26 ultra specs").want)
        assertEquals(TechWant.DISPLAY, tech("tell me about the steam deck oled screen").want)
        assertEquals(TechWant.SPECS, tech("rtx 5090 specs").want)
        assertEquals(TechWant.OVERVIEW, (Tech.parse("tell me about the nintendo switch 2") as Request.Tech).ask.want)
        assertEquals(listOf("s26+"), tech("what is the s26+").devices)
    }

    @Test fun terms() {
        for ((s, title) in listOf(
            "what is a gpu" to "Graphics processing unit",
            "what does a gpu do" to "Graphics processing unit",
            "what's a graphics card" to "Graphics processing unit",
            "what is ram" to "Random-access memory",
            "explain what an ssd is" to "Solid-state drive",
            "what is usb-c" to "USB-C",
            "what is oled" to "OLED",
            "what is a cpu" to "Central processing unit",
            "what is face id" to "Face ID",
            "what does nfc stand for" to "Near-field communication",
            "how does a gpu work" to "Graphics processing unit",
            "why is a gpu important" to "Graphics processing unit",
        )) {
            val a = tech(s)
            assertEquals(s, TechWant.TERM, a.want)
            assertEquals(s, title, Tech.term(a.devices.single())?.title)
        }
        val both = tech("what's the difference between a cpu and a gpu")
        assertEquals(TechWant.TERM, both.want)
        assertEquals(listOf("cpu", "gpu"), both.devices)
        assertEquals(TechWant.TERM, tech("cpu vs gpu").want)
    }

    @Test fun leavesOtherThingsAlone() {
        notTech("what is a galaxy")
        notTech("what is a pixel")
        notTech("how much is the iphone 16")
        notTech("cheapest price for the ps5")
        notTech("what's my battery")
        notTech("play music on my ps5")
        notTech("what time is it")
        notTech("set a timer for 5 minutes")
        notTech("who is taylor swift")
        notTech("what do eagles eat")
    }

    @Test fun names() {
        assertEquals("Samsung Galaxy S26 Ultra", Tech.canonical("s26 ultra"))
        assertEquals("Samsung Galaxy S26 Ultra", Tech.canonical("samsung galaxy s26 ultra"))
        assertEquals("Samsung Galaxy S26+", Tech.canonical("s26+"))
        assertEquals("Samsung Galaxy Z Fold 7", Tech.canonical("z fold 7"))
        assertEquals("iPhone 16 Pro Max", Tech.canonical("iphone 16 pro max"))
        assertEquals("Pixel 9a", Tech.canonical("pixel 9a"))
        assertEquals("PlayStation 5", Tech.canonical("ps5"))
        assertEquals("Nintendo Switch 2", Tech.canonical("switch 2"))
        assertEquals("GeForce RTX 5090", Tech.canonical("rtx 5090"))
        assertEquals("Radeon RX 9070 XT", Tech.canonical("rx 9070 xt"))
        assertEquals("Steam Deck OLED", Tech.canonical("steam deck oled"))
    }

    private val s26Html = """
        <table class="infobox"><tbody>
        <tr><th class="infobox-label">Battery</th><td class="infobox-data">S26: 4300&nbsp;mAh<br>S26+, S26 FE: 4900&nbsp;mAh<br>S26 Ultra: 5000&nbsp;mAh<sup>[3]</sup></td></tr>
        <tr><th class="infobox-label">Charging</th><td class="infobox-data">S26:<br>25W Super Fast Charging<br>15W Fast Wireless Charging<br>S26+:<br>45W Super Fast Charging<br>S26 Ultra:<br>60W Super Fast Charging<br>25W Fast Wireless Charging<br>All:<br>Reverse charging 4.5W</td></tr>
        <tr><th class="infobox-label">System-on-chip</th><td class="infobox-data">S26 &amp; S26+ (US, China) &amp; S26 Ultra: Qualcomm Snapdragon 8 Elite Gen 5 (3 nm)<br>S26 &amp; S26+ (worldwide): Samsung Exynos 2600<br>S26 FE: Samsung Exynos 2500</td></tr>
        <tr><th class="infobox-label">Data inputs</th><td class="infobox-data">S Pen (S26 Ultra)</td></tr>
        </tbody></table>
    """.trimIndent()

    @Test fun infoboxAndLineups() {
        val rows = Tech.infobox(s26Html)
        assertEquals(listOf("Battery", "Charging", "System-on-chip", "Data inputs"), rows.map { it.first })
        val ultra = Tech.model("Samsung Galaxy S26 Ultra")!!
        val base = Tech.model("Samsung Galaxy S26")!!
        val fe = Tech.model("Samsung Galaxy S26 FE")!!
        val battery = rows[0].second
        assertEquals("5000 mAh", Tech.forModel(battery, ultra))
        assertEquals("4300 mAh", Tech.forModel(battery, base))
        assertEquals("4900 mAh", Tech.forModel(battery, fe))
        val charging = Tech.forModel(rows[1].second, ultra)
        assertTrue(charging, charging.contains("60W") && charging.contains("25W Fast Wireless") && charging.contains("4.5W") && !charging.contains("45W"))
        assertEquals("Qualcomm Snapdragon 8 Elite Gen 5 (3 nm)", Tech.forModel(rows[2].second, ultra))
        assertEquals("Samsung Exynos 2500", Tech.forModel(rows[2].second, fe))

        val specs = Tech.Specs("Samsung Galaxy S26 Ultra", "Samsung Galaxy S26", "Android smartphone", "", null, "",
            rows.map { (k, v) -> k to Tech.forModel(v, ultra) }, "The Galaxy S26 Ultra has a larger 6.9 in display with a 1–120 Hz refresh rate. " +
                "The S26 series uses an ultrasonic in-screen fingerprint sensor, except the S26 FE which uses an optical sensor. " +
                "The S26 Ultra supports USB Type-C 3.2.", ultra, false, rows)
        assertEquals(5000, specs.mah)
        assertEquals(60.0, specs.watts.first)
        assertEquals(25.0, specs.watts.second)
        assertEquals("6.9", specs.screenSize)
        assertEquals(120 to 1, specs.hz)
        assertEquals(listOf("Ultrasonic under-display fingerprint reader"), specs.unlock)
        assertTrue(specs.usb!!, specs.usb!!.startsWith("USB-C, USB 3.2"))
        val stand = Tech.standouts(specs)
        assertTrue(stand.toString(), stand.any { it.startsWith("Biggest battery") })
        assertTrue(stand.toString(), stand.any { it.startsWith("Fastest wired charging: 60 W") })
        assertTrue(stand.toString(), stand.any { it.startsWith("S Pen") })

        val feSpecs = specs.copy(name = "Samsung Galaxy S26 FE", model = fe, rows = rows.map { (k, v) -> k to Tech.forModel(v, fe) })
        assertEquals(listOf("Optical under-display fingerprint reader"), feSpecs.unlock)
    }

    @Test fun lineupSentence() {
        val text = "Succeeding the S25, the S26 was produced in a standard model with a display size of 6.3-inch (160 mm), the S26+ has a larger 6.7 in (170 mm) display and the S26 Ultra has a larger 6.9 in (170 mm) display. All models come with a 120 Hz refresh rate."
        fun spec(name: String) = Tech.Specs(name, "Samsung Galaxy S26", "smartphone", "", null, "",
            listOf("Operating system" to "Original: ¦ Android 16 with One UI 8.5"), text, Tech.model(name), false)
        assertEquals("6.9", spec("Samsung Galaxy S26 Ultra").screenSize)
        assertEquals("6.7", spec("Samsung Galaxy S26+").screenSize)
        assertEquals("6.3", spec("Samsung Galaxy S26").screenSize)
        assertEquals(120, spec("Samsung Galaxy S26 Ultra").hz?.first)
        assertEquals("Android 16 with One UI 8.5", spec("Samsung Galaxy S26 Ultra").os)
    }

    @Test fun usbAndBattery() {
        assertEquals("Lightning (480 Mbps, USB 2.0 speed)", Tech.prettyUsb("Lightning"))
        assertEquals("USB-C, USB 3.2 Gen 2 (10 Gbps)", Tech.prettyUsb("USB-C 3.2 Gen 2"))
        assertEquals("10 Gbps", Tech.usbSpeed("usb 3.1 gen 2"))
        assertEquals("5 Gbps", Tech.usbSpeed("usb 3.0"))
        assertEquals("40 Gbps", Tech.usbSpeed("thunderbolt 4"))
        assertNull(Tech.usbSpeed("usb-c"))
        val (light, medium, heavy) = Tech.batteryLife(5000, false)
        assertTrue(light > medium && medium > heavy && medium in 18..26 && heavy in 8..13)
        assertTrue(Tech.batteryLife(3110, true).second > Tech.batteryLife(3110, false).second)
        assertEquals("12 MP wide, 12 MP ultrawide", Tech.cameraLine("12 MP wide (26 mm) f/1.8, 12 MP ultra-wide (13 mm) f/2.4"))
    }

    @Test fun versusScoreboard() {
        val bars = listOf(
            Versus.Bar("BATTERY", 3110.0, 5100.0, "3,110 mAh", "5,100 mAh", "a bigger battery"),
            Versus.Bar("SCREEN", 6.1, 6.3, "6.1 in", "6.3 in", null),
            Versus.Bar("REFRESH", 60.0, 120.0, "60 Hz", "120 Hz", "a smoother screen"),
            Versus.Bar("CAMERA", 12.0, 48.0, "12 MP", "48 MP", "more camera megapixels"),
            Versus.Bar("RAM", 4.0, null, "4 GB", null, "more RAM"),
        )
        assertEquals(1, bars[0].winner)
        assertNull(bars[4].winner)
        val v = Tech.verdict("iPhone 11", "Pixel 9a", bars)
        assertEquals("The Pixel 9a wins 3 of 3, with a bigger battery and a smoother screen.", v)
        assertTrue(v.length < 90)
        val card = Infographics.versusText(Versus("iPhone 11 (2019)", "Pixel 9a (2025)", bars,
            listOf(Versus.Words("CHIP", "A13 Bionic", "Tensor G4")), v), emptyList(), listOf("Wikipedia"))
        assertTrue(card, card.contains("[ VERSUS ]") && card.contains("SCORE  A 0 - 3 B") && card.contains("B 5,100 mAh") && card.contains(" <"))
        assertTrue(card, card.lines().all { it.length == Box.W + 4 })
    }

    @Test fun faceIdExplained() {
        val how = Tech.unlockHow(listOf("Face ID (TrueDepth 3D face scan)"))
        assertTrue(how.single().contains("dot projector") && how.single().contains("infrared") && how.single().contains("Secure Enclave"))
    }
}
