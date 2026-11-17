package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the PC is for; each kind weighs the parts differently. */
enum class BuildFor(val label: String, val defaultBudget: Int) {
    BUDGET("budget gaming PC", 750),
    GAMING("gaming PC", 1300),
    GOOD("all-round PC", 1300),
    GAMING_4K("4K gaming PC", 2400),
    BALLER("baller PC", 7000),
    GAMEDEV("game dev PC", 2000),
    VIDEO("video editing PC", 2200),
    GENERAL("everyday PC", 800),
}

/**
 * "Build me a pc for $1500", "baller pc", "4k gaming rig under 2.5k", "video editing pc with a 5080".
 * [budget] is null when no amount was said (the kind's usual budget is used). [gpu]/[cpu] pin a part that was named.
 */
data class BuildAsk(
    val purpose: BuildFor, val budget: Int?, val said: String,
    val gpu: String? = null, val cpu: String? = null,
    val amdCpu: Boolean? = null, val nvidia: Boolean? = null,
)

object PcBuild {
    private val us = Locale.US

    // ------------------------------------------------------------------ parts (rough US street prices)

    class Cpu(val name: String, val key: Regex, val socket: String, val price: Int, val game: Int, val multi: Int, val watts: Int,
              val igpu: Int, val boxCooler: Boolean, val amd: Boolean, val x3d: Boolean = false)
    class Gpu(val name: String, val key: Regex, val price: Int, val score: Int, val vram: Int, val watts: Int, val nvidia: Boolean, val timeSpy: Int)
    class Pick(val name: String, val price: Int, val size: Int = 0)

    private fun k(s: String) = Regex(s)

    val CPUS = listOf(
        Cpu("Ryzen 5 5600", k("\\b5600(?![xg0-9])"), "AM4", 95, 55, 34, 65, 0, true, true),
        Cpu("Core i5-14400F", k("\\b14400f?\\b"), "LGA1700", 150, 64, 50, 65, 0, true, false),
        Cpu("Ryzen 5 7600", k("\\b7600(?![x0-9])"), "AM5", 180, 70, 44, 65, 5, true, true),
        Cpu("Ryzen 5 8600G", k("\\b8600g\\b"), "AM5", 190, 62, 44, 65, 15, true, true),
        Cpu("Ryzen 5 9600X", k("\\b9600x\\b"), "AM5", 210, 75, 50, 65, 5, false, true),
        Cpu("Ryzen 7 9700X", k("\\b9700x\\b"), "AM5", 290, 78, 62, 65, 5, false, true),
        Cpu("Core Ultra 7 265K", k("\\b265kf?\\b"), "LGA1851", 280, 74, 86, 125, 8, false, false),
        Cpu("Ryzen 7 7800X3D", k("\\b7800 ?x3d\\b"), "AM5", 380, 92, 58, 120, 5, false, true, x3d = true),
        Cpu("Ryzen 9 9900X", k("\\b9900x(?! ?3d)\\b"), "AM5", 380, 80, 82, 120, 5, false, true),
        Cpu("Ryzen 7 9800X3D", k("\\b9800 ?x3d\\b"), "AM5", 470, 100, 66, 120, 5, false, true, x3d = true),
        Cpu("Ryzen 9 9950X", k("\\b9950x(?! ?3d)\\b"), "AM5", 540, 82, 100, 170, 5, false, true),
        Cpu("Ryzen 9 9950X3D", k("\\b9950 ?x3d\\b"), "AM5", 680, 100, 102, 170, 5, false, true, x3d = true),
    )

    val GPUS = listOf(
        Gpu("Intel Arc B580", k("\\bb580\\b"), 260, 40, 12, 190, false, 14500),
        Gpu("GeForce RTX 5060", k("\\b5060(?! ?ti)\\b"), 300, 42, 8, 145, true, 13000),
        Gpu("Radeon RX 9060 XT 16 GB", k("\\b9060 ?xt\\b"), 360, 48, 16, 160, false, 15500),
        Gpu("GeForce RTX 5060 Ti 16 GB", k("\\b5060 ?ti\\b"), 430, 52, 16, 180, true, 16000),
        Gpu("GeForce RTX 5070", k("\\b5070(?! ?ti)\\b"), 550, 66, 12, 250, true, 22500),
        Gpu("Radeon RX 9070", k("\\b9070(?! ?xt)\\b"), 560, 70, 16, 220, false, 25000),
        Gpu("Radeon RX 9070 XT", k("\\b9070 ?xt\\b"), 650, 78, 16, 304, false, 29500),
        Gpu("GeForce RTX 5070 Ti", k("\\b5070 ?ti\\b"), 780, 82, 16, 300, true, 28500),
        Gpu("GeForce RTX 5080", k("\\b5080\\b"), 1100, 92, 16, 360, true, 32500),
        Gpu("GeForce RTX 5090", k("\\b5090\\b"), 2500, 125, 32, 575, true, 48500),
    )

