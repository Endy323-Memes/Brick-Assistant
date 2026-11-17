package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** What's asked about a console or an operating system. */
enum class PlatformWant { OVERVIEW, MAKER, PARENT, PERFORMANCE, EXCLUSIVES, SALES, PRICE, RELEASE, PLATFORM, KERNEL, DISTRO, KNOWN, TYPE, USERS }

/** One console or OS ([ids] has two for a comparison); [detail] is what it should be good for ("gaming"). */
data class PlatformAsk(val ids: List<String>, val want: PlatformWant, val said: String, val detail: String? = null) {
    val compare get() = ids.size > 1
}

/**
 * Video game consoles and operating systems from Brick's own notes: who makes and owns them, how strong they are,
 * their famous games, kernels and distributions, what they're for and how many people use them; two side by side.
 */
object Platforms {
    sealed interface Entry { val id: String; val name: String; val wiki: String }

    data class Console(
        override val id: String, override val name: String, val short: String, override val wiki: String,
        val maker: String, val parent: String?, val ownerNote: String,
        val released: String, val year: Int, val price: String, val priceUsd: Double?, val form: String,
        val cpu: String, val gpu: String, val tflops: Double?, val ram: String, val ramGb: Double?,
        val storage: String, val output: String, val pc: String?,
        val games: List<String>, val gamesNote: String?, val sold: String, val soldM: Double?,
        val online: String?, val plays: String?, val known: String,
        /** Today's prices from the maker's own store (option to US dollars), and which store. */
        val now: List<Pair<String, Double>> = emptyList(), val nowFrom: String? = null,
        /** Units sold from Wikipedia's infobox today ("100 million (as of November 10, 2026)"). */
        val liveSold: String? = null,
    ) : Entry

    enum class OsType(val label: String) { ALL("All-purpose"), GAMING("Gaming"), WORK("Productivity"), MOBILE("Mobile, all-purpose"), SERVER("Server and enterprise"), SECURITY("Security testing") }

    data class Os(
        override val id: String, override val name: String, override val wiki: String,
        val maker: String, val parent: String?, val ownerNote: String, val year: Int,
        val platforms: String, val kernel: String, val version: String?, val distro: String?, val distroSay: String?,
        val type: OsType, val typeWhy: String, val known: String, val gaming: String,
        val users: String, val usersM: Double?, val market: String?, val share: Double?,
        val price: String, val license: String, val scores: List<Int>,
        val liveKernel: String? = null,
        /** The latest release from Wikipedia's infobox today ("26H1 (10.0.28000.3501) (November 10, 2026)"). */
        val latest: String? = null,
    ) : Entry

    // ---------------------------------------------------------------- consoles

    private const val SIE = "Sony Interactive Entertainment"
    private const val SONY_NOTE = "Sony Interactive Entertainment runs PlayStation; it's a subsidiary of Sony Group Corporation in Tokyo."
    private const val SCE = "Sony Computer Entertainment (now Sony Interactive Entertainment)"
    private const val XBOX = "Microsoft, through its Xbox division (Microsoft Gaming)"
    private const val XBOX_NOTE = "Xbox is a division of Microsoft Gaming, which belongs to Microsoft Corporation."
    private const val NINTENDO_NOTE = "Nintendo has no parent company: it's an independent, publicly traded company based in Kyoto."
    private const val XBOX_GAMES = "Most Xbox games also come to Windows PC, and since 2024 many come to PlayStation too."
    private const val SONY_GAMES = "Many of Sony's games reach PC a year or two later, but they stay off Xbox and Nintendo."

