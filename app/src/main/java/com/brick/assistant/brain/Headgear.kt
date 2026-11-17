package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.Locale

/** What's asked about a pair of headphones, earbuds, a gaming headset or a VR headset. */
enum class HeadWant {
    OVERVIEW, ANC, BATTERY, WIRELESS, CONNECTION, CODECS, FIT, DRIVERS, MIC, WEIGHT, WATER, PLATFORMS, SPATIAL, IMPEDANCE,
    RESOLUTION, DISPLAY, REFRESH, FOV, TRACKING, CONTROLLERS, CHIP, STORAGE, PC, STANDALONE, PASSTHROUGH,
    PRICE, RELEASE, MAKER, NOTES,
    // Sound, reviews and speakers.
    SOUND, SCORE, LOUD, PARTY, POWERBANK,
}

/** One device ([ids] has two for a comparison). */
data class HeadAsk(val ids: List<String>, val want: HeadWant, val said: String) {
    val compare get() = ids.size > 1
}

/**
 * Headphones (wired and wireless, over- and on-ear), earbuds and IEMs, portable speakers, gaming headsets and VR headsets (standalone and PC VR)
 * from Brick's own notes: noise cancelling, battery, codecs, fit, drivers, mic, water resistance, what they work with;
 * for VR the screens, refresh rate, field of view, tracking, chip and whether it needs a PC. Two side by side.
 * Ones Brick has no notes on are read live: gaming headsets from the maker's site, other audio gear from SoundGuys' product listings
 * (specs, score and price only), then Wikipedia. Audio gear in the notes gets SoundGuys' score and today's price there.
 */
object Headgear {
    enum class Kind(val noun: String, val heading: String) {
        HEADPHONES("headphones", "HEADPHONES"), EARBUDS("earbuds", "EARBUDS"), HEADSET("gaming headset", "HEADSET"), VR("VR headset", "VR HEADSET"),
        SPEAKER("speaker", "SPEAKER");
        val audio get() = this != VR
        /** Worn audio, speakers and VR each compare only among themselves. */
        val group get() = when (this) { VR -> 2; SPEAKER -> 1; else -> 0 }
    }

    data class Device(
        val id: String, val kind: Kind, val name: String, val maker: String, val year: Int?,
        /** "$399.99"; [launch] false when it's a typical price today rather than the launch price. */
        val price: String, val priceUsd: Double?, val launch: Boolean = true,
        /** A noun phrase: "pair of wireless noise-cancelling over-ear headphones", "standalone VR headset". */
        val form: String,
        // Sound.
        val fit: String? = null,
        /** "Active noise cancelling, with Ambient Sound mode"; starts with "No" when it has none. */
        val anc: String? = null,
        val battery: String? = null, val batteryH: Double? = null,
        /** With the charging case ("24 hours with the case"). */
        val case: String? = null,
        val connections: List<String> = emptyList(),
        val codecs: String? = null, val drivers: String? = null, val mic: String? = null, val water: String? = null,
        val platforms: String? = null, val spatial: String? = null, val impedance: String? = null,
        val weightG: Double? = null, val chip: String? = null,
        // VR.
        val display: String? = null, val perEye: String? = null, val eyePixels: Int? = null,
        val refresh: String? = null, val refreshHz: Int? = null, val fov: String? = null, val fovDeg: Double? = null, val lenses: String? = null,
        val tracking: String? = null, val controllers: String? = null, val ram: String? = null, val storage: String? = null, val os: String? = null,
        /** How it works with a PC ("Quest Link cable or Air Link over Wi-Fi"). */
        val pcvr: String? = null, val standalone: Boolean? = null, val passthrough: String? = null,
        // Speakers.
        /** "30 W (20 W woofer, 10 W tweeter)". */
        val power: String? = null, val powerW: Double? = null,
        /** Stereo pairing, party linking, phone charging, floating: one sentence. */
        val features: String? = null,
        /** Tuning and range ("V-shaped tuning; 20 Hz to 20 kHz"). */
        val sound: String? = null,
        val notes: List<String> = emptyList(),
        /** The page Brick read live (the maker's, Wikipedia's or SoundGuys'), its name, and the price there today. */
        val liveUrl: String? = null, val liveFrom: String? = null, val now: Double? = null,
        /** SoundGuys' score out of 10, and its page. */
        val score: Double? = null, val scoreUrl: String? = null,
    ) {
        /** Built from a live page alone, with no notes of Brick's own. */
        val liveOnly get() = id.startsWith("live|")
        /** "WH-1000XM5" for "Sony WH-1000XM5"; "Pico 4" stays whole ("the 4" means nothing). */
        val short get() = name.removePrefix("$maker ").let { if (it == name || it.first().isDigit()) name else it }
        val wireless get() = connections.any { Regex("(?i)bluetooth|2\\.4|wireless|wi-?fi").containsMatchIn(it) } || standalone == true
    }

    private val HP = Kind.HEADPHONES
    private val EB = Kind.EARBUDS
    private val GH = Kind.HEADSET
    private val VR = Kind.VR
    private val SP = Kind.SPEAKER
    private const val PORTABLE = "portable Bluetooth speaker"
    private const val WIFI_SPEAKER = "portable Wi-Fi and Bluetooth speaker"

    private fun bt(v: String) = "Bluetooth $v"
    private const val JACK = "Wired (3.5 mm)"
    private const val USBC = "Wired (USB-C)"
    private const val LS = "2.4 GHz wireless (USB-C dongle)"
    private const val NO_ANC = "No active noise cancelling"
    private const val QUEST_PC = "Quest Link over a USB-C cable, or Air Link over Wi-Fi, plays SteamVR and PC VR games"
    private const val BASE = "Base stations (SteamVR Lighthouse) placed around the room"

