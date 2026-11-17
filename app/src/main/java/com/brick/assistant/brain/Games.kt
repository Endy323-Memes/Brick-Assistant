package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.log10

/** What's being asked about one game. */
enum class GameWant { OVERVIEW, GENRE, TAGS, STYLE, LANGUAGES, INPUT, VR, OS, DEVELOPER, PUBLISHER, PRICE, REVIEWS }

/** A question about one game ("is hades on mac", "does elden ring have controller support"); [detail] is the asked language, system, input or tag. */
data class GameAsk(val title: String, val want: GameWant, val said: String, val detail: String? = null)

/** Finding games by what they're like ("cozy pixel art games on mac under $20", "vr only horror games"). */
data class GameSearch(
    val tags: List<Pair<String, Int>> = emptyList(),
    /** Steam's VR filter: 401 VR only, 402 any VR support. */
    val vr: Int? = null,
    val os: String? = null,
    val deck: Boolean = false,
    val controller: Boolean = false,
    /** Shown name and Steam's code ("Spanish" to "spanish"). */
    val lang: Pair<String, String>? = null,
    val maxPrice: Int? = null,
    val free: Boolean = false,
    val newest: Boolean = false,
    val said: String = "",
) {
    val isEmpty get() = tags.isEmpty() && vr == null && os == null && !deck && !controller && lang == null && maxPrice == null && !free

    /** "VR-only horror games on Mac under $20". */
    fun describe(): String {
        val words = buildList {
            if (free) add("free")
            if (vr == 401) add("VR-only") else if (vr == 402) add("VR")
            // Moods first, then looks, then kinds: "cozy pixel art farming games".
            val shown = tags.map { it.first }.sortedBy { t -> when (t) { "Cozy", "Relaxing", "Cute", "Dark", "Funny", "Comedy", "Difficult", "Short", "Atmospheric", "Colorful" -> 0; in setOf("Pixel Graphics", "Hand-drawn", "Anime", "Cartoony", "Low Poly", "Retro", "2D", "3D", "Realistic", "Stylized", "Isometric") -> 1; else -> 2 } }
            addAll(shown.map { t ->
                when (t) { "Pixel Graphics" -> "pixel art"; "Singleplayer" -> "single-player"; "Co-op" -> "co-op"; "Comedy" -> "funny"; else ->
                    if (Regex("^(?:RPG|JRPG|CRPG|FPS|RTS|MMORPG|MOBA|PvP|2D|3D|2\\.5D|VR)$").matches(t)) t else t.lowercase(Locale.US) }
            })
        }
        return buildString {
            append(if (words.isEmpty()) "Games" else words.joinToString(" ").replaceFirstChar { it.uppercase(Locale.US) } + " games")
            os?.let { append(" on ${Games.osName(it)}") }
            if (deck) append(" verified for Steam Deck")
            if (controller) append(" with controller support")
            lang?.let { append(" in ${it.first}") }
            maxPrice?.let { append(" under $$it") }
        }
    }
}

object Games {
    private val us = Locale.US

    // ================================================================== understanding the ask

    private const val T = "(.+?)"
    private const val OSW = "(macs?|macbooks?|macos|mac os|os x|linux|ubuntu|windows(?: 1[01])?|pc|chromebooks?)"
    private const val INW = "(controllers?|gamepads?|game ?pads?|joysticks?|xbox controllers?|ps[45] controllers?|playstation controllers?|dualsense|dualshock|keyboard(?: and mouse)?|mouse(?: and keyboard)?|kbm|m&k|touch ?screens?|touch(?: controls)?|motion controls|steam controller|racing wheels?|wheels?|flight sticks?|hotas)"
    private const val STYLEW = "(pixel art|pixel graphics|pixelated|pixel|2d|3d|2\\.5d|anime|cartoon|cartoony|cartoonish|hand ?drawn|hand-drawn|realistic|photorealistic|stylized|stylised|cel ?shaded|cel-shaded|low ?poly|voxel|retro|isometric|minimalist|first[- ]person|third[- ]person|top[- ]down|side[- ]scroll(?:ing|er))"
    private const val TAGW = "(rpg|jrpg|action rpg|roguelike|rogue ?like|roguelite|rogue ?lite|metroidvania|souls ?like|soulslike|platformer|shooter|fps|horror|survival horror|survival|open world|sandbox|strategy|rts|puzzle|racing|fighting|sports|simulation|sim|city builder|mmo|mmorpg|battle royale|moba|visual novel|dating sim|deck ?builder|deckbuilder|card game|tower defense|rhythm|stealth|hack and slash|action|adventure|indie|co-?op|multiplayer|single ?player|cozy|story rich|turn based|pvp|split ?screen|local co-?op|online co-?op|crossplay|cross[- ]platform|free to play|f2p|early access)"

    /** Language words to Steam's codes. */
    private val LANGS = linkedMapOf(
        "latin american spanish|latam spanish|mexican spanish|spanish latam" to ("Spanish (Latin America)" to "latam"),
        "brazilian portuguese|portuguese brazil|brazilian" to ("Portuguese (Brazil)" to "brazilian"),
        "simplified chinese|mandarin" to ("Simplified Chinese" to "schinese"), "traditional chinese|cantonese" to ("Traditional Chinese" to "tchinese"),
        "chinese" to ("Chinese" to "schinese"), "english" to ("English" to "english"), "spanish|espanol|español" to ("Spanish" to "spanish"),
        "french" to ("French" to "french"), "german" to ("German" to "german"), "italian" to ("Italian" to "italian"), "portuguese" to ("Portuguese" to "portuguese"),
        "russian" to ("Russian" to "russian"), "japanese" to ("Japanese" to "japanese"), "korean" to ("Korean" to "koreana"), "polish" to ("Polish" to "polish"),
        "turkish" to ("Turkish" to "turkish"), "ukrainian" to ("Ukrainian" to "ukrainian"), "dutch" to ("Dutch" to "dutch"), "swedish" to ("Swedish" to "swedish"),
        "norwegian" to ("Norwegian" to "norwegian"), "danish" to ("Danish" to "danish"), "finnish" to ("Finnish" to "finnish"), "czech" to ("Czech" to "czech"),
        "hungarian" to ("Hungarian" to "hungarian"), "romanian" to ("Romanian" to "romanian"), "thai" to ("Thai" to "thai"), "vietnamese" to ("Vietnamese" to "vietnamese"),
        "arabic" to ("Arabic" to "arabic"), "greek" to ("Greek" to "greek"), "bulgarian" to ("Bulgarian" to "bulgarian"), "indonesian" to ("Indonesian" to "indonesian"),
    )
    private val LANGW = "(" + LANGS.keys.joinToString("|") + ")"

    private fun rx(p: String) = Regex(p)