    val CONSOLES: List<Console> = listOf(
        Console("ps5", "PlayStation 5", "PS5", "PlayStation 5", SIE, "Sony Group Corporation", SONY_NOTE,
            "November 2020", 2020, "\$499 (\$399 Digital Edition)", 499.0, "Home console",
            "8-core AMD Zen 2, up to 3.5 GHz", "AMD RDNA 2, 36 CUs at up to 2.23 GHz", 10.28, "16 GB GDDR6", 16.0,
            "825 GB SSD (1 TB on the slim model)", "4K up to 120 fps; 8K output", "Roughly an RTX 2070 Super",
            listOf("Astro Bot", "Marvel's Spider-Man 2", "God of War Ragnarok", "Ghost of Yotei", "Demon's Souls", "Ratchet & Clank: Rift Apart", "Returnal", "Gran Turismo 7"),
            SONY_GAMES, "84.2 million (Sony, Sep 2025)", 84.2, "PlayStation Plus", "Most PS4 games",
            "its very fast SSD, the DualSense controller's haptics and adaptive triggers, and Sony's story-driven exclusives"),
        Console("ps5pro", "PlayStation 5 Pro", "PS5 Pro", "PlayStation 5 Pro", SIE, "Sony Group Corporation", SONY_NOTE,
            "November 2024", 2024, "\$699 (no disc drive included)", 699.0, "Home console",
            "8-core AMD Zen 2, up to 3.85 GHz", "AMD RDNA, 60 CUs, with PSSR AI upscaling and faster ray tracing", 16.7, "16 GB GDDR6 + 2 GB DDR5", 18.0,
            "2 TB SSD", "4K at higher frame rates, with PSSR upscaling", "Roughly an RTX 3070 to RTX 4070",
            listOf("Astro Bot", "Marvel's Spider-Man 2", "Ghost of Yotei", "Gran Turismo 7", "Demon's Souls"),
            "It plays every PS5 game; \"Pro Enhanced\" games look sharper or run faster.", "Counted with the PS5: 84.2 million (Sony, Sep 2025)", null,
            "PlayStation Plus", "PS5 and most PS4 games", "a bigger GPU and AI upscaling (PSSR) for sharper 4K and better ray tracing"),
        Console("ps4", "PlayStation 4", "PS4", "PlayStation 4", SIE, "Sony Group Corporation", SONY_NOTE,
            "November 2013", 2013, "\$399", 399.0, "Home console",
            "8-core AMD Jaguar, 1.6 GHz", "AMD GCN, 18 CUs at 800 MHz", 1.84, "8 GB GDDR5", 8.0,
            "500 GB hard drive (1 TB later)", "1080p, mostly 30 fps", "Roughly a GTX 750 Ti to GTX 1050",
            listOf("The Last of Us Part II", "God of War (2018)", "Marvel's Spider-Man", "Bloodborne", "Horizon Zero Dawn", "Uncharted 4", "Ghost of Tsushima"),
            SONY_GAMES, "Over 117 million (Sony)", 117.2, "PlayStation Plus", "No PS3 discs (some via streaming)",
            "Sony's biggest comeback: a huge library of acclaimed exclusives and the best-selling console of its generation"),
        Console("ps4pro", "PlayStation 4 Pro", "PS4 Pro", "PlayStation 4", SIE, "Sony Group Corporation", SONY_NOTE,
            "November 2016", 2016, "\$399", 399.0, "Home console",
            "8-core AMD Jaguar, 2.1 GHz", "AMD GCN, 36 CUs at 911 MHz", 4.2, "8 GB GDDR5 + 1 GB DDR3", 9.0,
            "1 TB hard drive", "Checkerboard 4K, steadier 1080p", "Roughly an RX 470",
            listOf("The Last of Us Part II", "God of War (2018)", "Marvel's Spider-Man", "Bloodborne", "Horizon Zero Dawn"),
            SONY_GAMES, "Counted with the PS4: over 117 million (Sony)", null, "PlayStation Plus", "Every PS4 game",
            "bringing 4K-ish output and HDR to the PS4 library"),
        Console("ps3", "PlayStation 3", "PS3", "PlayStation 3", SCE, "Sony Group Corporation", SONY_NOTE,
            "November 2006", 2006, "\$499 (20 GB) or \$599 (60 GB)", 499.0, "Home console",
            "IBM/Sony/Toshiba Cell, 3.2 GHz (1 PPE + 7 SPEs)", "Nvidia RSX, 550 MHz", 0.23, "256 MB XDR + 256 MB GDDR3", 0.5,
            "20 to 500 GB hard drive", "720p, some 1080p; Blu-ray player", null,
            listOf("The Last of Us", "Uncharted 2", "God of War III", "Metal Gear Solid 4", "LittleBigPlanet", "Demon's Souls"),
            null, "About 87.4 million", 87.4, "PlayStation Network", "Early models play PS2 discs",
            "its Blu-ray drive, the hard-to-program Cell chip, and a strong late run of exclusives"),
        Console("ps2", "PlayStation 2", "PS2", "PlayStation 2", SCE, "Sony Group Corporation", SONY_NOTE,
            "October 2000", 2000, "\$299", 299.0, "Home console",
            "MIPS Emotion Engine, 294.9 MHz", "Graphics Synthesizer, 147 MHz", null, "32 MB RDRAM", 0.032,
            "DVD discs, 8 MB memory cards", "480i; plays DVD movies", null,
            listOf("God of War", "Shadow of the Colossus", "Gran Turismo 3", "Jak and Daxter", "Ratchet & Clank", "Final Fantasy X"),
            null, "Over 160 million (Sony, 2024): the best-selling console ever", 160.0, null, "Most PS1 games",
            "being the best-selling console of all time, and a cheap DVD player in 2000"),
        Console("ps1", "PlayStation", "PS1", "PlayStation (console)", SCE, "Sony Group Corporation", SONY_NOTE,
            "September 1995 (Dec 1994 in Japan)", 1994, "\$299", 299.0, "Home console",
            "MIPS R3000A, 33.9 MHz", "Sony 3D GPU", null, "2 MB", 0.002,
            "CD-ROM discs, memory cards", "240p to 480i", null,
            listOf("Final Fantasy VII", "Metal Gear Solid", "Crash Bandicoot", "Spyro the Dragon", "Gran Turismo", "Tekken 3"),
            null, "About 102.5 million", 102.49, null, null,
            "taking 3D games and CDs mainstream, and Sony's start in games"),
        Console("psp", "PlayStation Portable", "PSP", "PlayStation Portable", SCE, "Sony Group Corporation", SONY_NOTE,
            "March 2005 (Dec 2004 in Japan)", 2004, "\$249", 249.0, "Handheld",
            "MIPS R4000-based, up to 333 MHz", "Graphics core, 166 MHz", null, "32 MB (64 MB on later models)", 0.032,
            "UMD discs, Memory Stick", "480x272 widescreen", null,
            listOf("God of War: Chains of Olympus", "Crisis Core: Final Fantasy VII", "Monster Hunter Freedom Unite", "Patapon", "LocoRoco"),
            null, "Roughly 80 million (estimate)", 80.0, null, null, "near-PS2 graphics in a handheld, and UMD movies"),
        Console("vita", "PlayStation Vita", "Vita", "PlayStation Vita", SIE, "Sony Group Corporation", SONY_NOTE,
            "February 2012 (Dec 2011 in Japan)", 2011, "\$249 (Wi-Fi)", 249.0, "Handheld",
            "4-core Arm Cortex-A9", "PowerVR SGX543MP4+", null, "512 MB + 128 MB video", 0.64,
            "Vita cards, memory cards", "960x544 OLED touchscreen", null,
            listOf("Gravity Rush", "Tearaway", "Uncharted: Golden Abyss", "Persona 4 Golden"),
            null, "Roughly 15 million (estimate)", 15.0, "PlayStation Plus", "Some PSP games",
            "a gorgeous OLED screen, a rear touch pad, and indie and Japanese games"),
        Console("seriesx", "Xbox Series X", "Series X", "Xbox Series X and Series S", XBOX, "Microsoft Corporation", XBOX_NOTE,
            "November 2020", 2020, "\$499", 499.0, "Home console",
            "8-core AMD Zen 2, 3.8 GHz", "AMD RDNA 2, 52 CUs at 1.825 GHz", 12.15, "16 GB GDDR6", 16.0,
            "1 TB SSD", "4K up to 120 fps; 8K capable", "Roughly an RTX 2080",
            listOf("Halo Infinite", "Forza Horizon 5", "Starfield", "Microsoft Flight Simulator 2024", "Avowed", "Gears of War: Reloaded"),
            XBOX_GAMES, "Microsoft doesn't say; estimates put the Series X and S near 30 million together", 30.0, "Xbox Game Pass", "Most Xbox One, 360 and original Xbox games on its list",
            "being the most powerful console at launch, Quick Resume, and Game Pass"),
        Console("seriess", "Xbox Series S", "Series S", "Xbox Series X and Series S", XBOX, "Microsoft Corporation", XBOX_NOTE,
            "November 2020", 2020, "\$299", 299.0, "Home console (digital only)",
            "8-core AMD Zen 2, 3.6 GHz", "AMD RDNA 2, 20 CUs at 1.565 GHz", 4.0, "10 GB GDDR6", 10.0,
            "512 GB SSD (1 TB model later)", "1440p up to 120 fps", "Roughly a GTX 1660",
            listOf("Halo Infinite", "Forza Horizon 5", "Starfield", "Microsoft Flight Simulator 2024", "Avowed"),
            XBOX_GAMES, "Microsoft doesn't say; estimates put the Series X and S near 30 million together", 30.0, "Xbox Game Pass", "Most Xbox One, 360 and original Xbox games on its list",
            "being the cheapest way into current-gen games, small and quiet, but with no disc drive"),
        Console("xboxone", "Xbox One", "Xbox One", "Xbox One", XBOX, "Microsoft Corporation", XBOX_NOTE,
            "November 2013", 2013, "\$499 (with Kinect)", 499.0, "Home console",
            "8-core AMD Jaguar, 1.75 GHz", "AMD GCN, 12 CUs at 853 MHz", 1.31, "8 GB DDR3 + 32 MB ESRAM", 8.0,
            "500 GB hard drive", "900p to 1080p", "Roughly a GTX 750",
            listOf("Halo 5: Guardians", "Forza Horizon 4", "Gears 5", "Sea of Thieves", "Ori and the Blind Forest"),
            XBOX_GAMES, "Microsoft doesn't say; estimates near 58 million", 58.0, "Xbox Live Gold, later Game Pass", "Some 360 and original Xbox games",
            "a rocky TV-focused launch, then backward compatibility and the start of Game Pass"),
        Console("onex", "Xbox One X", "One X", "Xbox One", XBOX, "Microsoft Corporation", XBOX_NOTE,
            "November 2017", 2017, "\$499", 499.0, "Home console",
            "8-core AMD Jaguar, 2.3 GHz", "AMD GCN, 40 CUs at 1.172 GHz", 6.0, "12 GB GDDR5", 12.0,
            "1 TB hard drive", "Native 4K in many games", "Roughly an RX 580",
            listOf("Halo 5: Guardians", "Forza Horizon 4", "Gears 5", "Sea of Thieves"),
            XBOX_GAMES, "Counted with the Xbox One", null, "Xbox Live Gold, later Game Pass", "Every Xbox One game",
            "being the strongest console of its generation"),
        Console("x360", "Xbox 360", "360", "Xbox 360", XBOX, "Microsoft Corporation", XBOX_NOTE,
            "November 2005", 2005, "\$299 (Core) or \$399", 299.0, "Home console",
            "3-core IBM Xenon, 3.2 GHz", "ATI Xenos, 500 MHz", 0.24, "512 MB GDDR3", 0.5,
            "Optional hard drive, 20 to 500 GB", "720p, some 1080p", null,
            listOf("Halo 3", "Gears of War", "Halo: Reach", "Fable II", "Forza Motorsport 3"),
            null, "About 84 million (estimate)", 84.0, "Xbox Live", "Some original Xbox games",
            "Xbox Live, achievements, and the \"red ring of death\""),
        Console("xbox", "Xbox (original)", "Xbox", "Xbox (console)", XBOX, "Microsoft Corporation", XBOX_NOTE,
            "November 2001", 2001, "\$299", 299.0, "Home console",
            "Intel Pentium III-class, 733 MHz", "Nvidia NV2A, 233 MHz", null, "64 MB DDR", 0.064,
            "8 GB hard drive, DVD", "480i/480p, some 720p", null,
            listOf("Halo: Combat Evolved", "Fable", "Ninja Gaiden", "Project Gotham Racing", "Jade Empire"),
            null, "About 24 million", 24.0, "Xbox Live", null, "Halo, a built-in hard drive, and Microsoft's start in consoles"),
        Console("switch", "Nintendo Switch", "Switch", "Nintendo Switch", "Nintendo", null, NINTENDO_NOTE,
            "March 2017", 2017, "\$299.99 (OLED \$349.99, Lite \$199.99)", 299.99, "Hybrid: handheld and TV",
            "4-core Arm Cortex-A57 (Nvidia Tegra X1), 1.02 GHz", "Nvidia Maxwell, 256 CUDA cores, up to 768 MHz docked", 0.39, "4 GB LPDDR4", 4.0,
            "32 GB (64 GB on the OLED) + microSD", "720p handheld, up to 1080p docked", "Well below a PS4",
            listOf("The Legend of Zelda: Tears of the Kingdom", "Breath of the Wild", "Super Mario Odyssey", "Animal Crossing: New Horizons", "Mario Kart 8 Deluxe", "Super Smash Bros. Ultimate", "Pokemon Scarlet and Violet", "Metroid Dread"),
            "Nintendo's own games almost never leave Nintendo systems.", "154.01 million (Nintendo, Sep 2025)", 154.01, "Nintendo Switch Online", null,
            "playing the same games on the TV and on the go, and Nintendo's best-selling home console"),
        Console("switch2", "Nintendo Switch 2", "Switch 2", "Nintendo Switch 2", "Nintendo", null, NINTENDO_NOTE,
            "June 2025", 2025, "\$449.99 (\$499.99 with Mario Kart World)", 449.99, "Hybrid: handheld and TV",
            "8-core Arm Cortex-A78C (Nvidia T239)", "Nvidia Ampere, 1,536 CUDA cores, with DLSS and ray tracing", 3.07, "12 GB LPDDR5X", 12.0,
            "256 GB + microSD Express", "1080p 120 Hz handheld; up to 4K docked", "Roughly an RTX 2050 laptop chip, docked",
            listOf("Mario Kart World", "Donkey Kong Bananza", "Kirby Air Riders", "Hyrule Warriors: Age of Imprisonment", "Drag x Drive"),
            "Nintendo's own games almost never leave Nintendo systems.", "10.36 million (Nintendo, Sep 2025)", 10.36, "Nintendo Switch Online, with GameChat", "Most Switch games",
            "the fastest-selling Nintendo console, mouse-mode Joy-Con 2, and a big jump in power over the Switch"),
        Console("wii", "Wii", "Wii", "Wii", "Nintendo", null, NINTENDO_NOTE,
            "November 2006", 2006, "\$249.99", 249.99, "Home console",
            "IBM Broadway, 729 MHz", "ATI Hollywood, 243 MHz", null, "88 MB", 0.088,
            "512 MB flash + SD", "480p", null,
            listOf("Wii Sports", "Super Mario Galaxy", "Mario Kart Wii", "Super Smash Bros. Brawl", "Xenoblade Chronicles"),
            null, "101.63 million (Nintendo)", 101.63, null, "GameCube games (early models)", "motion controls and bringing families and grandparents to games"),
        Console("wiiu", "Wii U", "Wii U", "Wii U", "Nintendo", null, NINTENDO_NOTE,
            "November 2012", 2012, "\$299.99 (8 GB) or \$349.99 (32 GB)", 299.99, "Home console with a tablet controller",
            "3-core IBM Espresso, 1.24 GHz", "AMD Latte, 550 MHz", 0.176, "2 GB DDR3", 2.0,
            "8 or 32 GB + USB drives", "720p to 1080p", null,
            listOf("Mario Kart 8", "Splatoon", "Super Mario 3D World", "Bayonetta 2", "Xenoblade Chronicles X"),
            null, "13.56 million (Nintendo)", 13.56, null, "Wii games", "the GamePad tablet, weak sales, and games that later shone on the Switch"),
        Console("gamecube", "GameCube", "GameCube", "GameCube", "Nintendo", null, NINTENDO_NOTE,
            "November 2001", 2001, "\$199", 199.0, "Home console",
            "IBM Gekko, 486 MHz", "ATI Flipper, 162 MHz", null, "43 MB total", 0.043,
            "Mini-DVD discs, memory cards", "480p", null,
            listOf("Super Smash Bros. Melee", "Metroid Prime", "The Legend of Zelda: The Wind Waker", "Super Mario Sunshine", "Pikmin"),
            null, "21.74 million (Nintendo)", 21.74, null, null, "its carry handle, tiny discs, and Smash Bros. Melee"),
        Console("n64", "Nintendo 64", "N64", "Nintendo 64", "Nintendo", null, NINTENDO_NOTE,
            "September 1996", 1996, "\$199", 199.0, "Home console",
            "NEC VR4300, 93.75 MHz", "Reality Co-Processor, 62.5 MHz", null, "4 MB (8 MB with the Expansion Pak)", 0.004,
            "Cartridges", "240p to 480i", null,
            listOf("Super Mario 64", "The Legend of Zelda: Ocarina of Time", "GoldenEye 007", "Mario Kart 64", "Banjo-Kazooie", "Super Smash Bros."),
            null, "32.93 million (Nintendo)", 32.93, null, null, "the analog stick, four controller ports, and early 3D classics"),
        Console("snes", "Super Nintendo (SNES)", "SNES", "Super Nintendo Entertainment System", "Nintendo", null, NINTENDO_NOTE,
            "August 1991 (Nov 1990 in Japan)", 1990, "\$199", 199.0, "Home console",
            "Ricoh 5A22, 3.58 MHz", "S-PPU, with Mode 7 effects", null, "128 KB", 0.000128,
            "Cartridges", "256x224", null,
            listOf("Super Mario World", "The Legend of Zelda: A Link to the Past", "Super Metroid", "Donkey Kong Country", "Chrono Trigger", "Super Mario Kart"),
            null, "49.10 million (Nintendo)", 49.1, null, null, "the golden age of 2D games and RPGs"),
        Console("nes", "Nintendo Entertainment System (NES)", "NES", "Nintendo Entertainment System", "Nintendo", null, NINTENDO_NOTE,
            "October 1985 (July 1983 in Japan as the Famicom)", 1983, "\$179.99", 179.99, "Home console",
            "Ricoh 2A03, 1.79 MHz", "Ricoh PPU", null, "2 KB", 0.000002,
            "Cartridges", "256x240", null,
            listOf("Super Mario Bros.", "Super Mario Bros. 3", "The Legend of Zelda", "Metroid", "Mega Man 2", "Punch-Out!!"),
            null, "61.91 million (Nintendo)", 61.91, null, null, "reviving home games after the 1983 crash"),
        Console("gameboy", "Game Boy", "Game Boy", "Game Boy", "Nintendo", null, NINTENDO_NOTE,
            "July 1989", 1989, "\$89.99", 89.99, "Handheld",
            "Sharp LR35902, 4.19 MHz", "Built in, 4 shades of green", null, "8 KB", 0.000008,
            "Cartridges", "160x144", null,
            listOf("Tetris", "Pokemon Red and Blue", "Super Mario Land", "The Legend of Zelda: Link's Awakening", "Kirby's Dream Land"),
            null, "118.69 million with the Game Boy Color (Nintendo)", 118.69, null, null, "Tetris, Pokemon, and AA batteries that lasted for days"),
        Console("gba", "Game Boy Advance", "GBA", "Game Boy Advance", "Nintendo", null, NINTENDO_NOTE,
            "June 2001", 2001, "\$99.99", 99.99, "Handheld",
            "Arm7TDMI, 16.78 MHz", "Built-in 2D engine", null, "288 KB", 0.000288,
            "Cartridges", "240x160", null,
            listOf("Pokemon Ruby and Sapphire", "Metroid Fusion", "Advance Wars", "Fire Emblem", "Golden Sun", "Mario Kart: Super Circuit"),
            null, "81.51 million (Nintendo)", 81.51, null, "Game Boy and Game Boy Color games", "SNES-level games in your pocket"),
        Console("ds", "Nintendo DS", "DS", "Nintendo DS", "Nintendo", null, NINTENDO_NOTE,
            "November 2004", 2004, "\$149.99", 149.99, "Handheld",
            "Arm946E-S, 67 MHz + Arm7, 33 MHz", "Built-in 2D and 3D engine", null, "4 MB", 0.004,
            "Cartridges", "Two 256x192 screens, the bottom one a touchscreen", null,
            listOf("New Super Mario Bros.", "Nintendogs", "Pokemon Diamond and Pearl", "Mario Kart DS", "Brain Age"),
            null, "154.02 million (Nintendo)", 154.02, null, "Game Boy Advance games (most models)", "two screens, a stylus, and reaching people who'd never played games"),
        Console("3ds", "Nintendo 3DS", "3DS", "Nintendo 3DS", "Nintendo", null, NINTENDO_NOTE,
            "March 2011", 2011, "\$249.99", 249.99, "Handheld",
            "2-core Arm11, 268 MHz", "DMP PICA200", null, "128 MB", 0.128,
            "Cartridges + SD", "Glasses-free 3D top screen, 800x240", null,
            listOf("Pokemon X and Y", "Animal Crossing: New Leaf", "Fire Emblem Awakening", "Super Mario 3D Land", "Kid Icarus: Uprising"),
            null, "75.94 million (Nintendo)", 75.94, null, "DS games", "3D without glasses and StreetPass"),
        Console("steamdeck", "Steam Deck", "Steam Deck", "Steam Deck", "Valve Corporation", null,
            "Valve has no parent company: it's privately held, and Gabe Newell owns most of it.",
            "February 2022 (OLED model November 2023)", 2022, "\$399 at launch (OLED from \$549)", 399.0, "Handheld gaming PC",
            "4-core / 8-thread AMD Zen 2, up to 3.5 GHz", "AMD RDNA 2, 8 CUs at up to 1.6 GHz", 1.6, "16 GB LPDDR5", 16.0,
            "64 GB to 1 TB + microSD", "1280x800 at 60 Hz (90 Hz HDR OLED)", "Roughly a GTX 1050",
            emptyList(), "It has no exclusives: it plays your Steam library, thousands of games rated Verified or Playable.",
            "Valve doesn't say; estimates run to several million", null, "Steam", "Your whole Steam library, through Proton",
            "taking your Steam library on the go, SteamOS, and a repairable, hackable design"),
        Console("rogally", "ROG Ally", "ROG Ally", "ROG Ally", "ASUS, under its ROG (Republic of Gamers) brand", null,
            "ASUS has no parent company: it's an independent, publicly traded company in Taiwan. ROG is its gaming brand.",
            "June 2023", 2023, "\$699 (Z1 Extreme)", 699.0, "Handheld gaming PC (Windows 11)",
            "8-core AMD Zen 4 (Ryzen Z1 Extreme), up to 5.1 GHz", "AMD RDNA 3, 12 CUs at up to 2.7 GHz", 8.6, "16 GB LPDDR5", 16.0,
            "512 GB NVMe SSD + microSD", "1080p at 120 Hz", "Roughly a GTX 1650 in real games (8.6 TFLOPS counts RDNA 3's dual-issue math)",
            emptyList(), "It has no exclusives: it's a Windows PC, so it runs Steam, Xbox, Epic and other PC stores.",
            "ASUS doesn't say", null, "Xbox Game Pass for PC and others", "Windows PC games",
            "running full Windows and every PC store in a handheld"),
        Console("genesis", "Sega Genesis (Mega Drive)", "Genesis", "Sega Genesis", "Sega", "Sega Sammy Holdings (since 2004)",
            "Sega made it on its own; since 2004 Sega has been part of Sega Sammy Holdings in Tokyo.",
            "August 1989 (Oct 1988 in Japan)", 1988, "\$189.99", 189.99, "Home console",
            "Motorola 68000, 7.6 MHz", "Yamaha YM7101 VDP", null, "64 KB", 0.000064,
            "Cartridges", "320x224", null,
            listOf("Sonic the Hedgehog", "Sonic the Hedgehog 2", "Streets of Rage 2", "Gunstar Heroes", "Phantasy Star IV"),
            null, "About 30.75 million", 30.75, null, null, "Sonic and the \"Genesis does what Nintendon't\" console war"),
        Console("dreamcast", "Sega Dreamcast", "Dreamcast", "Dreamcast", "Sega", "Sega Sammy Holdings (since 2004)",
            "Sega made it on its own; since 2004 Sega has been part of Sega Sammy Holdings in Tokyo.",
            "September 1999 (Nov 1998 in Japan)", 1998, "\$199", 199.0, "Home console",
            "Hitachi SH-4, 200 MHz", "NEC PowerVR2, 100 MHz", null, "16 MB", 0.016,
            "GD-ROM discs, VMU memory cards", "480p over VGA", null,
            listOf("Shenmue", "Jet Set Radio", "Soulcalibur", "Sonic Adventure", "Crazy Taxi", "Skies of Arcadia"),
            null, "About 9.13 million", 9.13, "SegaNet", null, "a built-in modem for online play, and Sega's last console"),
        Console("atari2600", "Atari 2600", "2600", "Atari 2600", "Atari, Inc.", "Warner Communications (from 1976)",
            "Atari was owned by Warner Communications while it sold the 2600; today's Atari is a different company.",
            "September 1977", 1977, "\$199", 199.0, "Home console",
            "MOS 6507, 1.19 MHz", "TIA chip", null, "128 bytes", 0.000000128,
            "Cartridges", "160x192", null,
            listOf("Pitfall!", "Adventure", "Space Invaders", "Missile Command", "Combat"),
            null, "About 30 million", 30.0, null, null, "popularizing cartridge games at home"),
    )