    val DEVICES: List<Device> = listOf(
        // ------------------------------------------------ wireless headphones
        Device("xm6", HP, "Sony WH-1000XM6", "Sony", 2025, "$449.99", 449.99, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; folds flat", anc = "Active noise cancelling (QN3 chip, 12 mics), with Ambient Sound mode",
            battery = "up to 30 hours with noise cancelling on (40 off)", batteryH = 30.0, connections = listOf(bt("5.3") + ", multipoint", JACK),
            codecs = "SBC, AAC, LDAC (and LC3)", drivers = "30 mm dynamic", mic = "Six beamforming mics for calls", weightG = 254.0,
            spatial = "360 Reality Audio", notes = listOf("Folds again, unlike the XM5.", "Sony's flagship noise cancelling; a mid-2025 update to the XM5.")),
        Device("xm5", HP, "Sony WH-1000XM5", "Sony", 2022, "$399.99", 399.99, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; swivels flat but doesn't fold", anc = "Active noise cancelling (8 mics, two processors), with Ambient Sound mode",
            battery = "up to 30 hours with noise cancelling on (40 off)", batteryH = 30.0, connections = listOf(bt("5.2") + ", multipoint", JACK),
            codecs = "SBC, AAC, LDAC", drivers = "30 mm dynamic", mic = "Four beamforming mics for calls", weightG = 250.0, spatial = "360 Reality Audio",
            notes = listOf("Three minutes of charging gives about three hours of play.", "Lighter than the XM4, but it doesn't fold.")),
        Device("xm4", HP, "Sony WH-1000XM4", "Sony", 2020, "$349.99", 349.99, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; folds", anc = "Active noise cancelling, with Ambient Sound mode",
            battery = "up to 30 hours with noise cancelling on (38 off)", batteryH = 30.0, connections = listOf(bt("5.0") + ", multipoint", JACK),
            codecs = "SBC, AAC, LDAC", drivers = "40 mm dynamic", weightG = 254.0, spatial = "360 Reality Audio",
            notes = listOf("Speak-to-chat pauses music when you talk.", "Often on sale since the XM5 came out.")),
        Device("ch720n", HP, "Sony WH-CH720N", "Sony", 2023, "$149.99", 149.99, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back", anc = "Active noise cancelling, with Ambient Sound mode", battery = "up to 35 hours with noise cancelling on", batteryH = 35.0,
            connections = listOf(bt("5.2") + ", multipoint", JACK), codecs = "SBC, AAC", drivers = "30 mm dynamic", weightG = 192.0,
            notes = listOf("Sony's lightest noise-cancelling headphones at launch.")),
        Device("qcultra", HP, "Bose QuietComfort Ultra Headphones", "Bose", 2023, "$429", 429.0, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; folds", anc = "Active noise cancelling, with Aware (transparency) mode", battery = "up to 24 hours", batteryH = 24.0,
            connections = listOf(bt("5.3") + ", multipoint", JACK + " (2.5 mm on the headphones)"), codecs = "SBC, AAC, aptX Adaptive (Snapdragon Sound)",
            weightG = 254.0, spatial = "Bose Immersive Audio, with head tracking",
            notes = listOf("Among the strongest noise cancelling you can buy.", "A second generation came out in 2025.")),
        Device("qc45", HP, "Bose QuietComfort 45", "Bose", 2021, "$329", 329.0, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; folds", anc = "Active noise cancelling, with Aware mode", battery = "up to 24 hours", batteryH = 24.0,
            connections = listOf(bt("5.1") + ", multipoint", JACK + " (2.5 mm on the headphones)"), codecs = "SBC, AAC", weightG = 240.0,
            notes = listOf("Light and comfortable for long flights.")),
        Device("airpodsmax", HP, "Apple AirPods Max", "Apple", 2020, "$549", 549.0, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; aluminum cups and a mesh canopy band", anc = "Active noise cancelling, with Transparency mode",
            battery = "up to 20 hours with noise cancelling on", batteryH = 20.0, connections = listOf(bt("5.0"), "USB-C (2024 model; Lightning before), with lossless audio over USB-C"),
            codecs = "AAC", drivers = "40 mm Apple dynamic", chip = "Apple H1 (one in each cup)", weightG = 385.0,
            spatial = "Personalized Spatial Audio with head tracking", platforms = "Best with iPhone, iPad and Mac; plain Bluetooth with others",
            notes = listOf("Heavy for headphones, at about 385 g.", "Switched from Lightning to USB-C in 2024.")),
        Device("momentum4", HP, "Sennheiser Momentum 4 Wireless", "Sennheiser", 2022, "$349.95", 349.95, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back", anc = "Adaptive noise cancelling, with Transparency mode", battery = "up to 60 hours", batteryH = 60.0,
            connections = listOf(bt("5.2") + ", multipoint", JACK, USBC + " audio"), codecs = "SBC, AAC, aptX, aptX Adaptive", drivers = "42 mm dynamic",
            weightG = 293.0, notes = listOf("Battery life is among the longest of any noise-cancelling headphones.")),
        Device("studiopro", HP, "Beats Studio Pro", "Beats", 2023, "$349.99", 349.99, form = "pair of wireless noise-cancelling over-ear headphones",
            fit = "Over-ear, closed-back; folds", anc = "Active noise cancelling, with Transparency mode", battery = "up to 24 hours with noise cancelling on (40 off)", batteryH = 24.0,
            connections = listOf(bt("5.3") + " (Class 1)", USBC + ", lossless audio", JACK), codecs = "SBC, AAC", drivers = "40 mm dynamic", weightG = 260.0,
            spatial = "Personalized Spatial Audio with head tracking", platforms = "One-touch pairing on both iPhone and Android",
            notes = listOf("Beats is owned by Apple.")),

        // ------------------------------------------------ earbuds
        Device("airpodspro3", EB, "Apple AirPods Pro 3", "Apple", 2025, "$249", 249.0, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, with five ear tip sizes (XXS to L)", anc = "Active noise cancelling, with Transparency and Adaptive Audio",
            battery = "up to 8 hours with noise cancelling on", batteryH = 8.0, case = "up to 24 hours with the case", connections = listOf("Bluetooth 5.3"),
            codecs = "AAC", chip = "Apple H2", water = "IP57 (buds and case)", spatial = "Personalized Spatial Audio with head tracking",
            platforms = "Best with iPhone; plain Bluetooth with others",
            notes = listOf("Adds heart-rate sensing for workouts.", "Live Translation works with a recent iPhone.")),
        Device("airpodspro2", EB, "Apple AirPods Pro 2", "Apple", 2022, "$249", 249.0, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, with four ear tip sizes", anc = "Active noise cancelling, with Transparency and Adaptive Audio",
            battery = "up to 6 hours with noise cancelling on", batteryH = 6.0, case = "up to 30 hours with the case", connections = listOf("Bluetooth 5.3"),
            codecs = "AAC", chip = "Apple H2", water = "IP54 (buds and case)", weightG = 5.3, spatial = "Personalized Spatial Audio with head tracking",
            platforms = "Best with iPhone; plain Bluetooth with others",
            notes = listOf("Can work as a clinical-grade hearing aid with a recent iPhone.", "The case got USB-C in 2023 (Lightning before).")),
        Device("airpods4", EB, "Apple AirPods 4", "Apple", 2024, "$129", 129.0, form = "pair of wireless earbuds",
            fit = "Open fit, no ear tips", anc = "No noise cancelling on the $129 model; the $179 AirPods 4 with ANC adds it, with Transparency mode",
            battery = "up to 5 hours", batteryH = 5.0, case = "up to 30 hours with the case", connections = listOf("Bluetooth 5.3"), codecs = "AAC",
            chip = "Apple H2", water = "IP54 (buds)", spatial = "Personalized Spatial Audio with head tracking",
            notes = listOf("The case charges over USB-C.", "The ANC model's case also charges wirelessly.")),
        Device("wf1000xm5", EB, "Sony WF-1000XM5", "Sony", 2023, "$299.99", 299.99, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, with foam tips", anc = "Active noise cancelling, with Ambient Sound mode", battery = "up to 8 hours with noise cancelling on", batteryH = 8.0,
            case = "up to 24 hours with the case", connections = listOf(bt("5.3") + ", multipoint"), codecs = "SBC, AAC, LDAC, LC3",
            drivers = "8.4 mm Dynamic Driver X", water = "IPX4", weightG = 5.9, spatial = "360 Reality Audio",
            notes = listOf("Smaller and lighter than the XM4 earbuds.")),
        Device("qcultrabuds", EB, "Bose QuietComfort Ultra Earbuds", "Bose", 2023, "$299", 299.0, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, with tips and stability bands", anc = "Active noise cancelling, with Aware mode", battery = "up to 6 hours", batteryH = 6.0,
            case = "up to 24 hours with the case", connections = listOf(bt("5.3")), codecs = "SBC, AAC, aptX Adaptive (Snapdragon Sound)", water = "IPX4",
            weightG = 6.2, spatial = "Bose Immersive Audio",
            notes = listOf("Among the strongest noise cancelling in earbuds.", "A second generation came out in 2025.")),
        Device("budspro3", EB, "Samsung Galaxy Buds3 Pro", "Samsung", 2024, "$249.99", 249.99, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, stem design", anc = "Adaptive noise cancelling, with Ambient sound", battery = "up to 6 hours with noise cancelling on", batteryH = 6.0,
            case = "up to 26 hours with the case", connections = listOf(bt("5.4")), codecs = "SBC, AAC, Samsung Seamless Codec (24-bit Hi-Fi), LC3",
            drivers = "Two-way: 10.5 mm woofer and 6.1 mm planar tweeter", water = "IP57", weightG = 5.4, spatial = "360 Audio with head tracking",
            platforms = "Best with Galaxy phones (Hi-Fi codec and extras)", notes = listOf("Live translation with Galaxy AI on recent Galaxy phones.")),
        Device("pixelbudspro2", EB, "Google Pixel Buds Pro 2", "Google", 2024, "$229", 229.0, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, with a twist-to-lock stabilizer", anc = "Active noise cancelling (Silent Seal 2.0), with Transparency mode",
            battery = "up to 8 hours with noise cancelling on", batteryH = 8.0, case = "up to 30 hours with the case", connections = listOf(bt("5.4") + ", multipoint"),
            codecs = "SBC, AAC, LC3", chip = "Google Tensor A1", drivers = "11 mm dynamic", water = "IP54 (buds)", weightG = 4.7,
            spatial = "Spatial Audio with head tracking", platforms = "Best with Pixel and Android phones", notes = listOf("Talk to Gemini hands-free.")),
        Device("beatsfitpro", EB, "Beats Fit Pro", "Beats", 2021, "$199.99", 199.99, form = "pair of wireless noise-cancelling earbuds",
            fit = "In-ear, with flexible wingtips", anc = "Active noise cancelling, with Transparency mode", battery = "up to 6 hours with noise cancelling on", batteryH = 6.0,
            case = "up to 24 hours with the case", connections = listOf("Bluetooth (Class 1)"), codecs = "SBC, AAC", chip = "Apple H1", water = "IPX4",
            spatial = "Spatial Audio with head tracking (Apple devices)", platforms = "One-touch pairing on iPhone; Beats app on Android",
            notes = listOf("The wingtips keep them in for running and the gym.")),

        // ------------------------------------------------ wired headphones and in-ears
        Device("hd600", HP, "Sennheiser HD 600", "Sennheiser", 1997, "$399.95", 399.95, launch = false, form = "pair of wired open-back over-ear headphones",
            fit = "Over-ear, open-back (lets sound in and out)", anc = NO_ANC, connections = listOf("Wired: detachable cable, 6.35 mm plug with a 3.5 mm adapter"),
            drivers = "42 mm dynamic", impedance = "300 ohms: an amp helps it reach full volume", weightG = 260.0,
            notes = listOf("A reference for neutral, natural sound for over 25 years.", "Open-back: not for noisy places or quiet offices.")),
        Device("hd650", HP, "Sennheiser HD 650", "Sennheiser", 2003, "$499.95", 499.95, launch = false, form = "pair of wired open-back over-ear headphones",
            fit = "Over-ear, open-back", anc = NO_ANC, connections = listOf("Wired: detachable cable, 6.35 mm plug with a 3.5 mm adapter"),
            drivers = "42 mm dynamic", impedance = "300 ohms: wants a headphone amp", weightG = 260.0,
            notes = listOf("Warmer than the HD 600; also sold as the Drop HD 6XX.")),
        Device("hd660s2", HP, "Sennheiser HD 660S2", "Sennheiser", 2023, "$599.95", 599.95, form = "pair of wired open-back over-ear headphones",
            fit = "Over-ear, open-back", anc = NO_ANC, connections = listOf("Wired: detachable 6.35 mm and 4.4 mm balanced cables"),
            drivers = "42 mm dynamic", impedance = "300 ohms: wants a headphone amp", weightG = 260.0, notes = listOf("More bass than the HD 600 and HD 650.")),
        Device("hd560s", HP, "Sennheiser HD 560S", "Sennheiser", 2020, "$199.95", 199.95, form = "pair of wired open-back over-ear headphones",
            fit = "Over-ear, open-back", anc = NO_ANC, connections = listOf("Wired: detachable cable, 6.35 mm plug with a 3.5 mm adapter"),
            impedance = "120 ohms: fine from most computers, better with an amp", weightG = 240.0,
            notes = listOf("Wide, open soundstage; popular for competitive gaming with a separate mic.")),
        Device("m50x", HP, "Audio-Technica ATH-M50x", "Audio-Technica", 2014, "$169", 169.0, launch = false, form = "pair of wired closed-back studio headphones",
            fit = "Over-ear, closed-back; folds and swivels", anc = NO_ANC, connections = listOf("Wired: three detachable cables (coiled, long and short), 3.5 mm with a 6.35 mm adapter"),
            drivers = "45 mm dynamic", impedance = "38 ohms: easy to drive from a phone or laptop", weightG = 285.0,
            notes = listOf("A studio monitoring classic with punchy bass.", "The M50xBT2 is the Bluetooth version.")),
        Device("dt770", HP, "Beyerdynamic DT 770 Pro", "Beyerdynamic", null, "about $160", 160.0, launch = false, form = "pair of wired closed-back studio headphones",
            fit = "Over-ear, closed-back, with soft velour pads", anc = NO_ANC, connections = listOf("Wired: fixed cable, 3.5 mm with a 6.35 mm adapter"),
            drivers = "45 mm dynamic", impedance = "32, 80 or 250 ohms (pick 32 for phones, 250 with an amp)", weightG = 270.0,
            notes = listOf("Made in Germany; a studio staple for decades.", "Isolates well for recording.")),
        Device("dt990", HP, "Beyerdynamic DT 990 Pro", "Beyerdynamic", null, "about $180", 180.0, launch = false, form = "pair of wired open-back studio headphones",
            fit = "Over-ear, open-back, with soft velour pads", anc = NO_ANC, connections = listOf("Wired: coiled cable, 3.5 mm with a 6.35 mm adapter"),
            drivers = "45 mm dynamic", impedance = "250 ohms (an 80-ohm version exists): wants an amp", weightG = 250.0,
            notes = listOf("Bright, detailed treble and a wide soundstage.")),
        Device("mdr7506", HP, "Sony MDR-7506", "Sony", 1991, "about $100", 100.0, launch = false, form = "pair of wired closed-back studio headphones",
            fit = "Over-ear, closed-back; folds", anc = NO_ANC, connections = listOf("Wired: coiled cable, 3.5 mm with a 6.35 mm adapter"),
            drivers = "40 mm dynamic", impedance = "63 ohms: easy to drive", weightG = 230.0,
            notes = listOf("A broadcast and film-set standard since the early '90s.")),
        Device("shp9500", HP, "Philips SHP9500", "Philips", 2014, "about $80", 80.0, launch = false, form = "pair of wired open-back over-ear headphones",
            fit = "Over-ear, open-back", anc = NO_ANC, connections = listOf("Wired: detachable 3.5 mm cable"), drivers = "50 mm dynamic", impedance = "32 ohms: easy to drive",
            notes = listOf("A budget favorite for open-back sound.")),
        Device("earpods", EB, "Apple EarPods", "Apple", 2012, "$19", 19.0, form = "pair of wired earbuds",
            fit = "Open fit, no ear tips", anc = NO_ANC, connections = listOf("Wired: 3.5 mm, Lightning or USB-C versions"),
            mic = "In-line remote and mic for calls and volume", notes = listOf("The USB-C version works with iPhone 15 and later, iPads, Macs and Android phones.")),
        Device("se215", EB, "Shure SE215", "Shure", 2011, "$99", 99.0, form = "pair of wired sound-isolating in-ear monitors",
            fit = "In-ear, cable over the ear, with foam and silicone tips", anc = "No active noise cancelling; the tips block up to 37 dB of outside sound",
            connections = listOf("Wired: detachable MMCX cable, 3.5 mm"), drivers = "Single dynamic micro-driver", impedance = "17 ohms",
            notes = listOf("Used by musicians on stage as in-ear monitors.", "A Bluetooth adapter cable turns them wireless.")),
        Device("chu2", EB, "Moondrop Chu II", "Moondrop", 2023, "$18.99", 18.99, form = "pair of wired in-ear monitors",
            fit = "In-ear, cable over the ear; detachable 2-pin cable", anc = "No active noise cancelling; the tips block outside sound passively",
            connections = listOf(JACK), drivers = "10 mm dynamic", impedance = "18 ohms, 119 dB sensitivity: a phone or a cheap USB-C dongle drives them easily",
            notes = listOf("A budget IEM favorite for its balanced, Harman-style tuning.")),
        Device("zeroblue2", EB, "Truthear Zero:Blue2", "Truthear", 2024, "$64.99", 64.99, form = "pair of wired in-ear monitors",
            fit = "In-ear, cable over the ear; detachable 2-pin cable", anc = "No active noise cancelling; the tips block outside sound passively",
            connections = listOf(JACK), drivers = "Two dynamic drivers (10 mm and 7.8 mm) per side", impedance = "5.8 ohms, 119.5 dB sensitivity: easy to drive",
            notes = listOf("Tuned with Crinacle for extra sub-bass.")),
        Device("salnotes", EB, "7Hz Salnotes Zero", "7Hz", 2022, "$19.99", 19.99, form = "pair of wired in-ear monitors",
            fit = "In-ear, cable over the ear; detachable 2-pin cable", anc = "No active noise cancelling; the tips block outside sound passively",
            connections = listOf(JACK), drivers = "10 mm dynamic", impedance = "32 ohms: easy to drive from a phone or dongle",
            notes = listOf("Sold through Linsoul; a common first IEM.")),
        Device("zs10pro", EB, "KZ ZS10 Pro", "KZ", 2019, "about $40", 40.0, launch = false, form = "pair of wired in-ear monitors",
            fit = "In-ear, cable over the ear; detachable 2-pin cable", anc = "No active noise cancelling; the tips block outside sound passively",
            connections = listOf(JACK), drivers = "Hybrid: one 10 mm dynamic driver and four balanced armatures per side",
            notes = listOf("Five drivers a side for the price of a dinner out; a bright, V-shaped sound.")),

        // ------------------------------------------------ speakers
        Device("flip6", SP, "JBL Flip 6", "JBL", 2021, "$129.95", 129.95, form = PORTABLE,
            battery = "up to 12 hours", batteryH = 12.0, connections = listOf(bt("5.1")), codecs = "SBC", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "Two-way: a racetrack woofer and a tweeter", power = "30 W (20 W woofer, 10 W tweeter)", powerW = 30.0, weightG = 550.0,
            features = "PartyBoost links it with other PartyBoost speakers, or two for stereo"),
        Device("flip7", SP, "JBL Flip 7", "JBL", 2025, "$149.95", 149.95, form = PORTABLE,
            battery = "up to 14 hours (16 with Playtime Boost)", batteryH = 14.0, connections = listOf(bt("5.4"), "Wired (USB-C audio)"), codecs = "SBC, AAC",
            water = "IP68 (dust-tight, survives being under water)", drivers = "Two-way: a woofer and a tweeter", power = "35 W (25 W woofer, 10 W tweeter)", powerW = 35.0,
            weightG = 560.0, features = "Auracast links it with other Auracast JBL speakers, or two for stereo",
            notes = listOf("Built to survive a 1.5 m drop onto concrete.")),
        Device("charge5", SP, "JBL Charge 5", "JBL", 2021, "$179.95", 179.95, form = PORTABLE,
            battery = "up to 20 hours", batteryH = 20.0, connections = listOf(bt("5.1")), codecs = "SBC", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "Two-way: a racetrack woofer and a tweeter", power = "40 W (30 W woofer, 10 W tweeter)", powerW = 40.0, weightG = 960.0,
            features = "Charges your phone from its USB-A port; PartyBoost links it with other PartyBoost speakers, or two for stereo"),
        Device("charge6", SP, "JBL Charge 6", "JBL", 2025, "$199.95", 199.95, form = PORTABLE,
            battery = "up to 24 hours (28 with Playtime Boost)", batteryH = 24.0, connections = listOf(bt("5.4"), "Wired (USB-C audio)"), codecs = "SBC, AAC",
            water = "IP68 (dust-tight, survives being under water)", drivers = "Two-way: a woofer and a tweeter", power = "45 W", powerW = 45.0,
            features = "Charges your phone over USB-C; Auracast links it with other Auracast JBL speakers, or two for stereo",
            notes = listOf("Has a detachable strap and survives drops onto concrete.")),
        Device("clip5", SP, "JBL Clip 5", "JBL", 2024, "$79.95", 79.95, form = PORTABLE,
            battery = "up to 12 hours (15 with Playtime Boost)", batteryH = 12.0, connections = listOf(bt("5.3")), codecs = "SBC", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "One full-range driver", power = "7 W", powerW = 7.0, weightG = 285.0, features = "Auracast links it with other Auracast JBL speakers, or two for stereo",
            notes = listOf("A built-in carabiner clips it to a bag.")),
        Device("go4", SP, "JBL Go 4", "JBL", 2024, "$49.95", 49.95, form = PORTABLE,
            battery = "up to 7 hours (9 with Playtime Boost)", batteryH = 7.0, connections = listOf(bt("5.3")), codecs = "SBC", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "One full-range driver", power = "4.2 W", powerW = 4.2, weightG = 190.0, features = "Auracast links it with other Auracast JBL speakers, or two for stereo",
            notes = listOf("Pocket-sized, with a loop strap.")),
        Device("wonderboom4", SP, "Ultimate Ears Wonderboom 4", "Ultimate Ears", 2024, "$99.99", 99.99, form = PORTABLE,
            battery = "up to 14 hours", batteryH = 14.0, connections = listOf("Bluetooth"), water = "IP67 (dust-tight, survives a dunk)",
            drivers = "Two drivers for 360-degree sound", weightG = 420.0, features = "Floats; pairs with another Wonderboom for twice the sound; Outdoor Boost mode",
            notes = listOf("Drop-proof from 2 m, and it floats.")),
        Device("boom4", SP, "Ultimate Ears Boom 4", "Ultimate Ears", 2024, "$149.99", 149.99, form = PORTABLE,
            battery = "up to 15 hours", batteryH = 15.0, connections = listOf("Bluetooth"), water = "IP67 (dust-tight, survives a dunk)",
            drivers = "360-degree sound", features = "Floats; PartyUp links it with other Boom, Megaboom and Hyperboom speakers",
            notes = listOf("The first Boom to charge over USB-C.")),
        Device("megaboom4", SP, "Ultimate Ears Megaboom 4", "Ultimate Ears", 2024, "$199.99", 199.99, form = PORTABLE,
            battery = "up to 20 hours", batteryH = 20.0, connections = listOf("Bluetooth"), water = "IP67 (dust-tight, survives a dunk)",
            drivers = "360-degree sound", features = "Floats; PartyUp links it with other Boom, Megaboom and Hyperboom speakers"),
        Device("flex2", SP, "Bose SoundLink Flex (2nd Gen)", "Bose", 2024, "$149", 149.0, form = PORTABLE,
            battery = "up to 12 hours", batteryH = 12.0, connections = listOf(bt("5.3")), codecs = "SBC, AAC, aptX Adaptive", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "One transducer and two passive radiators", weightG = 600.0, features = "Floats; pairs with another Bose speaker for stereo or party mode",
            notes = listOf("Has a programmable shortcut button.")),
        Device("soundlinkmax", SP, "Bose SoundLink Max", "Bose", 2024, "$399", 399.0, form = PORTABLE,
            battery = "up to 20 hours", batteryH = 20.0, connections = listOf("Bluetooth", "Wired (3.5 mm aux in)"), codecs = "SBC, AAC, aptX Adaptive",
            water = "IP67 (dust-tight, survives a dunk)", drivers = "Stereo: two tweeters, a woofer and two passive radiators", weightG = 2100.0,
            features = "Charges your phone over USB-C; pairs with another Bose speaker for stereo or party mode", notes = listOf("Comes with a removable rope handle.")),
        Device("roam2", SP, "Sonos Roam 2", "Sonos", 2024, "$179", 179.0, form = WIFI_SPEAKER,
            battery = "up to 10 hours", batteryH = 10.0, connections = listOf("Wi-Fi", "Bluetooth"), codecs = "SBC, AAC", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "A tweeter and a mid-woofer", weightG = 430.0, features = "Joins a Sonos system over Wi-Fi (multiroom, AirPlay 2); pairs with another Roam for stereo",
            notes = listOf("Trueplay tunes its sound to the room automatically.")),
        Device("move2", SP, "Sonos Move 2", "Sonos", 2023, "$449", 449.0, form = WIFI_SPEAKER,
            battery = "up to 24 hours", batteryH = 24.0, connections = listOf("Wi-Fi", "Bluetooth", "Wired (USB-C line-in)"), codecs = "SBC, AAC", water = "IP56 (dust and rain)",
            drivers = "Stereo: two tweeters and a woofer", weightG = 3000.0, features = "Charges your phone over USB-C; joins a Sonos system over Wi-Fi (multiroom, AirPlay 2)",
            notes = listOf("Its battery can be replaced.")),
        Device("pill", SP, "Beats Pill", "Beats", 2024, "$149.99", 149.99, form = PORTABLE,
            battery = "up to 24 hours", batteryH = 24.0, connections = listOf("Bluetooth (Class 1)", "Wired (USB-C audio)"), water = "IP67 (dust-tight, survives a dunk)",
            drivers = "A racetrack woofer and a tweeter", weightG = 680.0, mic = "Speakerphone for calls",
            features = "Charges your phone over USB-C; pairs with another Pill for stereo or Amplify mode", notes = listOf("Works with Apple's Find My and Google's Find My Device.")),
        Device("emberton2", SP, "Marshall Emberton II", "Marshall", 2022, "$169.99", 169.99, form = PORTABLE,
            battery = "up to 30 hours", batteryH = 30.0, connections = listOf(bt("5.1")), codecs = "SBC", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "Two full-range drivers and two passive radiators, for 360-degree sound", power = "20 W", powerW = 20.0, weightG = 700.0,
            features = "Stack Mode links it with other Marshall speakers"),
        Device("emberton3", SP, "Marshall Emberton III", "Marshall", 2024, "$169.99", 169.99, form = PORTABLE,
            battery = "up to 32 hours", batteryH = 32.0, connections = listOf(bt("5.3") + " LE Audio"), codecs = "SBC, LC3", water = "IP67 (dust-tight, survives a dunk)",
            drivers = "Two full-range drivers and two passive radiators, for 360-degree sound", weightG = 680.0, mic = "Built-in mic for calls",
            features = "Auracast links it with other Auracast speakers"),

        // ------------------------------------------------ gaming headsets
        Device("novaprowl", GH, "SteelSeries Arctis Nova Pro Wireless", "SteelSeries", 2022, "$349.99", 349.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back", anc = "Active noise cancelling, with Transparency mode", battery = "about 18-22 hours per battery, with two swappable batteries (one charges in the base)", batteryH = 22.0,
            connections = listOf("2.4 GHz wireless and Bluetooth at the same time", "Base station (GameDAC) over USB-C"), drivers = "40 mm neodymium",
            mic = "Retractable ClearCast Gen 2 noise-cancelling mic", weightG = 337.0, spatial = "360° Spatial Audio (Sonar on PC)",
            platforms = "PC, PlayStation, Switch and phones (a separate Xbox model also works on PC and PlayStation)",
            notes = listOf("Hot-swap batteries mean it never has to stop to charge.", "The base station has a volume dial and a screen.")),
        Device("nova7", GH, "SteelSeries Arctis Nova 7 Wireless", "SteelSeries", 2022, "$179.99", 179.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, battery = "up to 38 hours", batteryH = 38.0,
            connections = listOf("2.4 GHz wireless (USB-C dongle) and Bluetooth at the same time", JACK), drivers = "40 mm neodymium",
            mic = "Retractable ClearCast Gen 2 mic", weightG = 325.0, spatial = "360° Spatial Audio (Sonar on PC)",
            platforms = "PC, PlayStation, Switch and phones (Xbox model sold separately)",
            notes = listOf("Fast charging: 15 minutes gives about 6 hours.")),
        Device("nova1", GH, "SteelSeries Arctis Nova 1", "SteelSeries", 2022, "$59.99", 59.99, form = "wired gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, connections = listOf(JACK), drivers = "40 mm neodymium", mic = "Retractable ClearCast Gen 2 mic",
            weightG = 236.0, platforms = "PC, PlayStation, Xbox, Switch and phones (anything with a 3.5 mm jack)",
            notes = listOf("Light for a gaming headset.")),
        Device("bsv2pro", GH, "Razer BlackShark V2 Pro (2023)", "Razer", 2023, "$199.99", 199.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back, with memory foam cushions", anc = NO_ANC, battery = "up to 70 hours", batteryH = 70.0,
            connections = listOf("2.4 GHz HyperSpeed (USB-C dongle)", bt("5.2")), drivers = "50 mm Razer TriForce Titanium",
            mic = "Detachable HyperClear Super Wideband mic", weightG = 320.0, spatial = "THX Spatial Audio",
            platforms = "PC, PlayStation, Switch and phones (Xbox model sold separately)", notes = listOf("Esports favorite for clear footsteps and voice chat.")),
        Device("bsv2x", GH, "Razer BlackShark V2 X", "Razer", 2020, "$59.99", 59.99, form = "wired gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, connections = listOf(JACK), drivers = "50 mm Razer TriForce", mic = "Fixed HyperClear cardioid mic",
            weightG = 240.0, spatial = "7.1 surround on PC through the Razer app", platforms = "PC, PlayStation, Xbox, Switch and phones (3.5 mm)",
            notes = listOf("A light budget headset with solid sound.")),
        Device("gprox2", GH, "Logitech G Pro X 2 Lightspeed", "Logitech", 2023, "$249.99", 249.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, battery = "up to 50 hours", batteryH = 50.0,
            connections = listOf("2.4 GHz LIGHTSPEED (USB dongle)", "Bluetooth", JACK), drivers = "50 mm graphene",
            mic = "Detachable 6 mm cardioid mic, with Blue VO!CE filters in G HUB", weightG = 345.0, spatial = "DTS Headphone:X 2.0",
            platforms = "PC, PlayStation, Switch and phones (Xbox model sold separately)", notes = listOf("Graphene drivers for low distortion.")),
        Device("g733", GH, "Logitech G733 Lightspeed", "Logitech", 2020, "$129.99", 129.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back, with a stretchy suspension band", anc = NO_ANC, battery = "up to 29 hours (lights off)", batteryH = 29.0,
            connections = listOf("2.4 GHz LIGHTSPEED (USB dongle)"), drivers = "40 mm PRO-G", mic = "Detachable mic with Blue VO!CE",
            weightG = 278.0, spatial = "DTS Headphone:X 2.0", platforms = "PC and PlayStation", notes = listOf("Comes in bright colors with RGB lighting.")),
        Device("cloud3", GH, "HyperX Cloud III", "HyperX", 2023, "$99.99", 99.99, form = "wired gaming headset",
            fit = "Over-ear, closed-back, with memory foam", anc = NO_ANC, connections = listOf(JACK, "USB-C or USB-A adapter (built-in sound card)"),
            drivers = "53 mm angled", mic = "Detachable 10 mm noise-cancelling mic", spatial = "DTS Headphone:X on PC",
            platforms = "PC, PlayStation, Xbox, Switch and phones", notes = listOf("Known for comfort on long sessions.")),
        Device("cloud3wl", GH, "HyperX Cloud III Wireless", "HyperX", 2023, "$169.99", 169.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back, with memory foam", anc = NO_ANC, battery = "up to 120 hours", batteryH = 120.0,
            connections = listOf("2.4 GHz wireless (USB-C dongle with a USB-A adapter)"), drivers = "53 mm angled", mic = "Detachable 10 mm noise-cancelling mic",
            spatial = "DTS Headphone:X on PC", platforms = "PC, PlayStation and Switch"),
        Device("alphawl", GH, "HyperX Cloud Alpha Wireless", "HyperX", 2022, "$199.99", 199.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, battery = "up to 300 hours", batteryH = 300.0, connections = listOf("2.4 GHz wireless (USB-A dongle)"),
            drivers = "50 mm dual-chamber", mic = "Detachable noise-cancelling mic", weightG = 335.0, spatial = "DTS Headphone:X on PC", platforms = "PC and PlayStation",
            notes = listOf("Its 300-hour battery was a record for a gaming headset.")),
        Device("hs80", GH, "Corsair HS80 RGB Wireless", "Corsair", 2021, "$149.99", 149.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back, with a floating headband", anc = NO_ANC, battery = "up to 20 hours", batteryH = 20.0,
            connections = listOf("2.4 GHz SLIPSTREAM (USB dongle)", USBC + " audio"), drivers = "50 mm neodymium", mic = "Flip-to-mute omnidirectional broadcast-grade mic",
            weightG = 367.0, spatial = "Dolby Atmos on PC", platforms = "PC and PlayStation", notes = listOf("Plays 24-bit/96 kHz audio over the USB cable.")),
        Device("a50x", GH, "Logitech Astro A50 X", "Logitech", 2023, "$379.99", 379.99, form = "wireless gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, battery = "up to 24 hours", batteryH = 24.0,
            connections = listOf("2.4 GHz LIGHTSPEED to its base station", "Bluetooth", "Base station with HDMI passthrough (PlaySync)"), drivers = "40 mm PRO-G graphene",
            mic = "Flip-to-mute mic", platforms = "PS5, Xbox and PC at once: one button switches between them",
            notes = listOf("The base station sits between your consoles and TV, passing 4K 120 Hz video through.")),
        Device("maxwell", GH, "Audeze Maxwell", "Audeze", 2022, "$299 (PlayStation) / $329 (Xbox)", 299.0, form = "wireless gaming headset",
            fit = "Over-ear, closed-back", anc = NO_ANC, battery = "up to 80 hours", batteryH = 80.0,
            connections = listOf("2.4 GHz wireless (USB-C dongle)", bt("5.3"), USBC + " audio", JACK), codecs = "SBC, AAC, LDAC, LC3",
            drivers = "90 mm planar magnetic", mic = "Detachable boom mic plus built-in mics", weightG = 490.0,
            platforms = "PC, PlayStation, Switch and phones (the Xbox model also works on Xbox)",
            notes = listOf("Planar magnetic drivers, rare in gaming headsets, for detailed sound.", "Heavy, at about 490 g.")),

        // ------------------------------------------------ VR
        Device("quest3", VR, "Meta Quest 3", "Meta", 2023, "$499.99 (128 GB)", 499.99, form = "standalone mixed-reality VR headset",
            display = "Two LCD panels", perEye = "2064 x 2208", eyePixels = 2064 * 2208, refresh = "72, 80, 90 and 120 Hz", refreshHz = 120,
            fov = "110° wide, 96° tall", fovDeg = 110.0, lenses = "Pancake", chip = "Qualcomm Snapdragon XR2 Gen 2", ram = "8 GB",
            storage = "512 GB (the launch 128 GB model was dropped)", os = "Meta Horizon OS (Android-based)",
            tracking = "Inside-out: cameras on the headset, no base stations; hand tracking", controllers = "Touch Plus controllers (no tracking rings)",
            standalone = true, pcvr = QUEST_PC, passthrough = "Full-color, high-resolution passthrough for mixed reality",
            battery = "about 2.2 hours", batteryH = 2.2, weightG = 515.0, connections = listOf("Wi-Fi 6E", "USB-C"),
            notes = listOf("Meta's best standalone headset for games and mixed reality.")),
        Device("quest3s", VR, "Meta Quest 3S", "Meta", 2024, "$299.99 (128 GB)", 299.99, form = "standalone mixed-reality VR headset",
            display = "One LCD panel", perEye = "1832 x 1920", eyePixels = 1832 * 1920, refresh = "72, 80, 90 and 120 Hz", refreshHz = 120,
            fov = "96° wide, 90° tall", fovDeg = 96.0, lenses = "Fresnel", chip = "Qualcomm Snapdragon XR2 Gen 2", ram = "8 GB", storage = "128 or 256 GB",
            os = "Meta Horizon OS (Android-based)", tracking = "Inside-out: cameras on the headset; hand tracking", controllers = "Touch Plus controllers",
            standalone = true, pcvr = QUEST_PC, passthrough = "Full-color passthrough (lower resolution than the Quest 3)",
            battery = "about 2.5 hours", batteryH = 2.5, weightG = 514.0, connections = listOf("Wi-Fi 6E", "USB-C"),
            notes = listOf("The Quest 3's chip with the Quest 2's screen and lenses, for less.")),
        Device("quest2", VR, "Meta Quest 2", "Meta", 2020, "$299 (64 GB)", 299.0, form = "standalone VR headset",
            display = "One fast-switch LCD panel", perEye = "1832 x 1920", eyePixels = 1832 * 1920, refresh = "60, 72, 90 and 120 Hz", refreshHz = 120,
            fov = "about 97° wide", fovDeg = 97.0, lenses = "Fresnel", chip = "Qualcomm Snapdragon XR2", ram = "6 GB", storage = "64, 128 or 256 GB",
            os = "Meta Horizon OS (Android-based)", tracking = "Inside-out: four cameras on the headset; hand tracking", controllers = "Touch controllers",
            standalone = true, pcvr = QUEST_PC, passthrough = "Black-and-white passthrough", battery = "about 2-3 hours", batteryH = 2.5, weightG = 503.0,
            connections = listOf("Wi-Fi 6", "USB-C"), notes = listOf("Launched as the Oculus Quest 2.", "Discontinued in 2024.")),
        Device("questpro", VR, "Meta Quest Pro", "Meta", 2022, "$1,499.99", 1499.99, form = "standalone mixed-reality VR headset",
            display = "Two LCD panels with mini-LED local dimming", perEye = "1800 x 1920", eyePixels = 1800 * 1920, refresh = "72 and 90 Hz", refreshHz = 90,
            fov = "106° wide, 96° tall", fovDeg = 106.0, lenses = "Pancake", chip = "Qualcomm Snapdragon XR2+", ram = "12 GB", storage = "256 GB",
            tracking = "Inside-out, plus eye and face tracking", controllers = "Touch Pro controllers that track themselves with their own cameras",
            standalone = true, pcvr = QUEST_PC, passthrough = "Color passthrough", battery = "about 1-2 hours", batteryH = 1.5, weightG = 722.0,
            notes = listOf("Price cut to $999.99 in 2023; discontinued in 2025.")),
        Device("visionpro", VR, "Apple Vision Pro", "Apple", 2024, "$3,499 (256 GB)", 3499.0, form = "standalone mixed-reality headset",
            display = "Two micro-OLED displays, 23 million pixels in all", perEye = "about 3660 x 3200 (Apple quotes 23 million pixels for both)", eyePixels = 3660 * 3200,
            refresh = "90, 96 and 100 Hz", refreshHz = 100, chip = "Apple M2, plus an R1 chip for the cameras and sensors", ram = "16 GB", storage = "256 GB, 512 GB or 1 TB",
            os = "visionOS", tracking = "Inside-out, with eye tracking and hand tracking", controllers = "None: you look and pinch",
            standalone = true, pcvr = "No PC VR; it can show a Mac's screen as a big virtual display", passthrough = "Full-color, very sharp passthrough (Apple calls it spatial computing)",
            battery = "about 2 hours (2.5 for video), from an external battery pack", batteryH = 2.0, weightG = 650.0,
            notes = listOf("An M5 version came out in October 2025 at the same price.", "EyeSight shows your eyes on the front display.")),
        Device("index", VR, "Valve Index", "Valve", 2019, "$999 (full kit; $499 for the headset alone)", 999.0, form = "PC VR headset",
            display = "Two LCD panels", perEye = "1440 x 1600", eyePixels = 1440 * 1600, refresh = "80, 90, 120 and 144 Hz", refreshHz = 144,
            fov = "about 130° (Valve: about 20° more than the HTC Vive)", fovDeg = 130.0, lenses = "Dual-element canted", tracking = BASE,
            controllers = "Index controllers with finger tracking", standalone = false, pcvr = "Needs a gaming PC: DisplayPort and USB 3.0 to the PC, with SteamVR",
            passthrough = "Basic camera passthrough", weightG = 809.0, connections = listOf("Wired: DisplayPort, USB 3.0 and power"),
            notes = listOf("Off-ear speakers and the highest refresh rate of its time.")),
        Device("vivepro2", VR, "HTC Vive Pro 2", "HTC", 2021, "$799 (headset only)", 799.0, form = "PC VR headset",
            display = "Two LCD panels", perEye = "2448 x 2448", eyePixels = 2448 * 2448, refresh = "90 and 120 Hz", refreshHz = 120, fov = "up to 120°", fovDeg = 120.0,
            tracking = BASE, controllers = "Vive controllers or Index controllers", standalone = false, pcvr = "Needs a gaming PC (DisplayPort) and base stations, with SteamVR",
            connections = listOf("Wired: DisplayPort and USB 3.0")),
        Device("xrelite", VR, "HTC Vive XR Elite", "HTC", 2023, "$1,099", 1099.0, form = "standalone mixed-reality VR headset",
            display = "Two LCD panels", perEye = "1920 x 1920", eyePixels = 1920 * 1920, refresh = "90 Hz", refreshHz = 90, fov = "up to 110°", fovDeg = 110.0,
            chip = "Qualcomm Snapdragon XR2", ram = "12 GB", storage = "128 GB", tracking = "Inside-out; hand tracking", controllers = "Vive XR Elite controllers",
            standalone = true, pcvr = "Vive Streaming over Wi-Fi or a USB-C cable, with SteamVR", passthrough = "Color passthrough",
            battery = "about 2 hours (a swappable rear battery)", batteryH = 2.0, weightG = 625.0, notes = listOf("Turns into glasses-style 273 g goggles without its battery.")),
        Device("focusvision", VR, "HTC Vive Focus Vision", "HTC", 2024, "$999", 999.0, form = "standalone mixed-reality VR headset",
            display = "Two LCD panels", perEye = "2448 x 2448", eyePixels = 2448 * 2448, refresh = "90 Hz (120 Hz in DisplayPort mode)", refreshHz = 120, fov = "up to 120°", fovDeg = 120.0,
            chip = "Qualcomm Snapdragon XR2", ram = "12 GB", storage = "128 GB", tracking = "Inside-out, with eye tracking and hand tracking",
            controllers = "Vive Focus controllers", standalone = true, pcvr = "DisplayPort mode with a cable for lossless PC VR, or streaming over Wi-Fi",
            passthrough = "Color passthrough", weightG = 785.0, notes = listOf("A swappable rear battery, plus a small built-in one to swap without powering down.")),
        Device("beyond", VR, "Bigscreen Beyond", "Bigscreen", 2023, "$999", 999.0, form = "ultralight PC VR headset",
            display = "Two micro-OLED displays", perEye = "2560 x 2560", eyePixels = 2560 * 2560, refresh = "75 and 90 Hz", refreshHz = 90, lenses = "Pancake",
            tracking = BASE, controllers = "None included: use Index or other SteamVR controllers", standalone = false, pcvr = "Needs a gaming PC, base stations and SteamVR",
            weightG = 127.0, notes = listOf("Each one is made to fit its owner's face from an iPhone face scan.", "No built-in audio.")),
        Device("beyond2", VR, "Bigscreen Beyond 2", "Bigscreen", 2025, "$1,019", 1019.0, form = "ultralight PC VR headset",
            display = "Two micro-OLED displays", perEye = "2560 x 2560", eyePixels = 2560 * 2560, refresh = "75 and 90 Hz", refreshHz = 90,
            fov = "116° diagonal", fovDeg = 102.0, lenses = "Pancake", tracking = BASE, controllers = "None included: use Index or other SteamVR controllers",
            standalone = false, pcvr = "Needs a gaming PC, base stations and SteamVR", weightG = 107.0,
            notes = listOf("The Beyond 2e adds eye tracking.", "Custom-fit to each face from a phone scan.")),
        Device("psvr2", VR, "Sony PlayStation VR2", "Sony", 2023, "$549.99", 549.99, form = "VR headset for the PS5",
            display = "Two OLED panels with HDR", perEye = "2000 x 2040", eyePixels = 2000 * 2040, refresh = "90 and 120 Hz", refreshHz = 120, fov = "about 110°", fovDeg = 110.0,
            lenses = "Fresnel", tracking = "Inside-out (four cameras), with eye tracking", controllers = "PS VR2 Sense controllers with haptics and adaptive triggers",
            standalone = false, pcvr = "Works on PC through Sony's \$59.99 PC adapter and SteamVR (no eye tracking or HDR there)",
            passthrough = "Black-and-white see-through view", weightG = 560.0, connections = listOf("Wired: one USB-C cable to the PS5"),
            notes = listOf("Headset rumble and eye-tracked foveated rendering.", "Price cut to \$399.99 in 2025.")),
        Device("galaxyxr", VR, "Samsung Galaxy XR", "Samsung", 2025, "$1,799.99", 1799.99, form = "standalone mixed-reality headset",
            display = "Two micro-OLED displays", perEye = "3552 x 3840", eyePixels = 3552 * 3840, refresh = "60, 72 and 90 Hz", refreshHz = 90,
            fov = "109° wide, 100° tall", fovDeg = 109.0, lenses = "Pancake", chip = "Qualcomm Snapdragon XR2+ Gen 2", ram = "16 GB", storage = "256 GB",
            os = "Android XR, with Gemini", tracking = "Inside-out, with eye tracking and hand tracking", controllers = "Hand and eye controls; optional controllers",
            standalone = true, pcvr = "Streams PC VR through apps like Virtual Desktop", passthrough = "Full-color passthrough",
            battery = "about 2 hours (2.5 for video), from an external battery pack", batteryH = 2.0, weightG = 545.0,
            notes = listOf("Runs Android apps and Google Play.", "Lighter than the Vision Pro and about half the price.")),
    )

