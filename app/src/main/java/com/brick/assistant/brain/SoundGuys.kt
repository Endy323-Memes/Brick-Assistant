package com.brick.assistant.brain

import java.util.Locale

/**
 * SoundGuys' product database for headphones, earbuds, IEMs and speakers: each product page carries its specs as
 * structured data (battery, IP rating, codecs, drivers, impedance, weight, power output...), SoundGuys' score out of 10
 * and today's price. Brick reads only those facts and the score, never the reviews' own words, and links to the page.
 * Product addresses come from the catalog sitemap (allowed by robots.txt; search and the API aren't used).
 */
object SoundGuys {
    private val us = Locale.US
    private const val SITEMAP = "https://www.soundguys.com/sitemap-products.xml?page=1"

    data class Product(
        val url: String, val name: String, val brand: String?, val category: String?,
        val specs: Map<String, String>, val score: Double?, val price: Double?,
    ) {
        fun spec(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> specs[k]?.trim()?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) !in BLANK } }
        fun num(vararg keys: String): Double? = spec(*keys)?.let { Regex("\\d+(?:\\.\\d+)?").find(it.replace(",", ""))?.value?.toDoubleOrNull() }
        fun yes(key: String): Boolean? = specs[key]?.trim()?.lowercase(Locale.US)?.let { v ->
            when { v == "true" || v.startsWith("yes") -> true; v == "false" || v.startsWith("no") || v == "none" -> false; else -> null }
        }
        /** A speaker: by its category, or by the specs only speakers have. */
        val speaker get() = category?.contains("Speakers") == true || "Total Power Output" in specs || ("Is Portable" in specs && "Stereo Pairing" in specs) ||
            ("Amplification" in specs && "Channels" in specs)
        val earbuds get() = category?.contains("Earbuds") == true || "Earbud Form Factor" in specs || "Battery Life Earbuds" in specs
    }
    private val BLANK = setOf("none", "n/a", "na", "-", "unknown", "false", "no")

    // ---------------------------------------------------------------- finding the page

    /** Cases, ear tips, stands and the like share the catalog: not what's asked about. */
    private val ACCESSORY = Regex("(?:^|-)(?:case|cases|cover|covers|tips|strap|straps|lanyard|hooks?|stand|stands|mount|cable|cables|adapter|replacement|skin|skins|holder|pouch|pads|cushions|charger|dock|protector|sleeve|bag)(?:-|$)")

    @Volatile private var urls: List<String>? = null

    /** Every product address (kept a day). */
    private fun productUrls(): List<String> {
        urls?.let { return it }
        val key = "soundguys-products"
        val saved = WebCache.read(key, WebCache.LOOKUP_MS)?.lines()?.filter { it.isNotBlank() }
        val list = saved?.takeIf { it.isNotEmpty() } ?: Http.getText(SITEMAP)?.let { xml ->
            Regex("<loc>\\s*([^<\\s]+)\\s*</loc>").findAll(xml).map { it.groupValues[1] }.filter { it.contains("/product/") }.toList()
        }?.takeIf { it.isNotEmpty() }?.also { WebCache.write(key, it.joinToString("\n")) } ?: return emptyList()
        urls = list
        return list
    }

    /** The slug, its ordinals folded ("airpods-pro-3rd-gen" -> "airpods-pro-3") so "airpods pro 3" finds it. */
    internal fun normalize(url: String): String = url.lowercase(us).trimEnd('/').substringAfterLast('/')
        .replace(Regex("(\\d+)(?:st|nd|rd|th)-gen(?:eration)?"), "$1").replace(Regex("-gen(?:eration)?(?=-|$)"), "").let(::roman)

    /** "chu-iii" and "chu iii" read as "chu 3", so either spelling finds the other. */
    private fun roman(s: String) = s.replace(Regex("(?<=[- ])(ii|iii|iv)(?=[- ]|$)")) { when (it.value) { "ii" -> "2"; "iii" -> "3"; else -> "4" } }

    /** The best product address for "[maker] [model]", or null. */
    internal fun bestUrl(maker: String, model: String, all: List<String>): String? {
        val products = all.filterNot { ACCESSORY.containsMatchIn(it.lowercase(us).trimEnd('/').substringAfterLast('/')) }
        val byNorm = products.associateBy { "https://www.soundguys.com/product/" + normalize(it) + "/" }
        val keys = byNorm.keys.toList()
        // "SoundLink Flex (2nd Gen)" -> "soundlink flex 2"; "BlackShark V2 Pro (2023)" -> "blackshark v2 pro".
        val model = model.replace(Regex("\\((?:19|20)\\d\\d\\)"), " ").replace(Regex("(?i)(\\d+)(?:st|nd|rd|th) gen(?:eration)?"), "$1").replace(Regex("[()]"), " ").trim().lowercase(us).let(::roman)
        val hit = GearLive.bestUrl("$maker $model", keys) ?: GearLive.bestUrl(model, keys)?.takeIf { u ->
            // Without the maker in the address, the model must still be specific: two words or a digit.
            GearLive.tokens(model).let { t -> t.size >= 2 || t.any { w -> w.any(Char::isDigit) } } && !u.contains("for-")
        }
        return hit?.let { byNorm[it] }
    }

    private val cache = HashMap<String, Product?>()

    /** SoundGuys' page for "[maker] [model]", or null (offline, not in the catalog, or no specs). Blocking. */
    fun lookup(maker: String, model: String): Product? {
        if (!GearLive.enabled) return null
        val key = "$maker|${model.lowercase(us)}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val p = runCatching {
            bestUrl(maker, model, productUrls())?.let { u -> Http.getText(u)?.let { read(u, it) } }
        }.getOrNull()?.takeIf { it.specs.size >= 3 }
        synchronized(cache) { cache[key] = p }
        return p
    }

    // ---------------------------------------------------------------- reading the page

    private fun unescape(s: String) = s.replace("\\u0026", "&").replace("\\u0027", "'").replace("\\\"", "\"").replace("\\/", "/").replace("&amp;", "&")

    /** The page's structured data: name, brand, category, spec values, score and price. */
    fun read(url: String, html: String): Product {
        val name = Regex("\"@type\":\"Product\",(?:\"@id\":\"[^\"]*\",)?\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(html)?.groupValues?.get(1)?.let(::unescape)
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val brand = Regex("\"brand\":\\{\"@type\":\"Brand\",\"name\":\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let(::unescape)
            ?: Regex("\"brand\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val category = Regex("\"category\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unescape)
        val specs = LinkedHashMap<String, String>()
        for (m in Regex("\\{\"@type\":\"PropertyValue\",\"name\":\"([^\"]+)\",\"value\":(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([-\\d.]+|true|false))").findAll(html))
            specs.putIfAbsent(m.groupValues[1], unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }))
        val rating = Regex("\"@type\":\"Rating\",\"ratingValue\":\"?([\\d.]+)\"?(?:,\"bestRating\":\"?([\\d.]+))?").find(html)
        val score = rating?.groupValues?.get(1)?.toDoubleOrNull()?.let { v ->
            val best = rating.groupValues[2].toDoubleOrNull() ?: 10.0
            if (best > 0) Math.round(v / best * 100) / 10.0 else null
        }?.takeIf { it in 0.0..10.0 }
        val price = Regex("\"@type\":\"Offer\"[^}]*?\"price\":\"?([\\d.]+)").find(html)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        return Product(url, name, brand, category, specs, score, price)
    }
}
