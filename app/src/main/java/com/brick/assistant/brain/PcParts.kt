package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** The kinds of PC parts Brick reads specs for. */
enum class PartKind(val label: String, val imagePage: String) {
    CPU("Processor", "Central processing unit"),
    GPU("Graphics card", "Graphics card"),
    SSD("SSD", "Solid-state drive"),
    RAM("Memory kit", "DIMM"),
    BOARD("Motherboard", "Motherboard"),
    PSU("Power supply", "Power supply unit (computer)"),
}

/** A number that can be compared: [tag] says what winning means ("more VRAM"); [lower] when smaller wins. */
data class PartNum(val label: String, val value: Double, val show: String, val tag: String?, val lower: Boolean = false)

/** One part's spec sheet. */
data class Part(
    val kind: PartKind, val name: String, val description: String?, val image: String?, val url: String,
    val rows: List<Pair<String, String>>, val nums: List<PartNum>, val words: List<Pair<String, String>>,
    val notes: List<Pair<String, List<String>>>, val answer: String, val source: String,
)

/**
 * PC parts: CPUs and graphics cards from Wikipedia's spec tables ("List of AMD Ryzen processors",
 * "GeForce RTX 50 series"), SSDs from a table of popular drives (makers' rated speeds), memory kits
 * from their names ("DDR5-6000 CL30"), motherboards by chipset, and power supplies by series and wattage.
 */
object PcParts {
    private val us = Locale.US
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ names

    private const val SSD_NAMES = "(?:samsung )?(?:9100|990|980|970|870|860) (?:pro|evo plus|evo|qvo)(?: plus)?|" +
        "(?:wd |western digital )?(?:black |blue |green )?sn\\d{3,4}x?|(?:crucial )?t70[05]|crucial (?:t500|p3 plus|p3|p5 plus|p310|mx500|bx500)|" +
        "(?:kingston )?(?:kc3000|fury renegade(?! ddr)(?: ssd)?)|kingston nv[123]|(?:sk hynix )?platinum p[45]1|(?:solidigm )?p44 pro|(?:seagate )?firecuda 5[34]0|" +
        "(?:sabrent )?rocket 4 plus|(?:lexar )?nm790|(?:teamgroup )?mp44"
    private const val RAM_NAMES = "(?:\\d{1,3} ?gb )?ddr[345][- ]?\\d{4}|(?:\\d{1,3} ?gb )?ddr[345] cl ?\\d{2}|\\d{1,3} ?gb ddr[345]|" +
        "(?:corsair |g ?skill |kingston |crucial |teamgroup |patriot |adata )?(?:vengeance|dominator|trident z5?|ripjaws|flare x5|fury beast|t ?create|xpg lancer)(?: (?:rgb|neo|pro|s5|v|titanium|platinum))*(?: \\d{1,3} ?gb)?(?: ddr[345])?"
    private const val CHIPSETS = "a520|a620|b450|b550|b650e?|b840|b850|x570|x670e?|x870e?|h610|h670|h770|b660|b760|z690|z790|h810|b860|z890|x299|trx50|wrx90"
    private const val BOARD_NAMES = "(?:(?:asus|msi|gigabyte|asrock|nzxt|rog|strix|tuf|prime|mag|mpg|meg|aorus|gaming|pro) )*(?:$CHIPSETS)"
    private const val PSU_NAMES = "(?:corsair )?(?:rmx?|rme|rmi|hxi?|axi?|cxm?|sf)\\d{3,4}[xie]?|(?:seasonic )?(?:focus|prime|vertex|core) (?:gx|px|tx|gm|gc|gold|platinum|titanium)[- ]?\\d{3,4}|" +
        "(?:be quiet )?(?:pure power|straight power|dark power) \\d{1,2}(?: m)?(?: \\d{3,4} ?w)?|(?:evga )?supernova \\d{3,4}(?: (?:[gpt]\\d|gt|ga|g\\+))?|(?:msi )?mag a\\d{3,4}(?:gl|bn|gf)|" +
        "(?:thermaltake )?toughpower gf\\d? \\d{3,4}|(?:cooler master )?mwe (?:gold |bronze )?\\d{3,4}|\\d{3,4} ?(?:w|watts?)(?: 80 plus)?(?: (?:bronze|silver|gold|platinum|titanium))? (?:psu|power supply)"
    private const val CPU_NAMES = "i[3579][- ]\\d{4,5}[a-z]{0,2}|xeon|epyc|\\d{4}x3d|\\d{4,5}(?:kf|ks|k)(?![a-z0-9])"

    /** Part names for [Tech]'s device finder. */
    const val ANCHORS = "$SSD_NAMES|$RAM_NAMES|$BOARD_NAMES|$PSU_NAMES|$CPU_NAMES"

    private val GPU_RX = r("\\b(?:rtx|gtx|geforce|radeon|rx ?\\d{3,4}|arc [ab]\\d{3}|titan)\\b")
    private val CPU_RX = r("\\b(?:ryzen|threadripper|core (?:i[3579]|ultra)|$CPU_NAMES|athlon|pentium|celeron)\\b")
    private val SSD_RX = r("(?<![a-z0-9])(?:$SSD_NAMES)(?![a-z0-9])")
    private val RAM_RX = r("(?<![a-z0-9])(?:$RAM_NAMES)(?![a-z0-9])|\\bddr[345]\\b")
    private val BOARD_RX = r("(?<![a-z0-9])(?:$CHIPSETS)(?![0-9])")
    private val PSU_RX = r("(?<![a-z0-9])(?:$PSU_NAMES)(?![a-z0-9])")

    /** What kind of part [name] is, or null for phones, consoles and the rest. */
    fun kind(name: String): PartKind? {
        val n = name.lowercase(us)
        return when {
            RAM_RX.containsMatchIn(n) && Regex("\\bddr|vengeance|dominator|trident|ripjaws|flare|fury beast|t ?create|lancer").containsMatchIn(n) -> PartKind.RAM
            SSD_RX.containsMatchIn(n) -> PartKind.SSD
            PSU_RX.containsMatchIn(n) -> PartKind.PSU
            GPU_RX.containsMatchIn(n) -> PartKind.GPU
            CPU_RX.containsMatchIn(n) -> PartKind.CPU
            BOARD_RX.containsMatchIn(n) -> PartKind.BOARD
            else -> null
        }
    }

    /** "ddr5" alone is the memory type (a tech word), not a kit. */
    fun isBareType(name: String) = Regex("^ddr[345]$|^\\d{1,3} ?gb$").matches(name.trim().lowercase(us))

    /** "rtx 5060 ti" -> "5060ti", "ryzen 7 9800x3d" -> "9800x3d", "i9-14900k" -> "14900k": the model as Wikipedia's tables name it. */
    fun key(said: String): String? {
        val t = said.lowercase(us).replace(Regex("[-_]"), " ").split(' ').filter { it.isNotEmpty() }
        val i = t.indices.lastOrNull { k -> Regex("\\d").containsMatchIn(t[k]) && t[k].length >= 3 && !Regex("^i[3579]$|^\\d$").matches(t[k]) } ?: return null
        val tail = t.drop(i + 1).takeWhile { it in setOf("ti", "super", "xt", "xtx", "x3d", "gre", "d") }
        return (t[i] + tail.joinToString("")).replace(Regex("[^a-z0-9]"), "")
    }