    fun byId(id: String): Device? = DEVICES.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- names

    private data class Name(val id: String, val rx: Regex)

    private fun n(id: String, rx: String) = Name(id, Regex("(?<![a-z0-9])(?:$rx)(?![a-z0-9])"))

    private const val NOT_WF = "(?<!wf-|wf |wf)"
    private const val QC = "(?:bose )?(?:quiet ?comfort|qc)"

    private val NAMES = listOf(
        n("xm6", "$NOT_WF(?:sony )?(?:wh ?-?)?1000 ?x ?m6|$NOT_WF(?:sony )?xm6(?! (?:ear ?buds|buds))"),
        n("xm5", "$NOT_WF(?:sony )?(?:wh ?-?)?1000 ?x ?m5|$NOT_WF(?:sony )?xm5(?! (?:ear ?buds|buds))"),
        n("xm4", "$NOT_WF(?:sony )?(?:wh ?-?)?1000 ?x ?m4|$NOT_WF(?:sony )?xm4(?! (?:ear ?buds|buds))"),
        n("ch720n", "(?:sony )?(?:wh ?-?)?ch ?-?720 ?n?"),
        n("qcultra", "$QC ultra(?: headphones)?(?! (?:ear ?buds|buds)| ?2)"),
        n("qc45", "$QC ?45"),
        n("airpodsmax", "(?:apple )?air ?pods max(?: 2)?"),
        n("momentum4", "(?:sennheiser )?momentum 4(?: wireless)?"),
        n("studiopro", "beats studio pro"),
        n("airpodspro3", "(?:apple )?air ?pods pro 3|(?:apple )?air ?pods pro(?! ?[12]| (?:2nd|second|1st|first))"),
        n("airpodspro2", "(?:apple )?air ?pods pro 2|(?:apple )?air ?pods pro (?:2nd|second) gen(?:eration)?"),
        n("airpods4", "(?:apple )?air ?pods 4(?: with anc| with active noise cancell?ation)?|(?:apple )?air ?pods(?! ?(?:pro|max|[1-3]|(?:1st|2nd|3rd|first|second|third) gen))"),
        n("wf1000xm5", "(?:sony )?wf ?-?1000 ?x ?m5|(?:sony )?xm5 (?:ear ?buds|buds)"),
        n("qcultrabuds", "$QC ultra (?:ear ?buds|buds)"),
        n("budspro3", "(?:samsung )?(?:galaxy )?buds ?3 pro"),
        n("pixelbudspro2", "(?:google )?pixel buds pro 2"),
        n("beatsfitpro", "(?:beats )?fit pro"),
        n("hd600", "(?:sennheiser )?hd ?600"),
        n("hd650", "(?:sennheiser )?hd ?650|(?:drop )?hd ?6xx"),
        n("hd660s2", "(?:sennheiser )?hd ?660 ?s ?2"),
        n("hd560s", "(?:sennheiser )?hd ?560 ?s?"),
        n("m50x", "(?:audio[- ]?technica )?(?:ath ?-?)?m50 ?x(?! ?bt)"),
        n("dt770", "(?:beyer ?dynamic )?dt ?770(?: pro)?"),
        n("dt990", "(?:beyer ?dynamic )?dt ?990(?: pro)?"),
        n("mdr7506", "(?:sony )?mdr ?-?7506|sony 7506"),
        n("shp9500", "(?:philips )?shp ?9500"),
        n("earpods", "(?:apple )?ear ?pods"),
        n("se215", "(?:shure )?se ?215"),
        n("chu2", "(?:moondrop )?chu (?:ii|2)"),
        n("zeroblue2", "(?:truthear )?(?:x crinacle )?zero ?blue ?2"),
        n("salnotes", "(?:7 ?hz )?salnotes zero(?! ?2)|7 ?hz zero(?! ?2)"),
        n("zs10pro", "(?:kz )?zs ?10 pro(?! ?x| 2)"),
        n("flip6", "(?:jbl )?flip ?6(?! times| coins)"),
        n("flip7", "(?:jbl )?flip ?7(?! times| coins)"),
        n("charge5", "jbl charge ?5|(?<=the )charge 5"),
        n("charge6", "jbl charge ?6|(?<=the )charge 6"),
        n("clip5", "(?:jbl )?clip ?5"),
        n("go4", "jbl go ?4"),
        n("wonderboom4", "(?:ultimate ears |ue )?wonder ?boom(?: 4)?(?! ?[1-35])"),
        n("boom4", "(?:ultimate ears |ue )boom(?: 4)?(?! ?[1-35])"),
        n("megaboom4", "(?:ultimate ears |ue )?mega ?boom(?: 4)?(?! ?[1-35])"),
        n("flex2", "(?:bose )?sound ?link flex(?: 2nd gen(?:eration)?| 2| ii)?(?! (?:1st|first))"),
        n("soundlinkmax", "(?:bose )?sound ?link max"),
        n("roam2", "sonos roam(?: 2)?(?! ?[13]| sl)"),
        n("move2", "sonos move(?: 2)?(?! ?[13])"),
        n("pill", "beats pill(?! ?\\+| plus| 2 ?0)"),
        n("emberton2", "(?:marshall )?emberton (?:ii|2)"),
        n("emberton3", "(?:marshall )?emberton (?:iii|3)|(?:marshall )?emberton(?! ?(?:ii|2|iii|3|i)\\b)"),
        n("novaprowl", "(?:steelseries )?(?:arctis )?nova pro wireless|(?:steelseries )?arctis nova pro(?! omni)"),
        n("nova7", "(?:steelseries )?(?:arctis )?nova 7(?: wireless)?(?! ?[px])"),
        n("nova1", "(?:steelseries )?(?:arctis )?nova 1(?! ?[px])"),
        n("bsv2pro", "(?:razer )?black ?shark v2 pro"),
        n("bsv2x", "(?:razer )?black ?shark v2 x"),
        n("gprox2", "(?:logitech )?(?:g )?pro x 2(?! superlight| ?dex| tkl)(?: lightspeed)?(?: (?:wireless )?(?:gaming )?headset)?"),
        n("g733", "(?:logitech )?g ?733(?: lightspeed)?"),
        n("cloud3wl", "(?:hyperx )?cloud (?:iii|3) wireless"),
        n("cloud3", "(?:hyperx )?cloud (?:iii|3)(?! wireless|s)"),
        n("alphawl", "(?:hyperx )?cloud alpha wireless"),
        n("hs80", "(?:corsair )?hs ?80(?: rgb)?(?: wireless)?(?! ?(?:max|v2|pro))"),
        n("a50x", "(?:logitech )?(?:astro )?a50 ?x"),
        n("maxwell", "(?:audeze )?maxwell"),
        n("quest3", "(?:meta |oculus )?quest 3(?! ?s)|meta quest(?! ?(?:[1-3]|pro|\\d))"),
        n("quest3s", "(?:meta )?quest 3 ?s"),
        n("quest2", "(?:meta |oculus )?quest 2"),
        n("questpro", "(?:meta )?quest pro"),
        n("visionpro", "(?:apple )?vision pro"),
        n("index", "(?:valve )?index (?:vr|headset)|valve index"),
        n("vivepro2", "(?:htc )?vive pro 2"),
        n("xrelite", "(?:htc )?vive xr elite"),
        n("focusvision", "(?:htc )?vive focus vision"),
        n("beyond2", "(?:bigscreen )?beyond 2e?"),
        n("beyond", "bigscreen beyond(?! 2)"),
        n("psvr2", "(?:sony )?(?:ps ?vr ?2|playstation vr ?2)"),
        n("galaxyxr", "(?:samsung )?galaxy xr"),
    )