    private val RAM4 = listOf(Pick("16 GB (2x8) DDR4-3200 CL16", 70, 16), Pick("32 GB (2x16) DDR4-3600 CL18", 130, 32), Pick("64 GB (2x32) DDR4-3600 CL18", 250, 64))
    private val RAM5 = listOf(Pick("16 GB (2x8) DDR5-6000 CL36", 140, 16), Pick("32 GB (2x16) DDR5-6000 CL30", 260, 32),
        Pick("64 GB (2x32) DDR5-6000 CL30", 520, 64), Pick("96 GB (2x48) DDR5-6000 CL30", 780, 96))
    private val SSDS = listOf(Pick("WD Black SN7100 1 TB", 90, 1), Pick("WD Black SN7100 2 TB", 150, 2), Pick("Samsung 990 Pro 4 TB", 340, 4))

    /** The board for a chip: [band] 0 budget, 1 mid, 2 high end. */
    private fun board(c: Cpu, band: Int) = when (c.socket) {
        "AM4" -> Pick("ASRock B550M Pro4", 100)
        "LGA1700" -> Pick("MSI PRO B760M-A WiFi (DDR5)", 140)
        "LGA1851" -> if (band == 0) Pick("MSI PRO B860-P WiFi", 170) else Pick("MSI MAG Z890 Tomahawk WiFi", 300)
        else -> when (band) { 0 -> Pick("ASRock B650M Pro RS WiFi", 140); 1 -> Pick("MSI MAG B850 Tomahawk WiFi", 220); else -> Pick("ASUS ROG Strix X870E-E Gaming WiFi", 480) }
    }

    private fun cooler(c: Cpu, band: Int) = when {
        c.boxCooler && band == 0 && c.watts <= 65 -> Pick("Included box cooler", 0)
        c.watts <= 125 && band < 2 -> Pick("Thermalright Peerless Assassin 120 SE", 40)
        else -> Pick("Arctic Liquid Freezer III 360", 110)
    }

    private fun psu(c: Cpu, g: Gpu?, band: Int): Pick {
        val need = ((c.watts + (g?.watts ?: 0) + 75) * 1.4).toInt()
        return when {
            need <= 650 && band == 0 -> Pick("MSI MAG A650BN (650 W Bronze)", 60, 650)
            need <= 750 && band < 2 -> Pick("Corsair RM750e (750 W Gold)", 95, 750)
            need <= 850 && band < 2 -> Pick("Corsair RM850x (850 W Gold)", 125, 850)
            need <= 1000 -> Pick("Corsair RM1000x (1000 W Gold)", 175, 1000)
            else -> Pick("Corsair HX1200i (1200 W Platinum)", 260, 1200)
        }
    }

    private fun case(budget: Int) = when {
        budget < 900 -> Pick("Montech XR", 70)
        budget < 2000 -> Pick("Lian Li Lancool 207", 85)
        budget < 3500 -> Pick("Fractal Design North", 140)
        else -> Pick("Lian Li O11 Dynamic EVO", 170)
    }

    // ------------------------------------------------------------------ choosing

    class Build(
        val ask: BuildAsk, val budget: Int, val cpu: Cpu, val gpu: Gpu?, val board: Pick, val cooler: Pick, val ram: Pick,
        val ssd: Pick, val psu: Pick, val case: Pick,
    ) {
        val total get() = cpu.price + (gpu?.price ?: 0) + board.price + cooler.price + ram.price + ssd.price + psu.price + case.price
        val over get() = total > budget
    }

