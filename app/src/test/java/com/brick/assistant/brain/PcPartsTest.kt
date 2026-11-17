package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcPartsTest {
    private fun tech(s: String): TechAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Tech)
        return (r as Request.Tech).ask
    }

    @Test fun partQuestions() {
        assertEquals(listOf("rtx 5090", "rx 9070 xt"), tech("rtx 5090 vs rx 9070 xt").devices)
        assertEquals(listOf("ryzen 7 9800x3d"), tech("what is the ryzen 7 9800x3d").devices)
        assertEquals(listOf("9800x3d", "i9-14900k"), tech("which is better for gaming the 9800x3d or the i9-14900k").devices)
        assertEquals(listOf("samsung 990 pro", "wd black sn850x"), tech("compare the samsung 990 pro and the wd black sn850x").devices)
        assertEquals(listOf("ddr5 6000 cl30", "ddr5 6400 cl32"), tech("ddr5 6000 cl30 vs ddr5 6400 cl32").devices)
        assertEquals(listOf("b650", "x870e"), tech("b650 vs x870e").devices)
        assertEquals(listOf("corsair rm850x"), tech("tell me about the corsair rm850x").devices)
        assertEquals(listOf("core ultra 9 285k"), tech("core ultra 9 285k specs").devices)
        assertTrue(tech("which has more vram rtx 5080 or rx 9070 xt").compare)
        assertEquals(listOf("asus rog strix b650-a gaming wifi"), tech("what is the asus rog strix b650-a gaming wifi").devices)
    }

    @Test fun kindsOfParts() {
        for ((s, a, b) in listOf(
            Triple("ddr4 vs ddr5", "DDR4", "DDR5"), Triple("what's the difference between nvme and sata", "NVMe SSD", "SATA SSD"),
            Triple("ssd vs hdd", "SSD", "HDD"), Triple("gold or platinum psu", "80 Plus Gold", "80 Plus Platinum"),
            Triple("pcie 4.0 vs 5.0", "PCIe 4.0", "PCIe 5.0"), Triple("atx vs micro atx", "ATX", "Micro-ATX"),
        )) {
            val ask = tech(s)
            assertEquals(s, TechWant.TERM, ask.want)
            assertEquals(s, listOf(a, b), ask.devices.map { Tech.term(it)!!.let { t -> t.name ?: t.title } })
        }
        assertEquals(TechWant.TERM, tech("what is ddr5").want)
        assertEquals(TechWant.TERM, tech("what does a power supply do").want)
        assertEquals(TechWant.TERM, tech("what is an 80 plus gold psu").want)
        assertFalse(IntentParser.parse("what is gold") is Request.Tech)
    }

    @Test fun kindsAndKeys() {
        assertEquals(PartKind.GPU, PcParts.kind("rtx 5090"))
        assertEquals(PartKind.GPU, PcParts.kind("radeon rx 9070 xt"))
        assertEquals(PartKind.CPU, PcParts.kind("ryzen 7 9800x3d"))
        assertEquals(PartKind.CPU, PcParts.kind("i9-14900k"))
        assertEquals(PartKind.CPU, PcParts.kind("9800x3d"))
        assertEquals(PartKind.SSD, PcParts.kind("samsung 990 pro"))
        assertEquals(PartKind.RAM, PcParts.kind("corsair vengeance ddr5 6000"))
        assertEquals(PartKind.BOARD, PcParts.kind("msi mag b650 tomahawk wifi"))
        assertEquals(PartKind.PSU, PcParts.kind("corsair rm850x"))
        assertEquals(null, PcParts.kind("iphone 16"))
        assertEquals("5090", PcParts.key("rtx 5090"))
        assertEquals("5060ti", PcParts.key("rtx 5060 ti"))
        assertEquals("9070xt", PcParts.key("rx 9070 xt"))
        assertEquals("9800x3d", PcParts.key("ryzen 7 9800x3d"))
        assertEquals("14900k", PcParts.key("i9-14900k"))
        assertEquals("285k", PcParts.key("core ultra 9 285k"))
        assertEquals("Ryzen 7 9800X3D", PcParts.pretty("ryzen 7 9800x3d"))
        assertEquals("GeForce RTX 5090", PcParts.pretty("rtx 5090"))
        assertEquals("Core i9-14900K", PcParts.pretty("i9-14900k"))
    }

    @Test fun builtInSpecs() {
        val ram = PcParts.ram("corsair vengeance 32gb ddr5 6000 cl30")!!
        assertEquals("Corsair Vengeance 32 GB DDR5-6000 CL30", ram.name)
        assertEquals("10.0 ns", ram.rows.toMap()["TRUE LAT"])
        assertEquals(96.0, ram.nums.first { it.label == "BANDWID" }.value, 0.01)
        assertTrue(ram.nums.first { it.label == "TRUE LAT" }.lower)

        val psu = PcParts.psu("corsair rm850x")!!
        assertEquals("850 W", psu.rows.toMap()["POWER"])
        assertEquals("80 Plus Gold", psu.rows.toMap()["RATING"])
        assertEquals("Fully modular", psu.rows.toMap()["CABLES"])

        val board = PcParts.board("asus rog strix b650-a gaming wifi")!!
        assertEquals("AM5", board.rows.toMap()["SOCKET"])
        assertEquals("Yes", board.rows.toMap()["CPU OC"])
        assertEquals("B650 boards: AM5, DDR5, CPU overclocking, PCIe 4.0 graphics slot.", PcParts.board("b650")!!.answer)

        val ssd = PcParts.ssd("samsung 990 pro")!!
        assertEquals("up to 7,450 MB/s", ssd.rows.toMap()["READ"])
        assertEquals("Samsung 990 Evo Plus", PcParts.ssd("990 evo plus")!!.name)

        val v = PcParts.versus(PcParts.ssd("990 pro")!!, PcParts.ssd("sn850x")!!)
        assertTrue(v.verdict, v.verdict.startsWith("The Samsung 990 Pro wins 2 of 2"))
        val mem = PcParts.versus(PcParts.ram("ddr5 6000 cl28")!!, PcParts.ram("ddr5 6400 cl32")!!)
        assertEquals(0, mem.bars.first { it.label == "TRUE LAT" }.winner)
        assertEquals(1, mem.bars.first { it.label == "SPEED" }.winner)
    }

    private val acrossTop = """
        <table class="wikitable">
        <tr><th colspan="2">Model</th><th>5070</th><th>5080</th><th>5090</th></tr>
        <tr><th colspan="2">Launch</th><th colspan="2">Jan 2025</th><td>Jan 2025</td></tr>
        <tr><th colspan="2">CUDA cores</th><td>6,144</td><td>10,752</td><td>21,760</td></tr>
        <tr><th>Memory</th><th>Size (GB)</th><td>12</td><td>16</td><td>32</td></tr>
        <tr><th colspan="2">TBP (W)</th><td>250</td><td>360</td><td>575</td></tr>
        </table>
    """.trimIndent()

    private val downSide = """
        <table class="wikitable">
        <tr><th rowspan="2">Branding</th><th rowspan="2">Model</th><th rowspan="2">Cores (threads)</th><th colspan="2">Clock rate (GHz)</th><th rowspan="2">L3 cache</th><th rowspan="2">TDP</th></tr>
        <tr><th>Base</th><th>Boost</th></tr>
        <tr><td rowspan="2">Ryzen 7</td><th>9700X</th><td>8 (16)</td><td>3.8</td><td>5.5</td><td>32 MB</td><td>65 W</td></tr>
        <tr><th>9800X3D<sup>[1]</sup></th><td>8 (16)</td><td>4.7</td><td>5.2</td><td>96 MB</td><td>120 W</td></tr>
        </table>
    """.trimIndent()

    @Test fun specTables() {
        val gpu = PcParts.modelRow(acrossTop, "5090")!!.toMap()
        assertEquals("21,760", gpu["CUDA cores"])
        assertEquals("32", gpu["Memory Size (GB)"])
        assertEquals("575", gpu["TBP (W)"])
        val card = PcParts.build(PartKind.GPU, "GeForce RTX 5090", null, null, "", PcParts.modelRow(acrossTop, "5090")!!)
        assertEquals(21760.0, card.nums.first { it.label == "CORES" }.value, 0.1)
        assertEquals("32 GB", card.nums.first { it.label == "VRAM" }.show)
        assertEquals("575 W", card.nums.first { it.label == "POWER" }.show)

        val cpu = PcParts.modelRow(downSide, "9800x3d")!!.toMap()
        assertEquals("8 (16)", cpu["Cores (threads)"])
        assertEquals("5.2", cpu["Clock rate (GHz) Boost"])
        assertEquals("96 MB", cpu["L3 cache"])
        val chip = PcParts.build(PartKind.CPU, "Ryzen 7 9800X3D", null, null, "", PcParts.modelRow(downSide, "9800x3d")!!)
        assertEquals("The Ryzen 7 9800X3D: 8 cores, up to 5.2 GHz, 96 MB cache, 120 W.", chip.answer)
        assertEquals(16.0, chip.nums.first { it.label == "THREADS" }.value, 0.1)
        assertNotNull(PcParts.modelRow(downSide, "9700x"))
    }

    @Test fun hybridCoresAndSplitClocks() {
        val intel = PcParts.build(PartKind.CPU, "Core i9-14900K", null, null, "", listOf(
            "P-core (performance) Cores / (Threads)" to "8 (16)", "P-core (performance) Clock rate (GHz) Turbo" to "5.8",
            "P-core (performance) Clock rate (GHz) TVB" to "6.0", "E-core (efficiency) Cores / (Threads)" to "16 (16)",
            "E-core (efficiency) Clock rate (GHz) Turbo" to "4.4", "TDP Base" to "125 W", "TDP Max. / Turbo" to "253 W"))
        assertEquals("24 (8P + 16E)", intel.rows.toMap()["CORES"])
        assertEquals(32.0, intel.nums.first { it.label == "THREADS" }.value, 0.1)
        assertEquals("6.0 GHz", intel.nums.first { it.label == "BOOST" }.show)
        assertEquals("253 W", intel.rows.toMap()["MAX TDP"])
        assertEquals("125 W", intel.nums.first { it.label == "POWER" }.show)

        val gpu = PcParts.build(PartKind.GPU, "GeForce RTX 5090", null, null, "", listOf("Core Clock speed (GHz) / Boost value (GHz)" to "2.01 / 2.41"))
        assertEquals("2.01 GHz", gpu.rows.toMap()["BASE"])
        assertEquals("2.41 GHz", gpu.rows.toMap()["BOOST"])
    }
}
