package com.brick.assistant.brain

import java.util.Locale

/**
 * Live specs for keyboards, mice and mics from the makers' own sites: the product page is found in the maker's
 * sitemap or store search, then its specs are read from the page text (polling rate, battery, DPI, weight, sensor,
 * switches, keycaps, hot-swap, software, connections, wheel, mic type and pattern) and today's price.
 * Every step can fail (offline, a redesigned site); callers fall back to Brick's own notes.
 */
object GearLive {
    data class Facts(
        val url: String, val title: String?, val price: Double?, val kind: Peripherals.Kind?,
        val polling: Int? = null, val battery: String? = null, val batteryH: Double? = null, val dpi: Int? = null, val weightG: Double? = null,
        val sensor: String? = null, val switches: String? = null, val keycaps: String? = null, val hotSwap: Boolean = false,
        val software: List<String> = emptyList(), val connections: List<String> = emptyList(), val wheel: String? = null, val buttons: Int? = null,
        val micType: String? = null, val pattern: String? = null, val audio: String? = null, val keys: Int? = null,
    ) {
        /** How many spec facts were found: a page with almost none is likely the wrong page. */
        val found get() = listOfNotNull(polling, battery, dpi, weightG, sensor, switches, keycaps, wheel, buttons, micType, pattern, audio, keys).size +
            (if (hotSwap) 1 else 0) + (if (software.isNotEmpty()) 1 else 0) + (if (connections.isNotEmpty()) 1 else 0)
    }

    private val us = Locale.US

    // ---------------------------------------------------------------- where each maker lists its products

    private sealed interface Find
    /** Sitemaps (or sitemap indexes, following children that match [child]); product pages match [keep]. */
    private class Sitemap(val maps: List<String>, val keep: Regex, val child: Regex? = null, val page: (String) -> String = { it }) : Find
    /** A Shopify store: its search API finds the product, and /products/handle.js has the details. */
    private class Shopify(val host: String) : Find
    /** No usable product list: the product page's address is built from the model ("sm7b" -> .../microphones/sm7b); each guess is tried in turn. */
    private class Guess(val urls: (String) -> List<String>) : Find

    private val STORES: Map<String, Find> = mapOf(
        "Logitech" to Sitemap(listOf("https://www.logitechg.com/sitemap.xml", "https://www.logitech.com/sitemap.xml"), Regex("/en-us/shop/p/")),
        "Razer" to Sitemap(listOf("https://www.razer.com/sitemap.xml"), Regex("razer\\.com/(?:gaming-mice|gaming-keyboards|mice|keyboards|streaming-microphones|streaming|pc)/"), child = Regex("Product-en-US-USD")),
        "SteelSeries" to Sitemap(listOf("https://steelseries.com/sitemap-products.xml"), Regex("steelseries\\.com/(?:gaming-mice|gaming-keyboards|mice|keyboards|microphones)/")),
        "Wooting" to Sitemap(listOf("https://wooting.io/sitemap-0.xml"), Regex("wooting\\.io/wooting-")),
        "ASUS" to Sitemap(listOf("https://rog.asus.com/sitemap/product1.xml"), Regex("rog\\.asus\\.com/(?:keyboards|mice-mouse-pads|mice|microphones)/[^?]*/$"), page = { it + "spec/" }),
        "Zowie" to Sitemap(listOf("https://zowie.benq.com/en-us/sitemap.xml"), Regex("/en-us/(?:mouse|keyboard)/")),
        "Keychron" to Shopify("www.keychron.com"),
        "HyperX" to Shopify("hyperx.com"),
        "Glorious" to Shopify("www.gloriousgaming.com"),
        "Corsair" to Sitemap(listOf("https://www.corsair.com/us-sitemap-products-1.xml"), Regex("/p/(?:keyboards|gaming-mouse|diy-gaming-keyboards|stream-deck|audio)/")),
        // Elgato's sitemap lists its pages on a staging host; the same paths work on www.
        "Elgato" to Sitemap(listOf("https://www.elgato.com/us-sitemap-products-1.xml"), Regex("/p/(?:stream-deck|wave)"), page = { it.replace("://staging-pwa.elgato.com/", "://www.elgato.com/") }),
        "Shure" to Guess { m -> slugs(m).map { "https://www.shure.com/en-US/products/microphones/$it" } },
    )