    private val CELL_DROP = Regex("\\b(?:geforce|nvidia|rtx|gtx|radeon|amd|intel|arc|ryzen|threadripper|core|ultra|processor|graphics|i[3579]|[3579])\\b")
    private fun cellKey(s: String) = s.lowercase(us).replace(Regex("\\[[^]]*]"), " ").replace(CELL_DROP, " ").replace(Regex("[^a-z0-9]"), "")

    /** A part's name the way it's written ("Ryzen 7 9800X3D", "GeForce RTX 5090", "Samsung 990 Pro"). */
    fun pretty(said: String): String {
        val base = Tech.canonical(said)
        return base.split(' ').joinToString(" ") { w ->
            when {
                Regex("^(?:i[3579])-?\\d{4,5}[a-z]*$", RegexOption.IGNORE_CASE).matches(w) -> w.lowercase(us).replace(Regex("^(i[3579])-?"), "$1-").let { it.substringBefore('-') + "-" + it.substringAfter('-').uppercase(us) }
                Regex("\\d").containsMatchIn(w) && Regex("[a-z]", RegexOption.IGNORE_CASE).containsMatchIn(w) && !w.endsWith("GB", true) -> w.uppercase(us)
                w.equals("ddr5", true) || w.equals("ddr4", true) -> w.uppercase(us)
                else -> w
            }
        }.replace(Regex("^Rtx "), "GeForce RTX ").replace(Regex("^Ryzen (\\d) "), "Ryzen $1 ")
            .replace(Regex("^(i[3579]-)"), "Core $1")
    }

    // ------------------------------------------------------------------ finding

    suspend fun find(said: String): Part? = withContext(Dispatchers.IO) {
        when (kind(said)) {
            PartKind.CPU, PartKind.GPU -> fromWikipedia(said)
            PartKind.SSD -> ssd(said)?.withImage()
            PartKind.RAM -> ram(said)?.withImage()
            PartKind.BOARD -> board(said)?.withImage()
            PartKind.PSU -> psu(said)?.withImage()
            null -> null
        }
    }

    private fun wiki(url: String) = Http.getJsonCached(url)

    /** The picture of this kind of part (an SSD, a RAM stick...), from its Wikipedia article. */
    private fun Part.withImage() = copy(image = image ?: imageOf(summary(kind.imagePage)))

    private fun summary(title: String) = wiki("https://en.wikipedia.org/api/rest_v1/page/summary/${Http.enc(title.replace(' ', '_'))}")
        ?.takeIf { it.optString("type") == "standard" }

