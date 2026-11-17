package com.brick.assistant.brain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MacBook Air and MacBook Pro by generation. Wikipedia keeps one article per line with a specs table for each chip
 * ("M5 models"), so "macbook air m5" reads that table instead of landing on the chip's page, and a bare "macbook air"
 * reads the newest generation that's out.
 */
object Laptops {
    private val us = Locale.US

    /** "macbook air m5 15 inch" -> line AIR, chip 5, size 15. [tier] is "pro" or "max" for the bigger chips. */
    data class Mac(val pro: Boolean, val chip: Int?, val tier: String?, val size: Int?) {
        val page get() = if (pro) "MacBook Pro (Apple silicon)" else "MacBook Air (Apple silicon)"
        val line get() = if (pro) "MacBook Pro" else "MacBook Air"
    }

    /** Cells that say nothing: "—", "N/A", "—, N/a". */
    private val EMPTY = Regex("^[\\s—,\\-]*(?:N/?A)?[\\s—,\\-]*$", RegexOption.IGNORE_CASE)

    private val MAC = Regex("^(?:(?:the|a|an|newest|latest|current|new|apple) )*mac ?book (air|pro)\\b(.*)$")

    fun match(said: String): Mac? {
        val s = said.lowercase(us).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val m = MAC.find(s) ?: return null
        val rest = m.groupValues[2]
        // Intel models and anything that isn't a chip or a size ("macbook pro charger") stay with the general lookup.
        if (Regex("\\b(?:intel|i[3579]|20(?:0|1)\\d|charger|case|keyboard|battery replacement|screen protector)\\b").containsMatchIn(rest)) return null
        val chip = Regex("\\bm ?([1-9])\\b").find(rest)?.groupValues?.get(1)?.toIntOrNull()
        val tier = Regex("\\bm ?[1-9] ?(pro|max)\\b").find(rest)?.groupValues?.get(1)
        val size = Regex("\\b(13|14|15|16)(?: ?inch| ?in|\")?\\b").find(rest)?.groupValues?.get(1)?.toIntOrNull()
        val leftover = rest.replace(Regex("\\bm ?[1-9](?: ?(?:pro|max))?\\b|\\b(?:13|14|15|16)(?: ?inch| ?in)?\\b|\\b(?:newest|latest|current|new|the|model|models|laptop|chip|version|edition|base|20[23]\\d)\\b"), "").trim()
        if (leftover.isNotEmpty()) return null
        return Mac(m.groupValues[1] == "pro", chip, tier, size)
    }

    /** One generation's table, read: [groups] are the sizes ("13-inch, M5"), [rows] each spec per size. */
    data class Gen(val mac: Mac, val chip: Int, val tier: String?, val groups: List<String>, val rows: Map<String, List<String>>, val url: String) {
        /** The value for [label] (or "Group|Label"); sizes that agree show once, otherwise "13-inch: x; 15-inch: y". */
        fun get(label: String): String? {
            val v = find(label)?.map { if (EMPTY.matches(it)) "" else it.trim() } ?: return null
            val clean = v.filter { it.isNotBlank() && !it.equals("No", true) }
            if (clean.isEmpty()) return null
            if (clean.distinct().size == 1 || groups.size != v.size) return clean.first()
            return groups.zip(v).filter { it.second.isNotBlank() }.joinToString("; ") { (g, x) -> "${sizeOf(g)}: $x" }
        }
        fun first(label: String) = find(label)?.firstOrNull { it.isNotBlank() && !EMPTY.matches(it) }
        /** Value per size, for rows built size by size (the screen). */
        fun at(label: String, i: Int) = find(label)?.getOrNull(i)?.takeIf { it.isNotBlank() && !EMPTY.matches(it) }