    private val FORMS: List<Pair<Regex, GameWant>> = listOf(
        // VR
        rx("^(?:is|are) $T (?:a )?(?:vr|virtual reality)(?: only| game| exclusive| compatible| supported| playable)?$") to GameWant.VR,
        rx("^(?:does|do|can|will) $T (?:have|support|work (?:in|with)|run in|play in|offer) (?:a |any )?(?:vr|virtual reality)(?: mode| support| version)?$") to GameWant.VR,
        rx("^can (?:i|you|we) play $T (?:in|with|on) (?:a |my )?(?:vr|virtual reality|vr headset|headset|quest(?: [23])?|meta quest(?: [23])?|valve index|index|vive|oculus|rift)(?: headset)?$") to GameWant.VR,
        rx("^$T (?:vr|vr support|vr mode|in vr|vr version)$") to GameWant.VR,
        rx("^(?:is there|does it have) (?:a )?vr (?:mode|version) (?:of|for|in) $T$") to GameWant.VR,
        // Operating systems and Steam Deck
        rx("^(?:is|are) $T (?:on|for|available (?:on|for)|out on|playable on|compatible with|supported on|coming to) (?:the |a |my )?$OSW$") to GameWant.OS,
        rx("^(?:does|do|will|can) $T (?:run|work|play|launch) (?:on|with) (?:the |a |my )?$OSW$") to GameWant.OS,
        rx("^can (?:i|you|we) (?:play|run|get) $T on (?:the |a |my |an? )?$OSW$") to GameWant.OS,
        rx("^(?:what|which) (?:os|operating systems?|systems?|computers?) (?:is|does|can) $T (?:on|run on|support|work on|available on|play on)$") to GameWant.OS,
        rx("^(?:what )?(?:os|operating systems?) (?:does|is|for) $T(?: support| run on| on)?$") to GameWant.OS,
        rx("^is $T (?:steam )?deck (?:verified|playable|compatible|supported)$") to GameWant.OS,
        rx("^(?:does|do|will) $T (?:run|work|play) (?:well )?on (?:the |a |my )?(steam ?deck)$") to GameWant.OS,
        rx("^$T (?:steam )?deck (?:verified|support|compatibility|rating|status)$") to GameWant.OS,
        rx("^$T (?:mac|linux|steam deck) support$") to GameWant.OS,
        // Input
        rx("^(?:does|do|can|will) $T (?:have|support|work with|use|take|play with|allow|accept) (?:full |a |an |any |the )?$INW(?: support| controls?| input)?$") to GameWant.INPUT,
        rx("^can (?:i|you|we) play $T (?:with|using|on) (?:a |an |my |the )?$INW$") to GameWant.INPUT,
        rx("^(?:is|are) $T (?:playable|good|better|fun) (?:with|on) (?:a |an |my )?$INW$") to GameWant.INPUT,
        rx("^$T $INW support$") to GameWant.INPUT,
        rx("^$T (?:controls|control scheme|controller support|input methods?|inputs|input support)$") to GameWant.INPUT,
        rx("^(?:what|which) (?:controls|controllers|input methods?|inputs) (?:does|do|can) $T (?:have|support|use|take)$") to GameWant.INPUT,
        // Languages
        rx("^(?:is|are) $T (?:available |playable |out )?in $LANGW(?: language)?$") to GameWant.LANGUAGES,
        rx("^(?:does|do|can|will) $T (?:have|support|come in|offer|include|get) $LANGW(?: (?:language|subtitles|subs|audio|voices?|voice acting|voice over|dub|dubbing|text|translation))?$") to GameWant.LANGUAGES,
        rx("^can (?:i|you|we) play $T in $LANGW$") to GameWant.LANGUAGES,
        rx("^(?:what|which|how many) languages? (?:is|are|does|do|can) $T (?:in|support|available in|have|come in|offer|playable in|translated (?:in|into))$") to GameWant.LANGUAGES,
        rx("^(?:what|which|how many) languages? (?:is|are) (?:available |supported )?(?:in|for) $T$") to GameWant.LANGUAGES,
        rx("^$T (?:languages|language support|supported languages|translations|language options)$") to GameWant.LANGUAGES,
        // Art style
        rx("^(?:what(?:'s| is)?|whats) (?:the )?(?:art ?style|graphic(?:s|al)? style|visual style|art direction|style|graphics) (?:of|in|for) $T$") to GameWant.STYLE,
        rx("^(?:what|how) does $T look(?: like)?$") to GameWant.STYLE,
        rx("^$T (?:art ?style|graphics style|graphic style|visual style|graphics|art direction|visuals)$") to GameWant.STYLE,
        rx("^what (?:art ?style|style|kind of graphics|type of graphics|kind of art) (?:is|does) $T(?: have| use| in)?$") to GameWant.STYLE,
        rx("^(?:is|are) $T (?:a |an |in )?$STYLEW(?: game| graphics| style| art)?$") to GameWant.STYLE,
        rx("^(?:is|does) $T (?:have|use) $STYLEW (?:graphics|art|visuals)$") to GameWant.STYLE,
        // Genre and tags
        rx("^(?:what|which) (?:genre|genres) (?:is|are) $T$") to GameWant.GENRE,
        rx("^what (?:kind|type|sort|genre) of (?:a )?(?:video ?)?game is $T$") to GameWant.GENRE,
        rx("^$T (?:game )?(?:genre|genres)$") to GameWant.GENRE,
        rx("^(?:genre|genres) (?:of|for) $T$") to GameWant.GENRE,
        rx("^(?:is|are) $T (?:a |an )?$TAGW(?: game)?$") to GameWant.TAGS,
        rx("^(?:does|do|can) $T (?:have|support|offer) $TAGW(?: mode| play)?$") to GameWant.TAGS,
        rx("^$T (?:steam )?tags$") to GameWant.TAGS,
        rx("^(?:what|which) (?:steam )?tags (?:does|do) $T have$") to GameWant.TAGS,
        rx("^(?:steam )?tags (?:for|of|on) $T$") to GameWant.TAGS,
        rx("^how (?:do|would|does) (?:people|players|you|steam) (?:tag|categorize|classify) $T$") to GameWant.TAGS,
        // Developer and publisher
        rx("^who (?:developed|develops|is developing|programmed|coded) (?:the (?:video ?)?game )?$T$") to GameWant.DEVELOPER,
        rx("^(?:what|which) (?:studio|developer|game studio|dev|team) (?:made|makes|developed|created|built|is behind|was behind|works on|is making) $T$") to GameWant.DEVELOPER,
        rx("^who (?:made|created|makes) the (?:video ?)?game $T$") to GameWant.DEVELOPER,
        rx("^who (?:made|created) $T (?:the )?(?:video ?)?game$") to GameWant.DEVELOPER,
        rx("^$T (?:game )?(?:developers?|devs?|studios?|dev team)$") to GameWant.DEVELOPER,
        rx("^(?:developers?|studio|devs?) (?:of|for|behind) $T$") to GameWant.DEVELOPER,
        rx("^who is (?:the )?(?:developer|studio|dev|team) (?:of|for|behind) $T$") to GameWant.DEVELOPER,
        rx("^who (?:published|publishes|is publishing) (?:the (?:video ?)?game )?$T$") to GameWant.PUBLISHER,
        rx("^(?:what|which) (?:company|publisher) (?:published|publishes|puts out|released) $T$") to GameWant.PUBLISHER,
        rx("^$T (?:game )?publishers?$") to GameWant.PUBLISHER,
        rx("^(?:publisher|publishers) (?:of|for) $T$") to GameWant.PUBLISHER,
        rx("^who is (?:the )?publisher (?:of|for) $T$") to GameWant.PUBLISHER,
        // Reviews and prices across stores
        rx("^$T (?:steam|user|player|players|gamer) (?:reviews?|rating|ratings|score)$") to GameWant.REVIEWS,
        rx("^(?:what are|how are|show me|read) (?:the )?(?:steam |user |player )reviews? (?:for|of|on) $T$") to GameWant.REVIEWS,
        rx("^(?:what do|how do) (?:players|gamers|people on steam|steam users|people) (?:think of|rate|feel about) (?:the game )?$T$") to GameWant.REVIEWS,
        rx("^how (?:is|was) $T (?:rated|reviewed) on steam$") to GameWant.REVIEWS,
        rx("^$T (?:prices?|price check) (?:on|across|at|in) (?:all |every |different |other |the )?(?:stores|shops|sites|platforms|pc stores)$") to GameWant.PRICE,
        rx("^compare (?:the )?(?:prices?|deals) (?:for|of|on) $T$") to GameWant.PRICE,
        rx("^(?:where is|which store has|where can i get|where's) $T (?:the )?cheapest$") to GameWant.PRICE,
        rx("^(?:what is|what's|how much is) (?:the )?cheapest (?:price )?(?:for|of) $T$") to GameWant.PRICE,
        // The whole card
        rx("^(?:tell me about|info on|information (?:on|about)|details (?:on|about|for)|look up|lookup|search (?:steam )?for|show me|pull up) (?:the )?(?:video ?)?game $T$") to GameWant.OVERVIEW,
        rx("^$T (?:video ?)?game (?:info|details|information|overview|stats|specs)$") to GameWant.OVERVIEW,
        rx("^$T steam (?:page|info|listing)$") to GameWant.OVERVIEW,
        rx("^steam (?:page|info|listing) (?:for|of) $T$") to GameWant.OVERVIEW,
        rx("^(?:what is|what's|whats) the (?:video ?)?game $T$") to GameWant.OVERVIEW,
        rx("^(?:look up|search|find) $T on steam$") to GameWant.OVERVIEW,
    )

