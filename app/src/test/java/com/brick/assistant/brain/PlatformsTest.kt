package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformsTest {
    private fun ask(s: String): PlatformAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Platform)
        return (r as Request.Platform).ask
    }

    @Test fun consoleQuestions() {
        for ((s, ids, want) in listOf(
            Triple("tell me about the ps5", listOf("ps5"), PlatformWant.OVERVIEW),
            Triple("what is the nintendo switch 2", listOf("switch2"), PlatformWant.OVERVIEW),
            Triple("who makes the steam deck", listOf("steamdeck"), PlatformWant.MAKER),
            Triple("what company sells the xbox series x", listOf("seriesx"), PlatformWant.MAKER),
            Triple("who owns xbox", listOf("seriesx"), PlatformWant.PARENT),
            Triple("does nintendo switch have a parent company", listOf("switch"), PlatformWant.PARENT),
            Triple("how powerful is the switch 2", listOf("switch2"), PlatformWant.PERFORMANCE),
            Triple("ps5 pro specs", listOf("ps5pro"), PlatformWant.PERFORMANCE),
            Triple("how many teraflops does the series s have", listOf("seriess"), PlatformWant.PERFORMANCE),
            Triple("what are the ps5 exclusives", listOf("ps5"), PlatformWant.EXCLUSIVES),
            Triple("what is the gamecube known for", listOf("gamecube"), PlatformWant.EXCLUSIVES),
            Triple("how many ps4s were sold", listOf("ps4"), PlatformWant.SALES),
            Triple("how many switches has nintendo sold", listOf("switch"), PlatformWant.SALES),
            Triple("what was the launch price of the ps3", listOf("ps3"), PlatformWant.PRICE),
            Triple("when did the wii come out", listOf("wii"), PlatformWant.RELEASE),
            Triple("ps5 vs xbox series x", listOf("ps5", "seriesx"), PlatformWant.OVERVIEW),
            Triple("is the switch 2 more powerful than the steam deck", listOf("switch2", "steamdeck"), PlatformWant.PERFORMANCE),
            Triple("which sold more the ps2 or the ds", listOf("ps2", "ds"), PlatformWant.SALES),
            Triple("compare the steam deck and rog ally", listOf("steamdeck", "rogally"), PlatformWant.OVERVIEW),
            Triple("difference between xbox one and ps4", listOf("xboxone", "ps4"), PlatformWant.OVERVIEW),
            Triple("hey brick what's the original xbox", listOf("xbox"), PlatformWant.OVERVIEW),
            Triple("whats the best selling console ever", listOf("ps2"), PlatformWant.SALES),
            Triple("whats the most powerful console", listOf("ps5pro"), PlatformWant.PERFORMANCE),
            Triple("xbox or playstation", listOf("seriesx", "ps5"), PlatformWant.OVERVIEW),
            Triple("how many people own a ps5", listOf("ps5"), PlatformWant.SALES),
            Triple("who made the dreamcast", listOf("dreamcast"), PlatformWant.MAKER),
            Triple("how much is a ps5", listOf("ps5"), PlatformWant.PRICE),
            Triple("how much does the steam deck cost right now", listOf("steamdeck"), PlatformWant.PRICE),
            Triple("which is cheaper the series s or the switch 2", listOf("seriess", "switch2"), PlatformWant.PRICE),
            Triple("xbox series x price today", listOf("seriesx"), PlatformWant.PRICE),
        )) {
            val a = ask(s)
            assertEquals(s, ids, a.ids)
            assertEquals(s, want, a.want)
        }
    }

    @Test fun osQuestions() {
        for ((s, ids, want) in listOf(
            Triple("tell me about linux", listOf("linux"), PlatformWant.OVERVIEW),
            Triple("who made windows", listOf("windows"), PlatformWant.MAKER),
            Triple("what company makes android", listOf("android"), PlatformWant.MAKER),
            Triple("who owns fedora linux", listOf("fedora"), PlatformWant.PARENT),
            Triple("what kernel does android use", listOf("android"), PlatformWant.KERNEL),
            Triple("what's the latest linux kernel version", listOf("linux"), PlatformWant.KERNEL),
            Triple("what is mint based on", listOf("mint"), PlatformWant.DISTRO),
            Triple("is ubuntu a distro", listOf("ubuntu"), PlatformWant.DISTRO),
            Triple("what is arch linux known for", listOf("arch"), PlatformWant.KNOWN),
            Triple("is steamos a gaming os", listOf("steamos"), PlatformWant.TYPE),
            Triple("is linux good for gaming", listOf("linux"), PlatformWant.TYPE),
            Triple("how many people use linux", listOf("linux"), PlatformWant.USERS),
            Triple("how popular is macos", listOf("macos"), PlatformWant.USERS),
            Triple("what devices does chromeos run on", listOf("chromeos"), PlatformWant.PLATFORM),
            Triple("is ubuntu free", listOf("ubuntu"), PlatformWant.PRICE),
            Triple("windows vs mac", listOf("windows", "macos"), PlatformWant.OVERVIEW),
            Triple("linux or windows for gaming", listOf("linux", "windows"), PlatformWant.TYPE),
            Triple("ubuntu vs fedora", listOf("ubuntu", "fedora"), PlatformWant.OVERVIEW),
            Triple("what's the difference between windows 10 and windows 11", listOf("windows10", "windows11"), PlatformWant.OVERVIEW),
            Triple("is android better than ios", listOf("android", "ios"), PlatformWant.OVERVIEW),
            Triple("is macos good for programming", listOf("macos"), PlatformWant.TYPE),
            Triple("can you game on mac", listOf("macos"), PlatformWant.TYPE),
            Triple("is arch hard to use", listOf("arch"), PlatformWant.TYPE),
            Triple("mac or windows for video editing", listOf("macos", "windows"), PlatformWant.TYPE),
            Triple("what is steamos based on", listOf("steamos"), PlatformWant.DISTRO),
            Triple("who owns android", listOf("android"), PlatformWant.PARENT),
        )) {
            val a = ask(s)
            assertEquals(s, ids, a.ids)
            assertEquals(s, want, a.want)
        }
        assertEquals("gaming", ask("linux or windows for gaming").detail)
        assertEquals("programming", ask("is macos good for programming").detail)
    }

    @Test fun leavesOtherThingsAlone() {
        for (s in listOf("is hades on switch", "best ps5 games", "turn on the xbox", "open the windows", "close all the windows", "clean the windows",
                "switch to dark mode", "flip the light switch", "is hades on mac", "can i run elden ring on linux", "cozy games on mac",
                "fedora hat", "buy a ps5", "ps5 deals", "how do i install ubuntu", "play spotify on my ps5", "what's the weather",
                "how much is a mac mini", "iphone 16 vs pixel 9", "is the office on netflix", "elden ring ps5 price", "ps5 vs windows"))
            assertFalse(s, IntentParser.parse(s) is Request.Platform)
    }

    @Test fun answers() {
        val ps5 = Platforms.byId("ps5") as Platforms.Console
        val switch = Platforms.byId("switch") as Platforms.Console
        assertTrue(Platforms.answer(ps5, PlatformWant.MAKER), Platforms.answer(ps5, PlatformWant.MAKER).contains("Sony Group Corporation"))
        assertTrue(Platforms.answer(switch, PlatformWant.PARENT).startsWith("Nintendo has no parent company"))
        assertTrue(Platforms.answer(ps5, PlatformWant.PERFORMANCE).startsWith("The PS5 has a 10.3-teraflop GPU and 16 GB GDDR6 of memory."))
        assertTrue(Platforms.answer(ps5, PlatformWant.EXCLUSIVES).contains("Astro Bot"))
        val linux = (Platforms.byId("linux") as Platforms.Os).copy(liveKernel = "6.17.8")
        assertEquals("Linux uses the Linux kernel; the latest stable version is 6.17.8 (kernel.org).", Platforms.answer(linux, PlatformWant.KERNEL))
        val mint = Platforms.byId("mint") as Platforms.Os
        assertEquals("Linux Mint is a Linux distribution, based on Ubuntu (and Debian, in LMDE).", Platforms.answer(mint, PlatformWant.DISTRO))
        val win = Platforms.byId("windows") as Platforms.Os
        assertTrue(Platforms.answer(win, PlatformWant.DISTRO), Platforms.answer(win, PlatformWant.DISTRO).startsWith("Windows isn't a Linux distribution"))
        assertTrue(Platforms.answer(win, PlatformWant.TYPE), Platforms.answer(win, PlatformWant.TYPE).startsWith("Windows is an all-purpose OS"))
    }

    @Test fun versusAndCards() {
        val ps5 = Platforms.byId("ps5") as Platforms.Console
        val x = Platforms.byId("seriesx") as Platforms.Console
        val v = Platforms.versus(ps5, x)
        assertEquals(1, v.bars.first().winner)
        val said = Platforms.compareAnswer(ps5, x, PlatformWant.PERFORMANCE, null, v)
        assertTrue(said, said.startsWith("The Series X has the stronger GPU: 12.2 teraflops versus 10.3 teraflops for the PS5."))
        val sales = Platforms.compareAnswer(Platforms.byId("ps2")!!, Platforms.byId("ds")!!, PlatformWant.SALES, null,
            Platforms.versus(Platforms.byId("ps2") as Platforms.Console, Platforms.byId("ds") as Platforms.Console))
        assertTrue(sales, sales.startsWith("The PS2 sold more"))
        val linux = Platforms.byId("linux") as Platforms.Os
        val win = Platforms.byId("windows") as Platforms.Os
        val ov = Platforms.versus(linux, win)
        assertFalse(ov.verdict, ov.verdict.contains("The Linux"))
        assertTrue(Platforms.compareAnswer(linux, win, PlatformWant.TYPE, "gaming", ov).startsWith("Windows is better for gaming"))
        for (text in listOf(Infographics.versusText(v, emptyList(), listOf("x")), Infographics.versusText(ov, emptyList(), listOf("x"))) +
                Platforms.CONSOLES.map { Platforms.consoleText(it, Platforms.answer(it, PlatformWant.OVERVIEW)) } +
                Platforms.OSES.map { Platforms.osText(it, Platforms.answer(it, PlatformWant.OVERVIEW)) })
            for (line in text.lines()) assertEquals(line, Box.W + 4, line.length)
    }

    @Test fun liveInfobox() {
        val html = """<table class="infobox hproduct"><tbody><tr><th scope="row" class="infobox-label">Units sold</th><td class="infobox-data">100 million <small>(As of November 10, 2026)</small><sup class="reference"><a href="#cite">&#91;5&#93;</a></sup></td></tr>
            <tr><th scope="row"><a href="/wiki/Software_release_life_cycle">Latest release</a></th><td>26H1 (10.0.28000.3501) (November 10, 2026<span>; 6 days ago</span> (<span>2026-11-10</span>))</td></tr></tbody></table>"""
        val box = Platforms.infoboxRows(html)
        assertEquals("100 million (as of November 10, 2026)", Platforms.soldOf(box["units sold"]!!))
        assertEquals("26H1 (10.0.28000.3501) (November 10, 2026)", Platforms.latestOf(box["latest release"]!!))
        assertEquals("26H1 (10.0.28000.3501) (November 10, 2026)", Platforms.latestOf("26H1 (10.0.28000.3501) (November 10, 2026) [±] 26H2 (10.0.26300.9974) (November 10, 2026)"))
        assertEquals("26.35 million (as of September 30, 2026)", Platforms.soldOf("26.35 million as of September 30, 2026 [update]"))
        val ps5 = Platforms.CONSOLES.first { it.id == "ps5" }.copy(liveSold = "100 million (as of November 10, 2026)")
        assertTrue(Platforms.consoleText(ps5, null).contains("100 million"))
    }
}

class BriefTest {
    @Test fun keepsUnitShort() {
        assertEquals("Short answer.", Brief.spoken("Short answer."))
        val long = "The Switch 2's CPU is an 8-core Arm Cortex-A78C (Nvidia T239), and its GPU is an Nvidia Ampere, 1,536 CUDA cores, with DLSS and ray tracing, 3.1 teraflops. It has 12 GB."
        val s = Brief.spoken(long)
        assertTrue(s, s.length <= 140 && s.endsWith("."))
        assertEquals("Windows is better for gaming. It runs nearly every PC game.", Brief.spoken("Windows is better for gaming. It runs nearly every PC game. Then " + "x".repeat(120) + "."))
        assertEquals("Super Smash Bros. Ultimate sold the most on the Switch, by a wide margin over everything else Nintendo put out that generation.",
            Brief.spoken("Super Smash Bros. Ultimate sold the most on the Switch, by a wide margin over everything else Nintendo put out that generation. Then Mario Kart 8 Deluxe."))
    }
}