    // ---------------------------------------------------------------- operating systems

    private const val MS = "Microsoft"
    private const val MS_NOTE = "Microsoft has no parent company: it's an independent, publicly traded company in Redmond, Washington."
    private const val APPLE_NOTE = "Apple has no parent company: it's an independent, publicly traded company in Cupertino, California."
    private const val COMMUNITY = "It has no parent company: a volunteer community builds it."
    private const val WIN_KERNEL = "Windows NT kernel (hybrid), version 10.0"
    private const val WIN_GAMING = "Windows is the main PC gaming OS: nearly every PC game and anti-cheat runs on it, plus Xbox Game Pass for PC."
    private const val LINUX_GAMING = "Most Steam games run on Linux through Proton, but some online games with kernel anti-cheat (like Fortnite, Valorant and Call of Duty) don't."
    private const val LINUX_KERNEL = "Linux kernel (monolithic, with loadable modules)"

    val OSES: List<Os> = listOf(
        Os("windows", "Windows", "Microsoft Windows", MS, null, MS_NOTE, 1985,
            "PCs and laptops (x86 and Arm), tablets", WIN_KERNEL, "Windows 11 is current; Windows 10 support ended in October 2025", null, null,
            OsType.ALL, "it runs on most PCs, for everything from games to office work", "running almost every PC program and game, Microsoft Office, and wide hardware support",
            WIN_GAMING, "Over 1.4 billion monthly active devices (Microsoft); about 70% of desktops (StatCounter)", 1400.0, "desktop", 70.0,
            "Comes with most PCs; Windows 11 Home is \$139 and Pro \$199.99 on their own", "Proprietary", listOf(10, 10, 8, 5)),
        Os("windows11", "Windows 11", "Windows 11", MS, null, MS_NOTE, 2021,
            "PCs and laptops (x86 and Arm) with TPM 2.0", WIN_KERNEL, "Yearly feature updates (24H2, 25H2...)", null, null,
            OsType.ALL, "it does games, work and school on most PCs", "its centered taskbar, Snap layouts, Copilot, and strict hardware requirements (TPM 2.0)",
            WIN_GAMING + " It adds Auto HDR and DirectStorage.", "Most Windows PCs since mid-2025, passing Windows 10 (StatCounter)", null, "desktop", null,
            "Free upgrade from Windows 10 on supported PCs; \$139 Home, \$199.99 Pro", "Proprietary", listOf(10, 10, 8, 5)),
        Os("windows10", "Windows 10", "Windows 10", MS, null, MS_NOTE, 2015,
            "PCs and laptops (x86, some Arm)", WIN_KERNEL, "22H2 is the last version; free support ended October 14, 2025 (paid Extended Security Updates run longer)", null, null,
            OsType.ALL, "it does games, work and school on most PCs", "being Windows for a decade, the return of the Start menu, and running on older PCs",
            WIN_GAMING, "Hundreds of millions of PCs still; it fell behind Windows 11 in mid-2025 (StatCounter)", null, "desktop", null,
            "No longer sold; upgrade to Windows 11 for free on supported PCs", "Proprietary", listOf(10, 10, 8, 5)),
        Os("macos", "macOS", "macOS", "Apple", null, APPLE_NOTE, 2001,
            "Apple's Mac computers only (Apple silicon; older Intel Macs)", "XNU (hybrid kernel) in Darwin, Apple's Unix base",
            "A new version every fall; since 2025 numbered by year (macOS 26 Tahoe)", null, null,
            OsType.WORK, "it's built for creative and professional work, and it's easy for everyday use too", "polish, working smoothly with iPhone, creative apps like Final Cut Pro and Logic, and being a certified Unix",
            "Fewer games than Windows: a growing list of native Apple silicon games, plus translators like CrossOver and Apple's Game Porting Toolkit. Many big online games don't run.",
            "Over 100 million active Macs (Apple, 2018), roughly 15% of desktops", 100.0, "desktop", 15.0,
            "Free with every Mac; it only runs on Apple hardware", "Proprietary, on an open-source Darwin core", listOf(5, 8, 9, 3)),
        Os("linux", "Linux", "Linux", "Linus Torvalds and thousands of contributors, with the Linux Foundation backing the work", null,
            "Linux has no parent company: it's a community project led by Linus Torvalds, with the nonprofit Linux Foundation paying for key work and companies like Intel, Google and Red Hat contributing.", 1991,
            "Nearly everything: PCs, servers, phones (Android), supercomputers, routers, cars and TVs", LINUX_KERNEL, null, null,
            "Linux itself is the kernel; distributions like Ubuntu, Fedora, Debian, Mint and Arch package it with everything else into a full OS.",
            OsType.ALL, "it runs everything from servers and supercomputers to desktops and phones", "being free and open source, running most of the internet's servers and every top-500 supercomputer, and endless customizing",
            LINUX_GAMING, "About 4% of desktops (StatCounter), but most servers and cloud machines, and Android's 3 billion devices", null, "desktop", 4.0,
            "Free", "Free and open source (GPLv2)", listOf(7, 6, 5, 10)),
        Os("ubuntu", "Ubuntu", "Ubuntu", "Canonical", null, "Canonical has no parent company: it's a private company founded by Mark Shuttleworth.", 2004,
            "PCs, servers, clouds, Raspberry Pi and IoT", LINUX_KERNEL, "A release every six months; long-term support (LTS) every two years in April", "Based on Debian", null,
            OsType.ALL, "it's a friendly desktop and the most common Linux on servers and clouds", "being the most popular Linux distribution, long-term support releases, and Snap packages",
            LINUX_GAMING, "The most-used Linux distribution; Canonical doesn't publish a count", null, null, null,
            "Free", "Free and open source", listOf(7, 7, 7, 8)),
        Os("fedora", "Fedora Linux", "Fedora Linux", "The Fedora Project, sponsored by Red Hat", "Red Hat, owned by IBM",
            "Fedora is a community project sponsored by Red Hat, which IBM has owned since 2019.", 2003,
            "PCs, servers, clouds; x86 and Arm", LINUX_KERNEL, "A release about every six months", "Its own; upstream of Red Hat Enterprise Linux", "Fedora is a Linux distribution of its own, not based on another; Red Hat Enterprise Linux is built from it.",
            OsType.ALL, "it's a polished, up-to-date desktop that developers like", "shipping new tech first (like Wayland and PipeWire), a clean GNOME desktop, and its ties to Red Hat",
            LINUX_GAMING, "No official count; a popular developer desktop", null, null, null,
            "Free", "Free and open source", listOf(7, 6, 6, 8)),
        Os("debian", "Debian", "Debian", "The Debian Project, a volunteer community", null,
            "Debian has no parent company: volunteers build it, and the nonprofit Software in the Public Interest holds its assets.", 1993,
            "PCs, servers, and many chip types (x86, Arm, RISC-V and more)", LINUX_KERNEL, "A stable release about every two years", "Its own; the base for Ubuntu, Mint, Kali and more", "Debian is one of the oldest Linux distributions and isn't based on another; Ubuntu, Linux Mint and Kali are built on it.",
            OsType.ALL, "it's a rock-solid base for servers and desktops", "stability, being run by volunteers, and being the base for many other distributions",
            LINUX_GAMING + " Debian's packages are older, so newer hardware can need extra setup.", "No official count; very common on servers", null, null, null,
            "Free", "Free and open source", listOf(6, 6, 5, 9)),
        Os("mint", "Linux Mint", "Linux Mint", "The Linux Mint team, led by Clement Lefebvre", null, COMMUNITY + " It's funded by donations.", 2006,
            "PCs and laptops (x86)", LINUX_KERNEL, "Follows Ubuntu's LTS releases", "Based on Ubuntu (and Debian, in LMDE)", null,
            OsType.ALL, "it's an easy everyday desktop", "feeling familiar to Windows users with its Cinnamon desktop, and being beginner friendly",
            LINUX_GAMING, "No official count; one of the most popular desktop Linux distributions", null, null, null,
            "Free", "Free and open source", listOf(7, 7, 8, 8)),
        Os("arch", "Arch Linux", "Arch Linux", "The Arch Linux community", null, COMMUNITY, 2002,
            "PCs (x86-64)", LINUX_KERNEL, "Rolling release: always the newest packages, no version numbers", "Its own; the base for SteamOS, Manjaro and EndeavourOS", "Arch is its own Linux distribution, not based on another; SteamOS and Manjaro are built on it.",
            OsType.ALL, "you build it into whatever you want, desktop or server", "a do-it-yourself install, rolling updates, the AUR package collection, and the Arch Wiki",
            LINUX_GAMING + " Its fresh kernels and drivers help with new hardware.", "No official count; popular with enthusiasts (and SteamOS is built on it)", null, null, null,
            "Free", "Free and open source", listOf(7, 7, 2, 10)),
        Os("popos", "Pop!_OS", "Pop!_OS", "System76", null, "System76 has no parent company: it's a private PC maker in Denver.", 2017,
            "PCs and laptops (x86), especially System76's", LINUX_KERNEL, "Follows Ubuntu's LTS releases", "Based on Ubuntu", null,
            OsType.ALL, "it's a desktop for developers, creators and gamers", "its COSMIC desktop, tiling windows, and an Nvidia-ready install",
            LINUX_GAMING + " It ships an Nvidia driver image, which makes setup easy.", "No official count", null, null, null,
            "Free", "Free and open source", listOf(8, 6, 7, 8)),
        Os("steamos", "SteamOS", "SteamOS", "Valve Corporation", null, "Valve has no parent company: it's privately held, and Gabe Newell owns most of it.", 2013,
            "Steam Deck, Lenovo Legion Go S and other handhelds", LINUX_KERNEL, "SteamOS 3, updated through Steam", "Based on Arch Linux (SteamOS 1 and 2 were Debian-based)", null,
            OsType.GAMING, "it boots straight into Steam's game mode, with a KDE Plasma desktop on the side", "running Windows games on Linux through Proton, and the Steam Deck",
            "Built for gaming: Steam's game mode, Proton for Windows games, and per-game performance tweaks. Kernel anti-cheat games (Fortnite, Valorant) won't run.",
            "Every Steam Deck; SteamOS makes up a big share of Linux on Steam's hardware survey", null, null, null,
            "Free with the Steam Deck and other SteamOS handhelds", "Mostly open source, with Valve's Steam client on top", listOf(9, 4, 8, 6)),
        Os("bazzite", "Bazzite", "Bazzite", "The Universal Blue community", null, COMMUNITY, 2023,
            "PCs, handhelds (Steam Deck, ROG Ally, Legion Go) and HTPCs", LINUX_KERNEL, "Updates as whole images you can roll back", "Based on Fedora (Atomic)", null,
            OsType.GAMING, "it's set up for games out of the box, with a Steam game mode like SteamOS", "being a SteamOS-like gaming Linux for any PC or handheld, and atomic updates that are hard to break",
            "Built for gaming: Steam, Proton, drivers and handheld support come preinstalled. Kernel anti-cheat games still won't run.",
            "No official count; a fast-growing gaming distribution", null, null, null,
            "Free", "Free and open source", listOf(9, 6, 7, 7)),
        Os("chromeos", "ChromeOS", "ChromeOS", "Google", "Alphabet Inc.", "Google is a subsidiary of Alphabet Inc.", 2011,
            "Chromebooks, Chromeboxes and tablets", LINUX_KERNEL, "Updates every few weeks, automatically", "Built from Google's open ChromiumOS project (which uses Gentoo's tools)", "ChromeOS uses the Linux kernel but isn't a typical Linux distribution: it's built from Google's ChromiumOS project, with Android and Linux apps on the side.",
            OsType.WORK, "it's a simple, web-first OS for school and light work", "cheap Chromebooks in schools, automatic updates, security, and running mostly web apps",
            "Weak for games: web and Android games, cloud gaming like GeForce Now, and Steam only on a few higher-end Chromebooks (in testing).",
            "About 1 to 2% of desktops (StatCounter), but big in US schools", null, "desktop", 1.5,
            "Free with Chromebooks", "Proprietary, on the open-source ChromiumOS", listOf(3, 5, 10, 2)),
        Os("android", "Android", "Android (operating system)", "Google, with the Open Handset Alliance", "Alphabet Inc.", "Google, which makes Android, is a subsidiary of Alphabet Inc.", 2008,
            "Phones, tablets, TVs, watches (Wear OS), cars and more", "Linux kernel (Android Common Kernel; the version depends on the device)", "A new version yearly", null,
            "Android uses the Linux kernel but isn't a typical Linux distribution: Google's own system and apps sit on top.",
            OsType.MOBILE, "it's the phone OS for most of the world, on every price of phone", "running on most of the world's phones, from many makers, with deep customizing and the Play Store",
            "Huge mobile game library on Google Play, controller support, and emulators; PC games only through streaming.",
            "Over 3 billion active devices (Google, 2021); about 72% of phones (StatCounter)", 3000.0, "mobile", 72.0,
            "Free for phone makers; comes on the phone", "Open source core (AOSP), with Google's apps proprietary", listOf(8, 10, 8, 7)),
        Os("ios", "iOS", "iOS", "Apple", null, APPLE_NOTE, 2007,
            "iPhone only", "XNU (hybrid kernel) in Darwin, shared with macOS", "A new version every fall; since 2025 numbered by year (iOS 26)", null, null,
            OsType.MOBILE, "it runs everything on the iPhone, from apps and games to work", "simplicity, privacy, long update support, the App Store, and working smoothly with Mac and Apple Watch",
            "A big, high-quality mobile library, Apple Arcade, controller support, and some console ports.",
            "Over a billion active iPhones (Apple, 2021); about 28% of phones (StatCounter)", 1000.0, "mobile", 28.0,
            "Free with every iPhone", "Proprietary", listOf(9, 10, 9, 3)),
        Os("ipados", "iPadOS", "iPadOS", "Apple", null, APPLE_NOTE, 2019,
            "iPad only", "XNU (hybrid kernel) in Darwin", "Yearly, alongside iOS", null, null,
            OsType.WORK, "it turns the iPad into a tablet for drawing, notes and light work", "Apple Pencil apps, multitasking, and tablet apps",
            "The iPhone's game library on a bigger screen, plus Apple Arcade.",
            "Apple doesn't split it out", null, null, null, "Free with every iPad", "Proprietary", listOf(8, 9, 9, 3)),
        Os("harmonyos", "HarmonyOS", "HarmonyOS", "Huawei", null, "Huawei has no parent company: it's privately held, owned by its employees through a union.", 2019,
            "Huawei phones, tablets, watches, TVs and cars", "HarmonyOS NEXT uses Huawei's own HongMeng microkernel (earlier versions used Linux)", null, null,
            "HarmonyOS isn't a Linux distribution: HarmonyOS NEXT dropped Android apps and the Linux kernel for Huawei's own.",
            OsType.MOBILE, "it runs Huawei's phones and devices, mostly in China", "Huawei's answer to US sanctions, and linking all its devices together",
            "Mobile games from Huawei's AppGallery.", "Over 1 billion devices across all versions (Huawei)", null, "mobile", null,
            "Free on Huawei devices", "Proprietary, on the open-source OpenHarmony", listOf(6, 6, 8, 4)),
        Os("kali", "Kali Linux", "Kali Linux", "OffSec (Offensive Security)", null, "OffSec is a private security-training company.", 2013,
            "PCs, VMs, Raspberry Pi, phones (NetHunter)", LINUX_KERNEL, "Rolling, with quarterly snapshots", "Based on Debian", null,
            OsType.SECURITY, "it's built for penetration testing and security research, not everyday use", "hundreds of preinstalled security and hacking tools",
            "Not meant for gaming.", "No official count", null, null, null, "Free", "Free and open source", listOf(2, 5, 3, 9)),
        Os("rhel", "Red Hat Enterprise Linux", "Red Hat Enterprise Linux", "Red Hat", "IBM (since 2019)", "Red Hat has been owned by IBM since 2019.", 2002,
            "Servers, clouds, mainframes; x86, Arm, IBM Power and Z", LINUX_KERNEL, "A major release about every three years, each supported for 10 years", "Built from Fedora (through CentOS Stream)", null,
            OsType.SERVER, "it runs business servers with paid support", "enterprise support and certifications, and long life cycles",
            "Not meant for gaming.", "No official count; a top enterprise Linux", null, null, null,
            "Paid subscriptions (free for developers)", "Open source, sold as a subscription", listOf(2, 6, 5, 7)),
        Os("freebsd", "FreeBSD", "FreeBSD", "The FreeBSD Project, backed by the FreeBSD Foundation", null, "FreeBSD has no parent company: a community builds it, with the nonprofit FreeBSD Foundation funding it.", 1993,
            "Servers, network gear, PCs", "Its own FreeBSD kernel (monolithic), not Linux", "A major release about every two years", null,
            "FreeBSD isn't a Linux distribution: it's a BSD Unix with its own kernel. PlayStation's system software and parts of macOS borrow from it.",
            OsType.SERVER, "it's a fast, stable server and networking OS", "networking, ZFS, a permissive license, and being the base of the PS4 and PS5 system software",
            "Not meant for gaming.", "No official count; Netflix's video servers run it", null, null, null, "Free", "Free and open source (BSD license)", listOf(2, 4, 3, 9)),
        Os("elementary", "elementary OS", "Elementary OS", "elementary, Inc.", null, "elementary, Inc. is a small independent company.", 2011,
            "PCs and laptops (x86)", LINUX_KERNEL, "Follows Ubuntu's LTS releases", "Based on Ubuntu", null,
            OsType.ALL, "it's a clean, simple everyday desktop", "a Mac-like Pantheon desktop and pay-what-you-want apps",
            LINUX_GAMING, "No official count", null, null, null, "Pay what you want (free is OK)", "Free and open source", listOf(5, 5, 9, 5)),
    )