    /** Where each maker keeps its headsets (the same sitemaps as above, different product pages). */
    private val HEADSET_KEEP: Map<String, Regex> = mapOf(
        "Logitech" to Regex("/en-us/shop/p/"),
        "Razer" to Regex("razer\\.com/(?:gaming-headsets|console-headsets?|headsets)/"),
        "SteelSeries" to Regex("steelseries\\.com/gaming-headsets/"),
        "Corsair" to Regex("/p/gaming-headsets/"),
    )

    fun coversHeadsets(maker: String) = enabled && (maker in HEADSET_KEEP || maker == "HyperX")

    /** A product page: its address, HTML, title and price. */
    data class Page(val url: String, val html: String, val title: String?, val price: Double?)

    private val pageCache = HashMap<String, Page?>()

    /** The maker's page for a headset ("arctis nova 5"), or null. Blocking. */
    fun headsetPage(maker: String, model: String): Page? {
        if (!coversHeadsets(maker)) return null
        val key = "headset|$maker|${model.lowercase(us)}"
        synchronized(pageCache) { if (pageCache.containsKey(key)) return pageCache[key] }
        val page = runCatching {
            if (maker == "HyperX") shopifyPage("hyperx.com", model)
            else {
                val store = STORES[maker] as? Sitemap ?: return@runCatching null
                val all = productUrls(Sitemap(store.maps, HEADSET_KEEP.getValue(maker), store.child, store.page))
                bestUrl(model, all)?.let { u -> store.page(u) }?.let { u -> Http.getText(u)?.let { h -> Page(u, h, titleOf(u, h), ldPrice(h)) } }
            }
        }.getOrNull()
        synchronized(pageCache) { pageCache[key] = page }
        return page
    }

