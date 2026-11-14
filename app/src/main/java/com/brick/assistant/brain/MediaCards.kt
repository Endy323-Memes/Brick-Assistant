package com.brick.assistant.brain

import java.util.Locale

/** The cards for ratings, where to watch / buy / listen, and song credits (same ASCII box look as the rest). */
object MediaCards {
    private val us = Locale.US

    private fun head(rows: MutableList<String>, title: String, sub: String?) {
        rows += Box.wrap(Box.ascii(title), max = 2)
        sub?.takeIf { it.isNotBlank() }?.let { rows += Box.wrap(Box.ascii(it), max = 2) }
    }

    /** The name on a source's button: short, the same on every card. */
    private fun shortName(name: String): String = when {
        name.startsWith("Apple TV") -> "Apple TV"
        name.startsWith("Fandango") -> "Fandango"
        name.startsWith("Google TV") -> "Google TV"
        name.startsWith("Amazon Prime") || name == "Prime Video" -> "Prime Video"
        name.startsWith("eBay") -> "eBay"
        name == "Barnes & Noble" -> "B&N"
        name.startsWith("iTunes") -> "iTunes"
        name.startsWith("Amazon") && name != "Amazon" -> "Amazon"
        name == "The Roku Channel" -> "Roku Channel"
        name.startsWith("PlayStation Store") -> "PS Store"
        name.startsWith("Nintendo eShop") -> "eShop"
        name.startsWith("Xbox Store") || name.startsWith("Microsoft Store") -> "Xbox Store"
        name.startsWith("Epic Games") -> "Epic"
        name.startsWith("Google Play Books") -> "Play Books"
        name.startsWith("BoardGameGeek") -> "BGG"
        name.startsWith("Miniature Market") -> "Mini Market"
        name.startsWith("Library") -> "Library"
        else -> name.substringBefore(" (").trim()
    }

    /** One button for every source the card lists, in the card's order (up to six: two rows). */
    internal fun sourceButtons(sources: List<Pair<String, String>>, open: (String) -> Unit): List<Control> =
        sources.distinctBy { shortName(it.first) }.take(6).map { (name, url) -> Control.Button(shortName(name)) { open(url) } }

    private fun videoSub(v: VideoInfo) = listOfNotNull(v.year, v.kind?.label?.let { if (it.startsWith("TV")) it else it.lowercase(us) }, v.certificate, v.network).joinToString(" · ")

    private fun bar(p: Int) = Box.bar((p + 5) / 10, 10)

    /** Review scores as bars, plus the age rating. */
    fun rating(v: VideoInfo, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        head(rows, v.title, videoSub(v))
        rows += ""
        val lines = MediaGuide.scoreLines(v.scores)
        for ((who, value, note) in lines) {
            val label = when (who) { "Rotten Tomatoes" -> "ROTTEN"; "Metacritic" -> "META"; "IMDb" -> "IMDB"; else -> who.uppercase(us).take(7) }
            val p = MediaGuide.percent(value)
            rows += Box.kv(label, (if (p != null) bar(p) + " " else "") + value + (note?.let { " $it" } ?: ""))
        }
        v.tmdb?.let { rows += Box.kv("TMDB", bar((it * 10).toInt()) + " %.1f/10 users".format(us, it)) }
        v.tvmaze?.let { rows += Box.kv("TVMAZE", bar((it * 10).toInt()) + " %.1f/10 viewers".format(us, it)) }
        if (lines.isEmpty() && v.tmdb == null && v.tvmaze == null) rows += Box.wrap("No review scores listed for this one.", max = 2)
        v.certificate?.let { rows += ""; rows += Box.kv("AGES", it + (certNote(it)?.let { n -> "  $n" } ?: "")) }
        rows += ""
        rows += Box.kv("SOURCE", listOfNotNull(if (lines.isNotEmpty() || v.certificate != null) "Wikidata" else null,
            v.tmdb?.let { "TMDB" }, v.tvmaze?.let { "TVmaze" }).joinToString(", ").ifEmpty { "Wikidata" })
        val controls = sourceButtons(listOf(
            "Rotten Tomatoes" to "https://www.rottentomatoes.com/search?search=${Http.enc(v.title)}",
            "IMDb" to "https://www.imdb.com/find/?q=${Http.enc(v.title)}",
            "Metacritic" to "https://www.metacritic.com/search/${Http.enc(v.title).replace("+", "%20")}/",
        ), open)
        return Readout(Box.build("RATINGS", rows), image = v.image, controls = controls)
    }

    private fun certNote(c: String): String? = when (c.uppercase(us)) {
        "G", "TV-G", "TV-Y" -> "all ages"
        "PG", "TV-PG", "TV-Y7" -> "some guidance"
        "PG-13", "TV-14" -> "13 and up"
        "R" -> "under 17 with a parent"
        "NC-17", "TV-MA" -> "adults"
        else -> null
    }

