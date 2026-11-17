package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeripheralsTest {
    private fun ask(s: String) = Peripherals.parse(s)

    private fun want(s: String, id: String, w: GearWant) {
        val a = ask(s)
        assertNotNull(s, a)
        assertEquals(s, listOf(id), a!!.ids)
        assertEquals(s, w, a.want)
    }

    @Test fun names() {
        want("what is the logitech g915 tkl", "g915tkl", GearWant.OVERVIEW)
        want("tell me about the g915", "g915", GearWant.OVERVIEW)
        want("what's the superlight 2", "superlight2", GearWant.OVERVIEW)
        want("what is the gpx 2", "superlight2", GearWant.OVERVIEW)
        want("g pro x superlight", "superlight", GearWant.OVERVIEW)
        want("what is the g502 x plus", "g502xplus", GearWant.OVERVIEW)
        want("what is a g502", "g502", GearWant.OVERVIEW)
        want("what is an mx master 3s", "mxmaster3s", GearWant.OVERVIEW)
        want("what is a stream deck", "streamdeck", GearWant.OVERVIEW)
        want("what is the stream deck plus", "streamdeckplus", GearWant.OVERVIEW)
        want("what is the shure sm7b", "sm7b", GearWant.OVERVIEW)
        want("what is the sm7db", "sm7db", GearWant.OVERVIEW)
        want("tell me about the blue yeti", "yeti", GearWant.OVERVIEW)
        want("what is the wooting 80he", "wooting80he", GearWant.OVERVIEW)
        want("what's the razer blackwidow v4 75%", "bwv475", GearWant.OVERVIEW)
        want("what is the corsair k70 rgb pro", "k70pro", GearWant.OVERVIEW)
    }

    @Test fun keyboardWants() {
        want("what switches does the g915 tkl have", "g915tkl", GearWant.SWITCHES)
        want("what keycaps come on the keychron q1 pro", "q1pro", GearWant.KEYCAPS)
        want("is the k65 plus hot swappable", "k65plus", GearWant.HOTSWAP)
        want("can you swap the switches on the g915", "g915", GearWant.HOTSWAP)
        want("is the q1 pro programmable", "q1pro", GearWant.PROGRAM)
        want("is the blackwidow v4 pro wireless", "bwv4pro", GearWant.WIRELESS)
        want("how long does the g915 tkl battery last", "g915tkl", GearWant.BATTERY)
        want("what's the polling rate of the wooting 80he", "wooting80he", GearWant.POLLING)
        want("whats the max pulling rate on the k70 max", "k70max", GearWant.POLLING)
        want("does the azoth have bluetooth", "azoth", GearWant.CONNECTION)
    }

    @Test fun mouseWants() {
        want("what side buttons does the mx master 3s have", "mxmaster3s", GearWant.SIDE)
        want("does the g502 x plus have infinite scroll", "g502xplus", GearWant.WHEEL)
        want("what scroll wheel modes does the basilisk v3 pro have", "basiliskv3pro", GearWant.WHEEL)
        want("how much does the viper v3 pro weigh", "viperv3pro", GearWant.WEIGHT)
        want("what sensor is in the deathadder v3 pro", "dav3pro", GearWant.SENSOR)
        want("how many buttons does the naga v2 pro have", "nagav2pro", GearWant.BUTTONS)
        want("what's the polling rate of the superlight 2", "superlight2", GearWant.POLLING)
    }

    @Test fun otherWants() {
        want("is the sm7b a dynamic or condenser mic", "sm7b", GearWant.MIC_TYPE)
        want("what polar patterns does the blue yeti have", "yeti", GearWant.PATTERN)
        want("how many keys does the stream deck xl have", "streamdeckxl", GearWant.KEYS)
        want("how does the sm7b connect", "sm7b", GearWant.CONNECTION)
    }

    @Test fun compare() {
        val a = ask("superlight 2 vs viper v3 pro")!!
        assertEquals(listOf("superlight2", "viperv3pro"), a.ids)
        assertEquals(listOf("g915tkl", "k70pro"), ask("g915 tkl or k70 rgb pro")!!.ids)
        // A keyboard and a mouse don't compare.
        assertEquals(1, ask("g915 tkl and g502")!!.ids.size)
        GearLive.enabled = false
        val r = kotlinx.coroutines.runBlocking { Peripherals.find(a) }!!
        assertNotNull(r.versus)
        assertTrue(r.answer!!, r.answer!!.contains("Viper V3 Pro"))
    }

    @Test fun notGear() {
        assertNull(ask("buy a g502"))
        assertNull(ask("my g502 is double clicking"))
        assertNull(ask("how do i connect my mx master 3s"))
        assertNull(ask("what is a yeti"))
        assertNull(ask("what is aws ec2"))
        assertNull(ask("what's the weather"))
        assertNull(ask("what is apex legends"))
        assertNull(ask("how venomous is a viper"))
        assertNull(ask("what is the razer blade 16"))
        assertNull(ask("is the razer kraken good"))
        assertNull(ask("who founded logitech"))
    }

    @Test fun unlisted() {
        assertEquals(listOf("live|Logitech|mx master 4"), ask("what is the mx master 4")!!.ids)
        want("what is the razer cobra pro", "live|Razer|cobra pro", GearWant.OVERVIEW)
        want("is the hyperx pulsefire haste 2 wireless", "live|HyperX|pulsefire haste 2", GearWant.WIRELESS)
        want("what's the polling rate of the logitech g pro x2 superstrike", "live|Logitech|pro x2 superstrike", GearWant.POLLING)
        want("how much does the viper v2 pro weigh", "live|Razer|viper v2 pro", GearWant.WEIGHT)
        want("keychron q6 max switches", "live|Keychron|q6 max", GearWant.SWITCHES)
        assertEquals(listOf("live|Logitech|mx master 4", "mxmaster3s"), ask("mx master 4 vs mx master 3s")!!.ids)
        assertEquals("Razer Cobra PRO", Peripherals.label("live|Razer|cobra pro").replace("Pro", "PRO"))
    }

    @Test fun liveUrls() {
        val urls = listOf("https://www.logitech.com/en-us/shop/p/mx-master-4-business", "https://www.logitech.com/en-us/shop/p/mx-master-4",
            "https://www.logitech.com/en-us/shop/p/mx-master-3s", "https://www.logitechg.com/en-us/shop/p/pro-x-superlight-2c",
            "https://www.logitechg.com/en-us/shop/p/pro-x2-superlight-wireless-mouse", "https://www.logitechg.com/en-us/shop/p/g915-x-tkl-wireless")
        assertEquals(urls[1], GearLive.bestUrl("mx master 4", urls))
        assertEquals(urls[2], GearLive.bestUrl("mx master 3s", urls))
        assertEquals(urls[4], GearLive.bestUrl("pro x superlight 2", urls))
        assertNull(GearLive.bestUrl("g915 tkl", urls))
        assertEquals("https://www.razer.com/gaming-mice/razer-cobra-pro/RZ01-04660100-R3U1",
            GearLive.bestUrl("cobra pro", listOf("https://www.razer.com/gaming-mice/razer-cobra/RZ01-04650100-R3U1", "https://www.razer.com/gaming-mice/razer-cobra-pro/RZ01-04660100-R3U1")))
    }

    @Test fun liveCorsairElgatoShure() {
        want("what is the corsair m75", "live|Corsair|m75", GearWant.OVERVIEW)
        want("what's the polling rate of the corsair k70 core", "live|Corsair|k70 core", GearWant.POLLING)
        want("what is the elgato stream deck studio", "live|Elgato|stream deck studio", GearWant.OVERVIEW)
        want("what is the wave neo", "live|Elgato|wave neo", GearWant.OVERVIEW)
        want("what is the shure sm57", "live|Shure|sm57", GearWant.OVERVIEW)
        want("is the mv7x dynamic", "live|Shure|mv7x", GearWant.MIC_TYPE)
        want("what is the stream deck plus", "streamdeckplus", GearWant.OVERVIEW)
        want("what is the stream deck mk.2", "streamdeck", GearWant.OVERVIEW)
        assertNull(ask("how much is the corsair vengeance ddr5 ram"))
        assertNull(ask("what is the elgato facecam"))

        val cor = listOf("https://www.corsair.com/us/en/p/gaming-mouse/ch-931d01u-na/m75-wireless-lightweight-rgb-gaming-mouse-cyberpunk-2077-arasaka-white-edition-ch-931d01u-na",
            "https://www.corsair.com/us/en/p/gaming-mouse/ch-931d010-na/m75-wireless-lightweight-rgb-gaming-mouse-black-ch-931d010-na",
            "https://www.corsair.com/us/en/p/gaming-mouse/ch-931d101-na/m75-air-wireless-ultra-lightweight-gaming-mouse-white-ch-931d101-na",
            "https://www.corsair.com/us/en/p/keyboards/ch-910941a-na/k70-pro-rgb-optical-mechanical-gaming-keyboard-with-pbt-double-shot-pro-keycaps-ch-910941a-na",
            "https://www.corsair.com/us/en/p/keyboards/ch-9109412-na/k70-rgb-pro-mechanical-gaming-keyboard-with-pbt-double-shot-pro-keycaps-cherry-mx-brown-ch-9109412-na")
        assertEquals(cor[1], GearLive.bestUrl("m75", cor))
        assertEquals(cor[2], GearLive.bestUrl("m75 air", cor))
        assertEquals(cor[4], GearLive.bestUrl("k70 rgb pro", cor))
        val elg = listOf("https://staging-pwa.elgato.com/us/en/p/stream-deck-mk-2-fallout-edition", "https://staging-pwa.elgato.com/us/en/p/stream-deck-mk2-forest-green-faceplate",
            "https://staging-pwa.elgato.com/us/en/p/stream-deck", "https://staging-pwa.elgato.com/us/en/p/wave-3-white", "https://staging-pwa.elgato.com/us/en/p/wave-3-pink-petal")
        assertNull(GearLive.bestUrl("stream deck mk.2", elg))
        assertEquals(elg[2], GearLive.bestUrl("stream deck", elg))
        assertEquals(elg[3], GearLive.bestUrl("wave 3", elg))
        assertEquals(listOf("sm7b"), GearLive.slugs("SM7B"))
        assertEquals(listOf("mv7plus", "mv7-plus", "mv7_plus"), GearLive.slugs("MV7+"))
    }

    @Test fun liveMicPages() {
        // Elgato: the product's own spec table wins over the rest of the page (which names the Wave XLR everywhere).
        val row = { p: String, h: String, v: String -> "{\"title\":\"Audio / Comparison / $p / AUDIO / $h\",\"headline\":\"$h\",\"tooltip\":\"x\",\"featureInfo\":\"$v\",\"meta\":{}}" }
        val wave = """<meta property="og:title" content="Wave:3 MK.2"><p>Wave XLR. XLR. XLR. Wave XLR. Bluetooth.</p>""" +
            row("Wave:3", "Capsule type", "Condenser") + row("Wave:3", "Polar pattern", "Cardioid") + row("Wave:3", "Connection", "USB-C") + row("Wave:3", "Bit depth", "24-bit") + row("Wave:3", "Sample rate", "48 kHz") +
            row("Wave:3 MK.2", "Max SPL", "130 dB") + row("Wave DX", "Capsule type", "Dynamic") + row("Wave DX", "Connection", "XLR")
        val w = GearLive.read("https://www.elgato.com/us/en/p/wave-3", wave)
        assertEquals(Peripherals.Kind.MIC, w.kind)
        assertEquals("Condenser", w.micType)
        assertEquals("Cardioid", w.pattern)
        assertEquals(listOf("Wired (USB-C)"), w.connections)
        assertNull(w.audio) // the MK.2's own table doesn't give it, and the older Wave:3's numbers may not hold
        val neo = """<meta property="og:title" content="Stream Deck Neo">""" + row("Stream Deck Neo", "Keys", "8") + row("Stream Deck XL", "Keys", "32")
        assertEquals(8, GearLive.read("https://www.elgato.com/us/en/p/stream-deck-neo", neo).keys)
        // Shure: og:title is only "Vocal Microphone", and the page names its other mics' plugs and patterns a few times.
        val sm7b = """<meta property="og:title" content="Vocal Microphone"><title data-next-head="">SM7B - Vocal Microphone - Shure USA</title>
            <p>Dynamic microphone, cardioid. XLR. XLR. XLR. XLR. 3-pin XLR. Cardioid. Cardioid. Cardioid.</p><p>Also see: MV7+ with USB-C and Bluetooth, supercardioid.</p>"""
        val s = GearLive.read("https://www.shure.com/en-US/products/microphones/sm7b", sm7b)
        assertEquals("SM7B", s.title)
        assertEquals("Dynamic", s.micType)
        assertEquals("Cardioid", s.pattern)
        assertEquals(listOf("XLR"), s.connections)
        // The SM57 page names a condenser mic first (a related product) but says dynamic most.
        val sm57 = GearLive.read("https://www.shure.com/en-US/products/microphones/sm57",
            "<title>SM57 - Instrument Microphone - Shure USA</title><p>Instrument condenser microphone. Dynamic microphone. Cardioid dynamic microphone. Dynamic mic. XLR.</p>")
        assertEquals("Dynamic", sm57.micType)
        // No spec table: the title says USB, so the Wave XLR named all over Elgato's menus isn't how the Wave Neo connects.
        val wn = GearLive.read("https://www.elgato.com/us/en/p/wave-neo", """<meta property="og:title" content="Wave Neo | USB Condenser Microphone">
            <p>Wave XLR. XLR. XLR. XLR. USB-C. USB-C. Cardioid condenser microphone. Cardioid. 3.5 mm stereo headphone jack.</p>""")
        assertEquals(listOf("Wired (USB-C)"), wn.connections)
        assertEquals("Cardioid", wn.pattern)
        // Corsair: the switch named in the title, not the one other keyboards on the page use.
        val k70 = """<meta property="og:title" content="K70 RGB PRO Mechanical Gaming Keyboard with PBT DOUBLE SHOT PRO Keycaps — CHERRY® MX Brown">
            <p>OPX switch. OPX switch. OPX switch. 8,000 Hz polling rate. USB Type-C. iCUE.</p>"""
        val k = GearLive.read("https://www.corsair.com/us/en/p/keyboards/ch-9109412-na/k70-rgb-pro-mechanical-gaming-keyboard", k70)
        assertEquals("K70 RGB PRO Mechanical Gaming Keyboard", k.title)
        assertEquals("Cherry MX Brown", k.switches)
        assertEquals(8000, k.polling)
    }

    @Test fun livePage() {
        val html = """<html><head><meta property="og:title" content="Razer Cobra Pro - Black"><script type="application/ld+json">{"@type":"Product","offers":{"price":"129.99"}}</script></head>
            <body><h3>UP TO 100 HOURS OF BATTERY LIFE</h3><p>Focus Pro 30K optical sensor with 30,000 DPI. Weight: 77 g.</p>
            <p>HyperSpeed Wireless, Bluetooth and USB Type-C. Polling rate: up to 8000 Hz with HyperPolling. Customize in Razer Synapse.</p>
            <p>10 programmable buttons</p></body></html>"""
        val f = GearLive.read("https://www.razer.com/gaming-mice/razer-cobra-pro/RZ01", html)
        assertEquals("Razer Cobra Pro", f.title)
        assertEquals(129.99, f.price!!, 0.001)
        assertEquals(Peripherals.Kind.MOUSE, f.kind)
        assertEquals(8000, f.polling)
        assertEquals("up to 100 hours", f.battery)
        assertEquals(30000, f.dpi)
        assertEquals(77.0, f.weightG!!, 0.1)
        assertEquals("Focus Pro 30K", f.sensor)
        assertEquals(10, f.buttons)
        assertEquals(listOf("2.4 GHz wireless (USB receiver)", "Bluetooth", "Wired (USB-C)"), f.connections)
        assertEquals(listOf("Razer Synapse"), f.software)
    }

    @Test fun answers() {
        fun say(s: String) = Peripherals.answer(Peripherals.byId(ask(s)!!.ids[0])!!, ask(s)!!.want)
        assertEquals("The G915 TKL Lightspeed uses Logitech GL low-profile mechanical switches: Tactile, Linear or Clicky.", say("what switches does the g915 tkl have"))
        assertTrue(say("is the k65 plus hot swappable").startsWith("Yes, the K65 Plus Wireless is hot-swappable"))
        assertTrue(say("is the g915 hot swappable").startsWith("No, the G915 Lightspeed isn't hot-swappable"))
        assertEquals("The G915 TKL Lightspeed lasts up to 40 hours with lighting at full brightness.", say("how long does the g915 tkl battery last"))
        assertTrue(say("is the blackwidow v4 pro wireless"), say("is the blackwidow v4 pro wireless").startsWith("No, the BlackWidow V4 Pro is wired only"))
        assertTrue(say("is the azoth wireless"), say("is the azoth wireless").contains("Bluetooth"))
        assertTrue(say("does the g502 x plus have infinite scroll").contains("infinite free-spin"))
        assertTrue(say("what side buttons does the mx master 3s have").contains("thumb scroll wheel"))
        assertEquals("The SM7B is a dynamic microphone.", say("is the sm7b a dynamic or condenser mic"))
        assertTrue(say("what is the g502").startsWith("The G502 Hero is a wired gaming mouse from 2018"))
        assertTrue(say("polling rate of the mx keys s").contains("doesn't publish"))
    }

    @Test fun cards() {
        for (d in Peripherals.DEVICES) {
            val t = Peripherals.text(d, Peripherals.answer(d, GearWant.OVERVIEW))
            assertTrue(d.id, t.contains("CONNECTS BY"))
        }
        val kb = Peripherals.text(Peripherals.byId("g915tkl")!!, null)
        for (l in listOf("SWITCH", "KEYCAPS", "HOTSWAP", "PROGRAM", "WIRELESS", "BATTERY", "POLLING")) assertTrue(l, kb.contains(l))
        val m = Peripherals.text(Peripherals.byId("g502xplus")!!, null)
        for (l in listOf("SIDE", "WHEEL", "SENSOR", "WEIGHT")) assertTrue(l, m.contains(l))
    }

    @Test fun routing() {
        assertTrue(IntentParser.parse("what switches does the g915 tkl have") is Request.Peripheral)
        assertTrue(IntentParser.parse("superlight 2 vs viper v3 pro") is Request.Peripheral)
        assertTrue(IntentParser.parse("what is a stream deck") is Request.Peripheral)
    }

    /** The Wave:3 card once read "an USB condenser streaming microphone". */
    @Test fun articles() {
        assertEquals("a", Peripherals.an("USB condenser"))
        assertEquals("an", Peripherals.an("RGB"))
        assertEquals("an", Peripherals.an("optical"))
        assertEquals("a", Peripherals.an("wireless"))
        assertEquals("an", Peripherals.an("8K"))
    }
}