    /** How good this build is at the job, on a rough shared scale. */
    private fun score(p: BuildFor, c: Cpu, g: Gpu?, ram: Int, ssd: Int): Double {
        val gs = g?.score ?: c.igpu
        // The CPU caps the frames the card can push; less so at 4K.
        fun fps(k: Double) = minOf(gs.toDouble(), c.game * k) * 0.85 + gs * 0.15
        fun gameRam() = when { ram >= 32 -> 6.0; else -> 0.0 }
        fun drive(a: Double, b: Double) = when (ssd) { 1 -> 0.0; 2 -> a; else -> b }
        return when (p) {
            BuildFor.BUDGET, BuildFor.GAMING -> fps(1.0) + gameRam() + drive(3.0, 4.0)
            BuildFor.GOOD -> fps(1.1) + 0.12 * c.multi + gameRam() + drive(3.0, 4.0)
            BuildFor.GAMING_4K -> fps(1.6) - (if ((g?.vram ?: 0) < 16) 25 else 0) + gameRam() + drive(3.0, 4.0)
            BuildFor.BALLER -> fps(1.1) + 0.2 * c.multi + (if (ram >= 64) 9.0 else gameRam()) + drive(4.0, 8.0)
            BuildFor.GAMEDEV -> 0.45 * fps(1.1) + 0.55 * c.multi + (when { ram >= 64 -> 16.0; ram >= 32 -> 8.0; else -> 0.0 }) + drive(6.0, 8.0) + (if (g?.nvidia == true) 3 else 0)
            BuildFor.VIDEO -> 0.3 * gs + (if (g?.nvidia == true) 8 else 0) + 0.7 * c.multi +
                (when { ram >= 96 -> 22.0; ram >= 64 -> 18.0; ram >= 32 -> 8.0; else -> 0.0 }) + drive(8.0, 14.0)
            BuildFor.GENERAL -> 0.6 * c.multi + 0.25 * minOf(gs, 45) + (if (ram >= 32) 6.0 else 0.0) + drive(4.0, 5.0)
        }
    }

    fun choose(ask: BuildAsk): Build {
        val budget = ask.budget ?: ask.defaultBudget()
        val band = when { budget >= 4000 || (ask.purpose == BuildFor.BALLER && budget >= 2500) -> 2; budget >= 1800 -> 1; else -> 0 }
        val cpus = CPUS.filter { c -> (ask.cpu == null || c.name == ask.cpu) && (ask.amdCpu == null || c.amd == ask.amdCpu) }.ifEmpty { CPUS }
        val gpus: List<Gpu?> = GPUS.filter { g -> (ask.gpu == null || g.name == ask.gpu) && (ask.nvidia == null || g.nvidia == ask.nvidia) }.ifEmpty { GPUS }
        // No graphics card only where built-in graphics will do: everyday PCs, or a gaming budget too small for a card.
        val noCard = ask.gpu == null && (ask.purpose == BuildFor.GENERAL || budget < 650)
        var best: Build? = null; var bestScore = Double.NEGATIVE_INFINITY
        var cheapest: Build? = null
        for (c in cpus) for (g in (if (noCard) gpus + null else gpus)) {
            if (g == null && c.igpu == 0) continue
            for (ram in if (c.socket == "AM4") RAM4 else RAM5) for (ssd in SSDS) {
                val b = Build(ask, budget, c, g, board(c, band), cooler(c, band), ram, ssd, psu(c, g, band), case(budget))
                if (cheapest == null || b.total < cheapest.total) cheapest = b
                if (b.over) continue
                val s = score(ask.purpose, c, g, ram.size, ssd.size) - b.total * 0.0004
                if (s > bestScore) { bestScore = s; best = b }
            }
        }
        return best ?: cheapest!!
    }

    private fun BuildAsk.defaultBudget() = when {
        purpose != BuildFor.BUDGET && Regex("\\b(?:budget|cheap|cheapest|affordable|entry[- ]level|starter|low[- ]end|inexpensive)\\b").containsMatchIn(said) -> (purpose.defaultBudget * 0.6).toInt()
        else -> purpose.defaultBudget
    }

    // ------------------------------------------------------------------ estimates for the infographic

