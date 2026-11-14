package com.brick.assistant.brain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A store's current price, with its regular price when the store says (or Brick remembers) one, so a sale shows as
 * "50% off, save $12.50". [remembered] means the regular price is the highest Brick has seen there, not the store's own.
 */
data class Price(
    val store: String,
    val now: Double,
    val regular: Double? = null,
    val url: String? = null,
    val ends: String? = null,
    val remembered: Boolean = false,
) {
    val onSale: Boolean get() = regular != null && regular - now >= 0.01 && off >= 1
    val off: Int get() = regular?.takeIf { it > 0 }?.let { ((1 - now / it) * 100).roundToInt() } ?: 0
    val saved: Double get() = regular?.let { (it - now).coerceAtLeast(0.0) } ?: 0.0

    /** "$12.49 (50% off, save $12.50)", "$24.99" or "Free". */
    fun shown(): String = when {
        now <= 0.0 -> "Free"
        onSale -> "${Prices.money(now)} (${off}% off, save ${Prices.money(saved)})"
        else -> Prices.money(now)
    }
}

/** A game on sale somewhere, for the deals list. */
data class Deal(val title: String, val price: Price, val score: Int?, val steamId: String?)

object Prices {
    fun money(v: Double) = "$" + "%.2f".format(Locale.US, v)

    // ================================================================== remembered prices (Apple's stores give no regular price)

    private var file: File? = null
    private val seen = HashMap<String, Pair<Double, Long>>() // key -> highest price seen, when last seen

    fun init(context: Context) {
        file = File(context.filesDir, "prices_seen.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            val cutoff = System.currentTimeMillis() - 365L * 24 * 3600_000
            for (k in o.keys()) o.optJSONArray(k)?.let { a -> if (a.optLong(1) > cutoff) seen[k] = a.optDouble(0) to a.optLong(1) }
        }
    }

    /**
     * Notes [now] for [key] (a store page) and returns the price with the highest earlier price as its regular one, so a
     * drop since Brick last looked shows as a sale. Only on this phone; nothing is sent anywhere.
     */
    fun remember(store: String, key: String, now: Double?, url: String? = key): Price? {
        if (now == null || now <= 0) return null
        val t = System.currentTimeMillis()
        val high = synchronized(seen) {
            val before = seen[key]?.first
            seen[key] = maxOf(before ?: 0.0, now) to t
            if (seen.size > 600) seen.entries.sortedBy { it.value.second }.take(seen.size - 600).forEach { seen.remove(it.key) }
            save()
            before
        }
        return Price(store, now, high?.takeIf { it > now }, url, remembered = high != null && high > now)
    }

    private fun save() {
        val f = file ?: return
        runCatching { f.writeText(JSONObject().apply { for ((k, v) in seen) put(k, JSONArray().put(v.first).put(v.second)) }.toString()) }
    }

    // ================================================================== games