    // ---------------------------------------------------------------- wants

    private fun w(rx: String) = Regex(rx)

    private val WANTS: List<Pair<HeadWant, Regex>> = listOf(
        HeadWant.ANC to w("\\bnoise[- ]?cancel\\w*|\\banc\\b|\\btransparency\\b|\\bambient (?:sound|mode)\\b|\\baware mode\\b|\\badaptive audio\\b|\\bblock (?:out )?(?:noise|sound)\\b|\\bisolat\\w+"),
        HeadWant.CODECS to w("\\bcodecs?\\b|\\bldac\\b|\\baptx\\b|\\baac\\b|\\blc3\\b|\\bhi-?res\\b|\\blossless\\b|\\bsbc\\b"),
        HeadWant.IMPEDANCE to w("\\bimpedance\\b|\\bohms?\\b|\\bneed an? (?:amp|dac)\\b|\\bhard to drive\\b|\\bamp(?:lifier)?\\b|\\bdac\\b"),
        HeadWant.SPATIAL to w("\\bspatial\\b|\\b3d audio\\b|\\bsurround\\b|\\b7 ?1\\b|\\bdolby\\b|\\batmos\\b|\\bhead tracking\\b|\\bthx\\b|\\bdts\\b"),
        HeadWant.SOUND to w("\\bsound (?:signature|quality|profile)\\b|\\bfrequency response\\b|\\bhow (?:do|does) (?:it|they|the(?: [a-z0-9 ]+?)?) sound\\b|\\bsound(?:s)? good\\b|\\bbass\\w*\\b|\\btreble\\b|\\bv-?shaped\\b|\\bneutral\\b|\\bwarm\\b|\\btuning\\b|\\btuned\\b"),
        HeadWant.POWERBANK to w("\\bpower ?bank\\b|\\bcharge (?:a |my |your |the |other )?(?:phones?|devices?|iphone|ipad|tablet)\\b|\\bcharge other\\b"),
        HeadWant.PARTY to w("\\bstereo pair\\w*\\b|\\bparty ?(?:boost|mode|up)\\b|\\bauracast\\b|\\bstack mode\\b|\\b(?:pair|link|connect|use) (?:two|2|multiple|more|several|another)\\b|\\btwo of them\\b|\\bmulti-?room\\b|\\bstereo\\b|\\bairplay\\b"),
        HeadWant.LOUD to w("\\bhow loud\\b|\\bloud\\w*\\b|\\bwatts?\\b|\\bwattage\\b|\\bpower output\\b|\\b\\d+ ?w\\b|\\bvolume\\b"),
        HeadWant.WATER to w("\\bip rating\\b|\\bdust\\w*\\b|\\bfloats?\\b|\\bpool\\b|\\bbeach\\b|\\bwater\\w*\\b|\\bsweat\\w*\\b|\\bip ?x?\\d{1,2}\\b|\\brain\\b|\\bshower\\b|\\bswim\\w*\\b|\\bworkouts?\\b|\\bgym\\b|\\brunning\\b"),
        HeadWant.MIC to w("\\bmic\\b|\\bmics\\b|\\bmicrophones?\\b|\\bcalls?\\b|\\bvoice chat\\b"),
        HeadWant.DRIVERS to w("\\bdrivers?\\b|\\bplanar\\b"),
        HeadWant.FIT to w("\\bopen[- ]?back\\b|\\bclosed[- ]?back\\b|\\bover[- ]?ear\\b|\\bon[- ]?ear\\b|\\bin[- ]?ear\\b|\\bfit\\b|\\bcomfortable\\b|\\bcomfort\\b|\\bfold\\w*\\b|\\bear tips?\\b"),
        HeadWant.RESOLUTION to w("\\bresolution\\b|\\bpixels?\\b|\\bsharp\\w*\\b|\\bscreen door\\b|\\bppd\\b"),
        HeadWant.REFRESH to w("\\brefresh\\b|\\bhz\\b|\\bhertz\\b|\\bframe ?rate\\b|\\bfps\\b"),
        HeadWant.FOV to w("\\bfov\\b|\\bfield of view\\b|\\bhow wide\\b"),
        HeadWant.DISPLAY to w("\\bdisplays?\\b|\\bscreens?\\b|\\boled\\b|\\blcd\\b|\\blens(?:es)?\\b|\\bpancake\\b|\\bfresnel\\b|\\bhdr\\b"),
        HeadWant.PASSTHROUGH to w("\\bpass ?-?through\\b|\\bmixed reality\\b|\\bmr\\b|\\bar\\b|\\bsee (?:through|the room|around)\\b|\\bcolor cameras?\\b"),
        HeadWant.TRACKING to w("\\btracking\\b|\\bbase stations?\\b|\\blighthouses?\\b|\\binside[- ]?out\\b|\\b6 ?dof\\b|\\btracks?\\b"),
        HeadWant.CONTROLLERS to w("\\bcontrollers?\\b|\\bknuckles\\b"),
        HeadWant.CHIP to w("\\bchips?\\b|\\bprocessors?\\b|\\bsoc\\b|\\bsnapdragon\\b|\\bxr2\\b|\\bram\\b|\\bmemory\\b|\\bhow powerful\\b|\\bspecs\\b"),
        HeadWant.STORAGE to w("\\bstorage\\b|\\bgb\\b|\\btb\\b|\\bhow much space\\b"),
        HeadWant.PC to w("\\bpc ?vr\\b|\\bsteam ?vr\\b|\\blink cable\\b|\\bair ?link\\b|\\b(?:connect|hook|plug) (?:it )?(?:up )?(?:to|into) (?:a |my |the )?(?:pc|computer)\\b|\\bwork with (?:a |my )?(?:pc|computer|steam)\\b|\\bvirtual desktop\\b|\\bdisplayport\\b|\\btethered\\b|\\bon (?:a |my )?pc\\b|\\bwith (?:a |my )?pc\\b"),
        HeadWant.STANDALONE to w("\\bstand ?-?alone\\b|\\bwithout a (?:pc|computer|console|ps5)\\b|\\bneed (?:a |an )?(?:pc|computer|console|ps5|gaming pc)\\b|\\bon its own\\b|\\bby itself\\b|\\ball[- ]in[- ]one\\b"),
        HeadWant.BATTERY to w("\\bbattery\\b|\\bhow long (?:does|do|will|can|would) (?:it|they|the [a-z0-9 ]+?) (?:last|go|run)\\b|\\bper charge\\b|\\bcharge last\\b|\\blast on a charge\\b|\\bcharging case\\b|\\bhours\\b"),
        HeadWant.PLATFORMS to w("\\bps ?[45]\\b|\\bplaystation\\b|\\bxbox\\b|\\bswitch\\b|\\biphone\\b|\\bandroid\\b|\\bconsoles?\\b|\\bmac\\b|\\bcompatible\\b|\\bwork (?:with|on)\\b|\\bwhat (?:does it|do they) work with\\b"),
        HeadWant.WIRELESS to w("\\bwireless\\b|\\bcordless\\b|\\bwired\\b|\\bcable\\b"),
        HeadWant.CONNECTION to w("\\bconnect(?:s|ion|ions|ivity|ed)?\\b|\\bbluetooth\\b|\\b2 ?4 ?g(?:hz)?\\b|\\b2\\.4\\b|\\bdongle\\b|\\breceiver\\b|\\busb\\b|\\bjack\\b|\\b3 ?5 ?mm\\b|\\baux\\b|\\bmultipoint\\b|\\bpair\\w*\\b|\\bplug\\b"),
        HeadWant.WEIGHT to w("\\bweigh(?:t|s)?\\b|\\bheavy\\b|\\bhow light\\b|\\blightweight\\b|\\bgrams\\b"),
        HeadWant.PRICE to w("\\bprices?\\b|\\bcosts?\\b|\\bhow much\\b|\\bmsrp\\b|\\bexpensive\\b|\\bcheap\\b"),
        HeadWant.RELEASE to w("\\bwhen (?:did|was|were)\\b.*\\b(?:come out|released?|launch(?:ed)?|out|made)\\b|\\brelease date\\b|\\bcame out\\b|\\bhow old\\b|\\bwhat year\\b"),
        HeadWant.MAKER to w("\\bwho (?:makes|made|sells)\\b|\\bwhat (?:company|brand)\\b|\\bmade by\\b|\\bmaker\\b"),
        HeadWant.SCORE to w("\\breview\\w*\\b|\\bratings?\\b|\\bscored?\\b|\\bsoundguys\\b|\\bhow good\\b|\\bany good\\b|\\bworth (?:it|buying|the money)\\b|\\brecommend\\w*\\b|\\bis (?:it|the [a-z0-9 ]+?) good\\b|\\bare (?:they|the [a-z0-9 ]+?) good\\b"),
        HeadWant.NOTES to w("\\bnotes?\\b|\\bknown for\\b|\\bspecial about\\b|\\bpros\\b|\\bcons\\b|\\bstand out\\b|\\bgood for\\b"),
    )

