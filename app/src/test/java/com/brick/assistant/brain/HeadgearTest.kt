package com.brick.assistant.brain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeadgearTest {
    @Before fun offline() { GearLive.enabled = false }

    private fun ask(q: String) = Headgear.parse(q)
    private fun route(q: String) = IntentParser.parse(q)::class.java.simpleName

    @Test fun everyDeviceHasAName() {
        for (d in Headgear.DEVICES) {
            val hit = ask(d.name)
            assertEquals("\"${d.name}\" should name ${d.id}", d.id, hit?.ids?.firstOrNull())
        }
    }

    @Test fun headphonesAndEarbuds() {
        fun check(q: String, ids: List<String>, want: HeadWant) {
            val a = ask(q)
            assertNotNull("\"$q\" wasn't read", a)
            assertEquals("\"$q\"", ids, a!!.ids)
            assertEquals("\"$q\"", want, a.want)
        }
        check("sony xm5 battery life", listOf("xm5"), HeadWant.BATTERY)
        check("wh-1000xm5 vs wh-1000xm4", listOf("xm5", "xm4"), HeadWant.OVERVIEW)
        check("do the airpods pro 2 have noise cancelling", listOf("airpodspro2"), HeadWant.ANC)
        check("airpods pro", listOf("airpodspro3"), HeadWant.OVERVIEW)
        check("airpods", listOf("airpods4"), HeadWant.OVERVIEW)
        check("airpods max weight", listOf("airpodsmax"), HeadWant.WEIGHT)
        check("are the galaxy buds 3 pro waterproof", listOf("budspro3"), HeadWant.WATER)
        check("sennheiser hd 600 impedance", listOf("hd600"), HeadWant.IMPEDANCE)
        check("is the hd 650 open back", listOf("hd650"), HeadWant.FIT)
        check("does the m50x need an amp", listOf("m50x"), HeadWant.IMPEDANCE)
        check("what codecs does the wf-1000xm5 support", listOf("wf1000xm5"), HeadWant.CODECS)
        check("sony wf-1000xm5 vs airpods pro 3", listOf("wf1000xm5", "airpodspro3"), HeadWant.OVERVIEW)
        check("bose qc ultra earbuds vs bose qc ultra headphones", listOf("qcultrabuds", "qcultra"), HeadWant.OVERVIEW)
        check("are the pixel buds pro 2 good for running", listOf("pixelbudspro2"), HeadWant.WATER)
        check("does the dt 770 pro have a mic", listOf("dt770"), HeadWant.MIC)
        check("are earpods wired", listOf("earpods"), HeadWant.WIRELESS)
    }

    @Test fun gamingHeadsets() {
        assertEquals(listOf("novaprowl", "bsv2pro"), ask("arctis nova pro wireless vs blackshark v2 pro")!!.ids)
        assertEquals(HeadWant.CONNECTION, ask("does the g pro x 2 have bluetooth")!!.want)
        assertEquals(listOf("gprox2"), ask("does the g pro x 2 have bluetooth")!!.ids)
        assertEquals(HeadWant.MIC, ask("hyperx cloud iii mic")!!.want)
        assertEquals(listOf("cloud3"), ask("hyperx cloud iii mic")!!.ids)
        assertEquals(listOf("cloud3wl"), ask("cloud iii wireless battery")!!.ids)
        assertEquals(listOf("hs80"), ask("corsair hs80 rgb wireless")!!.ids)
        assertEquals(listOf("a50x"), ask("astro a50 x")!!.ids)
        assertEquals(HeadWant.DRIVERS, ask("what drivers does the audeze maxwell use")!!.want)
    }

    @Test fun vr() {
        assertEquals(HeadWant.RESOLUTION, ask("quest 3 resolution")!!.want)
        assertEquals(listOf("quest3"), ask("meta quest 3 resolution")!!.ids)
        assertEquals(listOf("quest3s"), ask("quest 3s battery")!!.ids)
        assertEquals(HeadWant.STANDALONE, ask("does the quest 3 need a pc")!!.want)
        assertEquals(HeadWant.PC, ask("can the quest 3 do pc vr")!!.want)
        assertEquals(HeadWant.REFRESH, ask("valve index refresh rate")!!.want)
        assertEquals(HeadWant.STANDALONE, ask("is the valve index wireless")!!.want)
        assertEquals(listOf("quest3", "visionpro"), ask("quest 3 vs vision pro")!!.ids)
        assertEquals(HeadWant.FOV, ask("psvr2 field of view")!!.want)
        assertEquals(listOf("psvr2"), ask("playstation vr2 price")!!.ids)
        assertEquals(HeadWant.TRACKING, ask("does the bigscreen beyond 2 use base stations")!!.want)
        assertEquals(listOf("galaxyxr"), ask("samsung galaxy xr specs")!!.ids)
        // Sound gear and VR don't compare: just the first.
        assertEquals(listOf("quest3"), ask("quest 3 vs airpods pro")!!.ids)
    }

    @Test fun unlistedOnesGoLive() {
        assertEquals(listOf("live|Sony|wf-1000xm4"), ask("sony wf-1000xm4")!!.ids)
        assertEquals(listOf("live|SteelSeries|arctis nova 5"), ask("arctis nova 5 battery")!!.ids)
        assertEquals(HeadWant.BATTERY, ask("arctis nova 5 battery")!!.want)
        assertEquals(listOf("live|Pico|4 ultra"), ask("pico 4 ultra specs")!!.ids)
        assertEquals(listOf("live|Apple|airpods 3"), ask("airpods 3 battery life")!!.ids)
        assertEquals("Sony WF-1000XM4", Headgear.label("live|Sony|wf-1000xm4"))
    }

    @Test fun routing() {
        for (q in listOf("sony xm5 battery life", "airpods pro 2 vs galaxy buds 3 pro", "quest 3 resolution", "is the vision pro worth it", "g pro x 2 weight",
                "corsair hs80 battery", "psvr2 price", "sennheiser hd 600", "pixel buds pro 2 water resistance", "arctis nova 5 battery", "playstation vr2 field of view"))
            assertEquals("\"$q\"", "Headgear", route(q))
        // Games on a headset, trouble, how-to: not spec questions.
        for (q in listOf("can i play beat saber on quest 3", "best quest 3 games", "how do i pair my airpods", "my airpods won't connect", "is beat saber on quest 3",
                "where are my airpods", "play music on my airpods"))
            assertFalse("\"$q\" went to Headgear", route(q) == "Headgear")
        // Keyboards, mice and phones stay where they were.
        assertEquals("Peripheral", route("logitech g pro x superlight 2 weight"))
        assertEquals("Peripheral", route("g502 dpi"))
        assertEquals("Tech", route("galaxy s25 battery"))
        assertEquals("Tech", route("what is anc"))
        assertEquals("Tech", route("what is passthrough"))
        assertEquals("Tech", route("open back vs closed back headphones"))
        assertEquals("Tech", route("what is ldac"))
    }

    @Test fun answers() {
        val d = { id: String -> Headgear.byId(id)!! }
        assertTrue(Headgear.answer(d("xm5"), HeadWant.BATTERY).contains("30 hours"))
        assertEquals("The WH-1000XM5 is a pair of wireless noise-cancelling over-ear headphones from 2022 with up to 30 hours of battery. It launched at \$399.99.",
            Headgear.answer(d("xm5"), HeadWant.OVERVIEW))
        assertEquals("The AirPods Pro 3 are a pair of wireless noise-cancelling earbuds from 2025 with up to 8 hours of battery (24 with the case). They launched at \$249.",
            Headgear.answer(d("airpodspro3"), HeadWant.OVERVIEW))
        assertTrue(Headgear.answer(d("hd600"), HeadWant.ANC).startsWith("No, the HD 600 doesn't have active noise cancelling"))
        assertEquals("The AirPods Pro 3 are rated IP57 (buds and case) for water and sweat.", Headgear.answer(d("airpodspro3"), HeadWant.WATER))
        assertEquals("Yes, the AirPods Pro 2 have active noise cancelling, with Transparency and Adaptive Audio.", Headgear.answer(d("airpodspro2"), HeadWant.ANC))
        assertEquals("The Quest 3 shows 2064 x 2208 pixels per eye on two LCD panels.", Headgear.answer(d("quest3"), HeadWant.RESOLUTION))
        assertTrue(Headgear.answer(d("index"), HeadWant.STANDALONE).startsWith("No, the Index needs a gaming PC."))
        assertTrue(Headgear.answer(d("quest3"), HeadWant.STANDALONE).startsWith("Yes, the Quest 3 runs on its own"))
        assertEquals("The Maxwell uses 90 mm planar magnetic drivers.", Headgear.answer(d("maxwell"), HeadWant.DRIVERS))
        assertEquals("The HD 600 sells for \$399.95 (list price).", Headgear.answer(d("hd600"), HeadWant.PRICE))
        assertEquals("The DT 770 Pro sells for about \$160.", Headgear.answer(d("dt770"), HeadWant.PRICE))
        assertTrue(Headgear.answer(d("m50x"), HeadWant.WIRELESS).startsWith("No, the ATH-M50x is wired"))
        assertTrue(Headgear.answer(d("airpodspro3"), HeadWant.BATTERY).contains("or up to 24 hours with the case"))
    }

    @Test fun comparing() = runBlocking {
        val r = Headgear.find(HeadAsk(listOf("xm5", "xm4"), HeadWant.OVERVIEW, "xm5 vs xm4"))!!
        val v = r.versus!!
        assertEquals(setOf("BATTERY", "WEIGHT", "PRICE"), v.bars.map { it.label }.toSet())
        assertEquals(0, v.bars.first { it.label == "WEIGHT" }.winner)
        val vr = Headgear.versus(Headgear.byId("quest3")!!, Headgear.byId("visionpro")!!)
        assertEquals(1, vr.bars.first { it.label == "PIXELS/EYE" }.winner)
        assertEquals(0, vr.bars.first { it.label == "PRICE" }.winner)
        assertTrue(vr.words.any { it.label == "CHIP" })
    }

    @Test fun cards() = runBlocking {
        val r = Headgear.find(HeadAsk(listOf("quest3"), HeadWant.RESOLUTION, "quest 3 resolution"))!!
        assertEquals("VR HEADSET", r.heading)
        val card = Headgear.text(r.headgear!!, r.answer)
        assertTrue(card.contains("VR HEADSET"))
        assertTrue(card.contains("2064 x 2208"))
        assertTrue(card.contains("Snapdragon XR2"))
        val buds = Headgear.text(Headgear.byId("airpodspro3")!!, null)
        assertTrue(buds.contains("EARBUDS"))
        assertTrue(buds.contains("IP57"))
        assertTrue(buds.contains("CASE   Up to 24 hours"))
        val wired = Headgear.text(Headgear.byId("hd600")!!, null)
        assertTrue(wired.contains("None (wired)"))
        assertTrue(wired.contains("(list)"))
    }

    @Test fun speakers() {
        fun check(q: String, ids: List<String>, want: HeadWant) {
            val a = ask(q)
            assertNotNull("\"$q\" wasn't read", a)
            assertEquals("\"$q\"", ids, a!!.ids)
            assertEquals("\"$q\"", want, a.want)
        }
        check("is the jbl flip 6 waterproof", listOf("flip6"), HeadWant.WATER)
        check("jbl charge 5 vs flip 6", listOf("charge5", "flip6"), HeadWant.OVERVIEW)
        check("how loud is the jbl charge 6", listOf("charge6"), HeadWant.LOUD)
        check("can the jbl charge 5 charge my phone", listOf("charge5"), HeadWant.POWERBANK)
        check("does the wonderboom 4 float", listOf("wonderboom4"), HeadWant.WATER)
        check("sonos roam 2 battery life", listOf("roam2"), HeadWant.BATTERY)
        check("bose soundlink flex vs jbl flip 7", listOf("flex2", "flip7"), HeadWant.OVERVIEW)
        check("can you stereo pair the jbl clip 5", listOf("clip5"), HeadWant.PARTY)
        check("marshall emberton ii weight", listOf("emberton2"), HeadWant.WEIGHT)
        // A speaker and earbuds don't compare.
        check("jbl flip 6 vs airpods pro", listOf("flip6"), HeadWant.OVERVIEW)
        assertEquals(listOf("live|JBL|xtreme 4"), ask("jbl xtreme 4 battery")!!.ids)
        assertEquals(listOf("live|Ultimate Ears|boom 3"), ask("ue boom 3 battery")!!.ids)
        assertEquals(listOf("live|Anker|soundcore motion 300"), ask("soundcore motion 300")!!.ids)
        assertEquals("Ultimate Ears Boom 3", Headgear.label("live|Ultimate Ears|boom 3"))
    }

    @Test fun iemsSoundAndScores() {
        assertEquals(listOf("chu2"), ask("moondrop chu 2 impedance")!!.ids)
        assertEquals(HeadWant.IMPEDANCE, ask("moondrop chu 2 impedance")!!.want)
        assertEquals(listOf("zeroblue2"), ask("truthear zero blue2")!!.ids)
        assertEquals(listOf("live|Moondrop|blessing 3"), ask("moondrop blessing 3")!!.ids)
        assertEquals(listOf("live|7Hz|salnotes zero 2"), ask("7hz salnotes zero 2")!!.ids)
        assertEquals(HeadWant.SCORE, ask("is the sony xm5 worth it")!!.want)
        assertEquals(HeadWant.SCORE, ask("sony wh-1000xm6 review")!!.want)
        assertEquals(HeadWant.SOUND, ask("how do the hd 600 sound")!!.want)
        assertEquals(HeadWant.SOUND, ask("does the xm5 have good bass")!!.want)
        assertEquals(HeadWant.WATER, ask("airpods pro 3 ip rating")!!.want)
        // The phone's own readout, not a spec: "my" right before the device.
        assertNull(ask("what's the battery on my airpods"))
    }

    @Test fun speakerAnswers() {
        val d = { id: String -> Headgear.byId(id)!! }
        assertEquals("The Flip 6 is a portable Bluetooth speaker from 2021 with up to 12 hours of battery. It launched at \$129.95.", Headgear.answer(d("flip6"), HeadWant.OVERVIEW))
        assertEquals("The Flip 6 is rated IP67 (dust-tight, survives a dunk).", Headgear.answer(d("flip6"), HeadWant.WATER))
        assertTrue(Headgear.answer(d("wonderboom4"), HeadWant.WATER).endsWith(", and it floats."))
        assertEquals("Yes, the Charge 5 charges your phone from its USB-A port.", Headgear.answer(d("charge5"), HeadWant.POWERBANK))
        assertEquals("No, the Flip 6 can't charge other devices.", Headgear.answer(d("flip6"), HeadWant.POWERBANK))
        assertEquals("The Flip 6 puts out 30 W (20 W woofer, 10 W tweeter).", Headgear.answer(d("flip6"), HeadWant.LOUD))
        assertTrue(Headgear.answer(d("clip5"), HeadWant.PARTY).startsWith("The Clip 5: auracast links it"))
        val v = Headgear.versus(d("charge5"), d("flip6"))
        assertEquals(setOf("BATTERY", "POWER", "WEIGHT", "PRICE"), v.bars.map { it.label }.toSet())
        assertTrue(v.words.any { it.label == "EXTRAS" })
        val card = Headgear.text(d("soundlinkmax"), null)
        assertTrue(card.contains("SPEAKER"))
        assertTrue(card.contains("2.1 kg"))
        assertTrue(card.contains("EXTRAS"))
    }

    /** SoundGuys' product pages: the catalog address, then the structured specs, score and price (no review text). */
    @Test fun soundGuys() {
        val urls = listOf("apple-airpods-pro-3rd-gen", "esr-case-for-airpods-pro-3", "apple-airpods-4", "apple-airpods-4-with-active-noise-cancellation",
            "bose-soundlink-flex", "bose-soundlink-flex-2nd-gen", "sony-wf-1000xm4", "sony-wf-1000xm5", "spigen-rugged-armor-for-airpods-pro-3").map { "https://www.soundguys.com/product/$it/" }
        fun best(maker: String, model: String) = SoundGuys.bestUrl(maker, model, urls)?.substringAfter("/product/")?.trimEnd('/')
        assertEquals("apple-airpods-pro-3rd-gen", best("Apple", "airpods pro 3"))
        assertEquals("apple-airpods-4", best("Apple", "AirPods 4"))
        assertEquals("bose-soundlink-flex-2nd-gen", best("Bose", "SoundLink Flex (2nd Gen)"))
        assertEquals("bose-soundlink-flex", best("Bose", "soundlink flex"))
        assertEquals("sony-wf-1000xm4", best("Sony", "wf-1000xm4"))
        assertNull(best("Sony", "wf-1000xm3"))

        val pv = { n: String, v: String -> "{\"@type\":\"PropertyValue\",\"name\":\"$n\",\"value\":\"$v\"}" }
        val html = "<script type=\"application/ld+json\">{\"@graph\":[{\"@type\":\"Product\",\"@id\":\"x#product\",\"name\":\"Sony WF-1000XM4\",\"brand\":{\"@type\":\"Brand\",\"name\":\"Sony\"}," +
            "\"category\":\"Electronics > Audio > Earbuds\",\"additionalProperty\":[" + listOf(pv("Battery Life Earbuds", "12"), pv("Battery Life With Case", "24"),
            pv("Battery Life Anc On", "8"), pv("IP Rating", "IPX4"), pv("Noise Cancellation Type", "active"), pv("Bluetooth Version", "5.2"), pv("Bluetooth Codec", "AAC, LDAC, SBC"),
            pv("Connection Type", "bluetooth"), pv("Multipoint Devices", "yes - 2 devices"), pv("Weight Per Earbud", "7.3"), pv("Msrp Usd", "280"), pv("Year", "2021"),
            pv("Ambient Mode", "yes, adaptive transparency"), pv("Driver Size", "6"), pv("Driver Type", "dynamic"), pv("Frequency Response Signature", "V-shaped"),
            pv("Frequency Response", "20-20000"), pv("Microphone", "true"), pv("Count", "4")).joinToString(",") +
            "],\"review\":{\"reviewRating\":{\"@type\":\"Rating\",\"ratingValue\":8.3,\"bestRating\":10}},\"offers\":{\"@type\":\"Offer\",\"price\":\"129.84\",\"priceCurrency\":\"USD\"}}]}</script>"
        val p = SoundGuys.read("https://www.soundguys.com/product/sony-wf-1000xm4/", html)
        assertEquals("Sony WF-1000XM4", p.name)
        assertEquals(8.3, p.score)
        assertEquals(129.84, p.price)
        assertTrue(p.earbuds)
        val d = Headgear.fromSoundGuys("live|Sony|wf-1000xm4", "Sony", "wf-1000xm4", p)
        assertEquals(Headgear.Kind.EARBUDS, d.kind)
        assertEquals("pair of wireless noise-cancelling earbuds", d.form)
        assertEquals("up to 8 hours with noise cancelling on (12 off)", d.battery)
        assertEquals("up to 24 hours with the case", d.case)
        assertEquals("IPX4", d.water)
        assertEquals(listOf("Bluetooth 5.2, multipoint"), d.connections)
        assertEquals("6 mm dynamic", d.drivers)
        assertEquals("4 mics for calls", d.mic)
        assertEquals("a V-shaped sound signature and a frequency response of 20 Hz to 20 kHz", d.sound)
        assertEquals(2021, d.year)
        assertEquals("The WF-1000XM4 launched at \$280 and is \$129.84 on SoundGuys today.", Headgear.answer(d, HeadWant.PRICE))
        assertEquals("SoundGuys scores the WF-1000XM4 8.3 out of 10.", Headgear.answer(d, HeadWant.SCORE))
        assertTrue(Headgear.text(d, null).contains("8.3/10 on SoundGuys"))
        assertTrue(Headgear.text(d, null).contains("SOUND  V-shaped; 20 Hz to 20 kHz"))
        assertEquals("moondrop-chu-iii", SoundGuys.bestUrl("Moondrop", "chu 3", listOf("https://www.soundguys.com/product/moondrop-chu-ii/", "https://www.soundguys.com/product/moondrop-chu-iii/"))
            ?.substringAfter("/product/")?.trimEnd('/'))
        assertEquals("moondrop-chu-ii", SoundGuys.bestUrl("Moondrop", "Chu II", listOf("https://www.soundguys.com/product/moondrop-chu-ii/", "https://www.soundguys.com/product/moondrop-chu-iii/"))
            ?.substringAfter("/product/")?.trimEnd('/'))

        val speaker = SoundGuys.read("https://www.soundguys.com/product/jbl-charge-5/", "{\"@type\":\"Product\",\"@id\":\"y\",\"name\":\"JBL Charge 5\",\"category\":\"Electronics > Audio > Speakers\"," +
            listOf(pv("Battery Life Wireless", "20"), pv("IP Rating", "IP67"), pv("Powerbank Output", "true"), pv("Stereo Pairing", "true"), pv("Total Power Output", "40"),
                pv("Connection Type", "bluetooth"), pv("Is Portable", "true")).joinToString(","))
        val s = Headgear.fromSoundGuys("live|JBL|charge 5", "JBL", "charge 5", speaker)
        assertEquals(Headgear.Kind.SPEAKER, s.kind)
        assertEquals("portable Bluetooth speaker", s.form)
        assertEquals("40 W", s.power)
        assertEquals("Yes, the Charge 5 charges your phone.", Headgear.answer(s, HeadWant.POWERBANK))
    }

    @Test fun headsetPages() {
        val html = "<title>Arctis Nova 5 Wireless Gaming Headset | SteelSeries</title><p>Up to 60 hours of battery life. Quantum 2.0 2.4 GHz wireless and Bluetooth 5.3." +
            " Neodymium magnetic drivers, 40 mm. Retractable boom mic with ClearCast. Works on PC, PlayStation, Nintendo Switch and mobile. Weight: 264 g. 360 Spatial Audio.</p>"
        val f = HeadgearLive.readHeadset(html)
        assertEquals(60.0, f.batteryH)
        assertEquals(listOf("2.4 GHz wireless (USB dongle)", "Bluetooth"), f.connections)
        assertEquals("40 mm neodymium", f.drivers)
        assertEquals("Retractable mic", f.mic)
        assertEquals(264.0, f.weightG)
        assertEquals("PC, PlayStation, Switch and phones", f.platforms)
        assertNull(f.anc)
    }
}