    private val ALL: List<Entry> = CONSOLES + OSES
    fun byId(id: String): Entry? = ALL.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- names

    /** [loose] names need a cue that games or computers are meant ("the switch" is also a light switch). */
    private data class Name(val id: String, val rx: Regex, val loose: Boolean = false)

    private fun n(id: String, rx: String, loose: Boolean = false) = Name(id, Regex("(?<![a-z0-9])(?:$rx)(?![a-z0-9])"), loose)

    private val NAMES = listOf(
        n("ps5pro", "(?:playstation|ps) ?5 ?pro"),
        n("ps5", "(?:sony )?(?:playstation|ps) ?5(?: slim| digital(?: edition)?)?s?|play ?station(?! (?:plus|network|store|portable|vita|[1-46]))|ps ?five"),
        n("ps4pro", "(?:playstation|ps) ?4 ?pro"),
        n("ps4", "(?:playstation|ps) ?4(?: slim)?s?|ps ?four"),
        n("ps3", "(?:playstation|ps) ?3s?"),
        n("ps2", "(?:playstation|ps) ?2s?"),
        n("ps1", "(?:playstation|ps) ?1|psx|ps one|original playstation"),
        n("psp", "psps?|playstation portable"),
        n("vita", "(?:playstation |ps )?vita"),
        n("seriesx", "xbox series x|series x|xsx|xbox(?! (?:one|360|series|game pass|live|app|controller|s\\b))s?"),
        n("seriess", "xbox series s|series s"),
        n("onex", "xbox one x|one x"),
        n("xboxone", "xbox one(?: s)?"),
        n("x360", "xbox 360s?|360(?= (?:console|vs|or|and))"),
        n("xbox", "original xbox|xbox classic|og xbox|first xbox"),
        n("switch2", "(?:nintendo )?switch ?2s?|switch two"),
        n("switch", "nintendo switch(?: oled| lite)?(?:es)?|switch (?:oled|lite)|nintendo switches"),
        n("switch", "(?<=(?:the|a|my|og|original|nintendo's|many|more|of|old) )switch(?:es)?(?! (?:to|on|off|over|from|between|it|the|my|lights?))", loose = true),
        n("wiiu", "wii ?u"),
        n("wii", "(?:nintendo )?wii(?! ?u)s?"),
        n("gamecube", "(?:nintendo )?game ?cubes?|\\bngc\\b"),
        n("n64", "n64|nintendo 64"),
        n("snes", "snes|super nintendo(?: entertainment system)?|super famicom"),
        n("nes", "nes|nintendo entertainment system|famicom"),
        n("gba", "gba|game ?boy advance"),
        n("gameboy", "(?:original )?game ?boy(?! advance)(?: color)?"),
        n("3ds", "(?:new )?(?:nintendo )?3ds(?: xl)?|2ds"),
        n("ds", "nintendo ds(?: lite|i)?|\\bds lite|\\bdsi"),
        n("ds", "(?<=(?:the|a|my|og|original|old) )ds(?: lite)?s?", loose = true),
        n("steamdeck","steam ?deck(?: oled| lcd)?s?"),
        n("rogally", "(?:asus )?rog ally(?: x)?|asus ally"),
        n("genesis", "sega genesis|mega ?drive|sega mega drive"),
        n("dreamcast", "(?:sega )?dreamcast"),
        n("atari2600", "atari(?: 2600| vcs)?"),

        n("windows11", "windows 11|win ?11|windows eleven"),
        n("windows10", "windows 10|win ?10|windows ten"),
        n("windows", "(?:microsoft )?windows(?: os)?"),
        n("macos", "mac ?os(?: x)?|os ?x|apple'?s (?:desktop |computer )?os"),
        n("macos", "macs?|macbooks?|imacs?", loose = true),
        n("ubuntu", "ubuntu(?: linux)?"),
        n("mint", "linux mint"),
        n("mint", "mint", loose = true),
        n("arch", "arch ?linux"),
        n("arch", "arch", loose = true),
        n("fedora", "fedora (?:linux|workstation)"),
        n("fedora", "fedora", loose = true),
        n("debian", "debian(?: linux)?"),
        n("popos", "pop!?_? ?os"),
        n("steamos", "steam ?os"),
        n("bazzite", "bazzite"),
        n("chromeos", "chrome ?os|chromium ?os"),
        n("android", "(?:google )?android(?! (?:auto|tv|phones? (?:vs|or)))(?! \\d+ )"),
        n("ios", "ios|iphone os"),
        n("ipados", "ipad ?os"),
        n("harmonyos", "harmony ?os(?: next)?"),
        n("kali", "kali linux"),
        n("kali", "kali", loose = true),
        n("rhel", "rhel|red hat(?: enterprise)?(?: linux)?"),
        n("freebsd", "freebsd|free bsd"),
        n("elementary", "elementary ?os"),
        n("linux", "linux|gnu/linux"),
    )