    /** Where to watch: subscription, free, and rent/buy, with what's confirmed. */
    fun watch(v: VideoInfo, free: Boolean, noKey: Boolean, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        head(rows, v.title, videoSub(v))
        fun section(label: String, spots: List<Spot>, empty: String?) {
            rows += ""
            rows += label
            if (spots.isEmpty()) empty?.let { rows += Box.wrap(it, "  ", indent = 2, max = 2) }
            for (s in spots) rows += Box.wrap(s.name + (s.note?.let { "  $it" } ?: ""), if (s.checked) "  * " else "  ? ", indent = 4, max = 2)
        }
        val freeSpots = v.spots.filter { it.access == Access.FREE }
        val stream = v.spots.filter { it.access == Access.SUBSCRIPTION || it.access == Access.SUB_OR_BUY && it.checked && it.note == "listed" }
        val buy = v.spots.filter { it.access == Access.RENT_BUY || it.access == Access.SUB_OR_BUY && it !in stream }
        val freeEmpty = "None found. Try Tubi, Pluto TV or The Roku Channel."
        if (free) section("FREE (with ads)", freeSpots, freeEmpty)
        section("STREAM (subscription)", stream, "None confirmed.")
        if (!free) section("FREE (with ads)", freeSpots, freeEmpty)
        section("RENT OR BUY", buy, null)
        rows += ""
        rows += Box.kv("KEY", "* listed  ? check there")
        rows += Box.kv("SOURCE", v.listingSource + (if (v.apple != null) ", Apple" else ""))
        if (noKey) rows += Box.wrap("Listings can be out of date. Add a free TMDB key in Settings for exact, current ones.", max = 3)
        // Free first when that's what was asked, then streaming; stores after (the order the card lists them).
        val ordered = (if (free) freeSpots + stream else stream + freeSpots) + buy
        return Readout(Box.build("WHERE TO WATCH", rows), image = v.image, controls = sourceButtons(ordered.map { it.name to it.url }, open))
    }

    private fun storeRows(rows: MutableList<String>, s: Store) {
        rows += Box.wrap(s.name + (s.price?.let { "  $it" } ?: ""), "  ", indent = 2, max = 2)
        for (p in s.pros) rows += Box.wrap(p, "    + ", indent = 6, max = 2)
        for (c in s.cons) rows += Box.wrap(c, "    - ", indent = 6, max = 2)
    }

    /** Where to buy a movie or show: digital stores with pros and cons, and disc retailers. */
    fun buyVideo(v: VideoInfo, stores: Pair<List<Store>, List<Store>>, rent: Boolean, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        head(rows, v.title, videoSub(v))
        val (digital, physical) = stores
        if (digital.isNotEmpty()) {
            rows += ""
            rows += if (rent) "RENT DIGITALLY" else "DIGITAL"
            for (s in digital) storeRows(rows, s)
            if (!rent && v.prices.isNotEmpty()) {
                v.lowest?.let { l ->
                    val at = l.ends?.let { d -> runCatching { java.time.LocalDate.parse(d) }.getOrNull() }
                        ?.let { " (${it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)} ${it.year})" } ?: ""
                    rows += Box.wrap("Lowest ever on ${l.store}: ${Prices.money(l.now)}$at", "  ", indent = 2, max = 2)
                }
                rows += Box.wrap("Prices from CheapCharts, which tracks these stores.", "  ", indent = 2, max = 2)
            }
            if (v.kind != LookupKind.TV_SHOW) rows += Box.wrap("Movies Anywhere (free) links movies from most big studios across Apple, Amazon, Google and Fandango.", "  ", indent = 2, max = 3)
        }
        if (physical.isNotEmpty()) {
            rows += ""
            rows += "ON DISC"
            for (s in physical) rows += Box.wrap("${s.name}  ${s.pros.first()}", "  ", indent = 4, max = 2)
            rows += Box.wrap("You keep it if a service drops it, and 4K Blu-ray looks and sounds best.", "    + ", indent = 6, max = 2)
            rows += Box.wrap("Needs a player and shelf space.", "    - ", indent = 6, max = 2)
            rows += Box.wrap("Best Buy stopped selling movies on disc in 2024.", "  ", indent = 2, max = 2)
        }
        // Digital stores first, then disc stores, as listed (eBay's used copies last).
        // CheapCharts (price history) right after the digital stores, so it isn't cut off by the button limit.
        val controls = sourceButtons(digital.map { it.name to it.url } + listOfNotNull(v.chartsPage?.takeIf { !rent }?.let { "CheapCharts" to it }) +
            physical.filter { !it.name.startsWith("eBay") }.map { it.name to it.url }, open)
        return Readout(Box.build(if (rent) "WHERE TO RENT" else "WHERE TO BUY", rows), image = v.image, controls = controls)
    }