    private val skip = ThreadLocal<Boolean>()

    /** Runs [block] with game questions turned off (a game Steam doesn't have falls back to Brick's other answers). */
    fun <R> without(block: () -> R): R {
        skip.set(true)
        try { return block() } finally { skip.set(false) }
    }

    private fun normalize(raw: String) = raw.lowercase(us).replace('’', '\'').replace(Regex("[?!]+"), " ").replace(Regex("(?<!\\d)[.,]|[.,](?!\\d)"), " ")
        .replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(?:(?:hey |ok |okay |yo )?brick )"), "")
        .replace(Regex("^(?:can you |could you |would you |will you |please |do you know |i want to know |i'd like to know |i wanna know |tell me (?!about )|quick question )+"), "")
        .replace(Regex("^(?:if|whether) (?=is |does |do |can |are )"), "")
        // "if hades is on linux" -> "is hades on linux".
        .replace(Regex("^(?:if|whether) (.+?) (is|are) "), "$2 $1 ")
        .trim()

    /** A question about one game ("is hades on mac"); null when it isn't one. */
    fun parseInfo(raw: String): Request.GameInfo? {
        if (skip.get() == true) return null
        val t = normalize(raw)
        if (t.isEmpty() || t.length > 160) return null
        for ((form, want) in FORMS) {
            val m = form.matchEntire(t) ?: continue
            val title = cleanTitle(m.groupValues[1]) ?: continue
            val detail = m.groupValues.drop(2).firstOrNull { it.isNotBlank() }
            return Request.GameInfo(GameAsk(title, want, raw.trim(), detail))
        }
        return null
    }

    /** A search for games by what they're like ("vr only horror games"); null when it isn't one. */
    fun parseFind(raw: String): Request.GameFind? {
        if (skip.get() == true) return null
        val t = normalize(raw)
        if (t.isEmpty() || t.length > 160) return null
        return find(t)?.let { Request.GameFind(it) }
    }

    fun parse(raw: String): Request? = parseInfo(raw) ?: parseFind(raw)

    private fun cleanTitle(raw: String): String? {
        val s = raw.trim().replace(Regex("^(?:the (?:video ?)?game |video ?game |game )"), "")
            .replace(Regex("(?: the)? (?:video ?)?game$|'s$| on steam$| on pc$"), "").trim()
        if (s.length < 2 || s.split(' ').size > 8) return null
        if (Regex("^(?:it|this|that|they|he|she|you|i|we|my \\w+|your \\w+|there|this game|that game|the game|games?)$").matches(s)) return null
        if (Regex("\\b(?:book|novel|novels|movie|film|tv show|show|series|album|song|author)\\b").containsMatchIn(s)) return null
        // "roguelike games with": a search, not a title; "is it": a question, not a title.
        if (Regex("\\bgames\\b|^(?:is|are|does|do|can|could|will|what|which|who|how|why|where|when|should|any|some|best|top|good)\\b").containsMatchIn(s)) return null
        return s
    }

    // ------------------------------------------------------------------ finding games by what they're like

    private val CONSOLES = Regex("\\b(?:switch|nintendo|ps[1-5]|playstation|xbox|phones?|iphone|ipad|ios|android|mobile|wii|game ?boy|3ds|ds)\\b")
    private val ASKING = Regex("\\b(?:find|show|recommend|suggest|list|search(?: for)?|look(?:ing)? for|any|some|what are|give me|i want|i need|i'm looking|good|best|top|great|cool|fun|new|newest|latest|cheap|free|popular|underrated|hidden gems?)\\b")