    private fun fits(w: HeadWant, k: Kind) = when (w) {
        HeadWant.LOUD, HeadWant.PARTY, HeadWant.POWERBANK -> k == Kind.SPEAKER
        HeadWant.ANC, HeadWant.FIT, HeadWant.IMPEDANCE, HeadWant.SPATIAL -> k.audio && k != Kind.SPEAKER
        HeadWant.CODECS, HeadWant.DRIVERS, HeadWant.MIC, HeadWant.WATER, HeadWant.SOUND -> k.audio
        HeadWant.RESOLUTION, HeadWant.DISPLAY, HeadWant.REFRESH, HeadWant.FOV, HeadWant.TRACKING, HeadWant.CONTROLLERS, HeadWant.CHIP,
        HeadWant.STORAGE, HeadWant.PC, HeadWant.STANDALONE, HeadWant.PASSTHROUGH -> k == Kind.VR
        HeadWant.PLATFORMS -> k.audio
        else -> true
    }

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
    private val ACTION = Regex("^(?:please )?(?:turn|power|switch (?:on|off|to)|open|close|launch|start|play|connect|pair|buy|order|sell|install|uninstall|update|upgrade|download|reset|restart|find|track|remind|set|text|call|navigate|record|clean|fix|repair|return|charge|put on|use)\\b")
    private val HOW_TO = Regex("\\bhow (?:do|can|to|should|would) (?:i|you|we)\\b(?! (?:know|tell))|\\bhow to\\b|\\bwhere (?:can|do|to) (?:i )?(?:buy|get|find)\\b|\\b(?:deals?|on sale|in stock|restock|discounts?|coupons?|for sale)\\b|\\bwhere (?:are|is) my\\b|\\bfind my\\b")
    /** Trouble with one ("my airpods won't connect"): not a spec question. */
    private val TROUBLE = Regex("\\b(?:not working|doesn'?t work|won'?t (?:work|connect|charge|turn on|pair)|stopped working|broken|broke|stuck|lagging|keeps (?:disconnecting|cutting out)|disconnect(?:ing|s)|died|dead|drivers? (?:download|update)|firmware|manual|warranty|replacement|reset|crackl\\w+|one side|only one)\\b")
    /** Games and apps on a headset ("can i play beat saber on quest 3", "best quest 3 games"): the game parsers' questions. */
    private val GAMES = Regex("\\b(?:play(?:s|ing|ed)?|games?|apps?|steam games|titles)\\b")

    /** A question about headphones, earbuds, a gaming headset or a VR headset, or two of them compared; null otherwise. */
    fun parse(raw: String): HeadAsk? {
        var s = raw.lowercase(Locale.US).replace(Regex("[?!,;:\"()]+"), " ").replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
            .replace('’', '\'').replace(Regex("\\s+"), " ").trim()
        s = s.replace(PREFIX, "").trim()
        if (s.isEmpty() || ACTION.containsMatchIn(s) || HOW_TO.containsMatchIn(s) || TROUBLE.containsMatchIn(s) || GAMES.containsMatchIn(s)) return null
        val found = ArrayList(hits(s).take(2).map { Triple(it.id, it.start, it.end) })
        if (found.size < 2) {
            var masked = s
            for ((_, a, b) in found) masked = masked.substring(0, a) + " ".repeat(b - a) + masked.substring(b)
            for (u in unlisted(masked).take(2 - found.size)) found += u
        }
        if (found.isEmpty()) return null
        found.sortBy { it.second }
        // "the battery of my galaxy buds" is the phone's own Bluetooth battery readout, not a spec question
        // ("can the charge 5 charge my phone" still is).
        if (found.any { Regex("\\bmy (?:new |old |two |)$").containsMatchIn(s.substring(0, it.second)) }) return null
        // "is beat saber on the quest 3", "does spotify work with airpods": an app or game on the device, not the device.
        val before = s.substring(0, found[0].second)
        if (Regex("\\b(?:on|for|with|support(?:s|ed)?|compatible with)\\s+(?:the |my |a |an )?$").containsMatchIn(before) &&
            Regex("^(?:is|are|can|does|do|will|would)\\s+\\S+").containsMatchIn(before) && !Regex("^(?:is|are|does|do) (?:it|they|the|my|this|that)\\b").containsMatchIn(before)) return null
        var ids = found.map { it.first }
        // Two of a kind compare; headphones, speakers and VR headsets don't compare with each other.
        if (ids.size == 2) {
            val ka = kindOf(ids[0]); val kb = kindOf(ids[1])
            if (ka != null && kb != null && ka.group != kb.group) ids = ids.take(1)
        }
        var rest = s
        for ((_, a, b) in found.sortedByDescending { it.second }) rest = rest.substring(0, a) + " " + rest.substring(b)
        rest = rest.replace(Regex("\\s+"), " ").trim()
        val kind = kindOf(ids[0]) ?: guessKind(s)
        val want = WANTS.firstOrNull { (w, rx) -> rx.containsMatchIn(rest) && (kind == null || fits(w, kind)) }?.first ?: HeadWant.OVERVIEW
        // "does the quest 3 need a pc" reads as a PC question; "is the index wireless" as standalone.
        val vrWant = if (kind == Kind.VR && want == HeadWant.WIRELESS) HeadWant.STANDALONE else want
        return HeadAsk(ids, vrWant, raw)
    }

    // ---------------------------------------------------------------- devices not in the notes

    /** Live ids look like "live|Sony|wf-1000xm4". */
    private const val LIVE = "live|"
    fun isLive(id: String) = id.startsWith(LIVE)
    private fun liveParts(id: String) = id.removePrefix(LIVE).split('|').let { it[0] to it.getOrElse(1) { "" } }

    /** "Sony WF-1000XM4" for a live id, the device's name otherwise. */
    fun label(id: String): String = byId(id)?.name ?: liveParts(id).let { (m, model) ->
        "$m ${model.split(' ').joinToString(" ") { w -> if (w.any(Char::isDigit) || w.length <= 2) w.uppercase(Locale.US) else w.replaceFirstChar { it.uppercase() } }}"
    }

    private fun kindOf(id: String): Kind? = byId(id)?.kind ?: if (isLive(id)) liveKind(liveParts(id).first, liveParts(id).second) else null

    private fun guessKind(s: String): Kind? = when {
        Regex("\\b(?:vr|virtual reality|mixed reality|xr|quest|vive|index|vision|psvr|pico|beyond|varjo)\\b").containsMatchIn(s) -> Kind.VR
        Regex("\\b(?:speakers?|boombox|party ?box|soundbar|flip|charge|clip|go|xtreme|wonderboom|megaboom|hyperboom|everboom|epicboom|boom|soundlink(?! (?:revolve\\+? )?(?:headphones|around-ear|on-ear|ii|2))|roam|move|era|five|emberton|stanmore|acton|woburn|middleton|tufton|willen|stormbox|xsound|motion|pill|ult field|srs)\\b").containsMatchIn(s) -> Kind.SPEAKER
        Regex("\\b(?:ear ?buds|buds|in-?ears?|iems?|earphones|true wireless|airpods(?! max)|ear ?pods)\\b").containsMatchIn(s) -> Kind.EARBUDS
        Regex("\\b(?:gaming headsets?|headsets?|arctis|black ?shark|kraken|barracuda|cloud|astro|hs\\d|virtuoso|stealth|recon|maxwell|nari)\\b").containsMatchIn(s) -> Kind.HEADSET
        Regex("\\b(?:headphones?|cans|over-?ears?|on-?ears?)\\b").containsMatchIn(s) -> Kind.HEADPHONES
        else -> null
    }

    private fun liveKind(maker: String, model: String): Kind = guessKind(model.lowercase(Locale.US)) ?: when (maker) {
        "Meta", "HTC", "Valve", "Pico", "Bigscreen", "Varjo" -> Kind.VR
        "SteelSeries", "Razer", "HyperX", "Corsair", "Turtle Beach", "Logitech" -> Kind.HEADSET
        "Ultimate Ears", "Tribit" -> Kind.SPEAKER
        "Moondrop", "Truthear", "7Hz", "KZ", "Tin HiFi", "Kiwi Ears", "Tangzu", "Thieaudio", "Simgot", "Campfire Audio", "Etymotic", "Shokz" -> Kind.EARBUDS
        else -> Kind.HEADPHONES
    }