    /** The page's product name: the title that names the model in the address, cleaned of store words. */
    private fun titleOf(url: String, html: String): String? {
        val slug = tokens(url.lowercase(us).substringAfterLast('/')).firstOrNull()
        val titles = listOfNotNull(Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1),
            Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))
        val t = titles.firstOrNull { t -> slug != null && t.lowercase(us).replace(Regex("[^a-z0-9]"), "").contains(slug) } ?: titles.firstOrNull() ?: return null
        return unescape(t).replace("&amp;", "&").substringBefore(" | ").substringBefore(" — ").replace(Regex(" with .*$|, [A-Z][A-Za-z]+(?: [A-Z][a-z]+)?$"), "")
            .removePrefix("Buy ").replace(Regex(" - (?:US|Black|White|[A-Z][a-z]+(?: [A-Z][a-z]+)?)(?: - .*)?$"), "").trim().ifBlank { null }
    }

    private fun shopifyPage(host: String, model: String): Page? {
        val j = Http.getJsonCached("https://$host/search/suggest.json?q=${Http.enc(model)}&resources%5Btype%5D=product&resources%5Blimit%5D=8") ?: return null
        val arr = j.optJSONObject("resources")?.optJSONObject("results")?.optJSONArray("products") ?: return null
        val handles = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("handle")?.ifBlank { null } }
        val handle = bestUrl(model, handles.map { "https://$host/products/$it" })?.substringAfterLast('/') ?: return null
        val p = Http.getJsonCached("https://$host/products/$handle.js") ?: return null
        val title = p.optString("title").ifBlank { null }
        return Page("https://$host/products/$handle", "<title>${title.orEmpty()}</title>" + p.optString("description"), title,
            p.optLong("price", -1).takeIf { it > 0 }?.let { it / 100.0 })
    }

    /** Off in unit tests, which mustn't reach the makers' sites. */
    @Volatile var enabled = true

    fun covers(maker: String) = enabled && STORES.containsKey(maker)

    private val mapCache = HashMap<String, List<String>>()
    private val factCache = HashMap<String, Facts?>()

    private fun locs(xml: String) = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>").findAll(xml).map { it.groupValues[1].replace("&amp;", "&") }.toList()

    /** Every product address in a maker's sitemaps (kept a day). */
    private fun productUrls(s: Sitemap): List<String> {
        val key = "gear-sitemap:" + s.maps.joinToString(",") + "|" + s.keep.pattern
        synchronized(mapCache) { mapCache[key]?.let { return it } }
        WebCache.read(key, WebCache.LOOKUP_MS)?.let { saved -> val l = saved.lines().filter { it.isNotBlank() }; synchronized(mapCache) { mapCache[key] = l }; return l }
        val out = LinkedHashSet<String>()
        for (m in s.maps) {
            val xml = Http.getText(m) ?: continue
            val children = if (s.child != null) locs(xml).filter { s.child.containsMatchIn(it) } else emptyList()
            if (children.isEmpty()) out += locs(xml).filter { s.keep.containsMatchIn(it) }
            for (c in children.take(3)) Http.getText(c)?.let { out += locs(it).filter { u -> s.keep.containsMatchIn(u) } }
        }
        val list = out.map { it.substringBefore('?').substringBefore('#') }.distinct()
        if (list.isNotEmpty()) { WebCache.write(key, list.joinToString("\n")); synchronized(mapCache) { mapCache[key] = list } }
        return list
    }

    // ---------------------------------------------------------------- matching a name to an address

    private val GENERIC = setOf("gaming", "wireless", "wired", "mouse", "mice", "keyboard", "keyboards", "mechanical", "rgb", "lightspeed", "the", "a",
        "en", "us", "shop", "p", "products", "product", "www", "com", "https", "http", "html", "logitech", "logitechg", "razer", "steelseries", "wooting",
        "io", "rog", "asus", "zowie", "benq", "keychron", "hyperx", "glorious", "microphone", "microphones", "mic", "streaming", "custom", "qmk", "via", "spec",
        "mouse", "and", "for", "with", "pc", "buy", "corsair", "elgato", "shure", "black", "white", "lightweight", "us", "p",
        "headset", "headsets", "headphones", "earbuds", "audio", "arctis", "astro", "multi", "platform")

    /** Words in an address that mean a different model ("g915-x-tkl" isn't the G915 TKL; "superlight-2-se" isn't the Superlight 2). */
    private val VARIANT = setOf("x", "se", "dex", "c", "pro", "max", "mini", "plus", "lite", "ultra", "tkl", "air", "core", "s", "v2", "v3", "v4", "hyperspeed",
        "bundle", "combo", "kit", "refurbished", "renewed", "barebone", "iso", "wired", "eternal", "classic", "8khz", "4k", "1k", "8k", "dw", "cw",
        // Accessories sold beside the product ("stream-deck-mk2-faceplate", "wave-mic-arm").
        "faceplate", "cable", "bracket", "mount", "mounting", "stand", "foam", "extension", "certified", "dock", "transmitter", "adapter", "screw", "riser",
        "clamping", "arm", "dial", "knob", "edition", "fallout", "anniversary", "cover", "case", "skin", "keycap", "keycaps", "set", "kit")

    /** Address guesses for a model name: "sm7b" -> sm7b; "mv7+" -> mv7plus, mv7-plus; "nexadyne 8" -> nexadyne8, nexadyne-8, nexadyne_8. */
    fun slugs(model: String): List<String> {
        val w = model.lowercase(us).replace("+", " plus").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        return if (w.isEmpty()) emptyList() else listOf(w.joinToString(""), w.joinToString("-"), w.joinToString("_")).distinct()
    }

    fun tokens(s: String) = s.lowercase(us).replace("+", " plus ").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in GENERIC }

    /** The best address for [model] ("mx master 4"): it must hold every word asked; the fewest extra words wins ("mx-master-4" over "mx-master-4-business"). */
    fun bestUrl(model: String, urls: List<String>): String? {
        val want = tokens(model)
        if (want.isEmpty()) return null
        var best: String? = null; var bestScore = Int.MIN_VALUE
        for (u in urls) {
            val path = u.substringAfter("://").substringAfter('/').lowercase(us)
                // Corsair and Elgato SKUs ("ch-931d010-na", "10gba9901-nc2509") aren't model words.
                .replace(Regex("-?\\bch-[0-9a-z]{7}-[a-z]{2}\\b"), "").replace(Regex("-?\\b\\d{2}[a-z]{3}\\d{4}(?:-[a-z]{2}\\d{4})?\\b"), "")
                // "...-keyboard-with-pbt-double-shot-pro-keycaps" describes the box, not the model; "ultra-lightweight" isn't the Ultra.
                .replace(Regex("-with-[^/]*"), "").replace("ultra-lightweight", "lightweight")
            // Razer ends its addresses with a SKU ("/RZ01-04630100-R3U1"): leave it out.
            val words = tokens(path.split('/').filterNot { Regex("^rz\\d|\\d{6,}", RegexOption.IGNORE_CASE).containsMatchIn(it) }.joinToString("/"))
            val joined = words.joinToString("")
            // "superlight 2" fits "pro-x2-superlight": short words may sit inside longer ones.
            fun composed(x: String) = want.any { a -> want.any { b -> a + b == x } }
            fun has(w: String) = w in words || words.any { composed(it) && it.contains(w) } || (w.length >= 3 && joined.contains(w))
            if (!want.all { has(it) }) continue
            val extras = words.filter { x -> x !in want && !composed(x) && !(x.length >= 4 && want.joinToString("").contains(x)) }
            if (extras.any { it in VARIANT || it.any(Char::isDigit) }) continue
            val extra = extras.size
            val score = -extra * 10 - path.length / 40
            if (score > bestScore) { bestScore = score; best = u }
        }
        return best
    }

    // ---------------------------------------------------------------- reading a page

    /** The maker's facts for "[maker] [model]", or null when the store can't be reached or the page isn't found. Blocking. */
    fun lookup(maker: String, model: String): Facts? {
        if (!enabled) return null
        val find = STORES[maker] ?: return null
        val key = "$maker|${model.lowercase(us)}"
        synchronized(factCache) { if (factCache.containsKey(key)) return factCache[key] }
        val facts = runCatching {
            when (find) {
                is Sitemap -> productUrls(find).let { all -> bestUrl(model, all) ?: model.replace(Regex("(?i)\\bmk\\.? ?\\d\\b"), "").takeIf { it != model }?.let { bestUrl(it, all) } }?.let { u -> Http.getText(find.page(u))?.let { read(find.page(u), it) } }
                is Shopify -> shopify(find.host, model)
                is Guess -> find.urls(model).take(3).firstNotNullOfOrNull { u -> Http.getText(u)?.let { read(u, it) } }
            }
        }.getOrNull()?.takeIf { it.found >= 2 }
        synchronized(factCache) { factCache[key] = facts }
        return facts
    }

    private fun shopify(host: String, model: String): Facts? {
        val j = Http.getJsonCached("https://$host/search/suggest.json?q=${Http.enc(model)}&resources%5Btype%5D=product&resources%5Blimit%5D=8") ?: return null
        val arr = j.optJSONObject("resources")?.optJSONObject("results")?.optJSONArray("products") ?: return null
        val handles = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("handle")?.ifBlank { null } }
        val handle = bestUrl(model, handles.map { "https://$host/products/$it" })?.substringAfterLast('/') ?: return null
        val p = Http.getJsonCached("https://$host/products/$handle.js") ?: return null
        val html = "<title>${p.optString("title")}</title>" + p.optString("description")
        val price = p.optLong("price", -1).takeIf { it > 0 }?.let { it / 100.0 }
        return read("https://$host/products/$handle", html).copy(title = p.optString("title").ifBlank { null }, price = price)
    }

    private fun unescape(h: String) = h.replace("\\u003C", "<").replace("\\u003c", "<").replace("\\u003E", ">").replace("\\u003e", ">")
        .replace("\\u002F", "/").replace("\\u0026", "&").replace("\\\\\"", "\"").replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ")

    /** Page text, lowercased, without markup, styles, reviews or JSON punctuation. */
    fun clean(html: String): String {
        var s = html.replace(Regex("<script[^>]*application/ld\\+json[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        s = s.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        s = unescape(s).replace(Regex("<[^>]{0,2000}>"), " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&#8209;", "-")
        s = s.replace(Regex("[\"{}\\[\\]]|\\|\\|\\|"), " ").replace(' ', ' ').replace('‑', '-').replace('–', '-')
        return s.replace(Regex("\\s+"), " ").lowercase(us)
    }

    private fun near(t: String, cue: Regex, value: Regex, before: Int = 60, after: Int = 120): List<MatchResult> =
        cue.findAll(t).flatMap { c -> value.findAll(t.substring((c.range.first - before).coerceAtLeast(0), (c.range.last + after).coerceAtMost(t.length))) }.toList()

    private fun num(s: String) = s.replace(",", "").toDoubleOrNull()

    private fun titleCase(s: String) = s.split(' ').joinToString(" ") { w -> if (w.length <= 3 && w.any { it.isDigit() } || w.matches(Regex("[a-z]?\\d+[a-z]*"))) w.uppercase(us) else w.replaceFirstChar { it.uppercase() } }

    /** The JSON-LD price, if the page has one. */
    private fun ldPrice(html: String): Double? = Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .findAll(html).map { it.groupValues[1] }.filter { it.contains("\"Product\"") }
        .firstNotNullOfOrNull { Regex("\"price\"\\s*:\\s*\"?(\\d{1,4}(?:\\.\\d{1,2})?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

    private val SENSOR = Regex("\\b(hero 2|hero 25k|hero 16k|hero(?= sensor)|focus pro 45k|focus pro 35k(?: gen-2)?|focus pro 30k|focus pro 4k|focus\\+|focus x 26k|truemove (?:air|core|pro\\+?|3\\+?)|bamf 2\\.0|bamf|paw ?3395|paw ?3950|paw ?3370|pmw ?3360|pmw ?3389|marksman|darkfield)\\b(?: (?:optical )?sensor)?")
    private val SWITCH = Regex("\\b(lightforce hybrid|gx (?:red|blue|brown|optical)(?: (?:linear|tactile|clicky))?|gl (?:tactile|linear|clicky)|cherry mx [a-z0-9]+(?: [a-z]+)?|gateron (?:g pro |ks-\\d+ |jupiter |magnetic )?[a-z]+|kailh (?:box |choc |low-profile )?[a-z0-9]+|razer (?:green|yellow|orange|purple|red) (?:mechanical |linear |tactile |clicky )?switch|razer analog optical|analog optical switch|omnipoint [\\d.]+|lekker (?:l\\d+|linear\\d*|tikken)|keychron (?:k pro|super|jupiter|silk|nova) [a-z]+|mlx [a-z]+|opx|mgx|nx (?:red|blue|brown|snow|storm|moss|ocean)|hall[- ]effect|magnetic switch|optical switch)(?:es)?\\b")
    private val SOFTWARE = listOf("G HUB" to "g hub", "Logi Options+" to "logi options", "Razer Synapse" to "synapse", "Corsair iCUE" to "icue", "SteelSeries GG" to "steelseries gg",
        "Armoury Crate" to "armoury crate", "HyperX NGENUITY" to "ngenuity", "QMK" to "qmk", "VIA" to "qmk/via","Wootility" to "wootility", "Keychron Launcher" to "keychron launcher", "Glorious Core" to "glorious core")

    /** The specs on one product page. */
    fun read(url: String, html: String): Facts {
        val t = clean(html)
        val u = url.lowercase(us)
        // Shure's og:title is just "Vocal Microphone": use whichever title names the model in the address.
        val slug = tokens(u.substringAfterLast('/')).firstOrNull()
        val titles = listOfNotNull(Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1),
            Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))
        val title = titles.firstOrNull { t -> slug != null && t.lowercase(us).replace(Regex("[^a-z0-9]"), "").contains(slug) } ?: titles.firstOrNull()
        val kind = when {
            Regex("mice|mouse").containsMatchIn(u) -> Peripherals.Kind.MOUSE
            Regex("keyboard").containsMatchIn(u) -> Peripherals.Kind.KEYBOARD
            Regex("microphone|/mic|/p/wave-").containsMatchIn(u) -> Peripherals.Kind.MIC
            Regex("stream-?deck").containsMatchIn(u) -> Peripherals.Kind.CONTROLLER
            t.split(" mouse").size > t.split(" keyboard").size + 3 -> Peripherals.Kind.MOUSE
            t.split(" keyboard").size > t.split(" mouse").size + 3 -> Peripherals.Kind.KEYBOARD
            else -> null
        }
        val polling = near(t, Regex("polling|report(?:ing)? rate|hyperpolling"), Regex("(\\d[\\d,]{2,5})\\s?hz|(\\d)\\s?khz|(\\d)k\\s?(?:hz|polling)"))
            .mapNotNull { m -> m.groupValues[1].ifBlank { null }?.let { num(it)?.toInt() } ?: (m.groupValues[2].ifBlank { m.groupValues[3] }).toIntOrNull()?.times(1000) }
            .filter { it in 125..8000 }.maxOrNull()
        val bat = near(t, Regex("battery"), Regex("up to (\\d+(?:\\.\\d+)?)\\s?(hours|hrs|hr|h|days|months)\\b|(\\d+(?:\\.\\d+)?)\\s?(hours|hrs)\\b"), 80, 100).firstOrNull()
        val (battery, batteryH) = bat?.let { m ->
            val n = num(m.groupValues[1].ifBlank { m.groupValues[3] })?.takeIf { it > 0 } ?: return@let null to null
            val unit = m.groupValues[2].ifBlank { m.groupValues[4] }
            val h = when { unit.startsWith("day") -> n * 24; unit.startsWith("month") -> n * 720; else -> n }
            val word = when { unit.startsWith("day") -> "days"; unit.startsWith("month") -> "months"; else -> "hours" }
            "up to ${fmt(n)} $word" to h
        } ?: (null to null)
        val dpi = if (kind == Peripherals.Kind.KEYBOARD || kind == Peripherals.Kind.MIC) null else (Regex("(\\d{1,2},\\d{3}|\\d{3,5})\\s?(?:dpi|cpi)\\b").findAll(t).mapNotNull { num(it.groupValues[1])?.toInt() } +
            Regex("\\b(\\d{2})k\\s?(?:dpi|cpi)\\b").findAll(t).mapNotNull { it.groupValues[1].toIntOrNull()?.times(1000) })
            .filter { it in 1000..50000 }.maxOrNull()
        val weight = if (kind == Peripherals.Kind.MOUSE || kind == null) near(t, Regex("\\bweigh(?:t|s|ing)?\\b|ultra-?light"), Regex("\\b(\\d{2,3}(?:\\.\\d)?)\\s?(?:g|grams)\\b"), 10, 60)
            .mapNotNull { num(it.groupValues[1]) }.firstOrNull { it in 30.0..200.0 } else null
        val sensor = if (kind == Peripherals.Kind.KEYBOARD || kind == Peripherals.Kind.MIC) null else mostCommon(SENSOR, t)?.let { titleCase(it).replace("Hero", "HERO").replace("Bamf", "BAMF").replace("Paw", "PAW").replace("Pmw", "PMW").replace("Truemove", "TrueMove") }
        val switches = if (kind != Peripherals.Kind.MOUSE) (title?.let { SWITCH.find(unescape(it).lowercase(us).replace(Regex("[®™]"), ""))?.groupValues?.get(1) } ?: mostCommon(SWITCH, t))?.let { titleCase(it).replace("Mx", "MX").replace("Gx", "GX").replace("Gl ", "GL ").replace("Nx", "NX").replace("Mlx", "MLX").replace("Opx", "OPX").replace("Mgx", "MGX").replace("Omnipoint", "OmniPoint") } else null
        val keycaps = if (kind == Peripherals.Kind.MOUSE) null else mostCommon(Regex("\\b(double[- ]shot pbt|dye[- ]sub(?:limated)? pbt|pbt|double[- ]shot abs|abs)\\s?(?:keycaps|key caps)"), t)?.let { it.replace("pbt", "PBT").replace("abs", "ABS") + " keycaps" }
        val hotSwap = kind != Peripherals.Kind.MOUSE && Regex("\\bhot[- ]?swap").containsMatchIn(t)
        val software = SOFTWARE.filter { (_, cue) -> t.contains(cue) }.map { it.first }
        // A mic page also lists the maker's other mics (Shure's SM7B page names Bluetooth and USB-C models): a mic keeps only the ways the page mentions most.
        val mic = kind == Peripherals.Kind.MIC
        val conn = listOf(
            "2.4 GHz wireless (USB receiver)" to Regex("\\b2\\.4\\s?ghz|lightspeed|hyperspeed|slipstream|quantum 2\\.0|speednova|hyperpolling wireless"),
            "Logi Bolt receiver" to Regex("\\blogi bolt\\b"), "Bluetooth" to Regex("bluetooth"),
            "Wired (USB-C)" to Regex("\\busb[- ]?c\\b|type[- ]c"), "XLR" to Regex("\\bxlr\\b"),
        ).filter { mic || it.first != "XLR" }.map { (n, rx) -> n to rx.findAll(t).count() }.filter { it.second > 0 }
        val connTop = conn.maxOfOrNull { it.second } ?: 0
        val connections = conn.filter { !mic || it.second * 4 >= connTop }.map { it.first }.toMutableList().apply {
            if ("Wired (USB-C)" !in this && !mic && Regex("\\bwired\\b|\\busb\\b").containsMatchIn(t)) add(size - (if ("XLR" in this) 1 else 0), "Wired (USB)")
            // A title that says how it plugs in ("Wave Neo | USB Condenser Microphone") beats the rest of the page.
            val head = titles.joinToString(" ").lowercase(us)
            if (mic && Regex("\\busb\\b").containsMatchIn(head) && !head.contains("xlr")) { remove("XLR"); if (isEmpty()) add("Wired (USB)") }
            if (mic && head.contains("xlr") && !Regex("\\busb\\b").containsMatchIn(head)) retainAll { it == "XLR" }
        }
        val wheel = if (kind == Peripherals.Kind.MOUSE || kind == null) {
            val free = Regex("infinite scroll|free[- ]?spin|hyper-?fast scroll|magspeed|freewheel|free-wheel").containsMatchIn(t)
            val tilt = Regex("\\btilt\\b").containsMatchIn(t)
            val smart = Regex("smartshift|smart-?reel").containsMatchIn(t)
            when {
                free -> "two modes: stepped or infinite free-spin" + (if (smart) ", switching on its own when you flick it" else "") + (if (tilt) ", plus left and right tilt" else "")
                else -> null
            }
        } else null
        val buttons = Regex("\\b(\\d{1,2}) (?:fully )?(?:programmable|customi[sz]able) (?:buttons|controls)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2..25 }
        val micType = if (kind == Peripherals.Kind.MIC || kind == null) mostCommon(Regex("\\b(dynamic|condenser)\\b(?= (?:cartridge|capsule|microphones?|mics?)\\b)"), t)?.replaceFirstChar { it.uppercase() } else null
        // Patterns the page names at least half as often as its main one (a multi-pattern mic names each often; a "related mics" strip names others once).
        // "Stereo" counts only as a pickup mode ("stereo mode"), not the "3.5 mm stereo" headphone jack.
        val patterns = Regex("\\b(supercardioid|hypercardioid|cardioid|omnidirectional|bidirectional|figure[- ]8|stereo(?= (?:mode|pattern|pickup|recording)))\\b").findAll(t).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val patTop = patterns.values.maxOrNull() ?: 0
        val pattern = if (micType != null || mic) patterns.filter { it.value * 2 >= patTop }.keys.toList()
            .ifEmpty { null }?.joinToString(", ")?.replaceFirstChar { it.uppercase() } else null
        val audio = Regex("\\b(16|24|32)[- ]bit\\s?/\\s?(\\d{2,3}(?:\\.\\d)?)\\s?khz").find(t)?.let { "${it.groupValues[1]}-bit/${it.groupValues[2]} kHz" }
        // Corsair: "K70 RGB PRO Mechanical Gaming Keyboard with PBT DOUBLE SHOT PRO Keycaps — CHERRY® MX Brown", "M75 WIRELESS ... Mouse, Black".
        val name = title?.let { unescape(it).replace("&amp;", "&").substringBefore(" | ").substringBefore(" — ").replace(Regex(" with .*$|, [A-Z][A-Za-z]+(?: [A-Z][a-z]+)?$"), "").removePrefix("Buy ").replace(Regex(" - (?:US|Black|White|Pink|Mercury|Quartz|[A-Z][a-z]+(?: [A-Z][a-z]+)?)(?: - .*)?$"), "").trim() }
        val facts = Facts(url, name, ldPrice(html), kind, polling, battery, batteryH, dpi, weight,
            sensor, switches, keycaps, hotSwap, software, connections, wheel, buttons, micType, pattern, audio)
        return name?.let { specRows(html, it) }?.takeIf { it.isNotEmpty() }?.let { withRows(facts, it) } ?: facts
    }

    /**
     * Elgato's spec table for this product ("Comparison / Wave:3 / AUDIO / Polar pattern" -> "Cardioid"), headline to value.
     * The page holds tables for its sister products too; the one whose name is the longest start of [name] is this product's.
     */
    fun specRows(html: String, name: String): Map<String, String> {
        val rows = Regex("Comparison / ([^/\"]+?) / [^\"]*\",\"headline\":\"([^\"]+)\"[^{}]*?\"featureInfo\":\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(html)
            .map { Triple(it.groupValues[1].trim(), it.groupValues[2].trim(), unescape(it.groupValues[3]).replace(Regex("<[^>]*>|&lt;br&gt;|&amp;lt;br&amp;gt;|\\\\n"), " ").replace(Regex("\\s+"), " ").trim()) }.toList()
        val norm = { s: String -> s.lowercase(us).replace(Regex("[^a-z0-9+]"), "") }
        // "Wave:3 MK.2" and "Wave:3 Mk.2" are one table; rows it leaves out come from the model it updates ("Wave:3").
        val names = rows.map { norm(it.first) }.distinct().filter { it.isNotEmpty() && norm(name).startsWith(it) }.sortedByDescending { it.length }
        if (names.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((i, n) in names.withIndex()) for (r in rows) if (norm(r.first) == n) {
            val k = r.second.lowercase(us)
            // An older model's numbers (bit depth, sample rate) may not hold for this one; its kind of capsule, pattern and plug do.
            if (i == 0 || k in INHERIT) out.putIfAbsent(k, r.third)
        }
        return out
    }

    private val INHERIT = setOf("capsule type", "polar pattern", "connection", "connection type", "connectivity")

    private fun withRows(f: Facts, r: Map<String, String>): Facts {
        val conn = (r["connection"] ?: r["connection type"] ?: r["connectivity"])?.let { c ->
            listOfNotNull(if (Regex("(?i)usb[- ]?c|type[- ]c").containsMatchIn(c)) "Wired (USB-C)" else if (c.contains("usb", true)) "Wired (USB)" else null,
                if (c.contains("xlr", true)) "XLR" else null, if (c.contains("bluetooth", true)) "Bluetooth" else null,
                if (Regex("(?i)wireless|2\\.4").containsMatchIn(c)) "2.4 GHz wireless (USB receiver)" else null).ifEmpty { null }
        }
        val audio = listOfNotNull(r["bit depth"], r["sample rate"]).takeIf { it.size == 2 }?.joinToString("/")
        return f.copy(
            micType = r["capsule type"]?.takeIf { Regex("(?i)dynamic|condenser").containsMatchIn(it) } ?: f.micType,
            pattern = r["polar pattern"] ?: f.pattern,
            connections = conn ?: f.connections,
            audio = audio ?: f.audio,
            keys = r["keys"]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: f.keys,
        )
    }

    /** The match a page repeats most: the product's own spec, not one from a "you may also like" strip. */
    private fun mostCommon(rx: Regex, t: String): String? = rx.findAll(t).map { it.groupValues[1] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}