    /** Words people use that aren't Steam's tag names. */
    private val TAG_WORDS = listOf(
        "rogue ?likes?|rogue-likes?" to "Roguelike", "rogue ?lites?|rogue-lites?" to "Roguelite", "souls ?likes?|soulslikes?" to "Souls-like",
        "metroidvanias?" to "Metroidvania", "pixel art|pixel|pixelated|8[- ]bit|16[- ]bit" to "Pixel Graphics", "hand ?drawn" to "Hand-drawn",
        "cartoon(?:y|ish)?" to "Cartoony", "low ?poly" to "Low Poly", "cel ?shaded" to "Cel-Shading", "co-?op|cooperative|coop" to "Co-op",
        "online co-?op" to "Online Co-Op", "local co-?op|couch co-?op" to "Local Co-Op", "split ?screen" to "Split Screen",
        "single ?player|solo" to "Singleplayer", "shooters?" to "Shooter", "fps|first[- ]person shooters?" to "FPS", "scary|spooky|horror" to "Horror",
        "farming|farm" to "Farming Sim", "city ?builders?|city building" to "City Builder", "deck ?builders?|deck ?building|deckbuilders?" to "Deckbuilding",
        "card games?" to "Card Game", "turn[- ]based" to "Turn-Based", "rts" to "RTS", "jrpgs?" to "JRPG", "rpgs?|role[- ]playing" to "RPG",
        "walking sims?|walking simulators?" to "Walking Simulator", "idle|idlers?" to "Idler", "clickers?" to "Clicker", "sci[- ]?fi|science fiction" to "Sci-fi",
        "space" to "Space", "funny|comedy|hilarious" to "Comedy", "relaxing|chill|calm" to "Relaxing", "cozy|cosy|wholesome" to "Cozy",
        "open[- ]world" to "Open World", "survival" to "Survival", "crafting" to "Crafting", "sandbox" to "Sandbox", "platformers?" to "Platformer",
        "puzzles?|puzzlers?" to "Puzzle", "racing|racers?|driving" to "Racing", "fighting games?|fighters" to "Fighting", "strategy" to "Strategy",
        "story[- ]rich|story driven|story based|narrative" to "Story Rich", "visual novels?" to "Visual Novel", "dating sims?" to "Dating Sim",
        "tower defen[cs]e" to "Tower Defense", "rhythm|music games?" to "Rhythm", "stealth" to "Stealth", "hack (?:and|n|&) slash" to "Hack and Slash",
        "mmos?|mmorpgs?" to "MMORPG", "battle royales?" to "Battle Royale", "mobas?" to "MOBA", "zombies?" to "Zombies", "pirates?" to "Pirates",
        "medieval" to "Medieval", "cyberpunk" to "Cyberpunk", "post[- ]apocalyptic" to "Post-apocalyptic", "anime" to "Anime", "realistic" to "Realistic",
        "stylized|stylised" to "Stylized", "retro" to "Retro", "2d" to "2D", "3d" to "3D", "isometric" to "Isometric", "first[- ]person" to "First-Person",
        "third[- ]person" to "Third Person", "top[- ]down" to "Top-Down", "side[- ]scroll(?:ing|ers?)" to "Side Scroller", "multiplayer|multi[- ]player" to "Multiplayer",
        "pvp" to "PvP", "short" to "Short", "difficult|hard|challenging" to "Difficult", "choices matter|choice based" to "Choices Matter",
        "detective|mystery|mysteries" to "Mystery", "simulation|simulators?|sims?" to "Simulation", "management" to "Management", "sports" to "Sports",
        "adventure" to "Adventure", "action" to "Action", "fantasy" to "Fantasy", "atmospheric" to "Atmospheric", "exploration|exploring" to "Exploration",
        "base ?building" to "Base Building", "physics" to "Physics", "dark" to "Dark", "cute" to "Cute", "colou?rful" to "Colorful", "indie" to "Indie",
        "lovecraftian|cosmic horror" to "Lovecraftian", "psychological horror" to "Psychological Horror", "survival horror" to "Survival Horror",
        "sandbox building|building" to "Building", "trading" to "Trading", "fishing" to "Fishing", "cooking" to "Cooking", "flight|flying" to "Flight",
        "soccer|football" to "Soccer", "basketball" to "Basketball", "golf" to "Golf", "skateboarding|skating" to "Skateboarding", "boxing" to "Boxing",
        "dungeon crawlers?" to "Dungeon Crawler", "looters?|looter shooters?" to "Looter Shooter", "bullet hell" to "Bullet Hell", "arcade" to "Arcade",
        "educational" to "Education", "military|war" to "War", "historical" to "Historical", "western|cowboy" to "Western", "ninjas?" to "Ninja",
        "dragons?" to "Dragons", "robots?|mechs?" to "Robots", "aliens?" to "Aliens", "vampires?" to "Vampire", "magic" to "Magic", "cats?" to "Cats",
        "dogs?" to "Dog", "ocean|underwater" to "Underwater", "noir" to "Noir", "gore|gory" to "Gore", "loot" to "Loot", "crpg" to "CRPG",
        "party games?" to "Party Game", "trivia" to "Trivia", "word games?" to "Word Game", "hidden object" to "Hidden Object", "escape rooms?" to "Escape Room",
        "point (?:and|&|n) click" to "Point & Click", "life sims?|life simulation" to "Life Sim", "colony sims?" to "Colony Sim", "automation|factory" to "Automation",
    )
    private val TAG_IDS: Map<String, Int> by lazy { GameTags.ALL.associate { (n, id) -> n.lowercase(us) to id } }

    private fun tagId(name: String) = TAG_IDS[name.lowercase(us)]

    /** "cozy pixel art games on mac under $20": the filters, or null when it isn't a game search. */
    fun find(t: String): GameSearch? {
        if (!Regex("\\b(?:games?|titles)\\b").containsMatchIn(t) || CONSOLES.containsMatchIn(t)) return null
        if (Regex("^(?:play|open|launch|start|close|install|uninstall|download|update|stream|record|watch)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(?:tonight|today|score|scores|schedule|playoffs?|vs|versus|board ?games?|tabletop)\\b").containsMatchIn(t)) return null
        // Years ("since 2020", "90s games") are the best-of lists' job.
        if (Regex("\\b(?:19|20)\\d{2}s?\\b|\\b[0-9]0s\\b|\\b(?:nineties|eighties|two thousands)\\b").containsMatchIn(t)) return null
        // Another store's list ("best epic games store games") is the deals and best-of lists' job.
        if (Regex("\\b(?:epic(?: games)?(?: store)?|gog|humble|itch(?:\\.io)?|game ?pass|ea app|ubisoft|battle\\.?net|origin)\\b").containsMatchIn(t)) return null
        var rest = " $t "
        fun take(p: String): MatchResult? = Regex("(?<![a-z0-9])(?:$p)(?![a-z0-9])").find(rest)?.also { m -> rest = rest.replaceRange(m.range, " ") }
        val vr = when { take("vr[- ]only|only (?:in )?vr|vr exclusives?|vr[- ]exclusive") != null -> 401; take("vr|virtual reality|vr headsets?|(?:meta )?quest(?: [23])?|valve index|vive|oculus") != null -> 402; else -> null }
        val deck = take("steam ?deck(?: verified)?|deck verified|steam ?os") != null
        val os = when { take("macs?|macbooks?|macos|mac os|os x|apple silicon") != null -> "mac"; take("linux|ubuntu") != null -> "linux"; take("windows") != null -> "win"; else -> null }
        val controller = take("(?:with |full )?(?:controller|gamepad|game ?pad) ?(?:support|supported|friendly)?|(?:on|with) (?:a )?controllers?") != null
        var lang: Pair<String, String>? = null
        for ((words, v) in LANGS) if (take("in (?:$words)|(?:$words) (?:language|subtitles|audio|voice acting|dub|translation|speaking)") != null) { lang = v; break }
        val free = take("free[- ]to[- ]play|f2p|free") != null
        val price = take("(?:under|less than|below|cheaper than|for under|up to|max|at most|no more than) \\$?(\\d{1,3})(?: dollars| bucks)?|\\$(\\d{1,3}) or (?:less|under)")
        val maxPrice = price?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()?.coerceIn(1, 200)
            ?: if (take("cheap|budget|inexpensive|affordable") != null) 15 else null
        val newest = take("new|newest|latest|recent|recently released|just released|brand new|upcoming") != null
        val tags = ArrayList<Pair<String, Int>>()
        for ((words, name) in TAG_WORDS.sortedByDescending { it.first.length }) {
            if (tags.size >= 3) break
            if (take(words) == null) continue
            val id = tagId(name) ?: continue
            if (tags.none { it.second == id }) tags += name to id
        }
        // Steam's own tag names the list above doesn't cover ("Lovecraftian", "Mining").
        if (tags.size < 3) for ((name, id) in GameTags.ALL.sortedByDescending { it.first.length }) {
            if (tags.size >= 3) break
            if (name.length < 4 || name in setOf("Controller", "VR", "Free to Play", "Game", "Games", "Steam Machine", "Software", "Mac", "Linux")) continue
            if (take(Regex.escape(name.lowercase(us))) != null && tags.none { it.second == id }) tags += name to id
        }
        val s = GameSearch(tags, vr, os, deck, controller, lang, maxPrice, free, newest, t)
        // Price or a system alone ("cheap games", "best games on steam deck") stay with the deals and best-of lists.
        if (s.tags.isEmpty() && vr == null && !controller && lang == null) return null
        if (!ASKING.containsMatchIn(t) && s.tags.size + listOfNotNull(vr, os, lang, maxPrice).size + (if (deck) 1 else 0) + (if (controller) 1 else 0) < 2) return null
        return s
    }

