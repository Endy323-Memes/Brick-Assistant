package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** What you asked about a movie, show or song. */
enum class MediaAskKind { RATING, WATCH, BUY_VIDEO, LISTEN, BUY_SONG, CREDITS }

/** Which song credit you asked for ("who produced this" is [PRODUCER]); [ALL] shows them all. */
enum class CreditRole { ALL, VOCALS, PRODUCER, WRITER, COMPOSER, LYRICIST, ENGINEER, MIX, INSTRUMENT, ALBUM, LABEL, RELEASE, GENRE }

/** Disc or download, when you said ("on blu-ray", "digitally"). */
enum class BuyFormat { ANY, PHYSICAL, DIGITAL }

/** How a service lets you watch or listen. */
enum class Access(val label: String) {
    FREE("free with ads"),
    FREE_OR_PAID("free with ads, or paid"),
    SUBSCRIPTION("subscription"),
    SUB_OR_BUY("subscription, or rent/buy"),
    RENT_BUY("rent or buy"),
}

/** A place to watch or listen: [checked] when a source confirmed it's there (not just a place to search). */
data class Spot(val name: String, val access: Access, val url: String, val checked: Boolean, val note: String? = null)

/** A store to buy from, with why you would or wouldn't. [price] when Brick found one. */
data class Store(val name: String, val url: String, val pros: List<String>, val cons: List<String>, val price: String? = null, val checked: Boolean = false)

/** A movie or show Brick found, with what it could learn about scores and where it is. */
data class VideoInfo(
    val title: String,
    val kind: LookupKind?,
    val year: String?,
    val description: String?,
    val image: String?,
    val pageUrl: String,
    val scores: List<Lookup.ReviewScore>,
    /** "PG-13", "TV-MA". */
    val certificate: String?,
    /** TVmaze's user rating out of 10 (shows). */
    val tvmaze: Double?,
    /** TMDB's user rating out of 10, with a TMDB key. */
    val tmdb: Double?,
    /** The show's home network or service ("AMC", "Netflix"), from TVmaze. */
    val network: String?,
    val spots: List<Spot>,
    /** Apple's prices, when its store search found the title. */
    val apple: AppleVideo?,
    /** Where the listings came from ("TMDB / JustWatch" or "Wikidata"). */
    val listingSource: String,
)

data class AppleVideo(val buy: Double?, val rent: Double?, val url: String, val certificate: String?, val season: String?)

/** A song Brick identified (from what's playing or what you named). */
data class SongInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val cover: String?,
    val deezerUrl: String?,
    val isrc: String?,
    val seconds: Int?,
    val explicit: Boolean,
    val release: String?,
    val label: String?,
    val genres: List<String>,
    val featured: List<String>,
    /** iTunes price and link, when Apple's search found it. */
    val applePrice: Double?,
    val appleUrl: String?,
    /** App playing it right now, for a current song. */
    val playingIn: String? = null,
)

/** Song credits by role ("Producer" -> names), in the order they read best. */
data class SongCredits(val roles: LinkedHashMap<String, List<String>>, val source: String?)

object MediaGuide {
    // ------------------------------------------------------------------ understanding the request

    /** Words that mean the song playing now. */
    private const val CUR =
        "(?:this|that|it|this one|that one|this song|that song|this track|that track|this tune|that tune|the song|the track|" +
            "the current song|the current track|the song (?:that's |that is |that was )?(?:playing|on)(?: now| right now)?|" +
            "what's playing|what is playing|whats playing|what i'm (?:listening to|playing)|what im listening to|what i am listening to|" +
            "the song i'm (?:listening to|playing)|the music|this music|this beat|this record)"
    /** The same, said in a way that can only mean a song (for "where can I buy this song"). */
    private const val SONG_CUR =
        "(?:this song|that song|this track|that track|this tune|that tune|the current song|the current track|the song (?:that's |that is )?(?:playing|on)(?: now| right now)?|" +
            "what's playing|what is playing|whats playing|what i'm (?:listening to|playing)|what im listening to|what i am listening to|the song i'm (?:listening to|playing)|this music|this beat|this record)"
    private val CUR_RX = Patterns.get("^$CUR$")
    private val SONG_CUR_RX = Patterns.get("^$SONG_CUR$")
    private val SONG_WORD = Patterns.get("\\b(?:song|songs|track|tracks|single|singles|album|albums|tune|tunes|mp3|mp3s|flac|lossless|ep|mixtape|beat|remix|vinyl|cd|cds|record|records|cassette|lp)\\b")
    private val VIDEO_WORD = Patterns.get("\\b(?:movie|movies|film|films|flick|show|shows|tv|series|season|seasons|episode|episodes|sitcom|anime|documentary|docuseries|miniseries|dvd|dvds|blu ray|blu rays|bluray|blurays|4k|uhd|box set|boxset|steelbook|digital copy|criterion)\\b")
    private val PHYSICAL = Patterns.get("\\b(?:dvd|dvds|blu ray|blu rays|bluray|blurays|4k|uhd|disc|discs|disk|physical|physically|hard copy|box set|boxset|steelbook|in store|in stores|at a store|vinyl|cd|cds|cassette|lp)\\b")
    private val DIGITAL = Patterns.get("\\b(?:digital|digitally|download|downloads|downloaded|online|to own|on itunes|mp3|flac)\\b")
    private val SKY = Patterns.get("\\b(?:meteor|meteors|stars?|eclipse|sunset|sunrise|aurora|northern lights|milky way|comet|planets?|the moon|rocket launch|fireworks|birds|whales|satellites?|iss|light pollution|bortle|night sky|the sky)\\b")
    private val FREE = Patterns.get("\\b(?:free|for nothing|without paying|without a subscription|no subscription)\\b")
    private const val MUSIC_APPS = "spotify|apple music|youtube music|yt music|amazon music|tidal|deezer|soundcloud|sound cloud|pandora|audiomack|itunes|bandcamp|qobuz"
    private const val VIDEO_APPS = "netflix|hulu|disney plus|disney\\+|disney|max|hbo max|hbo|prime video|amazon prime video|amazon prime|prime|amazon|peacock|" +
        "paramount plus|paramount\\+|paramount|apple tv plus|apple tv\\+|apple tv|tubi|pluto tv|pluto|crunchyroll|the roku channel|roku|plex|starz|amc plus|amc\\+|youtube|fandango at home|vudu|google tv"

    private fun rx(p: String) = Patterns.get(p)

    private val ROLE_WORDS: List<Pair<Regex, CreditRole>> = listOf(
        rx("^who(?:'s| is| was)? (?:singing|sings|sang|performing|performs|performed|rapping|raps|rapped|on vocals|doing the vocals|on the vocals)(?: (?:on |in )?$CUR)?$") to CreditRole.VOCALS,
        rx("^who(?:'s| is| was| are| were) (?:the )?(?:singer|singers|vocalist|vocalists|lead singer|rapper|rappers|artist|artists|band|performer|performers|group)(?: (?:of|on|for|in|behind) $CUR)?$") to CreditRole.VOCALS,
        rx("^who (?:produced|made the beat(?: for| of| on)?|did the beat(?: for| on)?|did the production(?: on| for)?|made) $CUR$") to CreditRole.PRODUCER,
        rx("^who (?:produced|made the beat|did the beat|made the instrumental)$") to CreditRole.PRODUCER,
        rx("^who(?:'s| is| are| were| was) (?:the )?(?:producer|producers|beatmaker|beat maker)(?: (?:of|on|for|behind) $CUR)?$") to CreditRole.PRODUCER,
        rx("^who (?:wrote|penned|co wrote|cowrote) $CUR$") to CreditRole.WRITER,
        rx("^who(?:'s| is| are| were| was) (?:the )?(?:writer|writers|songwriter|songwriters)(?: (?:of|on|for|behind) $CUR)?$") to CreditRole.WRITER,
        rx("^who (?:composed) $CUR$") to CreditRole.COMPOSER,
        rx("^who(?:'s| is| are| were| was) (?:the )?(?:composer|composers)(?: (?:of|on|for|behind) $CUR)?$") to CreditRole.COMPOSER,
        rx("^who(?:'s| is| are| were| was) (?:the )?(?:lyricist|lyricists)(?: (?:of|on|for|behind) $CUR)?$|^who wrote the (?:lyrics|words)(?: (?:to|for|of|on) $CUR)?$") to CreditRole.LYRICIST,
        rx("^who (?:mixed|mastered|engineered|recorded) $CUR$|^who(?:'s| is| was) (?:the )?(?:engineer|mixing engineer|mastering engineer|sound engineer)(?: (?:of|on|for) $CUR)?$") to CreditRole.ENGINEER,
        rx("^(?:what|which) album (?:is|was) $CUR(?: (?:from|on|off|in))?$|^(?:what|which) album (?:is|was) (?:this|it|that) (?:from|on|off)$|^what album$|^what album (?:is|was) (?:this|it) from$") to CreditRole.ALBUM,
        rx("^(?:what|which) (?:record )?label (?:is|was) $CUR (?:on|from|under|signed to|released on)$|^who (?:released|put out) $CUR$|^what label$") to CreditRole.LABEL,
        rx("^when (?:did|was) $CUR (?:come out|released|drop|dropped|made|recorded)$|^what year (?:is|was|did) $CUR(?: (?:from|come out|released|made))?$|^how old is $SONG_CUR$|^when did (?:this|that) (?:song|track) (?:come out|drop)$") to CreditRole.RELEASE,
        rx("^what (?:genre|kind of music|type of music|style of music|kind of song|type of song|style) is $CUR$|^what genre$") to CreditRole.GENRE,
        rx("^(?:show me |give me |get |pull up |what are |what're |list |read |read me )?(?:the )?(?:full )?(?:song |track )?credits(?: (?:for|of|on|to) $CUR)?$") to CreditRole.ALL,
        rx("^(?:tell me |give me |show me |get |get me |i want |pull up )?(?:more |some |the )?(?:info|information|details|facts|everything|stuff|more|trivia)(?: (?:about|on|of|for|regarding))? $SONG_CUR$") to CreditRole.ALL,
        rx("^(?:tell me |what do you know |what can you tell me )?about $SONG_CUR$") to CreditRole.ALL,
        rx("^who(?:'s| is| are) (?:on|behind|featured on|featuring on|featured in|in) $CUR$") to CreditRole.ALL,
    )