        /** "Chip Name", "chip name" and "Chip|Chip name" all find the same row; a trailing "*" matches the start ("MSRP*"). */
        private fun find(label: String): List<String>? {
            val l = label.lowercase(Locale.US)
            val prefix = l.endsWith("*")
            val want = l.removeSuffix("*")
            fun ok(key: String): Boolean {
                val k = key.lowercase(Locale.US)
                val test = if (want.contains('|')) k else k.substringAfter('|')
                return if (prefix) test.startsWith(want) else test == want
            }
            return rows.entries.firstOrNull { ok(it.key) }?.value
        }
        val name get() = "${mac.line} (M$chip${tier?.let { " ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""}" + (year?.let { ", $it" } ?: "") + ")"
        val released get() = first("Released")
        val year get() = released?.let { Regex("(20\\d\\d)").find(it)?.value }
    }

    private fun sizeOf(group: String) = Regex("(\\d+)-inch").find(group)?.value ?: group

    // ---------------------------------------------------------------- reading Wikipedia

    private fun api(page: String, extra: String) =
        "https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&redirects=1&page=${Http.enc(page)}&$extra"

    private data class Section(val line: String, val index: Int)

    private fun sections(page: String): List<Section> {
        val arr = Http.getJsonCached(api(page, "prop=sections"))?.optJSONObject("parse")?.optJSONArray("sections") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Section(it.optString("line"), it.optString("index").toIntOrNull() ?: return@let null) } }
    }

    private fun sectionHtml(page: String, index: Int): String? =
        Http.getJsonCached(api(page, "prop=text&section=$index&disablelimitreport=1&disableeditsection=1"))?.optJSONObject("parse")?.optString("text")?.ifBlank { null }

    /** The chip each specs section covers: "M5 models" -> (5, null), "M5 Pro and M5 Max models" -> (5, "pro"), "M1 (2020)" -> (1, null). */
    private fun chipOf(line: String): Pair<Int, String?>? {
        val n = Regex("^M([1-9])\\b").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return n to if (Regex("^M[1-9] (?:Pro|Max)\\b").containsMatchIn(line)) "pro" else null
    }

    /** The generation asked about, or the newest one that's been released. */
    fun generation(mac: Mac, today: LocalDate = LocalDate.now()): Gen? {
        val secs = sections(mac.page)
        val chips = secs.mapIndexedNotNull { i, s -> chipOf(s.line)?.let { Triple(it.first, it.second, i) } }
        if (chips.isEmpty()) return null
        val wantTier = mac.tier?.let { "pro" }
        val order = if (mac.chip != null) chips.filter { it.first == mac.chip }.sortedBy { if (it.second == wantTier) 0 else 1 }
            else chips.filter { it.second == null }.sortedByDescending { it.first }
        for ((chip, tier, i) in order) {
            // A section can hold its table itself or in the next one ("M1 (2020)" -> "Technical specifications").
            for (j in listOf(i, i + 1, i + 2)) {
                val s = secs.getOrNull(j) ?: break
                if (j > i && chipOf(s.line) != null) break
                val html = sectionHtml(mac.page, s.index) ?: continue
                val gen = parse(mac, chip, tier, html) ?: continue
                if (mac.chip == null && !released(gen, today)) break
                return pick(gen, mac)
            }
        }
        return null
    }

    private val DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", us)

    /** False for a table that's announced but not out yet (or has no date), so "newest" skips it. */
    private fun released(g: Gen, today: LocalDate): Boolean {
        val r = g.released ?: return false
        val d = runCatching { LocalDate.parse(Regex("[A-Z][a-z]+ \\d{1,2}, \\d{4}").find(r)?.value, DATE) }.getOrNull() ?: return Regex("\\b20\\d\\d\\b").containsMatchIn(r)
        return !d.isAfter(today)
    }

    /** Only the sizes and chip tier asked about ("14-inch, M5 Max"), when the table has them. */
    private fun pick(g: Gen, mac: Mac): Gen {
        val keep = g.groups.indices.filter { i ->
            val h = g.groups[i].lowercase(us)
            (mac.size == null || h.contains("${mac.size}-inch")) && (mac.tier == null || h.contains("m${g.chip} ${mac.tier}"))
        }
        if (keep.isEmpty() || keep.size == g.groups.size) return g.copy(tier = mac.tier ?: g.tier)
        return g.copy(tier = mac.tier, groups = keep.map { g.groups[it] }, rows = g.rows.mapValues { (_, v) -> if (v.size == g.groups.size) keep.map { v[it] } else v })
    }

    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<(th|td)([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)

    /** Reads a specs table: the size columns from the header, then each row's values per size (options joined with " / "). */
    fun parse(mac: Mac, chip: Int, tier: String?, html: String): Gen? {
        // The specs table is the one with size columns and a release date (a section can open with a smaller table).
        val table = Regex("<table[^>]*wikitable.*?</table>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }
            .firstOrNull { it.contains("scope=\"col\"") && it.contains("Released") } ?: return null
        val groups = ArrayList<Pair<String, Int>>()
        val rows = LinkedHashMap<String, List<String>>()
        var group = ""
        var last: String? = null
        for (tr in ROW.findAll(table)) {
            val cells = CELL.findAll(tr.groupValues[1]).map { Triple(it.groupValues[1], it.groupValues[2], text(it.groupValues[3])) }.toList()
            if (cells.isEmpty()) continue
            if (groups.isEmpty()) {
                // The header: "Model" then one column per size and chip.
                val heads = cells.filter { it.first == "th" && it.second.contains("scope=\"col\"") }
                if (heads.isNotEmpty()) { heads.forEach { groups += it.third to span(it.second) }; continue }
            }
            if (groups.isEmpty()) continue
            val ths = cells.filter { it.first == "th" }
            for (th in ths) if (th.second.contains("rowspan") && !th.second.contains("rowspan=\"1\"")) group = th.third
            val label = ths.lastOrNull()?.third?.takeIf { it.isNotBlank() && !(ths.last().second.contains("rowspan") && !ths.last().second.contains("rowspan=\"1\"")) }
            val tds = cells.filter { it.first == "td" }
            if (tds.isEmpty()) continue
            val total = groups.sumOf { it.second }
            val cols = ArrayList<String>()
            for (td in tds) repeat(span(td.second).coerceAtLeast(1)) { cols += td.third }
            while (cols.size < total) cols += ""
            // One value per size: that size's columns, options in order.
            var at = 0
            val perGroup = groups.map { (_, w) -> cols.subList(at, (at + w).coerceAtMost(cols.size)).also { at += w }.filter { it.isNotBlank() }.distinct().joinToString(" / ") }
            val key = when {
                label != null -> "$group|$label"
                last != null -> last  // a label-less row continues the one above ("24 GB / 32 GB" more memory options)
                else -> continue
            }
            rows[key] = if (label == null && rows[key] != null) rows[key]!!.zip(perGroup).map { (a, b) -> listOf(a, b).filter { it.isNotBlank() }.joinToString(" / ") } else perGroup
            last = key
        }
        if (groups.isEmpty() || rows.isEmpty()) return null
        return Gen(mac, chip, tier, groups.map { it.first }, rows, "https://en.wikipedia.org/wiki/${mac.page.replace(' ', '_')}")
    }

    private fun span(attrs: String) = Regex("colspan=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun text(html: String): String = html
        .replace(Regex("<sup[^>]*class=\"[^\"]*reference[^\"]*\"[^>]*>.*?</sup>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<span[^>]*class=\"[^\"]*(?:noprint|sortkey)[^\"]*\"[^>]*>.*?</span>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<li[^>]*>"), ", ").replace(Regex("<br\\s*/?>"), ", ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace("&amp;", "&").replace("&#8211;", "-").replace("&#215;", "x").replace("×", "x").replace("–", "-")
        .replace(Regex("\\[[a-z0-9 ]+]"), "").replace(Regex("\\s+"), " ").replace(Regex("^[, ]+|[, ]+$"), "").replace(Regex("(, )+"), ", ").trim()

    // ---------------------------------------------------------------- the card

    private fun money(v: String?): String? = v?.let { Regex("\\$\\s?([\\d,]+)").findAll(it).mapNotNull { m -> m.groupValues[1].replace(",", "").toIntOrNull() }.toList() }
        ?.takeIf { it.isNotEmpty() }?.let { p -> if (p.min() == p.max()) "$" + "%,d".format(us, p.min()) else "$" + "%,d".format(us, p.min()) + " to $" + "%,d".format(us, p.max()) }

    /** "16 GB / 24 GB, up to 32 GB": the sizes sold, plus build-to-order ones from the row under it. */
    private fun options(g: Gen, group: String, label: String): String? {
        val base = g.get("$group|$label") ?: g.get(label) ?: return null
        val extra = g.get("$group|Online configuration")?.let { o -> Regex("(\\d+) ?(GB|TB)").findAll(o).map { it.value.replace(" ", "") }.toList() }.orEmpty()
        fun gb(s: String) = Regex("(\\d+) ?(GB|TB)").find(s)?.let { (it.groupValues[1].toIntOrNull() ?: 0) * if (it.groupValues[2] == "TB") 1024 else 1 } ?: 0
        val top = (Regex("(\\d+) ?(GB|TB)").findAll(base).map { it.value } + extra).maxByOrNull { gb(it) }
        val baseTop = Regex("(\\d+) ?(GB|TB)").findAll(base).map { it.value }.maxByOrNull { gb(it) }
        return if (top != null && baseTop != null && gb(top) > gb(baseTop)) "$base, up to ${top.replace(Regex("(\\d)(GB|TB)"), "$1 $2")}" else base
    }

    /** The screen per size: "13.6 in, 2560 x 1664 or 15.3 in, 2880 x 1864", then the refresh rate. */
    private fun screen(g: Gen): String? {
        val sizes = g.groups.indices.mapNotNull { i ->
            g.at("Size|Diagonal", i)?.substringBefore(" (")?.let { d -> listOfNotNull(d, g.at("Resolution|Vertical-by-horizontal", i)).joinToString(", ") }
        }.distinct()
        if (sizes.isEmpty()) return null
        return sizes.joinToString(" or ") + (g.get("Refresh rate|Maximum")?.let { ", up to $it" } ?: "")
    }

    private fun cooling(g: Gen): String? = g.get("Cooling system|Number of fan(s)")?.let { n -> if (n.trim() == "1") "1 fan" else "$n fans" }
        ?: g.get("Cooling System")

    /** The card's rows, in reading order. */
    fun facts(g: Gen): List<Pair<String, String>> = listOfNotNull(
        g.released?.let { "OUT" to it },
        money(g.get("MSRP*"))?.let { "PRICE" to it },
        g.get("Chip name")?.let { "CHIP" to it },
        listOfNotNull(g.get("Total CPU cores")?.let { "$it cores" }, g.get("High-performance cores")?.let { "$it performance" }, g.get("Energy-efficiency cores")?.let { "$it efficiency" })
            .takeIf { it.isNotEmpty() }?.let { "CPU" to it.joinToString(", ") },
        g.get("Total GPU cores")?.let { "GPU" to "$it cores" },
        options(g, "Unified memory", "Memory size")?.let { "RAM" to it },
        options(g, "Storage", "Storage size")?.let { "STORAGE" to it },
        screen(g)?.let { "SCREEN" to it },
        g.get("SDR max brightness")?.let { "BRIGHT" to it + (g.get("XDR max brightness")?.let { x -> "; HDR $x" } ?: "") },
        g.get("Battery")?.let { "BATTERY" to it.replace("W·h", "Wh") },
        battery(g)?.let { "LASTS" to it },
        g.get("USB-C/Thunderbolt")?.let { "PORTS" to listOfNotNull(it, g.get("HDMI")?.let { h -> h.takeIf { it.startsWith("HDMI") } ?: "HDMI" }, g.get("SDXC card slot")?.let { "SD card" }).joinToString(", ") },
        g.get("Weight")?.let { "WEIGHT" to it },
        cooling(g)?.let { "COOLING" to it },
        g.get("Wi-Fi")?.let { "WI-FI" to it },
        g.get("Camera|Resolution")?.let { "CAMERA" to it },
    )

    /** "Up to 18 hours of video" from a battery-life row, when the table has one. */
    private fun battery(g: Gen): String? = g.rows.keys.firstOrNull { k -> Regex("(?i)hour").containsMatchIn(g.get(k).orEmpty()) && Regex("(?i)battery|video|web|life").containsMatchIn(k) }?.let { g.get(it) }

    private fun num(s: String?): Double? = s?.let { Regex("(\\d+(?:\\.\\d+)?)").findAll(it).mapNotNull { m -> m.value.toDoubleOrNull() }.maxOrNull() }
    private fun low(s: String?): Double? = s?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.value?.toDoubleOrNull() }

    /** Short, spoken: which model it is and its headline specs. */
    fun answer(g: Gen, want: TechWant, asked: Mac): String {
        val n = g.name.substringBefore(" (") + " M${g.chip}" + (g.tier?.let { " ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: "")
        val price = money(g.get("MSRP*"))
        return when (want) {
            TechWant.BATTERY -> battery(g)?.let { "The $n lasts ${lowerFirst(it)}." } ?: g.get("Battery")?.let { "The $n has a $it watt-hour battery." } ?: overview(g, n, price, asked)
            TechWant.DISPLAY, TechWant.REFRESH -> g.get("Size|Diagonal")?.let { "The $n has a $it screen" + (g.get("Refresh Rate|Maximum")?.let { r -> " at up to $r" } ?: "") + "." } ?: overview(g, n, price, asked)
            TechWant.CHIP -> "The $n runs the ${g.get("Chip Name") ?: "Apple M${g.chip}"}" + (g.get("Total CPU Cores")?.let { ", with $it CPU cores" } ?: "") + "."
            TechWant.MEMORY -> listOfNotNull(g.get("Memory Size")?.let { "${it.replace(" / ", " or ")} of memory" }, g.get("Storage Size")?.let { "${it.replace(" / ", " or ")} of storage" })
                .takeIf { it.isNotEmpty() }?.let { "The $n comes with ${it.joinToString(" and ")}." } ?: overview(g, n, price, asked)
            TechWant.PORT -> g.get("USB-C/Thunderbolt")?.let { "The $n has ${lowerFirst(it)}." } ?: overview(g, n, price, asked)
            TechWant.DESIGN -> g.get("Weight")?.let { "The $n weighs $it." } ?: overview(g, n, price, asked)
            else -> overview(g, n, price, asked)
        }
    }

    private fun overview(g: Gen, n: String, price: String?, asked: Mac): String {
        val lead = if (asked.chip == null) "The newest ${g.mac.line} is the M${g.chip} model" + (g.released?.let { ", out $it" } ?: "") + "." else "The $n came out ${g.released ?: "recently"}."
        val bits = listOfNotNull(g.get("Total CPU Cores")?.let { "a $it-core CPU" }, g.get("Memory Size")?.let { "${it.substringBefore(" /")} or more of memory" },
            price?.let { "from ${it.substringBefore(" to")}" })
        return lead + if (bits.isEmpty()) "" else " It has ${bits.joinToString(", ")}."
    }

    private fun lowerFirst(s: String) = if (s.length > 1 && s[1].isLowerCase()) s.replaceFirstChar { it.lowercase(us) } else s

    suspend fun find(ask: TechAsk): LookupResult? {
        val macs = ask.devices.map { match(it) ?: return null }
        val gens = macs.map { generation(it) ?: return null }
        val image = runCatching { PcParts.pageImage(gens[0].mac.page) }.getOrNull()
        if (gens.size == 2) {
            val (a, b) = gens
            val v = versus(a, b)
            val said = Tech.verdict(a.name.substringBefore(" ("), b.name.substringBefore(" ("), v.bars)
            return LookupResult(v.a, null, null, "", image, a.url, emptyList(), v.verdict.ifBlank { said },
                sources = listOf("Wikipedia"), links = listOf(b.name to b.url), versus = v)
        }
        val g = gens[0]
        val notes = buildList {
            if (macs[0].chip == null) add("Asked about a ${g.mac.line} with no chip, so this is the newest model. Say \"${g.mac.line.lowercase(us)} m${g.chip - 1}\" for the one before.")
            if (g.groups.size > 1) add("Sizes: " + g.groups.joinToString(", "))
        }
        return LookupResult(g.name, "Apple laptop", null, "", image, g.url, facts(g), answer(g, ask.want, macs[0]),
            sources = listOf("Wikipedia"), heading = "TECH", more = if (notes.isEmpty()) emptyList() else listOf("NOTE" to notes))
    }

    /** "MacBook Air M5", "MacBook Pro M5 Max". */
    private fun short(g: Gen) = g.mac.line + " M${g.chip}" + (g.tier?.let { " ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: "")

    /** Two MacBooks head to head, each at its smallest size (the base model). */
    fun versus(a: Gen, b: Gen): Versus {
        fun base(g: Gen, label: String) = g.at(label, 0) ?: g.first(label)
        fun top(s: String?) = s?.let { Regex("(\\d+(?:\\.\\d+)?)").findAll(it).mapNotNull { m -> m.value.toDoubleOrNull() }.maxOrNull() }
        fun first(s: String?) = s?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.value?.toDoubleOrNull() }
        fun wh(g: Gen) = base(g, "Battery")?.let { Regex("(\\d+(?:\\.\\d+)?) ?W").find(it)?.groupValues?.get(1)?.toDoubleOrNull() ?: first(it) }
        fun mem(g: Gen) = options(g, "Unified memory", "Memory size")?.let { s ->
            Regex("(\\d+) ?(GB|TB)").findAll(s).map { (it.groupValues[1].toDoubleOrNull() ?: 0.0) * if (it.groupValues[2] == "TB") 1024 else 1 }.maxOrNull() }
        fun price(g: Gen) = money(g.get("MSRP*"))?.let { first(it.replace(",", "").removePrefix("$")) }
        fun fmt(d: Double?) = d?.let { if (it % 1.0 == 0.0) "%,d".format(us, it.toLong()) else it.toString() }
        val bars = listOf(
            Versus.Bar("CPU CORES", first(base(a, "Total CPU cores")), first(base(b, "Total CPU cores")), fmt(first(base(a, "Total CPU cores"))), fmt(first(base(b, "Total CPU cores"))), "more CPU cores"),
            Versus.Bar("GPU CORES (UP TO)", top(base(a, "Total GPU cores")), top(base(b, "Total GPU cores")), fmt(top(base(a, "Total GPU cores"))), fmt(top(base(b, "Total GPU cores"))), "more GPU cores"),
            Versus.Bar("MEMORY (UP TO)", mem(a), mem(b), mem(a)?.let { "${fmt(it)} GB" }, mem(b)?.let { "${fmt(it)} GB" }, "more memory"),
            Versus.Bar("BATTERY", wh(a), wh(b), wh(a)?.let { "${fmt(it)} Wh" }, wh(b)?.let { "${fmt(it)} Wh" }, "a bigger battery"),
            Versus.Bar("BRIGHTNESS (SDR)", first(base(a, "SDR max brightness")), first(base(b, "SDR max brightness")), first(base(a, "SDR max brightness"))?.let { "${fmt(it)} nits" }, first(base(b, "SDR max brightness"))?.let { "${fmt(it)} nits" }, "a brighter screen"),
            Versus.Bar("REFRESH", first(a.get("Refresh rate|Maximum")), first(b.get("Refresh rate|Maximum")), a.get("Refresh rate|Maximum"), b.get("Refresh rate|Maximum"), "a smoother screen"),
            Versus.Bar("WEIGHT", first(base(a, "Weight")), first(base(b, "Weight")), base(a, "Weight")?.substringBefore(" ("), base(b, "Weight")?.substringBefore(" ("), "less weight", lower = true),
            Versus.Bar("PRICE (FROM)", price(a), price(b), price(a)?.let { "$" + fmt(it) }, price(b)?.let { "$" + fmt(it) }, "a lower price", lower = true),
        ).filter { it.a != null && it.b != null }
        val words = listOf(
            Versus.Words("CHIP", a.get("Chip name"), b.get("Chip name")),
            Versus.Words("SCREEN", screen(a), screen(b)),
            Versus.Words("PORTS", a.get("USB-C/Thunderbolt")?.substringBefore(" supporting"), b.get("USB-C/Thunderbolt")?.substringBefore(" supporting")),
            Versus.Words("COOLING", cooling(a), cooling(b)),
            Versus.Words("OUT", a.released, b.released),
        ).filter { it.a != null || it.b != null }
        return Versus(a.name, b.name, bars, words, Tech.verdict(short(a), short(b), bars))
    }

}