    private fun isConsole(id: String) = CONSOLES.any { it.id == id }

    private val CONSOLE_CUE = Regex("\\b(?:consoles?|nintendo|sony|games?|gaming|exclusives?|sold|sales|sell|units|powerful|power|specs?|tera ?flops?|tflops|gpu|cpu|ram|handheld|docked|launch(?:ed)?|released?|came out|owns?|parent|makes?|made|company|stronger|weaker)\\b")
    private val OS_CUE = Regex("\\b(?:os|operating systems?|kernel|distros?|distribution|linux|desktop|pc|laptops?|computers?|users|people use|gaming|games|productivity|based on|built on|open source|free|programming|coding|unix|game on|(?:hard|easy|simple|difficult) to (?:use|learn|install))\\b")
    private val WINDOW_THINGS = Regex("\\b(?:open|close|clean|wash|shut|roll(?:ing)? (?:up|down)|tint(?:ed)?|replace|fix|crack(?:ed)?|broken|car|house|home|room|bedroom|kitchen|bay|stained|glass|double|pane)\\s+(?:the |my |all |of |the car |the house )*windows\\b|\\bwindows (?:are|is) (?:open|closed|down|up|dirty|foggy)|\\bwindows (?:and|or) doors\\b")
    private val ACTION = Regex("^(?:please )?(?:turn|power|switch (?:on|off|to)|open|close|launch|start|play|connect|pair|cast|mirror|buy|order|sell|install|uninstall|update|upgrade|download|reset|restart|reboot|shut|find|track|remind|set|text|call|navigate|record|stream|screenshot|show me (?:deals|prices))\\b")
    private val HOW_TO = Regex("\\bhow (?:do|can|to|should|would) (?:i|you|we)\\b(?! (?:know|tell))|\\bhow to\\b|\\bwhere (?:can|do|to) (?:i )?(?:buy|get|find)\\b|\\b(?:deals?|on sale|in stock|restock|preorders?|pre-?orders?|discounts?|coupons?|for sale)\\b")
    private val GAMES_LIST = Regex("\\b(?:best|top|good|new|upcoming|free|cheap|coolest|fun|popular|recommended) (?:\\w+ ){0,2}games\\b|\\bgames? (?:on|for|like|to play)\\b|\\bwhat games\\b(?!.*exclusive)")
    private val COMPARE = Regex("\\b(?:vs\\.?|versus|or|compared? (?:to|with)|compare|comparison|difference|differences|better than|worse than|stronger than|weaker than|more powerful than|than|against)\\b")

    // Words that can sit around a question about a platform; anything else is likely a game or app title.
    private val FILLER = ("the a an is are was were be been it its it's does do did doing can could would should will i im i'm me my you your we us our of in on at to for from with about " +
        "and or vs versus than then compare compared comparison difference differences between which what whats what's who whos who's how when where why " +
        "tell know info information details detail overview explain describe description give show brick hey hi please quick quickly much many more most less " +
        "better best worse good bad great really actually like kind type sort any some has have had got get there that this these those one ones so if " +
        "company companies corporation made make makes making maker makers sell sells sold selling owns own owned owner owners parent behind created creates create " +
        "build built builds manufacture manufactures manufactured manufacturer manufacturers brand performance perform performs powerful power strong stronger strongest weaker " +
        "fast faster specs spec specifications hardware cpu gpu processor processors chip chips graphics ram memory vram storage ssd resolution fps frame frames rate 4k 8k " +
        "teraflops teraflop tflops tf horsepower exclusive exclusives exclusively known famous games game titles title units sales copies popular people users user use uses " +
        "used using run runs running market share base install installed launch launched release released releases came come comes out date year years old price cost costs msrp " +
        "retail original priced dollars free paid kernel kernels version versions latest newest current distro distribution distros based fork derived flavor platform " +
        "platforms devices device architecture arm x86 phone phones computer computers pc pcs laptop laptops desktop desktops os operating system systems gaming productivity work " +
        "office school programming coding development developers developer purpose general everyday daily all-purpose general-purpose all them they their console consoles " +
        "handheld handhelds docked home portable linux open source opensource apps app software programs program strengths weaknesses pros cons why good okay ok fine " +
        "worth suitable decent ideal meant designed number total how's whats much who's whose under hood inside behind world worldwide sold? ever generation gen " +
        "entertainment exactly approximately roughly around about over summary sum up facts fact rundown breakdown nintendo sony microsoft valve apple google sega asus been worldwide globally total hard easy difficult simple learn beginner beginners friendly video editing music art security hacking privacy students server servers design photo streaming game cheap cheaper cheapest expensive today now currently right").split(" ").toSet()

    // ---------------------------------------------------------------- wants

    private val WANTS: List<Pair<PlatformWant, Regex>> = listOf(
        PlatformWant.PARENT to Regex("\\bparent(?: company| corporation)?\\b|\\bwho owns\\b|\\bowned by\\b|\\bowner\\b|\\bwho(?:'s| is) behind\\b|\\bsubsidiary\\b|\\bwho (?:does|do) (?:it|they) belong\\b"),
        PlatformWant.MAKER to Regex("\\bwho (?:makes?|made|sells?|sold|created?|creates|manufactures?|builds?|built|develops?|developed|maintains?|designed|designs|is the (?:maker|developer|company))\\b|\\b(?:what|which) (?:company|companies|corporation|brand)\\b|\\bmade by\\b|\\bmakers?\\b|\\bmanufacturer\\b|\\bdeveloper of\\b|\\bcompany (?:behind|that)\\b|\\bwho(?:'s| is) the (?:maker|company)\\b"),
        PlatformWant.KERNEL to Regex("\\bkernels?\\b|\\b(?:latest|newest|current) (?:\\w+ )?version\\b|\\bwhat version\\b"),
        PlatformWant.DISTRO to Regex("\\bdistros?\\b|\\bdistribution\\b|\\bbased (?:on|off)\\b|\\bbuilt on\\b|\\bfork(?:ed)? (?:of|from)\\b|\\bderived from\\b|\\bflavou?r of\\b|\\bwhat is .* built from\\b"),
        PlatformWant.EXCLUSIVES to Regex("\\bexclusives?\\b|\\bexclusively\\b|\\bonly on\\b|\\bfirst.?party\\b|\\bsignature games?\\b|\\bfamous games\\b"),
        PlatformWant.SALES to Regex("\\bhow many\\b.*\\b(?:sold|sell|units?|bought|own|made|shipped|out there)\\b|\\bunits? sold\\b|\\bsales\\b|\\bsold\\b|\\bbest.?selling\\b|\\bhow many (?:\\w+ )?users\\b"),
        PlatformWant.USERS to Regex("\\bhow many (?:people|users|devices|computers|pcs|phones|persons)\\b|\\b(?:user|install) ?base\\b|\\bmarket share\\b|\\bhow (?:popular|common|widespread)\\b|\\b(?:number of|how many) users\\b|\\bwho uses\\b|\\bpeople use\\b|\\bpopular\\b"),
        PlatformWant.PRICE to Regex("\\bhow much (?:is|are|does|do|would|will|for)\\b(?!.*\\b(?:ram|memory|storage|vram|space|power)\\b)|\\bprices?d?\\b|\\bcosts?\\b|\\bcheap(?:er|est)?\\b|\\b(?:more |less )?expensive\\b|\\b(?:launch|release|original|retail|starting) price\\b|\\bmsrp\\b|\\blaunched (?:at|for)\\b|\\bhow much (?:did|was|were|does|is|do)\\b.*\\b(?:cost|at launch|when it came out|new|originally|launch)\\b|\\bcost at launch\\b|\\bis .* free\\b|\\bdoes .* cost\\b|\\bhow much (?:is|does) (?:\\w+ ){0,3}(?:os|linux|ubuntu|windows|macos|debian|fedora|steamos|chromeos)\\b"),
        PlatformWant.RELEASE to Regex("\\bwhen (?:did|was|were|is|does)\\b.*\\b(?:come out|came out|release|released|launch|launched|made|created|first|out)\\b|\\brelease date\\b|\\bcame out\\b|\\bhow old is\\b|\\bwhat year\\b|\\bwhen .* (?:out|released)\\b"),
        PlatformWant.PERFORMANCE to Regex("\\bhow (?:powerful|strong|fast|good (?:are|is) (?:the )?(?:specs|graphics|hardware))\\b|\\bperformance\\b|\\bspecs?\\b|\\bspecifications\\b|\\btera ?flops?\\b|\\btflops\\b|\\bgpu\\b|\\bcpu\\b|\\bprocessor\\b|\\bchips?\\b|\\bgraphics\\b|\\bram\\b|\\bvram\\b|\\bmemory\\b|\\bstorage\\b|\\bresolution\\b|\\bfps\\b|\\bframe ?rate\\b|\\bhorsepower\\b|\\bpower(?:ful)?\\b|\\bstronger\\b|\\bweaker\\b|\\bfaster\\b|\\bunder the hood\\b|\\bhardware\\b|\\b4k\\b"),
        PlatformWant.PLATFORM to Regex("\\bwhat (?:devices|hardware|platforms?|computers|phones|chips|cpus|machines)\\b|\\b(?:runs?|run|work|works) on\\b|\\bplatforms?\\b|\\barchitectures?\\b|\\b(?:on|support) arm\\b|\\bx86\\b|\\bwhich devices\\b|\\bwhat (?:is it|does it run) on\\b"),
        PlatformWant.KNOWN to Regex("\\bknown for\\b|\\bfamous for\\b|\\bgood at\\b|\\bbest at\\b|\\bstrengths?\\b|\\bpros\\b|\\bcons\\b|\\bwhy (?:do )?(?:people|use|would|should|is)\\b|\\bspecial about\\b|\\bstand out\\b"),
        PlatformWant.TYPE to Regex("\\b(?:good|great|best|better|bad|okay|ok|fine|worth it|suitable|decent|ideal|meant|designed|made|used|use) for (gaming|games|work|productivity|programming|coding|development|developers|school|students|office|everyday use|everyday|daily use|beginners|servers?|video editing|editing|music|art|security|hacking|privacy|old (?:pcs|computers|laptops))\\b|\\b(?:gaming|productivity|all.?purpose|general.?purpose|everyday|mobile|server) (?:os|operating system)\\b|\\bwhat (?:kind|type|sort) of (?:os|operating system)\\b|\\bcan (?:i|you) (?:game|play games)\\b|\\b(?:is it|is .*) (?:all.?purpose|general.?purpose|for gaming)\\b|\\b(?:hard|easy|difficult|simple) to (?:use|learn|install|set up)\\b|\\bbeginner.?friendly\\b|\\b(?:game|gaming) on\\b" +
            "|\\bfor (?:video editing|gaming|programming|coding|school|work|productivity|music|art|beginners|students|servers?|privacy|security)\\b|\\bwhat is .* (?:used|for)\\b|\\bwhat(?:'s| is) .* for\\??$"),
    )