    private fun fetchText(url: String, timeout: Int = 10000): String? = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "BrickAssistant/1.0 (Android personal assistant app)")
        val body = if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        c.disconnect()
        body
    } catch (e: Exception) {
        null
    }

    /** Steam's own price, with its sale (Steam gives the regular price and the discount). */
    fun steam(appId: String): Price? {
        val j = Http.getJsonCached("https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&filters=price_overview") ?: return null
        val d = j.optJSONObject(appId)?.takeIf { it.optBoolean("success") } ?: return null
        val url = "https://store.steampowered.com/app/$appId"
        val p = d.optJSONObject("data")?.optJSONObject("price_overview") ?: return if (d.optJSONArray("data") != null) Price("Steam", 0.0, url = url) else null
        return Price("Steam", p.optInt("final") / 100.0, p.optInt("initial").takeIf { it > 0 }?.let { it / 100.0 }, url)
    }

    /** PC stores CheapShark watches (Brick lists these; others it tracks are smaller resellers). */
    private val CHEAPSHARK_STORES = mapOf(
        "25" to "Epic Games Store", "7" to "GOG", "11" to "Humble Store", "15" to "Fanatical", "3" to "Green Man Gaming", "1" to "Steam",
    )

    fun storeUrl(store: String, title: String): String {
        val q = Http.enc(title)
        return when (store) {
            "Epic Games Store" -> "https://store.epicgames.com/en-US/browse?q=$q"
            "GOG" -> "https://www.gog.com/en/games?query=$q"
            "Humble Store" -> "https://www.humblebundle.com/store/search?search=$q"
            "Fanatical" -> "https://www.fanatical.com/en/search?search=$q"
            "Green Man Gaming" -> "https://www.greenmangaming.com/search/?query=$q"
            else -> "https://store.steampowered.com/search/?term=$q"
        }
    }

    /** PC store prices (Epic, GOG, Humble, Fanatical, Green Man Gaming) from CheapShark, by Steam id or exact title. */
    fun pcStores(steamId: String?, title: String): List<Price> {
        val id = if (steamId != null) {
            Http.getJsonArray("https://www.cheapshark.com/api/1.0/games?steamAppID=$steamId&limit=1")?.optJSONObject(0)?.optString("gameID")
        } else {
            Http.getJsonArray("https://www.cheapshark.com/api/1.0/games?title=${Http.enc(title)}&exact=1&limit=1")?.optJSONObject(0)?.optString("gameID")
        }?.ifBlank { null } ?: return emptyList()
        val deals = Http.getJsonCached("https://www.cheapshark.com/api/1.0/games?id=$id")?.optJSONArray("deals") ?: return emptyList()
        return (0 until deals.length()).mapNotNull { deals.optJSONObject(it) }.mapNotNull { d ->
            val store = CHEAPSHARK_STORES[d.optString("storeID")]?.takeIf { it != "Steam" } ?: return@mapNotNull null
            val now = d.optString("price").toDoubleOrNull() ?: return@mapNotNull null
            Price(store, now, d.optString("retailPrice").toDoubleOrNull(), storeUrl(store, title))
        }.distinctBy { it.store }
    }

    private val NSUID = Patterns.get("\"pageType\":\"Game Detail Page\".{0,200}?\"nsuid\":\"(\\d{14})\"")

    /** The Nintendo eShop's price (and sale, with its end date) from the game's store page id on Wikidata. */
    fun nintendo(slug: String): Price? {
        val url = "https://www.nintendo.com/us/store/products/$slug/"
        val page = fetchText(url, 15000) ?: return null
        val nsuid = NSUID.find(page)?.groupValues?.get(1) ?: return null
        val p = Http.getJsonCached("https://api.ec.nintendo.com/v1/price?country=US&lang=en&ids=$nsuid")?.optJSONArray("prices")?.optJSONObject(0) ?: return null
        if (p.optString("sales_status") != "onsale") return null
        val regular = p.optJSONObject("regular_price")?.optString("raw_value")?.toDoubleOrNull() ?: return null
        val sale = p.optJSONObject("discount_price")
        val now = sale?.optString("raw_value")?.toDoubleOrNull() ?: regular
        val ends = sale?.optString("end_datetime")?.take(10)?.ifBlank { null }
        return Price("Nintendo eShop", now, regular, url, ends)
    }

    /** The Microsoft Store's (Xbox) price and sale, found by title. */
    fun xbox(title: String): Price? {
        val s = Http.getJsonCached("https://displaycatalog.mp.microsoft.com/v7.0/productFamilies/autosuggest?market=US&languages=en-us&query=${Http.enc(title)}&productFamilyNames=Games")
            ?.optJSONArray("Results") ?: return null
        val core = Franchise.core(title)
        val products = (0 until s.length()).mapNotNull { s.optJSONObject(it)?.optJSONArray("Products") }.flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        val hit = products.firstOrNull { it.optString("Type") == "Game" && Franchise.core(it.optString("Title")) == core } ?: return null
        val id = hit.optString("ProductId").ifBlank { return null }
        val prod = Http.getJsonCached("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=$id&market=US&languages=en-us")
            ?.optJSONArray("Products")?.optJSONObject(0) ?: return null
        val skus = prod.optJSONArray("DisplaySkuAvailabilities") ?: return null
        var best: Price? = null
        for (i in 0 until skus.length()) {
            val av = skus.optJSONObject(i)?.optJSONArray("Availabilities") ?: continue
            for (j in 0 until av.length()) {
                val a = av.optJSONObject(j) ?: continue
                val actions = a.optJSONArray("Actions")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
                if ("Purchase" !in actions) continue
                val price = a.optJSONObject("OrderManagementData")?.optJSONObject("Price") ?: continue
                val now = price.optDouble("ListPrice").takeIf { !it.isNaN() } ?: continue
                val msrp = price.optDouble("MSRP").takeIf { !it.isNaN() && it > 0 }
                val ends = a.optJSONObject("Conditions")?.optString("EndDate")?.take(10)?.takeIf { msrp != null && msrp > now && !it.startsWith("9998") }
                val p = Price("Xbox Store", now, msrp, "https://www.xbox.com/en-US/games/store/p/$id", ends)
                // A $0 "purchase" next to a paid one is Game Pass or a trial, not the price.
                if (best == null || (p.now < best.now && p.now > 0) || best.now <= 0) best = p
            }
            if (best != null) break // the first SKU is the game itself; later ones are bundles
        }
        return best
    }

    /** Every store Brick can price for a game, checked at once. */
    suspend fun games(w: WorkInfo): List<Price> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf(
                async { listOfNotNull(w.steamId?.let { runCatching { steam(it) }.getOrNull() }) },
                async { if (w.steamId != null || w.epicId != null || w.gogId != null) runCatching { pcStores(w.steamId, w.title) }.getOrDefault(emptyList()) else emptyList() },
                async { listOfNotNull(w.eshop?.let { runCatching { nintendo(it) }.getOrNull() }) },
                async { if (w.platforms.any { it in setOf("Q132020", "Q48263", "Q13361286", "Q64513817", "Q98973368") } || w.platforms.isEmpty()) listOfNotNull(runCatching { xbox(w.title) }.getOrNull()) else emptyList() },
            ).awaitAll().flatten()
        }
    }

    // ================================================================== the deals list (PC stores, through CheapShark)

    val DEAL_STORES = mapOf("steam" to "1", "gog" to "7", "epic" to "25", "epic games" to "25", "humble" to "11", "fanatical" to "15", "green man gaming" to "3", "gmg" to "3")

    /** Games on sale now, best deals first: one store or all, under a price, only well-reviewed ones when [good]. */
    suspend fun deals(store: String?, under: Double?, good: Boolean, title: String? = null): List<Deal> = withContext(Dispatchers.IO) {
        val params = buildList {
            add("pageSize=40"); add("onSale=1"); add("sortBy=${Http.enc("Deal Rating")}")
            add("storeID=" + (store?.let { DEAL_STORES[it] } ?: CHEAPSHARK_STORES.keys.joinToString(",")))
            under?.let { add("upperPrice=${it.roundToInt()}") }
            if (good) add("steamRating=80")
            title?.let { add("title=${Http.enc(it)}") }
        }
        val a = Http.getJsonArray("https://www.cheapshark.com/api/1.0/deals?" + params.joinToString("&")) ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { d ->
            val storeName = CHEAPSHARK_STORES[d.optString("storeID")] ?: return@mapNotNull null
            val now = d.optString("salePrice").toDoubleOrNull() ?: return@mapNotNull null
            val title0 = d.optString("title").ifBlank { return@mapNotNull null }
            val steamId = d.optString("steamAppID").ifBlank { null }?.takeIf { it != "null" }
            val url = if (storeName == "Steam" && steamId != null) "https://store.steampowered.com/app/$steamId" else storeUrl(storeName, title0)
            Deal(title0, Price(storeName, now, d.optString("normalPrice").toDoubleOrNull(), url),
                d.optString("steamRatingPercent").toIntOrNull()?.takeIf { it > 0 } ?: d.optString("metacriticScore").toIntOrNull()?.takeIf { it > 0 }, steamId)
        }.filter { it.price.onSale }.distinctBy { base(it.title) }
    }

    /** "Borderlands 3 Super Deluxe Edition (EPIC)" and "Borderlands 3: Ultimate Edition" are one game in a list. */
    private fun base(title: String) = Franchise.core(
        title.replace(Patterns.get("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Patterns.get("(?i)[:\\-–]?\\s*\\b(?:super |digital |game of the year |goty |definitive |complete |ultimate |deluxe |gold |premium |standard |enhanced |anniversary |special |collector'?s |legendary |royal )*(?:edition|bundle|pack|collection)\\b.*$"), "")
            .replace(Patterns.get("(?i)\\b(?:goty|remastered|director'?s cut)\\b"), "")
    )

    // ================================================================== movies and TV (CheapCharts' free public API)

    private const val CC = "https://public-api.cheapcharts.com/v1"

    /** CheapCharts can take several seconds on a long list: a longer wait than other lookups, and one retry. */
    private fun ccJson(url: String): JSONObject? {
        Http.getJsonCached(url)?.let { return it }
        return fetchText(url, 20000)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    /** CheapCharts' store ids and the names Brick's movie cards use. */
    val VIDEO_STORES = linkedMapOf("itunes" to "Apple TV", "amazon" to "Prime Video", "vudu" to "Fandango at Home", "googlePlay" to "Google TV")

    data class VideoPrices(val prices: List<Price>, val lowest: Price?, val page: String?)

    private fun ccItems(j: JSONObject?, type: String): List<JSONObject> {
        val r = j?.takeIf { it.optString("status") == "success" } ?: return emptyList()
        val a = r.optJSONObject("results")?.optJSONArray(type) ?: r.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun ccPrice(o: JSONObject, store: String): Price? {
        val now = o.optDouble("price").takeIf { !it.isNaN() && it > 0 } ?: return null
        val before = o.optDouble("priceBefore").takeIf { !it.isNaN() && it > 0 }
        return Price(VIDEO_STORES[store] ?: store, now, before, null)
    }

    /** CheapCharts' page without its tracking tags. */
    private fun ccPage(o: JSONObject) = (o.optString("detail_url").ifBlank { o.optString("cheapChartsProductPageUrl") }).substringBefore("?").ifBlank { null }

    /**
     * A movie's (or one TV season's) buy price at Apple TV, Prime Video, Fandango at Home and Google TV, each with the price
     * before its latest change (so a sale shows), and Apple's all-time low. Movies are matched by IMDb id; a season by
     * Apple's store id (CheapCharts' title search is too loose for seasons).
     */
    suspend fun videos(title: String, year: String?, tv: Boolean, appleUrl: String?): VideoPrices = withContext(Dispatchers.IO) {
        val appleId = appleUrl?.let { Patterns.get("/id(\\d+)").find(it)?.groupValues?.get(1) }
        if (tv) {
            val id = appleId ?: return@withContext VideoPrices(emptyList(), null, null)
            return@withContext coroutineScope {
                val now = async { ccItems(ccJson("$CC/Prices.php?action=getPrices&store=itunes&country=us&itemType=seasons&idInStore=$id"), "seasons").firstOrNull() }
                val hist = async { ccItems(ccJson("$CC/Prices.php?action=getPriceHistory&store=itunes&country=us&itemType=seasons&idInStore=$id&quality=hd"), "seasons").firstOrNull() }
                val o = now.await()
                VideoPrices(listOfNotNull(o?.let { ccPrice(it, "itunes") }), hist.await()?.let { lowest(it, "itunes") }, o?.let { ccPage(it) })
            }
        }
        // The movie's IMDb id from CheapCharts' search (title and year), or Apple's own id.
        val hits = ccItems(ccJson("$CC/Search.php?action=search&store=itunes&country=us&itemType=buymovies&query=${Http.enc(title)}&limit=10"), "results")
        val core = Franchise.core(title)
        val hit = hits.filter { Franchise.core(it.optString("title")) == core }
            .minByOrNull { h -> year?.toIntOrNull()?.let { y -> kotlin.math.abs((h.optString("releaseYear").toIntOrNull() ?: 0) - y) } ?: 0 }
            ?: hits.firstOrNull { appleId != null && it.optString("idInStore") == appleId }
        val imdb = hit?.optString("imdbId")?.ifBlank { null }
        val ids = imdb?.let { "imdbIDs=$it" } ?: (hit?.optString("idInStore")?.ifBlank { null } ?: appleId)?.let { "idInStore=$it" }
            ?: return@withContext VideoPrices(emptyList(), null, null)
        coroutineScope {
            val stores = VIDEO_STORES.keys.filter { imdb != null || it == "itunes" }.map { s ->
                async { s to ccItems(ccJson("$CC/Prices.php?action=getPrices&store=$s&country=us&itemType=buymovies&$ids"), "buymovies").firstOrNull() }
            }
            val hist = async { ccItems(ccJson("$CC/Prices.php?action=getPriceHistory&store=itunes&country=us&itemType=buymovies&$ids&quality=hd"), "buymovies").firstOrNull() }
            val found = stores.awaitAll()
            VideoPrices(found.mapNotNull { (s, o) -> o?.let { ccPrice(it, s) } }, hist.await()?.let { lowest(it, "itunes") },
                found.firstOrNull { it.first == "itunes" }?.second?.let { ccPage(it) } ?: hit?.let { ccPage(it) })
        }
    }

    private fun lowest(o: JSONObject, store: String): Price? {
        val l = o.optJSONObject("lowest_price") ?: return null
        val p = l.optDouble("price").takeIf { !it.isNaN() && it > 0 } ?: return null
        return Price(VIDEO_STORES[store] ?: store, p, ends = l.optString("date").ifBlank { null })
    }

    val VIDEO_GENRES = linkedMapOf(
        "action|adventure" to "ActionAdventure", "comed(?:y|ies)|funny" to "Comedy", "documentar(?:y|ies)|docs?" to "Docus", "dramas?" to "Drama",
        "horror|scary" to "Horror", "classics?|old movies" to "Classical", "romance|romantic|rom-?coms?" to "Romance", "indie|independent" to "Independent",
        "kids?|family|children'?s?" to "KidsFamily", "sci-?fi|science fiction|fantasy" to "SciFiFantasy", "sports?" to "Sport", "thrillers?" to "Thriller",
        "westerns?" to "Western", "anime" to "Anime", "musicals?" to "Musicals",
    )

    /** Movies or TV seasons on sale at one store (Apple TV by default): under a price, a genre, years, well-reviewed. */
    suspend fun videoDeals(tv: Boolean, store: String?, under: Double?, good: Boolean, genre: String?, years: Pair<Int?, Int?>?): List<Deal> = withContext(Dispatchers.IO) {
        val s = store ?: "itunes"
        val type = if (tv) "seasons" else "buymovies"
        val params = buildList {
            add("action=getDeals"); add("store=$s"); add("country=us"); add("itemType=$type"); add("limit=40")
            add("sort=" + if (good || genre != null || years != null) "greatestPercentageSavings" else "popularity")
            genre?.let { add("genre=$it") }
            under?.let { add("maxPrice=${"%.2f".format(Locale.US, it)}") }
            if (good) add(if (tv) "imdbRating=7.5" else "rottenTomatoesRating=75")
            years?.let { (a, b) -> add("releaseYear=${a ?: 1900}-${b ?: java.time.LocalDate.now().year}") }
        }
        val items = ccItems(ccJson("$CC/Deals.php?" + params.joinToString("&")), type)
        items.mapNotNull { o ->
            val p = ccPrice(o, s)?.copy(url = ccPage(o)) ?: return@mapNotNull null
            val score = o.optInt("rottenTomatoesRating").takeIf { it > 0 } ?: o.optDouble("imdbRating").takeIf { !it.isNaN() && it > 0 }?.let { (it * 10).roundToInt() }
            Deal(o.optString("title").ifBlank { return@mapNotNull null }, p, score, null)
        }.filter { it.price.onSale }.distinctBy { Franchise.core(it.title) }
    }

    // ================================================================== books

    /** Google Play Books' ebook price, with its list price when it's discounted (Google gives both). */
    fun googleBook(title: String, author: String?): Price? {
        val q = "intitle:$title" + (author?.let { " inauthor:${it.substringAfterLast(' ')}" } ?: "")
        val items = Http.getJsonCached("https://www.googleapis.com/books/v1/volumes?q=${Http.enc(q)}&filter=paid-ebooks&country=US&maxResults=10&printType=books")
            ?.optJSONArray("items") ?: return null
        val core = Franchise.core(title)
        val hit = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.firstOrNull { o ->
            val v = o.optJSONObject("volumeInfo") ?: return@firstOrNull false
            Franchise.core(v.optString("title")) == core && o.optJSONObject("saleInfo")?.optString("saleability") == "FOR_SALE"
        } ?: return null
        val sale = hit.optJSONObject("saleInfo") ?: return null
        val now = sale.optJSONObject("retailPrice")?.optDouble("amount")?.takeIf { !it.isNaN() } ?: return null
        val list = sale.optJSONObject("listPrice")?.optDouble("amount")?.takeIf { !it.isNaN() }
        return Price("Google Play Books", now, list, sale.optString("buyLink").ifBlank { null } ?: "https://play.google.com/store/search?q=${Http.enc(title)}&c=books")
    }

    /** Apple Books' ebook price (Apple gives no list price, so a drop is measured against what Brick saw before). */
    fun appleBook(title: String, author: String?): Price? {
        val r = Http.getJsonCached("https://itunes.apple.com/search?term=${Http.enc(listOfNotNull(title, author).joinToString(" "))}&media=ebook&country=US&limit=10")
            ?.optJSONArray("results") ?: return null
        val core = Franchise.core(title)
        val hit = (0 until r.length()).mapNotNull { r.optJSONObject(it) }.firstOrNull { Franchise.core(it.optString("trackName")) == core } ?: return null
        val url = hit.optString("trackViewUrl").substringBefore("?").ifBlank { return null }
        return remember("Apple Books", url, hit.optDouble("price").takeIf { !it.isNaN() })
    }

    suspend fun books(title: String, author: String?): List<Price> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf(async { runCatching { googleBook(title, author) }.getOrNull() }, async { runCatching { appleBook(title, author) }.getOrNull() })
                .awaitAll().filterNotNull()
        }
    }

    // ================================================================== saying it

    /** "It's on sale at Steam: $12.49, 50% off (you save $12.50), until Dec 1." for the best sale, or null. */
    fun saleLine(title: String, prices: List<Price>): String? {
        val sales = prices.filter { it.onSale }.sortedByDescending { it.saved }
        if (sales.isEmpty()) return null
        val best = sales.first()
        val until = best.ends?.let { e -> runCatching { java.time.LocalDate.parse(e) }.getOrNull() }
            ?.let { ", until ${it.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)} ${it.dayOfMonth}" } ?: ""
        val vs = if (best.remembered) "${best.off}% below the ${money(best.regular!!)} I saw before" else "${best.off}% off"
        val more = sales.drop(1).take(2).map { "${theStore(it.store)} has it ${it.off}% off" }
        return "$title is on sale at ${theStore(best.store)}: ${money(best.now)}, $vs, so you save ${money(best.saved)}$until." +
            (if (more.isNotEmpty()) " " + more.joinToString(" and ").replaceFirstChar { it.uppercase() } + "." else "")
    }

    fun theStore(n: String) = if (n.endsWith("Store") || n == "Nintendo eShop") "the $n" else n

    /** "Its lowest price on Apple TV was $4.99, in June 2024." (or that it's at its lowest now). */
    fun lowestLine(lowest: Price?, prices: List<Price>): String? {
        val l = lowest ?: return null
        val now = prices.firstOrNull { it.store == l.store }?.now ?: return null
        val at = l.ends?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?.let { " in ${it.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)} ${it.year}" } ?: ""
        return if (now <= l.now + 0.005) "That's the lowest it's ever been on ${l.store}."
        else "Its lowest on ${l.store} was ${money(l.now)}$at."
    }

    fun noSaleLine(title: String, prices: List<Price>): String {
        val cheapest = prices.filter { it.now > 0 }.minByOrNull { it.now }
        return if (cheapest == null) "I couldn't find a price for $title right now."
        else "$title isn't on sale anywhere I can check. The cheapest is ${money(cheapest.now)} at ${theStore(cheapest.store)}."
    }
}