    /** A demanding game: frames per point of GPU score at 1440p high (native, no ray tracing), and the CPU's ceiling. */
    class Game(val name: String, val perGpu: Double, val perCpu: Double)
    val GAMES = listOf(
        Game("Cyberpunk 2077", 1.35, 1.6), Game("Black Myth Wukong", 0.85, 1.5), Game("Alan Wake 2", 0.8, 1.5),
        Game("RDR 2", 1.25, 1.6), Game("CoD Black Ops 6", 1.9, 2.2), Game("Fortnite", 1.9, 2.4),
    )
    enum class Res(val label: String, val scale: Double) { P1080("1080p", 1.4), P1440("1440p", 1.0), P2160("4K", 0.55) }

    private fun Build.gpuScore() = gpu?.score ?: cpu.igpu

    /** Estimated average fps in [g] at [r], high settings. */
    fun fps(b: Build, g: Game, r: Res): Int {
        val v = minOf(b.gpuScore() * g.perGpu * r.scale, b.cpu.game * g.perCpu)
        return if (v >= 30) (Math.round(v / 5) * 5).toInt() else v.toInt()
    }

    /** Estimated Cinebench 2024 multi-core score. */
    fun cinebench(c: Cpu) = (Math.round(c.multi * 22.5 / 10) * 10).toInt()

    /** Estimated 3DMark Time Spy graphics score (built-in graphics when there's no card). */
    fun timeSpy(b: Build) = b.gpu?.timeSpy ?: when { b.cpu.igpu >= 15 -> 2800; b.cpu.igpu >= 8 -> 1200; else -> 700 }

    /** What the build is good for, each rated 1-5. */
    fun ratings(b: Build): List<Pair<String, Int>> {
        val gs = b.gpuScore().toDouble(); val m = b.cpu.multi.toDouble()
        fun stars(v: Double) = when { v >= 85 -> 5; v >= 68 -> 4; v >= 50 -> 3; v >= 32 -> 2; else -> 1 }
        fun game(r: Res) = fps(b, GAMES[0], r).let { when { it >= 120 -> 5; it >= 80 -> 4; it >= 55 -> 3; it >= 35 -> 2; else -> 1 } }
        val ram = when { b.ram.size >= 64 -> 15.0; b.ram.size >= 32 -> 8.0; else -> 0.0 }
        return listOf(
            "1080p gaming" to game(Res.P1080), "1440p gaming" to game(Res.P1440), "4K gaming" to game(Res.P2160),
            "Game dev" to stars(0.45 * m + 0.35 * minOf(gs, 100.0) + ram),
            "Video editing" to stars(0.6 * m + 0.2 * minOf(gs, 100.0) + ram + (if (b.gpu?.nvidia == true) 8 else 0)),
            "3D rendering" to stars(0.45 * gs * (if (b.gpu?.nvidia == true) 1.15 else 1.0) + 0.4 * m),
            "Streaming" to stars(0.5 * minOf(gs, 90.0) + 0.5 * m + (if (b.gpu?.nvidia == true) 10 else 0)),
            "Everyday use" to 5,
        )
    }
    fun ratingWord(n: Int) = when (n) { 5 -> "Excellent"; 4 -> "Great"; 3 -> "Good"; 2 -> "OK"; else -> "Weak" }

    class Power(val psu: Int, val rating: String, val gaming: Int, val peak: Int, val wallGaming: Int, val wallMax: Int)

    /** What the build draws while gaming and at full tilt, and what the power supply pulls from the wall. */
    fun power(b: Build): Power {
        val rating = Regex("(Bronze|Gold|Platinum|Titanium)").find(b.psu.name)?.value ?: "Gold"
        val (half, full) = when (rating) { "Bronze" -> 0.85 to 0.82; "Platinum" -> 0.92 to 0.89; "Titanium" -> 0.94 to 0.90; else -> 0.90 to 0.87 }
        val gpuW = b.gpu?.watts ?: 0
        val gaming = (b.cpu.watts * 0.6 + gpuW * 0.95 + 60).toInt()
        val peak = (b.cpu.watts * (if (b.cpu.amd) 1.0 else 1.6) + gpuW + 75).toInt()
        return Power(b.psu.size, "80 Plus $rating", gaming, peak, (gaming / half).toInt(), (b.psu.size / full).toInt())
    }