    /** Product families that name their maker ("arctis" is SteelSeries), so "arctis nova 5" can be looked up live. */
    private data class Family(val maker: String, val rx: Regex)
    private fun fam(maker: String, rx: String) = Family(maker, Regex("\\b(?:$rx)\\b"))
    private val FAMILIES = listOf(
        // Sony's model codes always hold a digit ("wh-1000xm4", "wf-c700n"): "where", "which" and "white" don't.
        fam("Sony", "w[hf] ?-?(?=[a-z]*\\d)[a-z0-9]{3,}|ult wear|inzone (?:h\\d|buds)|linkbuds\\w*|mdr ?-?(?=[a-z]*\\d)[a-z0-9]+|ps ?vr|playstation vr"),
        fam("Bose", "quiet ?comfort|qc ?(?:\\d{2}|ultra|earbuds)|bose (?:headphones|noise cancelling headphones|sport earbuds)"),
        fam("Apple", "air ?pods|powerbeats|beats (?:solo|studio|flex|studio buds|fit)"),
        fam("Sennheiser", "momentum (?:true wireless|sport|\\d)|hd ?\\d{3}\\w*|ie ?\\d{3}\\w*|accentum\\w*|cx ?\\d+\\w*"),
        fam("Audio-Technica", "ath ?-?[a-z0-9]+"),
        fam("Beyerdynamic", "dt ?\\d{3}|amiron|aventho"),
        fam("Samsung", "galaxy buds\\w*|buds ?\\d(?: pro| fe)?|buds (?:live|pro|fe)"),
        fam("Google", "pixel buds\\w*"),
        fam("Jabra", "jabra (?:elite|evolve)|elite \\d{1,2}(?: active| pro)?"),
        fam("JBL", "jbl (?:tune|live|tour|quantum|endurance|vibe|flip|charge|clip|go|xtreme|boombox|partybox|authentics|bar|wave|reflect)"),
        fam("Nothing", "nothing ear|cmf buds"),
        fam("Anker", "soundcore|liberty \\d|space (?:one|q\\d+)"),
        // Speakers.
        fam("Ultimate Ears", "(?:ultimate ears|ue) (?:wonderboom|boom|megaboom|hyperboom|everboom|epicboom|miniroll)|wonderboom|megaboom|hyperboom|everboom|epicboom"),
        fam("Bose", "sound ?link(?: (?:flex|max|micro|mini|revolve|color|home|plus))?"),
        fam("Sonos", "sonos (?:roam|move|era|one|five|ace|arc|beam|ray|sub|play)\\w*"),
        fam("Marshall", "emberton|stanmore|acton|woburn|middleton|kilburn|tufton|willen|marshall (?:major|minor|motif|monitor)"),
        fam("Beats", "beats pill(?: ?\\+| plus)"),
        fam("Sony", "ult field ?\\d|srs ?-?x[a-z]?\\d+"),
        fam("Tribit", "tribit|stormbox|xsound"),
        // IEMs and other earphones.
        fam("Moondrop", "moondrop|blessing ?\\d"),
        fam("Truthear", "truthear"),
        fam("7Hz", "7 ?hz|salnotes"),
        fam("KZ", "kz [a-z]{2,4} ?\\d+"),
        fam("Tin HiFi", "tin hifi|tinhifi"),
        fam("Kiwi Ears", "kiwi ears"),
        fam("Tangzu", "tangzu"),
        fam("Thieaudio", "thieaudio"),
        fam("Simgot", "simgot"),
        fam("Campfire Audio", "campfire audio"),
        fam("Etymotic", "etymotic"),
        fam("Shure", "aonic ?\\d*|shure se ?\\d{3}"),
        fam("Shokz", "shokz|aftershokz|openrun|openfit|opencomm|openswim|opendots"),
        fam("HiFiMan", "hifiman(?: (?:sundara|ananda|arya|edition xs|he\\d+\\w*))?"),
        fam("Focal", "focal (?:bathys|utopia|clear|elegia|stellia|celestee|azurys|hadenys)"),
        fam("Bowers & Wilkins", "bowers (?:and|&) wilkins|b ?& ?w px ?\\d+|px ?[78](?: s2e?)?"),
        fam("Skullcandy", "skullcandy|crusher (?:evo|anc)"),
        fam("SteelSeries", "arctis"),
        fam("Razer", "black ?shark|kraken|barracuda|nari|hammerhead"),
        fam("Logitech", "astro a\\d{2}|g ?\\d{3} (?:lightspeed )?(?:wireless )?headset|pro x (?:wireless )?headset"),
        fam("HyperX", "cloud(?: (?:ii|iii|alpha|flight|stinger|mix|orbit|core|revolver|earbuds))"),
        fam("Corsair", "hs ?\\d{2}|virtuoso|void (?:rgb|elite|wireless)"),
        fam("Turtle Beach", "turtle beach|stealth (?:\\d{3}|pro|600|700)|recon \\d+"),
        fam("Audeze", "audeze|lcd ?-?\\d|penrose|mobius"),
        fam("Meta", "oculus (?:quest|rift|go)|meta quest|quest (?:\\d|pro)"),
        fam("HTC", "vive(?: (?:pro|cosmos|focus|xr|flow|elite|\\d))*"),
        fam("Valve", "valve index"),
        fam("Pico", "pico (?:\\d|neo)"),
        fam("Bigscreen", "bigscreen beyond"),
        fam("Varjo", "varjo"),
        fam("Samsung", "galaxy xr|odyssey\\+? vr"),
    )
    private val MODEL_STOP = setOf("is", "are", "does", "do", "did", "have", "has", "had", "vs", "versus", "or", "and", "compared", "to", "with", "what", "whats",
        "how", "battery", "life", "last", "lasts", "weigh", "weighs", "weight", "noise", "cancelling", "canceling", "anc", "connect", "connects", "connection",
        "bluetooth", "price", "cost", "costs", "good", "worth", "headphones", "headphone", "earbuds", "buds", "headset", "headsets", "specs", "spec", "the", "a", "an",
        "it", "its", "of", "for", "on", "in", "use", "uses", "come", "comes", "much", "many", "long", "heavy", "light", "wireless", "wired", "really", "any",
        "against", "than", "better", "worse", "made", "make", "by", "who", "when", "released", "out", "new", "newest", "latest", "need", "needs", "pc", "resolution",
        "refresh", "rate", "field", "fov", "tracking", "controllers", "mic", "microphone", "codec", "codecs", "waterproof", "water", "fit", "drivers", "driver",
        "support", "supports", "work", "works", "vr", "standalone", "passthrough", "storage", "chip", "ram", "display", "screen",
        "speaker", "speakers", "loud", "sound", "sounds", "bass", "score", "review", "rating", "iem", "iems", "earphones", "power", "bank", "float", "floats",
        "stereo", "party", "charging", "case", "battery", "volume", "watts")

