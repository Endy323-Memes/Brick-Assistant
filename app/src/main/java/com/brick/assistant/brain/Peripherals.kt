package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.Locale

/** What's asked about a keyboard, mouse, stream controller or microphone. */
enum class GearWant {
    OVERVIEW, SWITCHES, KEYCAPS, HOTSWAP, PROGRAM, WIRELESS, BATTERY, POLLING, CONNECTION,
    SIDE, WHEEL, SENSOR, WEIGHT, BUTTONS, PRICE, RELEASE, MAKER, MIC_TYPE, PATTERN, KEYS, NOTES,
}

/** One device ([ids] has two for a comparison). */
data class GearAsk(val ids: List<String>, val want: GearWant, val said: String) {
    val compare get() = ids.size > 1
}

/**
 * PC peripherals from Brick's own notes: keyboards (switches, keycaps, hot-swap, programmability), mice (sensor,
 * weight, side buttons, wheel modes), stream controllers and microphones, with battery, polling rate and how they
 * connect; two side by side.
 */
object Peripherals {
    enum class Kind(val noun: String, val heading: String) {
        KEYBOARD("keyboard", "KEYBOARD"), MOUSE("mouse", "MOUSE"), CONTROLLER("stream controller", "STREAM DECK"), MIC("microphone", "MIC"),
    }

    data class Device(
        val id: String, val kind: Kind, val name: String, val maker: String, val year: Int?, val price: String, val priceUsd: Double?,
        /** A noun phrase: "low-profile wireless tenkeyless keyboard". */
        val form: String,
        val switches: String? = null, val keycaps: String? = null, val hotSwap: String? = null,
        val sensor: String? = null, val dpi: Int? = null, val weightG: Double? = null, val buttons: Int? = null,
        /** Noun phrases: "two side buttons (back and forward) on the left"; "stepped scrolling only". */
        val side: String? = null, val wheel: String? = null,
        val program: String? = null,
        val connections: List<String>, val battery: String? = null, val batteryH: Double? = null,
        val polling: String? = null, val pollingHz: Int? = null,
        /** Rows for mics and stream controllers: TYPE, PATTERN, AUDIO, GAIN, KEYS. */
        val extra: List<Pair<String, String>> = emptyList(),
        val notes: List<String> = emptyList(),
        /** The maker's page Brick read live, and its price there today. */
        val liveUrl: String? = null, val now: Double? = null,
    ) {
        /** Built from the maker's page alone, with no notes of Brick's own. */
        val liveOnly get() = liveUrl != null && id.startsWith("live|")
        val short get() = name.removePrefix("$maker ").removePrefix("Logitech G ").let { if (it == name) name else it }
        val wireless get() = connections.any { !it.startsWith("Wired") && !it.startsWith("XLR") && !it.startsWith("USB") && !it.contains("charging") }
        fun extra(label: String) = extra.firstOrNull { it.first == label }?.second
    }

    private val K = Kind.KEYBOARD
    private val M = Kind.MOUSE
    private val C = Kind.CONTROLLER
    private val MIC = Kind.MIC

    private const val LS = "2.4 GHz LIGHTSPEED (USB receiver)"
    private const val BT = "Bluetooth"
    private const val USBC = "Wired (USB-C)"
    private const val NO_SWAP = "No, the switches are soldered in"
    private const val SWAP = "Yes, 3- and 5-pin MX-style switches"
    private const val GL = "Logitech GL low-profile mechanical switches: Tactile, Linear or Clicky"
    private const val GX = "Logitech GX mechanical switches: Tactile, Linear or Clicky"
    private const val KPRO = "Keychron K Pro mechanical switches: Red (linear), Brown (tactile) or Banana (tactile)"
    private const val QMK = "Open-source QMK firmware: remap every key, build layers and macros in VIA or the Keychron Launcher website."
    private const val HS = "2.4 GHz HyperSpeed (USB receiver)"
    private const val OFFICE_POLL = "Not published (built for work, not gaming)"
    private const val SD_APP = "Stream Deck app: one-touch actions, multi-actions, folders, profiles that switch with the app you're in, and plugins from the Elgato Marketplace (OBS, Twitch, Spotify, Discord, smart lights)."