    // ------------------------------------------------------------------ the card

    suspend fun find(ask: BuildAsk): LookupResult = withContext(Dispatchers.IO) {
        val b = choose(ask)
        val image = runCatching { PcParts.pageImage(if (b.gpu == null) "Desktop computer" else "Gaming computer") }.getOrNull()
        card(b, image)
    }

    fun money(n: Int) = "$" + String.format(us, "%,d", n)

    fun title(b: Build) = if (b.ask.budget != null) "${money(b.ask.budget)} ${b.ask.purpose.label}" else b.ask.purpose.label.replaceFirstChar { it.uppercase(us) }

    fun card(b: Build, image: String?): LookupResult {
        val facts = listOf(
            "CPU" to "${b.cpu.name} - ${money(b.cpu.price)}",
            "COOLER" to "${b.cooler.name}${if (b.cooler.price > 0) " - " + money(b.cooler.price) else ""}",
            "BOARD" to "${b.board.name} - ${money(b.board.price)}",
            "RAM" to "${b.ram.name} - ${money(b.ram.price)}",
            "GPU" to (b.gpu?.let { "${it.name} - ${money(it.price)}" } ?: "None: the ${b.cpu.name}'s built-in graphics"),
            "SSD" to "${b.ssd.name} NVMe - ${money(b.ssd.price)}",
            "PSU" to "${b.psu.name} - ${money(b.psu.price)}",
            "CASE" to "${b.case.name} - ${money(b.case.price)}",
            "TOTAL" to "about ${money(b.total)}" + (b.ask.budget?.let { " of ${money(it)}" } ?: ""),
        )
        val more = listOf("WHY THESE PARTS" to reasons(b), "WHAT TO EXPECT" to expect(b), "BEFORE YOU BUY" to notes(b))
        return LookupResult(title(b), listOfNotNull(b.cpu.name, b.gpu?.name).joinToString(" + "), null, "", image, "", facts,
            answer(b), sources = listOf("Brick's parts list (rough US prices)"), heading = "PC BUILD", more = more, build = b)
    }

    /** "For $1,500: a Ryzen 7 7800X3D with an RX 9070 XT, 32 GB of RAM and a 2 TB SSD, about $1,490." */
    fun answer(b: Build): String {
        val gpu = b.gpu?.let { "with ${article(short(it.name))} ${short(it.name)}" } ?: "using its built-in graphics"
        val parts = "${article(b.cpu.name)} ${b.cpu.name} $gpu, ${b.ram.size} GB of RAM and a ${b.ssd.size} TB SSD"
        return when {
            b.over && b.ask.budget != null -> "${money(b.ask.budget)} is tight for this. The closest build is $parts, about ${money(b.total)}."
            b.ask.budget != null -> "For ${money(b.ask.budget)}: $parts, about ${money(b.total)}."
            else -> b.ask.purpose.label.replace(" PC", "").let { "Here's ${article(it)} $it build: $parts, about ${money(b.total)}." }
        }
    }

    private fun short(gpu: String) = gpu.removePrefix("GeForce ").removePrefix("Radeon ").removePrefix("Intel ").replace(" 16 GB", "")
    private fun article(w: String) = if (w.first().lowercaseChar() in "aeio" || w.startsWith("RX") || w.startsWith("RTX") || w.startsWith("8")) "an" else "a"