    /** "Who plays guitar on this?" -> the instrument. */
    private val INSTRUMENT = rx("^who(?:'s| is)? (?:plays|played|playing|is playing|on) (?:the )?([a-z ]{3,20}?) (?:on|in) $CUR$")

    /** Credits for a song you name: "who produced the song blinding lights", "who sings levitating". */
    private val NAMED_CREDITS: List<Pair<Regex, CreditRole>> = listOf(
        rx("^who (?:sings|sang|performs|performed|raps|rapped) (?:the song |the track |the single )?(.+)$") to CreditRole.VOCALS,
        rx("^who (?:produced|made the beat for) (?:the song |the track |the single )(.+)$") to CreditRole.PRODUCER,
        rx("^who (?:produced|made the beat for) (.+ by .+)$") to CreditRole.PRODUCER,
        rx("^who (?:wrote|composed) (?:the song |the track |the single )(.+)$") to CreditRole.WRITER,
        rx("^who (?:wrote|composed) (?:the )?(?:lyrics (?:to|for|of) )?(.+ by .+)$") to CreditRole.WRITER,
        rx("^(?:show me |give me |get |what are )?(?:the )?(?:song )?credits (?:for|of|on|to) (?:the song |the track )?(.+)$") to CreditRole.ALL,
    )

