package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBuildTest {
    private fun ask(s: String): BuildAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.PcBuild)
        return (r as Request.PcBuild).ask
    }

    @Test fun understandsLoosePhrasing() {
        for ((s, p, b) in listOf(
            Triple("build me a pc for \$1500", BuildFor.GOOD, 1500),
            Triple("can you build me a gaming pc for 1000 dollars", BuildFor.GAMING, 1000),
            Triple("spec out a gaming rig under 1.5k", BuildFor.GAMING, 1500),
            Triple("baller pc", BuildFor.BALLER, null),
            Triple("build me the most powerful pc money can buy", BuildFor.BALLER, null),
            Triple("4k gaming pc", BuildFor.GAMING_4K, null),
            Triple("what pc should i build for 4k gaming with 2 grand", BuildFor.GAMING_4K, 2000),
            Triple("budget build", BuildFor.BUDGET, null),
            Triple("cheap gaming pc for 700 bucks", BuildFor.BUDGET, 700),
            Triple("good pc for 1200", BuildFor.GOOD, 1200),
            Triple("game developer pc", BuildFor.GAMEDEV, null),
            Triple("i need a pc for unreal engine around 2500", BuildFor.GAMEDEV, 2500),
            Triple("video editor rig", BuildFor.VIDEO, null),
            Triple("put together a pc for video editing for fifteen hundred", BuildFor.VIDEO, 1500),
            Triple("general project pc", BuildFor.GENERAL, null),
            Triple("make me a computer for school for 600", BuildFor.GENERAL, 600),
            Triple("i have 800 to spend on a gaming pc", BuildFor.GAMING, 800),
            Triple("parts list for a two grand gaming pc", BuildFor.GAMING, 2000),
            Triple("how much is a good gaming pc", BuildFor.GAMING, null),
        )) {
            val a = ask(s)
            assertEquals(s, p, a.purpose)
            assertEquals(s, b, a.budget)
        }
        assertEquals("GeForce RTX 5090", ask("build me a gaming pc with a 5090").gpu)
        assertEquals(true, ask("all nvidia gaming build for 1500").nvidia)
        assertEquals(false, ask("intel gaming pc for 1200").amdCpu)
    }

    @Test fun leavesOtherThingsAlone() {
        for (s in listOf("restart my pc", "how do i build a pc", "what is a pc", "turn off the computer", "rtx 5090 vs rx 9070 xt", "remind me to build a pc", "my pc is slow", "what is a gaming pc"))
            assertFalse(s, IntentParser.parse(s) is Request.PcBuild)
    }

    @Test fun budgets() {
        assertEquals(1500, PcBuild.budget("a pc for \$1,500"))
        assertEquals(2500, PcBuild.budget("2.5k gaming pc"))
        assertEquals(1000, PcBuild.budget("a grand"))
        assertNull(PcBuild.budget("4k gaming pc"))
        assertEquals(4000, PcBuild.budget("\$4k gaming pc"))
        assertNull(PcBuild.budget("a pc with 32 gb"))
        assertEquals(900, PcBuild.budget("gaming pc under 900"))
    }

    @Test fun buildsFitTheBudgetAndTheJob() {
        for (budget in listOf(600, 800, 1000, 1500, 2000, 3000, 5000)) for (p in BuildFor.values()) {
            val b = PcBuild.choose(BuildAsk(p, budget, "x"))
            assertTrue("$p $budget -> ${b.total}", b.total <= budget || budget <= 600)
        }
        val gaming = PcBuild.choose(BuildAsk(BuildFor.GAMING, 1500, "x"))
        assertTrue(gaming.gpu!!.price > gaming.cpu.price)
        val video = PcBuild.choose(BuildAsk(BuildFor.VIDEO, 2500, "x"))
        assertTrue(video.ram.size >= 64)
        assertTrue(video.cpu.multi >= 80)
        val k4 = PcBuild.choose(BuildAsk(BuildFor.GAMING_4K, 2400, "x"))
        assertTrue(k4.gpu!!.vram >= 16)
        val baller = PcBuild.choose(BuildAsk(BuildFor.BALLER, null, "x"))
        assertEquals("GeForce RTX 5090", baller.gpu?.name)
        assertTrue(baller.psu.size >= 1000)
        val office = PcBuild.choose(BuildAsk(BuildFor.GENERAL, 600, "x"))
        assertNull(office.gpu)
        assertTrue(office.cpu.igpu > 0)
    }

    @Test fun infographicFitsTheCard() {
        for (p in BuildFor.values()) {
            val b = PcBuild.choose(BuildAsk(p, 1500, "x"))
            val r = PcBuild.card(b, null)
            val text = Infographics.buildText(b, r.answer, r.more)
            for (line in text.lines()) assertEquals(line, Box.W + 4, line.length)
            for (head in listOf("PARTS AND PRICE", "AAA GAMES", "CINEBENCH", "TIME SPY", "WHAT IT CAN DO", "POWER")) assertTrue(head, text.contains(head))
        }
        val b = PcBuild.choose(BuildAsk(BuildFor.GAMING, 1500, "x"))
        assertTrue(PcBuild.answer(b), PcBuild.answer(b).startsWith("For \$1,500: a "))
        assertTrue(PcBuild.power(b).peak < b.psu.size)
    }
}