    private fun reasons(b: Build): List<String> = buildList {
        val p = b.ask.purpose
        when (p) {
            BuildFor.VIDEO -> {
                add("Editing and exports lean on CPU cores, so the ${b.cpu.name} gets a big share.")
                if (b.ram.size >= 64) add("${b.ram.size} GB of RAM keeps long 4K timelines and effects smooth.")
                if (b.gpu?.nvidia == true) add("NVIDIA's encoder and CUDA speed up Premiere Pro and DaVinci Resolve.")
                add("A ${b.ssd.size} TB SSD for active projects; keep finished footage on a big hard drive.")
            }
            BuildFor.GAMEDEV -> {
                add("Compiling code and shaders eats cores; the ${b.cpu.name} has ${if (b.cpu.multi >= 80) "plenty" else "a good number"}.")
                add("Unreal Engine and Unity want ${if (b.ram.size >= 64) "64 GB" else "at least 32 GB"} of RAM for big projects.")
                b.gpu?.let { add("The ${short(it.name)} lets you play-test at high settings and bake lighting fast.") }
            }
            BuildFor.GENERAL -> {
                if (b.gpu == null) add("No graphics card needed: the ${b.cpu.name}'s built-in graphics handle 4K video and light games.")
                add("Plenty for browsing, school, office work and video calls, and it stays quiet.")
                if (b.ram.size >= 32) add("32 GB of RAM so lots of tabs and apps never slow it down.")
            }
            else -> {
                b.gpu?.let { add("The graphics card gets the biggest share: the ${short(it.name)} decides your frame rate.") }
                if (b.cpu.x3d) add("X3D chips have extra cache on top; they're the fastest CPUs for games.")
                if (p == BuildFor.GAMING_4K) add("4K needs a 16 GB card; high-res textures fill VRAM fast.")
                if (p == BuildFor.BALLER) add("No corners cut: top-tier chips, a premium board and liquid cooling.")
                if (b.ram.size >= 32) add("${b.ram.size} GB of RAM is the new normal for games with Discord and a browser open.")
                if (b.gpu == null) add("Built-in graphics keep the cost down; add a graphics card later for real gaming.")
            }
        }
        if (b.psu.size >= 850 || b.gpu == null) Unit else add("The ${b.psu.size} W power supply leaves headroom for a better card later.")
    }

    private fun expect(b: Build): List<String> {
        val g = b.gpu ?: return listOf("Esports games at 1080p on low to medium; 4K streaming and everyday work with ease.")
        val s = g.score
        val games = when {
            s >= 110 -> "4K max settings at high frame rates, even with path tracing (with DLSS)."
            s >= 88 -> "4K high at 60+ fps; 1440p at 144+ fps."
            s >= 75 -> "1440p high or ultra at 100+ fps; 4K with upscaling."
            s >= 60 -> "1440p high at 60-100 fps; 1080p at 144+ fps."
            s >= 45 -> "1080p high at 60-100+ fps; 1440p on medium."
            else -> "1080p medium to high at 60+ fps."
        }
        return buildList {
            add(games)
            if (b.ask.purpose == BuildFor.VIDEO || b.ask.purpose == BuildFor.GAMEDEV) add("Exports and builds about ${if (b.cpu.multi >= 95) "twice" else if (b.cpu.multi >= 80) "1.6x" else "1.3x"} as fast as a 6-core chip.")
        }
    }

    private fun notes(b: Build): List<String> = buildList {
        if (b.over) add("This comes to ${money(b.total - b.budget)} over budget; it's the cheapest build that still makes sense.")
        else if (b.ask.budget != null && b.budget - b.total >= 300) add("About ${money(b.budget - b.total)} left over: put it toward a monitor or more storage.")
        add("Prices are rough US estimates and move week to week, RAM and SSDs especially.")
        add("Windows, monitor, keyboard and mouse aren't included.")
        add("Turn on EXPO or XMP in the BIOS so the RAM runs at its rated speed.")
        if (b.gpu != null) add("Plug the monitor into the graphics card, not the motherboard.")
        if (b.board.name.contains("WiFi").not()) add("This board has no Wi-Fi; add a cheap Wi-Fi card or use Ethernet.")
    }

    // ------------------------------------------------------------------ understanding the ask

