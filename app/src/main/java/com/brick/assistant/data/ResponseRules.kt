package com.brick.assistant.data

/**
 * A more specific David response pool inside a request type, for example "Driver › Audio effects ›
 * Reverb › Turn on". When a request matches a rule, David picks from the most specific rule that has
 * responses, then its parent rules, then the request type's own pool.
 */
data class ResponseRule(
    val key: String,
    val category: Category,
    /** Breadcrumb shown in the Responses screen. */
    val label: String,
    val parent: String?,
    val defaults: List<String> = emptyList(),
) {
    val depth: Int get() = label.count { it == '›' }
    val storeKey: String get() = "rule:$key"
}

object ResponseRules {
    private val effects = listOf(
        "reverb" to "Reverb", "lpf" to "Low-pass filter", "hpf" to "High-pass filter",
        "eq" to "Equalizer", "spatial" to "Spatial audio",
    )
    private val kinds = listOf(
        "song" to "Song", "album" to "Album", "artist" to "Artist", "playlist" to "Playlist",
        "liked" to "Liked songs", "any" to "Anything (no title)",
    )

    val all: List<ResponseRule> = buildList {
        val m = Category.MUSIC
        add(ResponseRule("driver", m, "Driver", null, listOf("Spinning it up in Driver.", "Driver is on it.")))
        add(ResponseRule("music.listen", m, "Where to listen to a song", null, listOf("Let me check the stations.", "Finding you a stream.")))
        add(ResponseRule("music.buy", m, "Where to buy a song", null, listOf("Let me check the record stores.", "Shopping for tracks.")))
        add(ResponseRule("music.credits", m, "Song credits (singer, producer, writers)", null, listOf("Reading the liner notes.", "Let me check the credits.")))
        add(ResponseRule("driver.play", m, "Driver › Play", "driver"))
        kinds.forEach { (k, l) -> add(ResponseRule("driver.play.$k", m, "Driver › Play › $l", "driver.play")) }
        add(ResponseRule("driver.effect", m, "Driver › Audio effects", "driver"))
        add(ResponseRule("driver.effect.on", m, "Driver › Audio effects › Any effect on", "driver.effect", listOf("Adding some flavor.", "Dialing in the effect.")))
        add(ResponseRule("driver.effect.off", m, "Driver › Audio effects › Any effect off", "driver.effect", listOf("Back to the dry mix.", "Stripping it back.")))
        effects.forEach { (e, l) ->
            add(ResponseRule("driver.effect.$e", m, "Driver › Audio effects › $l", "driver.effect"))
            add(ResponseRule("driver.effect.$e.on", m, "Driver › Audio effects › $l › Turn on", "driver.effect.$e"))
            add(ResponseRule("driver.effect.$e.off", m, "Driver › Audio effects › $l › Turn off", "driver.effect.$e"))
        }
        add(ResponseRule("driver.preset", m, "Driver › Presets", "driver", listOf("Loading your preset.", "Good taste.")))
        effects.forEach { (e, l) -> add(ResponseRule("driver.preset.$e", m, "Driver › Presets › $l", "driver.preset")) }
        add(ResponseRule("driver.lyrics", m, "Driver › Lyrics", "driver", listOf("Let me find the words.", "Fetching the lyrics.")))
        add(ResponseRule("music.lyrics", m, "Lyrics", null, listOf("Let me find the words.", "Pulling up the lyrics.")))
        add(ResponseRule("driver.nowplaying", m, "Driver › What's playing", "driver", listOf("Let me check.", "One moment.")))
        add(ResponseRule("spotify", m, "Spotify", null, listOf("Firing up Spotify.", "Spotify it is.")))
        kinds.forEach { (k, l) -> add(ResponseRule("spotify.play.$k", m, "Spotify › Play › $l", "spotify")) }
        add(ResponseRule("otherapp", m, "Other music apps", null))
        add(ResponseRule("controls", m, "Pause, resume and skip", null, listOf("Sure.", "Done.")))
        add(ResponseRule("like", m, "I like this song", null, listOf("Good taste.", "Noted. It's a banger.", "Saving it.")))
        add(ResponseRule("playlist.add", m, "Add the song to a playlist", null, listOf("Filing it away.", "Adding it to the mix.")))

        val t = Category.TIMER
        add(ResponseRule("timers.pause", t, "Pause a timer", null, listOf("Holding it right there.", "Pausing.")))
        add(ResponseRule("timers.resume", t, "Resume a timer", null, listOf("And we're back.", "Picking up where we left off.")))
        add(ResponseRule("timers.cancel", t, "Cancel or stop a timer", null, listOf("Done.", "All clear.")))
        add(ResponseRule("timers.status", t, "Time left", null, listOf("Let me check the clock.", "One moment.")))
        add(ResponseRule("stopwatch", t, "Stopwatch", null, listOf("Clock's running.", "Timing it.")))

        val y = Category.YOUTUBE
        add(ResponseRule("youtube.play", y, "Play a video", null, listOf("Rolling the tape.", "Press play, done.")))
        add(ResponseRule("youtube.music", y, "Play music (YouTube Music)", null))
        add(ResponseRule("youtube.search", y, "Search", null, listOf("Let's see what's out there.", "Searching the tube.")))

        val se = Category.SEARCH
        add(ResponseRule("search.web", se, "When you ask to search", null))
        add(ResponseRule("search.question", se, "Questions (answered without a web search)", null, listOf("Let me think.", "Good question.", "Hmm.")))
        add(ResponseRule("search.person", se, "Public figures (actors, singers, YouTubers...)", null, listOf("Ah, a famous face.", "Let me pull their file.")))
        add(ResponseRule("search.movie", se, "Movies", null, listOf("Rolling the credits.", "Let me check the reel.")))
        add(ResponseRule("search.tv_show", se, "TV shows", null, listOf("Changing the channel.", "Let me check the listings.")))
        add(ResponseRule("search.platform", se, "Apps, sites and platforms", null, listOf("Logging on.", "Let me look it up.")))
        add(ResponseRule("search.company", se, "Companies and brands", null, listOf("Checking the books.", "Let me look into them.")))
        add(ResponseRule("search.game", se, "Video games", null, listOf("Pressing start.", "Let me look it up.")))
        add(ResponseRule("search.music", se, "Songs, albums and bands", null, listOf("Dropping the needle.", "Let me look it up.")))
        add(ResponseRule("search.book", se, "Books", null, listOf("Opening the book on that.", "Let me check the shelves.")))
        add(ResponseRule("search.place", se, "Places", null, listOf("Checking the atlas.", "Let me look it up.")))
        add(ResponseRule("search.spell", se, "Spelling", null, listOf("Spelling bee time.", "Letter by letter.")))
        add(ResponseRule("media.rating", se, "Movie and TV ratings", null, listOf("Let me check with the critics.", "Pulling the scores.")))
        add(ResponseRule("media.watch", se, "Where to watch", null, listOf("Checking the listings.", "Let me find a screen for that.")))
        add(ResponseRule("media.buy", se, "Where to buy movies and shows", null, listOf("Let me check the shelves.", "Shopping around.")))

        val ma = Category.MATH
        add(ResponseRule("math.calc", ma, "Calculations", null))
        add(ResponseRule("math.convert", ma, "Unit conversions", null, listOf("Converting.", "Let me run the numbers.")))

        val we = Category.WEATHER
        add(ResponseRule("weather.today", we, "Today", null))
        add(ResponseRule("weather.tomorrow", we, "Tomorrow", null, listOf("Peeking at tomorrow.")))
        add(ResponseRule("weather.week", we, "This week", null, listOf("Let me look at the week ahead.")))
        add(ResponseRule("weather.weekend", we, "The weekend", null, listOf("Checking the weekend.")))

        val dv = Category.DEVICE
        add(ResponseRule("device.time", dv, "Time and date", null, listOf("Let me check the clock.", "Time check.")))
        add(ResponseRule("device.countdown", dv, "Countdowns", null, listOf("Counting the days.", "Let me count.")))
        add(ResponseRule("device.flashlight", dv, "Flashlight", null))
        add(ResponseRule("device.flashlight.on", dv, "Flashlight › On", "device.flashlight", listOf("Let there be light.", "Lights.")))
        add(ResponseRule("device.flashlight.off", dv, "Flashlight › Off", "device.flashlight", listOf("Lights out.", "Back to the dark.")))
        add(ResponseRule("device.volume", dv, "Volume", null))
        add(ResponseRule("device.ringer", dv, "Ringer and Do Not Disturb", null, listOf("Shh.", "Got it.")))
        add(ResponseRule("device.battery", dv, "Battery", null, listOf("Checking the juice.", "Let me look.")))
        add(ResponseRule("device.brightness", dv, "Brightness", null))
        add(ResponseRule("device.connections", dv, "Wi-Fi and Bluetooth (older)", null))
        add(ResponseRule("device.settings", dv, "Phone settings (Wi-Fi, airplane mode, rotation...)", null, listOf("On it.", "Let me flip that.")))
        add(ResponseRule("device.settings.where", dv, "Phone settings › Where is it", "device.settings", listOf("Let me show you where.", "It's in there somewhere. Found it.")))
        add(ResponseRule("device.smarthome", dv, "Smart home and lights", null, listOf("Talking to the house.", "On it.")))

        val r = Category.READING
        add(ResponseRule("read.notifications", r, "Notifications", null))
        add(ResponseRule("read.email", r, "Email", null, listOf("Opening the mailbag.", "Let me check your inbox.")))
        add(ResponseRule("read.calendar", r, "Calendar events", null, listOf("Let me check your schedule.", "Looking at your calendar.")))

        val n = Category.NAVIGATION
        listOf("google" to "Google Maps", "waze" to "Waze", "other" to "Other maps apps").forEach { (a, l) ->
            add(ResponseRule("maps.$a", n, l, null))
            add(ResponseRule("maps.$a.directions", n, "$l › Directions", "maps.$a"))
            add(ResponseRule("maps.$a.show", n, "$l › Show a place", "maps.$a"))
        }
        add(ResponseRule("maps.hours", n, "Opening and closing times", null, listOf("Let me check their hours.", "Checking when they're open.")))
        add(ResponseRule("maps.photos", n, "Photography spots", null, listOf("Let me find you something worth shooting.", "Scouting locations.")))
        add(ResponseRule("maps.food", n, "Food spots", null, listOf("Let's get you fed.", "Sniffing out something good.", "On it. My treat. Not really.")))
        add(ResponseRule("maps.shopping", n, "Shopping and stores", null, listOf("Time to spend some money.", "Let me find a store.")))
        add(ResponseRule("maps.places", n, "Other places (gas, gyms, salons...)", null, listOf("Let me find one.", "Looking around.")))
        add(ResponseRule("maps.saved", n, "Saved places (take me home)", null, listOf("Taking you there.", "I know the way.")))

        val s = Category.NIGHT_SKY
        add(ResponseRule("sky.bortle", s, "Bortle level", null, listOf("Measuring the glow.", "Let me check the light pollution.")))
        add(ResponseRule("sky.stars", s, "Visible stars", null, listOf("Counting stars.", "Let me count them for you.")))
        add(ResponseRule("sky.milkyway", s, "Milky Way", null, listOf("Looking for the galaxy.", "Let me find the Milky Way.")))
        add(ResponseRule("sky.report", s, "Night sky report", null, listOf("Running the night sky report.", "Here's your sky.")))
        add(ResponseRule("sky.darkest", s, "Darkest sky search", null, listOf("Hunting for darkness.", "Let me find you some real dark.", "Searching for the darkest skies.")))
        add(ResponseRule("sky.darkest.world", s, "Darkest sky search › Worldwide", "sky.darkest", listOf("Searching the whole planet.", "Scanning the globe for darkness.")))
    }

    private val byKey = all.associateBy { it.key }

    fun forCategory(c: Category): List<ResponseRule> = all.filter { it.category == c }

    fun get(key: String): ResponseRule? = byKey[key]

    /** [key] and its parents, most specific first. Unknown keys are skipped. */
    fun chain(key: String?): List<ResponseRule> {
        val out = ArrayList<ResponseRule>()
        var r = key?.let { byKey[it] }
        while (r != null) {
            out += r
            r = r.parent?.let { byKey[it] }
        }
        return out
    }
}