    private fun imageOf(j: org.json.JSONObject?) = j?.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }

    /** The lead picture of a Wikipedia article (blocking; call off the main thread). */
    fun pageImage(title: String): String? = imageOf(summary(title))

    private fun pageHtml(title: String): String? = wiki("https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&prop=text&redirects=1&page=${Http.enc(title)}")
        ?.optJSONObject("parse")?.optString("text")?.ifBlank { null }

    /** The pages to look in: the name's own redirect (an RTX card leads to its series), then a search, lists and series first. */
    private fun candidates(said: String, kind: PartKind): List<String> {
        val out = ArrayList<String>()
        val name = pretty(said)
        summary(name)?.optString("title")?.ifBlank { null }?.let { out += it }
        if (out.isNotEmpty()) return out
        val q = when {
            kind == PartKind.CPU && Regex("^\\d{4}x3d|^\\d{4}x$", RegexOption.IGNORE_CASE).containsMatchIn(said.trim()) -> "Ryzen $name"
            kind == PartKind.CPU && Regex("^\\d{4,5}(?:k|kf|ks)$", RegexOption.IGNORE_CASE).matches(said.trim()) -> "Intel Core $name"
            else -> name
        }
        val hits = wiki("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=6&srprop=&srsearch=${Http.enc(q)}")
            ?.optJSONObject("query")?.optJSONArray("search") ?: return out
        val titles = (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optString("title") }
        val key = key(said) ?: return titles.take(2)
        // The model's own article, then "List of ..." and "... series" pages, then the rest.
        return titles.sortedBy { t ->
            when {
                cellKey(t).endsWith(key) -> 0
                t.startsWith("List of", true) -> 1
                t.contains("series", true) -> 2
                else -> 3
            }
        }.take(2)
    }

    private suspend fun fromWikipedia(said: String): Part? {
        val kind = kind(said) ?: return null
        val key = key(said) ?: return null
        for (title in candidates(said, kind)) {
            val html = pageHtml(title) ?: continue
            val own = cellKey(title).endsWith(key)
            val rows = (if (own) Tech.infobox(html).takeIf { it.size >= 4 } else null) ?: modelRow(html, key) ?: continue
            val sum = summary(title)
            val image = imageOf(sum) ?: imageOf(summary(if (kind == PartKind.GPU) brandPage(said) else cpuBrandPage(said)))
            return build(kind, pretty(said), sum?.optString("description")?.ifBlank { null }?.let { if (own) it else "From ${sum.optString("title", title)}" },
                image, sum?.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "https://en.wikipedia.org/wiki/${Http.enc(title.replace(' ', '_'))}", rows)
        }
        return null
    }

    private fun brandPage(s: String) = when {
        r("radeon|\\brx").containsMatchIn(s) -> "Radeon"
        r("\\barc\\b").containsMatchIn(s) -> "Intel Arc"
        else -> "GeForce"
    }
    private fun cpuBrandPage(s: String) = if (r("ryzen|threadripper|x3d|epyc|athlon").containsMatchIn(s)) "Ryzen" else "Intel Core"

    // ------------------------------------------------------------------ Wikipedia's spec tables

    /** One table cell: its text and whether it's a heading. */
    data class Cell(val text: String, val head: Boolean)

    private val TABLE = Regex("<table[^>]*class=\"[^\"]*wikitable[^\"]*\"[^>]*>(.*?)</table>", RegexOption.DOT_MATCHES_ALL)
    private val TR = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<(th|td)(\\s[^>]*)?>(.*?)</(?:th|td)>", RegexOption.DOT_MATCHES_ALL)

    private fun text(h: String): String = h
        // Footnote marks go; other superscripts ("mm²") stay.
        .replace(Regex("<sup[^>]*class=\"[^\"]*(?:reference|noprint|cite)[^\"]*\"[^>]*>.*?</sup>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<sup[^>]*>\\s*(?:\\[[^<]*]|<a[^>]*>\\s*\\[[^<]*]\\s*</a>)\\s*</sup>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<sup>2</sup>"), "²")
        .replace(Regex("<span[^>]*(?:display: ?none|visibility: ?hidden)[^>]*>[^<]*</span>"), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " / ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&#8201;", " ").replace("&thinsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("[\\s\\u00a0\\u202f\\u2009]+"), " ").trim().trim('/', ' ')

    /** A table as a grid, with row and column spans filled in. */
    fun grid(table: String): List<List<Cell?>> {
        val body = table.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        val out = ArrayList<List<Cell?>>()
        val pending = HashMap<Pair<Int, Int>, Cell>()
        TR.findAll(body).forEachIndexed { rIdx, tr ->
            val row = ArrayList<Cell?>()
            fun fill() { while (pending.containsKey(rIdx to row.size)) row += pending.remove(rIdx to row.size) }
            for (m in CELL.findAll(tr.groupValues[1])) {
                fill()
                val attrs = m.groupValues[2]
                val rs = Regex("rowspan=\"?(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 60) ?: 1
                val cs = Regex("colspan=\"?(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 60) ?: 1
                val cell = Cell(text(m.groupValues[3]), m.groupValues[1] == "th")
                repeat(cs) {
                    for (d in 1 until rs) pending[(rIdx + d) to row.size] = cell
                    row += cell
                }
            }
            fill()
            val last = pending.keys.filter { it.first == rIdx }.maxOfOrNull { it.second }
            if (last != null) while (row.size <= last) row += pending.remove(rIdx to row.size)
            out += row
        }
        return out
    }

    private val SKIP_LABEL = r("^(?:model|models|branding|brand|name|sku|series|product|codename|code name|marketing name|model number|ref|refs|references|notes?)$")

    /**
     * The specs for the model [key] from the first table that has it: as (label, value) pairs.
     * Tables list models down the side ("List of AMD Ryzen processors") or across the top ("GeForce RTX 50 series").
     */
    fun modelRow(html: String, key: String): List<Pair<String, String>>? {
        val digits = Regex("\\d{3,}").find(key)?.value ?: key
        for (t in TABLE.findAll(html)) {
            if (!t.value.contains(digits, true)) continue
            val g = grid(t.groupValues[1])
            if (g.size < 2) continue
            val headRows = g.indexOfFirst { row -> row.any { it != null && !it.head } }.let { if (it < 0) g.size else it }
            var hit: Pair<Int, Int>? = null
            loop@ for (ri in g.indices) for (ci in g[ri].indices) {
                val c = g[ri][ci] ?: continue
                if (cellKey(c.text) == key) { hit = ri to ci; break@loop }
            }
            val (hr, hc) = hit ?: continue
            val out = ArrayList<Pair<String, String>>()
            fun add(label: String, value: String?) {
                val l = label.trim(); val v = value?.trim().orEmpty()
                if (l.isEmpty() || v.isEmpty() || v == "—" || v == "-" || v == "N/A" || SKIP_LABEL.matches(l) || cellKey(v) == key) return
                if (out.none { it.first == l }) out += l to v
            }
            if (hr < headRows) {
                // Models across the top: each row below is one spec.
                for (ri in hr + 1 until g.size) {
                    val row = g[ri]
                    val label = row.takeWhile { it?.head == true }.mapNotNull { it?.text }.distinct().joinToString(" ")
                    add(label, row.getOrNull(hc)?.takeIf { !it.head }?.text)
                }
            } else {
                // Models down the side: the headings above each column name the spec.
                val row = g[hr]
                for (ci in row.indices) {
                    if (ci == hc) continue
                    val label = (0 until headRows).mapNotNull { g[it].getOrNull(ci)?.text }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
                    add(label, row[ci]?.text)
                }
            }
            if (out.size >= 3) return out
        }
        return null
    }

    // ------------------------------------------------------------------ CPU and GPU card

    /** A heading pattern and the card's short label for it; a null label leaves the row off the card. */
    private class Rule(val rx: Regex, val label: String?)
    private val GPU_RULES = listOf(
        Rule(r("tflops|fillrate|fill rate|gflops|fp64|fp16"), null), Rule(r("branding|^model"), null),
        Rule(r("cuda cores|stream processors|shading units|shaders|xe cores|core config"), "CORES"),
        Rule(r("boost"), "BOOST"), Rule(r("base|core clock|game clock"), "BASE"),
        Rule(r("memory.*(?:size|capacity)|^size|vram|memory \\(gb\\)|memory \\(gib\\)"), "VRAM"),
        Rule(r("bus width"), "BUS"), Rule(r("bandwidth"), "BANDWID"), Rule(r("memory.*clock|memory.*speed"), "MEM SPD"),
        Rule(r("memory.*type|^type|dram type"), "MEMORY"),
        Rule(r("interface.*power|power connector|connector"), "PLUG"), Rule(r("display"), "OUTPUTS"),
        Rule(r("tdp|tbp|board power|tgp|^power"), "POWER"),
        Rule(r("price|msrp"), "PRICE"), Rule(r("launch|release"), "RELEASE"),
        Rule(r("ray tracing|rt cores|ray accelerators"), "RT"), Rule(r("tensor|ai accelerators|matrix"), "AI CORES"),
        Rule(r("texture"), "TMUS"), Rule(r("render output|rops"), "ROPS"), Rule(r("streaming multi|compute units|\\bcus?\\b"), "UNITS"),
        Rule(r("\\bl1\\b"), null), Rule(r("l2|infinity cache|cache"), "CACHE"), Rule(r("process|fab"), "PROCESS"), Rule(r("transistor"), "TRANSIS"),
        Rule(r("die size"), "DIE"), Rule(r("\\bdie\\b|chip|gpu"), "CHIP"), Rule(r("interface|host|pcie"), "PCIE"),
    )
    private val CPU_RULES = listOf(
        Rule(r("branding|^model"), null),
        // Intel's hybrid chips list P-cores and E-cores in separate columns.
        Rule(r("^p-core.*cores"), "P-CORES"), Rule(r("^e-core.*cores"), "E-CORES"),
        Rule(r("^e-core.*(?:turbo|boost)"), "E BOOST"), Rule(r("^e-core.*base"), "E BASE"),
        Rule(r("tvb|thermal velocity"), "TVB"),
        Rule(r("tdp.*(?:max|turbo)|max.*turbo power|^mtp"), "MAX TDP"), Rule(r("tdp|ppt|pbp"), "TDP"),
        Rule(r("cores.*threads|cores \\(threads\\)|^cores|core count|\\(c/t\\)"), "CORES"), Rule(r("threads"), "THREADS"),
        Rule(r("p-?cores|performance cores"), "P-CORES"), Rule(r("e-?cores|efficient cores"), "E-CORES"),
        Rule(r("boost|turbo|max"), "BOOST"), Rule(r("base"), "BASE"),
        Rule(r("l3"), "L3"), Rule(r("l2"), "L2"), Rule(r("cache"), "CACHE"),
        Rule(r("tdp|power|ppt|pbp|mtp"), "TDP"), Rule(r("socket"), "SOCKET"),
        Rule(r("price|msrp|rcp"), "PRICE"), Rule(r("release|launch"), "RELEASE"),
        Rule(r("thermal solution|cooler"), "COOLER"), Rule(r("chiplets"), "CHIPLET"), Rule(r("core config"), "LAYOUT"),
        Rule(r("gpu|graphics"), "IGPU"), Rule(r("memory"), "MEMORY"), Rule(r("process|node|fab"), "PROCESS"),
        Rule(r("architecture|microarch"), "ARCH"), Rule(r("pcie"), "PCIE"),
    )

    private fun firstNum(s: String) = Regex("(\\d[\\d,]*(?:\\.\\d+)?)").find(s)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    private fun maxNum(s: String) = Regex("(\\d[\\d,]*(?:\\.\\d+)?)").findAll(s).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.maxOrNull()
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) "%,d".format(us, d.toLong()) else d.toString()

    /** A CPU or graphics card's card: friendly labels for Wikipedia's headings, and the numbers to compare. */
    fun build(kind: PartKind, name: String, description: String?, image: String?, url: String, raw: List<Pair<String, String>>): Part {
        val rules = if (kind == PartKind.GPU) GPU_RULES else CPU_RULES
        val rows = ArrayList<Pair<String, String>>()
        for ((rawLabel, value) in raw) {
            // Headings split over lines read "Cores / (threads)", "Release / date".
            val label = rawLabel.replace(" / ", " ").replace("- ", "")
            if (value.startsWith("—") || value.equals("N/a", true)) continue
            val rule = rules.firstOrNull { it.rx.containsMatchIn(label) }
            if (rule != null && rule.label == null) continue
            val short = rule?.label ?: label.uppercase(us).replace(Regex("\\s*\\([^)]*\\)"), "").take(12).trim()
            // "(GHz)", "(GB)" in the heading belongs with a bare number.
            val unit = Regex("\\((GHz|MHz|GB|GiB|MB|MiB|KB|W|nm|mm2|mm²|GB/s|bit|USD|US\\$|billion)\\)", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)
            val shown = if (unit != null && Regex("^[\\d.,\\s/]+$").matches(value)) {
                when (unit.uppercase(us)) { "USD", "US$" -> "$$value"; else -> "$value $unit" }
            } else value
            // "Clock speed / Boost value" holds both clocks in one cell: "2.01 / 2.41 GHz".
            val both = Regex("^([\\d.]+)\\s*/\\s*([\\d.]+)(.*)$").find(shown)
            if (short == "BOOST" && both != null && Regex("base|clock speed|core clock", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
                val (lo, hi, rest) = both.destructured
                if (rows.none { it.first == "BASE" }) rows += "BASE" to "$lo$rest"
                if (rows.none { it.first == "BOOST" }) rows += "BOOST" to "$hi$rest"
                continue
            }
            if (rows.none { it.first == short }) rows += short to shown.take(120)
        }
        // A hybrid chip's total: 8 P-cores + 16 E-cores = 24 cores, 32 threads.
        val pc = rows.firstOrNull { it.first == "P-CORES" }?.second
        val ec = rows.firstOrNull { it.first == "E-CORES" }?.second
        if (pc != null && rows.none { it.first == "CORES" }) {
            fun ct(s: String?) = s?.let { Regex("(\\d+)(?:\\s*\\((\\d+)\\))?").find(it) }?.let { m -> m.groupValues[1].toInt() to (m.groupValues[2].toIntOrNull() ?: m.groupValues[1].toInt()) }
            val p = ct(pc); val e = ct(ec)
            if (p != null) {
                val cores = p.first + (e?.first ?: 0); val threads = p.second + (e?.second ?: 0)
                rows += "CORES" to (if (e != null) "$cores (${p.first}P + ${e.first}E)" else "$cores")
                rows += "THREADS" to "$threads"
            }
        }
        // Thermal Velocity Boost is the real top clock when a chip has it.
        rows.firstOrNull { it.first == "TVB" }?.let { t ->
            val i = rows.indexOfFirst { it.first == "BOOST" }
            if (i >= 0 && (maxNum(t.second) ?: 0.0) > (maxNum(rows[i].second) ?: 0.0)) rows[i] = "BOOST" to t.second
        }
        val find = { l: String -> rows.firstOrNull { it.first == l }?.second }
        val nums = ArrayList<PartNum>()
        fun num(label: String, v: Double?, show: String?, tag: String?, lower: Boolean = false) { if (v != null && v > 0) nums += PartNum(label, v, show ?: fmt(v), tag, lower) }
        find("CORES")?.let { c -> firstNum(c)?.let { num("CORES", it, fmt(it), "more cores") } }
        (find("THREADS") ?: find("CORES")?.let { Regex("\\((\\d+)\\)|/\\s*(\\d+)").find(it)?.value })?.let { t -> maxNum(t)?.let { num("THREADS", it, fmt(it), "more threads") } }
        find("BOOST")?.let { b -> maxNum(b)?.let { v -> val ghz = if (v > 100) v / 1000 else v; num("BOOST", ghz, "%.2f GHz".format(us, ghz).replace(Regex("0 GHz$"), " GHz"), "a higher boost clock") } }
        find("VRAM")?.let { v -> firstNum(v)?.let { num("VRAM", it, "${fmt(it)} GB", "more VRAM") } }
        find("BANDWID")?.let { v -> firstNum(v)?.let { num("BANDWID", it, "${fmt(it)} GB/s", "more memory bandwidth") } }
        (find("L3") ?: find("CACHE"))?.let { v -> maxNum(v)?.let { num("CACHE", it, "${fmt(it)} MB", "more cache") } }
        (find("POWER") ?: find("TDP"))?.let { v -> firstNum(v)?.let { num("POWER", it, "${fmt(it)} W", "lower power draw", lower = true) } }
        find("PRICE")?.let { v -> firstNum(v)?.takeIf { it >= 20 }?.let { num("PRICE", it, "$${fmt(it)}", "a lower launch price", lower = true) } }
        find("RELEASE")?.let { v -> Regex("\\b(?:19|20)\\d{2}\\b").find(v)?.value?.toDoubleOrNull()?.let { num("RELEASE", it, it.toInt().toString(), null) } }
        val words = rows.filter { it.first in setOf("MEMORY", "SOCKET", "PROCESS", "ARCH", "IGPU", "PCIE", "CHIP") }
        val n = nums.associateBy { it.label }
        val answer = when (kind) {
            PartKind.GPU -> listOfNotNull(n["CORES"]?.let { "${it.show} cores" }, n["VRAM"]?.let { "${it.show} of ${find("MEMORY")?.substringBefore(" ")?.takeIf { m -> m.startsWith("GDDR", true) || m.startsWith("HBM", true) } ?: "VRAM"}" }, n["POWER"]?.show)
            else -> listOfNotNull(n["CORES"]?.let { "${it.show} cores" }, n["BOOST"]?.let { "up to ${it.show}" }, n["CACHE"]?.let { "${it.show} cache" }, n["POWER"]?.show)
        }.let { if (it.isEmpty()) "Here are the specs for the $name." else "The $name: ${it.joinToString(", ")}." }
        val notes = listOf("WHAT THE NUMBERS MEAN" to if (kind == PartKind.GPU) GPU_NOTES else CPU_NOTES)
        // The specs people shop on go first; the rest fill in after.
        val first = if (kind == PartKind.GPU) listOf("CORES", "BOOST", "BASE", "VRAM", "MEMORY", "BUS", "BANDWID", "POWER", "PLUG", "PCIE", "CACHE", "RT", "AI CORES", "PRICE", "RELEASE")
            else listOf("CORES", "THREADS", "P-CORES", "E-CORES", "BOOST", "BASE", "L3", "L2", "CACHE", "TDP", "MAX TDP", "SOCKET", "IGPU", "MEMORY", "PRICE", "RELEASE")
        val ordered = rows.sortedBy { r -> first.indexOf(r.first).let { if (it < 0) first.size else it } }
        return Part(kind, name, description, image, url, ordered.take(18), nums, words, notes, answer, "Wikipedia")
    }

    private val GPU_NOTES = listOf(
        "Cores: more shader cores means more frames, within the same brand and generation.",
        "VRAM: 8 GB is tight for new games; 12-16 GB suits 1440p; 16 GB+ for 4K and local AI models.",
        "Bandwidth: how fast the GPU reads its memory; it matters most at high resolutions.",
        "Power: decides the power supply you need and how much heat and noise the card makes.",
    )
    private val CPU_NOTES = listOf(
        "Cores and threads: more help with rendering, streaming and multitasking; games mostly use 6-8.",
        "Boost clock: how fast one core can run; it drives game frame rates and snappiness.",
        "Cache: big L3 caches (AMD's X3D chips) give a large boost in games.",
        "TDP: the power and cooling the chip is built for; the cooler should handle it.",
    )

    // ------------------------------------------------------------------ SSDs

    /** A drive's rated specs (the maker's "up to" numbers). */
    private class Drive(val name: String, val iface: String, val read: Int, val write: Int, val dram: Boolean?, val nand: String?, val max: String)

    private val DRIVES = listOf(
        "9100 pro" to Drive("Samsung 9100 Pro", "PCIe 5.0 x4 NVMe", 14800, 13400, true, "TLC", "8 TB"),
        "990 pro" to Drive("Samsung 990 Pro", "PCIe 4.0 x4 NVMe", 7450, 6900, true, "TLC", "4 TB"),
        "990 evo plus" to Drive("Samsung 990 Evo Plus", "PCIe 4.0 x4 / 5.0 x2 NVMe", 7250, 6300, false, "TLC", "4 TB"),
        "990 evo" to Drive("Samsung 990 Evo", "PCIe 4.0 x4 / 5.0 x2 NVMe", 5000, 4200, false, "TLC", "2 TB"),
        "980 pro" to Drive("Samsung 980 Pro", "PCIe 4.0 x4 NVMe", 7000, 5100, true, "TLC", "2 TB"),
        "980" to Drive("Samsung 980", "PCIe 3.0 x4 NVMe", 3500, 3000, false, "TLC", "1 TB"),
        "970 evo plus" to Drive("Samsung 970 Evo Plus", "PCIe 3.0 x4 NVMe", 3500, 3300, true, "TLC", "2 TB"),
        "870 evo" to Drive("Samsung 870 Evo", "SATA III (2.5 in)", 560, 530, true, "TLC", "4 TB"),
        "870 qvo" to Drive("Samsung 870 QVO", "SATA III (2.5 in)", 560, 530, true, "QLC", "8 TB"),
        "860 evo" to Drive("Samsung 860 Evo", "SATA III (2.5 in)", 550, 520, true, "TLC", "4 TB"),
        "sn8100" to Drive("WD Black SN8100", "PCIe 5.0 x4 NVMe", 14900, 14000, null, "TLC", "8 TB"),
        "sn850x" to Drive("WD Black SN850X", "PCIe 4.0 x4 NVMe", 7300, 6600, true, "TLC", "8 TB"),
        "sn850" to Drive("WD Black SN850", "PCIe 4.0 x4 NVMe", 7000, 5300, true, "TLC", "2 TB"),
        "sn770" to Drive("WD Black SN770", "PCIe 4.0 x4 NVMe", 5150, 4900, false, "TLC", "2 TB"),
        "sn580" to Drive("WD Blue SN580", "PCIe 4.0 x4 NVMe", 4150, 4150, false, "TLC", "2 TB"),
        "t705" to Drive("Crucial T705", "PCIe 5.0 x4 NVMe", 14500, 12700, true, "TLC", "4 TB"),
        "t700" to Drive("Crucial T700", "PCIe 5.0 x4 NVMe", 12400, 11800, true, "TLC", "4 TB"),
        "t500" to Drive("Crucial T500", "PCIe 4.0 x4 NVMe", 7400, 7000, true, "TLC", "2 TB"),
        "p310" to Drive("Crucial P310", "PCIe 4.0 x4 NVMe", 7100, 6000, false, "QLC", "4 TB"),
        "p3 plus" to Drive("Crucial P3 Plus", "PCIe 4.0 x4 NVMe", 5000, 4200, false, "QLC", "4 TB"),
        "p5 plus" to Drive("Crucial P5 Plus", "PCIe 4.0 x4 NVMe", 6600, 5000, true, "TLC", "2 TB"),
        "p3" to Drive("Crucial P3", "PCIe 3.0 x4 NVMe", 3500, 3000, false, "QLC", "4 TB"),
        "mx500" to Drive("Crucial MX500", "SATA III (2.5 in)", 560, 510, true, "TLC", "4 TB"),
        "bx500" to Drive("Crucial BX500", "SATA III (2.5 in)", 540, 500, false, null, "4 TB"),
        "kc3000" to Drive("Kingston KC3000", "PCIe 4.0 x4 NVMe", 7000, 7000, true, "TLC", "4 TB"),
        "fury renegade" to Drive("Kingston Fury Renegade", "PCIe 4.0 x4 NVMe", 7300, 7000, true, "TLC", "4 TB"),
        "nv3" to Drive("Kingston NV3", "PCIe 4.0 x4 NVMe", 6000, 5000, false, null, "4 TB"),
        "nv2" to Drive("Kingston NV2", "PCIe 4.0 x4 NVMe", 3500, 2800, false, null, "4 TB"),
        "platinum p41" to Drive("SK hynix Platinum P41", "PCIe 4.0 x4 NVMe", 7000, 6500, true, "TLC", "2 TB"),
        "p44 pro" to Drive("Solidigm P44 Pro", "PCIe 4.0 x4 NVMe", 7000, 6500, true, "TLC", "2 TB"),
        "firecuda 530" to Drive("Seagate FireCuda 530", "PCIe 4.0 x4 NVMe", 7300, 6900, true, "TLC", "4 TB"),
        "rocket 4 plus" to Drive("Sabrent Rocket 4 Plus", "PCIe 4.0 x4 NVMe", 7100, 6600, true, "TLC", "8 TB"),
        "nm790" to Drive("Lexar NM790", "PCIe 4.0 x4 NVMe", 7400, 6500, false, "TLC", "4 TB"),
        "mp44" to Drive("Teamgroup MP44", "PCIe 4.0 x4 NVMe", 7400, 6500, false, null, "4 TB"),
    )

    fun ssd(said: String): Part? {
        val s = said.lowercase(us)
        val d = DRIVES.firstOrNull { (k, _) -> Regex("(?<![a-z0-9])${Regex.escape(k)}(?![a-z0-9])").containsMatchIn(s) }?.second ?: return null
        val rows = listOfNotNull(
            "TYPE" to d.iface, "READ" to "up to %,d MB/s".format(us, d.read), "WRITE" to "up to %,d MB/s".format(us, d.write),
            d.dram?.let { "DRAM" to if (it) "Yes (own cache chip)" else "No (borrows system RAM, HMB)" }, d.nand?.let { "NAND" to it },
            "SIZE" to (if (d.iface.startsWith("SATA")) "2.5 in drive" else "M.2 2280"), "MAX" to "up to ${d.max}",
            "100 GB" to "about ${(100_000.0 / d.write).roundToInt()} s to write at full speed",
        )
        val nums = listOf(
            PartNum("READ", d.read.toDouble(), "%,d MB/s".format(us, d.read), "faster reads"),
            PartNum("WRITE", d.write.toDouble(), "%,d MB/s".format(us, d.write), "faster writes"),
        )
        val words = listOfNotNull("TYPE" to d.iface, d.dram?.let { "DRAM" to if (it) "Yes" else "No (HMB)" }, d.nand?.let { "NAND" to it })
        val notes = listOf("WHAT THE NUMBERS MEAN" to listOf(
            "Read and write: top speeds for big files; game loads and boot feel similar on any NVMe drive.",
            "DRAM cache: keeps speed steady during long transfers; drives without it borrow a little system RAM (HMB).",
            "NAND: TLC lasts longer and holds speed better than QLC, which is cheaper per terabyte.",
            "Your slot must match: a PCIe 5.0 drive runs at 4.0 speed in a 4.0 slot.",
        ))
        return Part(PartKind.SSD, d.name, "${d.iface} solid-state drive", null, "https://duckduckgo.com/?q=${Http.enc(d.name + " specs")}",
            rows, nums, words, notes, "The ${d.name}: ${d.iface.substringBefore(" (")}, up to %,d MB/s reads.".format(us, d.read), "Maker's rated specs")
    }

    // ------------------------------------------------------------------ memory kits

    fun ram(said: String): Part? {
        val s = said.lowercase(us)
        val gen = Regex("ddr([345])").find(s)?.groupValues?.get(1)?.toInt() ?: if (Regex("trident z5|flare x5|fury beast|lancer|t ?create").containsMatchIn(s)) 5 else null
        val speed = Regex("(?:ddr[345][- ]?)?(\\d{4})(?: ?(?:mt/?s|mhz))?").findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1333..12000 }
        val cl = Regex("\\bc(?:l|as)? ?(\\d{2})\\b").find(s)?.groupValues?.get(1)?.toIntOrNull()
        val gb = Regex("(\\d{1,3}) ?gb").find(s)?.groupValues?.get(1)?.toIntOrNull()
        if (gen == null && speed == null) return null
        val g = gen ?: if ((speed ?: 0) >= 4800) 5 else 4
        val brand = Regex("corsair|g ?skill|kingston|crucial|teamgroup|patriot|adata").find(s)?.value?.replace("g skill", "gskill")
        val series = Regex("vengeance|dominator|trident z5?|ripjaws|flare x5|fury beast|t ?create|xpg lancer").find(s)?.value
        val name = listOfNotNull(brand?.let { if (it == "gskill") "G.Skill" else it.replaceFirstChar { c -> c.titlecase(us) } }, series?.split(' ')?.joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(us) } },
            gb?.let { "$it GB" }, "DDR$g" + (speed?.let { "-$it" } ?: ""), cl?.let { "CL$it" }).joinToString(" ")
        val latency = if (speed != null && cl != null) cl * 2000.0 / speed else null
        val perChannel = speed?.let { it * 8 / 1000.0 }
        val volts = when (g) { 3 -> "1.5 V (JEDEC)"; 4 -> "1.2 V JEDEC, ~1.35 V with XMP"; else -> "1.1 V JEDEC, ~1.25-1.4 V with XMP/EXPO" }
        val platforms = when (g) { 3 -> "Older PCs (LGA 1150, AM3)"; 4 -> "AM4, Intel 6th-14th gen (LGA 1151/1200/1700)"; else -> "AM5, Intel 12th gen+ (LGA 1700/1851)" }
        val rows = listOfNotNull(
            "TYPE" to "DDR$g", speed?.let { "SPEED" to "$it MT/s" }, cl?.let { "LATENCY" to "CL$it" },
            latency?.let { "TRUE LAT" to "%.1f ns".format(us, it) }, perChannel?.let { "BANDWID" to "%.1f GB/s per channel, %.1f GB/s dual".format(us, it, it * 2) },
            gb?.let { "SIZE" to "$it GB" }, "VOLTAGE" to volts, "FITS" to platforms,
            "PROFILE" to if (g >= 4) "XMP (Intel) or EXPO (AMD): turn it on in the BIOS to get the rated speed" else "Set speed manually",
        )
        val nums = listOfNotNull(
            speed?.let { PartNum("SPEED", it.toDouble(), "$it MT/s", "faster memory") },
            latency?.let { PartNum("TRUE LAT", it, "%.1f ns".format(us, it), "lower latency", lower = true) },
            perChannel?.let { PartNum("BANDWID", it * 2, "%.1f GB/s".format(us, it * 2), "more bandwidth") },
            gb?.let { PartNum("SIZE", it.toDouble(), "$it GB", "more capacity") },
        )
        val answer = "$name: " + listOfNotNull(latency?.let { "%.1f nanosecond true latency".format(us, it) }, perChannel?.let { "%.0f GB/s in dual channel".format(us, it * 2) }).joinToString(", ").ifBlank { "a DDR$g kit" } + "."
        val notes = listOf("WHAT THE NUMBERS MEAN" to listOf(
            "Speed (MT/s): how many transfers per second; higher means more bandwidth.",
            "CL: clock cycles of delay. True latency = CL x 2000 / speed; lower is snappier.",
            "Dual channel: two or four sticks run in pairs and double the bandwidth.",
            "DDR4 and DDR5 don't fit each other's slots; the board decides which you need.",
        ))
        return Part(PartKind.RAM, name, "DDR$g memory", null, "https://en.wikipedia.org/wiki/DDR${g}_SDRAM",
            rows, nums, listOf("TYPE" to "DDR$g", "FITS" to platforms), notes, answer, "Worked out from the kit's rated speed and timings")
    }

    // ------------------------------------------------------------------ motherboards

    private class Chipset(val socket: String, val cpus: String, val memory: String, val cpuOc: Boolean, val gpu: String, val m2: String, val extra: String? = null, val tier: Int)

    private val CHIPS = mapOf(
        "a620" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", false, "PCIe 4.0", "PCIe 4.0", "Budget: no CPU overclocking", 1),
        "b650" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 4.0", "PCIe 4.0 (some boards 5.0)", null, 2),
        "b650e" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 5.0", "PCIe 5.0", null, 3),
        "b840" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", false, "PCIe 3.0-4.0", "PCIe 3.0-4.0", "Budget: no CPU overclocking", 1),
        "b850" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 4.0 (some 5.0)", "PCIe 5.0", null, 3),
        "x670" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 4.0", "PCIe 5.0", "Two chipsets: more USB and M.2", 4),
        "x670e" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 5.0", "PCIe 5.0", "Two chipsets: more USB and M.2", 5),
        "x870" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 5.0", "PCIe 5.0", "USB4 built in", 5),
        "x870e" to Chipset("AM5", "Ryzen 7000-9000", "DDR5", true, "PCIe 5.0", "PCIe 5.0", "USB4 and two chipsets: the most I/O", 6),
        "a520" to Chipset("AM4", "Ryzen 3000-5000", "DDR4", false, "PCIe 3.0", "PCIe 3.0", "Budget: no CPU overclocking", 1),
        "b450" to Chipset("AM4", "Ryzen 1000-5000 (BIOS update)", "DDR4", true, "PCIe 3.0", "PCIe 3.0", null, 2),
        "b550" to Chipset("AM4", "Ryzen 3000-5000", "DDR4", true, "PCIe 4.0", "PCIe 4.0", null, 3),
        "x570" to Chipset("AM4", "Ryzen 2000-5000", "DDR4", true, "PCIe 4.0", "PCIe 4.0", "PCIe 4.0 from the chipset too", 4),
        "h610" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", false, "PCIe 4.0", "PCIe 4.0", "Budget: no overclocking, fewer ports", 1),
        "b660" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", false, "PCIe 4.0", "PCIe 4.0", "Memory overclocking only", 2),
        "b760" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", false, "PCIe 4.0 (some 5.0)", "PCIe 4.0", "Memory overclocking only", 2),
        "h670" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", false, "PCIe 5.0", "PCIe 4.0", null, 3),
        "h770" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", false, "PCIe 5.0", "PCIe 4.0", null, 3),
        "z690" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", true, "PCIe 5.0", "PCIe 4.0", null, 4),
        "z790" to Chipset("LGA 1700", "Intel 12th-14th gen", "DDR4 or DDR5 (by board)", true, "PCIe 5.0", "PCIe 4.0 (some 5.0)", null, 5),
        "h810" to Chipset("LGA 1851", "Intel Core Ultra 200S", "DDR5", false, "PCIe 5.0", "PCIe 4.0", "Budget: no overclocking", 1),
        "b860" to Chipset("LGA 1851", "Intel Core Ultra 200S", "DDR5", false, "PCIe 5.0", "PCIe 5.0 (by board)", "Memory overclocking only", 3),
        "z890" to Chipset("LGA 1851", "Intel Core Ultra 200S", "DDR5", true, "PCIe 5.0", "PCIe 5.0", "Thunderbolt 4 support", 5),
        "x299" to Chipset("LGA 2066", "Intel Core X", "DDR4 (quad channel)", true, "PCIe 3.0", "PCIe 3.0", "High-end desktop", 4),
        "trx50" to Chipset("sTR5", "Threadripper 7000-9000", "DDR5 (quad channel)", true, "PCIe 5.0", "PCIe 5.0", "High-end desktop", 6),
        "wrx90" to Chipset("sTR5", "Threadripper Pro 7000-9000", "DDR5 (8 channel)", true, "PCIe 5.0", "PCIe 5.0", "Workstation", 7),
    )

    fun board(said: String): Part? {
        val s = said.lowercase(us)
        val chip = Regex("(?<![a-z0-9])($CHIPSETS)(?![0-9])").find(s)?.groupValues?.get(1) ?: return null
        val c = CHIPS[chip] ?: return null
        val form = when {
            Regex("\\bitx\\b|[- ]i(?: |$)|\\bmini\\b").containsMatchIn(s) -> "Mini-ITX"
            Regex("\\bm-?atx\\b|\\bmicro\\b|[- ]m(?: |$)|mortar|\\bm plus\\b").containsMatchIn(s) -> "Micro-ATX"
            Regex("\\be-?atx\\b").containsMatchIn(s) -> "E-ATX"
            else -> null
        }
        val isChipsetOnly = Regex("^(?:the )?(?:$CHIPSETS)(?: (?:chipset|motherboards?|boards?))?$").matches(s.trim())
        val name = if (isChipsetOnly) "${chip.uppercase(us)} motherboards" else pretty(said)
        val rows = listOfNotNull(
            "CHIPSET" to chip.uppercase(us), "SOCKET" to c.socket, "CPUS" to c.cpus, "MEMORY" to c.memory,
            "CPU OC" to if (c.cpuOc) "Yes" else "No", "GPU SLOT" to c.gpu, "M.2" to c.m2, c.extra?.let { "NOTE" to it },
            "SIZE" to (form ?: if (isChipsetOnly) "ATX, Micro-ATX or Mini-ITX" else "Check the model (ATX by default)"),
        )
        val nums = listOf(PartNum("TIER", c.tier.toDouble(), listOf("", "entry", "budget", "mid", "upper-mid", "high", "enthusiast", "workstation")[c.tier], "a higher-end chipset"),
            PartNum("GPU PCIE", Regex("(\\d)\\.0").findAll(c.gpu).mapNotNull { it.groupValues[1].toDoubleOrNull() }.max(), c.gpu.substringBefore(" ("), "a faster graphics slot"),
            PartNum("M.2 PCIE", Regex("(\\d)\\.0").findAll(c.m2).mapNotNull { it.groupValues[1].toDoubleOrNull() }.max(), c.m2.substringBefore(" ("), "faster SSD slots"))
        val answer = "${if (isChipsetOnly) "${chip.uppercase(us)} boards" else "The $name"}: ${c.socket}, ${c.memory.substringBefore(" (")}, ${if (c.cpuOc) "CPU overclocking" else "no CPU overclocking"}, ${c.gpu.substringBefore(" (")} graphics slot."
        val notes = listOf("WHAT THE NUMBERS MEAN" to listOf(
            "Socket: the CPU must match it (AM5 for Ryzen 7000+, LGA 1700 for Intel 12th-14th gen).",
            "Chipset: sets overclocking, PCIe speeds and how many USB and M.2 ports you get.",
            "Memory: AM5 and LGA 1851 boards are DDR5 only; LGA 1700 boards come in DDR4 or DDR5 versions.",
            "Size: ATX has the most slots; Micro-ATX and Mini-ITX fit smaller cases.",
        ))
        return Part(PartKind.BOARD, name, "${chip.uppercase(us)} chipset motherboard", null, "https://duckduckgo.com/?q=${Http.enc(name + " specs")}",
            rows, nums, listOf("SOCKET" to c.socket, "MEMORY" to c.memory, "CPU OC" to if (c.cpuOc) "Yes" else "No"), notes, answer, "Chipset specs")
    }

    // ------------------------------------------------------------------ power supplies

    /** 80 Plus efficiency at 20 / 50 / 100% load (115 V). */
    private val RATINGS = mapOf("bronze" to Triple(82, 85, 82), "silver" to Triple(85, 88, 85), "gold" to Triple(87, 90, 87),
        "platinum" to Triple(90, 92, 89), "titanium" to Triple(92, 94, 90), "white" to Triple(80, 80, 80))

    /** Series -> (rating, modular). */
    private val SERIES: List<Triple<Regex, String, String>> = listOf(
        Triple(r("\\brm\\d{3,4}x\\b|\\brmx\\b"), "gold", "Fully modular"), Triple(r("\\brm\\d{3,4}e\\b|\\brme\\b"), "gold", "Fully modular"),
        Triple(r("\\brm\\d{3,4}i\\b|\\brmi\\b"), "gold", "Fully modular"), Triple(r("\\brm\\d{3,4}\\b"), "gold", "Fully modular"),
        Triple(r("\\bhx\\d{3,4}i?\\b"), "platinum", "Fully modular"), Triple(r("\\bax\\d{3,4}i?\\b"), "titanium", "Fully modular"),
        Triple(r("\\bcx\\d{3,4}m\\b|\\bcxm\\b"), "bronze", "Semi-modular"), Triple(r("\\bcx\\d{3,4}\\b"), "bronze", "Not modular"),
        Triple(r("\\bsf\\d{3,4}\\b"), "platinum", "Fully modular (SFX)"),
        Triple(r("focus (?:gx|gm)"), "gold", "Fully modular"), Triple(r("focus px|prime px|vertex px"), "platinum", "Fully modular"),
        Triple(r("prime tx|vertex tx"), "titanium", "Fully modular"), Triple(r("vertex gx|core gx"), "gold", "Fully modular"), Triple(r("core gc"), "gold", "Not modular"),
        Triple(r("pure power"), "gold", "Semi or fully modular (M models)"), Triple(r("straight power"), "platinum", "Fully modular"), Triple(r("dark power"), "titanium", "Fully modular"),
        Triple(r("supernova \\d{3,4} (?:g\\d|gt|ga|g\\+)"), "gold", "Fully modular"), Triple(r("supernova \\d{3,4} p\\d"), "platinum", "Fully modular"), Triple(r("supernova \\d{3,4} t\\d"), "titanium", "Fully modular"),
        Triple(r("mag a\\d{3,4}gl"), "gold", "Fully modular"), Triple(r("mag a\\d{3,4}gf"), "gold", "Fully modular"), Triple(r("mag a\\d{3,4}bn"), "bronze", "Not modular"),
        Triple(r("toughpower gf"), "gold", "Fully modular"), Triple(r("mwe gold"), "gold", "Fully modular"), Triple(r("mwe bronze|mwe \\d"), "bronze", "Not modular"),
    )

    fun psu(said: String): Part? {
        val s = said.lowercase(us)
        val watts = Regex("(?<![0-9])(\\d{3,4})(?: ?(?:w|watts?)\\b|[xie]?\\b)").findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 250..2000 } ?: return null
        val series = SERIES.firstOrNull { it.first.containsMatchIn(s) }
        val rating = Regex("\\b(bronze|silver|gold|platinum|titanium)\\b").find(s)?.value ?: series?.second
        val eff = rating?.let { RATINGS[it] }
        val name = pretty(said).replace(Regex("\\bPsu\\b"), "PSU")
        fun wall(load: Int) = eff?.let { (load / (it.second / 100.0)).roundToInt() }
        val rows = listOfNotNull(
            "POWER" to "$watts W", rating?.let { "RATING" to "80 Plus ${it.replaceFirstChar { c -> c.titlecase(us) }}" },
            eff?.let { "EFFIC" to "${it.first}% / ${it.second}% / ${it.third}% at 20 / 50 / 100% load" },
            series?.third?.let { "CABLES" to it },
            eff?.let { "AT HALF" to "${watts / 2} W load pulls ~${wall(watts / 2)} W from the wall, ~${wall(watts / 2)!! - watts / 2} W as heat" },
            "SWEET" to "Best efficiency and quietest around ${watts * 4 / 10}-${watts * 6 / 10} W",
            "FITS" to fitsFor(watts),
        )
        val nums = listOfNotNull(PartNum("POWER", watts.toDouble(), "$watts W", "more wattage"),
            eff?.let { PartNum("EFFIC", it.second.toDouble(), "${it.second}%", "better efficiency") })
        val answer = "The $name: $watts watts" + (rating?.let { ", 80 Plus ${it.replaceFirstChar { c -> c.titlecase(us) }}" } ?: "") + (series?.third?.let { ", ${it.lowercase(us).substringBefore(" (")}" } ?: "") + "."
        val notes = listOf("WHAT THE NUMBERS MEAN" to listOf(
            "Wattage: add your CPU and GPU power and leave about 30-40% headroom for spikes.",
            "80 Plus rating: efficiency. Gold wastes about 10% as heat at half load, Bronze about 15%.",
            "Modular: you plug in only the cables you need, which tidies the case.",
            "New GPUs use the 12V-2x6 (12VHPWR) plug; ATX 3.x supplies include it natively.",
        ))
        return Part(PartKind.PSU, name, "${watts} W power supply", null, "https://duckduckgo.com/?q=${Http.enc(name + " specs")}",
            rows, nums, listOfNotNull(rating?.let { "RATING" to "80 Plus ${it.replaceFirstChar { c -> c.titlecase(us) }}" }, series?.third?.let { "CABLES" to it }), notes, answer,
            if (series != null) "Series specs" else "Worked out from the wattage")
    }

    /** What a supply of [watts] comfortably runs. */
    fun fitsFor(watts: Int) = when {
        watts >= 1200 -> "Any single GPU incl. RTX 5090 / 4090 with a high-end CPU"
        watts >= 1000 -> "RTX 5090 / 4090-class builds"
        watts >= 850 -> "RTX 5080 / 4080, RX 9070 XT / 7900 XTX builds"
        watts >= 750 -> "RTX 5070 Ti / 4070 Ti, RX 9070 builds"
        watts >= 650 -> "RTX 5070 / 4070, RX 7800 XT builds"
        watts >= 550 -> "RTX 5060 / 4060, RX 7600 builds"
        else -> "Office PCs and low-power GPUs"
    }

    // ------------------------------------------------------------------ comparing

    /** Two parts head to head: their shared numbers as bars, the rest as words. */
    fun versus(a: Part, b: Part): Versus {
        val labels = (a.nums.map { it.label } + b.nums.map { it.label }).distinct()
        val bars = labels.mapNotNull { l ->
            val x = a.nums.firstOrNull { it.label == l }; val y = b.nums.firstOrNull { it.label == l }
            val p = x ?: y ?: return@mapNotNull null
            Versus.Bar(l, x?.value, y?.value, x?.show, y?.show, p.tag, p.lower)
        }
        val wl = (a.words.map { it.first } + b.words.map { it.first }).distinct()
        val words = wl.map { l -> Versus.Words(l, a.words.firstOrNull { it.first == l }?.second, b.words.firstOrNull { it.first == l }?.second) }
        val year = { p: Part -> p.nums.firstOrNull { it.label == "RELEASE" }?.show }
        return Versus(listOfNotNull(a.name, year(a)?.let { "($it)" }).joinToString(" "), listOfNotNull(b.name, year(b)?.let { "($it)" }).joinToString(" "),
            bars.filter { it.label != "RELEASE" }, words, Tech.verdict(a.name, b.name, bars.filter { it.label != "RELEASE" }))
    }
}