    private const val PC = "(?:pc|pcs|computer|computers|rig|rigs|build|builds|desktop|tower|setup|workstation|battle ?station)"
    private val REJECT = Regex("^(?:remind|note|add|text|message|send|call|search|google|look up|play|set|turn|open|close|restart|reboot|shut|launch|start|stop|how (?:do|to|can|should|would)|why|when|where|is my|my )")
    private val VERB = Regex("\\b(?:build|spec(?: out)?|put together|assemble|piece together|plan(?: out)?|design|configure|kit out|spec me)\\b(?: me| us)?(?: out)? (?:an? |the |my |some |our )?(?:[a-z0-9$.,+'-]+ ){0,5}?$PC\\b")
    private val ASK = Regex("\\b(?:make|give|get|pick(?: out)?|choose|recommend|suggest|find|show|put together|help me (?:build|pick|choose))\\b(?: me| us)? (?:an? |some )(?:[a-z0-9$.,+'-]+ ){0,5}?$PC\\b")
    private val QUESTION = Regex("\\b(?:what|which) (?:pc|computer|rig|parts|build|setup|desktop)s? (?:should|could|can|would|do) (?:i|we) (?:build|get|buy|make|go with)|\\bparts? ?list\\b|\\bpc ?parts? for\\b|\\bhow much (?:is|does|would|will|for) (?:an? |it cost to build an? )?(?:[a-z0-9-]+ ){0,4}$PC\\b")
    private val ADJ = Regex("\\b(?:budget|cheap|cheapest|affordable|baller|ballin|ballin'|4k|gaming|gamer|editing|editor|video editing|video editor|streaming|streamer|developer|dev|game dev|gamedev|office|school|work|general|general purpose|everyday|starter|entry[- ]level|mid ?range|high[- ]end|good|decent|solid|nice|great|monster|beast|dream|ultimate|insane|crazy|overkill|god ?tier|project|content creation|creator|youtube|youtuber|unreal|blender|first)\\s+(?:gaming\\s+)?$PC\\b")
    private val DEFINE = Regex("^(?:what is|what's|whats|define|what does) (?:an? |the )?(?:[a-z0-9-]+ )?(?:pc|computer|rig|desktop|workstation)s?(?: mean| do)?$")