    // ---------------------------------------------------------------- parsing

    private data class Hit(val id: String, val start: Int, val end: Int, val loose: Boolean)

    private fun hits(s: String): List<Hit> {
        val all = NAMES.flatMap { nm -> nm.rx.findAll(s).map { Hit(nm.id, it.range.first, it.range.last + 1, nm.loose) }.toList() }
        val kept = ArrayList<Hit>()
        for (h in all.sortedWith(compareBy<Hit>({ it.loose }, { -(it.end - it.start) }, { it.start }))) {
            if (kept.none { it.start < h.end && h.start < it.end }) kept += h
        }
        return kept.sortedBy { it.start }
    }

    private val PREFIX = Regex("^(?:(?:hey|hi|ok|okay|yo) )?(?:brick[, ]+)?(?:(?:can|could|would) you (?:please )?|please |i (?:want|need|wanna|would like) to know |i'?m curious |do you know |quick question:? )*")

    /** A question about a console or an OS, or two of them compared; null when it's about a game, a deal, or something else. */
    fun parse(raw: String): PlatformAsk? {
        var s = raw.lowercase(Locale.US).replace(Regex("[?!.,;:\"()]+"), " ").replace('’', '\'').replace(Regex("\\s+"), " ").trim()
        s = s.replace(PREFIX, "").trim()
        if (s.isEmpty() || ACTION.containsMatchIn(s) || HOW_TO.containsMatchIn(s)) return null
        if (WINDOW_THINGS.containsMatchIn(s)) return null
        var found = hits(s)
        if (found.isEmpty()) {
            // "What's the best-selling console ever", "the most powerful console".
            if (Regex("\\b(?:best|top|highest).?sell(?:ing|er)\\b.*\\bconsoles?\\b|\\bconsoles?\\b.*\\bsold (?:the )?most\\b|\\bmost sold consoles?\\b").containsMatchIn(s) && !s.contains("handheld"))
                return PlatformAsk(listOf("ps2"), PlatformWant.SALES, raw)
            if (Regex("\\b(?:most powerful|strongest|fastest) (?:video game |gaming |home )?console\\b").containsMatchIn(s))
                return PlatformAsk(listOf("ps5pro"), PlatformWant.PERFORMANCE, raw)
            return null
        }
        // Loose names count only with a cue, or next to a name that's surely a console or an OS.
        val strong = found.filter { !it.loose }
        found = found.filter { h ->
            if (!h.loose) return@filter true
            val console = isConsole(h.id)
            strong.any { isConsole(it.id) == console } || (if (console) CONSOLE_CUE else OS_CUE).containsMatchIn(s)
        }
        if (found.isEmpty()) return null
        val ids = found.map { it.id }.distinct()
        val consoles = ids.count { isConsole(it) }
        if (consoles in 1 until ids.size) return null
        val isConsoleAsk = consoles > 0
        if (ids.size > 2) return null
        if (ids.size == 2 && !COMPARE.containsMatchIn(s) && !Regex("\\band\\b").containsMatchIn(s)) return null

        // Game lists and game availability belong to the game finders.
        if (isConsoleAsk && (GAMES_LIST.containsMatchIn(s) || Regex("\\bgames\\b").containsMatchIn(s)) && !Regex("exclusive|known for|famous").containsMatchIn(s)) return null
        if (!isConsoleAsk && Regex("\\bgames (?:on|for)\\b|\\b(?:best|top|new|free|good) (?:\\w+ ){0,2}(?:games|apps)\\b").containsMatchIn(s)) return null
        if (Regex("\\b(?:best|top|good|which|what) (?:\\w+ )?(?:linux )?distros?\\b(?! (?:is|does))").containsMatchIn(s) && ids == listOf("linux")) return null

        // Whatever is left once the names and question words are gone: a leftover word is likely a game or app title.
        var rest = s
        for (h in found.sortedByDescending { it.start }) rest = rest.removeRange(h.start, h.end)
        val unknown = rest.split(' ').filter { it.isNotBlank() && it !in FILLER && !it.matches(Regex("\\d+|[a-z]")) && it.trimEnd('s') !in FILLER }
        if (unknown.isNotEmpty()) return null

        var want = WANTS.firstOrNull { (w, rx) -> rx.containsMatchIn(s) && fits(w, isConsoleAsk) }?.first ?: PlatformWant.OVERVIEW
        // "How many PS5s sold" and "how many people use Linux" are the same question for each kind.
        if (isConsoleAsk && want == PlatformWant.USERS) want = PlatformWant.SALES
        if (!isConsoleAsk && want == PlatformWant.SALES) want = PlatformWant.USERS
        if (isConsoleAsk && want == PlatformWant.KNOWN) want = PlatformWant.EXCLUSIVES
        if (!isConsoleAsk && want == PlatformWant.PERFORMANCE && Regex("\\bgaming|games\\b").containsMatchIn(s)) want = PlatformWant.TYPE
        val detail = when (want) {
            PlatformWant.TYPE -> Regex("\\b(gaming|games|game|hard|easy|difficult|beginner|work|productivity|programming|coding|development|developers|school|students|office|everyday|beginners|servers?|video editing|editing|music|art|security|hacking|privacy)\\b").find(s)?.groupValues?.get(1)
                ?.let { when (it) { "games", "game" -> "gaming"; "hard", "easy", "difficult", "beginner" -> "beginners"; "coding", "development", "developers" -> "programming"; "students" -> "school"; "office" -> "work"; "productivity" -> "work"; "editing" -> "video editing"; "hacking" -> "security"; "servers" -> "server"; else -> it } }
            else -> null
        }
        // "Is Linux good for gaming" with no other word is the type question; "linux gaming" too.
        if (want == PlatformWant.OVERVIEW && !isConsoleAsk && Regex("\\bgaming\\b").containsMatchIn(s)) return PlatformAsk(ids, PlatformWant.TYPE, raw, "gaming")
        return PlatformAsk(ids, want, raw, detail)
    }

    private fun fits(w: PlatformWant, console: Boolean) = when (w) {
        PlatformWant.PERFORMANCE, PlatformWant.EXCLUSIVES -> console
        PlatformWant.KERNEL, PlatformWant.DISTRO, PlatformWant.PLATFORM, PlatformWant.TYPE -> !console
        else -> true
    }

    // ---------------------------------------------------------------- answers

    private fun us(d: Double) = if (d >= 100 || d == Math.floor(d)) String.format(Locale.US, "%.0f", d) else if (d >= 10) String.format(Locale.US, "%.1f", d).removeSuffix(".0") else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
    private fun tf(c: Console) = c.tflops?.let { if (it < 1) String.format(Locale.US, "%.2f", it).trimEnd('0') else String.format(Locale.US, "%.1f", it).removeSuffix(".0") }
    private fun list(items: List<String>): String = when (items.size) { 0 -> ""; 1 -> items[0]; 2 -> "${items[0]} and ${items[1]}"; else -> items.dropLast(1).joinToString(", ") + " and " + items.last() }
    private fun makerShort(c: Console) = when { c.maker.startsWith("Sony") -> "Sony"; c.maker.startsWith("Microsoft") -> "Microsoft"; c.maker.startsWith("Valve") -> "Valve"; c.maker.startsWith("ASUS") -> "ASUS"; c.maker.startsWith("Atari") -> "Atari"; else -> c.maker }
    private fun makerShort(o: Os) = o.maker.substringBefore(",").substringBefore(" (").replaceFirst(Regex("^The "), "the ")
    private fun lowerFirst(s: String) = if (s.length > 1 && s[1].isLowerCase()) s[0].lowercaseChar() + s.substring(1) else s
    private fun an(word: String) = if (word.first().lowercaseChar() in "aeiou8" || Regex("^(?:11|18)(?!\\d)|^(?:Nvidia|NEC|NES|N64|RTX|RX|SNES|S-PPU|IBM)\\b").containsMatchIn(word)) "an" else "a"

