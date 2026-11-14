package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** An actor and the character they play (when known). */
data class Credit(val actor: String, val character: String?)

/**
 * What a source other than Wikipedia and Wikidata knows about a title: length, pages, cast with their characters,
 * characters, seasons... Only what the source has is filled in.
 */
data class MediaExtra(
    val source: String,
    val title: String,
    val description: String? = null,
    val summary: String? = null,
    val image: String? = null,
    val url: String? = null,
    val year: String? = null,
    /** Length in seconds (a movie, an episode, a song or an album). */
    val seconds: Int? = null,
    val pages: Int? = null,
    val seasons: Int? = null,
    val episodes: Int? = null,
    val tracks: Int? = null,
    val cast: List<Credit> = emptyList(),
    val characters: List<String> = emptyList(),
    val by: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val network: String? = null,
)

/**
 * Sources Brick falls back on when Wikipedia or Wikidata don't have the answer (or have no page at all):
 *  - TVmaze (TV: main cast with their characters, episode length, seasons and episodes) — free, no key
 *  - TMDB (movies: billed cast with characters, runtime) — with the TMDB key from settings
 *  - Apple's store search (movie length, song length) — free
 *  - Deezer (albums: length and track count; songs: length) — free
 *  - Open Library (books: typical page count, characters) and Google Books (page count) — free
 *  - Steam (games: description, developers, genres, release date) — free
 *  - Wikipedia's "List of accolades received by..." pages (awards that Wikidata doesn't list)
 */
object LookupExtras {

    fun norm(s: String) = s.lowercase(Locale.US).replace("&", "and").replace(Regex("\\s*\\([^)]*\\)"), "")
        .replace(Regex("^(?:the|a|an)\\s+"), "").replace(Regex("[^a-z0-9]+"), "")

    /** Whether a source's title is the one asked for ("The Office (US)" for "the office"; not "Dune: Prophecy" for "dune"). */
    fun same(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && x == y
    }

    private fun arr(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }.orEmpty()