    fun parse(raw: String): BuildAsk? {
        val s = raw.lowercase(us).replace("’", "'").replace(Regex("[?!]+"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(?:hey |ok |okay |yo )?brick,? "), "").replace(Regex("^(?:can you |could you |would you |will you |please |i want you to |i need you to |i'd like you to )+"), "")
        if (s.length > 200 || !Regex("\\b$PC\\b").containsMatchIn(s) || REJECT.containsMatchIn(s)) return null
        val budget = budget(s)
        val asked = VERB.containsMatchIn(s) || ASK.containsMatchIn(s) || QUESTION.containsMatchIn(s) ||
            (budget != null && Regex("\\b$PC\\b.{0,20}\\b(?:for|under|around|about|with|at|below|max|budget|of)\\b|\\bi (?:have|got|wanna spend|want to spend|can spend)\\b").containsMatchIn(s)) ||
            (ADJ.containsMatchIn(s) && !DEFINE.matches(s))
        if (!asked) return null
        // "a gaming pc with a 5090", "all amd", "nvidia card".
        val gpu = GPUS.firstOrNull { it.key.containsMatchIn(s) }?.name
        val cpu = CPUS.firstOrNull { it.key.containsMatchIn(s) }?.name
        val nvidia = when {
            gpu != null -> null
            Regex("\\b(?:nvidia|geforce|rtx)\\b").containsMatchIn(s) -> true
            Regex("\\b(?:radeon|all amd|full amd|team red)\\b").containsMatchIn(s) -> false
            else -> null
        }
        val amd = when {
            cpu != null -> null
            Regex("\\b(?:amd|ryzen|team red)\\b").containsMatchIn(s) -> true
            Regex("\\b(?:intel|core ultra|team blue)\\b").containsMatchIn(s) -> false
            else -> null
        }
        return BuildAsk(purpose(s), budget, s, gpu, cpu, amd, nvidia)
    }

    fun purpose(s: String): BuildFor {
        fun has(p: String) = Regex(p).containsMatchIn(s)
        val gaming = has("\\b(?:gaming|games?|gamer|esports|fortnite|valorant|warzone|call of duty|cod|cs2|apex|minecraft|gta|cyberpunk|fps|frames|steam|ray tracing|1080p|1440p|competitive)\\b")
        return when {
            has("\\b(?:baller|ballin'?|money(?: is)? no object|no budget|unlimited|price (?:doesn't|does not|don't) matter|cost (?:doesn't|does not) matter|best of the best|best possible|fastest possible|most powerful|top of the line|top[- ]end|highest[- ]end|flagship|dream|ultimate|god ?tier|monster|beast|insane|crazy|overkill|max(?:ed)? out|no expense|whatever it costs|rich|money to burn|sky'?s the limit)\\b") -> BuildFor.BALLER
            has("\\b(?:video edit|editing|editor|premiere|davinci|resolve|final cut|after effects|content creat|creator|youtube|youtuber|color grad|vfx|motion graphics|photo edit|lightroom|photoshop|render(?:ing)? videos?)") -> BuildFor.VIDEO
            has("\\b(?:game dev|gamedev|game develop|unreal|unity|godot|making games|make games|indie dev|game design|3d model|3d art|blender|modeling|modelling|developer|dev)\\b") -> BuildFor.GAMEDEV
            has("(?<![$\\d.])\\b(?:4k|2160p|uhd)\\b(?! ?(?:budget|dollars|bucks|usd))") -> BuildFor.GAMING_4K
            !gaming && has("\\b(?:general|everyday|every day|office|school|homework|work|browsing|web|email|family|home|basic|simple|projects?|productivity|all[- ]around|all[- ]purpose|multi ?purpose|study|college|student|spreadsheets|zoom|coding|programming|plex|home server|parents?|grandma|grandpa|kids?)\\b") -> BuildFor.GENERAL
            has("\\b(?:budget|cheap|cheapest|affordable|entry[- ]level|starter|first|low[- ]end|low budget|inexpensive|bang for (?:the|your|my) buck|value|broke|tight)\\b") -> BuildFor.BUDGET
            has("\\b(?:good|decent|solid|nice|great|mid ?range|sweet spot|balanced|reliable|well[- ]rounded|all[- ]rounder|best)\\b") && !gaming -> BuildFor.GOOD
            gaming -> BuildFor.GAMING
            else -> BuildFor.GOOD
        }
    }

    private val WORD_NUM = mapOf("a" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "twenty five" to 25)

    /** "$1,500", "1500 dollars", "1.5k", "2 grand", "a grand", "fifteen hundred", "under 900". "4k gaming" is a resolution. */
    fun budget(s: String): Int? {
        val found = ArrayList<Int>()
        fun num(t: String) = t.replace(",", "").toDoubleOrNull()
        fun scale(v: Double, unit: String?) = when (unit?.trim()) { "k", "grand", "thousand" -> v * 1000; "hundred" -> v * 100; else -> v }
        Regex("\\$\\s?(\\d[\\d,]*(?:\\.\\d+)?)\\s?(k|grand|thousand)?\\b").findAll(s).forEach { m -> num(m.groupValues[1])?.let { found += scale(it, m.groupValues[2]).toInt() } }
        Regex("(?<![$\\d.,])(\\d[\\d,]*(?:\\.\\d+)?)\\s?(k|grand|thousand|hundred)?\\s?(?:dollars|bucks|usd|dollar)\\b").findAll(s).forEach { m -> num(m.groupValues[1])?.let { found += scale(it, m.groupValues[2]).toInt() } }
        // "1.5k", "2k", "3 grand" (but "4k" and "8k" alone are screens).
        Regex("(?<![$\\d.,])(\\d+(?:\\.\\d+)?)\\s?(k|grand)\\b").findAll(s).forEach { m ->
            val v = num(m.groupValues[1]) ?: return@forEach
            if (m.groupValues[2] == "k" && (v == 4.0 || v == 8.0 || v == 5.0)) return@forEach
            found += scale(v, m.groupValues[2]).toInt()
        }
        Regex("\\b(a|one|two|three|four|five|six|seven|eight|nine|ten) (grand|thousand)\\b").findAll(s).forEach { m -> found += (WORD_NUM[m.groupValues[1]] ?: 0) * 1000 }
        Regex("\\b(five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|twenty five) hundred\\b").findAll(s).forEach { m -> found += (WORD_NUM[m.groupValues[1]] ?: 0) * 100 }
        // A bare amount after "for", "under", "budget of": "a pc for 1200", "budget is 900".
        if (found.isEmpty()) Regex("\\b(?:for|under|around|about|with|budget(?: of| is)?|max|maximum|at most|up to|less than|below|spend(?:ing)?|have|got|of|is)\\s(?:about |around |like |roughly |maybe |only |just )?(\\d{3,5}|\\d{1,2},\\d{3})\\b(?! ?(?:w|watts?|gb|tb|mb|hz|p|fps|mhz|series))")
            .findAll(s).forEach { m -> num(m.groupValues[1])?.let { found += it.toInt() } }
        return found.filter { it in 200..50000 }.maxOrNull()
    }
}