    fun answer(c: Console, want: PlatformWant): String = when (want) {
        PlatformWant.MAKER -> "The ${c.name} is made and sold by ${c.maker}." + (c.parent?.let { " Its parent company is $it." } ?: "")
        PlatformWant.PARENT -> c.ownerNote
        PlatformWant.PERFORMANCE -> (if (c.id == "ps5pro") "The PS5 Pro is the most powerful console on sale. " else "") +
            "The ${c.short} has " + (tf(c)?.let { "${an(it)} $it-teraflop GPU and " } ?: "") + "${c.ram.split(" + ").first()} of memory." +
            (c.pc?.let { " On PC, that's ${lowerFirst(it)}." } ?: "") +
            " Its CPU is ${an(c.cpu)} ${c.cpu}, and its GPU is ${an(c.gpu)} ${c.gpu.removeSuffix(" GPU")}. It aims for ${lowerFirst(c.output)}."
        PlatformWant.EXCLUSIVES -> if (c.games.isEmpty()) c.gamesNote.orEmpty()
            else "The ${c.short} is known for ${list(c.games.take(5))}." + (c.gamesNote?.let { " $it" } ?: "")
        PlatformWant.SALES -> when {
            c.sold.startsWith("Counted") -> "The ${c.short} is ${lowerFirst(c.sold)}."
            Regex("^(?:\\d|About|Over|Roughly)").containsMatchIn(c.sold) -> "The ${c.short} has sold ${lowerFirst(c.sold)}."
            else -> "${c.sold}."
        }
        PlatformWant.PRICE -> when {
            c.now.isNotEmpty() -> "The ${c.short} is ${nowRange(c)} today at the ${c.nowFrom}. It launched at ${c.price.substringBefore(" (")}."
            LAST_KNOWN[c.id] != null -> "The ${c.short} lists at ${LAST_KNOWN[c.id]}. It launched at ${c.price.substringBefore(" (")}."
            c.id in STILL_SOLD -> "I couldn't get today's price for the ${c.short}; it launched at ${c.price}."
            else -> "The ${c.short} isn't sold new anymore. It launched at ${c.price}."
        }
        PlatformWant.RELEASE -> "The ${c.name} came out in ${c.released}."
        else -> "The ${c.name} is ${makerShort(c)}'s ${lowerFirst(c.form).substringBefore(" (").replace(Regex("^hybrid:.*"), "hybrid console")} from ${c.year}" +
            (tf(c)?.let { ", with ${an(it)} $it-teraflop GPU" } ?: "") + ". It's known for ${c.known}."
    }

    fun answer(o: Os, want: PlatformWant, detail: String? = null): String = when (want) {
        PlatformWant.MAKER -> "${o.name} is made by ${o.maker}." + (o.parent?.let { " Its parent company is $it." } ?: "")
        PlatformWant.PARENT -> o.ownerNote
        PlatformWant.PLATFORM -> "${o.name} runs on ${lowerFirst(o.platforms)}."
        PlatformWant.KERNEL -> when {
            o.liveKernel != null -> "${o.name} uses the Linux kernel; the latest stable version is ${o.liveKernel} (kernel.org)."
            o.kernel == LINUX_KERNEL -> "${o.name} uses the Linux kernel, a monolithic kernel with loadable modules."
            else -> "${o.name} uses the ${o.kernel.replaceFirst(Regex("^Its own "), "")}."
        } + (o.version?.let { " ${it.trimEnd('.')}." } ?: "")
        PlatformWant.DISTRO -> o.distroSay ?: o.distro?.let { "${o.name} is a Linux distribution, ${lowerFirst(it)}." }
            ?: "${o.name} isn't a Linux distribution; ${makerShort(o)} builds it as its own OS on the ${o.kernel.substringBefore(" (").let { if (it.contains("kernel")) it else "$it kernel" }}."
        PlatformWant.KNOWN -> "${o.name} is known for ${o.known}."
        PlatformWant.TYPE -> when (detail) {
            "gaming" -> o.gaming
            null -> "${o.name} is ${an(o.type.label)} ${o.type.label.lowercase(Locale.US).replace("mobile, all-purpose", "all-purpose mobile")} OS: ${o.typeWhy}."
            else -> "${o.name} is ${an(o.type.label)} ${o.type.label.lowercase(Locale.US).replace("mobile, all-purpose", "all-purpose mobile")} OS: ${o.typeWhy}." + fitFor(o, detail)
        }
        PlatformWant.USERS -> "${o.name}: ${lowerFirst(o.users)}."
        PlatformWant.PRICE -> "${o.name}: ${lowerFirst(o.price)}."
        PlatformWant.RELEASE -> "${o.name} first came out in ${o.year}."
        else -> "${o.name}, from ${makerShort(o)}, is ${an(o.type.label)} ${o.type.label.lowercase(Locale.US).replace("mobile, all-purpose", "all-purpose mobile")} OS: ${o.typeWhy}. It's known for ${o.known}."
    }

    private fun fitFor(o: Os, detail: String): String {
        val linuxish = o.kernel == LINUX_KERNEL
        return when (detail) {
            "programming" -> when { o.id == "macos" || linuxish -> " It's a favorite with programmers."; o.id.startsWith("windows") -> " For programming, WSL adds a Linux system inside Windows."; else -> "" }
            "school" -> if (o.id == "chromeos") " It's the most common OS in US schools." else ""
            "beginners" -> if (o.scores[2] >= 8) " It's easy for beginners." else if (o.scores[2] <= 4) " It's not the easiest place to start." else ""
            "security" -> if (o.id == "kali") " It's the standard OS for security testing." else ""
            "server" -> if (linuxish || o.id == "freebsd") " It's a common server OS." else ""
            "video editing" -> when (o.id) { "macos" -> " Final Cut Pro and DaVinci Resolve run great on Apple silicon."; "windows", "windows11", "windows10" -> " Premiere Pro, DaVinci Resolve and most editors run on it."; else -> if (linuxish) " DaVinci Resolve runs on Linux, but Premiere Pro doesn't." else "" }
            else -> ""
        }
    }

    // ---------------------------------------------------------------- comparisons

    private fun lowNow(c: Console) = c.now.minOfOrNull { it.second }

    private fun soldShort(c: Console) = c.soldM?.let { (if (c.sold.contains("estimate", true) || c.sold.contains("doesn't say") || c.sold.startsWith("Roughly") || c.sold.startsWith("About")) "~" else "") + us(it) + "M" }

    fun versus(a: Console, b: Console): Versus {
        val bars = listOf(
            Versus.Bar("GPU POWER (TFLOPS)", a.tflops, b.tflops, tf(a)?.let { "$it TF" }, tf(b)?.let { "$it TF" }, "more GPU power"),
            Versus.Bar("MEMORY", a.ramGb, b.ramGb, a.ram.split(" ").take(2).joinToString(" "), b.ram.split(" ").take(2).joinToString(" "), "more memory"),
            Versus.Bar("UNITS SOLD", a.soldM, b.soldM, soldShort(a), soldShort(b), "more units sold"),
            Versus.Bar("PRICE TODAY (FROM)", lowNow(a), lowNow(b), lowNow(a)?.let { Prices.money(it) }, lowNow(b)?.let { Prices.money(it) }, "a lower price today", lower = true),
            Versus.Bar("LAUNCH PRICE (US)", a.priceUsd, b.priceUsd, a.priceUsd?.let { "$" + us(it) }, b.priceUsd?.let { "$" + us(it) },
                if (lowNow(a) != null && lowNow(b) != null) null else "a lower launch price", lower = true),
        ).filter { it.a != null || it.b != null }
        val words = listOf(
            Versus.Words("MAKER", makerShort(a) + (a.parent?.let { " ($it)" } ?: ""), makerShort(b) + (b.parent?.let { " ($it)" } ?: "")),
            Versus.Words("OUT", "${a.year}, ${a.form}", "${b.year}, ${b.form}"),
            Versus.Words("CPU", a.cpu, b.cpu),
            Versus.Words("GPU", a.gpu, b.gpu),
            Versus.Words("TARGET", a.output, b.output),
            Versus.Words("STORAGE", a.storage, b.storage),
            Versus.Words("KNOWN FOR", a.games.take(3).joinToString(", ").ifEmpty { "Its PC library" }, b.games.take(3).joinToString(", ").ifEmpty { "Its PC library" }),
            Versus.Words("ONLINE", a.online, b.online),
        )
        return Versus("${a.name} (${a.year})", "${b.name} (${b.year})", bars, words, Tech.verdict(a.short, b.short, bars))
    }

    private val SCORE_LABELS = listOf("GAMING" to "better gaming", "APPS" to "more apps", "EASE OF USE" to "easier to use", "CUSTOMIZING" to "more customizing")

    fun versus(a: Os, b: Os): Versus {
        val bars = ArrayList<Versus.Bar>()
        SCORE_LABELS.forEachIndexed { i, (label, tag) -> bars += Versus.Bar("$label (/10)", a.scores[i].toDouble(), b.scores[i].toDouble(), "${a.scores[i]}/10", "${b.scores[i]}/10", tag) }
        if (a.usersM != null && b.usersM != null) bars += Versus.Bar("USERS", a.usersM, b.usersM, usersShort(a.usersM), usersShort(b.usersM), "more users")
        if (a.market != null && a.market == b.market && a.share != null && b.share != null)
            bars += Versus.Bar("${a.market.uppercase(Locale.US)} SHARE", a.share, b.share, us(a.share) + "%", us(b.share) + "%", if (a.usersM != null && b.usersM != null) null else "more market share")
        val words = listOf(
            Versus.Words("MAKER", makerShort(a) + (a.parent?.let { " ($it)" } ?: ""), makerShort(b) + (b.parent?.let { " ($it)" } ?: "")),
            Versus.Words("TYPE", a.type.label, b.type.label),
            Versus.Words("KERNEL", a.liveKernel?.let { "Linux $it" } ?: a.kernel, b.liveKernel?.let { "Linux $it" } ?: b.kernel),
            Versus.Words("BASE", a.distro ?: "Its own", b.distro ?: "Its own"),
            Versus.Words("RUNS ON", a.platforms, b.platforms),
            Versus.Words("PRICE", a.price, b.price),
            Versus.Words("KNOWN FOR", a.known.replaceFirstChar { it.uppercase() }, b.known.replaceFirstChar { it.uppercase() }),
        )
        val verdict = Tech.verdict(a.name, b.name, bars).replace("The ${a.name}", a.name).replace("the ${a.name}", a.name).replace("The ${b.name}", b.name).replace("the ${b.name}", b.name)
        return Versus("${a.name} (${a.year})", "${b.name} (${b.year})", bars, words, verdict)
    }

    private fun usersShort(m: Double) = if (m >= 1000) us(m / 1000) + "B+" else us(m) + "M+"

    /** The spoken line for a comparison: the part asked about first, then the overall verdict. */
    fun compareAnswer(a: Entry, b: Entry, want: PlatformWant, detail: String?, v: Versus): String {
        if (a is Console && b is Console) {
            fun more(x: Double?, y: Double?, what: String, unit: (Double) -> String, lower: Boolean = false): String? {
                if (x == null || y == null) return null
                if (x == y) return "They're even on $what: ${unit(x)} each."
                val (w, l, wv, lv) = if ((x > y) != lower) listOf(a.short, b.short, unit(x), unit(y)) else listOf(b.short, a.short, unit(y), unit(x))
                return "The $w has $what: $wv versus $lv for the $l."
            }
            return when (want) {
                PlatformWant.PERFORMANCE -> more(a.tflops, b.tflops, "the stronger GPU", { "${tf(if (it == a.tflops) a else b)} teraflops" })
                    ?.plus(" Teraflops only roughly compare across chip makers and generations.")
                    ?: "The ${a.short} has ${an(a.gpu)} ${a.gpu} GPU; the ${b.short} has ${an(b.gpu)} ${b.gpu} GPU."
                PlatformWant.SALES -> more(a.soldM, b.soldM, "sold more", { soldShort(if (it == a.soldM) a else b)!!.replace("M", " million") })?.replace("has sold more", "sold more")
                    ?: "${answer(a, PlatformWant.SALES)} ${answer(b, PlatformWant.SALES)}"
                PlatformWant.PRICE -> (if (lowNow(a) != null && lowNow(b) != null) more(lowNow(a), lowNow(b), "the lower price today", { Prices.money(it) }, lower = true)?.replace("has the lower price today:", "is cheaper today:")
                    else null) ?: more(a.priceUsd, b.priceUsd, "the lower launch price", { "$" + us(it) }, lower = true) ?: "${answer(a, want)} ${answer(b, want)}"
                PlatformWant.MAKER, PlatformWant.PARENT -> "${answer(a, want)} ${answer(b, want)}"
                PlatformWant.EXCLUSIVES -> "The ${a.short} has ${list(a.games.take(3)).ifEmpty { "no exclusives" }}; the ${b.short} has ${list(b.games.take(3)).ifEmpty { "no exclusives" }}."
                PlatformWant.RELEASE -> "The ${a.short} came out in ${a.year}; the ${b.short} in ${b.year}."
                else -> v.verdict
            }
        }
        if (a is Os && b is Os) {
            return when (want) {
                PlatformWant.TYPE -> if (detail == "gaming") {
                    val (w, l) = if (a.scores[0] >= b.scores[0]) a to b else b to a
                    if (a.scores[0] == b.scores[0]) "They're about even for gaming. ${a.gaming}" else "${w.name} is better for gaming. ${w.gaming}"
                } else "${a.name} is ${an(a.type.label)} ${a.type.label.lowercase(Locale.US)} OS; ${b.name} is ${an(b.type.label)} ${b.type.label.lowercase(Locale.US)} OS. ${v.verdict}"
                PlatformWant.USERS -> "${answer(a, want)} ${answer(b, want)}"
                PlatformWant.KERNEL, PlatformWant.MAKER, PlatformWant.PARENT, PlatformWant.DISTRO, PlatformWant.PLATFORM, PlatformWant.KNOWN, PlatformWant.PRICE, PlatformWant.RELEASE ->
                    "${answer(a, want)} ${answer(b, want)}"
                else -> v.verdict
            }
        }
        return v.verdict
    }

    // ---------------------------------------------------------------- the card

    private fun wikiUrl(e: Entry) = "https://en.wikipedia.org/wiki/" + e.wiki.replace(' ', '_')

    /** The CONSOLE or OS card, or the VERSUS card for two. */
    suspend fun find(ask: PlatformAsk): LookupResult? = withContext(Dispatchers.IO) {
        val entries = ask.ids.mapNotNull { byId(it) }.map { e ->
            // Live facts from Wikipedia's infobox: sales for consoles, the latest release for an OS.
            val box = runCatching { infobox(e.wiki) }.getOrNull().orEmpty()
            when {
                e is Os && e.id == "linux" -> e.copy(liveKernel = runCatching { latestKernel() }.getOrNull())
                e is Os -> e.copy(latest = box["latest release"]?.let { latestOf(it) })
                e is Console -> (runCatching { nowPrices(e.id) }.getOrNull()?.let { (from, list) -> e.copy(now = list, nowFrom = from) } ?: e)
                    .copy(liveSold = (box["units sold"] ?: box["units shipped"])?.let { soldOf(it) })
                else -> e
            }
        }
        if (entries.isEmpty()) return@withContext null
        val image = runCatching { PcParts.pageImage(entries[0].wiki) }.getOrNull()
        if (entries.size == 2) {
            val (a, b) = entries
            val v = when { a is Console && b is Console -> versus(a, b); a is Os && b is Os -> versus(a, b); else -> return@withContext null }
            val note = if (a is Console) listOf("Teraflops only roughly compare across chip makers and generations.")
                else listOf("The /10 scores are Brick's rough take, not a benchmark.")
            return@withContext LookupResult(v.a, null, null, "", image, wikiUrl(a), emptyList(), compareAnswer(a, b, ask.want, ask.detail, v),
                sources = listOf("Brick's platform notes", "Wikipedia"), links = listOf(b.name to wikiUrl(b)), more = listOf("NOTE" to note), versus = v)
        }
        val e = entries[0]
        val askedVersion = Regex("\\b(?:version|release|update)s?\\b").containsMatchIn(ask.said.lowercase(Locale.US))
        val answer = when {
            e is Console && ask.want == PlatformWant.SALES && e.liveSold != null -> "The ${e.short} has sold ${e.liveSold.replace(")", ", per Wikipedia)")}."
            e is Os && ask.want == PlatformWant.KERNEL && askedVersion && e.latest != null -> "The latest ${e.name} release is ${e.latest} (Wikipedia)."
            e is Console -> answer(e, ask.want)
            e is Os -> answer(e, ask.want, ask.detail)
            else -> null
        }
        LookupResult(e.name, null, null, "", image, wikiUrl(e), emptyList(), answer,
            sources = listOfNotNull("Brick's platform notes", if (e is Os && e.liveKernel != null) "kernel.org" else null, "Wikipedia"),
            heading = if (e is Console) "CONSOLE" else "OS", platform = e)
    }

    // ---------------------------------------------------------------- Wikipedia's infobox, live

    /** Infobox rows of a Wikipedia article, keyed by lowercased heading ("units sold" to "100 million (As of November 10, 2026)"). Blocking. */
    fun infobox(title: String): Map<String, String> {
        val html = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&prop=text&section=0&redirects=1&page=${Http.enc(title)}")
            ?.optJSONObject("parse")?.optString("text") ?: return emptyMap()
        return infoboxRows(html)
    }

    fun infoboxRows(html: String): Map<String, String> {
        val box = Regex("<table[^>]*class=\"[^\"]*infobox.*?</table>", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (row in Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(box)) {
            val th = Regex("<th[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL).find(row.groupValues[1])?.groupValues?.get(1) ?: continue
            val td = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(row.groupValues[1])?.groupValues?.get(1) ?: continue
            val k = boxText(th).lowercase(Locale.US)
            if (k.isNotBlank() && k !in out) out[k] = boxText(td)
        }
        return out
    }

    private fun boxText(h: String) = h.replace(Regex("<sup[^>]*>.*?</sup>|<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&#91;", "[").replace("&#93;", "]").replace(' ', ' ')
        .replace(Regex("\\[(?:update|\\d+|[a-z])]"), " ")
        .replace(Regex("\\s+"), " ").replace(" )", ")").replace("( ", "(").replace(" ,", ",").replace(" ;", ";").trim()

    /** "26H1 (10.0.28000.3501) (November 10, 2026; 6 days ago (2026-11-10))" -> "26H1 (10.0.28000.3501) (November 10, 2026)". */
    fun latestOf(s: String): String? = s.split(Regex("\\[\\s*±?\\s*]|±"))[0].replace(Regex(";?\\s*\\d+ (?:days?|weeks?|months?|years?) ago\\s*(?:\\([\\d-]+\\))?"), "")
        .replace(Regex("\\s+"), " ").replace(" )", ")").replace(" ,", ",").trim().takeIf { it.isNotBlank() }?.take(90)

    /** "100 million (As of November 10, 2026)" -> "100 million (as of November 10, 2026)". */
    fun soldOf(s: String): String? {
        val n = Regex("(?:over |more than |about |~)?[\\d.,]+\\+?\\s*(?:million|billion)", RegexOption.IGNORE_CASE).find(s)?.value ?: return null
        val asOf = Regex("as of ([A-Z][a-z]+(?: \\d{1,2},)? \\d{4})", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
        return n.trim() + (asOf?.let { " (as of $it)" } ?: "")
    }

    // ---------------------------------------------------------------- today's prices

    /** Consoles still sold new, so a missing price means the store didn't answer. */
    private val STILL_SOLD = setOf("ps5", "ps5pro", "seriesx", "seriess", "switch", "switch2", "steamdeck", "rogally")

    /** Sony's store doesn't share prices with apps, so the last US list price Brick knows, dated. */
    private val LAST_KNOWN = mapOf(
        "ps5" to "\$549.99 (Digital Edition \$499.99), Sony's US list price as of August 2025",
        "ps5pro" to "\$749.99, Sony's US list price as of August 2025",
    )

    private fun nowRange(c: Console): String {
        val lo = c.now.minOf { it.second }; val hi = c.now.maxOf { it.second }
        return if (lo == hi) Prices.money(lo) else "${Prices.money(lo)} to ${Prices.money(hi)}"
    }

    private const val NINTENDO = "https://www.nintendo.com/us/store/products/"
    private val NINTENDO_PAGES = mapOf("switch2" to "nintendo-switch-2-system-123669", "switch" to "nintendo-switch-neon-blue-neon-red-joy-con-117972")
    private val XBOX_PRODUCTS = mapOf("seriesx" to listOf("8WJ714N3RBTL"), "seriess" to listOf("942J774TP9JN", "8ZCBGTT29H9C"))
    private val OFFER = Regex("\"offers\":\\{\"@type\":\"Offer\"[^}]*?\"price\":\"([0-9.]+)\"")

    /** Today's prices from the maker's own store: Steam for the Deck, the Microsoft Store for Xbox, Nintendo's store for the Switch. */
    fun nowPrices(id: String): Pair<String, List<Pair<String, Double>>>? {
        if (id == "steamdeck") {
            val subs = Http.getJsonCached("https://store.steampowered.com/api/appdetails?appids=1675200&cc=us&l=en")
                ?.optJSONObject("1675200")?.optJSONObject("data")?.optJSONArray("package_groups")?.optJSONObject(0)?.optJSONArray("subs") ?: return null
            val out = (0 until subs.length()).mapNotNull { i ->
                val o = subs.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("option_text").substringBefore(" - ").trim()
                if (label.isBlank() || label.contains("w/o", true)) null else label.removePrefix("Steam Deck ") to o.optInt("price_in_cents_with_discount") / 100.0
            }.filter { it.second > 0 }.distinctBy { it.first }
            return if (out.isEmpty()) null else "Steam Store" to out
        }
        XBOX_PRODUCTS[id]?.let { ids ->
            val out = ArrayList<Pair<String, Double>>()
            for (pid in ids) {
                val skus = Http.getJsonCached("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=$pid&market=US&languages=en-us")
                    ?.optJSONArray("Products")?.optJSONObject(0)?.optJSONArray("DisplaySkuAvailabilities") ?: continue
                for (i in 0 until skus.length()) {
                    val s = skus.optJSONObject(i) ?: continue
                    val title = s.optJSONObject("Sku")?.optJSONArray("LocalizedProperties")?.optJSONObject(0)?.optString("SkuTitle")?.trim().orEmpty()
                    val avail = s.optJSONArray("Availabilities") ?: continue
                    val price = (0 until avail.length()).mapNotNull { avail.optJSONObject(it)?.optJSONObject("OrderManagementData")?.optJSONObject("Price")?.optDouble("ListPrice") }
                        .filter { it > 0 }.minOrNull() ?: continue
                    if (title.contains("refurb", true)) continue
                    val label = title.replace(Regex("(?i)^xbox series [xs]\\s*(?:[-–]\\s*)?"), "").replace("–", "-").trim()
                        .replace(Regex(",\\s*(?:[A-Z][a-z]+ )?(?:Black|White)$|\\s*\\((?:Black|White)\\)$"), "").ifBlank { "Standard" }
                    out += label to price
                }
            }
            val clean = out.distinctBy { it.first to it.second }.sortedBy { it.second }
            return if (clean.isEmpty()) null else "Microsoft Store" to clean
        }
        NINTENDO_PAGES[id]?.let { slug ->
            val page = Http.getTextCached("$NINTENDO$slug/") ?: return null
            val p = OFFER.find(page)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return "Nintendo Store" to listOf((if (id == "switch2") "Switch 2" else "Switch") to p)
        }
        return null
    }

    /** The newest stable Linux kernel, from kernel.org ("6.17.8"). */
    fun latestKernel(): String? = Http.getJsonCached("https://www.kernel.org/releases.json")?.optJSONObject("latest_stable")?.optString("version")?.ifBlank { null }

    fun text(e: Entry, answer: String?): String = when (e) { is Console -> consoleText(e, answer); is Os -> osText(e, answer) }

    private fun kv(rows: MutableList<String>, label: String, value: String?, max: Int = 3) {
        if (!value.isNullOrBlank()) rows += Box.wrap(Box.ascii(value), first = Box.kv(label, ""), indent = 7, max = max)
    }

    private val TOP_TF = CONSOLES.mapNotNull { it.tflops }.max()

    fun consoleText(c: Console, answer: String?): String {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(c.name), max = 2)
        rows += Box.wrap("${c.form}, ${c.year}", max = 2)
        answer?.let { rows += ""; rows += Box.wrap(Box.ascii(it), "> ", indent = 2, max = 8) }
        rows += ""
        kv(rows, "MAKER", c.maker)
        kv(rows, "PARENT", c.parent ?: "None (independent)")
        kv(rows, "OUT", c.released)
        when {
            c.now.isNotEmpty() -> {
                rows += Box.kv("NOW", "${c.nowFrom}, today")
                for ((label, p) in c.now.take(5)) rows += Box.kv("", label.take(Box.W - 18).padEnd(Box.W - 17) + Prices.money(p))
            }
            LAST_KNOWN[c.id] != null -> kv(rows, "NOW", LAST_KNOWN[c.id])
            c.id !in STILL_SOLD -> kv(rows, "NOW", "Not sold new anymore")
        }
        kv(rows, "LAUNCH", c.price)
        kv(rows, "SOLD", c.liveSold?.let { "$it, per Wikipedia" } ?: c.sold)
        rows += ""
        rows += "PERFORMANCE"
        c.tflops?.let { t ->
            val barW = (Box.W - 17).coerceIn(5, 24)
            rows += Box.kv("POWER", Box.bar(Math.ceil(t / TOP_TF * barW).toInt().coerceAtLeast(1), barW) + " ${tf(c)} TF")
        }
        kv(rows, "CPU", c.cpu)
        kv(rows, "GPU", c.gpu)
        kv(rows, "RAM", c.ram)
        kv(rows, "DRIVE", c.storage)
        kv(rows, "TARGET", c.output)
        kv(rows, "ON PC", c.pc)
        rows += ""
        if (c.games.isNotEmpty()) {
            rows += if (c.id in setOf("seriesx", "seriess", "xboxone", "onex")) "FIRST-PARTY HITS" else if (c.year < 2000) "FAMOUS GAMES" else "EXCLUSIVES"
            for (g in c.games.take(6)) rows += Box.wrap(Box.ascii(g), "- ", indent = 2, max = 2)
        }
        c.gamesNote?.let { rows += Box.wrap(Box.ascii(it), max = 3) }
        rows += ""
        kv(rows, "ONLINE", c.online)
        kv(rows, "PLAYS", c.plays)
        rows += "KNOWN FOR"
        rows += Box.wrap(Box.ascii(c.known.replaceFirstChar { it.uppercase() }) + ".", max = 4)
        rows += ""
        rows += Box.wrap("Brick's platform notes, Wikipedia", first = Box.kv("SOURCE", ""), indent = 7, max = 2)
        return Box.build("CONSOLE", rows)
    }

    fun osText(o: Os, answer: String?): String {
        val rows = ArrayList<String>()
        rows += Box.wrap(Box.ascii(o.name), max = 2)
        rows += Box.wrap("${o.type.label} OS, since ${o.year}", max = 2)
        answer?.let { rows += ""; rows += Box.wrap(Box.ascii(it), "> ", indent = 2, max = 8) }
        rows += ""
        kv(rows, "MAKER", o.maker)
        kv(rows, "PARENT", o.parent ?: "None")
        kv(rows, "RUNS ON", o.platforms)
        kv(rows, "KERNEL", o.liveKernel?.let { "Linux $it (latest stable)" } ?: o.kernel)
        kv(rows, "LATEST", o.latest?.let { "$it, per Wikipedia" })
        kv(rows, "VERSION", o.version)
        kv(rows, "BASE", o.distro ?: if (o.kernel == LINUX_KERNEL && o.id != "linux") null else if (o.id == "linux") "The kernel under every Linux distribution" else "Its own (not a Linux distribution)")
        kv(rows, "TYPE", o.type.label)
        kv(rows, "USERS", o.users, max = 4)
        kv(rows, "PRICE", o.price)
        kv(rows, "LICENSE", o.license)
        rows += ""
        rows += "BRICK'S TAKE (/10)"
        val barW = (Box.W - 13).coerceIn(5, 20)
        SCORE_LABELS.forEachIndexed { i, (label, _) ->
            rows += listOf("GAMES", "APPS", "EASE", "CUSTOM")[i].padEnd(7) + Box.bar(Math.round(o.scores[i] / 10.0 * barW).toInt(), barW) + " ${o.scores[i]}"
        }
        rows += ""
        rows += "KNOWN FOR"
        rows += Box.wrap(Box.ascii(o.known.replaceFirstChar { it.uppercase() }) + ".", max = 4)
        rows += ""
        rows += "GAMING"
        rows += Box.wrap(Box.ascii(o.gaming), max = 4)
        rows += ""
        rows += Box.wrap(listOfNotNull("Brick's platform notes", if (o.liveKernel != null) "kernel.org" else null, "StatCounter", "Wikipedia").joinToString(", "), first = Box.kv("SOURCE", ""), indent = 7, max = 2)
        return Box.build("OS", rows)
    }
}