    private fun strip(html: String?) = html?.replace(Regex("<[^>]+>"), "")?.replace("&amp;", "&")?.replace("&quot;", "\"")
        ?.replace("&#39;", "'")?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }

    // ------------------------------------------------------------------ TV: TVmaze

    suspend fun tv(title: String): MediaExtra? = withContext(Dispatchers.IO) {
        val j = Http.getJsonCached("https://api.tvmaze.com/singlesearch/shows?q=${Http.enc(title)}&embed%5B%5D=cast&embed%5B%5D=seasons") ?: return@withContext null
        val name = j.optString("name").ifBlank { return@withContext null }
        if (!same(name, title)) return@withContext null
        val emb = j.optJSONObject("_embedded")
        val seasons = arr(emb?.optJSONArray("seasons")).filter { it.optString("premiereDate").isNotBlank() }
        val eps = seasons.sumOf { it.optInt("episodeOrder", 0) }.takeIf { it > 0 }
        val cast = arr(emb?.optJSONArray("cast")).mapNotNull { c ->
            val actor = c.optJSONObject("person")?.optString("name")?.ifBlank { null } ?: return@mapNotNull null
            Credit(actor, c.optJSONObject("character")?.optString("name")?.ifBlank { null })
        }
        val runtime = (j.optInt("averageRuntime", 0).takeIf { it > 0 } ?: j.optInt("runtime", 0).takeIf { it > 0 })
        MediaExtra(
            "TVmaze", name, description = "TV series", summary = strip(j.optString("summary")),
            image = j.optJSONObject("image")?.optString("medium")?.ifBlank { null }?.replace("http://", "https://"),
            url = j.optString("url").ifBlank { null }, year = j.optString("premiered").take(4).ifBlank { null },
            seconds = runtime?.times(60), seasons = seasons.size.takeIf { it > 0 }, episodes = eps, cast = cast,
            characters = cast.mapNotNull { it.character }, genres = (0 until (j.optJSONArray("genres")?.length() ?: 0)).map { j.optJSONArray("genres")!!.optString(it) },
            network = (j.optJSONObject("webChannel") ?: j.optJSONObject("network"))?.optString("name")?.ifBlank { null },
        )
    }

    // ------------------------------------------------------------------ movies: TMDB (with a key), then Apple

    suspend fun movieTmdb(title: String, year: String?, key: String): MediaExtra? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext null
        val bearer = key.length > 40
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        fun url(path: String) = "https://api.themoviedb.org/3$path" + (if (bearer) "" else (if ('?' in path) "&" else "?") + "api_key=${Http.enc(key)}")
        val found = arr(Http.getJsonCached(url("/search/movie?query=${Http.enc(title)}" + (year?.let { "&year=$it" } ?: "")), headers)?.optJSONArray("results"))
        val hit = found.firstOrNull { same(it.optString("title"), title) } ?: return@withContext null
        val m = Http.getJsonCached(url("/movie/${hit.optInt("id")}?append_to_response=credits"), headers) ?: return@withContext null
        val cast = arr(m.optJSONObject("credits")?.optJSONArray("cast")).sortedBy { it.optInt("order", 99) }.take(15).mapNotNull { c ->
            val actor = c.optString("name").ifBlank { null } ?: return@mapNotNull null
            Credit(actor, c.optString("character").ifBlank { null })
        }
        MediaExtra(
            "TMDB", m.optString("title", title), description = "Film", summary = m.optString("overview").ifBlank { null },
            image = m.optString("poster_path").ifBlank { null }?.let { "https://image.tmdb.org/t/p/w342$it" },
            url = "https://www.themoviedb.org/movie/${hit.optInt("id")}", year = m.optString("release_date").take(4).ifBlank { null },
            seconds = m.optInt("runtime", 0).takeIf { it > 0 }?.times(60), cast = cast, characters = cast.mapNotNull { it.character },
            genres = arr(m.optJSONArray("genres")).map { it.optString("name") },
        )
    }

    suspend fun movieApple(title: String): MediaExtra? = withContext(Dispatchers.IO) {
        val r = arr(Http.getJsonCached("https://itunes.apple.com/search?term=${Http.enc(title)}&media=movie&entity=movie&country=US&limit=10")?.optJSONArray("results"))
        val m = r.firstOrNull { same(it.optString("trackName"), title) } ?: return@withContext null
        MediaExtra(
            "Apple", m.optString("trackName"), description = "Film", summary = m.optString("longDescription").ifBlank { null },
            image = m.optString("artworkUrl100").ifBlank { null }?.replace("100x100", "300x300"),
            url = m.optString("trackViewUrl").substringBefore("?").ifBlank { null }, year = m.optString("releaseDate").take(4).ifBlank { null },
            seconds = m.optLong("trackTimeMillis", 0).takeIf { it > 0 }?.let { (it / 1000).toInt() },
            by = listOfNotNull(m.optString("artistName").ifBlank { null }), genres = listOfNotNull(m.optString("primaryGenreName").ifBlank { null }),
        )
    }

    // ------------------------------------------------------------------ music: Deezer, then Apple

    suspend fun album(title: String, artist: String?): MediaExtra? = withContext(Dispatchers.IO) {
        val q = listOfNotNull(title, artist).joinToString(" ")
        val list = arr(Http.getJsonCached("https://api.deezer.com/search/album?q=${Http.enc(q)}&limit=10")?.optJSONArray("data"))
        val hit = list.firstOrNull { same(it.optString("title"), title) && (artist == null || same(it.optJSONObject("artist")?.optString("name").orEmpty(), artist)) }
            ?: list.firstOrNull { same(it.optString("title"), title) } ?: return@withContext null
        val a = Http.getJsonCached("https://api.deezer.com/album/${hit.optLong("id")}") ?: return@withContext null
        MediaExtra(
            "Deezer", a.optString("title", title), description = "Album",
            image = a.optString("cover_medium").ifBlank { null }, url = a.optString("link").ifBlank { null },
            year = a.optString("release_date").take(4).ifBlank { null }, seconds = a.optInt("duration", 0).takeIf { it > 0 },
            tracks = a.optInt("nb_tracks", 0).takeIf { it > 0 }, by = listOfNotNull(a.optJSONObject("artist")?.optString("name")?.ifBlank { null }),
            genres = arr(a.optJSONObject("genres")?.optJSONArray("data")).map { it.optString("name") },
        )
    }

    suspend fun song(title: String, artist: String?): MediaExtra? = withContext(Dispatchers.IO) {
        val q = listOfNotNull(title, artist).joinToString(" ")
        val list = arr(Http.getJsonCached("https://api.deezer.com/search?q=${Http.enc(q)}&limit=10")?.optJSONArray("data"))
        val hit = list.firstOrNull { same(it.optString("title"), title) && (artist == null || same(it.optJSONObject("artist")?.optString("name").orEmpty(), artist)) }
            ?: list.firstOrNull { same(it.optString("title"), title) }
        if (hit != null) return@withContext MediaExtra(
            "Deezer", hit.optString("title", title), description = "Song",
            image = hit.optJSONObject("album")?.optString("cover_medium")?.ifBlank { null }, url = hit.optString("link").ifBlank { null },
            seconds = hit.optInt("duration", 0).takeIf { it > 0 }, by = listOfNotNull(hit.optJSONObject("artist")?.optString("name")?.ifBlank { null }),
        )
        val r = arr(Http.getJsonCached("https://itunes.apple.com/search?term=${Http.enc(q)}&media=music&entity=song&country=US&limit=10")?.optJSONArray("results"))
        val s = r.firstOrNull { same(it.optString("trackName"), title) } ?: return@withContext null
        MediaExtra(
            "Apple", s.optString("trackName"), description = "Song", image = s.optString("artworkUrl100").ifBlank { null },
            url = s.optString("trackViewUrl").substringBefore("?").ifBlank { null }, year = s.optString("releaseDate").take(4).ifBlank { null },
            seconds = s.optLong("trackTimeMillis", 0).takeIf { it > 0 }?.let { (it / 1000).toInt() }, by = listOfNotNull(s.optString("artistName").ifBlank { null }),
        )
    }

    // ------------------------------------------------------------------ books: Open Library, then Google Books

    suspend fun book(title: String, author: String?): MediaExtra? = withContext(Dispatchers.IO) {
        val fields = "key,title,author_name,first_publish_year,number_of_pages_median,person,cover_i,subject"
        val docs = arr(Http.getJsonCached("https://openlibrary.org/search.json?title=${Http.enc(title)}" +
            (author?.let { "&author=${Http.enc(it)}" } ?: "") + "&fields=$fields&limit=5")?.optJSONArray("docs"))
        val d = docs.firstOrNull { same(it.optString("title"), title) }
        fun list(o: JSONObject?, f: String) = o?.optJSONArray(f)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        var pages = d?.optInt("number_of_pages_median", 0)?.takeIf { it > 0 }
        if (pages == null) {
            val items = arr(Http.getJsonCached("https://www.googleapis.com/books/v1/volumes?q=${Http.enc("intitle:$title" + (author?.let { " inauthor:$it" } ?: ""))}&maxResults=5&printType=books")?.optJSONArray("items"))
            pages = items.mapNotNull { it.optJSONObject("volumeInfo") }.filter { same(it.optString("title"), title) }
                .mapNotNull { it.optInt("pageCount", 0).takeIf { p -> p > 20 } }.sorted().let { it.getOrNull(it.size / 2) }
        }
        if (d == null && pages == null) return@withContext null
        MediaExtra(
            "Open Library", d?.optString("title") ?: title, description = "Book",
            image = d?.optInt("cover_i", 0)?.takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
            url = d?.optString("key")?.ifBlank { null }?.let { "https://openlibrary.org$it" },
            year = d?.optInt("first_publish_year", 0)?.takeIf { it > 0 }?.toString(), pages = pages,
            characters = list(d, "person").filter { it.length < 40 }.take(15), by = list(d, "author_name").take(2),
        )
    }

    // ------------------------------------------------------------------ games: Steam

    suspend fun game(title: String): MediaExtra? = withContext(Dispatchers.IO) {
        val items = arr(Http.getJsonCached("https://store.steampowered.com/api/storesearch/?term=${Http.enc(title)}&cc=us&l=en")?.optJSONArray("items"))
        val hit = items.firstOrNull { same(it.optString("name"), title) } ?: return@withContext null
        val id = hit.optLong("id")
        val d = Http.getJsonCached("https://store.steampowered.com/api/appdetails?appids=$id&cc=us&l=en")?.optJSONObject("$id")
            ?.takeIf { it.optBoolean("success") }?.optJSONObject("data") ?: return@withContext null
        fun names(f: String) = d.optJSONArray(f)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        MediaExtra(
            "Steam", d.optString("name", title), description = "Video game", summary = strip(d.optString("short_description")),
            image = d.optString("header_image").ifBlank { null }, url = "https://store.steampowered.com/app/$id",
            year = Regex("\\d{4}").find(d.optJSONObject("release_date")?.optString("date").orEmpty())?.value,
            by = names("developers").take(2), genres = arr(d.optJSONArray("genres")).map { it.optString("description") }.take(3),
        )
    }

    // ------------------------------------------------------------------ awards: Wikipedia's accolades lists

    /** Wikipedia's "List of accolades received by Oppenheimer" (or "awards and nominations") page: its title, summary and address. */
    suspend fun accolades(title: String): Triple<String, String, String>? = withContext(Dispatchers.IO) {
        val name = title.substringBefore(" (").trim()
        val r = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=6&srsearch=${Http.enc("intitle:\"$name\" accolades OR awards nominations list")}")
        val hits = arr(r?.optJSONObject("query")?.optJSONArray("search")).map { it.optString("title") }
        val page = hits.firstOrNull { t -> Regex("^List of (?:accolades|awards and nominations|awards) (?:received by|for) ", RegexOption.IGNORE_CASE).containsMatchIn(t) && t.contains(name, true) }
            ?: return@withContext null
        // The whole intro: the counts ("won seven: Best Picture, ...") come after the first paragraph.
        val pages = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=${Http.enc(page)}")
            ?.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
        val extract = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it)?.optString("extract") }.firstOrNull { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")?.trim() ?: return@withContext null
        Triple(page, extract, "https://en.m.wikipedia.org/wiki/${Http.enc(page.replace(' ', '_')).replace("+", "_")}")
    }

    /** Wikipedia's "List of The Legend of Zelda characters" page for [title]: its title and address. */
    suspend fun characterList(title: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val name = title.substringBefore(" (").trim()
        val r = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=6&srsearch=${Http.enc("intitle:\"$name\" intitle:characters list")}")
        val page = arr(r?.optJSONObject("query")?.optJSONArray("search")).map { it.optString("title") }
            .firstOrNull { t -> Regex("^(?:List of .*characters|Characters of )", RegexOption.IGNORE_CASE).containsMatchIn(t) && norm(t).contains(norm(name)) }
            ?: return@withContext null
        page to "https://en.m.wikipedia.org/wiki/${page.replace(' ', '_')}"
    }

    /** The best other source for a [kind] of title ([by] is the artist or author, when known; [album] for an album over a song). */
    suspend fun forKind(kind: LookupKind?, title: String, by: String?, year: String?, album: Boolean, tmdbKey: String): MediaExtra? = when (kind) {
        LookupKind.TV_SHOW -> tv(title)
        LookupKind.MOVIE -> movieTmdb(title, year, tmdbKey) ?: movieApple(title)
        LookupKind.MUSIC -> if (album) album(title, by) ?: song(title, by) else song(title, by) ?: album(title, by)
        LookupKind.BOOK -> book(title, by)
        LookupKind.GAME -> game(title)
        else -> null
    }
}