    fun osName(code: String) = when (code) { "mac" -> "Mac"; "linux" -> "Linux"; "win" -> "Windows"; else -> code }

    /** Steam's search, filtered: the page to open and the address the app reads. */
    fun searchUrl(s: GameSearch, results: Boolean): String {
        val p = buildList {
            if (s.tags.isNotEmpty()) add("tags=" + s.tags.joinToString("%2C") { it.second.toString() })
            s.vr?.let { add("vrsupport=$it") }
            s.os?.let { add("os=$it") }
            if (s.deck) add("deck_compatibility=3")
            if (s.controller) add("controllersupport=18")
            s.lang?.let { add("supportedlang=${it.second}") }
            if (s.free) add("maxprice=free") else s.maxPrice?.let { add("maxprice=$it") }
            add("category1=998")
            add("sort_by=" + if (s.newest) "Released_DESC" else "Reviews_DESC")
        }.joinToString("&")
        return if (results) "https://store.steampowered.com/search/results/?$p&cc=us&l=english&infinite=1&count=50" else "https://store.steampowered.com/search/?$p"
    }

    class Hit(val id: String, val name: String, val released: String?, val price: String?, val percent: Int?, val reviews: Int?, val image: String?)

    /** Steam's matches for a search, the best-loved first (a 98% game with 300,000 reviews beats a 100% one with 12). */
    suspend fun search(s: GameSearch): List<Hit> = withContext(Dispatchers.IO) {
        val html = Http.getJsonCached(searchUrl(s, results = true))?.optString("results_html").orEmpty()
        val hits = parseResults(html)
        if (s.newest) hits else hits.sortedByDescending { h -> (h.percent ?: 50) / 100.0 * log10((h.reviews ?: 1) + 10.0) }
    }