    /** Reads a request about ratings, where to watch / buy / listen, or song credits. Null when it is none of these. */
    fun parse(input: String): Request.MediaAsk? {
        val s = input.lowercase(Locale.US).replace(Patterns.get("^(?:hey )?brick[, ]+"), "").trim()
        if (s.isEmpty()) return null
        // No title at all ("where can i watch"): ask which one instead of searching the words.
        Patterns.get("^(?:where|how) (?:can|could|do|should) (?:i|we) (watch|stream|see|rent|buy|get)(?: it| that| this| something| stuff)?$").find(s)?.let {
            return Request.MediaAsk(if (it.groupValues[1] in setOf("rent", "buy", "get")) MediaAskKind.BUY_VIDEO else MediaAskKind.WATCH, null)
        }
        credits(s)?.let { return it }
        rating(s)?.let { return it }
        listenOrWatch(s)?.let { return it }
        buy(s)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ loose wording (keywords)

    private val RATING_KEYS = Patterns.get("\\b(?:ratings?|rated|reviews?|review scores?|rotten tomatoes|tomatometer|imdb|metacritic|metascore|critic scores?|critics|audience score|scores?|stars out of)\\b")
    private val WATCH_KEYS = Patterns.get("\\b(?:streaming|stream|streamable|watch|watchable|where to watch|which app|what app|what service|which service|what platform|which platform|on demand)\\b")
    private val BUY_KEYS = Patterns.get("\\b(?:buy|buying|purchase|purchasing|rent|renting|rental|own it|for sale|price|cost|costs|how much)\\b")
    private val LISTEN_KEYS = Patterns.get("\\b(?:listen|listening|hear|stream|streaming|play it|which app|what app|what platform|which platform|what service)\\b")
    private val CREDIT_KEYS: List<Pair<Regex, CreditRole>> = listOf(
        Patterns.get("\\b(?:producers?|produced|production|beat ?makers?|made the beat|beat by)\\b") to CreditRole.PRODUCER,
        Patterns.get("\\b(?:songwriters?|writers?|wrote|written)\\b") to CreditRole.WRITER,
        Patterns.get("\\b(?:composers?|composed)\\b") to CreditRole.COMPOSER,
        Patterns.get("\\b(?:lyricists?|lyrics by)\\b") to CreditRole.LYRICIST,
        Patterns.get("\\b(?:singers?|vocalists?|vocals|singing|sings|sang|rapper|rapping|artist)\\b") to CreditRole.VOCALS,
        Patterns.get("\\b(?:engineers?|engineered|mixed|mixing|mastered|mastering)\\b") to CreditRole.ENGINEER,
        Patterns.get("\\b(?:which|what) album\\b|\\balbum\\b") to CreditRole.ALBUM,
        Patterns.get("\\b(?:record label|label)\\b") to CreditRole.LABEL,
        Patterns.get("\\b(?:release date|came out|come out|released|what year)\\b") to CreditRole.RELEASE,
        Patterns.get("\\b(?:genre|kind of music|type of music)\\b") to CreditRole.GENRE,
        Patterns.get("\\b(?:credits|credit|details|info|information|who made|who's on|featuring|features)\\b") to CreditRole.ALL,
    )
    /** Words that mean the playing song, anywhere in a loose request. */
    private val PLAYING = Patterns.get("\\b(?:this song|that song|this track|that track|this tune|current song|current track|song playing|song that's playing|what's playing|whats playing|what i'm listening to|this beat|now playing|the song on)\\b")
    private val FILLER = Regex(
        "^(?:(?:hey|yo|ok|okay|so|um|uh|like|please|pls|plz|can|could|would|will|you|u|i|we|me|my|do|does|did|is|are|was|were|what|what's|whats|where|where's|wheres|which|how|who|who's|tell|show|give|find|get|check|look|up|lookup|search|the|of|for|on|about|to|it|any|a|an|and|or|in|at|from|with|by|be|that|this|there|good|well|its|it's)\\s+)+" +
            "|(?:\\s+(?:please|pls|plz|now|right now|real quick|quick|for me|is|are|on|for|of|at|in|it|be|again|then|ok|okay|thanks|yeah|good|any good|lol|bro|man|dude))+$",
    )

    private fun strip(s: String, vararg keys: Regex): String {
        var t = s
        for (k in keys) t = t.replace(k, " ")
        t = t.replace(Patterns.get("\\b(?:$VIDEO_APPS|$MUSIC_APPS)\\b"), " ").replace(Patterns.get("\\s+"), " ").trim()
        repeat(4) { t = t.replace(FILLER, "").trim() }
        return t
    }

    /**
     * Keyword reading for loose wording the patterns miss ("dune rotten tomatoes", "netflix severance", "blinding lights
     * spotify", "producer of this song", "dune blu ray price"). Run late, after every stricter parser has passed.
     */
    fun loose(input: String): Request.MediaAsk? {
        val s = input.lowercase(Locale.US).replace(Patterns.get("^(?:hey )?brick[, ]+"), "").replace("?", "").trim()
        if (s.split(' ').size > 14 || SKY.containsMatchIn(s)) return null
        val musicApp = Patterns.get("\\b(?:$MUSIC_APPS)\\b").find(s)?.value
        val videoApp = Patterns.get("\\b(?:$VIDEO_APPS)\\b").find(s)?.value?.takeIf { musicApp == null }
        val songish = musicApp != null || SONG_WORD.containsMatchIn(s) || PLAYING.containsMatchIn(s)
        val videoish = videoApp != null || VIDEO_WORD.containsMatchIn(s)
        // Credits for the playing song: needs a credit word and a "this song" word.
        if (PLAYING.containsMatchIn(s) || Patterns.get("\\b(?:this|it)\\b").containsMatchIn(s) && Patterns.get("\\b(?:producers?|produced|songwriters?|vocals|vocalist|credits|beat)\\b").containsMatchIn(s)) {
            for ((k, role) in CREDIT_KEYS) if (k.containsMatchIn(s) && !BUY_KEYS.containsMatchIn(s) && !LISTEN_KEYS.containsMatchIn(s)) return Request.MediaAsk(MediaAskKind.CREDITS, null, role = role)
        }
        val free = FREE.containsMatchIn(s)
        if (BUY_KEYS.containsMatchIn(s) || PHYSICAL.containsMatchIn(s) && videoish) {
            val format = when {
                PHYSICAL.containsMatchIn(s) && !Patterns.get("\\b(?:vinyl|cd|cds|cassette|lp)\\b").containsMatchIn(s) -> BuyFormat.PHYSICAL
                DIGITAL.containsMatchIn(s) || Patterns.get("\\brent").containsMatchIn(s) -> BuyFormat.DIGITAL
                else -> BuyFormat.ANY
            }
            if (songish && !videoish) {
                if (PLAYING.containsMatchIn(s)) return Request.MediaAsk(MediaAskKind.BUY_SONG, null, format = format)
                val (t, a) = splitBy(strip(s, BUY_KEYS, SONG_WORD, DIGITAL))
                return cleanTitle(t)?.let { Request.MediaAsk(MediaAskKind.BUY_SONG, it, artist = a, format = format) }
            }
            if (videoish) {
                val t = cleanTitle(strip(s, BUY_KEYS, PHYSICAL, DIGITAL, VIDEO_WORD)) ?: return null
                return Request.MediaAsk(MediaAskKind.BUY_VIDEO, t, video = videoKind(s), format = format, rent = Patterns.get("\\brent").containsMatchIn(s))
            }
            return null
        }
        if (RATING_KEYS.containsMatchIn(s) && !songish) {
            val t = cleanTitle(strip(s, RATING_KEYS, VIDEO_WORD)) ?: return null
            if (t.split(' ').size > 6) return null
            return Request.MediaAsk(MediaAskKind.RATING, t, video = videoKind(s))
        }
        if (songish && (LISTEN_KEYS.containsMatchIn(s) || musicApp != null)) {
            if (PLAYING.containsMatchIn(s)) return Request.MediaAsk(MediaAskKind.LISTEN, null, free = free, service = musicApp)
            val (t, a) = splitBy(strip(s, LISTEN_KEYS, SONG_WORD, FREE))
            return cleanTitle(t)?.let { Request.MediaAsk(MediaAskKind.LISTEN, it, artist = a, free = free, service = musicApp) }
        }
        if (WATCH_KEYS.containsMatchIn(s) || videoApp != null && s.split(' ').size <= 6) {
            val t = cleanTitle(strip(s, WATCH_KEYS, VIDEO_WORD, FREE)) ?: return null
            if (t.split(' ').size > 6) return null
            return Request.MediaAsk(MediaAskKind.WATCH, t, video = videoKind(s), free = free, service = videoApp)
        }
        return null
    }

    private fun credits(s: String): Request.MediaAsk? {
        INSTRUMENT.find(s)?.let { m ->
            val inst = m.groupValues[1].trim()
            if (inst !in setOf("song", "track", "this", "it")) return Request.MediaAsk(MediaAskKind.CREDITS, null, role = CreditRole.INSTRUMENT, instrument = inst)
        }
        for ((r, role) in ROLE_WORDS) if (r.matches(s)) return Request.MediaAsk(MediaAskKind.CREDITS, null, role = role)
        for ((r, role) in NAMED_CREDITS) {
            val m = r.find(s) ?: continue
            var t = m.groupValues[1].trim()
            if (CUR_RX.matches(t)) return Request.MediaAsk(MediaAskKind.CREDITS, null, role = role)
            // "who sings at the super bowl" / "who sings happy birthday" stay as lookups unless it reads like a title.
            if (role == CreditRole.VOCALS && Patterns.get("^(?:at|in|for|on|with|during|the national anthem)\\b").containsMatchIn(t)) return null
            val (title, artist) = splitBy(t)
            t = cleanTitle(title) ?: return null
            return Request.MediaAsk(MediaAskKind.CREDITS, t, artist = artist, role = role)
        }
        return null
    }

    private fun rating(s: String): Request.MediaAsk? {
        val forms = listOf(
            "^(?:what(?:'s| is| are| were| was) )?(?:the )?(?:ratings?|reviews?|review scores?|critic scores?|critics scores?|audience scores?|critic ratings?|" +
                "rotten tomatoes(?: scores?| ratings?)?|tomatometer(?: score)?|imdb(?: ratings?| scores?)?|metacritic(?: scores?| ratings?)?|metascore|" +
                "age rating|mpa rating|mpaa rating|parental rating|content rating|tv rating|score on rotten tomatoes|rating on imdb)(?: (?:of|for|on))? (.+)$",
            "^how (?:is|was|are|were) (.+?) (?:rated|reviewed|doing with critics)$",
            "^what (?:is|was) (.+?) rated(?: on (?:rotten tomatoes|imdb|metacritic))?$",
            "^how (?:well reviewed|well rated|highly rated) (?:is|was|are|were) (.+)$",
            "^how (?:good|bad|popular) (?:is|was|are|were) (?:the |that )?(?:movie|film|show|tv show|series|anime|new season of) (.+)$",
            "^how (?:good|bad) (?:is|was|are|were) (.+)$",
            "^(?:is|was|are|were) (.+?) (?:any good|worth (?:watching|a watch|seeing|it|the watch|my time|the time|binging|bingeing)|rated well|well reviewed|highly rated|good or bad)$",
            "^what (?:do|did) (?:critics|people|reviewers|audiences|fans) (?:think|say) (?:of|about) (.+)$",
            "^(.+?)(?:'s|s)? (?:ratings?|reviews?|rotten tomatoes(?: scores?)?|imdb(?: ratings?| scores?)?|metacritic(?: scores?)?|age rating|critic scores?)$",
            "^what did (.+?) get on (?:rotten tomatoes|imdb|metacritic)$",
            "^(?:should i|is it worth it to|would you recommend i) (?:watch|see|binge) (.+)$",
        )
        for (f in forms) {
            val m = Patterns.get(f).find(s) ?: continue
            val raw = m.groupValues[1].trim()
            if (raw.isEmpty() || SKY.containsMatchIn(raw) || SONG_WORD.containsMatchIn(raw) && !VIDEO_WORD.containsMatchIn(raw)) continue
            // "is it good" / "is the weather good": "good" alone needs a movie or show word.
            if (raw in setOf("it", "this", "that", "they", "he", "she", "the weather", "the food", "this place")) continue
            val title = cleanTitle(raw) ?: continue
            return Request.MediaAsk(MediaAskKind.RATING, title, video = videoKind(raw))
        }
        // "is the movie dune good", "is the show severance good".
        Patterns.get("^(?:is|was) (?:the |that )?(?:movie|film|show|tv show|series|anime) (.+?) good$").find(s)?.let { m ->
            cleanTitle(m.groupValues[1])?.let { return Request.MediaAsk(MediaAskKind.RATING, it, video = videoKind(s)) }
        }
        return null
    }

    private fun listenOrWatch(s: String): Request.MediaAsk? {
        if (SKY.containsMatchIn(s)) return null
        val service = Patterns.get("\\b($MUSIC_APPS|$VIDEO_APPS)$").find(s)?.groupValues?.get(1)
        data class Hit(val verb: String, val subject: String)
        val hit: Hit = run {
            val forms = listOf(
                "^(?:where|how) (?:can|could|do|should|would|will|might) (?:i|we|you|one|people) (?:go )?(?:to )?(watch|stream|see|catch|view|listen to|hear|play|find|get) (.+)$",
                "^where (?:to|i can|can i|do i|should i) (watch|stream|see|listen to|hear|find) (.+)$",
                "^(?:what|which) (?:app|apps|service|services|platform|platforms|streaming service|streaming services|streaming app|streaming apps|streaming platform|streaming platforms|streamer|streamers|site|sites|website|websites) (?:can|could|do|should|would) (?:i|we|you) (watch|stream|see|listen to|hear|play|find) (.+?)(?: on| at| in| with)?$",
                "^(?:can|could) (?:i|we|you) (watch|stream|listen to|hear|play) (.+?) (?:on|with|in) (?:$MUSIC_APPS|$VIDEO_APPS)$",
                "^where (?:can|could) (?:i|we) (?:get|find) (.+?) to (watch|stream|listen to)$",
            )
            for (f in forms) {
                val m = Patterns.get(f).find(s) ?: continue
                return@run if (f.startsWith("^where (?:can|could) (?:i|we) (?:get|find)")) Hit(m.groupValues[2], m.groupValues[1]) else Hit(m.groupValues[1], m.groupValues[2])
            }
            val noVerb = listOf(
                "^where (?:is|are|can i find) (.+?) (?:streaming|available to (?:stream|watch)|available(?: to watch)?|on streaming|showing)$",
                "^(?:what|which) (?:app|apps|service|services|platform|platforms|streaming service|streaming services|streaming app|streaming apps|streaming platform|streaming platforms|streamer|streamers|site|sites|channel|channels|network|music app|music apps|music service|music services) (?:is|are|has|have|carries|carry|streams|stream|got|offers|offer|plays|play|shows|show) (.+?)(?: on| streaming)?$",
                "^(?:is|are) (.+?) (?:on|streaming on|available on|playing on|in|out on) (?:$MUSIC_APPS|$VIDEO_APPS)$",
                "^(?:what|which) streaming (?:service|services|app|apps|platform|platforms|site|sites) (?:is|are) (.+?) on$",
            )
            for (f in noVerb) Patterns.get(f).find(s)?.let { return@run Hit("", it.groupValues[1]) }
            return null
        }
        var subject = hit.subject.trim().replace(Patterns.get(" (?:on|with|in) (?:$MUSIC_APPS|$VIDEO_APPS)$"), "").trim()
        val musicService = service != null && Patterns.get("^(?:$MUSIC_APPS)$").matches(service)
        val videoService = service != null && !musicService
        val songish = hit.verb in setOf("listen to", "hear") || musicService || SONG_WORD.containsMatchIn(subject) || SONG_CUR_RX.matches(subject) || Patterns.get(" by ").containsMatchIn(subject)
        val videoish = hit.verb in setOf("watch", "see", "view", "catch") || videoService || VIDEO_WORD.containsMatchIn(subject)
        val free = FREE.containsMatchIn(s)
        if (songish && !(videoish && hit.verb in setOf("watch", "see", "view", "catch"))) {
            val current = CUR_RX.matches(subject) || SONG_CUR_RX.matches(subject)
            if (current) return Request.MediaAsk(MediaAskKind.LISTEN, null, free = free, service = service)
            val (title, artist) = splitBy(subject)
            val t = cleanTitle(title) ?: return null
            return Request.MediaAsk(MediaAskKind.LISTEN, t, artist = artist, free = free, service = service)
        }
        // "stream", "find" and "get" with nothing saying movie or show could be anything ("where can I find a plumber").
        if (!videoish && !(hit.verb == "stream" || hit.verb == "")) return null
        if (CUR_RX.matches(subject)) return null
        subject = subject.replace(Patterns.get("^(?:the )?(?:full )?(?:new )?(?:episodes? of |seasons? of )"), "")
        val t = cleanTitle(subject) ?: return null
        return Request.MediaAsk(MediaAskKind.WATCH, t, video = videoKind(s), free = free, service = service)
    }

    private fun buy(s: String): Request.MediaAsk? {
        val forms = listOf(
            "^(?:where|how) (?:can|could|do|should|would|will|might) (?:i|we|you|one) (?:go )?(?:to )?(buy|purchase|get|rent|order|pick up|grab|find|download|own) (?:a copy of |a |my own copy of |copies of )?(.+)$",
            "^where (?:to|i can|can i|should i) (buy|purchase|rent|get|download|order) (?:a copy of )?(.+)$",
            "^(?:what|which) (?:stores?|shops?|sites?|websites?|places?|retailers?|apps?|services?) (sell|sells|has|have|carry|carries|stock|stocks|rent|rents) (.+)$",
            "^who (sells|has|carries|rents|stocks) (.+)$",
            "^(?:i want to |i wanna |i'd like to |help me |how do i )(buy|purchase|rent|download|own) (.+?)(?: somewhere| from somewhere| where| digitally| on (?:dvd|blu ray|4k))?$",
        )
        for (f in forms) {
            val m = Patterns.get(f).find(s) ?: continue
            val verb = m.groupValues[1]
            val subject = m.groupValues[2].trim()
            val rent = verb.startsWith("rent")
            val format = when {
                PHYSICAL.containsMatchIn(s) && !Patterns.get("\\b(?:vinyl|cd|cds|cassette|lp)\\b").containsMatchIn(s) -> BuyFormat.PHYSICAL
                rent || DIGITAL.containsMatchIn(s) || verb == "download" -> BuyFormat.DIGITAL
                else -> BuyFormat.ANY
            }
            val songish = SONG_WORD.containsMatchIn(subject) || SONG_CUR_RX.matches(subject) || Patterns.get(" by ").containsMatchIn(subject) ||
                Patterns.get("\\b(?:on|in) (?:$MUSIC_APPS)$").containsMatchIn(s)
            val videoish = rent || VIDEO_WORD.containsMatchIn(subject) || (PHYSICAL.containsMatchIn(s) || Patterns.get("\\b(?:digitally|digital copy|on digital|to own)\\b").containsMatchIn(s)) && !songish
            if (songish && !VIDEO_WORD.containsMatchIn(subject)) {
                if (SONG_CUR_RX.matches(subject) || CUR_RX.matches(subject)) return Request.MediaAsk(MediaAskKind.BUY_SONG, null, format = format)
                val (title, artist) = splitBy(subject)
                val t = cleanTitle(title) ?: return null
                return Request.MediaAsk(MediaAskKind.BUY_SONG, t, artist = artist, format = format)
            }
            if (!videoish || CUR_RX.matches(subject)) return null
            val t = cleanTitle(subject) ?: return null
            return Request.MediaAsk(MediaAskKind.BUY_VIDEO, t, video = videoKind(s), format = format, rent = rent)
        }
        return null
    }

    /** "blinding lights by the weeknd" -> ("blinding lights", "the weeknd"). */
    private fun splitBy(t: String): Pair<String, String?> {
        val i = t.lastIndexOf(" by ")
        if (i <= 0) return t to null
        val artist = t.substring(i + 4).trim().replace(Patterns.get("\\s+(?:on (?:$MUSIC_APPS)|for free|free|online)$"), "").trim()
        return t.substring(0, i).trim() to artist.ifEmpty { null }
    }

    /** MOVIE or TV_SHOW when the wording says ("the movie", "season 2", "the series"). */
    private fun videoKind(s: String): LookupKind? = when {
        Patterns.get("\\b(?:movie|movies|film|films|flick)\\b").containsMatchIn(s) -> LookupKind.MOVIE
        Patterns.get("\\b(?:show|shows|tv|series|season|seasons|episode|episodes|sitcom|anime|docuseries|miniseries)\\b").containsMatchIn(s) -> LookupKind.TV_SHOW
        else -> null
    }

    /** Cuts "the movie", "on blu-ray", "for free", "season 2 of" and the like from around a title. Null if nothing is left. */
    fun cleanTitle(raw: String): String? {
        var t = raw.trim()
        repeat(3) {
            t = t.replace(Patterns.get("\\s+(?:for free|free|for cheap|cheap|cheaply|online|right now|now|legally|in the us|in america|today|tonight|anywhere|please|for me|at|from|somewhere|streaming)$"), "")
            t = t.replace(Patterns.get("\\s+(?:on|in|as an?|as) (?:dvd|blu ray|bluray|4k(?: blu ray| uhd)?|uhd|disc|digital|vinyl|cd|itunes|physical media|physical|a digital copy|a download|mp3|flac)$"), "")
            t = t.replace(Patterns.get("\\s+(?:dvd|blu ray|bluray|4k|digitally|digital copy|on digital|physically|to own|box set|boxset|steelbook|copy)$"), "")
            t = t.replace(Patterns.get("\\s+(?:on|from|at) (?:$VIDEO_APPS|$MUSIC_APPS)$"), "")
            t = t.replace(Patterns.get("^(?:the |a |an )?(?:dvd|blu ray|bluray|4k|digital copy|box set|copy|physical copy|hard copy) (?:of )?"), "")
            t = t.replace(Patterns.get("^(?:the |a |an |that )?(?:movie|film|flick|show|tv show|tv series|series|anime|documentary|song|track|single|album|tune)(?: called| named| titled)? "), "")
            t = t.replace(Patterns.get("\\s+(?:the )?(?:movie|film|show|tv show|series|song|track|single)$"), "")
            t = t.replace(Patterns.get("^(?:the )?(?:first|second|third|fourth|fifth|last|latest|new|newest|final|\\d+(?:st|nd|rd|th)?) seasons? of "), "")
            t = t.replace(Patterns.get("^seasons? \\d+(?: (?:and|through|to) \\d+)? of "), "")
            t = t.replace(Patterns.get("\\s+seasons? \\d+(?: (?:and|through|to) \\d+)?$"), "")
            t = t.replace(Patterns.get("\\s+(?:all )?seasons$"), "")
            t = t.trim()
        }
        t = t.removeSuffix("'s").trim()
        if (t.isEmpty() || CUR_RX.matches(t) || t in setOf("movies", "shows", "songs", "music", "stuff", "things", "something", "anything", "tv", "a movie", "a show", "a song",
                "i", "me", "we", "you", "there", "here", "one", "can i", "can we", "do i")) return null // "where can i watch" names no title ("It" and "Us" are real ones)
        return t
    }

    // ------------------------------------------------------------------ movies and shows

    /** Where a Wikidata ID says a title is: property -> (service, how, link). */
    private data class Listing(val prop: String, val name: String, val access: Access, val url: (id: String, title: String) -> String)
    private fun g(title: String, where: String) = "https://www.google.com/search?q=" + Http.enc("watch $title on $where")
    private val LISTINGS = listOf(
        Listing("P1874", "Netflix", Access.SUBSCRIPTION) { id, _ -> "https://www.netflix.com/title/$id" },
        Listing("P6466", "Hulu", Access.SUBSCRIPTION) { id, _ -> "https://www.hulu.com/movie/$id" },
        Listing("P6467", "Hulu", Access.SUBSCRIPTION) { id, _ -> "https://www.hulu.com/series/$id" },
        Listing("P7595", "Disney+", Access.SUBSCRIPTION) { _, t -> g(t, "Disney+") },
        Listing("P7596", "Disney+", Access.SUBSCRIPTION) { _, t -> g(t, "Disney+") },
        Listing("P8298", "HBO Max", Access.SUBSCRIPTION) { _, t -> g(t, "HBO Max") },
        Listing("P11815", "Peacock", Access.SUBSCRIPTION) { _, t -> g(t, "Peacock") },
        Listing("P13147", "Paramount+", Access.SUBSCRIPTION) { _, t -> g(t, "Paramount+") },
        Listing("P11330", "Crunchyroll", Access.SUBSCRIPTION) { id, _ -> "https://www.crunchyroll.com/series/$id" },
        Listing("P7760", "Tubi", Access.FREE) { id, _ -> "https://tubitv.com/movies/$id" },
        Listing("P7761", "Tubi", Access.FREE) { id, _ -> "https://tubitv.com/series/$id" },
        Listing("P8055", "Prime Video", Access.RENT_BUY) { id, _ -> "https://www.primevideo.com/detail/$id" },
        Listing("P9586", "Apple TV", Access.RENT_BUY) { id, _ -> "https://tv.apple.com/movie/$id" },
        Listing("P9751", "Apple TV", Access.RENT_BUY) { id, _ -> "https://tv.apple.com/show/$id" },
    )

    /** A show's home network -> the service it usually streams on in the US. */
    private val NETWORK_HOME = mapOf(
        "hbo" to "HBO Max", "cinemax" to "HBO Max", "cartoon network" to "HBO Max", "adult swim" to "HBO Max", "tbs" to "HBO Max", "tnt" to "HBO Max",
        "discovery" to "HBO Max", "hgtv" to "HBO Max", "food network" to "HBO Max", "tlc" to "HBO Max", "max" to "HBO Max", "hbo max" to "HBO Max",
        "amc" to "AMC+", "fx" to "Hulu", "fxx" to "Hulu", "abc" to "Hulu", "freeform" to "Hulu", "fox" to "Hulu", "hulu" to "Hulu",
        "nbc" to "Peacock", "bravo" to "Peacock", "usa network" to "Peacock", "syfy" to "Peacock", "peacock" to "Peacock", "e!" to "Peacock",
        "cbs" to "Paramount+", "showtime" to "Paramount+", "comedy central" to "Paramount+", "nickelodeon" to "Paramount+", "mtv" to "Paramount+",
        "bet" to "Paramount+", "paramount+" to "Paramount+", "netflix" to "Netflix", "disney+" to "Disney+", "disney channel" to "Disney+",
        "apple tv+" to "Apple TV", "apple tv" to "Apple TV", "prime video" to "Prime Video", "amazon prime video" to "Prime Video",
        "starz" to "Starz", "the cw" to "The CW app (free)", "pbs" to "PBS app (free)",
    )

    private fun search(name: String, title: String): String = when (name) {
        "Apple TV" -> "https://tv.apple.com/search?term=${Http.enc(title)}"
        "Prime Video" -> "https://www.amazon.com/s?k=${Http.enc(title)}&i=instant-video"
        "Google TV" -> "https://play.google.com/store/search?q=${Http.enc(title)}&c=movies"
        "Tubi" -> "https://tubitv.com/search/${Http.enc(title).replace("+", "%20")}"
        "YouTube" -> "https://www.youtube.com/results?search_query=${Http.enc("$title full movie")}"
        else -> g(title, name)
    }

    /** Finds a movie or show and what's known about its scores and where to watch it. */
    suspend fun video(title: String, kind: LookupKind?, tmdbKey: String, country: String): VideoInfo? = coroutineScope {
        val accept = if (kind != null) setOf(kind) else setOf(LookupKind.MOVIE, LookupKind.TV_SHOW)
        val words = if (kind != null) listOf(kind.searchWord) else emptyList()
        val page = Lookup.page(title, accept, words) ?: return@coroutineScope null
        // Not a movie or show ("how good is chatgpt"): let the normal search answer instead.
        if (kind == null && page.kind != null && page.kind !in setOf(LookupKind.MOVIE, LookupKind.TV_SHOW)) return@coroutineScope null
        val k = page.kind?.takeIf { it in setOf(LookupKind.MOVIE, LookupKind.TV_SHOW) } ?: kind
        val name = page.title.replace(Patterns.get("\\s*\\((?:\\d{4} )?(?:film|movie|TV series|American TV series|miniseries|series)\\)$"), "")
        val claimsJob = async { page.qid?.let { Lookup.claims(it) } }
        val mazeJob = async { if (k != LookupKind.MOVIE) tvmaze(name) else null }
        val tmdbJob = async { if (tmdbKey.isNotBlank()) tmdb(name, k, tmdbKey, country) else null }
        val appleJob = async { apple(name, k) }
        val claims = claimsJob.await()
        val scores = claims?.let { Lookup.reviewScores(it) }.orEmpty()
        val cert = claims?.optJSONArray("P1657")?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
            ?.optJSONObject("value")?.optString("id")?.let { Lookup.labels(listOf(it))[it] }
        val year = claims?.let { c -> listOf("P577", "P580").firstNotNullOfOrNull { p ->
            c.optJSONArray(p)?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")
        } }?.removePrefix("+")?.take(4)
        val maze = mazeJob.await()
        val tmdb = tmdbJob.await()
        val apple = appleJob.await()

        val spots = ArrayList<Spot>()
        val source: String
        if (tmdb != null && tmdb.providers != null) {
            source = "TMDB, JustWatch"
            spots += tmdb.providers
        } else {
            source = "Wikidata"
            if (claims != null) for (l in LISTINGS) {
                val id = Lookup.strings(claims, l.prop).firstOrNull() ?: continue
                if (spots.none { it.name == l.name }) spots += Spot(l.name, l.access, l.url(id, name), checked = true, note = "listed")
            }
            val home = maze?.network?.lowercase(Locale.US)?.let { NETWORK_HOME[it] }
            if (home != null && spots.none { it.name == home }) {
                val access = if (home.endsWith("(free)")) Access.FREE else Access.SUBSCRIPTION
                spots += Spot(home, access, g(name, home), checked = false, note = "usually, from ${maze.network}")
            }
        }
        // Stores that rent and sell almost every movie and show.
        for (store in listOf("Apple TV", "Prime Video", "Google TV", "Fandango at Home")) {
            val i = spots.indexOfFirst { it.name == store }
            val appleHere = store == "Apple TV" && apple != null
            if (i < 0) spots += Spot(store, Access.RENT_BUY, apple?.url?.takeIf { appleHere } ?: search(store, name), checked = appleHere,
                note = if (appleHere) applePrices(apple!!) else null)
            else if (appleHere) spots[i] = spots[i].copy(checked = true, note = applePrices(apple!!))
        }
        VideoInfo(name, k, year ?: maze?.premiered?.take(4), page.description, page.image ?: maze?.image, page.url, scores,
            cert ?: apple?.certificate, maze?.rating, tmdb?.rating, maze?.network, spots, apple, source)
    }

    private fun applePrices(a: AppleVideo): String? = listOfNotNull(
        a.buy?.let { if (a.season != null) "buy from ${money(it)} a season" else "buy ${money(it)}" }, a.rent?.let { "rent ${money(it)}" },
    ).joinToString(", ").ifEmpty { null }

    fun money(v: Double) = "$" + "%.2f".format(Locale.US, v)

    private data class Maze(val rating: Double?, val network: String?, val image: String?, val premiered: String?)

    private suspend fun tvmaze(title: String): Maze? = withContext(Dispatchers.IO) {
        val j = Http.getJsonCached("https://api.tvmaze.com/singlesearch/shows?q=${Http.enc(title)}") ?: return@withContext null
        Maze(
            j.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() },
            (j.optJSONObject("webChannel") ?: j.optJSONObject("network"))?.optString("name")?.ifBlank { null },
            j.optJSONObject("image")?.optString("medium")?.ifBlank { null }?.replace("http://", "https://"),
            j.optString("premiered").ifBlank { null },
        )
    }