    val DEVICES: List<Device> = listOf(
        // ---------------------------------------------------------------- keyboards
        Device("g915", K, "Logitech G915 Lightspeed", "Logitech", 2019, "$249.99", 249.99, "low-profile wireless full-size gaming keyboard",
            switches = GL, keycaps = "low-profile ABS keycaps", hotSwap = NO_SWAP,
            program = "Five dedicated G-keys, and every other key, take macros and remaps in Logitech G HUB, with onboard memory for profiles.",
            connections = listOf(LS, BT, "Wired (micro-USB)"), battery = "up to 30 hours with lighting at full brightness", batteryH = 30.0,
            polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Dedicated media keys and a metal volume roller.", "Aluminum top plate; only about 22 mm thick.", "Replaced in 2024 by the G915 X.")),
        Device("g915tkl", K, "Logitech G915 TKL Lightspeed", "Logitech", 2020, "$229.99", 229.99, "low-profile wireless tenkeyless gaming keyboard",
            switches = GL, keycaps = "low-profile ABS keycaps", hotSwap = NO_SWAP,
            program = "Every key can be remapped or given a macro in Logitech G HUB (there are no dedicated G-keys), with onboard memory.",
            connections = listOf(LS, BT, USBC), battery = "up to 40 hours with lighting at full brightness", batteryH = 40.0,
            polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Tenkeyless: no number pad, so more room for your mouse.", "Media keys and a volume roller.")),
        Device("proxtkl", K, "Logitech G Pro X TKL Lightspeed", "Logitech", 2023, "$199.99", 199.99, "wireless tenkeyless esports keyboard",
            switches = GX, keycaps = "double-shot PBT keycaps", hotSwap = NO_SWAP,
            program = "Remap keys and record macros in Logitech G HUB; KEYCONTROL stacks extra functions on keys, and profiles live in onboard memory.",
            connections = listOf(LS, BT, USBC), battery = "up to 50 hours", batteryH = 50.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Made for esports, with a game mode that locks out the Windows key.", "Has a volume roller and media keys.")),
        Device("prox60", K, "Logitech G Pro X 60 Lightspeed", "Logitech", 2024, "$179.99", 179.99, "wireless 60% esports keyboard",
            switches = "Logitech GX Optical switches: Linear or Tactile", keycaps = "double-shot PBT keycaps", hotSwap = NO_SWAP,
            program = "KEYCONTROL in G HUB layers up to 15 functions per key, saved to onboard memory.",
            connections = listOf(LS, BT, USBC), battery = "up to 65 hours", batteryH = 65.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("60%: no arrow cluster, F-row or number pad; those live on a function layer.", "A volume roller sits on the top edge.")),
        Device("mxkeyss", K, "Logitech MX Keys S", "Logitech", 2023, "$109.99", 109.99, "low-profile wireless office keyboard",
            switches = "Scissor switches, like a laptop (not mechanical)", keycaps = "fixed low-profile keys with a dished top", hotSwap = "No, it isn't a mechanical keyboard",
            program = "Logi Options+ remaps the F-keys and runs Smart Actions (several shortcuts from one key).",
            connections = listOf(BT, "Logi Bolt receiver", "USB-C (charging)"), battery = "up to 10 days with the backlight on, up to 5 months with it off", batteryH = 240.0,
            polling = OFFICE_POLL,
            notes = listOf("Pairs with up to 3 devices and switches between them with Easy-Switch keys.", "The backlight turns on as your hands get close.")),
        Device("mxmech", K, "Logitech MX Mechanical", "Logitech", 2022, "$169.99", 169.99, "low-profile wireless mechanical office keyboard",
            switches = "Kailh Choc V2 low-profile switches: Tactile Quiet, Linear or Clicky", keycaps = "low-profile ABS keycaps", hotSwap = NO_SWAP,
            program = "Logi Options+ remaps keys and runs Smart Actions.",
            connections = listOf(BT, "Logi Bolt receiver", "USB-C (charging)"), battery = "up to 15 days with the backlight on, up to 10 months with it off", batteryH = 360.0,
            polling = OFFICE_POLL,
            notes = listOf("Pairs with up to 3 devices.", "Also comes as the smaller MX Mechanical Mini (75%).")),
        Device("magickb", K, "Apple Magic Keyboard", "Apple", 2024, "$99 (with Touch ID $149, with Touch ID and number pad $199)", 99.0, "wireless low-profile keyboard for Mac",
            switches = "Scissor switches, like a MacBook (not mechanical)", keycaps = "fixed low-profile keys", hotSwap = "No, it isn't a mechanical keyboard",
            program = "No remapping software; macOS keyboard settings only. Touch ID models unlock Apple silicon Macs with your fingerprint.",
            connections = listOf(BT, "Wired (USB-C cable)"), battery = "about a month or more per charge", polling = "Not published",
            notes = listOf("The USB-C version came out in 2024; the design dates back to 2015.", "Works with iPad and iPhone too.")),
        Device("bwv4pro", K, "Razer BlackWidow V4 Pro", "Razer", 2023, "$229.99", 229.99, "wired full-size gaming keyboard",
            switches = "Razer Green (clicky) or Yellow (linear) mechanical switches", keycaps = "double-shot ABS keycaps", hotSwap = NO_SWAP,
            program = "8 dedicated macro keys and the Razer Command Dial; everything is programmable in Razer Synapse, with onboard memory.",
            connections = listOf(USBC), polling = "8,000 Hz (Razer HyperPolling)", pollingHz = 8000,
            notes = listOf("Comes with a magnetic plush wrist rest.", "Lighting on the keys plus underglow on three sides.")),
        Device("bwv475", K, "Razer BlackWidow V4 75%", "Razer", 2023, "$189.99", 189.99, "wired 75% hot-swappable gaming keyboard",
            switches = "Razer Orange tactile mechanical switches (pre-lubed)", keycaps = "double-shot ABS keycaps", hotSwap = SWAP,
            program = "Remaps and macros in Razer Synapse, plus a programmable Command Dial; onboard memory.",
            connections = listOf(USBC), polling = "8,000 Hz", pollingHz = 8000,
            notes = listOf("Razer's first hot-swappable keyboard.", "Gasket-mounted with two layers of sound-dampening foam.")),
        Device("huntsmanv3pro", K, "Razer Huntsman V3 Pro", "Razer", 2023, "$249.99", 249.99, "wired full-size analog gaming keyboard",
            switches = "Razer Analog Optical switches (Gen-2), with actuation you can set from 0.1 to 4.0 mm", keycaps = "double-shot PBT keycaps", hotSwap = NO_SWAP,
            program = "Rapid Trigger, per-key actuation and macros in Razer Synapse; a dial and buttons on top.",
            connections = listOf(USBC), polling = "1,000 Hz (the 8KHz edition polls at 8,000 Hz)", pollingHz = 1000,
            notes = listOf("Rapid Trigger resets a key the instant it starts rising, for faster taps in shooters.", "Magnetic wrist rest included.", "Also comes in TKL and Mini sizes.")),
        Device("k70pro", K, "Corsair K70 RGB Pro", "Corsair", 2022, "$159.99", 159.99, "wired full-size gaming keyboard",
            switches = "Cherry MX mechanical switches (such as Red, Brown or Speed Silver), or Corsair OPX optical", keycaps = "double-shot PBT keycaps", hotSwap = NO_SWAP,
            program = "Remaps, macros and lighting in Corsair iCUE, with onboard memory for profiles.",
            connections = listOf(USBC), polling = "8,000 Hz (Corsair AXON)", pollingHz = 8000,
            notes = listOf("A tournament switch turns off fancy lighting and macros.", "Aluminum frame and a detachable wrist rest.")),
        Device("k70max", K, "Corsair K70 Max", "Corsair", 2023, "$229.99", 229.99, "wired full-size magnetic gaming keyboard",
            switches = "Corsair MGX magnetic-mechanical switches, with actuation you can set from 0.4 to 3.6 mm", keycaps = "double-shot PBT keycaps", hotSwap = NO_SWAP,
            program = "Per-key actuation and macros in Corsair iCUE; onboard memory.",
            connections = listOf(USBC), polling = "8,000 Hz", pollingHz = 8000,
            notes = listOf("Set how far each key travels before it registers.", "Magnetic wrist rest included.")),
        Device("k100", K, "Corsair K100 RGB", "Corsair", 2020, "$229.99", 229.99, "wired full-size premium gaming keyboard",
            switches = "Corsair OPX optical or Cherry MX Speed switches", keycaps = "double-shot PBT keycaps", hotSwap = NO_SWAP,
            program = "Six dedicated G-keys, the iCUE Control Wheel and macros in Corsair iCUE, with onboard memory.",
            connections = listOf("Wired (USB)"), polling = "4,000 Hz (Corsair AXON)", pollingHz = 4000,
            notes = listOf("Magnetic wrist rest and a USB passthrough port.", "Corsair's flagship keyboard.")),
        Device("k65plus", K, "Corsair K65 Plus Wireless", "Corsair", 2023, "$159.99", 159.99, "wireless 75% gaming keyboard",
            switches = "pre-lubed Corsair MLX Red linear switches", keycaps = "double-shot PBT keycaps", hotSwap = SWAP,
            program = "Remaps and macros in Corsair iCUE, plus a programmable volume knob.",
            connections = listOf("2.4 GHz Slipstream (USB receiver)", BT, USBC), battery = "up to 266 hours with lighting off", batteryH = 266.0,
            polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Gasket-mounted with layers of foam for a softer, quieter sound.")),
        Device("apexpro", K, "SteelSeries Apex Pro TKL Wireless Gen 3", "SteelSeries", 2024, "$249.99", 249.99, "wireless tenkeyless magnetic gaming keyboard",
            switches = "OmniPoint 3.0 Hall-effect magnetic switches, with actuation you can set from 0.1 to 4.0 mm", keycaps = "double-shot keycaps", hotSwap = NO_SWAP,
            program = "Rapid Trigger, two actions per key and macros in SteelSeries GG, with settings on its OLED screen.",
            connections = listOf("2.4 GHz Quantum 2.0 (USB receiver)", BT, USBC), battery = "up to 37.5 hours on 2.4 GHz, 45 on Bluetooth", batteryH = 37.5,
            polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("OLED screen with a dial for settings.", "Magnetic wrist rest.", "Also sold wired and full-size.")),
        Device("azoth", K, "ASUS ROG Azoth", "ASUS", 2022, "$249.99", 249.99, "wireless 75% custom gaming keyboard",
            switches = "ROG NX mechanical switches (Red linear, Brown tactile or Blue clicky), pre-lubed", keycaps = "double-shot PBT keycaps",
            hotSwap = "Yes, 3- and 5-pin MX-style switches, with a switch puller and lube kit in the box",
            program = "Macros and remaps in Armoury Crate, plus a 2-inch OLED screen and a three-way control knob.",
            connections = listOf("2.4 GHz SpeedNova (USB receiver)", BT, USBC), battery = "up to about 2,000 hours with lighting and the screen off", batteryH = 2000.0,
            polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Gasket mount with three layers of dampening foam.", "Followed by the Azoth Extreme (2024) and Azoth X (2025).")),
        Device("q1pro", K, "Keychron Q1 Pro", "Keychron", 2023, "$199 (fully built)", 199.0, "wireless 75% aluminum custom keyboard",
            switches = KPRO, keycaps = "double-shot PBT keycaps, OSA profile", hotSwap = "Yes, 3- and 5-pin MX-style switches (south-facing)",
            program = QMK, connections = listOf("Bluetooth 5.1", USBC), battery = "up to 300 hours with the backlight off (4,000 mAh)", batteryH = 300.0,
            polling = "1,000 Hz wired, 90 Hz on Bluetooth", pollingHz = 1000,
            notes = listOf("Full CNC aluminum body with a gasket mount and a knob.", "Comes with Mac and Windows keycaps.")),
        Device("k2pro", K, "Keychron K2 Pro", "Keychron", 2022, "$99 (fully built)", 99.0, "wireless 75% keyboard",
            switches = KPRO, keycaps = "double-shot PBT keycaps, OSA profile", hotSwap = SWAP,
            program = QMK, connections = listOf("Bluetooth 5.1", USBC), battery = "up to 300 hours with the backlight off (4,000 mAh)", batteryH = 300.0,
            polling = "1,000 Hz wired, 90 Hz on Bluetooth", pollingHz = 1000,
            notes = listOf("Switches between 3 Bluetooth devices.", "Comes with Mac and Windows keycaps.")),
        Device("v1", K, "Keychron V1", "Keychron", 2022, "$84 (fully built)", 84.0, "wired 75% custom keyboard",
            switches = KPRO, keycaps = "double-shot PBT keycaps, OSA profile", hotSwap = SWAP,
            program = QMK, connections = listOf(USBC), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("A budget way into custom keyboards: plastic case, gasket-style mount, optional knob.")),
        Device("wooting80he", K, "Wooting 80HE", "Wooting", 2024, "$199.99", 199.99, "wired 75% analog magnetic gaming keyboard",
            switches = "Lekker L60 magnetic Hall-effect switches, with actuation you can set from 0.1 to 4.0 mm", keycaps = "double-shot PBT keycaps",
            hotSwap = "Yes, but only magnetic Hall-effect switches (Lekker, Gateron magnetic and similar)",
            program = "Wootility (works in your browser): Rapid Trigger, per-key actuation, Mod Tap, SOCD settings, and analog keys that act like a gamepad stick.",
            connections = listOf(USBC), polling = "8,000 Hz", pollingHz = 8000,
            notes = listOf("Analog input: keys can work like a controller stick for smooth movement.", "Wooting helped make Rapid Trigger popular.")),
        Device("wooting60he", K, "Wooting 60HE", "Wooting", 2022, "about $175", 175.0, "wired 60% analog magnetic gaming keyboard",
            switches = "Lekker magnetic Hall-effect switches, with actuation you can set from 0.1 to 4.0 mm", keycaps = "PBT keycaps",
            hotSwap = "Yes, but only magnetic Hall-effect switches",
            program = "Wootility: Rapid Trigger, per-key actuation and analog keys that act like a gamepad stick.",
            connections = listOf(USBC), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("60%: arrows and the F-row live on a function layer.", "The 80HE followed in 2024.")),
        Device("alloycore", K, "HyperX Alloy Origins Core", "HyperX", 2020, "$89.99", 89.99, "wired tenkeyless gaming keyboard",
            switches = "HyperX Red (linear), Aqua (tactile) or Blue (clicky) mechanical switches", keycaps = "ABS keycaps (a PBT version is sold too)", hotSwap = NO_SWAP,
            program = "Macros, remaps and lighting in HyperX NGENUITY, with 3 onboard profiles.",
            connections = listOf(USBC), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Aircraft-grade aluminum body.", "Feet with three tilt angles.")),
        Device("duckyone3", K, "Ducky One 3", "Ducky", 2021, "about $119", 119.0, "wired hot-swappable mechanical keyboard",
            switches = "Cherry MX mechanical switches (other brands in some regions)", keycaps = "double-shot PBT keycaps, Cherry profile", hotSwap = SWAP,
            program = "No software needed: macros and remaps are set on the keyboard itself with key combinations.",
            connections = listOf(USBC), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Layers of foam and silicone for a cleaner sound.", "Known for colorful keycap themes like Daybreak and Fuji.", "Comes in full, TKL, 65% and 60% sizes.")),
        Device("gmmkpro", K, "Glorious GMMK Pro", "Glorious", 2021, "$169.99 (barebone)", 169.99, "wired 75% custom keyboard kit",
            switches = "none in the barebone kit: you add your own", keycaps = "none in the barebone kit", hotSwap = "Yes, 3- and 5-pin MX-style switches (south-facing)",
            program = "Glorious Core software, or open-source QMK and VIA.",
            connections = listOf(USBC), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Gasket-mounted aluminum case with a rotary knob.", "Barebone means you bring your own switches and keycaps.")),

        // ---------------------------------------------------------------- mice
        Device("superlight2", M, "Logitech G Pro X Superlight 2", "Logitech", 2023, "$159.99", 159.99, "ultralight wireless esports mouse",
            sensor = "HERO 2 optical sensor, up to 44,000 DPI after updates (32,000 at launch)", dpi = 44000, weightG = 60.0, buttons = 5,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap all 5 buttons and set DPI steps in Logitech G HUB, with onboard memory.",
            connections = listOf(LS, USBC), battery = "up to 95 hours", batteryH = 95.0,
            polling = "8,000 Hz over LIGHTSPEED (1,000 Hz wired)", pollingHz = 8000,
            notes = listOf("LIGHTFORCE hybrid optical-mechanical switches.", "Symmetrical shape, but the side buttons are on the left only.", "Works with POWERPLAY wireless charging.", "A right-handed DEX version came in 2024.")),
        Device("superlight", M, "Logitech G Pro X Superlight", "Logitech", 2020, "$149.99", 149.99, "ultralight wireless esports mouse",
            sensor = "HERO 25K optical sensor, up to 25,600 DPI", dpi = 25600, weightG = 63.0, buttons = 5,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap buttons and set DPI in Logitech G HUB, with onboard memory.",
            connections = listOf(LS, "Wired (micro-USB)"), battery = "up to 70 hours", batteryH = 70.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("One of the most popular esports mice ever.", "Replaced by the Superlight 2 in 2023.")),
        Device("gprowireless", M, "Logitech G Pro Wireless", "Logitech", 2018, "$149.99", 149.99, "wireless ambidextrous esports mouse",
            sensor = "HERO 25K optical sensor, up to 25,600 DPI (16,000 at launch)", dpi = 25600, weightG = 80.0, buttons = 8,
            side = "magnetic side buttons that clip onto either side, two per side (blank covers if you want none)", wheel = "stepped scrolling only",
            program = "Remap buttons and set DPI in Logitech G HUB, with onboard memory.",
            connections = listOf(LS, "Wired (micro-USB)"), battery = "up to 60 hours", batteryH = 60.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Truly ambidextrous: the side buttons swap sides for lefties.", "Works with POWERPLAY wireless charging.")),
        Device("g502xplus", M, "Logitech G502 X Plus", "Logitech", 2022, "$159.99", 159.99, "wireless RGB gaming mouse",
            sensor = "HERO 25K optical sensor, up to 25,600 DPI", dpi = 25600, weightG = 106.0, buttons = 13,
            side = "back and forward, plus a removable, reversible DPI-shift (sniper) button under your thumb",
            wheel = "two modes: stepped, or infinite free-spin (toggled by a button), plus left and right tilt",
            program = "13 programmable controls in Logitech G HUB, with onboard memory.",
            connections = listOf(LS, USBC), battery = "up to 130 hours with lighting off", batteryH = 130.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("LIGHTFORCE hybrid optical-mechanical switches.", "Works with POWERPLAY wireless charging.", "The wired G502 X and the G502 X Lightspeed share its shape.")),
        Device("g502", M, "Logitech G502 Hero", "Logitech", 2018, "$79.99", 79.99, "wired gaming mouse",
            sensor = "HERO 25K optical sensor, up to 25,600 DPI", dpi = 25600, weightG = 121.0, buttons = 11,
            side = "back and forward, plus a DPI-shift (sniper) button under your thumb",
            wheel = "two modes: stepped, or infinite free-spin (toggled by a button), plus left and right tilt",
            program = "11 programmable buttons in Logitech G HUB, with onboard memory.",
            connections = listOf("Wired (USB)"), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Five removable 3.6 g weights to tune the heft.", "One of the best-selling gaming mice ever.")),
        Device("g305", M, "Logitech G305 Lightspeed", "Logitech", 2018, "$59.99", 59.99, "budget wireless gaming mouse",
            sensor = "HERO optical sensor, up to 12,000 DPI", dpi = 12000, weightG = 99.0, buttons = 6,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap buttons and set DPI in Logitech G HUB, with onboard memory.",
            connections = listOf(LS), battery = "up to 250 hours on one AA battery", batteryH = 250.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Runs on a single AA battery, no charging cable.", "The receiver stores inside the mouse.")),
        Device("mxmaster3s", M, "Logitech MX Master 3S", "Logitech", 2022, "$99.99", 99.99, "ergonomic wireless productivity mouse",
            sensor = "8,000 DPI Darkfield sensor (tracks even on glass)", dpi = 8000, weightG = 141.0, buttons = 7,
            side = "back and forward buttons, a thumb gesture button, and a horizontal thumb scroll wheel",
            wheel = "a MagSpeed electromagnetic wheel with two modes: stepped (ratchet) or infinite free-spin, switched by a button or automatically by SmartShift",
            program = "Logi Options+ remaps buttons per app and adds gestures and Smart Actions.",
            connections = listOf(BT, "Logi Bolt receiver", "USB-C (charging)"), battery = "up to 70 days per charge (1 minute of charging gives about 3 hours)", batteryH = 1680.0,
            polling = OFFICE_POLL,
            notes = listOf("Quiet clicks.", "Logitech Flow moves your cursor, and copied files, between computers.", "Right-handed only.", "Followed by the MX Master 4 in 2025.")),
        Device("mxanywhere3s", M, "Logitech MX Anywhere 3S", "Logitech", 2023, "$79.99", 79.99, "compact wireless productivity mouse",
            sensor = "8,000 DPI Darkfield sensor (tracks even on glass)", dpi = 8000, weightG = 99.0, buttons = 6,
            side = "two side buttons (back and forward) on the left",
            wheel = "a MagSpeed electromagnetic wheel with two modes: stepped or infinite free-spin, switched with the button behind it",
            program = "Logi Options+ remaps buttons per app.",
            connections = listOf(BT, "Logi Bolt receiver", "USB-C (charging)"), battery = "up to 70 days per charge", batteryH = 1680.0,
            polling = OFFICE_POLL,
            notes = listOf("Small enough to travel with.", "Quiet clicks.")),
        Device("magicmouse", M, "Apple Magic Mouse", "Apple", 2024, "$79 (black $99)", 79.0, "wireless multi-touch mouse for Mac",
            sensor = "Optical (DPI not published)", weightG = 99.0, buttons = 2,
            side = "no side buttons", wheel = "no wheel at all: you scroll by swiping its Multi-Touch top, in any direction, with momentum",
            program = "Gestures and right-click are set in macOS settings; no button remapping.",
            connections = listOf(BT), battery = "about a month per charge", polling = "Not published",
            notes = listOf("The charging port is on the bottom, so you can't use it while it charges.", "The USB-C version came out in 2024.")),
        Device("dav3pro", M, "Razer DeathAdder V3 Pro", "Razer", 2022, "$149.99", 149.99, "ultralight ergonomic wireless esports mouse",
            sensor = "Focus Pro 30K optical sensor, up to 30,000 DPI", dpi = 30000, weightG = 63.0, buttons = 5,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap buttons and set DPI in Razer Synapse, with onboard memory.",
            connections = listOf(HS, USBC), battery = "up to 90 hours", batteryH = 90.0,
            polling = "up to 8,000 Hz with the HyperPolling dongle (1,000 Hz with the standard one)", pollingHz = 8000,
            notes = listOf("Right-handed ergonomic shape.", "Razer Optical Mouse Switches Gen-3: no double-clicking from worn contacts.")),
        Device("viperv3pro", M, "Razer Viper V3 Pro", "Razer", 2024, "$159.99", 159.99, "ultralight symmetrical wireless esports mouse",
            sensor = "Focus Pro 35K Gen-2 optical sensor, up to 35,000 DPI", dpi = 35000, weightG = 54.0, buttons = 6,
            side = "two side buttons (back and forward) on the left only, unlike older Vipers", wheel = "stepped scrolling only",
            program = "Remap buttons and set DPI in Razer Synapse, with onboard memory; a DPI button sits underneath.",
            connections = listOf("2.4 GHz HyperPolling dongle (included)", USBC), battery = "up to 95 hours at 1,000 Hz", batteryH = 95.0,
            polling = "8,000 Hz with the included HyperPolling dongle", pollingHz = 8000,
            notes = listOf("Built with esports pros for claw and fingertip grips.", "Optical Mouse Switches Gen-3.")),
        Device("basiliskv3pro", M, "Razer Basilisk V3 Pro", "Razer", 2022, "$159.99", 159.99, "wireless ergonomic RGB gaming mouse",
            sensor = "Focus Pro 30K optical sensor, up to 30,000 DPI", dpi = 30000, weightG = 112.0, buttons = 11,
            side = "back and forward, plus a sensitivity clutch (DPI-shift) trigger under your thumb",
            wheel = "a HyperScroll tilt wheel: stepped or infinite free-spin, with a Smart-reel mode that switches on its own by how fast you flick, plus left and right tilt",
            program = "11 programmable controls and Razer HyperShift (a second layer of functions) in Razer Synapse, with onboard memory.",
            connections = listOf(HS, BT, USBC), battery = "up to 90 hours on HyperSpeed", batteryH = 90.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Charges wirelessly on the Razer Mouse Dock Pro or Charging Pad.", "13 lighting zones, including underglow.")),
        Device("nagav2pro", M, "Razer Naga V2 Pro", "Razer", 2022, "$179.99", 179.99, "wireless MMO and MOBA gaming mouse",
            sensor = "Focus Pro 30K optical sensor, up to 30,000 DPI", dpi = 30000, weightG = 134.0, buttons = 20,
            side = "three swappable magnetic side plates: 2 buttons, 6 buttons in a ring, or a 12-button grid for MMOs",
            wheel = "a HyperScroll Pro wheel: stepped or infinite free-spin",
            program = "Up to 20 programmable controls in Razer Synapse, with onboard memory.",
            connections = listOf(HS, BT, USBC), battery = "up to 150 hours on HyperSpeed", batteryH = 150.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Swap side plates for shooters, MOBAs or MMOs.", "Charges wirelessly on the Razer Mouse Dock Pro.")),
        Device("aerox3w", M, "SteelSeries Aerox 3 Wireless", "SteelSeries", 2022, "$99.99", 99.99, "ultralight wireless gaming mouse",
            sensor = "TrueMove Air optical sensor, up to 18,000 CPI", dpi = 18000, weightG = 68.0, buttons = 6,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap buttons and set CPI in SteelSeries GG, with onboard memory.",
            connections = listOf("2.4 GHz Quantum 2.0 (USB receiver)", BT, USBC), battery = "up to 80 hours on 2.4 GHz, about 200 on Bluetooth", batteryH = 80.0,
            polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Honeycomb shell that still resists water and dust (IP54).")),
        Device("rival3", M, "SteelSeries Rival 3", "SteelSeries", 2019, "$29.99", 29.99, "budget wired gaming mouse",
            sensor = "TrueMove Core optical sensor, up to 8,500 CPI", dpi = 8500, weightG = 77.0, buttons = 6,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap buttons and set CPI in SteelSeries GG, with onboard memory.",
            connections = listOf("Wired (USB)"), polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("A popular budget pick with a true gaming sensor.")),
        Device("modelo", M, "Glorious Model O Wireless", "Glorious", 2021, "$79.99", 79.99, "ultralight wireless gaming mouse",
            sensor = "BAMF optical sensor, up to 19,000 DPI", dpi = 19000, weightG = 69.0, buttons = 6,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling only",
            program = "Remap buttons and set DPI in Glorious Core, with onboard memory.",
            connections = listOf("2.4 GHz (USB receiver)", "Wired (USB)"), battery = "up to 71 hours", batteryH = 71.0, polling = "1,000 Hz", pollingHz = 1000,
            notes = listOf("Honeycomb shell to cut weight.")),
        Device("ec2c", M, "Zowie EC2-C", "Zowie", 2022, "$69.99", 69.99, "wired ergonomic esports mouse",
            sensor = "PMW3360 optical sensor, 400 to 3,200 DPI", dpi = 3200, weightG = 73.0, buttons = 5,
            side = "two side buttons (back and forward) on the left", wheel = "stepped scrolling, with a firm 24-step wheel",
            program = "No software at all: DPI and polling rate are set with buttons on the bottom (plug and play).",
            connections = listOf("Wired (USB)"), polling = "1,000 Hz (125, 500 or 1,000 selectable)", pollingHz = 1000,
            notes = listOf("Right-handed shape loved by Counter-Strike players.", "Soft, flexible cable.")),

        // ---------------------------------------------------------------- stream controllers
        Device("streamdeck", C, "Elgato Stream Deck MK.2", "Elgato", 2021, "$149.99", 149.99, "15-key stream controller",
            program = SD_APP, connections = listOf("Wired (USB-C to USB-A cable)"),
            extra = listOf("KEYS" to "15 customizable LCD keys"),
            notes = listOf("Swappable faceplates and a detachable stand.", "Also comes as the Mini (6 keys), XL (32 keys), Neo (8 keys) and Stream Deck + (8 keys and 4 dials).", "Elgato is owned by Corsair.")),
        Device("streamdeckmini", C, "Elgato Stream Deck Mini", "Elgato", 2017, "$79.99", 79.99, "6-key stream controller",
            program = SD_APP, connections = listOf("Wired (USB)"), extra = listOf("KEYS" to "6 customizable LCD keys"),
            notes = listOf("Folders and pages let 6 keys do far more than 6 things.")),
        Device("streamdeckxl", C, "Elgato Stream Deck XL", "Elgato", 2019, "$249.99", 249.99, "32-key stream controller",
            program = SD_APP, connections = listOf("Wired (USB-C)"), extra = listOf("KEYS" to "32 customizable LCD keys"),
            notes = listOf("The biggest Stream Deck: room for a whole show's worth of scenes and sounds.")),
        Device("streamdeckplus", C, "Elgato Stream Deck +", "Elgato", 2022, "$199.99", 199.99, "stream controller with dials",
            program = SD_APP, connections = listOf("Wired (USB-C)"),
            extra = listOf("KEYS" to "8 customizable LCD keys", "DIALS" to "4 clickable dials and a touch strip"),
            notes = listOf("The dials are great for volume, lights and editing scrubs.")),
        Device("streamdeckneo", C, "Elgato Stream Deck Neo", "Elgato", 2023, "$99.99", 99.99, "8-key stream controller",
            program = SD_APP, connections = listOf("Wired (USB-C)"),
            extra = listOf("KEYS" to "8 customizable LCD keys, plus 2 touch points to flip pages", "SCREEN" to "an info bar with the time and page"),
            notes = listOf("Made for work and home as much as streaming.")),
        Device("streamdeckpedal", C, "Elgato Stream Deck Pedal", "Elgato", 2022, "$89.99", 89.99, "3-pedal foot controller",
            program = SD_APP, connections = listOf("Wired (USB-C)"), extra = listOf("KEYS" to "3 foot pedals with swappable springs"),
            notes = listOf("Hands-free control while you play or work.")),

        // ---------------------------------------------------------------- microphones
        Device("sm7b", MIC, "Shure SM7B", "Shure", 2001, "$399", 399.0, "dynamic broadcast microphone",
            connections = listOf("XLR (needs an audio interface or mixer)"),
            extra = listOf("TYPE" to "Dynamic", "PATTERN" to "Cardioid (picks up what's in front)", "GAIN" to "Needs lots of clean gain, about 60 dB; many people add a Cloudlifter or FetHead"),
            notes = listOf("A podcast and radio classic; the SM7 it came from was used on Michael Jackson's Thriller.", "Rejects room noise well; speak into it from up close.", "Bass roll-off and presence boost switches on the back.")),
        Device("sm7db", MIC, "Shure SM7dB", "Shure", 2023, "$499", 499.0, "dynamic broadcast microphone with a built-in preamp",
            connections = listOf("XLR (needs an audio interface, plus 48 V phantom power for the preamp)"),
            extra = listOf("TYPE" to "Dynamic", "PATTERN" to "Cardioid", "GAIN" to "Built-in preamp adds +18 or +28 dB, so no Cloudlifter needed"),
            notes = listOf("An SM7B with the booster built in.")),
        Device("mv7plus", MIC, "Shure MV7+", "Shure", 2024, "$279", 279.0, "dynamic USB and XLR podcast microphone",
            program = "The MOTIV Mix app sets the sound and lighting.",
            connections = listOf("USB-C", "XLR"),
            extra = listOf("TYPE" to "Dynamic", "PATTERN" to "Cardioid", "AUDIO" to "up to 24-bit/48 kHz over USB"),
            notes = listOf("Real-time Denoiser and Auto Level Mode.", "Touch panel with an LED light bar.", "Replaced the MV7 (2020).")),
        Device("sm58", MIC, "Shure SM58", "Shure", 1966, "about $109", 109.0, "dynamic vocal microphone",
            connections = listOf("XLR (needs an audio interface or mixer)"),
            extra = listOf("TYPE" to "Dynamic", "PATTERN" to "Cardioid"),
            notes = listOf("The classic stage vocal mic, famously hard to break.", "The SM58S version adds an on/off switch.")),
        Device("yeti", MIC, "Logitech G Yeti", "Logitech", 2009, "$129.99", 129.99, "USB condenser microphone",
            connections = listOf("USB (mini-USB)"),
            extra = listOf("TYPE" to "Condenser", "PATTERN" to "4 patterns: cardioid, omnidirectional, bidirectional and stereo", "AUDIO" to "16-bit/48 kHz"),
            notes = listOf("Sold for years as the Blue Yeti; Logitech bought Blue in 2018.", "Headphone jack with zero-latency monitoring, a gain knob and a mute button.", "Picks up a lot of room sound, so it's best in a quiet room.")),
        Device("quadcasts", MIC, "HyperX QuadCast S", "HyperX", 2020, "$159.99", 159.99, "USB condenser microphone with RGB",
            connections = listOf("USB-C"),
            extra = listOf("TYPE" to "Condenser", "PATTERN" to "4 patterns: cardioid, omnidirectional, bidirectional and stereo", "AUDIO" to "16-bit/48 kHz"),
            notes = listOf("Tap the top to mute.", "Built-in shock mount and pop filter.")),
        Device("podmic", MIC, "Rode PodMic", "Rode", 2019, "$99", 99.0, "dynamic podcast microphone",
            connections = listOf("XLR (needs an audio interface or mixer)"),
            extra = listOf("TYPE" to "Dynamic", "PATTERN" to "Cardioid"),
            notes = listOf("All-metal build with an internal pop filter.", "The PodMic USB (2023) adds USB-C.")),
        Device("podmicusb", MIC, "Rode PodMic USB", "Rode", 2023, "$199", 199.0, "dynamic USB and XLR podcast microphone",
            program = "Rode Central and Rode Connect set the sound.", connections = listOf("USB-C", "XLR"),
            extra = listOf("TYPE" to "Dynamic", "PATTERN" to "Cardioid", "AUDIO" to "24-bit/48 kHz over USB"),
            notes = listOf("Revolution Preamp and APHEX processing built in.")),
        Device("wave3", MIC, "Elgato Wave:3", "Elgato", 2020, "$149.99", 149.99, "USB condenser streaming microphone",
            program = "Wave Link software mixer: blend game, music and chat into separate mixes for you and your stream.",
            connections = listOf("USB-C"),
            extra = listOf("TYPE" to "Condenser", "PATTERN" to "Cardioid", "AUDIO" to "24-bit, up to 96 kHz"),
            notes = listOf("Clipguard keeps loud moments from distorting.", "Touch-to-mute on top.")),
        Device("at2020", MIC, "Audio-Technica AT2020", "Audio-Technica", null, "about $99", 99.0, "studio condenser microphone",
            connections = listOf("XLR (needs an audio interface with 48 V phantom power)"),
            extra = listOf("TYPE" to "Condenser", "PATTERN" to "Cardioid"),
            notes = listOf("A go-to first studio mic for vocals and instruments.", "Also sold as the AT2020USB-X with USB-C.")),
    )

    fun byId(id: String): Device? = DEVICES.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- names

    private data class Name(val id: String, val rx: Regex)

    private fun n(id: String, rx: String) = Name(id, Regex("(?<![a-z0-9])(?:$rx)(?![a-z0-9])"))

    private val NAMES = listOf(
        n("g915tkl", "(?:logitech )?g ?915 (?:x )?tkl(?: lightspeed| wireless)?"),
        n("g915", "(?:logitech )?g ?915(?: x)?(?: lightspeed| wireless)?"),
        n("prox60", "(?:logitech )?(?:g )?pro x ?60(?: lightspeed| wireless)?"),
        n("proxtkl", "(?:logitech )?(?:g )?pro x tkl(?: lightspeed| wireless)?"),
        n("superlight2", "(?:logitech )?(?:g )?(?:pro )?(?:x )?superlight ?(?:2|two)(?: dex| c)?|gpx ?2"),
        n("superlight", "(?:logitech )?(?:g )?(?:pro )?(?:x )?superlight"),
        n("gprowireless", "(?:logitech )?g ?pro wireless(?: mouse)?"),
        n("g502xplus", "(?:logitech )?g ?502 x(?: plus| lightspeed| wireless)?"),
        n("g502", "(?:logitech )?g ?502(?: hero| se| lightspeed)?"),
        n("g305", "(?:logitech )?g ?305(?: lightspeed)?"),
        n("mxmaster3s", "(?:logitech )?mx master(?: 3s| 3| 2s)?(?! ?4)"),
        n("mxanywhere3s", "(?:logitech )?mx anywhere(?: 3s| 3)?"),
        n("mxkeyss", "(?:logitech )?mx keys(?: s)?(?! mini)"),
        n("mxmech", "(?:logitech )?mx mechanical(?: mini)?"),
        n("magicmouse", "(?:apple )?magic mouse(?: 2)?"),
        n("magickb", "(?:apple )?magic keyboard(?! (?:for ipad|folio))"),
        n("bwv4pro", "(?:razer )?black ?widow v4 pro(?! 75)"),
        n("bwv475", "(?:razer )?black ?widow v4 75(?: ?%| percent)?"),
        n("huntsmanv3pro", "(?:razer )?huntsman v3 pro(?: tkl| mini| 8 ?k(?:hz)?)?"),
        n("dav3pro", "(?:razer )?death ?adder v3 pro"),
        n("viperv3pro", "(?:razer )?viper v3 pro"),
        n("basiliskv3pro", "(?:razer )?basilisk v3 pro"),
        n("nagav2pro", "(?:razer )?naga v2 pro"),
        n("k70pro", "(?:corsair )?k70 (?:rgb )?pro"),
        n("k70max", "(?:corsair )?k70 max"),
        n("k100", "(?:corsair )?k100(?: rgb)?"),
        n("k65plus", "(?:corsair )?k65 plus(?: wireless)?"),
        n("apexpro", "(?:steelseries )?apex pro(?: tkl)?(?: wireless)?(?: gen ?3)?"),
        n("azoth", "(?:asus )?(?:rog )?azoth(?! x| extreme)"),
        n("q1pro", "(?:keychron )?q1 pro"),
        n("k2pro", "(?:keychron )?k2 pro"),
        n("v1", "keychron v1"),
        n("wooting80he", "(?:wooting )?80 ?he"),
        n("wooting60he", "(?:wooting )?60 ?he\\+?"),
        n("alloycore", "(?:hyperx )?alloy origins(?: core)?"),
        n("duckyone3", "ducky one ?3"),
        n("gmmkpro", "(?:glorious )?gmmk pro"),
        n("aerox3w", "(?:steelseries )?aerox 3(?: wireless)?"),
        n("rival3", "(?:steelseries )?rival 3"),
        n("modelo", "(?:glorious )?model o(?: wireless)?"),
        n("ec2c", "zowie ec2(?: ?-?c)?|ec2 ?-?c"),
        n("streamdeckplus", "(?:elgato )?stream ?deck ?(?:\\+|plus)(?! ?xl)"),
        n("streamdeckxl", "(?:elgato )?stream ?deck xl"),
        n("streamdeckmini", "(?:elgato )?stream ?deck mini"),
        n("streamdeckneo", "(?:elgato )?stream ?deck neo"),
        n("streamdeckpedal", "(?:elgato )?stream ?deck pedal"),
        n("streamdeck", "(?:elgato )?stream ?decks?(?: mk\\.? ?2)?(?! (?:studio|modules?|scissor|\\+|plus|xl|mini|neo|pedal))"),
        n("sm7db", "(?:shure )?sm ?7 ?db"),
        n("sm7b", "(?:shure )?sm ?7 ?b?"),
        n("mv7plus", "(?:shure )?mv ?7(?: ?\\+| plus)?"),
        n("sm58", "(?:shure )?sm ?58s?"),
        n("yeti", "blue yeti(?: mic| microphone)?|(?:logitech )?g yeti|logitech yeti|yeti (?:mic|microphone)s?"),
        n("quadcasts", "(?:hyperx )?quad ?cast(?: s| 2(?: s)?)?"),
        n("podmicusb", "(?:rode )?pod ?mic usb"),
        n("podmic", "(?:rode )?pod ?mic"),
        n("wave3", "elgato wave ?3|wave ?3 (?:mic|microphone)"),
        n("at2020", "audio ?-?technica at ?2020(?: ?usb)?|at2020(?: ?usb)?"),
    )

    // ---------------------------------------------------------------- wants

    private val WANTS: List<Pair<GearWant, Regex>> = listOf(
        GearWant.HOTSWAP to Regex("\\bhot ?-?swap\\w*\\b|\\bswap(?:pable)? (?:the |its |out )?switch(?:es)?\\b|\\bchange (?:the )?switch(?:es)?\\b|\\breplace (?:the )?switch(?:es)?\\b"),
        GearWant.POLLING to Regex("\\bp[ou]l+ing\\b|\\breport(?:ing)? rate\\b|\\bhz\\b|\\bhertz\\b"),
        GearWant.BATTERY to Regex("\\bbattery\\b|\\bhow long (?:does|do|will|can|would) (?:it|they|the [a-z0-9 ]+?) (?:last|go|run)\\b|\\bper charge\\b|\\bcharge last\\b|\\blast on a charge\\b"),
        GearWant.KEYCAPS to Regex("\\bkey ?caps?\\b|\\bpbt\\b|\\babs\\b"),
        GearWant.SIDE to Regex("\\bside (?:buttons?|scroll(?: ?wheel)?|wheel)\\b|\\bthumb (?:buttons?|wheel|scroll)\\b|\\bhow many side buttons\\b|\\bsniper\\b"),
        GearWant.WHEEL to Regex("\\b(?:scroll ?)?wheel\\b|\\bscroll(?:ing)?\\b|\\binfinite\\b|\\bfree ?-?spin\\w*\\b|\\bstepped\\b|\\bratchet\\w*\\b|\\btilt\\b"),
        GearWant.BUTTONS to Regex("\\bbuttons?\\b"),
        GearWant.SWITCHES to Regex("\\bswitch(?:es)?\\b|\\blinear\\b|\\btactile\\b|\\bclicky\\b|\\bactuation\\b"),
        GearWant.PROGRAM to Regex("\\bprogramm?(?:able|ing)?\\b|\\bmacros?\\b|\\bremap\\w*\\b|\\bsoftware\\b|\\bqmk\\b|\\bvia\\b|\\bcustomi[sz]\\w*\\b|\\brebind\\w*\\b|\\bbind\\b|\\bonboard memory\\b|\\bapp\\b"),
        GearWant.MIC_TYPE to Regex("\\bdynamic\\b|\\bcondenser\\b|\\bwhat (?:kind|type) of (?:mic|microphone)\\b"),
        GearWant.PATTERN to Regex("\\bpolar\\b|\\bpatterns?\\b|\\bcardioid\\b|\\bomni\\w*\\b"),
        GearWant.SENSOR to Regex("\\bsensor\\b|\\bdpi\\b|\\bcpi\\b"),
        GearWant.WEIGHT to Regex("\\bweigh(?:t|s)?\\b|\\bheavy\\b|\\bhow light\\b|\\blightweight\\b|\\bgrams\\b"),
        GearWant.WIRELESS to Regex("\\bwireless\\b|\\bcordless\\b|\\bwired\\b|\\bcable\\b"),
        GearWant.CONNECTION to Regex("\\bconnect(?:s|ion|ions|ivity|ed)?\\b|\\bbluetooth\\b|\\b2 ?4 ?g(?:hz)?\\b|\\b2\\.4\\b|\\bdongle\\b|\\breceiver\\b|\\busb\\b|\\bxlr\\b|\\bplug (?:in|into)\\b|\\binterface\\b"),
        GearWant.KEYS to Regex("\\bhow many keys\\b|\\bdials?\\b|\\bknobs?\\b|\\bkeys\\b|\\bbuttons?\\b|\\bpedals?\\b"),
        GearWant.PRICE to Regex("\\bprices?\\b|\\bcosts?\\b|\\bhow much\\b|\\bmsrp\\b|\\bexpensive\\b|\\bcheap\\b"),
        GearWant.RELEASE to Regex("\\bwhen (?:did|was|were)\\b.*\\b(?:come out|released?|launch(?:ed)?|out|made)\\b|\\brelease date\\b|\\bcame out\\b|\\bhow old\\b|\\bwhat year\\b"),
        GearWant.MAKER to Regex("\\bwho (?:makes|made|sells)\\b|\\bwhat (?:company|brand)\\b|\\bmade by\\b|\\bmaker\\b"),
        GearWant.NOTES to Regex("\\bnotes?\\b|\\bknown for\\b|\\bspecial about\\b|\\bworth it\\b|\\bpros\\b|\\bcons\\b|\\bstand out\\b|\\bgood for\\b"),
    )

    // ---------------------------------------------------------------- parsing

    private data class Hit(val id: String, val start: Int, val end: Int)

    private fun hits(s: String): List<Hit> {
        val all = NAMES.flatMap { nm -> nm.rx.findAll(s).map { Hit(nm.id, it.range.first, it.range.last + 1) }.toList() }
        val kept = ArrayList<Hit>()
        for (h in all.sortedWith(compareBy<Hit>({ -(it.end - it.start) }, { it.start }))) {
            if (kept.none { it.start < h.end && h.start < it.end }) kept += h
        }
        return kept.sortedBy { it.start }.distinctBy { it.id }
    }

    private val PREFIX = Regex("^(?:(?:hey|hi|ok|okay|yo) )?(?:brick[, ]+)?(?:(?:can|could|would) you (?:please )?|please |i (?:want|need|wanna|would like) to know |i'?m curious |do you know |quick question:? )*")
    private val ACTION = Regex("^(?:please )?(?:turn|power|switch (?:on|off|to)|open|close|launch|start|play|connect|pair|buy|order|sell|install|uninstall|update|upgrade|download|reset|restart|find|track|remind|set|text|call|navigate|record|clean|fix|repair|return|charge)\\b")
    private val HOW_TO = Regex("\\bhow (?:do|can|to|should|would) (?:i|you|we)\\b(?! (?:know|tell))|\\bhow to\\b|\\bwhere (?:can|do|to) (?:i )?(?:buy|get|find)\\b|\\b(?:deals?|on sale|in stock|restock|discounts?|coupons?|for sale)\\b")
    /** Trouble with one ("my g502 is double clicking"): not a spec question. */
    private val TROUBLE = Regex("\\b(?:not working|doesn'?t work|won'?t (?:work|connect|charge|turn on|pair)|stopped working|broken|broke|double ?-?click(?:ing|s)|stuck|lagging|keeps (?:disconnecting|cutting out)|disconnect(?:ing|s)|died|dead|drivers?|firmware update|manual|warranty|replacement|reset)\\b")

    /** A question about a keyboard, mouse, stream controller or mic, or two of them compared; null otherwise. */
    fun parse(raw: String): GearAsk? {
        var s = raw.lowercase(Locale.US).replace(Regex("[?!,;:\"()]+"), " ").replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
            .replace('’', '\'').replace(Regex("\\s+"), " ").trim()
        s = s.replace(PREFIX, "").trim()
        if (s.isEmpty() || ACTION.containsMatchIn(s) || HOW_TO.containsMatchIn(s) || TROUBLE.containsMatchIn(s)) return null
        // Devices in Brick's notes, then ones it can look up on the maker's site ("razer cobra pro", "mx master 4").
        val found = ArrayList(hits(s).take(2).map { Triple(it.id, it.start, it.end) })
        if (found.size < 2 && !NOT_GEAR.containsMatchIn(s)) {
            var masked = s
            for ((_, a, b) in found) masked = masked.substring(0, a) + " ".repeat(b - a) + masked.substring(b)
            for (u in unlisted(masked).take(2 - found.size)) found += u
        }
        if (found.isEmpty()) return null
        found.sortBy { it.second }
        var ids = found.map { it.first }
        // Two of a kind compare; a keyboard and a mouse don't.
        if (ids.size == 2 && kindOf(ids[0]) != null && kindOf(ids[1]) != null && kindOf(ids[0]) != kindOf(ids[1])) ids = ids.take(1)
        // Wants are read with the names cut out ("g pro x 60 polling" mustn't read "x" or "60" as anything).
        var rest = s
        for ((_, a, b) in found.sortedByDescending { it.second }) rest = rest.substring(0, a) + " " + rest.substring(b)
        rest = rest.replace(Regex("\\s+"), " ").trim()
        val kind = kindOf(ids[0]) ?: guessKind(s)
        val want = WANTS.firstOrNull { (w, rx) -> rx.containsMatchIn(rest) && (kind == null || fits(w, kind)) }?.first ?: GearWant.OVERVIEW
        return GearAsk(ids, want, raw)
    }

    // ---------------------------------------------------------------- gear that isn't in the notes

    /** Live ids look like "live|Razer|cobra pro". */
    private const val LIVE = "live|"
    fun isLive(id: String) = id.startsWith(LIVE)
    private fun liveParts(id: String) = id.removePrefix(LIVE).split('|').let { it[0] to it.getOrElse(1) { "" } }

    /** "Razer Cobra Pro" for a live id, the device's name otherwise. */
    fun label(id: String): String = byId(id)?.name ?: liveParts(id).let { (m, model) -> "$m ${model.split(' ').joinToString(" ") { w -> if (w.any(Char::isDigit) || w.length <= 2) w.uppercase(Locale.US) else w.replaceFirstChar { it.uppercase() } }}" }

    private fun kindOf(id: String): Kind? = byId(id)?.kind

    private fun guessKind(s: String): Kind? = when {
        Regex("\\b(?:mouse|mice|dpi|sensor|side buttons?|scroll)\\b").containsMatchIn(s) -> Kind.MOUSE
        Regex("\\b(?:keyboard|keycaps?|switches|hot ?-?swap\\w*)\\b").containsMatchIn(s) -> Kind.KEYBOARD
        Regex("\\b(?:mic|microphone|cardioid|condenser|dynamic)\\b").containsMatchIn(s) -> Kind.MIC
        else -> null
    }

    private val BRANDS = listOf("Logitech" to "logitech(?: g)?", "Razer" to "razer", "SteelSeries" to "steel ?series", "Wooting" to "wooting",
        "ASUS" to "asus rog|asus|rog", "Zowie" to "benq zowie|zowie", "Keychron" to "keychron", "HyperX" to "hyper ?x", "Glorious" to "glorious",
        "Corsair" to "corsair", "Elgato" to "elgato", "Shure" to "shure")
    /** Makers that sell nothing but keyboards and mice, so their name alone means gear. */
    private val GEAR_ONLY = setOf("Keychron", "Wooting", "Glorious", "Zowie")
    /** Product families that name their maker ("deathadder" is Razer). [loose] ones ("viper", "apex") also need a model word or a gear word. */
    private data class Family(val maker: String, val rx: Regex, val loose: Boolean)
    private fun fam(maker: String, rx: String, loose: Boolean = false) = Family(maker, Regex("\\b(?:$rx)\\b"), loose)
    private val FAMILIES = listOf(
        fam("Logitech", "mx (?:master|anywhere|keys|mechanical|ergo|vertical)|g ?\\d{3}|g pro|pro x superlight|superlight|pop (?:mouse|keys)|signature m\\d+"),
        fam("Razer", "deathadder|basilisk|blackwidow|huntsman|ornata|deathstalker|orochi|seiren"),
        fam("Razer", "viper|naga|cobra", loose = true),
        fam("SteelSeries", "aerox|sensei"),
        fam("SteelSeries", "apex|rival|prime", loose = true),
        fam("HyperX", "pulsefire|quadcast|duocast|solocast"),
        fam("HyperX", "alloy", loose = true),
        fam("Glorious", "gmmk|model [oid]"),
        fam("ASUS", "azoth|harpe|keris|gladius|falchion|strix scope|chakram|spatha"),
        fam("Wooting", "\\d0 ?he|two ?he"),
        fam("Corsair", "k(?:55|57|60|63|65|68|70|95|100)|m(?:55|65|75)|scimitar|darkstar|nightsabre|ironclaw|harpoon|katar|glaive|dark core"),
        fam("Corsair", "sabre", loose = true),
        fam("Elgato", "stream ?deck|wave ?(?::? ?\\d|neo|xlr|dx|go)"),
        fam("Shure", "sm ?\\d{1,3}[a-z]{0,2}|mv ?\\d{1,2}[a-z+]{0,2}|nexadyne|ksm ?\\d{2,3}[a-z]{0,2}|pga ?\\d{2,3}[a-z]?|beta ?\\d{2,3}[a-z]{0,2}|super ?55|mv7\\+?"),
    )
    /** Not a keyboard, mouse or mic: laptops, headsets, chairs, the company itself. */
    private val NOT_GEAR = Regex("\\b(?:blade|laptops?|headsets?|headphones?|earbuds?|kraken|barracuda|blackshark|arctis|cloud(?: ii| iii| alpha| flight)?|phones?|chairs?|controllers?|wolverine|kishi|webcams?|kiyo|facecam|key ?lights?|capture cards?|hd60|4k60|aonic|in-?ear|power supply|psus?|ddr\\d|coolers?|fans?|ssds?|speakers?|nommo|monitors?|mouse ?pads?|mousepads?|desk ?mats?|stocks?|shares|ceo|founders?|founded|company|headquarters|careers?|legends|video|stream(?:ing)? platform|movie|game|games|snake|snakes|car|cars)\\b")
    private val MODEL_STOP = setOf("is", "are", "does", "do", "did", "have", "has", "had", "vs", "versus", "or", "and", "compared", "to", "with", "what", "whats",
        "how", "polling", "pulling", "rate", "battery", "life", "last", "lasts", "weigh", "weighs", "weight", "switches", "switch", "keycaps", "keycap", "hot",
        "hotswap", "swappable", "programmable", "connect", "connects", "connection", "bluetooth", "sensor", "dpi", "side", "buttons", "button", "scroll", "wheel",
        "price", "cost", "costs", "good", "worth", "mouse", "keyboard", "mic", "microphone", "specs", "spec", "the", "a", "an", "it", "its", "of", "for", "on",
        "in", "use", "uses", "come", "comes", "much", "many", "long", "heavy", "light", "wireless", "wired", "really", "any", "against", "than", "better", "worse",
        "infinite", "free", "spin", "tilt", "maximum", "type", "kind", "pattern", "polar", "dynamic", "condenser", "made", "make", "by", "who", "when", "released",
        "come", "out", "new", "newest", "latest")
    private val MODEL_WORD = Regex("^(?:\\d.*|.*\\d.*|pro|x|v\\d|mini|ultimate|elite|hyperspeed|essential|tkl|plus|max|core|lite|se|dex|ultra|origins|haste|wireless)$")

    /** Gear not in Brick's notes but on a maker's site Brick can read: (live id, start, end) for each one named. */
    private fun unlisted(s: String): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        val starts = ArrayList<Triple<String, Int, Int>>() // maker, where the model starts, where the name starts
        for ((maker, rx) in BRANDS) Regex("\\b(?:$rx)\\b").findAll(s).forEach { starts += Triple(maker, it.range.last + 1, it.range.first) }
        for (f in FAMILIES) f.rx.findAll(s).forEach { m ->
            if (starts.none { it.third <= m.range.first && m.range.first <= it.second + 1 }) starts += Triple(f.maker + if (f.loose) "?" else "", m.range.first, m.range.first)
        }
        for ((maker0, from, nameStart) in starts.sortedBy { it.third }) {
            if (out.any { nameStart < it.third }) continue
            val loose = maker0.endsWith("?")
            val maker = maker0.removeSuffix("?")
            val words = ArrayList<String>()
            var end = from
            for (m in Regex("[a-z0-9+%-]+").findAll(s, from.coerceAtMost(s.length))) {
                val w = m.value
                if (w in MODEL_STOP || words.size >= 5 || s.substring(end, m.range.first).isNotBlank()) break
                words += w; end = m.range.last + 1
            }
            if (words.isEmpty()) continue
            val model = words.joinToString(" ")
            // "viper" alone is a snake: loose families need a model word ("viper v3 pro") or a gear word nearby.
            if (loose && words.drop(1).none { MODEL_WORD.matches(it) } && guessKind(s) == null) continue
            // Makers of more than gear ("asus rog strix b650", "rog ally") count only with a product line or a gear word.
            if (from != nameStart && maker !in GEAR_ONLY && guessKind(s) == null && FAMILIES.none { it.maker == maker && (it.rx.find(s, nameStart)?.range?.first ?: Int.MAX_VALUE) <= from + 1 }) continue
            if (GearLive.tokens(model).isEmpty()) continue
            out += Triple("$LIVE$maker|$model", nameStart, end)
        }
        return out
    }

    private fun fits(w: GearWant, k: Kind) = when (w) {
        GearWant.SWITCHES, GearWant.KEYCAPS, GearWant.HOTSWAP -> k == Kind.KEYBOARD
        GearWant.SIDE, GearWant.WHEEL, GearWant.SENSOR, GearWant.BUTTONS -> k == Kind.MOUSE
        GearWant.MIC_TYPE, GearWant.PATTERN -> k == Kind.MIC
        GearWant.KEYS -> k == Kind.CONTROLLER
        GearWant.BATTERY, GearWant.POLLING -> k == Kind.KEYBOARD || k == Kind.MOUSE
        else -> true
    }

    // ---------------------------------------------------------------- answers

    // "a USB mic" (said "you-ess-bee"), "an RGB keyboard" (said "ar-gee-bee").
    internal fun an(word: String) = when {
        Regex("^U[A-Z]").containsMatchIn(word) -> "a"
        Regex("^[FHLMNRSX][A-Z0-9]").containsMatchIn(word) -> "an"
        else -> if (word.first().lowercaseChar() in "aeiou8" || word.startsWith("18")) "an" else "a"
    }
    private fun list(items: List<String>): String = when (items.size) { 0 -> ""; 1 -> items[0]; 2 -> "${items[0]} or ${items[1]}"; else -> items.dropLast(1).joinToString(", ") + " or " + items.last() }
    /** Names that keep their capital mid-sentence ("uses Logitech GL switches", "uses Cherry MX"). */
    private val PROPER = setOf("Logitech", "Logi", "Razer", "Corsair", "Cherry", "Kailh", "Keychron", "Lekker", "HyperX", "Glorious", "SteelSeries",
        "Wootility", "Elgato", "Stream", "Rode", "Wave", "Shure", "Apple", "Focus", "Open-source", "Armoury", "Gateron", "Darkfield", "Optical")
    private fun lowerFirst(s: String): String {
        val w = s.substringBefore(' ')
        return if (s.length > 1 && s[1].isLowerCase() && w !in PROPER) s[0].lowercaseChar() + s.substring(1) else s
    }
    private fun the(d: Device) = "the ${d.short}"
    private fun The(d: Device) = "The ${d.short}"
    private fun sentence(s: String) = s.trimEnd('.') + "."
    private fun connectSay(d: Device) = list(d.connections.filterNot { it.contains("charging") }.map { c ->
        when {
            c.startsWith("Wired") -> "a " + (Regex("\\((.+)\\)").find(c)?.groupValues?.get(1)?.let { "$it " } ?: "") + "cable"
            else -> c.substringBefore(" (")
        }
    })
    private fun switchShort(d: Device) = d.switches?.substringBefore(":")?.substringBefore(", with")?.substringBefore(" (")

    fun answer(d: Device, want: GearWant): String = when (want) {
        GearWant.SWITCHES -> d.switches?.let { if (it.startsWith("none")) "${The(d)} comes with no switches: ${it.substringAfter(": ")}." else "${The(d)} uses ${lowerFirst(it)}." } ?: missing(d, "the switches")
        GearWant.KEYCAPS -> d.keycaps?.let { if (it.startsWith("none")) "${The(d)} comes with no keycaps in the barebone kit." else "${The(d)} ships with ${lowerFirst(it)}." } ?: missing(d, "the keycaps")
        GearWant.HOTSWAP -> d.hotSwap?.let { h ->
            if (h.startsWith("Yes")) "Yes, ${the(d)} is hot-swappable: ${lowerFirst(h.removePrefix("Yes, ").removePrefix("Yes"))}."
            else "No, ${the(d)} isn't hot-swappable: ${lowerFirst(h.removePrefix("No, ").removePrefix("No"))}."
        } ?: missing(d, "whether the switches are hot-swappable")
        GearWant.PROGRAM -> d.program?.let { sentence(it) } ?: "${The(d)} has no software to program it."
        GearWant.WIRELESS -> if (d.wireless) "Yes, ${the(d)} is wireless: it connects by ${connectSay(d)}." else "No, ${the(d)} is wired only, by ${connectSay(d)}."
        GearWant.BATTERY -> d.battery?.let { "${The(d)} lasts $it." } ?: if (!d.wireless) "${The(d)} is wired, so there's no battery." else missing(d, "the battery life")
        GearWant.POLLING -> d.polling?.let {
            if (it.startsWith("Not")) "${d.maker} doesn't publish a polling rate for ${the(d)}" + (if (it.contains("work")) "; it's built for work, not gaming." else ".")
            else "${The(d)}'s max polling rate is $it."
        } ?: missing(d, "a polling rate")
        GearWant.CONNECTION -> "${The(d)} connects by ${connectSay(d)}."
        GearWant.SIDE -> d.side?.let { "${The(d)} has $it." } ?: missing(d, "the side buttons")
        GearWant.WHEEL -> d.wheel?.let { if (it.startsWith("no wheel")) "${The(d)} has ${it}." else "${The(d)}'s wheel has $it." } ?: missing(d, "the scroll wheel modes")
        GearWant.SENSOR -> d.sensor?.let { "${The(d)} uses the ${it.replaceFirst(Regex("^Optical"), "an optical sensor")}." } ?: missing(d, "the sensor")
        GearWant.WEIGHT -> d.weightG?.let { "${The(d)} weighs about ${it.toInt()} g." } ?: missing(d, "the weight")
        GearWant.BUTTONS -> d.buttons?.let { "${The(d)} has $it buttons" + (d.side?.let { s -> ", with $s." } ?: ".") } ?: missing(d, "the number of buttons")
        GearWant.PRICE -> priceSay(d)
        GearWant.RELEASE -> d.year?.let { "${The(d)} came out in $it." } ?: overview(d)
        GearWant.MAKER -> "${The(d)} is made by ${d.maker}."
        GearWant.MIC_TYPE -> d.extra("TYPE")?.let { "${The(d)} is ${an(it)} ${it.lowercase(Locale.US)} microphone." } ?: missing(d, "whether it is dynamic or condenser")
        GearWant.PATTERN -> d.extra("PATTERN")?.let { "${The(d)}'s pickup pattern: ${lowerFirst(it)}." } ?: missing(d, "the pickup pattern")
        GearWant.KEYS -> d.extra("KEYS")?.let { "${The(d)} has ${lowerFirst(it)}" + (d.extra("DIALS")?.let { x -> ", plus ${lowerFirst(x)}." } ?: ".") } ?: overview(d)
        GearWant.NOTES -> d.notes.firstOrNull()?.let { "${The(d)}: ${lowerFirst(it)}" } ?: overview(d)
        GearWant.OVERVIEW -> overview(d)
    }

    private fun overview(d: Device): String {
        val what = "${The(d)} is ${an(d.form)} ${d.form}" + (d.year?.let { " from $it" } ?: "")
        val detail = when (d.kind) {
            Kind.KEYBOARD -> switchShort(d)?.takeUnless { it.startsWith("none") }?.let { " with ${lowerFirst(it)}" } ?: ""
            Kind.MOUSE -> d.weightG?.let { " that weighs about ${it.toInt()} g" } ?: ""
            Kind.CONTROLLER -> d.extra("KEYS")?.let { " with ${lowerFirst(it)}" } ?: ""
            Kind.MIC -> " that connects by ${connectSay(d).replace(Regex(" \\(.*\\)"), "")}"
        }
        return "$what$detail. " + priceSay(d).replaceFirst(The(d), "It")
    }

    private fun money(p: Double) = "$" + String.format(Locale.US, "%,.2f", p)

    /** Launch price, and today's price on the maker's site when Brick could read it. */
    private fun priceSay(d: Device): String {
        val now = d.now?.let { "${money(it)} on ${d.maker}'s site today" }
        return when {
            d.liveOnly -> now?.let { "${The(d)} is $it." } ?: "${d.maker}'s page doesn't show a price for ${the(d)}."
            now != null && d.priceUsd != null && Math.abs(d.priceUsd - d.now) >= 1 -> "${The(d)} launched at ${d.price} and is $now."
            now != null -> "${The(d)} is $now."
            else -> "${The(d)} launched at ${d.price}."
        }
    }

    /** A spec the maker's page didn't give (live gear), or the overview. */
    private fun missing(d: Device, what: String) = if (d.liveOnly) "${d.maker}'s page doesn't list $what for ${the(d)}." else overview(d)

    // ---------------------------------------------------------------- comparing

    fun versus(a: Device, b: Device): Versus {
        fun fmt(d: Double) = if (d % 1.0 == 0.0) "%,d".format(Locale.US, d.toLong()) else d.toString()
        val bars = ArrayList<Versus.Bar>()
        if (a.kind == Kind.MOUSE) {
            bars += Versus.Bar("WEIGHT", a.weightG, b.weightG, a.weightG?.let { "${it.toInt()} g" }, b.weightG?.let { "${it.toInt()} g" }, "less weight", lower = true)
            bars += Versus.Bar("MAX DPI", a.dpi?.toDouble(), b.dpi?.toDouble(), a.dpi?.let { fmt(it.toDouble()) }, b.dpi?.let { fmt(it.toDouble()) }, "a higher max DPI")
            bars += Versus.Bar("BUTTONS", a.buttons?.toDouble(), b.buttons?.toDouble(), a.buttons?.toString(), b.buttons?.toString(), "more buttons")
        }
        if (a.kind == Kind.KEYBOARD || a.kind == Kind.MOUSE) {
            bars += Versus.Bar("POLLING", a.pollingHz?.toDouble(), b.pollingHz?.toDouble(), a.pollingHz?.let { "${fmt(it.toDouble())} Hz" }, b.pollingHz?.let { "${fmt(it.toDouble())} Hz" }, "a faster polling rate")
            bars += Versus.Bar("BATTERY", a.batteryH, b.batteryH, a.batteryH?.let { "${fmt(it)} h" }, b.batteryH?.let { "${fmt(it)} h" }, "longer battery life")
        }
        bars += Versus.Bar("PRICE", a.priceUsd, b.priceUsd, a.priceUsd?.let { "$" + fmt(it) }, b.priceUsd?.let { "$" + fmt(it) }, "a lower price", lower = true)
        val words = when (a.kind) {
            Kind.KEYBOARD -> listOf(
                Versus.Words("TYPE", a.form, b.form),
                Versus.Words("SWITCH", switchShort(a), switchShort(b)),
                Versus.Words("KEYCAPS", a.keycaps, b.keycaps),
                Versus.Words("HOTSWAP", a.hotSwap?.substringBefore(","), b.hotSwap?.substringBefore(",")),
            )
            Kind.MOUSE -> listOf(
                Versus.Words("TYPE", a.form, b.form),
                Versus.Words("SENSOR", a.sensor?.substringBefore(","), b.sensor?.substringBefore(",")),
                Versus.Words("SIDE", a.side, b.side),
                Versus.Words("WHEEL", a.wheel, b.wheel),
            )
            else -> (a.extra.map { it.first } + b.extra.map { it.first }).distinct().map { l -> Versus.Words(l, a.extra(l), b.extra(l)) } +
                listOf(Versus.Words("TYPE", a.form, b.form))
        } + listOf(
            Versus.Words("CONNECT", a.connections.joinToString("; "), b.connections.joinToString("; ")),
            Versus.Words("OUT", a.year?.toString(), b.year?.toString()),
        )
        val kept = bars.filter { it.a != null && it.b != null }
        return Versus(a.name, b.name, kept, words.filter { it.a != null || it.b != null }, Tech.verdict(a.short, b.short, kept))
    }

    // ---------------------------------------------------------------- the card

    /** The KEYBOARD, MOUSE, STREAM DECK or MIC card, or the VERSUS card for two. */
    suspend fun find(ask: GearAsk): LookupResult? = withContext(Dispatchers.IO) {
        // Up to two maker sites at once.
        val devices = ask.ids.map { id -> async { runCatching { resolve(id) }.getOrNull() } }.awaitAll().filterNotNull()
        if (devices.isEmpty()) return@withContext null
        if (devices.size == 2 && devices[0].kind == devices[1].kind) {
            val (a, b) = devices
            val v = versus(a, b)
            return@withContext LookupResult(a.name, null, null, "", null, web(a), emptyList(), v.verdict,
                sources = sources(devices), versus = v, links = listOf(b.name to web(b)),
                more = listOf("NOTE" to listOf("Specs are the makers' own; battery life depends on lighting and polling rate.")))
        }
        val d = devices[0]
        LookupResult(d.name, null, null, "", null, web(d), emptyList(), answer(d, ask.want),
            sources = sources(devices), heading = d.kind.heading, peripheral = d)
    }

    private fun sources(ds: List<Device>) = listOfNotNull(
        if (ds.any { it.liveUrl != null }) "the makers' sites (live)" else null,
        if (ds.any { !it.liveOnly }) "Brick's gear notes" else null)

    private fun web(d: Device) = d.liveUrl ?: ("https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(d.name, "UTF-8"))

    /** A device from the notes with today's price and any missing specs from the maker's site, or one built from the maker's site alone. */
    private fun resolve(id: String): Device? {
        if (isLive(id)) { val (maker, model) = liveParts(id); return GearLive.lookup(maker, model)?.let { fromLive(id, maker, model, it) } }
        val d = byId(id) ?: return null
        if (!GearLive.covers(d.maker)) return d
        val f = GearLive.lookup(d.maker, d.short) ?: return d
        if (f.kind != null && f.kind != d.kind) return d
        return d.copy(
            liveUrl = f.url, now = f.price,
            pollingHz = d.pollingHz ?: f.polling, polling = d.polling ?: f.polling?.let { hz(it) },
            battery = d.battery ?: f.battery, batteryH = d.batteryH ?: f.batteryH,
            dpi = d.dpi ?: f.dpi, weightG = d.weightG ?: f.weightG, wheel = d.wheel ?: f.wheel,
        )
    }

    private fun hz(n: Int) = String.format(Locale.US, "%,d Hz", n)

    private fun fromLive(id: String, maker: String, model: String, f: GearLive.Facts): Device? {
        val kind = f.kind ?: guessKind(model) ?: return null
        val wireless = f.connections.any { !it.startsWith("Wired") && it != "XLR" }
        // "MX Master 4 Wireless Mouse" -> "MX Master 4": the card already says what kind of device it is.
        val title = f.title?.replace(Regex("(?i)(?:\\s+(?:wireless|wired|gaming|rgb|mechanical|ergonomic|performance|usb|lightweight|optical|dynamic|condenser|vocal|podcast))*\\s+(?:mouse|keyboard|microphone|mic)$"), "")
            ?.trim()?.takeIf { it.length in 3..80 } ?: label(id).removePrefix("$maker ")
        val name = if (title.contains(maker, ignoreCase = true)) title else "$maker $title"
        val kindWord = when (kind) { Kind.MIC -> "microphone"; else -> kind.noun }
        val sensor = f.sensor?.let { s -> "$s sensor" + (f.dpi?.let { ", up to ${String.format(Locale.US, "%,d", it)} DPI" } ?: "") } ?: f.dpi?.let { "up to ${String.format(Locale.US, "%,d", it)} DPI" }
        return Device(id, kind, name, maker, null, f.price?.let { money(it) + " (today)" } ?: "Not listed", f.price,
            (if (kind == Kind.MIC) "" else if (wireless) "wireless " else "wired ") + kindWord,
            switches = f.switches?.let { if (it.contains("switch", true)) it else "$it switches" }, keycaps = f.keycaps,
            hotSwap = if (f.hotSwap) "Yes, according to $maker's page" else null,
            sensor = sensor, dpi = f.dpi, weightG = f.weightG, buttons = f.buttons, wheel = f.wheel,
            program = f.software.takeIf { it.isNotEmpty() }?.let { "Programmable in ${list(it).replace(" or ", " and ")}." },
            connections = f.connections.ifEmpty { listOf("See $maker's page") }, battery = f.battery, batteryH = f.batteryH,
            polling = f.polling?.let { hz(it) }, pollingHz = f.polling,
            extra = listOfNotNull(f.micType?.let { "TYPE" to it }, f.pattern?.let { "PATTERN" to it }, f.audio?.let { "AUDIO" to it }, f.keys?.let { "KEYS" to "$it customizable keys" }),
            notes = listOf("Read live from $maker's website; Brick has no notes of its own on this one, so some specs may be missing."),
            liveUrl = f.url, now = f.price)
    }

    private fun kv(rows: MutableList<String>, label: String, value: String?, max: Int = 3) {
        if (!value.isNullOrBlank()) rows += Box.wrap(Box.ascii(value), first = Box.kv(label, ""), indent = 7, max = max)
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun text(d: Device, answer: String?): String {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(d.name), max = 2)
        rows += Box.wrap(Box.ascii(cap(d.form)) + (d.year?.let { ", $it" } ?: ""), max = 2)
        answer?.let { rows += ""; rows += Box.wrap(Box.ascii(it), "> ", indent = 2, max = 8) }
        rows += ""
        kv(rows, "MAKER", d.maker)
        if (!d.liveOnly) kv(rows, "PRICE", d.price)
        kv(rows, "NOW", d.now?.let { "${money(it)} on ${d.maker}'s site" })
        rows += ""
        when (d.kind) {
            Kind.KEYBOARD -> {
                kv(rows, "SWITCH", d.switches?.let { cap(it) })
                kv(rows, "KEYCAPS", d.keycaps?.let { cap(it) })
                kv(rows, "HOTSWAP", d.hotSwap)
            }
            Kind.MOUSE -> {
                kv(rows, "SENSOR", d.sensor)
                kv(rows, "WEIGHT", d.weightG?.let { "${it.toInt()} g" })
                kv(rows, "BUTTONS", d.buttons?.toString())
                kv(rows, "SIDE", d.side?.let { cap(it) })
                kv(rows, "WHEEL", d.wheel?.let { cap(it) }, max = 4)
            }
            else -> for ((l, v) in d.extra) kv(rows, l, v)
        }
        kv(rows, "PROGRAM", d.program, max = 4)
        rows += ""
        if (d.kind == Kind.KEYBOARD || d.kind == Kind.MOUSE) {
            kv(rows, "WIRELESS", if (d.wireless) "Yes" else "No, wired only")
            kv(rows, "BATTERY", d.battery?.let { cap(it) } ?: if (d.wireless) null else "None (wired)")
            kv(rows, "POLLING", d.polling?.let { cap(it) })
        }
        rows += "CONNECTS BY"
        for (c in d.connections) rows += Box.wrap(Box.ascii(c), "- ", indent = 2, max = 2)
        if (d.notes.isNotEmpty()) {
            rows += ""
            rows += "NOTES"
            for (n in d.notes) rows += Box.wrap(Box.ascii(n), "- ", indent = 2, max = 3)
        }
        rows += ""
        val source = when {
            d.liveOnly -> "${d.maker}'s website (live)"
            d.liveUrl != null -> "Brick's gear notes, ${d.maker}'s website (live)"
            else -> "Brick's gear notes (makers' specs)"
        }
        rows += Box.wrap(source, first = Box.kv("SOURCE", ""), indent = 7, max = 2)
        return Box.build(d.kind.heading, rows)
    }
}