    fun parseResults(html: String): List<Hit> = html.split("<a href=").drop(1).mapNotNull { block ->
        val id = Regex("data-ds-appid=\"(\\d+)").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        val name = Regex("class=\"title\">([^<]+)").find(block)?.groupValues?.get(1)?.let { unescape(it) } ?: return@mapNotNull null
        val released = Regex("search_released[^>]*>\\s*([^<]*?)\\s*<").find(block)?.groupValues?.get(1)?.ifBlank { null }
        val price = Regex("discount_final_price[^>]*>([^<]+)<").find(block)?.groupValues?.get(1)?.trim()
            ?: if (block.contains("Free", ignoreCase = false)) "Free" else null
        val review = Regex("data-tooltip-html=\"[^\"]*?(\\d+)% of the ([\\d,]+) user reviews").find(block)
        Hit(id, name, released, price, review?.groupValues?.get(1)?.toIntOrNull(), review?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull(),
            "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg")
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

    suspend fun findCard(s: GameSearch): LookupResult? {
        val hits = search(s).take(10)
        if (hits.isEmpty()) return null
        val what = s.describe()
        val names = hits.take(3).map { it.name }
        val answer = "Top picks for ${(if (what.getOrNull(1)?.isUpperCase() == true) what else what.replaceFirstChar { it.lowercase(us) })}: " + when (names.size) { 1 -> names[0]; 2 -> "${names[0]} and ${names[1]}"; else -> "${names[0]}, ${names[1]} and ${names[2]}" } + "."
        val facts = buildList {
            if (s.tags.isNotEmpty()) add("TAGS" to s.tags.joinToString(", ") { it.first })
            s.vr?.let { add("VR" to if (it == 401) "VR only" else "VR supported") }
            s.os?.let { add("OS" to osName(it)) }
            if (s.deck) add("DECK" to "Verified")
            if (s.controller) add("INPUT" to "Full controller support")
            s.lang?.let { add("LANG" to it.first) }
            if (s.free) add("PRICE" to "Free") else s.maxPrice?.let { add("PRICE" to "Under $$it") }
            add("SORT" to if (s.newest) "Newest first" else "Best reviewed first")
        }
        val list = hits.mapIndexed { i, h ->
            val bits = listOfNotNull(h.price, h.percent?.let { p -> "$p% positive" + (h.reviews?.let { " of ${compact(it)}" } ?: "") }, h.released?.let { Regex("\\d{4}").find(it)?.value })
            "${i + 1}. ${h.name}" + if (bits.isEmpty()) "" else " - " + bits.joinToString(", ")
        }
        return LookupResult(what, "Steam search", null, "", hits.first().image, searchUrl(s, results = false), facts, answer,
            sources = listOf("Steam"), list = list, listTitle = "TOP MATCHES", heading = "GAMES",
            links = hits.take(3).map { it.name to "https://store.steampowered.com/app/${it.id}" })
    }

    private fun compact(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(us, n / 1e6); n >= 10_000 -> "${n / 1000}K"; n >= 1000 -> "%.1fK".format(us, n / 1e3); else -> "$n" }

    // ================================================================== one game's details

    class Lang(val name: String, val audio: Boolean)

    class Info(
        val id: String, val name: String, val image: String?, val url: String, val about: String?, val developers: List<String>, val publishers: List<String>,
        val released: String?, val genres: List<String>, val categories: List<String>, val windows: Boolean, val mac: Boolean, val linux: Boolean,
        val languages: List<Lang>, val steamPrice: Price?, val metacritic: Int?, val tags: List<String>, val positive: Int?, val negative: Int?,
        /** Steam Deck: 3 Verified, 2 Playable, 1 Unsupported, 0 or null unknown. */
        val deck: Int?, val otherPrices: List<Price>, val controller: String?, val comingSoon: Boolean,
    ) {
        val vrOnly get() = categories.any { it.equals("VR Only", true) }
        val vrMode get() = !vrOnly && categories.any { it.equals("VR Supported", true) || it.equals("VR Support", true) }
        val percent get() = if (positive != null && negative != null && positive + negative > 0) positive * 100 / (positive + negative) else null
        val reviewCount get() = if (positive != null && negative != null) positive + negative else null
        val prices get() = listOfNotNull(steamPrice) + otherPrices
        val cheapest get() = prices.minByOrNull { it.now }
    }

    private fun norm(s: String) = s.lowercase(us).replace(Regex("[™®©'’]"), "").replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\b(?:the)\\b"), " ").replace(Regex("\\s+"), " ").trim()

    /** Steam's app for [title]: the exact name, or a match that starts with every word of it. */
    private fun appFor(title: String, loose: Boolean): Pair<String, String>? {
        val items = Http.getJsonCached("https://store.steampowered.com/api/storesearch/?term=${Http.enc(title)}&cc=us&l=en")?.optJSONArray("items") ?: return null
        val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.filter { it.optString("type", "app") == "app" }
        val want = norm(title)
        val hit = list.firstOrNull { norm(it.optString("name")) == want }
            ?: list.takeIf { loose }?.firstOrNull { norm(it.optString("name")).let { n -> n.startsWith("$want ") && !Regex("\\b(?:soundtrack|dlc|demo|pack|edition upgrade|artbook|ost)\\b").containsMatchIn(n) } && want.length >= 4 }
            ?: return null
        return hit.optLong("id").toString() to hit.optString("name")
    }

    /** Everything Brick shows about a game, or null when Steam doesn't have it. */
    suspend fun info(title: String, loose: Boolean = false): Info? = withContext(Dispatchers.IO) {
        val (id, found) = appFor(title, loose) ?: return@withContext null
        coroutineScope {
            val spyAhead = async { runCatching { Http.getJsonCached("https://steamspy.com/api.php?request=appdetails&appid=$id") }.getOrNull() }
            val deckAhead = async { runCatching { Http.getJsonCached("https://store.steampowered.com/saleaction/ajaxgetdeckappcompatibilityreport?nAppID=$id") }.getOrNull() }
            val storesAhead = async { runCatching { Prices.pcStores(id, found) }.getOrDefault(emptyList()) }
            val d = Http.getJsonCached("https://store.steampowered.com/api/appdetails?appids=$id&cc=us&l=en")?.optJSONObject(id)
                ?.takeIf { it.optBoolean("success") }?.optJSONObject("data") ?: return@coroutineScope null
            fun names(f: String) = d.optJSONArray(f)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
            fun descs(f: String) = d.optJSONArray(f)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("description") } }.orEmpty().distinct()
            val plat = d.optJSONObject("platforms")
            val po = d.optJSONObject("price_overview")
            val steamPrice = when {
                d.optBoolean("is_free") -> Price("Steam", 0.0, url = "https://store.steampowered.com/app/$id")
                po != null -> Price("Steam", po.optInt("final") / 100.0, po.optInt("initial").takeIf { it > 0 }?.div(100.0), "https://store.steampowered.com/app/$id")
                else -> null
            }
            val spy = spyAhead.await()
            val tags = spy?.optJSONObject("tags")?.let { o -> o.keys().asSequence().map { it to o.optInt(it) }.sortedByDescending { it.second }.map { it.first }.toList() }.orEmpty()
            val deck = deckAhead.await()?.optJSONObject("results")?.optInt("resolved_category")
            Info(
                id, d.optString("name", found), d.optString("header_image").ifBlank { null }, "https://store.steampowered.com/app/$id",
                strip(d.optString("short_description")).ifBlank { null }, names("developers"), names("publishers"),
                d.optJSONObject("release_date")?.optString("date")?.ifBlank { null }, descs("genres"), descs("categories"),
                plat?.optBoolean("windows") == true, plat?.optBoolean("mac") == true, plat?.optBoolean("linux") == true,
                languages(d.optString("supported_languages")), steamPrice, d.optJSONObject("metacritic")?.optInt("score")?.takeIf { it > 0 },
                tags, spy?.optInt("positive")?.takeIf { it > 0 }, spy?.optInt("negative")?.takeIf { spy.optInt("positive") > 0 },
                deck, storesAhead.await().filter { it.store != "Steam" }, d.optString("controller_support").ifBlank { null },
                d.optJSONObject("release_date")?.optBoolean("coming_soon") == true,
            )
        }
    }

    private fun strip(h: String) = h.replace(Regex("<[^>]+>"), " ").replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    /** "English<strong>*</strong>, French, ..." -> languages, with full audio marked. */
    fun languages(html: String): List<Lang> {
        val body = html.substringBefore("<br>").ifBlank { html }
        return body.split(',').mapNotNull { part ->
            val audio = part.contains("*")
            val name = strip(part.replace("*", "")).replace(Regex("languages with full audio support", RegexOption.IGNORE_CASE), "").trim()
            if (name.isBlank()) null else Lang(name, audio)
        }
    }

    // ------------------------------------------------------------------ reading the details

    private val STYLE_TAGS = setOf(
        "Pixel Graphics", "Hand-drawn", "Anime", "Cartoony", "Cartoon", "Realistic", "Stylized", "Cel-Shading", "Low Poly", "Voxel", "2D", "3D", "2.5D",
        "Isometric", "Minimalist", "Colorful", "Cute", "Dark", "Retro", "Comic Book", "Psychedelic", "Abstract", "Beautiful", "First-Person", "Third Person",
        "Top-Down", "Side Scroller", "Old School", "Noir", "Surreal", "Lovecraftian", "FMV", "Text-Based", "Gothic",
    )
    private val GENERIC_TAGS = setOf("Indie", "Singleplayer", "Great Soundtrack", "Atmospheric", "Multiplayer", "Free to Play", "Early Access", "Classic", "Masterpiece",
        "Addictive", "Replay Value", "Good Soundtrack", "Memes", "Family Friendly", "Moddable", "Controller", "VR", "Co-op", "Online Co-Op", "Local Co-Op", "PvP",
        "Casual", "Funny", "Difficult", "Short", "Story Rich", "Beautiful", "Cute", "Colorful", "Dark", "2D", "3D")

    fun styles(i: Info) = i.tags.filter { it in STYLE_TAGS }.take(6)
    fun genreTags(i: Info) = i.tags.filter { it !in STYLE_TAGS && it !in GENERIC_TAGS }.take(4)

    fun modes(i: Info) = buildList {
        val c = i.categories.map { it.lowercase(us) }
        if ("single-player" in c) add("Single-player")
        if (c.any { it == "online co-op" }) add("Online co-op")
        if (c.any { it.contains("shared/split screen co-op") || it == "lan co-op" }) add("Local co-op")
        else if ("co-op" in c && none { it.contains("co-op") }) add("Co-op")
        if (c.any { it == "online pvp" }) add("Online PvP")
        if (c.any { it.contains("shared/split screen pvp") }) add("Local PvP")
        if ("mmo" in c) add("MMO")
        if (c.any { it.contains("cross-platform multiplayer") }) add("Crossplay")
        if (isEmpty() && "multi-player" in c) add("Multiplayer")
    }

    fun inputs(i: Info) = buildList {
        val c = i.categories.map { it.lowercase(us) }
        if (!i.vrOnly && (i.windows || i.mac || i.linux)) add("Keyboard and mouse")
        when {
            i.controller == "full" || "full controller support" in c -> add("Full controller support")
            i.controller == "partial" || "partial controller support" in c -> add("Partial controller support")
        }
        if (c.any { it.startsWith("dualsense") }) add("PlayStation DualSense")
        else if (c.any { it.startsWith("dualshock") }) add("PlayStation DualShock")
        if (c.any { it == "tracked controller support" || it == "tracked motion controller support" }) add("Tracked VR controllers")
        if (c.any { it == "remote play on tablet" || it == "remote play on phone" }) add("Touch, through Remote Play")
    }

    fun vrLine(i: Info) = when { i.vrOnly -> "VR only: needs a headset"; i.vrMode -> "Optional VR mode"; else -> "No VR" }

    fun deckWord(n: Int?) = when (n) { 3 -> "Verified"; 2 -> "Playable"; 1 -> "Unsupported"; else -> null }

    /** Steam's review words for a share of positive reviews. */
    fun reviewWord(pct: Int, count: Int) = when {
        count < 10 -> "Too few reviews"
        pct >= 95 && count >= 500 -> "Overwhelmingly Positive"
        pct >= 80 && count >= 50 -> "Very Positive"
        pct >= 80 -> "Positive"
        pct >= 70 -> "Mostly Positive"
        pct >= 40 -> "Mixed"
        pct >= 20 -> "Mostly Negative"
        count >= 500 -> "Overwhelmingly Negative"
        count >= 50 -> "Very Negative"
        else -> "Negative"
    }

    private fun and(l: List<String>) = when (l.size) { 0 -> ""; 1 -> l[0]; 2 -> "${l[0]} and ${l[1]}"; else -> l.dropLast(1).joinToString(", ") + " and " + l.last() }
    private fun an(w: String) = if (w.isNotEmpty() && w.first().lowercaseChar() in "aeiou") "an" else "a"

    private val OS_OF = mapOf("mac" to "Mac", "macs" to "Mac", "macbook" to "Mac", "macbooks" to "Mac", "macos" to "Mac", "mac os" to "Mac", "os x" to "Mac",
        "linux" to "Linux", "ubuntu" to "Linux", "windows" to "Windows", "windows 10" to "Windows", "windows 11" to "Windows", "pc" to "Windows",
        "steam deck" to "Steam Deck", "steamdeck" to "Steam Deck", "steam os" to "Steam Deck", "steamos" to "Steam Deck", "chromebook" to "Chromebook", "chromebooks" to "Chromebook")

    /** The spoken answer to what was asked. */
    fun answer(i: Info, ask: GameAsk): String {
        val n = i.name
        val d = ask.detail?.lowercase(us)
        return when (ask.want) {
            GameWant.VR -> when { i.vrOnly -> "$n is VR only; you need a headset to play it."; i.vrMode -> "$n has an optional VR mode; you can play it with or without a headset."; else -> "$n doesn't support VR." }
            GameWant.OS -> when (val os = d?.let { OS_OF[it] }) {
                "Mac" -> if (i.mac) "Yes, $n runs on Mac." else "No, $n isn't on Mac; it's ${and(osList(i))} only."
                "Linux" -> if (i.linux) "Yes, $n runs on Linux." else "No native Linux version of $n" + (deckWord(i.deck)?.let { if (i.deck!! >= 2) ", but it's Steam Deck $it, so it runs through Proton." else "." } ?: ".")
                "Windows" -> if (i.windows) "Yes, $n runs on Windows." else "No, $n isn't on Windows."
                "Steam Deck" -> deckWord(i.deck)?.let { w -> "$n is Steam Deck $w." } ?: "Valve hasn't rated $n for Steam Deck yet."
                "Chromebook" -> "$n has no Chromebook version" + (if (i.linux) "; Chromebooks that run Linux apps and Steam may handle it." else ".")
                else -> "$n runs on ${and(osList(i))}" + (deckWord(i.deck)?.let { "; it's Steam Deck $it." } ?: ".")
            }
            GameWant.INPUT -> {
                val ins = inputs(i)
                if (d != null && Regex("controller|gamepad|game ?pad|joystick|dualsense|dualshock").containsMatchIn(d)) when {
                    ins.contains("Full controller support") -> "Yes, $n has full controller support."
                    ins.contains("Partial controller support") -> "$n has partial controller support; some menus may need a mouse."
                    else -> "Steam doesn't list controller support for $n; it's made for ${if (i.vrOnly) "VR controllers" else "keyboard and mouse"}."
                } else if (d != null && Regex("touch").containsMatchIn(d)) {
                    if (ins.any { it.startsWith("Touch") }) "Only through Steam Remote Play on a phone or tablet." else "Steam doesn't list touch controls for $n."
                } else "$n plays with ${and(ins.map { it.replaceFirstChar { c -> c.lowercase(us) } }).ifBlank { "keyboard and mouse" }}."
            }
            GameWant.LANGUAGES -> {
                val asked = d?.let { w -> LANGS.entries.firstOrNull { Regex("^(?:${it.key})$").matches(w) }?.value?.first }
                if (asked != null) {
                    val base = asked.substringBefore(" (")
                    val hit = i.languages.firstOrNull { it.name.startsWith(base, true) || it.name.contains(base, true) }
                    if (hit == null) "No, $n isn't in $asked."
                    else if (hit.audio) "Yes, $n is in $asked, with full voice audio."
                    else "Yes, $n is in $asked: menus and subtitles, not voices."
                } else {
                    val audio = i.languages.filter { it.audio }.map { it.name }
                    "$n supports ${i.languages.size} language${if (i.languages.size == 1) "" else "s"}" + (if (audio.isNotEmpty()) ", with full audio in ${and(audio.take(4))}" + (if (audio.size > 4) " and ${audio.size - 4} more" else "") else "") + "."
                }
            }
            GameWant.STYLE -> {
                val st = styles(i)
                if (d != null) {
                    val want = d.replace(Regex("pixel art|pixelated|pixel"), "pixel graphics").replace(Regex("cartoon(?:ish)?"), "cartoony").replace("cel shaded", "cel-shading")
                        .replace("hand drawn", "hand-drawn").replace("stylised", "stylized").replace("photorealistic", "realistic").replace("side scrolling", "side scroller")
                        .replace(Regex("first person"), "first-person").replace(Regex("third person"), "third person").replace("top down", "top-down")
                    val yes = i.tags.any { it.equals(want, true) || it.lowercase(us).replace("-", " ") == want.replace("-", " ") }
                    if (yes) "Yes, $n is ${want.replace("pixel graphics", "pixel art")}." + (st.takeIf { it.size > 1 }?.let { " Players also call it ${and(it.filter { s -> !s.equals(want, true) }.take(2).map { s -> s.lowercase(us) })}." } ?: "")
                    else "Not really; players describe $n as ${and(st.take(3).map { it.lowercase(us) }).ifBlank { "something else" }}."
                } else if (st.isEmpty()) "Steam players haven't tagged an art style for $n." else "$n has a ${and(st.take(3).map { it.lowercase(us).replace("pixel graphics", "pixel art") })} look."
            }
            GameWant.GENRE -> {
                val g = genreTags(i)
                "$n is " + (if (g.isNotEmpty()) "${an(g[0])} ${and(g.take(3).map { it.lowercase(us).replace("rpg", "RPG").replace("fps", "FPS") })} game" else "a game") +
                    (if (i.genres.isNotEmpty()) "; Steam files it under ${and(i.genres.take(3))}." else ".")
            }
            GameWant.TAGS -> if (d != null) {
                val words = TAG_WORDS.firstOrNull { Regex("^(?:${it.first})$").matches(d) }?.second ?: d
                val cats = i.categories.map { it.lowercase(us) }
                val yes = i.tags.any { it.equals(words, true) } || i.genres.any { it.equals(words, true) } ||
                    (Regex("co-?op").matches(d) && cats.any { it.contains("co-op") }) || (d == "multiplayer" && cats.any { it.contains("multi-player") || it.contains("pvp") || it.contains("co-op") }) ||
                    (Regex("single ?player").matches(d) && "single-player" in cats) || (Regex("crossplay|cross[- ]platform").matches(d) && cats.any { it.contains("cross-platform") }) ||
                    (Regex("split ?screen|local co-?op").matches(d) && cats.any { it.contains("split screen") }) || (Regex("free to play|f2p").matches(d) && i.steamPrice?.now == 0.0)
                val mode = Regex("co-?op|multi|single|split|pvp|cross|online").containsMatchIn(d)
                if (yes && mode) "Yes. $n has ${and(modes(i).map { it.lowercase(us) }).ifBlank { d }}."
                else if (yes) "Yes, $n is ${an(words)} ${words.lowercase(us).replace("rpg", "RPG").replace("fps", "FPS")} game."
                else if (mode) "No. $n is ${and(modes(i).map { it.lowercase(us) }).ifBlank { "single-player" }}."
                else "Not really. Players tag $n as ${and(genreTags(i).take(3))}."
            } else "Players tag $n as ${and(i.tags.take(5))}."
            GameWant.DEVELOPER -> "$n was made by ${and(i.developers.take(3)).ifBlank { "an unlisted studio" }}" + (if (i.publishers.isNotEmpty() && i.publishers != i.developers) " and published by ${and(i.publishers.take(2))}." else ".")
            GameWant.PUBLISHER -> "$n is published by ${and(i.publishers.take(3)).ifBlank { "its developer" }}" + (if (i.developers.isNotEmpty() && i.publishers != i.developers) " and made by ${and(i.developers.take(2))}." else ".")
            GameWant.REVIEWS -> reviewLine(i) ?: "$n doesn't have enough Steam reviews yet."
            GameWant.PRICE -> priceLine(i)
            GameWant.OVERVIEW -> {
                val g = genreTags(i).take(2).map { it.lowercase(us).replace("rpg", "RPG").replace("fps", "FPS") }
                "$n is " + (if (g.isNotEmpty()) "${an(g[0])} ${and(g)} game" else "a game") + (i.developers.firstOrNull()?.let { " from $it" } ?: "") +
                    ", ${i.steamPrice?.shown()?.let { if (it == "Free") "free" else it } ?: "not priced"} on Steam" + (i.percent?.let { p -> " with $p% positive reviews." } ?: ".")
            }
        }
    }

    fun osList(i: Info) = listOfNotNull("Windows".takeIf { i.windows }, "Mac".takeIf { i.mac }, "Linux".takeIf { i.linux }).ifEmpty { listOf("Windows") }

    private fun reviewLine(i: Info): String? {
        val p = i.percent ?: return i.metacritic?.let { "${i.name} has a Metacritic score of $it." }
        val c = i.reviewCount ?: 0
        return "${i.name} is ${reviewWord(p, c)} on Steam: $p% of ${String.format(us, "%,d", c)} reviews are positive" + (i.metacritic?.let { "; Metacritic gives it $it." } ?: ".")
    }

    private fun priceLine(i: Info): String {
        val c = i.cheapest ?: return if (i.comingSoon) "${i.name} isn't out yet, so there's no price." else "I couldn't find a price for ${i.name}."
        val steam = i.steamPrice
        return when {
            c.now <= 0.0 -> "${i.name} is free to play."
            steam != null && (c.store == "Steam" || c.now >= steam.now) -> "${i.name} is ${steam.shown()} on Steam" + (if (i.otherPrices.isNotEmpty()) ", the best price right now." else ".")
            steam != null -> "${i.name} is cheapest at ${c.store}: ${c.shown()}, versus ${Prices.money(steam.now)} on Steam."
            else -> "${i.name} is cheapest at ${c.store}: ${c.shown()}."
        }
    }

    /** The GAME card for a question, or null when Steam doesn't have the game. */
    suspend fun card(ask: GameAsk, about: String? = null): LookupResult? {
        // "Harry potter" alone could be a book: near-misses only count when the question is about a game.
        // Not GENRE: "what genre is radiohead" asks about a band, and a loose match would find Radiohead's Kid A Mnesia Exhibition.
        val gamey = ask.want in setOf(GameWant.VR, GameWant.OS, GameWant.INPUT, GameWant.STYLE, GameWant.LANGUAGES, GameWant.TAGS) ||
            Regex("\\b(?:game|steam|video ?game)\\b", RegexOption.IGNORE_CASE).containsMatchIn(ask.said)
        val i = info(ask.title, loose = gamey) ?: return null
        return LookupResult(i.name, listOfNotNull(i.developers.firstOrNull(), i.released?.let { Regex("\\d{4}").find(it)?.value }).joinToString(", ").ifBlank { null },
            LookupKind.GAME, about ?: i.about.orEmpty(), i.image, i.url, emptyList(), answer(i, ask), sources = listOf("Steam", "SteamSpy", "CheapShark"),
            heading = "GAME", game = i)
    }

    suspend fun findCardFor(req: GameSearch) = findCard(req)
}