    private fun unlisted(s: String): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        for (f in FAMILIES) for (m in f.rx.findAll(s)) {
            if (out.any { m.range.first < it.third && it.second < m.range.last + 1 }) continue
            // The family's own words ("galaxy buds"), then the model words after it ("2 pro").
            val words = arrayListOf(m.value)
            var end = m.range.last + 1
            for (t in Regex("[a-z0-9+-]+").findAll(s, end)) {
                if (t.value in MODEL_STOP || words.size >= 6 || s.substring(end, t.range.first).isNotBlank()) break
                words += t.value; end = t.range.last + 1
            }
            val model = words.joinToString(" ").removePrefix(f.maker.lowercase(Locale.US) + " ").removePrefix("ue ").trim().ifBlank { continue }
            out += Triple("$LIVE${f.maker}|$model", m.range.first, end)
        }
        return out.sortedBy { it.second }
    }

    // ---------------------------------------------------------------- answers

    private fun an(word: String) = Peripherals.an(word)
    private fun the(d: Device) = "the ${d.short}"
    private fun The(d: Device) = "The ${d.short}"
    private fun lowerFirst(s: String): String {
        val w = s.substringBefore(' ')
        return if (s.length > 1 && s[1].isLowerCase() && w !in PROPER) s[0].lowercaseChar() + s.substring(1) else s
    }
    private val PROPER = setOf("Apple", "Sony", "Bose", "Bluetooth", "Qualcomm", "Meta", "Google", "Samsung", "Personalized", "Spatial", "Dolby", "THX", "DTS",
        "Razer", "Logitech", "SteelSeries", "HyperX", "Corsair", "Audeze", "Base", "Touch", "Index", "Vive", "Quest", "Android", "Wi-Fi", "Vive", "Inside-out",
        "PS5", "PC", "PlayStation", "Xbox", "Switch", "Best", "One-touch", "Fresnel", "Pancake", "Full-color", "Color", "Black-and-white", "Hand", "Wired:")
    private fun sentence(s: String) = s.trimEnd('.') + "."
    private fun list(items: List<String>): String = when (items.size) { 0 -> ""; 1 -> items[0]; 2 -> "${items[0]} or ${items[1]}"; else -> items.dropLast(1).joinToString(", ") + " or " + items.last() }
    private fun connectSay(d: Device) = list(d.connections.map { c ->
        when {
            c.startsWith("Wired: ") -> "a cable (" + c.removePrefix("Wired: ").substringBefore(",") + ")"
            c.startsWith("Wired (") -> "a " + c.substringAfter("(").substringBefore(")") + " cable"
            else -> c.substringBefore(" (")
        }
    })
    /** Names that read as plural ("AirPods", "Buds3 Pro", "QuietComfort Ultra Headphones"); "HD 600" and "WH-1000XM5" read as one. */
    private fun pair(d: Device) = Regex("(?i)pods|buds|headphones|earphones").containsMatchIn(d.short)
    /** "is"/"are": "The AirPods Pro 3 are", "The Quest 3 is". */
    private fun be(d: Device) = if (pair(d)) "are" else "is"
    private fun have(d: Device) = if (pair(d)) "have" else "has"
    private fun last(d: Device) = if (pair(d)) "last" else "lasts"

    fun answer(d: Device, want: HeadWant): String = when (want) {
        HeadWant.ANC -> d.anc?.let { a ->
            if (a.startsWith("No")) "No, ${the(d)} ${if (pair(d)) "don't" else "doesn't"} have active noise cancelling" +
                (if (a.contains(";")) ": ${a.substringAfter("; ")}." else if (d.fit?.contains("open-back") == true) "; ${if (pair(d)) "they're" else "it's"} open-back, so sound goes in and out." else ".")
            else "Yes, ${the(d)} ${have(d)} ${lowerFirst(a)}."
        } ?: missing(d, "noise cancelling")
        HeadWant.BATTERY -> when {
            d.battery != null -> "${The(d)} ${last(d)} ${d.battery}" + (d.case?.let { ", or $it" } ?: "") + "."
            !d.wireless && d.kind.audio -> "${The(d)} ${be(d)} wired, so there's no battery."
            d.kind == Kind.VR && d.standalone == false -> "${The(d)} runs off its cable, so there's no battery to run down."
            else -> missing(d, "the battery life")
        }
        HeadWant.WIRELESS -> if (d.wireless) "Yes, ${the(d)} ${be(d)} wireless: ${if (pair(d)) "they connect" else "it connects"} by ${connectSay(d)}." else "No, ${the(d)} ${be(d)} wired: ${connectSay(d)}."
        HeadWant.CONNECTION -> if (d.connections.isEmpty()) missing(d, "how it connects") else "${The(d)} ${if (pair(d)) "connect" else "connects"} by ${connectSay(d)}."
        HeadWant.CODECS -> d.codecs?.let { "${The(d)} ${if (pair(d)) "support" else "supports"} $it." } ?: if (!d.wireless) "${The(d)} ${be(d)} wired, so Bluetooth codecs don't apply." else missing(d, "the Bluetooth codecs")
        HeadWant.FIT -> d.fit?.let { "${The(d)}: ${lowerFirst(it)}." } ?: missing(d, "the fit")
        HeadWant.DRIVERS -> d.drivers?.let { "${The(d)} ${if (pair(d)) "use" else "uses"} ${driverSay(it)}." } ?: missing(d, "the drivers")
        HeadWant.MIC -> d.mic?.let { "${The(d)}: ${lowerFirst(it)}." } ?: if (d.kind == Kind.HEADPHONES && !d.wireless || d.kind == Kind.SPEAKER && !d.liveOnly) "${The(d)} ${have(d)} no mic." else missing(d, "the mic")
        HeadWant.WATER -> if (d.kind == Kind.SPEAKER) d.water?.let { "${The(d)} ${be(d)} rated $it" + (if (d.features?.contains("floats", ignoreCase = true) == true) ", and it floats." else ".") } ?: missing(d, "a water rating")
            else d.water?.let { "${The(d)} ${be(d)} rated $it for water and sweat." } ?: if (d.kind == Kind.EARBUDS || d.kind == Kind.HEADPHONES && d.wireless) "${d.maker} doesn't give ${the(d)} a water-resistance rating, so keep ${if (pair(d)) "them" else "it"} dry." else missing(d, "a water rating")
        HeadWant.PLATFORMS -> d.platforms?.let { "${The(d)} ${if (pair(d)) "work" else "works"} with: ${lowerFirst(it)}." } ?: if (d.wireless) "${The(d)} ${if (pair(d)) "work" else "works"} with anything that has Bluetooth." else "${The(d)} ${if (pair(d)) "plug" else "plugs"} into anything with the right jack: ${connectSay(d)}."
        HeadWant.SPATIAL -> d.spatial?.let { "${The(d)} ${have(d)} $it." } ?: missing(d, "spatial audio")
        HeadWant.IMPEDANCE -> d.impedance?.let { "${The(d)} ${be(d)} $it." } ?: missing(d, "the impedance")
        HeadWant.RESOLUTION -> d.perEye?.let { "${The(d)} shows $it pixels per eye" + (d.display?.let { x -> " on ${lowerFirst(x)}" } ?: "") + "." } ?: missing(d, "the resolution")
        HeadWant.DISPLAY -> d.display?.let { "${The(d)} uses ${lowerFirst(it)}" + (d.perEye?.let { p -> ", $p per eye" } ?: "") + (d.lenses?.let { l -> ", with ${l.lowercase(Locale.US)} lenses" } ?: "") + "." } ?: missing(d, "the screens")
        HeadWant.REFRESH -> d.refresh?.let { "${The(d)} runs at $it." } ?: missing(d, "the refresh rate")
        HeadWant.FOV -> d.fov?.let { "${The(d)}'s field of view is $it." } ?: missing(d, "the field of view")
        HeadWant.TRACKING -> d.tracking?.let { "${The(d)}: ${lowerFirst(it)}." } ?: missing(d, "the tracking")
        HeadWant.CONTROLLERS -> d.controllers?.let { "${The(d)}: ${lowerFirst(it)}." } ?: missing(d, "the controllers")
        HeadWant.CHIP -> d.chip?.let { "${The(d)} runs on the $it" + (d.ram?.let { r -> " with $r of RAM" } ?: "") + "." } ?: if (d.standalone == false) "${The(d)} has no chip of its own for games: the ${if (d.id == "psvr2") "PS5" else "PC"} does the work." else missing(d, "the chip")
        HeadWant.STORAGE -> d.storage?.let { "${The(d)} comes with $it of storage." } ?: if (d.standalone == false) "${The(d)} has no storage of its own: games live on the ${if (d.id == "psvr2") "PS5" else "PC"}." else missing(d, "the storage")
        HeadWant.PC -> d.pcvr?.let { sentence(it).let { p -> if (d.standalone == false) "${The(d)}: ${lowerFirst(p)}" else "Yes. ${p}" } } ?: missing(d, "how it works with a PC")
        HeadWant.STANDALONE -> when (d.standalone) {
            true -> "Yes, ${the(d)} runs on its own: no PC or console needed." + (d.pcvr?.let { " For PC VR: ${lowerFirst(sentence(it))}" } ?: "")
            false -> "No, ${the(d)} needs a " + (if (d.id == "psvr2") "PS5 (or a PC with Sony's adapter)." else "gaming PC.") + (d.pcvr?.let { " ${sentence(it)}" } ?: "")
            null -> missing(d, "whether it needs a PC")
        }
        HeadWant.PASSTHROUGH -> d.passthrough?.let { "${The(d)} has ${lowerFirst(it)}." } ?: missing(d, "passthrough")
        HeadWant.WEIGHT -> d.weightG?.let { "${The(d)} ${if (pair(d)) "weigh" else "weighs"} about ${grams(it)}" + (if (d.kind == Kind.EARBUDS) " each." else ".") } ?: missing(d, "the weight")
        HeadWant.PRICE -> priceSay(d)
        HeadWant.RELEASE -> d.year?.let { "${The(d)} came out in $it." } ?: overview(d)
        HeadWant.MAKER -> "${The(d)} ${be(d)} made by ${d.maker}."
        HeadWant.NOTES -> d.notes.firstOrNull()?.let { "${The(d)}: ${lowerFirst(it)}" } ?: overview(d)
        HeadWant.OVERVIEW -> overview(d)
        HeadWant.SOUND -> d.sound?.let { "SoundGuys lists ${the(d)} with $it." } ?: missing(d, "the sound signature")
        HeadWant.SCORE -> d.score?.let { "SoundGuys scores ${the(d)} ${scoreSay(it)} out of 10." + (if (!d.liveOnly) d.notes.firstOrNull()?.let { n -> " ${sentence(n)}" } ?: "" else "") }
            ?: answer(d, HeadWant.NOTES)
        HeadWant.LOUD -> d.power?.let { "${The(d)} puts out $it." } ?: missing(d, "the power output")
        HeadWant.PARTY -> d.features?.split("; ")?.filter { Regex("(?i)stereo|party|link|auracast|stack|pairs|multiroom|sonos system").containsMatchIn(it) }
            ?.takeIf { it.isNotEmpty() }?.let { "${The(d)}: ${lowerFirst(it.joinToString("; "))}." }
            ?: if (d.features != null && !d.liveOnly) "${The(d)} doesn't link with other speakers." else missing(d, "speaker linking")
        HeadWant.POWERBANK -> d.features?.split("; ")?.firstOrNull { it.startsWith("charges your phone", ignoreCase = true) }?.let { "Yes, ${the(d)} ${lowerFirst(it)}." }
            ?: if (d.features != null && !d.liveOnly) "No, ${the(d)} can't charge other devices." else missing(d, "phone charging")
    }

    private fun scoreSay(s: Double) = if (s % 1.0 == 0.0) s.toInt().toString() else s.toString()

    /** "40 mm dynamic" -> "40 mm dynamic drivers"; "Two-way: a woofer and a tweeter" stays as it is. */
    private fun driverSay(s: String): String {
        val x = lowerFirst(s)
        return if (Regex("(?i)driver|woofer|tweeter|:").containsMatchIn(x)) x else "$x drivers"
    }

    private fun grams(g: Double) = if (g < 20) "${if (g % 1.0 == 0.0) g.toInt().toString() else g.toString()} g" else "${g.toInt()} g"

    private fun overview(d: Device): String {
        val what = "${The(d)} ${be(d)} ${an(d.form)} ${d.form}" + (d.year?.let { " from $it" } ?: "")
        val detail = when {
            d.kind == Kind.VR -> d.perEye?.let { " with ${it.substringBefore(" (")} pixels per eye" + (d.refreshHz?.let { h -> " at up to $h Hz" } ?: "") } ?: ""
            d.batteryH != null -> " with up to ${if (d.batteryH % 1.0 == 0.0) d.batteryH.toInt().toString() else d.batteryH.toString()} hours of battery" +
                (d.case?.let { c -> Regex("\\d+").find(c)?.value?.let { " ($it with the case)" } } ?: "")
            d.impedance != null -> ", ${d.impedance.substringBefore(":")}"
            else -> ""
        }
        return "$what$detail. " + priceSay(d).replaceFirst(The(d), if (pair(d)) "They" else "It") +
            (d.score?.let { " SoundGuys scores ${if (pair(d)) "them" else "it"} ${scoreSay(it)} out of 10." } ?: "")
    }

    private fun money(p: Double) = "$" + String.format(Locale.US, "%,.2f", p).removeSuffix(".00")

    /** Launch price (or today's typical price), and today's price where Brick read it live. */
    private fun priceSay(d: Device): String {
        val now = d.now?.let { "${money(it)} on ${d.liveFrom ?: "${d.maker}'s site"} today" }
        val sells = if (pair(d)) "sell" else "sells"
        return when {
            now != null && d.priceUsd != null && d.price != "Not listed" && Math.abs(d.priceUsd - d.now) >= 1 ->
                "${The(d)} ${if (d.launch) "launched at ${d.price}" else "${if (pair(d)) "list" else "lists"} at ${d.price.removePrefix("about ")}"} and ${be(d)} $now."
            d.liveOnly -> now?.let { "${The(d)} ${be(d)} $it." } ?: if (d.priceUsd != null) "${The(d)} launched at ${d.price}." else "${d.liveFrom ?: "The page"} doesn't give a price for ${the(d)}."
            now != null -> "${The(d)} ${be(d)} $now."
            !d.launch -> "${The(d)} $sells for ${d.price.let { if (it.startsWith("about")) it else "$it (list price)" }}."
            else -> "${The(d)} launched at ${d.price}."
        }
    }

    /** A spec the live page didn't give, or the overview. */
    private fun missing(d: Device, what: String) = if (d.liveOnly) "${d.liveFrom ?: "The page"} doesn't list $what for ${the(d)}." else overview(d)

    // ---------------------------------------------------------------- comparing

    fun versus(a: Device, b: Device): Versus {
        fun fmt(d: Double) = if (d % 1.0 == 0.0) "%,d".format(Locale.US, d.toLong()) else d.toString()
        val bars = ArrayList<Versus.Bar>()
        if (a.kind == Kind.VR) {
            // Megapixels per eye: "2064 x 2208" doesn't fit the bar's label column.
            fun mp(p: Int?) = p?.let { String.format(Locale.US, "%.1f MP", it / 1_000_000.0) }
            bars += Versus.Bar("PIXELS/EYE", a.eyePixels?.toDouble(), b.eyePixels?.toDouble(), mp(a.eyePixels), mp(b.eyePixels), "more pixels per eye")
            bars += Versus.Bar("REFRESH", a.refreshHz?.toDouble(), b.refreshHz?.toDouble(), a.refreshHz?.let { "$it Hz" }, b.refreshHz?.let { "$it Hz" }, "a higher refresh rate")
            bars += Versus.Bar("FOV", a.fovDeg, b.fovDeg, a.fovDeg?.let { "${it.toInt()}°" }, b.fovDeg?.let { "${it.toInt()}°" }, "a wider view")
        } else {
            bars += Versus.Bar("BATTERY", a.batteryH, b.batteryH, a.batteryH?.let { "${fmt(it)} h" }, b.batteryH?.let { "${fmt(it)} h" }, "longer battery life")
            if (a.kind == Kind.SPEAKER) bars += Versus.Bar("POWER", a.powerW, b.powerW, a.powerW?.let { "${fmt(it)} W" }, b.powerW?.let { "${fmt(it)} W" }, "more power")
            bars += Versus.Bar("SG SCORE", a.score, b.score, a.score?.let { "${scoreSay(it)}/10" }, b.score?.let { "${scoreSay(it)}/10" }, "a higher SoundGuys score")
        }
        bars += Versus.Bar("WEIGHT", a.weightG, b.weightG, a.weightG?.let { grams(it) }, b.weightG?.let { grams(it) }, "less weight", lower = true)
        bars += Versus.Bar("PRICE", a.priceUsd, b.priceUsd, a.priceUsd?.let { money(it) }, b.priceUsd?.let { money(it) }, "a lower price", lower = true)
        val words = if (a.kind == Kind.SPEAKER) listOf(
            Versus.Words("TYPE", a.form, b.form),
            Versus.Words("WATER", a.water?.substringBefore(" ("), b.water?.substringBefore(" (")),
            Versus.Words("DRIVERS", a.drivers, b.drivers),
            Versus.Words("CODECS", a.codecs, b.codecs),
            Versus.Words("EXTRAS", a.features, b.features),
            Versus.Words("CONNECT", a.connections.joinToString("; ").ifBlank { null }, b.connections.joinToString("; ").ifBlank { null }),
        ) else if (a.kind == Kind.VR) listOf(
            Versus.Words("TYPE", a.form, b.form),
            Versus.Words("DISPLAY", a.display, b.display),
            Versus.Words("LENSES", a.lenses, b.lenses),
            Versus.Words("CHIP", a.chip, b.chip),
            Versus.Words("TRACKING", a.tracking, b.tracking),
            Versus.Words("PC VR", a.pcvr, b.pcvr),
        ) else listOf(
            Versus.Words("TYPE", a.form.removePrefix("pair of "), b.form.removePrefix("pair of ")),
            Versus.Words("FIT", a.fit, b.fit),
            Versus.Words("ANC", a.anc, b.anc),
            Versus.Words("CODECS", a.codecs, b.codecs),
            Versus.Words("DRIVERS", a.drivers, b.drivers),
            Versus.Words("MIC", a.mic, b.mic),
            Versus.Words("WATER", a.water, b.water),
            Versus.Words("CONNECT", a.connections.joinToString("; ").ifBlank { null }, b.connections.joinToString("; ").ifBlank { null }),
        )
        val kept = bars.filter { it.a != null && it.b != null }
        return Versus(a.name, b.name, kept, (words + Versus.Words("OUT", a.year?.toString(), b.year?.toString())).filter { it.a != null || it.b != null },
            Tech.verdict(a.short, b.short, kept))
    }

    // ---------------------------------------------------------------- the card

    /** The HEADPHONES, EARBUDS, HEADSET or VR HEADSET card, or the VERSUS card for two. */
    suspend fun find(ask: HeadAsk): LookupResult? = withContext(Dispatchers.IO) {
        // One at a time when both need Wikipedia (its request limits); maker sites can load side by side.
        val devices = if (ask.ids.count { isLive(it) && !GearLive.coversHeadsets(liveParts(it).first) } > 1) ask.ids.mapNotNull { runCatching { resolve(it) }.getOrNull() }
            else ask.ids.map { id -> async { runCatching { resolve(id) }.getOrNull() } }.awaitAll().filterNotNull()
        if (devices.isEmpty()) return@withContext null
        if (devices.size == 2 && devices[0].kind.group == devices[1].kind.group) {
            val (a, b) = devices
            val v = versus(a, b)
            return@withContext LookupResult(a.name, null, null, "", null, web(a), emptyList(), v.verdict,
                sources = sources(devices), versus = v, links = listOf(b.name to web(b)),
                more = listOf("NOTE" to listOf(if (a.kind == Kind.VR) "Specs are the makers' own; field of view varies with fit and how each maker measures it."
                    else if (a.kind == Kind.SPEAKER) "Specs are the makers' own; battery life depends on volume." + (if (a.score != null || b.score != null) " Scores are SoundGuys'." else "")
                    else "Specs are the makers' own; battery life depends on volume and noise cancelling." + (if (a.score != null || b.score != null) " Scores are SoundGuys'." else ""))))
        }
        val d = devices[0]
        LookupResult(d.name, null, null, "", null, web(d), emptyList(), answer(d, ask.want), sources = sources(devices), heading = d.kind.heading, headgear = d)
    }

    private fun sources(ds: List<Device>) = listOfNotNull(
        ds.flatMap { liveNames(it) }.distinct().takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} (live)" },
        if (ds.any { !it.liveOnly }) "Brick's gear notes" else null)

    /** Where a device's live facts came from: its page, and SoundGuys for a score. */
    private fun liveNames(d: Device) = listOfNotNull(d.liveFrom, if (d.score != null && d.liveFrom != SG) SG else null)

    private fun web(d: Device) = d.liveUrl ?: ("https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(d.name, "UTF-8"))

    /** A device from the notes (gaming headsets get today's price from the maker's site), or one read live. */
    private fun resolve(id: String): Device? {
        if (isLive(id)) {
            val (maker, model) = liveParts(id)
            // A gaming headset from its maker; headphones, earbuds, IEMs and speakers from SoundGuys; then Wikipedia.
            return headsetLive(id, maker, model)?.let { withSoundGuys(it) }
                ?: (if (liveKind(maker, model) != Kind.VR) SoundGuys.lookup(maker, model)?.let { fromSoundGuys(id, maker, model, it) } else null)
                ?: HeadgearLive.wiki(maker, model)?.let { fromWiki(id, maker, model, it) }
        }
        var d = byId(id) ?: return null
        if (d.kind == Kind.HEADSET && GearLive.coversHeadsets(d.maker)) {
            GearLive.headsetPage(d.maker, d.short.replace(Regex(" \\(\\d{4}\\)$"), ""))?.let { page -> d = d.copy(liveUrl = page.url, liveFrom = "${d.maker}'s site", now = page.price) }
        }
        return withSoundGuys(d)
    }

    private const val SG = "SoundGuys"

    /** Whether SoundGuys' product is the same sort of thing (a speaker isn't the headphones of the same name). */
    private fun sameKind(k: Kind, p: SoundGuys.Product) = when (k) {
        Kind.SPEAKER -> p.speaker
        Kind.EARBUDS -> !p.speaker && (p.earbuds || p.category.isNullOrBlank())
        Kind.HEADPHONES, Kind.HEADSET -> !p.speaker && !p.earbuds
        Kind.VR -> false
    }

    /** A notes device with SoundGuys' score, today's price there, and any specs the notes leave out. */
    private fun withSoundGuys(d: Device): Device {
        if (!d.kind.audio) return d
        val p = SoundGuys.lookup(d.maker, d.name.removePrefix("${d.maker} ")) ?: return d
        if (!sameKind(d.kind, p)) return d
        val f = fromSoundGuys(d.id, d.maker, d.short, p)
        val priced = d.liveFrom == null
        return d.copy(
            year = d.year ?: f.year, water = d.water ?: f.water, codecs = d.codecs ?: f.codecs, drivers = d.drivers ?: f.drivers,
            impedance = d.impedance ?: f.impedance, weightG = d.weightG ?: f.weightG, power = d.power ?: f.power, powerW = d.powerW ?: f.powerW,
            sound = d.sound ?: f.sound, features = d.features ?: f.features, spatial = d.spatial ?: f.spatial,
            score = p.score, scoreUrl = p.url,
            liveUrl = if (priced) p.url else d.liveUrl, liveFrom = if (priced) SG else d.liveFrom, now = if (priced) p.price else d.now,
        )
    }

    private fun hours(h: Double) = if (h >= 10 || h % 1.0 == 0.0) Math.round(h).toString() else String.format(Locale.US, "%.1f", h)
    private fun num(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()

    /** A device from SoundGuys' product listing: its structured specs, score and price (never the review's words). */
    internal fun fromSoundGuys(id: String, maker: String, model: String, p: SoundGuys.Product): Device {
        val brand = p.brand?.takeIf { it.isNotBlank() } ?: maker
        val name = p.name.trim().let { n -> if (n.contains(maker, ignoreCase = true) || n.contains(brand, ignoreCase = true)) n else "$maker $n" }
        val kind = when {
            p.speaker -> Kind.SPEAKER
            p.earbuds -> Kind.EARBUDS
            guessKind(name.lowercase(Locale.US)) == Kind.HEADSET || liveKind(maker, model) == Kind.HEADSET -> Kind.HEADSET
            else -> Kind.HEADPHONES
        }
        val ct = p.specs["Connection Type"].orEmpty().lowercase(Locale.US)
        val wiredOnly = ct.isNotBlank() && !Regex("bluetooth|wireless|wi-?fi|2\\.4").containsMatchIn(ct)
        val connections = buildList {
            if (Regex("2\\.4|dongle").containsMatchIn(ct)) add("2.4 GHz wireless (USB dongle)")
            if ("bluetooth" in ct || (!wiredOnly && p.spec("Bluetooth Version") != null))
                add("Bluetooth" + (p.spec("Bluetooth Version")?.let { " $it" } ?: "") + (if (p.specs["Multipoint Devices"]?.lowercase(Locale.US)?.startsWith("yes") == true) ", multipoint" else ""))
            if (Regex("wi-?fi").containsMatchIn(ct)) add("Wi-Fi")
            if (Regex("3\\.5|aux").containsMatchIn(ct) || p.yes("Headphone Jack") == true) add(if (kind == Kind.SPEAKER) "Wired (3.5 mm aux in)" else JACK)
            if (Regex("wired[- ]usb").containsMatchIn(ct)) add(if (kind == Kind.SPEAKER) "Wired (USB-C audio)" else USBC)
        }
        val wireless = connections.any { !it.startsWith("Wired") }
        // Battery: with noise cancelling on first, where SoundGuys lists both.
        val ancOn = p.num("Battery Life Anc On"); val plain = p.num("Battery Life Earbuds", "Battery Life Wireless", "Battery Life")
        val head = ancOn ?: plain
        val battery = head?.takeIf { wireless }?.let { "up to ${hours(it)} hours" + (if (ancOn != null && plain != null && plain - ancOn >= 1) " with noise cancelling on (${hours(plain)} off)" else "") }
        val nc = p.specs["Noise Cancellation Type"]?.lowercase(Locale.US)
        val anc = when {
            kind == Kind.SPEAKER || nc == null -> null
            Regex("active|anc|hybrid|adaptive|feedback").containsMatchIn(nc) -> "Active noise cancelling" + (if (p.yes("Adaptive Anc") == true) ", adaptive" else "") +
                (if (p.specs["Ambient Mode"]?.lowercase(Locale.US)?.startsWith("yes") == true) ", with a transparency mode" else "")
            else -> NO_ANC
        }
        val sizes = p.spec("Driver Size")?.split(Regex("\\s*,\\s*"))?.mapNotNull { it.toDoubleOrNull()?.let(::num) }.orEmpty()
        val type = p.spec("Driver Type")?.lowercase(Locale.US)?.replace(Regex("\\s*drivers?$"), "")?.ifBlank { null }
        val count = p.num("Driver Count")?.toInt()
        val drivers = when {
            kind == Kind.SPEAKER -> count?.let { if (it == 1) "One driver" else "$it drivers" }
            sizes.size > 1 -> "${count ?: sizes.size} ${type?.let { "$it " } ?: ""}drivers (${sizes.joinToString(" mm and ")} mm)"
            sizes.size == 1 -> "${sizes[0]} mm" + (type?.let { " $it" } ?: "") + (if ((count ?: 1) > 1) " (x$count)" else "")
            count != null && count > 1 -> "$count ${type?.let { "$it " } ?: ""}drivers"
            else -> type
        }
        val ohms = p.num("Impedance")
        val impedance = ohms?.let { o ->
            "${num(o)} ohms" + (p.num("Sensitivity")?.let { s -> ", ${num(s)} dB sensitivity" } ?: "") +
                (if (o >= 150) ": an amp helps" else if (o <= 50 && kind != Kind.SPEAKER) ": easy to drive from a phone or dongle" else "")
        }
        val hasMic = p.yes("Microphone") ?: p.yes("Has Microphone")
        val mic = when {
            kind == Kind.SPEAKER -> if (p.yes("Speakerphone") == true) "Speakerphone for calls" else null
            hasMic == false -> "No mic"
            hasMic == true -> p.num("Count")?.toInt()?.takeIf { it > 1 }?.let { "$it mics for calls" } ?: "Mic for calls"
            else -> null
        }
        val fit = when (kind) {
            Kind.EARBUDS -> listOfNotNull(p.spec("Earbud Form Factor")?.let(::cap), p.spec("Wearing Style"), p.spec("Ear Tip Sizes Included")?.let { "$it tips" },
                if (p.yes("Detachable Cable") == true) "detachable cable" else null).joinToString("; ").ifBlank { null }
            Kind.HEADPHONES, Kind.HEADSET -> listOfNotNull(p.spec("Acoustic Design")?.let(::cap), if (p.yes("Foldable") == true) "folds" else null).joinToString(", ").ifBlank { null }
            else -> null
        }
        val fr = p.spec("Frequency Response")?.let { Regex("(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)").find(it) }?.let { m ->
            fun hz(v: Double) = if (v >= 1000) "${num(v / 1000)} kHz" else "${num(v)} Hz"
            "${hz(m.groupValues[1].toDouble())} to ${hz(m.groupValues[2].toDouble())}"
        }
        val sig = p.spec("Frequency Response Signature")
        val sound = listOfNotNull(sig?.let { "${an(it)} $it sound signature" }, fr?.let { "a frequency response of $it" }).joinToString(" and ").ifBlank { null }
        val wifi = connections.contains("Wi-Fi")
        val features = if (kind != Kind.SPEAKER) null else listOfNotNull(
            if (p.yes("Floating Design") == true) "Floats" else null,
            if (p.yes("Powerbank Output") == true) "charges your phone" else null,
            if (p.yes("Stereo Pairing") == true) "pairs with another for stereo" else null,
            if (p.yes("Multi Speaker Chain") == true) "links with more speakers for parties" else null,
            if (wifi || p.yes("Multiroom") == true) "plays in sync with other speakers over Wi-Fi (multiroom)" else null,
        ).joinToString("; ").ifBlank { null }?.let(::cap)
        val portable = p.yes("Is Portable") != false
        val form = when (kind) {
            Kind.SPEAKER -> (if (portable) "portable " else "") + (if (wifi) "Wi-Fi and Bluetooth speaker" else if (wireless) "Bluetooth speaker" else "speaker")
            Kind.EARBUDS -> "pair of " + (if (wireless) "wireless " else "wired ") + (if (anc?.startsWith("Active") == true) "noise-cancelling " else "") +
                (if (!wireless && (p.yes("Detachable Cable") == true || p.spec("Wearing Style")?.contains("over-ear") == true)) "in-ear monitors" else "earbuds")
            Kind.HEADPHONES -> "pair of " + (if (wireless) "wireless " else "wired ") + (if (anc?.startsWith("Active") == true) "noise-cancelling " else "") +
                (p.spec("Acoustic Design")?.takeIf { it.contains("open", true) }?.let { "open-back " } ?: "") + "headphones"
            else -> (if (wireless) "wireless " else "wired ") + "gaming headset"
        }
        val msrp = p.num("Msrp Usd")
        val year = p.num("Year")?.toInt()?.takeIf { it in 1950..2100 } ?: p.spec("Date")?.let { Regex("\\b(19|20)\\d\\d\\b").find(it)?.value?.toIntOrNull() }
        val weight = if (kind == Kind.EARBUDS) p.num("Weight Per Earbud") else p.num("Weight")
        return Device(id, kind, name, maker, year, msrp?.let { money(it) } ?: "Not listed", msrp ?: p.price, form = form,
            fit = fit, anc = anc, battery = battery, batteryH = head?.takeIf { wireless }, case = p.num("Battery Life With Case")?.let { "up to ${hours(it)} hours with the case" },
            connections = connections, codecs = p.spec("Bluetooth Codec")?.replace(Regex("(?i)\\baptx"), "aptX"), drivers = drivers, mic = mic,
            water = p.spec("IP Rating")?.uppercase(Locale.US)?.takeIf { it.startsWith("IP") }, impedance = impedance, weightG = weight?.takeIf { it > 0 },
            spatial = p.specs["Spatial Audio"]?.let { v -> if (v.equals("true", true)) "Spatial audio" else if (v.isNotBlank() && !v.equals("false", true) && !v.equals("none", true)) v else null },
            power = p.num("Total Power Output")?.let { "${num(it)} W" }, powerW = p.num("Total Power Output"), features = features, sound = sound,
            notes = listOf("From SoundGuys' product listing; Brick has no notes of its own on this one."),
            liveUrl = p.url, liveFrom = SG, now = p.price, score = p.score, scoreUrl = p.url)
    }

    /** A gaming headset from its maker's site. */
    private fun headsetLive(id: String, maker: String, model: String): Device? {
        if (!GearLive.coversHeadsets(maker)) return null
        val page = GearLive.headsetPage(maker, model) ?: return null
        val f = HeadgearLive.readHeadset(page.html)
        if (f.found < 2) return null
        val title = page.title?.replace(Regex("(?i)(?:\\s+(?:wireless|wired|gaming|rgb|multi-platform|premium|esports))*\\s+(?:headset|headphones)$"), "")?.trim()?.takeIf { it.length in 3..80 } ?: label(id).removePrefix("$maker ")
        val name = if (title.contains(maker, ignoreCase = true)) title else "$maker $title"
        return Device(id, Kind.HEADSET, name, maker, null, page.price?.let { money(it) } ?: "Not listed", page.price,
            form = (if (f.connections.any { Regex("(?i)wireless|bluetooth|2\\.4").containsMatchIn(it) }) "wireless " else "wired ") + "gaming headset",
            anc = f.anc, battery = f.battery, batteryH = f.batteryH, connections = f.connections, drivers = f.drivers, mic = f.mic, weightG = f.weightG,
            spatial = f.spatial, platforms = f.platforms,
            notes = listOf("Read live from $maker's website; Brick has no notes of its own on this one, so some specs may be missing."),
            liveUrl = page.url, liveFrom = "$maker's site", now = page.price)
    }

    /** A device built from its Wikipedia article's infobox and opening. */
    private fun fromWiki(id: String, maker: String, model: String, w: HeadgearLive.Wiki): Device {
        val kind = w.kind ?: liveKind(maker, model)
        val name = w.title.substringBefore(" (")
        val form = when (kind) {
            Kind.VR -> when (w.standalone) { true -> "standalone VR headset"; false -> "PC VR headset"; null -> "VR headset" }
            Kind.EARBUDS -> "pair of " + (if (w.connections.any { it.contains("Bluetooth") }) "wireless " else "") + "earbuds"
            Kind.HEADPHONES -> "pair of " + (if (w.connections.any { it.contains("Bluetooth") }) "wireless " else "") + "headphones"
            Kind.HEADSET -> (if (w.connections.any { it.contains("Bluetooth") || it.contains("2.4") }) "wireless " else "") + "gaming headset"
            Kind.SPEAKER -> (if (w.connections.any { it.contains("Bluetooth") }) "Bluetooth " else "") + "speaker"
        }
        return Device(id, kind, if (name.contains(maker, ignoreCase = true)) name else "$maker $name", maker, w.year,
            w.price?.let { money(it) } ?: "Not listed", w.price, form = form,
            battery = w.battery, batteryH = w.batteryH, connections = w.connections, codecs = w.codecs, weightG = w.weightG, chip = w.chip,
            display = w.display, perEye = w.perEye, eyePixels = w.eyePixels, refresh = w.refresh, refreshHz = w.refreshHz, fov = w.fov, fovDeg = w.fovDeg,
            tracking = w.tracking, controllers = w.controllers, ram = w.ram, os = w.os, standalone = w.standalone,
            // "128 GB 256 GB" -> "128 GB or 256 GB".
            storage = w.storage?.replace(Regex("(GB|TB)\\s+(?=\\d)"), "$1 or "),
            notes = listOfNotNull(w.intro, "Read live from Wikipedia; Brick has no notes of its own on this one, so some specs may be missing."),
            liveUrl = w.url, liveFrom = "Wikipedia")
    }

    private fun kv(rows: MutableList<String>, label: String, value: String?, max: Int = 3) {
        // The card's font has no degree sign ("110° wide" would show as "110o wide").
        if (!value.isNullOrBlank()) rows += Box.wrap(Box.ascii(value.replace("°", " deg")), first = Box.kv(label, ""), indent = 7, max = max)
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** "a V-shaped sound signature and a frequency response of 20 Hz to 20 kHz" -> "V-shaped; 20 Hz to 20 kHz". */
    private fun soundRow(d: Device) = d.sound?.replace(Regex("^an? "), "")?.replace(" sound signature and a frequency response of ", "; ")
        ?.replace(" sound signature", "")?.replace(Regex("(?:a )?frequency response of "), "")?.let(::cap)

    fun text(d: Device, answer: String?): String {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(d.name), max = 2)
        rows += Box.wrap(Box.ascii(cap(d.form.removePrefix("pair of "))) + (d.year?.let { ", $it" } ?: ""), max = 2)
        answer?.let { rows += ""; rows += Box.wrap(Box.ascii(it), "> ", indent = 2, max = 8) }
        rows += ""
        kv(rows, "MAKER", d.maker)
        if (!d.liveOnly || d.now == null || d.priceUsd != d.now) kv(rows, "PRICE", d.price.takeIf { it != "Not listed" }?.let { if (d.launch || it.startsWith("about")) it else "$it (list)" })
        kv(rows, "NOW", d.now?.let { "${money(it)} on ${d.liveFrom ?: "${d.maker}'s site"}" })
        kv(rows, "SCORE", d.score?.let { "${scoreSay(it)}/10 on SoundGuys" })
        rows += ""
        if (d.kind == Kind.SPEAKER) {
            kv(rows, "POWER", d.power)
            kv(rows, "DRIVERS", d.drivers)
            kv(rows, "CODECS", d.codecs)
            kv(rows, "SOUND", soundRow(d))
            kv(rows, "MIC", d.mic)
            rows += ""
            kv(rows, "BATTERY", d.battery?.let { cap(it) } ?: if (d.connections.isNotEmpty() && d.connections.none { it.startsWith("Bluetooth") }) "None (plugs in)" else null)
            kv(rows, "WATER", d.water)
            kv(rows, "WEIGHT", d.weightG?.let { if (it >= 1000) String.format(Locale.US, "%.1f kg", it / 1000) else grams(it) })
            kv(rows, "EXTRAS", d.features, max = 5)
        } else if (d.kind == Kind.VR) {
            kv(rows, "TYPE", when (d.standalone) { true -> "Standalone" + (if (d.pcvr != null && !d.pcvr.startsWith("No")) ", also PC VR" else ""); false -> if (d.id == "psvr2") "Plugs into a PS5" else "PC VR (needs a PC)"; null -> null })
            kv(rows, "DISPLAY", d.display)
            kv(rows, "PER EYE", d.perEye)
            kv(rows, "REFRESH", d.refresh)
            kv(rows, "FOV", d.fov)
            kv(rows, "LENSES", d.lenses)
            rows += ""
            kv(rows, "CHIP", d.chip)
            kv(rows, "RAM", d.ram)
            kv(rows, "STORAGE", d.storage)
            kv(rows, "OS", d.os)
            rows += ""
            kv(rows, "TRACKING", d.tracking, max = 4)
            kv(rows, "CONTROLS", d.controllers, max = 4)
            kv(rows, "PASSTHRU", d.passthrough)
            kv(rows, "PC VR", d.pcvr, max = 4)
            kv(rows, "BATTERY", d.battery?.let { cap(it) })
            kv(rows, "WEIGHT", d.weightG?.let { grams(it) })
        } else {
            kv(rows, "FIT", d.fit)
            kv(rows, "ANC", d.anc, max = 4)
            kv(rows, "DRIVERS", d.drivers)
            kv(rows, "CODECS", d.codecs)
            kv(rows, "SOUND", soundRow(d))
            kv(rows, "SPATIAL", d.spatial)
            kv(rows, "OHMS", d.impedance)
            kv(rows, "MIC", d.mic)
            kv(rows, "CHIP", d.chip)
            rows += ""
            kv(rows, "WIRELESS", if (d.connections.isEmpty()) null else if (d.wireless) "Yes" else "No, wired only")
            kv(rows, "BATTERY", d.battery?.let { cap(it) } ?: if (!d.wireless && d.connections.isNotEmpty()) "None (wired)" else null)
            kv(rows, "CASE", d.case?.removeSuffix(" with the case")?.let { cap(it) })
            kv(rows, "WATER", d.water)
            kv(rows, "WEIGHT", d.weightG?.let { grams(it) + if (d.kind == Kind.EARBUDS) " each" else "" })
            kv(rows, "WORKS ON", d.platforms, max = 4)
        }
        if (d.connections.isNotEmpty()) {
            rows += ""
            rows += "CONNECTS BY"
            for (c in d.connections) rows += Box.wrap(Box.ascii(c), "- ", indent = 2, max = 2)
        }
        if (d.notes.isNotEmpty()) {
            rows += ""
            rows += "NOTES"
            for (n in d.notes) rows += Box.wrap(Box.ascii(n), "- ", indent = 2, max = 3)
        }
        rows += ""
        val live = liveNames(d).joinToString(", ")
        val source = when {
            d.liveOnly -> "$live (live)"
            live.isNotEmpty() -> "Brick's gear notes, $live (live)"
            else -> "Brick's gear notes (makers' specs)"
        }
        rows += Box.wrap(source, first = Box.kv("SOURCE", ""), indent = 7, max = 2)
        return Box.build(d.kind.heading, rows)
    }
}