    private fun songSub(s: SongInfo) = listOfNotNull(s.artist, s.album, s.release?.take(4)).joinToString(" · ")

    /** Where to listen: free and paid services, with which were confirmed. */
    fun listen(s: SongInfo, spots: List<Spot>, free: Boolean, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        head(rows, s.title, songSub(s))
        val freeOnes = spots.filter { it.access == Access.FREE || it.access == Access.FREE_OR_PAID }
        val paid = spots.filter { it.access == Access.SUBSCRIPTION }
        rows += ""
        rows += "FREE (with ads)"
        for (p in freeOnes) rows += Box.wrap("${p.name}  ${p.note.orEmpty()}", if (p.checked) "  * " else "  ? ", indent = 4, max = 2)
        if (!free || paid.isNotEmpty()) {
            rows += ""
            rows += "PAID"
            for (p in paid) rows += Box.wrap("${p.name}  ${p.note.orEmpty()}", if (p.checked) "  * " else "  ? ", indent = 4, max = 2)
        }
        rows += ""
        rows += Box.kv("KEY", "* found  ? search there")
        s.playingIn?.let { rows += Box.kv("PLAYING", "in $it") }
        rows += Box.kv("SOURCE", listOfNotNull("Deezer", if (s.appleUrl != null) "Apple" else null).joinToString(", "))
        val pick = spots.filter { it.name in setOf("Spotify", "YouTube Music", "Apple Music") }
        return Readout(Box.build("WHERE TO LISTEN", rows), image = s.cover, controls = pick.map { p -> Control.Button(p.name) { open(p.url) } })
    }

    /** Where to buy a song: download stores with pros and cons. */
    fun buySong(s: SongInfo, stores: List<Store>, open: (String) -> Unit): Readout {
        val rows = ArrayList<String>()
        head(rows, s.title, songSub(s))
        rows += ""
        rows += "DOWNLOAD TO OWN"
        for (st in stores) storeRows(rows, st)
        rows += ""
        rows += Box.wrap("On CD or vinyl: Amazon, Target, Walmart, Barnes & Noble, or a local record store.", max = 3)
        return Readout(Box.build("WHERE TO BUY", rows), image = s.cover,
            controls = stores.take(3).map { st -> Control.Button(st.name.substringBefore(" Store").substringBefore(" Digital")) { open(st.url) } })
    }

    private fun roleLabel(r: String): String = when (r) {
        "Vocals" -> "VOCALS"; "Backing vocals" -> "BACKING"; "Producer" -> "PRODUCE"; "Executive producer" -> "EXEC"
        "Writer" -> "WRITERS"; "Composer" -> "MUSIC"; "Lyricist" -> "LYRICS"; "Arranger" -> "ARRANGE"; "Programming" -> "PROGRAM"
        "Mixing" -> "MIX"; "Mastering" -> "MASTER"; "Engineer" -> "ENGINE"; "Performer" -> "PLAYERS"
        else -> r.uppercase(us).replace(Patterns.get("[^A-Z]"), "").take(7).ifEmpty { "PLAYS" }
    }

    /** Song credits: who sings, produced, wrote and played, plus album, label, genre and length. */
    fun credits(s: SongInfo, c: SongCredits, role: CreditRole): Readout {
        val rows = ArrayList<String>()
        head(rows, s.title, songSub(s))
        rows += ""
        if (c.roles["Vocals"] == null) rows += Box.wrap((listOf(s.artist) + s.featured).joinToString(", "), Box.kv("ARTIST", ""), indent = 7, max = 2)
        for ((r, names) in c.roles) rows += Box.wrap(names.take(6).joinToString(", "), Box.kv(roleLabel(r), ""), indent = 7, max = 3)
        if (c.roles.isEmpty() && role !in setOf(CreditRole.ALBUM, CreditRole.LABEL, CreditRole.RELEASE, CreditRole.GENRE))
            rows += Box.wrap("No detailed credits listed for this song yet.", max = 2)
        rows += ""
        s.album?.let { rows += Box.wrap(it, Box.kv("ALBUM", ""), indent = 7, max = 2) }
        s.release?.let { rows += Box.kv("OUT", it) }
        s.label?.let { rows += Box.wrap(it, Box.kv("LABEL", ""), indent = 7, max = 2) }
        if (s.genres.isNotEmpty()) rows += Box.kv("GENRE", s.genres.take(2).joinToString(", "))
        s.seconds?.let { rows += Box.kv("LENGTH", "%d:%02d".format(us, it / 60, it % 60) + if (s.explicit) "  explicit" else "") }
        s.playingIn?.let { rows += Box.kv("PLAYING", "in $it") }
        rows += Box.kv("SOURCE", listOfNotNull(c.source, "Deezer").joinToString(", "))
        return Readout(Box.build("SONG CREDITS", rows), image = s.cover)
    }
}