    private data class Tmdb(val rating: Double?, val providers: List<Spot>?)

    /** TMDB's rating and its JustWatch listings for [country] (needs a free TMDB key: v3 key or read token). */
    private suspend fun tmdb(title: String, kind: LookupKind?, key: String, country: String): Tmdb? = withContext(Dispatchers.IO) {
        val bearer = key.length > 40
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        fun url(path: String) = "https://api.themoviedb.org/3$path" + (if (bearer) "" else (if ('?' in path) "&" else "?") + "api_key=${Http.enc(key)}")
        val type = when (kind) { LookupKind.MOVIE -> "movie"; LookupKind.TV_SHOW -> "tv"; else -> "multi" }
        val found = Http.getJsonCached(url("/search/$type?query=${Http.enc(title)}"), headers)?.optJSONArray("results") ?: return@withContext null
        val hit = (0 until found.length()).mapNotNull { found.optJSONObject(it) }
            .firstOrNull { type != "multi" || it.optString("media_type") in setOf("movie", "tv") } ?: return@withContext null
        val media = if (type == "multi") hit.optString("media_type") else type
        val id = hit.optInt("id")
        val rating = hit.optDouble("vote_average").takeIf { !it.isNaN() && hit.optInt("vote_count") > 10 }
        val r = Http.getJsonCached(url("/$media/$id/watch/providers"), headers)?.optJSONObject("results")?.optJSONObject(country)
            ?: return@withContext Tmdb(rating, null)
        val link = r.optString("link").ifBlank { null }
        val spots = LinkedHashMap<String, Spot>()
        fun add(field: String, access: Access) {
            val arr = r.optJSONArray(field) ?: return
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("provider_name")?.ifBlank { null } ?: continue
                val clean = name.replace(Patterns.get("\\s+(?:Amazon Channel|Apple TV Channel|Roku Premium Channel)$"), "").replace("Amazon Prime Video", "Prime Video")
                    .replace("Apple TV Store", "Apple TV").replace("Google Play Movies", "Google TV")
                val prev = spots[clean]
                spots[clean] = when {
                    prev == null -> Spot(clean, access, link ?: search(clean, title), checked = true)
                    prev.access == Access.SUBSCRIPTION && access == Access.RENT_BUY -> prev.copy(access = Access.SUB_OR_BUY)
                    else -> prev
                }
            }
        }
        add("flatrate", Access.SUBSCRIPTION); add("free", Access.FREE); add("ads", Access.FREE); add("rent", Access.RENT_BUY); add("buy", Access.RENT_BUY)
        Tmdb(rating, spots.values.toList())
    }

    /** Checks a TMDB key with one search. */
    suspend fun tmdbWorks(key: String): Boolean = withContext(Dispatchers.IO) {
        val bearer = key.length > 40
        val u = "https://api.themoviedb.org/3/search/movie?query=inception" + if (bearer) "" else "&api_key=${Http.enc(key)}"
        Http.getJson(u, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap())?.has("results") == true
    }

    /** Apple's store: the title's buy and rent price (the store search misses some titles). */
    private suspend fun apple(title: String, kind: LookupKind?): AppleVideo? = withContext(Dispatchers.IO) {
        val norm = norm(title)
        if (kind != LookupKind.TV_SHOW) {
            val r = Http.getJsonCached("https://itunes.apple.com/search?term=${Http.enc(title)}&media=movie&entity=movie&country=US&limit=10")?.optJSONArray("results")
            val m = r?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { norm(it.optString("trackName")) == norm } }
            if (m != null) return@withContext AppleVideo(
                m.optDouble("trackPrice").takeIf { it > 0 }, m.optDouble("trackRentalPrice").takeIf { it > 0 },
                m.optString("trackViewUrl").substringBefore("?"), m.optString("contentAdvisoryRating").ifBlank { null }, null)
        }
        if (kind != LookupKind.MOVIE) {
            val r = Http.getJsonCached("https://itunes.apple.com/search?term=${Http.enc(title)}&media=tvShow&entity=tvSeason&country=US&limit=25")?.optJSONArray("results")
            val seasons = r?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { norm(it.optString("artistName")) == norm } }.orEmpty()
            val first = seasons.minByOrNull { Patterns.get("Season (\\d+)").find(it.optString("collectionName"))?.groupValues?.get(1)?.toIntOrNull() ?: 99 }
            val cheapest = seasons.mapNotNull { it.optDouble("collectionPrice").takeIf { p -> p > 0 } }.minOrNull()
            if (first != null) return@withContext AppleVideo(
                cheapest, null, first.optString("collectionViewUrl").substringBefore("?"),
                first.optString("contentAdvisoryRating").ifBlank { null }, "from, a season")
        }
        null
    }

    private fun norm(s: String) = s.lowercase(Locale.US).replace("&", "and").replace(Patterns.get("[^a-z0-9]+"), "")

    /** Stores to buy a movie or show from, digital and on disc, with pros and cons. */
    fun videoStores(v: VideoInfo, format: BuyFormat): Pair<List<Store>, List<Store>> {
        val t = v.title
        val applePrice = v.apple?.let { applePrices(it) }
        val digital = listOf(
            Store("Apple TV (iTunes)", v.apple?.url ?: search("Apple TV", t),
                listOf("4K, HDR and Dolby Vision usually at no extra cost", "iTunes Extras on many movies"),
                listOf("Best on Apple devices; on Android, watch through Movies Anywhere"), applePrice, v.apple != null),
            Store("Prime Video", search("Prime Video", t),
                listOf("Works on almost every phone, TV and console", "Frequent sales"),
                listOf("4K isn't on every device", "Store mixes in Prime and ad-supported titles")),
            Store("Fandango at Home (Vudu)", "https://www.google.com/search?q=" + Http.enc("$t Fandango at Home"),
                listOf("Regular sales and bundle deals", "Strong 4K catalog"),
                listOf("Fewer devices than Amazon", "App pushes its free, ad-supported movies")),
            Store("Google TV / YouTube", search("Google TV", t),
                listOf("Built into Android and Google TV", "Share purchases with up to 5 family members"),
                listOf("4K depends on the device", "Google TV and YouTube storefronts can be confusing")),
        )
        val q = "$t ${if (v.kind == LookupKind.TV_SHOW) "complete series" else "blu ray"}"
        val physical = listOf(
            Store("Amazon", "https://www.amazon.com/s?k=${Http.enc(q)}", listOf("Biggest selection, including imports"), listOf("Watch for third-party sellers")),
            Store("Walmart", "https://www.walmart.com/search?q=${Http.enc(q)}", listOf("Low prices, pick up in store"), listOf("Smaller 4K selection")),
            Store("Target", "https://www.target.com/s?searchTerm=${Http.enc(q)}", listOf("Exclusive editions and steelbooks"), listOf("Small in-store shelf")),
            Store("Barnes & Noble", "https://www.barnesandnoble.com/s/${Http.enc(q)}", listOf("Criterion Collection sales"), listOf("Higher regular prices")),
            Store("eBay (used)", "https://www.ebay.com/sch/i.html?_nkw=${Http.enc(q)}", listOf("Cheap used and out-of-print discs"), listOf("Check condition and seller")),
        )
        return when (format) {
            BuyFormat.PHYSICAL -> emptyList<Store>() to physical
            BuyFormat.DIGITAL -> digital to emptyList()
            BuyFormat.ANY -> digital to physical
        }
    }

    // ------------------------------------------------------------------ songs

    private val NOISE = Regex(
        "\\s*(?:\\((?:feat|ft|with|prod)[^)]*\\)|\\[(?:feat|ft|with|prod)[^]]*]|\\((?:official|lyric|lyrics|audio|visualizer|music video|video|explicit|clean|remaster(?:ed)?|\\d{4} remaster(?:ed)?)[^)]*\\)|" +
            "\\[(?:official|lyric|lyrics|audio|visualizer|music video|video|explicit|clean|remaster(?:ed)?)[^]]*]|\\s-\\s(?:\\d{4} )?remaster(?:ed)?(?: \\d{4})?.*$|\\s(?:feat|ft)\\.? .*$)",
        RegexOption.IGNORE_CASE,
    )

    /** What a media app reports, cleaned for searching ("Song (Official Video)" by "ArtistVEVO"). */
    fun cleanPlaying(title: String, artist: String?): Pair<String, String?> {
        var t = title.replace(NOISE, "").trim()
        var a = artist?.replace(Patterns.get("(?:VEVO| - Topic|Official|Music)$", true), "")?.trim()?.ifBlank { null }
        // YouTube videos are often "Artist - Song".
        if (" - " in t && (a == null || t.startsWith(a, ignoreCase = true) || a.contains("channel", true))) {
            a = t.substringBefore(" - ").trim(); t = t.substringAfter(" - ").trim()
        }
        return t to a
    }

    /** Identifies a song on Deezer (free, no key), then adds its album, label, genre and Apple's price. */
    suspend fun song(title: String, artist: String?): SongInfo? = coroutineScope {
        val q = if (artist != null) "artist:\"$artist\" track:\"$title\"" else title
        var list = withContext(Dispatchers.IO) { Http.getJsonCached("https://api.deezer.com/search?q=${Http.enc(q)}&limit=10")?.optJSONArray("data") }
        if ((list == null || list.length() == 0) && artist != null)
            list = withContext(Dispatchers.IO) { Http.getJsonCached("https://api.deezer.com/search?q=${Http.enc("$title $artist")}&limit=10")?.optJSONArray("data") }
        val items = list?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val nt = norm(title)
        val hit = items.firstOrNull { norm(it.optString("title_short").ifBlank { it.optString("title") }) == nt && (artist == null || norm(it.optJSONObject("artist")?.optString("name").orEmpty()) == norm(artist)) }
            ?: items.firstOrNull { norm(it.optString("title")).startsWith(nt) }
            ?: items.firstOrNull()
            ?: return@coroutineScope null
        val id = hit.optLong("id")
        val name = hit.optString("title_short").ifBlank { hit.optString("title") }
        val by = hit.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { artist ?: "" }
        val trackJob = async(Dispatchers.IO) { Http.getJsonCached("https://api.deezer.com/track/$id") }
        val appleJob = async(Dispatchers.IO) { appleSong(name, by) }
        val track = trackJob.await()
        val albumId = track?.optJSONObject("album")?.optLong("id") ?: hit.optJSONObject("album")?.optLong("id")
        val album = albumId?.let { withContext(Dispatchers.IO) { Http.getJsonCached("https://api.deezer.com/album/$it") } }
        val genres = album?.optJSONObject("genres")?.optJSONArray("data")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
        val contributors = track?.optJSONArray("contributors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
        val apple = appleJob.await()
        SongInfo(
            name, by,
            hit.optJSONObject("album")?.optString("title")?.ifBlank { null },
            hit.optJSONObject("album")?.optString("cover_big")?.ifBlank { null } ?: hit.optJSONObject("album")?.optString("cover_medium")?.ifBlank { null },
            hit.optString("link").ifBlank { null },
            (track?.optString("isrc") ?: hit.optString("isrc")).ifBlank { null },
            hit.optInt("duration").takeIf { it > 0 },
            hit.optBoolean("explicit_lyrics"),
            (track?.optString("release_date") ?: album?.optString("release_date"))?.ifBlank { null }?.takeIf { it != "0000-00-00" },
            album?.optString("label")?.ifBlank { null },
            genres,
            contributors.filter { it != by },
            apple?.first, apple?.second,
        )
    }

    /** iTunes price and link for a song ("1.29" and its page), or null. */
    private fun appleSong(title: String, artist: String): Pair<Double?, String>? {
        val r = Http.getJsonCached("https://itunes.apple.com/search?term=${Http.enc("$title $artist")}&media=music&entity=song&country=US&limit=10")?.optJSONArray("results")
            ?: return null
        val nt = norm(title); val na = norm(artist)
        val m = (0 until r.length()).mapNotNull { r.optJSONObject(it) }.firstOrNull {
            norm(it.optString("trackName")).startsWith(nt) && norm(it.optString("artistName")).contains(na.take(6))
        } ?: return null
        return m.optDouble("trackPrice").takeIf { it > 0 } to m.optString("trackViewUrl").substringBefore("?")
    }

    /**
     * Song credits from MusicBrainz (free, no key): vocals, producers, writers, engineers and players. MusicBrainz asks
     * for at most one request a second, so the calls are spaced.
     */
    suspend fun credits(song: SongInfo): SongCredits = withContext(Dispatchers.IO) {
        val mb = "https://musicbrainz.org/ws/2"
        val ids = ArrayList<String>()
        song.isrc?.let { isrc ->
            Http.getJsonCached("$mb/isrc/$isrc?fmt=json")?.optJSONArray("recordings")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("id")?.let { ids += it } }
        }
        if (ids.isEmpty()) {
            if (song.isrc != null) delay(1100)
            val q = "recording:\"${song.title.replace("\"", "")}\" AND artist:\"${song.artist.replace("\"", "")}\""
            Http.getJsonCached("$mb/recording?query=${Http.enc(q)}&fmt=json&limit=3")?.optJSONArray("recordings")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optInt("score") >= 90 }?.optString("id")?.let { ids += it }
            }
        }
        var best = LinkedHashMap<String, List<String>>()
        for (id in ids.take(2)) {
            delay(1100)
            val rec = Http.getJsonCached("$mb/recording/$id?inc=artist-rels+work-rels+work-level-rels+artist-credits&fmt=json") ?: continue
            val roles = parseCredits(rec)
            if (roles.values.sumOf { it.size } > best.values.sumOf { it.size }) best = roles
            if (best.size >= 3) break
        }
        SongCredits(best, if (best.isEmpty()) null else "MusicBrainz")
    }

    private fun parseCredits(rec: JSONObject): LinkedHashMap<String, List<String>> {
        val by = LinkedHashMap<String, LinkedHashSet<String>>()
        fun add(role: String, name: String?) { if (!name.isNullOrBlank()) by.getOrPut(role) { LinkedHashSet() } += name }
        val rels = rec.optJSONArray("relations")
        val workRels = ArrayList<JSONObject>()
        if (rels != null) for (i in 0 until rels.length()) {
            val r = rels.optJSONObject(i) ?: continue
            val name = r.optJSONObject("artist")?.optString("name")
            val attrs = r.optJSONArray("attributes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            when (r.optString("type")) {
                "vocal" -> add(if ("background vocals" in attrs || "choir vocals" in attrs) "Backing vocals" else "Vocals", name)
                "producer" -> add(if ("executive" in attrs) "Executive producer" else "Producer", name)
                "programming" -> add("Programming", name)
                "instrument" -> add(attrs.firstOrNull()?.let { instrumentLabel(it) } ?: "Instruments", name)
                "performer" -> add("Performer", name)
                "mix" -> add("Mixing", name)
                "mastering" -> add("Mastering", name)
                "engineer", "recording", "audio", "sound" -> if ("assistant" !in attrs) add("Engineer", name)
                "performance" -> r.optJSONObject("work")?.optJSONArray("relations")?.let { w -> for (j in 0 until w.length()) w.optJSONObject(j)?.let { workRels += it } }
            }
        }
        for (r in workRels) {
            val name = r.optJSONObject("artist")?.optString("name")
            when (r.optString("type")) {
                "composer" -> add("Composer", name)
                "lyricist" -> add("Lyricist", name)
                "writer" -> add("Writer", name)
                "arranger" -> add("Arranger", name)
            }
        }
        // A composer who also wrote the lyrics reads better once, as a writer.
        val comp = by["Composer"]; val lyr = by["Lyricist"]
        if (comp != null && lyr != null && comp == lyr) { by.getOrPut("Writer") { LinkedHashSet() }.addAll(comp); by.remove("Composer"); by.remove("Lyricist") }
        val order = listOf("Vocals", "Backing vocals", "Producer", "Executive producer", "Writer", "Composer", "Lyricist", "Arranger", "Programming")
        val out = LinkedHashMap<String, List<String>>()
        for (k in order) by[k]?.let { out[k] = it.toList() }
        for ((k, v) in by) if (k !in out && k !in setOf("Engineer", "Mixing", "Mastering")) out[k] = v.toList()
        for (k in listOf("Mixing", "Mastering", "Engineer")) by[k]?.let { out[k] = it.toList() }
        return out
    }

    private fun instrumentLabel(a: String): String {
        val base = a.substringBefore(" (").trim()
        return when (base) {
            "keyboard", "keyboards", "synthesizer", "piano", "organ" -> base.replaceFirstChar { it.uppercase() }
            "drums", "drum machine", "percussion" -> "Drums"
            "bass guitar", "bass", "electric bass guitar" -> "Bass"
            else -> base.replaceFirstChar { it.uppercase() }
        }
    }

    /** Where to stream a song, free and paid. [checked] ones were found by a source; the rest are search links. */
    fun listenSpots(s: SongInfo): List<Spot> {
        val q = "${s.title} ${s.artist}"
        val e = Http.enc(q)
        return listOf(
            Spot("Spotify", Access.FREE_OR_PAID, "https://open.spotify.com/search/${e.replace("+", "%20")}", false, "free with ads; Premium for any song, offline"),
            Spot("YouTube Music", Access.FREE_OR_PAID, "https://music.youtube.com/search?q=$e", false, "free with ads; Premium for background play"),
            Spot("Amazon Music", Access.FREE_OR_PAID, "https://music.amazon.com/search/${e.replace("+", "%20")}", false, "free tier with ads; Prime or Unlimited"),
            Spot("Pandora", Access.FREE_OR_PAID, "https://www.pandora.com/search/${e.replace("+", "%20")}/all", false, "free radio with ads; Premium to pick songs"),
            Spot("SoundCloud", Access.FREE_OR_PAID, "https://soundcloud.com/search?q=$e", false, "free with ads; Go+ for the full catalog"),
            Spot("Deezer", Access.FREE_OR_PAID, s.deezerUrl ?: "https://www.deezer.com/search/${e.replace("+", "%20")}", s.deezerUrl != null, "free with ads in some countries; Premium"),
            Spot("YouTube", Access.FREE, "https://www.youtube.com/results?search_query=$e", false, "music videos, free with ads"),
            Spot("Apple Music", Access.SUBSCRIPTION, s.appleUrl?.replace("itunes.apple.com", "music.apple.com") ?: "https://music.apple.com/us/search?term=$e", s.appleUrl != null, "subscription (free trial)"),
            Spot("Tidal", Access.SUBSCRIPTION, "https://tidal.com/search?q=$e", false, "subscription, lossless"),
        )
    }

    /** Good stores to buy a song from, with pros and cons. */
    fun songStores(s: SongInfo): List<Store> {
        val e = Http.enc("${s.title} ${s.artist}")
        val dance = s.genres.any { Patterns.get("dance|electro|house|techno|edm|trance", true).containsMatchIn(it) }
        return listOfNotNull(
            Store("iTunes Store", s.appleUrl ?: "https://music.apple.com/us/search?term=$e",
                listOf("256 kbps AAC with no copy protection; the file is yours", "Big catalog, including major labels"),
                listOf("Not lossless", "Buy in iTunes or on an iPhone; there's no store on Android"),
                s.applePrice?.let { money(it) }, s.appleUrl != null),
            Store("Amazon Digital Music", "https://www.amazon.com/s?k=$e&i=digital-music",
                listOf("MP3s with no copy protection; play anywhere", "Often cheaper than iTunes"),
                listOf("MP3 only, not lossless", "Buy on the website; the app pushes streaming")),
            Store("Bandcamp", "https://bandcamp.com/search?q=$e",
                listOf("Lossless FLAC, WAV or MP3", "Most of the money goes to the artist"),
                listOf("Mostly independent artists; major-label hits are rarely there")),
            Store("Qobuz", "https://www.qobuz.com/us-en/search?q=$e",
                listOf("Hi-res lossless downloads (up to 24-bit)", "Good for audiophiles"),
                listOf("Costs more than iTunes or Amazon")),
            if (dance) Store("Beatport", "https://www.beatport.com/search?q=$e",
                listOf("Extended mixes and WAV/AIFF for DJs"), listOf("Electronic and dance music only")) else null,
        )
    }

    // ------------------------------------------------------------------ what Brick says

    /** A score as a 0-100 number for the bars ("87%" -> 87, "7.4/10" -> 74, "74/100" -> 74). */
    fun percent(value: String): Int? {
        Patterns.get("^(\\d+(?:\\.\\d+)?)\\s*%$").find(value.trim())?.let { return it.groupValues[1].toDouble().toInt() }
        Patterns.get("^(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)$").find(value.trim())?.let {
            val d = it.groupValues[2].toDouble(); return if (d > 0) (it.groupValues[1].toDouble() * 100 / d).toInt() else null
        }
        return null
    }

    /** Scores worth showing, one line each: "Rotten Tomatoes" "87%" "critics". Drops the RT averages. */
    fun scoreLines(scores: List<Lookup.ReviewScore>): List<Triple<String, String, String?>> {
        val out = ArrayList<Triple<String, String, String?>>()
        for (s in scores) {
            val m = s.method?.lowercase(Locale.US).orEmpty()
            val who = when {
                s.by.contains("rotten", true) -> "Rotten Tomatoes"
                s.by.contains("metacritic", true) -> "Metacritic"
                s.by.contains("imdb", true) -> "IMDb"
                else -> s.by
            }
            if (who == "Rotten Tomatoes" && !s.value.endsWith("%")) continue
            val note = when {
                "audience" in m || "popcorn" in m || "user" in m -> "audience"
                "tomatometer" in m || "metascore" in m || "critic" in m -> "critics"
                else -> null
            }
            if (out.none { it.first == who && it.third == note }) out += Triple(who, s.value, note)
        }
        val main = listOf("Rotten Tomatoes", "Metacritic", "IMDb")
        val known = out.filter { it.first in main }
        return (known.ifEmpty { out.take(2) }).sortedBy { main.indexOf(it.first).let { i -> if (i < 0) 9 else i } }
    }

    /** "RT 87% · MC 74 · IMDb 8.8" for a lookup card, or null. */
    fun shortScores(scores: List<Lookup.ReviewScore>): String? = scoreLines(scores).filter { it.third != "audience" }.joinToString(" · ") { (who, v, _) ->
        when (who) { "Rotten Tomatoes" -> "RT $v"; "Metacritic" -> "MC ${v.substringBefore('/')}"; "IMDb" -> "IMDb ${v.substringBefore('/')}"; else -> "$who $v" }
    }.ifEmpty { null }

    fun ratingLine(v: VideoInfo): String {
        val parts = scoreLines(v.scores).map { (who, value, note) ->
            when (who) {
                "Rotten Tomatoes" -> "$value on Rotten Tomatoes" + (if (note == "audience") " with audiences" else "")
                "Metacritic" -> "${value.substringBefore('/')} on Metacritic"
                "IMDb" -> "${value.substringBefore('/')} out of 10 on IMDb"
                else -> "$value from $who"
            }
        }.toMutableList()
        v.tmdb?.let { parts += "%.1f out of 10 on TMDB".format(Locale.US, it) }
        v.tvmaze?.let { parts += "%.1f out of 10 from TVmaze viewers".format(Locale.US, it) }
        val cert = v.certificate?.let { " It's rated $it." } ?: ""
        if (parts.isEmpty()) return "I couldn't find review scores for ${v.title}.$cert"
        return "${v.title} has ${join(parts)}.$cert"
    }

    fun watchLine(v: VideoInfo, free: Boolean): String {
        val stream = v.spots.filter { it.checked && (it.access == Access.SUBSCRIPTION || it.access == Access.SUB_OR_BUY) }.map { it.name }
        val usually = v.spots.filter { it.access == Access.SUBSCRIPTION && !it.checked }.map { it.name }
        val freeSpots = v.spots.filter { it.access == Access.FREE }.map { it.name }
        val buy = v.spots.filter { it.access in setOf(Access.RENT_BUY, Access.SUB_OR_BUY) }.map { it.name }.distinct()
        val sb = StringBuilder()
        if (free) {
            if (freeSpots.isNotEmpty()) sb.append("${v.title} is free with ads on ${join(freeSpots)}. ")
            else sb.append("I didn't find ${v.title} free anywhere. Tubi, Pluto TV and The Roku Channel are worth a search. ")
        }
        when {
            stream.isNotEmpty() -> sb.append("You can stream ${v.title} on ${join(stream)}. ")
            usually.isNotEmpty() -> sb.append("${v.title} usually streams on ${join(usually)}. ")
            !free && freeSpots.isEmpty() -> sb.append("I couldn't confirm a streaming service for ${v.title}. ")
        }
        if (!free && freeSpots.isNotEmpty() && stream.isNotEmpty()) sb.append("It's also free with ads on ${join(freeSpots)}. ")
        if (!free && freeSpots.isNotEmpty() && stream.isEmpty()) sb.append("${v.title} is free with ads on ${join(freeSpots)}. ")
        if (buy.isNotEmpty()) sb.append("Rent or buy it on ${join(buy.take(4))}.")
        return sb.toString().trim()
    }

    fun buyVideoLine(v: VideoInfo, format: BuyFormat, rent: Boolean): String {
        val price = v.apple?.let { a -> if (rent) a.rent?.let { " (${money(it)} to rent on Apple)" } else a.buy?.let { " (${money(it)} on Apple)" } } ?: ""
        val digital = "Apple TV$price, Prime Video, Google TV and Fandango at Home"
        if (rent) return "You can rent ${v.title} on $digital."
        val disc = "On disc, try Amazon, Walmart, Target or Barnes & Noble. Best Buy stopped selling movies on disc in 2024."
        return when (format) {
            BuyFormat.DIGITAL -> "You can buy ${v.title} digitally on $digital. The card has pros and cons for each store."
            BuyFormat.PHYSICAL -> "For ${v.title} on disc, try Amazon, Walmart, Target, Barnes & Noble, or eBay for used copies. Best Buy stopped selling movies on disc in 2024."
            BuyFormat.ANY -> "You can buy ${v.title} digitally on $digital. $disc"
        }
    }

    fun listenLine(s: SongInfo, free: Boolean, service: String?): String {
        val name = "${s.title} by ${s.artist}"
        val found = listOfNotNull(if (s.deezerUrl != null) "Deezer" else null, if (s.appleUrl != null) "Apple Music" else null)
        if (service != null) {
            val svc = service.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.replace("Youtube", "YouTube").replace("Soundcloud", "SoundCloud")
            val confirmed = found.any { it.equals(svc, true) }
            return if (confirmed) "Yes, $name is on $svc." else "I can't check $svc directly, but $name is on ${join(found.ifEmpty { listOf("Deezer") })}, so it's very likely on $svc too. The card has a search link."
        }
        val head = if (found.isNotEmpty()) "I found $name on ${join(found)}. Songs there are almost always on the others too. " else "$name should be on the big services. "
        return head + if (free) "Free with ads: Spotify, YouTube Music, Amazon Music's free tier, Pandora and SoundCloud."
        else "Free with ads: Spotify, YouTube Music, Pandora and SoundCloud. Paid: Apple Music, Tidal, and the premium tiers."
    }

    fun buySongLine(s: SongInfo): String {
        val itunes = s.applePrice?.let { "on iTunes for ${money(it)}" } ?: "on iTunes"
        return "You can buy ${s.title} by ${s.artist} $itunes, or on Amazon Music, Bandcamp or Qobuz. The card has pros and cons for each store."
    }

    /** The answer to a credits question: "Blinding Lights was produced by Max Martin, Oscar Holter and The Weeknd." */
    fun creditsLine(s: SongInfo, c: SongCredits, role: CreditRole, instrument: String?): String {
        val t = s.title
        fun names(vararg keys: String) = keys.flatMap { c.roles[it].orEmpty() }.distinct()
        return when (role) {
            CreditRole.VOCALS -> {
                val v = names("Vocals").ifEmpty { listOf(s.artist) + s.featured }
                "${join(v)} ${if (v.size == 1) "sings" else "sing"} $t."
            }
            CreditRole.PRODUCER -> names("Producer").takeIf { it.isNotEmpty() }?.let { "$t was produced by ${join(it)}." } ?: "I couldn't find who produced $t."
            CreditRole.WRITER, CreditRole.COMPOSER, CreditRole.LYRICIST -> {
                val w = when (role) { CreditRole.COMPOSER -> names("Composer", "Writer"); CreditRole.LYRICIST -> names("Lyricist", "Writer"); else -> names("Writer", "Composer", "Lyricist") }
                if (w.isEmpty()) "I couldn't find who wrote $t." else "$t was ${if (role == CreditRole.COMPOSER) "composed" else "written"} by ${join(w)}."
            }
            CreditRole.ENGINEER, CreditRole.MIX -> {
                val parts = listOfNotNull(names("Mixing").takeIf { it.isNotEmpty() }?.let { "mixed by ${join(it)}" },
                    names("Mastering").takeIf { it.isNotEmpty() }?.let { "mastered by ${join(it)}" },
                    names("Engineer").takeIf { it.isNotEmpty() }?.let { "engineered by ${join(it)}" })
                if (parts.isEmpty()) "I couldn't find the engineers on $t." else "$t was ${join(parts)}."
            }
            CreditRole.INSTRUMENT -> {
                val key = c.roles.keys.firstOrNull { k -> instrument != null && (k.contains(instrument, true) || instrument.contains(k, true)) }
                if (key == null) "I couldn't find who plays ${instrument ?: "that"} on $t." else "${join(c.roles[key]!!)} ${if (c.roles[key]!!.size == 1) "plays" else "play"} ${key.lowercase(Locale.US)} on $t."
            }
            CreditRole.ALBUM -> s.album?.let { "$t is on ${s.artist}'s album $it" + (s.release?.take(4)?.let { y -> ", from $y" } ?: "") + "." } ?: "I couldn't find the album for $t."
            CreditRole.LABEL -> s.label?.let { "$t came out on $it." } ?: "I couldn't find the label for $t."
            CreditRole.RELEASE -> s.release?.let { "$t came out ${prettyDate(it)}." } ?: "I couldn't find when $t came out."
            CreditRole.GENRE -> if (s.genres.isEmpty()) "I couldn't find a genre for $t." else "$t is ${join(s.genres.map { it.lowercase(Locale.US) }.take(2))}."
            CreditRole.ALL -> {
                val prod = names("Producer")
                val writers = names("Writer", "Composer", "Lyricist")
                buildString {
                    append("$t by ${s.artist}")
                    s.album?.let { append(", from $it") }
                    append(".")
                    if (prod.isNotEmpty()) append(" Produced by ${join(prod.take(3))}.")
                    if (writers.isNotEmpty()) append(" Written by ${join(writers.take(4))}.")
                }
            }
        }
    }

    private fun prettyDate(d: String): String = runCatching {
        val dt = java.time.LocalDate.parse(d.take(10))
        "on " + dt.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + " ${dt.dayOfMonth}, ${dt.year}"
    }.getOrElse { "in ${d.take(4)}" }

    fun join(l: List<String>): String = when (l.size) {
        0 -> ""; 1 -> l[0]; 2 -> "${l[0]} and ${l[1]}"; else -> l.dropLast(1).joinToString(", ") + ", and " + l.last()
    }
}
