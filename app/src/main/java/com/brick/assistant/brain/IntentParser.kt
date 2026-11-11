package com.brick.assistant.brain

import com.brick.assistant.data.Category

enum class TimerOp { PAUSE, RESUME, CANCEL, STATUS }

enum class CalendarRange { TODAY, TOMORROW, WEEK, NEXT }

enum class MusicCommand { PLAY_QUERY, PLAY_ANY, PAUSE, RESUME, NEXT, PREVIOUS }

/** What a "play ..." request is aimed at. AUTO lets the music app decide (playlist, album, artist or song). */
enum class MediaKind { AUTO, SONG, ALBUM, ARTIST, PLAYLIST, LIKED }

/** Audio effects in the Driver music player. [spoken] is used in robot lines. */
enum class AudioEffect(val spoken: String) {
    REVERB("reverb"),
    LOW_PASS("the low-pass filter"),
    HIGH_PASS("the high-pass filter"),
    EQUALIZER("the equalizer"),
    SPATIAL("spatial audio"),
}

enum class EffectState { ON, OFF, TOGGLE }

/** Which app should play a "play ..." request. DEFAULT = Driver if installed, unless Settings names another app. */
enum class MusicApp { DEFAULT, DRIVER, SPOTIFY }

/** Optic's visualizer designs ([name] is Optic's setting) and how Brick says them. */
enum class VizDesign(val spoken: String) { EDGE_GLOW("Edge Glow") }

/** Where Optic's visualizer takes its colors ([name] is Optic's setting). */
enum class VizColors(val spoken: String) {
    BRICK("my colors"), ALBUM("the album art"), SPECTRUM("the full spectrum"), GRADIENT("a gradient"), PALETTE("the palette"), SINGLE("a single color"),
}

/** A parsed user request. [category] selects which David response pool is used. */
sealed class Request(val category: Category?) {
    data class Timer(val seconds: Long?) : Request(Category.TIMER)
    /** "Pause the timer" / "resume my timer" / "cancel all timers" / "how much time is left". */
    data class TimerControl(val op: TimerOp, val all: Boolean) : Request(Category.TIMER)
    /** [days] uses java.util.Calendar day constants; empty = one-time alarm. */
    data class Alarm(val time: ParsedTime, val label: String?, val days: List<Int>) : Request(Category.ALARM)
    data class Navigate(val destination: String, val directions: Boolean) : Request(Category.NAVIGATION)
    /** Discord, through its notifications: read messages, or reply to someone ([body] null = ask for it). */
    data class Discord(val reply: Boolean, val who: String?, val body: String?) : Request(Category.DISCORD)    /** "Turn off Hey Brick", "use Celsius": changes one of Brick's own settings. */
    data class Setting(val setting: BrickSetting, val on: Boolean) : Request(Category.SETTINGS)
    /**
     * "Find me a good sandwich place", "look for Chinese", "where can I buy shoes": places to eat or shop.
     * [what] is the thing ("sandwich", "chinese food", "shoe stores"), [area] a place named ("in Chicago"),
     * null meaning near you. [best]: you asked for good ones.
     */
    data class FindPlace(val what: String, val kind: FindKind, val area: String?, val best: Boolean) : Request(Category.NAVIGATION)
    /** "When does Target close?" / "Is the library open?" */
    data class PlaceHours(val place: String, val ask: HoursAsk, val tomorrow: Boolean) : Request(Category.NAVIGATION)
    /** "Show me photography spots in a 50 mile radius". */
    data class PhotoSpots(val radiusMiles: Int, val kind: SpotKind) : Request(Category.NAVIGATION)
    data class Reminder(val task: String, val time: ParsedTime) : Request(Category.REMINDER)
    data class CalendarEvent(val title: String, val time: ParsedTime) : Request(Category.CALENDAR)
    /** If [body] is null the recipient/body split is decided later by matching contacts. */
    data class Text(val recipient: String, val body: String?) : Request(Category.TEXT)
    data class Call(val who: String) : Request(Category.CALL)
    /** [explicit] = you asked to search ("search for", "look up", "google"); otherwise Brick only tries to answer. */
    data class Search(val query: String, val explicit: Boolean = false) : Request(Category.SEARCH)
    data class Math(val result: MathEvaluator.Result) : Request(Category.MATH)
    data class Weather(val city: String?, val tomorrow: Boolean, val aspect: WeatherAspect = WeatherAspect.GENERAL) : Request(Category.WEATHER)
    data class Music(
        val command: MusicCommand,
        val query: String?,
        val kind: MediaKind = MediaKind.AUTO,
        val artist: String? = null,
        /** App the user named ("... in Driver", "... on Spotify"); DEFAULT follows Settings. */
        val app: MusicApp = MusicApp.DEFAULT,
    ) : Request(Category.MUSIC)
    /** Driver: turn an audio effect on / off. */
    data class Effect(val effect: AudioEffect, val state: EffectState, val others: List<AudioEffect> = emptyList()) : Request(Category.MUSIC) {
        /** Every effect named ("turn on the lpf and spatialize"). */
        val all: List<AudioEffect> get() = listOf(effect) + others
    }
    /** Driver: apply a saved effect preset by name. [effect] is null when the user didn't say which effect. */
    data class EffectPreset(val effect: AudioEffect?, val preset: String) : Request(Category.MUSIC)
    /** Driver: download lyrics. [song] null = the song that is playing. */
    data class Lyrics(val song: String?) : Request(Category.MUSIC)
    /** "I like this song": add what's playing to its app's likes (Driver favorites, Spotify Liked Songs...). */
    data object LikeSong : Request(Category.MUSIC)
    /** Driver: "what's playing?" */
    data object NowPlaying : Request(Category.MUSIC)
    /** "Take a note" (text null = dictate it) / "note to self buy milk". */
    data class Note(val text: String?) : Request(Category.NOTES)
    /** "Read my notifications" / "read my emails (from John)". */
    data class ReadNotifications(val email: Boolean, val from: String?) : Request(Category.READING)
    /** "What's on my calendar tomorrow" / "what's my next meeting". */
    data class ReadCalendar(val range: CalendarRange) : Request(Category.READING)
    /** YouTube: [play] the top video, or open search results. [music] = YouTube Music when it's available. */
    data class YouTube(val query: String, val play: Boolean, val music: Boolean) : Request(Category.YOUTUBE)
    /** "Open Spotify". */
    data class OpenApp(val name: String) : Request(Category.APPS)
    /** "What time is it (in Tokyo)" / "what's the date". */
    data class TellTime(val date: Boolean, val place: String?) : Request(Category.DEVICE)
    /** Flashlight, volume, ringer, Do Not Disturb, battery, brightness, Wi-Fi / Bluetooth. */
    /** [target]: for Bluetooth battery, which device ("watch", "headphones", "galaxy buds"); null = all. */
    data class Device(val op: DeviceOp, val percent: Int? = null, val target: String? = null) : Request(Category.DEVICE)
    data class Chat(val reply: String) : Request(Category.CHAT)
    /** "Add this song to my gym playlist": the playing app's playlist called [playlist]. */
    data class AddToPlaylist(val playlist: String) : Request(Category.MUSIC)
    /**
     * Optic's edge visualizer: turn it on / off ([on]), pick a [design] or [colors], make it more (+1) or less
     * (-1) [sensitive], or ask what it's set to (nothing else set).
     */
    data class Visualizer(
        val on: Boolean? = null,
        val design: VizDesign? = null,
        val colors: VizColors? = null,
        val sensitive: Int = 0,
        val onlyMedia: Boolean? = null,
    ) : Request(Category.SETTINGS)
}

object IntentParser {
    private val IC = setOf(RegexOption.IGNORE_CASE)
    /** Compiled patterns, reused: a request runs hundreds of them, and compiling each one every time was the main cost. */
    private val compiled = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun rx(p: String): Regex = compiled.getOrPut(p) { Regex(p, IC) }
    private val compiledCs = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun re(p: String, ignoreCase: Boolean = false): Regex = if (ignoreCase) rx(p) else compiledCs.getOrPut(p) { Regex(p) }

    private val politePrefix = rx("^(?:(?:hey|ok|okay)\\s+brick[,\\s]*)?(?:(?:please|can you|could you|would you|will you|i want you to|i need you to)\\s+)*")
    private val trailingPolite = rx("[\\s,]+(?:please|for me|now|right now|thanks|thank you)$")

    /** Light clean-up that keeps letter case (names, message bodies). */
    fun clean(raw: String): String {
        var s = raw.trim()
        s = s.replace(re("\\ba\\.\\s?m\\.?(?=\\s|$)", true), "am")
        s = s.replace(re("\\bp\\.\\s?m\\.?(?=\\s|$)", true), "pm")
        s = s.replace(re("(?<=[A-Za-z])-(?=[A-Za-z])"), " ")
        s = s.replace(re("[,!?;\"“”]"), " ")
        s = s.replace(re("\\.(?!\\d)"), " ")
        s = s.replace(re("\\s+"), " ").trim()
        // Typed shortcuts without apostrophes ("whats", "wheres", "hows") read like the spoken forms.
        s = s.replace(re("\\b(what|where|when|how|who|that|it)s\\b", true)) { "${it.groupValues[1]}'s" }
        s = NumberWords.convert(s)
        s = s.replace(politePrefix, "").replace(trailingPolite, "").trim()
        return s
    }

    /**
     * Understands a request. When the exact phrasings don't match, it tries again with filler words removed
     * and common synonyms swapped ("could you just switch on the flashlight real quick" -> "turn on the
     * flashlight"), so you don't need the exact words.
     */
    fun parse(raw: String): Request {
        val first = parseStrict(raw)
        if (first !is Request.Search || first.explicit) return first
        val relaxed = relax(clean(raw))
        if (relaxed.isBlank() || relaxed.equals(clean(raw), ignoreCase = true)) return first
        val second = parseStrict(relaxed)
        return if (second is Request.Search) first else second
    }

    private val fillers = listOf(
        "um+", "uh+", "erm", "hmm+", "so", "just", "actually", "basically", "literally", "kind of", "sort of", "like",
        "real quick", "quickly", "maybe", "go ahead and", "i want you to", "i need you to", "i'd like you to",
        "i would like you to", "can you", "could you", "would you", "will you", "would you mind", "do me a favou?r and",
        "please", "for me", "brick", "hey", "okay", "ok", "yo", "now", "right now", "for a sec", "real fast",
        "i want to", "i'd like to", "let's", "lets", "the heck", "the hell",
    ).joinToString("|")

    /** Removes filler words and swaps synonyms for the words the patterns know. */
    fun relax(s: String): String {
        var t = " ${s.lowercase()} "
        t = t.replace(re("\\b(?:switch|power|flip|click|crank|kick) on\\b"), "turn on")
        t = t.replace(re("\\b(?:switch|power|flip|shut|click|kill) off\\b|\\bshut down\\b"), "turn off")
        t = t.replace(re("\\b(?:put on|start up|fire up|boot up)\\b(?= (?:the )?(?:flashlight|torch|light))"), "turn on")
        t = t.replace(re("\\bwhat's the deal with\\b|\\bwhat's up with\\b"), "what is")
        t = t.replace(re("\\bhow's\\b"), "how is")
        t = t.replace(re("\\b(?:$fillers)\\b"), " ")
        return t.replace(re("\\s+"), " ").trim()
    }

    // ---------------------------------------------------------------- "Did you mean ...?"

    /** Words speech recognition often hears instead of command words. */
    private val misheard = listOf(
        "unable|in able|and able|an able|enabled|unabled" to "enable",
        "this able|dis able|disabled|these able|is able" to "disable",
        "elle p f|el p f|l p f|lp f|l pf|lpg|lpa|elpf|alpf|lbf|lpv|lfp|lps|lpn" to "lpf",
        "h p f|hp f|h pf|hpg|hbf|hfp|hps" to "hpf",
        "rebirth|re verb|rewerb|revere|reverbs|rever" to "reverb",
        "e q|eeq|eek|equaliser|equalize her" to "eq",
        "spatial eyes|special eyes|special lies|spatial lies|spatialise|special eyes" to "spatialize",
        "special audio|spatially audio" to "spatial audio",
        "flash lite|flash like|flashlights|flesh light" to "flashlight",
        "turn of|turnoff" to "turn off",
        "turnon" to "turn on",
        "timber|time her" to "timer",
        "a lorm|uh larm" to "alarm",
        "play list" to "playlist",
        "you tube" to "youtube",
    )

    /** Words commands use, for correcting close mishearings ("tymer" -> "timer"). */
    private val commandWords = setOf(
        "enable", "disable", "toggle", "turn", "switch", "play", "pause", "resume", "next", "skip", "previous",
        "timer", "alarm", "remind", "reminder", "text", "call", "flashlight", "torch", "volume", "mute", "unmute",
        "brightness", "battery", "weather", "reverb", "lpf", "hpf", "equalizer", "spatial", "spatialize", "preset",
        "lyrics", "playlist", "navigate", "directions", "note", "open", "launch", "start", "stop", "cancel",
        "louder", "quieter", "vibrate", "silent", "bluetooth", "wifi", "youtube", "spotify", "driver", "calendar",
        "notifications", "email", "minutes", "seconds", "hours", "headphones", "earbuds", "watch", "speaker",
    )

    /**
     * When [raw] isn't understood as a command, a corrected version that is ("unable lpf" -> "enable lpf"),
     * or null. Only corrections that turn into a real command are offered, so questions are left alone.
     */
    fun suggest(raw: String): String? {
        val first = parse(raw)
        if (first !is Request.Search || first.explicit) return null
        val base = relax(clean(raw))
        var t = " $base "
        for ((from, to) in misheard) t = t.replace(re("(?<= )(?:$from)(?= )"), to)
        val fixed = t.trim().split(" ").joinToString(" ") { w ->
            if (w.length < 4 || w in commandWords || w.any { it.isDigit() }) w
            else commandWords.filter { it.length >= 4 }.map { it to AppLauncher.similarity(w, it) }.maxByOrNull { it.second }
                ?.takeIf { it.second >= 0.75 }?.first ?: w
        }.trim()
        if (fixed.isBlank() || fixed == base) return null
        val second = parse(fixed)
        return if (second is Request.Search || second is Request.Chat) null else fixed
    }

    /** Which Bluetooth device a battery question is about: a kind ("watch"), a name ("galaxy buds"), or null for all. */
    private fun btTarget(s: String): String? {
        val t = s.lowercase()
        if (re("\\b(?:all|every|each)\\b|\\bbluetooth devices\\b|\\bdevices\\b").containsMatchIn(t)) return null
        // A name before the kind word ("galaxy buds", "sony headphones", "jbl speaker")
        re("\\b(?:my|the)\\s+((?:[a-z0-9]+\\s+){0,2}?)(headphones?|earbuds?|ear ?buds?|buds|airpods|headset|speakers?|watch|controllers?)\\b").find(t)?.let {
            val brand = it.groupValues[1].trim()
            val kind = it.groupValues[2]
            return if (brand.isEmpty() || brand == "bluetooth" || brand == "wireless") kind else "$brand $kind"
        }
        re("\\b(headphones?|earbuds?|ear ?buds?|buds|airpods|headset|speakers?|watch|controllers?)\\b").find(t)?.let { return it.groupValues[1] }
        return null
    }

    private fun parseStrict(raw: String): Request {
        val s = clean(raw)
        if (s.isEmpty()) return Request.Chat("I did not hear a request.")

        if (rx("^(?:hi|hello|hey|yo|greetings)(?:\\s+(?:there|brick))?$").matches(s)) {
            return Request.Chat("Hello. Awaiting your request.")
        }
        if (rx("^(?:thanks|thank you)(?:\\s+.*)?$").matches(s)) {
            return Request.Chat("You are welcome.")
        }

        parseSetting(s)?.let { return it }
        parseVisualizer(s)?.let { return it }
        parseAlarm(s)?.let { return it }
        parseTime(s)?.let { return it }
        parseDevice(s)?.let { return it }
        parseNote(raw, s)?.let { return it }
        parseDiscord(raw)?.let { return it }
        parseReading(s)?.let { return it }
        parseYouTube(s)?.let { return it }
        parseTimerControl(s)?.let { return it }
        parseTimer(s)?.let { return it }
        parseReminder(s)?.let { return it }
        parseCalendar(s)?.let { return it }
        parseText(s)?.let { return it }
        parseCall(s)?.let { return it }
        // Effects, presets, lyrics and "what's playing" always go to Driver, so naming it is optional:
        // "turn on reverb in Driver" / "enable Driver's reverb" are matched without the app name.
        val sd = withoutDriverMention(s)
        parseEffectPreset(sd)?.let { return it }
        parseEffect(sd)?.let { return it }
        parseEffectLoose(sd)?.let { return it }
        parseLyrics(sd)?.let { return it }
        parseNowPlaying(sd)?.let { return it }
        parseAddToPlaylist(sd)?.let { return it }
        if (rx("^(?:i (?:really )?(?:like|love|dig) (?:this|that|the) (?:song|track|tune|one)|(?:like|love|heart|favou?rite|save|thumbs up) (?:this|that|the) (?:song|track|tune|one)(?: for me)?|add (?:this|that|the|it)(?: song| track)? to (?:my )?(?:likes|liked songs|favou?rites|library|liked)|(?:like|heart|favou?rite) (?:it|this)|thumbs up|this (?:song|track) (?:is fire|slaps|bangs|is good))$").matches(sd)) return Request.LikeSong
        parsePhotoSpots(s)?.let { return it }
        parseHours(s)?.let { return it }
        parseFindPlace(s)?.let { return it }
        parseMusic(s)?.let { return it }
        parseWeather(s)?.let { return it }
        parseNavigation(s)?.let { return it }
        parseOpenApp(s)?.let { return it }
        parseBareTime(s)?.let { return it }
        MathEvaluator.tryParse(s)?.let { return Request.Math(it) }
        val explicit = rx("^(?:search\\b|look\\s+up\\b|google\\b|find\\s+out\\b|search\\s+the\\s+web\\b|web\\s+search\\b|bing\\b)").containsMatchIn(s)
        return Request.Search(searchQuery(s), explicit)
    }

    // ---------------------------------------------------------------- Alarm

    /** The current time; tests replace it. */
    @Volatile var clock: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() }

    private val dayNames = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    private fun parseAlarm(s: String): Request? {
        if (!rx("\\balarms?\\b|^wake me\\b|\\bwake me up\\b").containsMatchIn(s)) return null
        if (rx("\\b(?:cancel|delete|remove|turn off|stop|dismiss|snooze)\\b").containsMatchIn(s)) return null
        var t = s
        // Repeating days (java.util.Calendar: SUNDAY = 1 ... SATURDAY = 7)
        val days = sortedSetOf<Int>()
        fun take(r: Regex, add: () -> Unit) { if (r.containsMatchIn(t)) { add(); t = t.replace(r, " ") } }
        take(rx("\\b(?:every\\s+day|everyday|daily|each\\s+day)\\b")) { days += (1..7) }
        take(rx("\\b(?:on\\s+)?(?:every\\s+)?weekdays?\\b")) { days += (2..6) }
        take(rx("\\b(?:on\\s+)?(?:every\\s+)?weekends?\\b")) { days += listOf(1, 7) }
        rx("\\b(?:every|on)\\s+((?:(?:sunday|monday|tuesday|wednesday|thursday|friday|saturday)s?(?:\\s*(?:,|and)\\s*)?)+)").find(t)?.let { m ->
            if (m.value.startsWith("every", true) || re("s\\b").containsMatchIn(m.groupValues[1]) || days.isNotEmpty()) {
                dayNames.forEachIndexed { i, d -> if (m.groupValues[1].contains(d, true)) days += i + 1 }
                t = t.replace(m.value, " ")
            }
        }
        // Label: "called X" / "labeled X"
        var label: String? = null
        rx("\\b(?:called|named|labeled|labelled|label it|with the label)\\s+(.+)$").find(t)?.let { m ->
            label = m.groupValues[1].trim().ifBlank { null }
            t = t.substring(0, m.range.first)
        }
        // "alarm for 7" means a clock time, not a duration.
        t = t.replace(rx("\\bfor\\s+(?=\\d{1,2}(?::\\d{2})?(?:\\s*(?:am|pm))?(?:\\s|$))"), "at ")
        // A bare hour ("at 6") means its next occurrence; "wake me" prefers the morning.
        val hasMeridiem = rx("\\b(?:am|pm|morning|afternoon|evening|tonight|noon|midnight)\\b").containsMatchIn(t)
        val now = clock()
        // A day said anywhere ("9:40 tomorrow", "on Friday at 7") decides which 9:40 is meant.
        val saidDay = TimeParser.parse(t, now).let { if (it.hasDate) it.dateTime?.toLocalDate() else null }
        if (!hasMeridiem) {
            rx("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\b").find(t)?.let { m ->
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 1..12 && min in 0..59) {
                    val am = java.time.LocalTime.of(h % 12, min)
                    val pm = java.time.LocalTime.of(h % 12 + 12, min)
                    fun next(lt: java.time.LocalTime) = now.toLocalDate().atTime(lt).let { if (it.isAfter(now)) it else it.plusDays(1) }
                    val wake = rx("\\bwake\\b").containsMatchIn(s)
                    val useAm = when {
                        wake -> true
                        // Another day: alarms are for mornings unless you say otherwise.
                        saidDay != null && saidDay.isAfter(now.toLocalDate()) -> true
                        // "Today": the morning one if it's still ahead, else the evening.
                        saidDay != null -> now.toLocalDate().atTime(am).isAfter(now)
                        else -> !next(am).isAfter(next(pm))
                    }
                    t = t.replaceRange(m.range, "at $h:${"%02d".format(min)} ${if (useAm) "am" else "pm"}")
                }
            }
        }
        val pt = TimeParser.parse(t, now)
        return Request.Alarm(pt, label, days.toList())
    }

    // ---------------------------------------------------------------- Maps

    // ---------------------------------------------------------------- Brick's own settings

    private val settingNames = listOf(
        BrickSetting.WAKE_WORD to "hey brick|hay brick|the wake word|wake word|hot ?word|always listening|hands ?free(?: mode)?|wake up word|listening for hey brick",
        BrickSetting.POCKET_PAUSE to "pocket (?:detection|pause|mode|sensor)|(?:the )?pocket (?:feature|setting)|pause (?:hey brick )?in (?:my |a |the )?pocket",
        BrickSetting.HEADSET_MIC to "(?:the )?(?:bluetooth |headphones?'?s? |headset |earbuds?'? |wireless )+(?:mic|microphone)s?|(?:mic|microphone) on (?:my )?(?:headphones|headset|earbuds)",
        BrickSetting.OVERLAY to "(?:the )?(?:floating card|floating assistant|pop ?up card|overlay|card over (?:other )?apps|answer(?:ing)? over (?:other )?apps)",
        BrickSetting.BRAND_GLOW to "(?:the )?(?:app|brand(?:ed)?|bottom|spotify|youtube|maps|colou?red|branded bottom) (?:animations?|glows?|effects?)",
        BrickSetting.ROBOT_VOICE to "(?:the )?robot(?:ic)? (?:unit )?voice|robot unit|(?:the )?robot sound",
    )

    fun parseSetting(s: String): Request? {
        val t = s.lowercase().replace(re("^(?:can you |could you |please )+"), "").trim()
        // Units: "use Celsius", "switch to Fahrenheit", "show the temperature in Celsius"
        rx("^(?:use|switch to|change to|set (?:the )?(?:units?|temperature|weather) to|show (?:the )?(?:temperature|weather) in|change (?:the )?(?:units?|temperature) to|put (?:the )?(?:temperature|weather) in)\\s+(?:degrees )?(celsius|centigrade|metric|fahrenheit|imperial)(?: (?:units|degrees|for (?:the )?weather))?$").find(t)?.let {
            return Request.Setting(BrickSetting.FAHRENHEIT, it.groupValues[1].startsWith("f") || it.groupValues[1] == "imperial")
        }
        val on = "turn on|switch on|enable|activate|start|start up|re ?enable|unmute|bring back|use"
        val off = "turn off|switch off|disable|deactivate|stop|kill|shut off|mute|pause|get rid of"
        for ((setting, names) in settingNames) {
            rx("^(?:($on)|($off))\\s+(?:the |my )?(?:$names)(?:\\s+(?:feature|setting|option|mode))?(?:\\s+(?:in )?(?:brick|settings|brick settings))?$").find(t)?.let {
                return Request.Setting(setting, it.groupValues[1].isNotEmpty())
            }
            rx("^(?:turn |switch |set )?(?:the |my )?(?:$names)(?:\\s+(?:feature|setting|option|mode))?\\s+(on|off)$").find(t)?.let {
                return Request.Setting(setting, it.groupValues[1] == "on")
            }
            rx("^(?:stop|quit|don't keep|no more) (?:listening for hey brick|always listening)$").find(t)?.let { return Request.Setting(BrickSetting.WAKE_WORD, false) }
        }
        return null
    }

    // ---------------------------------------------------------------- Optic (edge visualizer)

    /** How you name Optic's visualizer: "optic", "the visualizer", "the music visualizer", "optic's visualizer". */
    private const val VIZ = "(?:the |my )?(?:optic(?:'s)?(?: (?:edge |music |audio )?visuali[sz]er)?(?: app)?|(?:edge |music |audio |screen |sound )?visuali[sz]er)"

    private val vizDesigns = listOf(
        VizDesign.EDGE_GLOW to "edge glow|glow|edge|glowing edges?|intro",
    )

    private val vizColors = listOf(
        VizColors.ALBUM to "(?:the )?(?:album|song|cover|track)(?: art(?:work)?| cover)?(?: colou?rs?)?|what's playing",
        VizColors.BRICK to "(?:my|your|brick(?:'s)?|the brick|brick's own|the app's) colou?rs?|(?:my|your|the brick) (?:palette|theme)|brick",
        VizColors.SPECTRUM to "(?:a |the )?(?:rainbow|spectrum|full spectrum|all (?:the )?colou?rs)(?: colou?rs)?",
        VizColors.GRADIENT to "(?:a |the )?gradient",
        VizColors.PALETTE to "(?:the )?palette(?: colou?rs)?",
        VizColors.SINGLE to "(?:one|a single|single|a solid|solid) colou?r",
    )

    fun parseVisualizer(s: String): Request? {
        val t = s.lowercase().replace(re("^(?:can you |could you |please )+"), "").trim()
        if (!rx("optic|visuali[sz]er").containsMatchIn(t)) return null
        val design = vizDesigns.joinToString("|") { "(?:${it.second})" }
        fun designOf(w: String) = vizDesigns.firstOrNull { rx("^(?:${it.second})$").matches(w) }?.first
        fun colorsOf(w: String) = vizColors.firstOrNull { rx("^(?:${it.second})$").matches(w) }?.first
        val on = "turn on|switch on|enable|start|start up|activate|show|bring back|resume|unhide|fire up"
        val off = "turn off|switch off|disable|stop|deactivate|hide|kill|pause|shut off|get rid of"

        // On / off
        rx("^(?:($on)|($off))\\s+$VIZ$").find(t)?.let { return Request.Visualizer(on = it.groupValues[1].isNotEmpty()) }
        rx("^(?:turn |switch |set )?$VIZ\\s+(on|off|back on)$").find(t)?.let { return Request.Visualizer(on = it.groupValues[1] != "off") }

        // Only while music plays / all the time
        if (rx("^(?:only (?:show|run|use) $VIZ|(?:show|run) $VIZ only|make $VIZ only (?:show|run|appear)) (?:when|while|if) (?:music|something|media|a song|audio) (?:is )?(?:playing|on)$").matches(t)) {
            return Request.Visualizer(onlyMedia = true)
        }
        if (rx("^(?:always (?:show|run) $VIZ|(?:show|run) $VIZ (?:all the time|always|even when nothing'?s? playing))$").matches(t)) {
            return Request.Visualizer(onlyMedia = false)
        }

        // Sensitivity: "make the visualizer more sensitive", "turn up optic's sensitivity"
        rx("^(?:make|set) $VIZ\\s+(more|less) (?:sensitive|reactive|responsive|jumpy|bouncy)$").find(t)?.let {
            return Request.Visualizer(sensitive = if (it.groupValues[1] == "more") 1 else -1)
        }
        val up = "increase|raise|turn up|bump up|boost|up|crank up|higher"
        val down = "decrease|lower|turn down|reduce|drop|down"
        rx("^(?:($up)|($down)) (?:the )?(?:$VIZ(?:'s)? )?sensitivity(?: (?:of|on|for|in) $VIZ)?$").find(t)?.let {
            return Request.Visualizer(sensitive = if (it.groupValues[1].isNotEmpty()) 1 else -1)
        }
        rx("^$VIZ(?:'s)? sensitivity (up|down|higher|lower)$").find(t)?.let {
            return Request.Visualizer(sensitive = if (it.groupValues[1] == "up" || it.groupValues[1] == "higher") 1 else -1)
        }

        // Design: "switch the visualizer to edge glow" (Optic has one design; its look is tuned in Optic)
        rx("^(?:switch|change|set|make|put|turn) $VIZ(?:'s)?(?: (?:design|style|look|mode|effect|animation))? (?:to|into) (?:the |a |an )?($design)(?: (?:design|style|look|one|mode|effect|animation|visuali[sz]er))?$").find(t)?.let { m ->
            designOf(m.groupValues[1])?.let { return Request.Visualizer(design = it) }
        }
        rx("^(?:use|try|show|give me|switch to|change to|put on) (?:the |a |an )?($design)(?: (?:design|style|look|one|mode|effect|animation))?(?: (?:visuali[sz]er|on $VIZ|for $VIZ|in $VIZ))$").find(t)?.let { m ->
            designOf(m.groupValues[1])?.let { return Request.Visualizer(design = it) }
        }

        // Colors: "make the visualizer use the album art colors", "set optic's colors to rainbow"
        val colors = vizColors.joinToString("|") { "(?:${it.second})" }
        rx("^(?:make|set|change|switch|turn|have) $VIZ(?:'s)?(?: colou?rs?)? (?:to|into|use|match|follow|show|be) ($colors)$").find(t)?.let { m ->
            colorsOf(m.groupValues[1])?.let { return Request.Visualizer(colors = it) }
        }
        rx("^(?:colou?r|use) ($colors) (?:on|for|in) $VIZ$|^colou?r $VIZ (?:by|with|from|using) ($colors)$").find(t)?.let { m ->
            colorsOf(m.groupValues[1].ifEmpty { m.groupValues[2] })?.let { return Request.Visualizer(colors = it) }
        }

        // What's it set to: "is the visualizer on", "what's optic set to", "what design is the visualizer using"
        if (rx("^(?:is $VIZ (?:on|off|running|working|enabled)|(?:what's|what is|how's|how is) $VIZ(?: (?:set to|doing|using|set up|looking like|on))?|what (?:design|style|colou?rs?|mode) (?:is|does) $VIZ(?: (?:using|use|on|set to|have))?|$VIZ status)$").matches(t)) {
            return Request.Visualizer()
        }
        return null
    }

    // ---------------------------------------------------------------- Food and shopping

    private const val CUISINE = "chinese|mexican|italian|thai|japanese|indian|korean|vietnamese|greek|mediterranean|french|" +
        "american|cajun|creole|caribbean|jamaican|cuban|spanish|middle eastern|lebanese|turkish|persian|ethiopian|" +
        "african|peruvian|brazilian|filipino|hawaiian|soul food|southern|tex mex|asian|thai|german|polish|halal|kosher|" +
        "vegan|vegetarian|gluten free"
    private const val DISH = "pizza|pizzas|sandwich|sandwiches|subs?|hoagies?|burgers?|hamburgers?|tacos?|burritos?|wings|" +
        "chicken|fried chicken|chicken wings|seafood|fish|crab|crawfish|oysters?|lobster|steaks?|steakhouse|bbq|barbecue|" +
        "sushi|ramen|pho|noodles|dumplings|dim sum|hot pot|kbbq|curry|kebabs?|falafel|shawarma|gyros?|pasta|salads?|" +
        "soup|breakfast|brunch|lunch|dinner|bagels?|donuts?|doughnuts?|pastries|pastry|bakery|bakeries|desserts?|" +
        "ice cream|frozen yogurt|froyo|gelato|cupcakes?|cake|cookies|coffee|espresso|latte|tea|boba|bubble tea|smoothies?|" +
        "juice|food|fast food|takeout|take out|eats?|a bite|something to eat|restaurants?|diners?|cafes?|caf[eé]s?|" +
        "delis?|bars?|pubs?|breweries|brewery|wine bars?|cocktails?|drinks|beer|buffets?|food trucks?|hot dogs?|" +
        "cheesesteaks?|po boys?|fries|nachos|poke|acai|crepes|waffles|pancakes|bistros?|eateries|eatery|grill"
    private const val SHOP = "stores?|shops?|shopping|malls?|outlets?|boutiques?|markets?|supermarkets?|groceries|grocery(?: stores?)?|" +
        "clothes|clothing|apparel|shoes|sneakers|electronics|computers?|phones?|hardware|tools|furniture|home goods|" +
        "pharmacy|pharmacies|drug ?stores?|thrift(?: stores?| shops?)?|bookstores?|books|gifts?|jewelry|jewellery|" +
        "sporting goods|toys|toy stores?|pet stores?|pet supplies|department stores?|convenience stores?|beauty supplies|" +
        "makeup|cosmetics|games|video games|records|vinyl|music stores?|instruments|bikes?|bike shops?|auto parts|" +
        "flowers|florists?|art supplies|crafts|office supplies|liquor(?: stores?)?|wine shops?|vape shops?|smoke shops?|" +
        "dollar stores?"

    private const val SERVICE = "gas|gas stations?|barbers?|barbershops?|barber shops?|salons?|hair salons?|nail salons?|gyms?|" +
        "laundromats?|car wash(?:es)?|mechanics?|auto shops?|banks?|atms?|post offices?|ev chargers?|charging stations?|" +
        "parking|hotels?|motels?|vets?|urgent care|hospitals?|dentists?"

    private fun kindOf(what: String): FindKind? = when {
        rx("\\b(?:coffee|tea|boba|pizza|donut|doughnut|bagel|sandwich|sub|cake|cupcake|ice cream|juice|smoothie|sushi|burger|taco|wine|food)\\s+(?:shops?|stores?|places?|bars?|trucks?|joints?|halls?)\\b").containsMatchIn(what) -> FindKind.FOOD
        rx("^(?:$SERVICE)$").containsMatchIn(what) -> FindKind.OTHER
        rx("\\b(?:stores?|shops?|malls?|outlets?|boutiques?|markets?|supermarkets?|groceries|grocery)$").containsMatchIn(what) -> FindKind.SHOPPING
        rx("\\b(?:$DISH|$CUISINE)\\b").containsMatchIn(what) -> FindKind.FOOD
        rx("\\b(?:$SHOP)\\b").containsMatchIn(what) -> FindKind.SHOPPING
        rx("\\b(?:$SERVICE)\\b").containsMatchIn(what) -> FindKind.OTHER
        else -> null
    }
    fun parseFindPlace(s: String): Request? {
        if (rx("^(?:play|put on|listen to|shuffle|queue|add|text|call|set|remind|navigate|directions|how do i make|recipe)\\b").containsMatchIn(s)) return null
        if (rx("\\b(?:recipe|recipes|song|songs|playlist|album|video|videos|youtube|spotify|lyrics|calories|my phone|my keys|my wallet)\\b").containsMatchIn(s)) return null
        // "Where's the nearest gas station" stays a map search for the closest one.
        if (rx("\\b(?:nearest|closest)\\b").containsMatchIn(s)) return null
        var t = s.lowercase().replace(re("[?!.,]"), " ").replace(re("\\s+"), " ").trim()
        // I'm hungry / starving: food near you.
        if (rx("^(?:i'm|i am|we're|we are) (?:so |really |super )?(?:hungry|starving|famished)(?: (?:what's|what is) (?:good|nearby|around))?$").matches(t)) {
            return Request.FindPlace("food", FindKind.FOOD, null, true)
        }
        // Where it is: near you (the default) or a named place ("in Chicago", "near downtown", "around Austin").
        var area: String? = null
        rx("\\s+(?:near|around|close to|by)\\s+(?:me|here|us|my (?:location|house|place|home))$|\\s+(?:nearby|close by|around here|in (?:my|the) area|in town|near by)$|\\s+(?:that (?:are|is)|which (?:are|is))? ?(?:open (?:now|right now|late|today))$").find(t)?.let { t = t.substring(0, it.range.first) }
        rx("\\s+(?:in|near|around|close to|by|at|over in|out in|downtown)\\s+((?!the (?:area|mood)\\b)[a-z0-9' .-]{2,40})$").find(t)?.let { m ->
            val a = m.groupValues[1].trim()
            if (!rx("^(?:a while|a bit|mind|the mood|stock|general|particular|person)$").matches(a)) { area = a; t = t.substring(0, m.range.first) }
        }
        t = t.replace(rx("\\s+(?:nearby|close by|around here|near me|to eat|to go|for (?:lunch|dinner|breakfast|brunch)|tonight|today|right now|now)$"), "").trim()
        val verb = rx("^(?:can you |could you |please |hey brick |brick )*(?:find|find me|find us|look for|looking for|search for|search|show me|show|get me|get us|recommend|recommend me|suggest|suggest me|any|i want|i wanna|i want some|i need|i need some|i'm craving|i am craving|craving|i'm in the mood for|i'm hungry for|i feel like(?: having| eating| getting)?|i could go for|let's get|let's go get|where can i (?:get|find|buy|grab|eat|go for|shop for|pick up)|where (?:should|can|could|do) (?:i|we) (?:get|eat|grab|go for|buy|shop for|find|go)(?: to)?(?: get| eat| buy)?|where(?:'s| is| are) (?:a |an |the |some )?(?:good |best |nearest |closest )?|what(?:'s| is| are) (?:a |an |the |some )?(?:good|best|nearest|closest|top)|who (?:has|makes|sells) (?:the )?(?:best|good|a good)?|places? to (?:get|eat|buy|shop for)|spots? to (?:get|eat))\\b\\s*")
        val vm = verb.find(t) ?: return null
        var what = t.substring(vm.range.last + 1).trim()
        val bestRx = rx("\\b(?:good|great|nice|decent|best|the best|top|top rated|highly rated|popular|tasty|delicious|amazing|solid|legit)\\b")
        val best = bestRx.containsMatchIn(t)
        what = what.replace(rx("^(?:me|us)\\s+"), "")
            .replace(rx("^(?:a|an|some|the|any|good|great|nice|decent|best|top|top rated|highly rated|popular|tasty|delicious|amazing|solid|legit|cheap|affordable|local|nearby|nearest|closest|new|place|places|spot|spots|for|to eat|to get|options)\\s+"), "")
        repeat(4) {
            what = what.replace(rx("^(?:a|an|some|the|any|good|great|nice|decent|best|top|top rated|highly rated|popular|tasty|delicious|amazing|solid|legit|cheap|affordable|local|nearby|nearest|closest|new|place|places|spot|spots|for|to eat|to get|options|of|with)\\s+"), "")
        }
        what = what.replace(rx("\\s+(?:places?|spots?|joints?|options?|around|at)$"), "").trim()
        if (what.isBlank() || what.split(" ").size > 5) return null
        val kind = kindOf(what) ?: return null
        return Request.FindPlace(what, kind, area, best)
    }

    private fun parseNavigation(s: String): Request? {
        fun dest(raw: String) = raw.replace(rx("^(?:the|my)\\s+"), "").replace(rx("\\s+(?:please|now)$"), "").trim()

        rx("^(?:navigate|navigation|drive|take me|bring me|get me|guide me|route me|give me directions|get directions|directions|show me (?:the )?(?:way|route|directions))\\s+(?:to\\s+)?(.+)$")
            .find(s)?.let { m -> dest(m.groupValues[1]).takeIf { it.isNotBlank() }?.let { return Request.Navigate(it, true) } }
        rx("^how (?:do|can|would) i (?:get|drive|walk) to\\s+(.+)$")
            .find(s)?.let { m -> return Request.Navigate(dest(m.groupValues[1]), true) }
        rx("^(?:where(?:'s| is)|find|show me|search for|look for|locate)\\s+(?:the\\s+|a\\s+)?(?:nearest|closest)\\s+(.+)$")
            .find(s)?.let { m -> return Request.Navigate(dest(m.groupValues[1]) + " near me", false) }
        rx("^(?:find|show me|search for|look for|where are|where is|are there)?\\s*(?:a\\s+|some\\s+|any\\s+)?(.+?)\\s+(?:near me|nearby|around here)$")
            .find(s)?.let { m -> return Request.Navigate(dest(m.groupValues[1]) + " near me", false) }
        rx("^(?:show|open|find|look up|search)\\s+(.+?)\\s+(?:on|in)\\s+(?:the\\s+)?(?:map|maps|google maps)$")
            .find(s)?.let { m -> return Request.Navigate(dest(m.groupValues[1]), false) }
        return null
    }

    private fun placeName(raw: String) = raw
        .replace(rx("^(?:the|my|that|this)\\s+"), "")
        .replace(rx("^(?:nearest|closest|nearby|local)\\s+"), "")
        .replace(rx("\\s+(?:near me|nearby|around here|by me|close to me)$"), "")
        .trim()

    fun parseHours(s: String): Request? {
        if (rx("^(?:play|put on|listen to|shuffle|how many|set|start)\\b").containsMatchIn(s)) return null
        val tomorrow = rx("\\btomorrow\\b").containsMatchIn(s)
        val t = s.replace(rx("\\s+(?:today|tomorrow|tonight|right now|now|yet|still|on (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))$"), "")
            .replace(rx("\\s+(?:today|tomorrow|tonight)$"), "")
        fun make(place: String, ask: HoursAsk) = placeName(place).takeIf { it.isNotBlank() }?.let { Request.PlaceHours(it, ask, tomorrow) }
        rx("^(?:when|what time)\\s+(?:does|do|will|is|are)\\s+(.+?)\\s+(open|opening|close|closing)(?:\\s+up)?$").find(t)?.let { m ->
            val ask = if (m.groupValues[2].startsWith("open", true)) HoursAsk.OPENS else HoursAsk.CLOSES
            return make(m.groupValues[1], ask)
        }
        rx("^how late (?:is|are)\\s+(.+?)\\s+open$").find(t)?.let { return make(it.groupValues[1], HoursAsk.CLOSES) }
        rx("^(?:is|are)\\s+(.+?)\\s+(?:still\\s+)?(?:open|closed)(?:\\s+(?:right now|now|today|tomorrow|tonight|yet|late))?$").find(t)?.let {
            return make(it.groupValues[1], HoursAsk.IS_OPEN)
        }
        rx("^(?:what are|what're|tell me|give me|get|show me|check|look up|find)?\\s*(?:the\\s+)?(?:opening |business |store |open )?hours\\s+(?:for|of|at)\\s+(.+)$").find(t)?.let {
            return make(it.groupValues[1], HoursAsk.HOURS)
        }
        rx("^(?:what are|what're|tell me|check|look up|find)?\\s*(.+?)(?:'s)?\\s+(?:opening |business |store |open )?hours$").find(t)?.let {
            if (!rx("^(?:the|what|what are)$").matches(it.groupValues[1])) return make(it.groupValues[1], HoursAsk.HOURS)
        }
        return null
    }

    fun parsePhotoSpots(s: String): Request? {
        val photo = rx("\\b(?:photo(?:graphy)?|photographic|picture|pic|photogenic|scenic|instagram(?:mable|able)?)\\s+(?:spots?|locations?|places?|areas?|views?|destinations?)\\b")
            .containsMatchIn(s) ||
            rx("\\b(?:places?|spots?|somewhere|where)\\b.*\\b(?:to|can i|should i)\\s+(?:take|shoot|get)\\s+(?:some\\s+)?(?:good\\s+|nice\\s+|great\\s+)?(?:photos|pictures|pics|photographs|shots)\\b").containsMatchIn(s)
        val subject = rx("^(?:show me|find|find me|where are|are there|look for|search for|any|list)?\\s*(?:some\\s+|any\\s+|good\\s+|the\\s+best\\s+|beautiful\\s+|pretty\\s+|nice\\s+)*(waterfalls?|falls|wildflowers?|flower fields?|botanical gardens?|gardens?|scenic (?:views?|overlooks?|landscapes?)|overlooks?|viewpoints?|landscapes?)\\b.*(?:near me|nearby|around here|radius|within|miles?|kilometers?|km)").containsMatchIn(s)
        if (!photo && !subject || rx("^(?:play|put on|listen to|shuffle)\\b").containsMatchIn(s)) return null
        val kind = when {
            rx("\\b(?:waterfalls?|falls|cascades?)\\b").containsMatchIn(s) -> SpotKind.WATERFALL
            rx("\\b(?:flora|flowers?|wildflowers?|gardens?|botanical|blooms?|plants?|forests?)\\b").containsMatchIn(s) -> SpotKind.FLORA
            rx("\\b(?:landscapes?|scenic views?|views?|overlooks?|viewpoints?|vistas?|mountains?|sunsets?|sunrises?)\\b").containsMatchIn(s) -> SpotKind.LANDSCAPE
            else -> SpotKind.ANY
        }
        val r = rx("(\\d+(?:\\.\\d+)?)\\s*-?\\s*(miles?|mi|kilometers?|kilometres?|km)\\b").find(s)
        val miles = r?.let {
            val n = it.groupValues[1].toDouble()
            if (it.groupValues[2].startsWith("k", true)) n / 1.609 else n
        }?.toInt()?.coerceIn(1, PhotoSpots.MAX_MILES) ?: 25
        return Request.PhotoSpots(miles, kind)
    }

    // ---------------------------------------------------------------- Timer

    // ---------------------------------------------------------------- Notes, reading, YouTube

    /** Notes keep the original wording (case, punctuation) of what follows the trigger. */
    fun parseNote(raw: String, s: String): Request? {
        val trigger = rx("^(?:(?:create|make|take|start|write|add|jot down|new)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?(?:new\\s+|quick\\s+)?notes?|note to self|take notes?|jot (?:this|that) down):?(?:\\s+(?:that says|saying|to say|that|about|with|reading))?(?:\\s+(.*))?$")
        val m = trigger.find(s) ?: return null
        val rest = m.groupValues[1].trim()
        if (rest.isEmpty()) return Request.Note(null)
        // Take the same words from the raw input so capitalization and punctuation survive.
        val r = raw.trim()
        val idx = r.lowercase().indexOf(rest.lowercase().take(12))
        val text = (if (idx >= 0) r.substring(idx) else rest).trim().trimEnd('.').trim()
        return Request.Note(text.replaceFirstChar { it.uppercase() })
    }

    /** "Reply to Alex on Discord saying I'm on my way", "read my Discord messages", "what did Sam say on Discord". */
    fun parseDiscord(raw: String): Request? {
        val s = clean(raw)
        if (!rx("\\bdiscord\\b").containsMatchIn(s)) return null
        fun who(w: String) = w.trim().replace(rx("^(?:to |the |my )+"), "").trim().takeUnless { it.isBlank() || rx("^(?:them|him|her|that|this|it|me|someone|people|my (?:last |latest )?(?:message|dm))$").matches(it) }
        fun body(b: String?) = b?.trim()?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        val say = "(?:saying|that says|and say|and tell (?:him|her|them)|telling (?:him|her|them)|with|that)"
        // Reply / message someone: "reply to Alex on discord saying ...", "tell Sam on discord I'm late"
        rx("^(?:reply|respond|answer|write back|send (?:a )?(?:discord )?(?:message|dm)|message|dm|tell|text)\\s+(?:back\\s+)?(?:to\\s+)?(.+?)\\s+(?:on|in|over|through|via|using)\\s+discord(?:\\s+$say\\s*(.*)|\\s+(.+))?$").find(s)?.let { m ->
            return Request.Discord(true, who(m.groupValues[1]), body(m.groupValues[2].ifBlank { m.groupValues[3] }))
        }
        // "discord Alex saying hi", "send a discord message to Alex saying hi", "reply on discord saying ok"
        rx("^(?:discord|send (?:a )?discord (?:message|dm)(?: to)?|reply (?:to (?:my |the )?(?:last |latest )?)?(?:on |in )?discord(?: message| dm)?(?: from)?|discord (?:reply|message|dm)(?: to)?)(?:\\s+(.*?))??(?:\\s+$say\\s+(.*))?$").find(s)?.let { m ->
            val w = m.groupValues[1]
            val b = m.groupValues[2]
            if (s.startsWith("reply") || s.startsWith("send") || s.startsWith("discord ") && (b.isNotBlank() || w.isNotBlank())) {
                return Request.Discord(true, who(w), body(b))
            }
        }
        // Read: "read my discord messages", "any new discord dms", "discord messages from Alex", "what did Alex say on discord"
        rx("^(?:what did|what's|what has|has|did)\\s+(.+?)\\s+(?:say|said|send|sent|write|wrote|message|messaged)(?: me)?\\s+(?:on|in)\\s+discord$").find(s)?.let { m ->
            return Request.Discord(false, who(m.groupValues[1]), null)
        }
        rx("^(?:read|check|show|any|do i have any|did i get any|what are|go through|catch me up on|open)?\\s*(?:me\\s+)?(?:my\\s+)?(?:new\\s+|unread\\s+|latest\\s+|recent\\s+)?discord(?:\\s+(?:messages?|dms?|notifications?|pings?|chats?|mentions?))?(?:\\s+from\\s+(.+))?$").find(s)?.let { m ->
            if (s.startsWith("open")) return null
            return Request.Discord(false, who(m.groupValues[1]), null)
        }
        return null
    }

    private val readNotAsk = Regex(
        "^(?:remind|set|add|create|make|note|write|send|compose|draft|reply|respond|forward|delete|archive|clear|dismiss|" +
            "mute|silence|block|turn|switch|disable|enable|stop|allow|open|launch|start|search|google|look up|text|call|tell \\w+ )\\b",
        IC,
    )
    private val emailWord = Regex("\\b(?:e ?mails?|mails?|inbox|gmail|outlook)\\b", IC)
    private val noticeWord = Regex("\\b(?:notifications?|notifs?|alerts?)\\b", IC)
    private val readIntent = Regex(
        "\\b(?:read|check|show|tell|what|what's|whats|which|any|anything|do i have|have i got|did i get|did i receive|got|" +
            "get|new|unread|latest|last|recent|go through|catch me up|how many|is there|are there|who|see|list|pending|waiting|missed)\\b",
        IC,
    )
    private val readFiller = Regex("\\s+(?:please|today|this morning|this afternoon|tonight|yesterday|lately|recently|yet|so far|right now|now)$", IC)
    private val nobody = Regex("^(?:anyone|anybody|someone|somebody|people|any(?:one)? new)$", IC)

    /** "from John (today)" -> "John". */
    private fun fromWho(s: String): String? {
        val m = rx("\\bfrom\\s+(?:the\\s+)?(.+)$").find(s) ?: return null
        var w = m.groupValues[1].trim()
        while (true) {
            val t = w.replace(readFiller, "").trim()
            if (t == w) break
            w = t
        }
        return w.removeSuffix(" app").trim().takeUnless { it.isBlank() || nobody.matches(it) }
    }

    /**
     * Reading notifications and email, phrased loosely: "any new emails", "did John email me", "what's in my
     * inbox", "do I have any notifications", "any Instagram notifications", "what did I miss", "catch me up".
     */
    private fun parseNotices(s: String): Request? {
        if (readNotAsk.containsMatchIn(s) || rx("\\b(?:settings?|calendar|reminders?|timers?|alarms?|do not disturb|dnd)\\b").containsMatchIn(s)) return null
        // "did John email me", "has my boss emailed me today", "who emailed me"
        rx("^(?:did|has|have)\\s+(.+?)\\s+(?:e ?mail(?:ed)?|sent me (?:an? |any )?e ?mails?)(?:\\s+me)?(?:\\s+(?:back|today|yet|lately|recently|this morning))?$").find(s)?.let { m ->
            val who = m.groupValues[1].trim().takeUnless { nobody.matches(it) }
            return Request.ReadNotifications(email = true, from = who)
        }
        if (rx("^who(?:'s| has| have)?\\s+(?:e ?mailed|sent me (?:an? |any )?e ?mails?)(?:\\s+me)?(?:\\s+.*)?$").matches(s)) {
            return Request.ReadNotifications(email = true, from = null)
        }
        // Just the word: "emails", "my inbox", "notifications please"
        val bare = s.replace(rx("^(?:my|the|me|me my)\\s+"), "").replace(readFiller, "")
        if (rx("^(?:new\\s+|unread\\s+)?(?:e ?mails?|mail|inbox|gmail)$").matches(bare)) return Request.ReadNotifications(true, null)
        if (rx("^(?:new\\s+)?(?:notifications?|notifs?|alerts)$").matches(bare)) return Request.ReadNotifications(false, null)
        if (emailWord.containsMatchIn(s) && readIntent.containsMatchIn(s)) {
            return Request.ReadNotifications(email = true, from = fromWho(s))
        }
        if (noticeWord.containsMatchIn(s) && readIntent.containsMatchIn(s)) {
            // "any instagram notifications" -> from Instagram
            val app = rx("\\b([a-z][a-z0-9+&' ]{1,24}?)\\s+(?:notifications?|notifs?|alerts?)\\b").find(s)?.groupValues?.get(1)
                ?.split(' ')?.lastOrNull()
                ?.takeUnless { rx("^(?:my|new|any|unread|latest|recent|the|all|missed|pending|of|some|last|read|check|show|are|i|have|got|get|what|which)$").matches(it) }
            return Request.ReadNotifications(email = false, from = fromWho(s) ?: app)
        }
        // No keyword at all, but clearly about what came in.
        if (rx("^(?:what did i miss|what have i missed|did i miss anything|anything i missed|catch me up|fill me in|" +
                "what's new(?: on my phone)?|anything new(?: on my phone)?|what came in|what's come in|what has come in|" +
                "check my phone|what's (?:going on|happening) (?:on|with) my phone|anything (?:important|interesting)(?: on my phone)?)$").matches(s.replace(readFiller, ""))) {
            return Request.ReadNotifications(email = false, from = null)
        }
        return null
    }

    fun parseReading(s: String): Request? {
        parseNotices(s)?.let { return it }
        if (rx("^(?:what's|what is|when's|when is)\\s+my\\s+next\\s+(?:event|meeting|appointment|thing)(?:\\s+on my calendar)?$").matches(s)) {
            return Request.ReadCalendar(CalendarRange.NEXT)
        }
        val cal = rx("^(?:what's|what is|what do i have|what have i got|read|check|show|tell me|anything|is there anything|do i have anything|do i have any|what are)\\b.*\\b(?:calendar|schedule|agenda|events?|appointments?|meetings?|plans?|planned)\\b.*$|^do i have (?:anything|plans) (?:today|tomorrow|this week)$|^what (?:do i have|have i got)(?: going on)? (?:today|tomorrow|this week)$")
        if (cal.matches(s) && !rx("\\b(?:add|create|schedule a|book|set up|new)\\b").containsMatchIn(s)) {
            val range = when {
                rx("\\btomorrow\\b").containsMatchIn(s) -> CalendarRange.TOMORROW
                rx("\\b(?:this week|week|next few days|coming up)\\b").containsMatchIn(s) -> CalendarRange.WEEK
                rx("\\bnext\\b").containsMatchIn(s) -> CalendarRange.NEXT
                else -> CalendarRange.TODAY
            }
            return Request.ReadCalendar(range)
        }
        return null
    }

    // ---------------------------------------------------------------- Time, device, apps

    fun parseTime(s: String): Request? {
        if (rx("^(?:what(?:'s| is) (?:the |today's )?date(?: today)?|what day is (?:it|today)|what(?:'s| is) today(?:'s date)?|today's date|what is the date today|what's the day)$").matches(s)) {
            return Request.TellTime(date = true, place = null)
        }
        rx("^(?:what(?:'s| is) the time|what time is it|tell me the time|the time|time|current time|do you have the time|what's the current time)(?:\\s+(?:now|right now))?(?:\\s+in\\s+([a-z][a-z .'-]+))?$").find(s)?.let {
            return Request.TellTime(date = false, place = it.groupValues[1].trim().ifBlank { null })
        }
        rx("^what time is it (?:now |right now )?in\\s+([a-z][a-z .'-]+)$").find(s)?.let { return Request.TellTime(false, it.groupValues[1].trim()) }
        return null
    }

    fun parseDevice(s: String): Request? {
        val light = "(?:flashlight|flash light|torch|the light|the flash)"
        rx("^(?:turn|switch|flip)\\s+(on|off)\\s+(?:the\\s+|my\\s+)?$light$").find(s)?.let {
            return Request.Device(if (it.groupValues[1].equals("on", true)) DeviceOp.FLASH_ON else DeviceOp.FLASH_OFF)
        }
        rx("^(?:turn|switch|flip)\\s+(?:the\\s+|my\\s+)?$light\\s+(on|off)$|^$light\\s+(on|off)$").find(s)?.let {
            val w = it.groupValues[1].ifBlank { it.groupValues[2] }
            return Request.Device(if (w.equals("on", true)) DeviceOp.FLASH_ON else DeviceOp.FLASH_OFF)
        }
        if (rx("^(?:toggle\\s+(?:the\\s+)?$light|$light|lumos|nox|i need (?:a |some )?light)$").matches(s)) {
            return Request.Device(if (s.equals("nox", true)) DeviceOp.FLASH_OFF else if (s.equals("lumos", true)) DeviceOp.FLASH_ON else DeviceOp.FLASH_TOGGLE)
        }
        // Volume
        rx("^(?:set|turn|put|change)\\s+(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+to\\s+(\\d{1,3})(?:\\s*(?:percent|%))?$|^volume\\s+(?:to\\s+)?(\\d{1,3})(?:\\s*(?:percent|%))?$").find(s)?.let {
            return Request.Device(DeviceOp.VOLUME_SET, (it.groupValues[1].ifBlank { it.groupValues[2] }).toInt().coerceIn(0, 100))
        }
        if (rx("^(?:turn\\s+(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+up|volume\\s+up|turn it up|louder|make it louder|raise the volume|increase the volume)$").matches(s)) return Request.Device(DeviceOp.VOLUME_UP)
        if (rx("^(?:turn\\s+(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+down|volume\\s+down|turn it down|quieter|make it quieter|lower the volume|decrease the volume)$").matches(s)) return Request.Device(DeviceOp.VOLUME_DOWN)
        if (rx("^(?:max(?:imum)? volume|turn (?:the volume|it) all the way up|volume (?:to )?max(?:imum)?)$").matches(s)) return Request.Device(DeviceOp.VOLUME_MAX)
        if (rx("^mute(?:\\s+(?:the\\s+)?(?:volume|sound|media|music|audio))?$").matches(s)) return Request.Device(DeviceOp.MUTE)
        if (rx("^unmute(?:\\s+(?:the\\s+)?(?:volume|sound|media|music|audio))?$").matches(s)) return Request.Device(DeviceOp.UNMUTE)
        // Ringer and Do Not Disturb
        rx("^(?:put|set|switch|turn)\\s+(?:my\\s+|the\\s+)?(?:phone|ringer)\\s+(?:on|to|into)\\s+(vibrate|vibration|silent|silence|normal|ring|sound|loud)(?:\\s+mode)?$|^(vibrate|silent|normal|sound) mode(?: on)?$").find(s)?.let {
            val w = it.groupValues[1].ifBlank { it.groupValues[2] }.lowercase()
            return Request.Device(when {
                w.startsWith("vibrat") -> DeviceOp.RING_VIBRATE
                w.startsWith("silen") -> DeviceOp.RING_SILENT
                else -> DeviceOp.RING_NORMAL
            })
        }
        if (rx("^(?:silence|mute) (?:my |the )?(?:phone|ringer)$").matches(s)) return Request.Device(DeviceOp.RING_SILENT)
        rx("^(?:turn|switch)\\s+(on|off)\\s+do not disturb$|^(?:turn|switch)\\s+do not disturb\\s+(on|off)$|^do not disturb\\s+(on|off)$").find(s)?.let {
            val w = listOf(1, 2, 3).map { i -> it.groupValues[i] }.first { g -> g.isNotBlank() }
            return Request.Device(if (w.equals("on", true)) DeviceOp.DND_ON else DeviceOp.DND_OFF)
        }
        if (rx("^(?:enable|start) do not disturb$|^do not disturb$").matches(s)) return Request.Device(DeviceOp.DND_ON)
        // Bluetooth device battery ("what's the battery on my earbuds", "how much battery do my headphones have")
        val btDev = "(headphones?|earbuds?|ear ?buds?|buds|airpods|headset|speakers?|watch|controllers?|bluetooth(?: devices?)?)"
        if (rx("\\b(?:battery|charge)\\b").containsMatchIn(s) && rx("\\b$btDev\\b").containsMatchIn(s)) {
            return Request.Device(DeviceOp.BT_BATTERY, target = btTarget(s))
        }
        // A device by name: "what's the battery of my galaxy buds", "how much charge does my sony have"
        rx("^(?:what(?:'s| is)|check|how(?:'s| is)|tell me)\\s+(?:the\\s+)?(?:battery|charge)(?:\\s+(?:level|percentage|life))?\\s+(?:of|on|for|in)\\s+(?:my|the)\\s+(.+)$|" +
            "^(?:what(?:'s| is)|check|how(?:'s| is))\\s+(?:my|the)\\s+(.+?)(?:'s)?\\s+battery(?:\\s+(?:level|at|life))?$|" +
            "^how much (?:battery|charge) (?:do|does|is) (?:my|the) (.+?)(?: have| got| left| at)*$").find(s)?.let { m ->
            val name = (1..3).map { m.groupValues[it] }.first { it.isNotBlank() }.trim()
            return if (rx("^(?:phone|cell(?: phone)?|mobile|device|s25(?: ultra)?|galaxy|samsung)$").matches(name)) Request.Device(DeviceOp.BATTERY)
            else Request.Device(DeviceOp.BT_BATTERY, target = name.lowercase())
        }
        // Battery
        if (rx("^(?:what(?:'s| is)|check|how(?:'s| is))\\s+(?:my\\s+|the\\s+)?battery(?:\\s+(?:level|percentage|percent|life|at))?$|^how much battery(?: (?:do i have|is left|have i got))?(?: left)?$|^battery(?: level| percentage)?$").matches(s)) {
            return Request.Device(DeviceOp.BATTERY)
        }
        // Brightness
        rx("^(?:set|turn|put|change)\\s+(?:the\\s+|screen\\s+)?brightness\\s+to\\s+(\\d{1,3})(?:\\s*(?:percent|%))?$|^brightness\\s+(?:to\\s+)?(\\d{1,3})(?:\\s*(?:percent|%))?$").find(s)?.let {
            return Request.Device(DeviceOp.BRIGHT_SET, (it.groupValues[1].ifBlank { it.groupValues[2] }).toInt().coerceIn(0, 100))
        }
        if (rx("^(?:turn\\s+(?:the\\s+)?(?:screen\\s+)?brightness\\s+up|brightness up|make (?:the|my) screen brighter|brighter|increase (?:the )?brightness)$").matches(s)) return Request.Device(DeviceOp.BRIGHT_UP)
        if (rx("^(?:turn\\s+(?:the\\s+)?(?:screen\\s+)?brightness\\s+down|brightness down|make (?:the|my) screen (?:dimmer|darker)|dimmer|dim the screen|decrease (?:the )?brightness)$").matches(s)) return Request.Device(DeviceOp.BRIGHT_DOWN)
        // Wi-Fi / Bluetooth
        rx("^(?:turn|switch|toggle)\\s+(?:on\\s+|off\\s+)?(?:the\\s+|my\\s+)?(wi ?fi|wifi|wireless|bluetooth)(?:\\s+(?:on|off))?$|^(wi ?fi|wifi|bluetooth)\\s+(?:on|off|settings)$").find(s)?.let {
            val w = it.groupValues[1].ifBlank { it.groupValues[2] }
            return Request.Device(if (w.contains("blue", true)) DeviceOp.BLUETOOTH else DeviceOp.WIFI)
        }
        return null
    }

    /**
     * Just a time or a length of time ("7:00", "7 am", "30 mins", "an hour and a half"): that's for the Clock
     * app (an alarm or a timer), not a web search.
     */
    fun parseBareTime(s: String): Request? {
        if (s.split(" ").size > 7) return null
        if (rx("^(?:at\\s+|for\\s+)?(?:\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}:\\d{2}|noon|midnight)(?:\\s+(?:today|tomorrow|tonight|in the morning|in the evening|in the afternoon))?$").matches(s)) {
            return parseAlarm("set an alarm for $s")
        }
        val units = "sec|secs|second|seconds|min|mins|minute|minutes|hr|hrs|hour|hours"
        if (rx("\\b(?:$units)\\b").containsMatchIn(s)) {
            val rest = s.replace(rx("\\b(?:\\d+(?:\\.\\d+)?|a|an|and|half|quarter|of|for|in|just|like|$units)\\b"), " ").trim()
            if (rest.isEmpty()) TimeParser.parseDuration(s)?.let { return Request.Timer(it) }
        }
        return null
    }

    fun parseOpenApp(s: String): Request? {
        val m = rx("^(?:open|launch|start|run|go to|bring up|pull up|switch to)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app(?:lication)?)?$").find(s) ?: return null
        val name = m.groupValues[1].trim()
        if (name.isEmpty() || rx("^(?:a|an|the)\\s").containsMatchIn(name)) return null
        return Request.OpenApp(name)
    }

    fun parseYouTube(s: String): Request? {
        rx("^(?:play|put on|watch|listen to|show me)\\s+(.+?)\\s+(?:on|in|from|using)\\s+(?:the\\s+)?youtube(\\s+music)?(?:\\s+app)?$").find(s)?.let {
            return Request.YouTube(it.groupValues[1].trim(), play = true, music = it.groupValues[2].isNotBlank())
        }
        rx("^(?:search|look up|find|look for)\\s+(.+?)\\s+(?:on|in)\\s+youtube(\\s+music)?$").find(s)?.let {
            return Request.YouTube(it.groupValues[1].trim(), play = false, music = it.groupValues[2].isNotBlank())
        }
        rx("^(?:search\\s+)?youtube(\\s+music)?\\s+(?:search\\s+)?(?:for\\s+)?(.+)$").find(s)?.let {
            return Request.YouTube(it.groupValues[2].trim(), play = false, music = it.groupValues[1].isNotBlank())
        }
        rx("^(?:find|show me|search for|look up|pull up)\\s+(?:some\\s+|a\\s+)?(?:youtube\\s+)?(?:videos?|music videos?|clips?)\\s+(?:of|about|for|on|from|by)\\s+(.+)$").find(s)?.let {
            return Request.YouTube(it.groupValues[1].trim(), play = false, music = false)
        }
        return null
    }

    fun parseTimerControl(s: String): Request? {
        rx("^(pause|hold|freeze|resume|unpause|un pause|continue|restart|start|cancel|stop|delete|remove|dismiss|end|kill|turn off|clear)\\s+(?:the\\s+|my\\s+|this\\s+|that\\s+)?(all\\s+(?:of\\s+)?(?:the\\s+|my\\s+)?|both\\s+(?:of\\s+)?(?:the\\s+|my\\s+)?)?timers?(?:\\s+(?:again|back up))?$")
            .find(s)?.let { m ->
                val op = when (m.groupValues[1].lowercase()) {
                    "pause", "hold", "freeze" -> TimerOp.PAUSE
                    "resume", "unpause", "un pause", "continue", "restart", "start" -> TimerOp.RESUME
                    else -> TimerOp.CANCEL
                }
                val all = m.groupValues[2].isNotBlank() || re("timers$", true).containsMatchIn(s)
                return Request.TimerControl(op, all)
            }
        if (rx("^(?:how much time(?: is| has)? left|how much longer|how long(?: is)? left|how long until the timer|what(?:'s| is) the time left|time left|check (?:the |my )?timer|timer status)(?:\\s+on\\s+(?:the|my)\\s+timers?)?$").matches(s) ||
            rx("^how (?:much time|long) (?:is |has )?left on (?:the |my )?timers?$").matches(s)
        ) return Request.TimerControl(TimerOp.STATUS, false)
        return null
    }

    private fun parseTimer(s: String): Request? {
        val isTimer = rx("\\btimer\\b|\\bcount\\s?down\\b").containsMatchIn(s) ||
            rx("^(?:start|set)\\s+(?:a\\s+)?(?:\\d+|an?|half an?)\\s+\\S+\\s+(?:timer|countdown)").containsMatchIn(s)
        if (!isTimer) return null
        if (rx("\\b(?:cancel|stop|delete)\\b").containsMatchIn(s)) return null // let it fall through to search/music
        return Request.Timer(TimeParser.parseDuration(s))
    }

    // ---------------------------------------------------------------- Reminder

    private fun parseReminder(s: String): Request? {
        if (!rx("\\bremind\\s+me\\b|\\breminder\\b").containsMatchIn(s)) return null
        val pt = TimeParser.parse(s)
        var t = pt.remainder
        t = t.replace(rx("\\b(?:set|create|add|make|give me)\\s+(?:a\\s+|an\\s+|me\\s+a\\s+)?(?:new\\s+)?reminder\\b"), " ")
        t = t.replace(rx("\\breminder\\b"), " ")
        t = t.replace(rx("\\bremind\\s+me\\b"), " ")
        t = stripConnectors(t)
        if (t.isBlank()) t = "your reminder"
        return Request.Reminder(t, pt)
    }

    // ---------------------------------------------------------------- Calendar

    private val calendarTrigger = rx(
        "\\bcalendar\\b|\\bschedule\\b|^(?:add|create|make|book|set up|new)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?(?:event|meeting|appointment)\\b|^(?:new\\s+)?(?:event|meeting|appointment)\\b",
    )

    private fun parseCalendar(s: String): Request? {
        if (!calendarTrigger.containsMatchIn(s)) return null
        if (rx("^what(?:'s| is)\\b|^(?:show|read|check)\\b").containsMatchIn(s)) return null // calendar questions -> search
        val pt = TimeParser.parse(s)
        var t = pt.remainder
        t = t.replace(rx("\\b(?:to|on|in|into)\\s+(?:my\\s+)?calendar\\b"), " ")
        t = t.replace(rx("\\bcalendar\\b"), " ")
        t = t.trim().replace(
            rx("^(?:add|create|schedule|put|make|set up|book|new)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?(?:event\\s+)?(?:called|named|titled|for)?\\s*"),
            "",
        )
        t = stripConnectors(t)
        if (t.isBlank()) t = "New event"
        return Request.CalendarEvent(t.replaceFirstChar { it.uppercase() }, pt)
    }

    // ---------------------------------------------------------------- Text

    private val bodySplit = rx("\\s+(?:saying|that says|and say|and tell (?:him|her|them)|telling (?:him|her|them)|that)\\s+|\\s*:\\s*")

    private fun parseText(s: String): Request? {
        // "send mom a text saying hi"
        rx("^send\\s+(.+?)\\s+(?:a\\s+)?(?:text|message|sms|text message)(?:\\s+(?:saying|that says|that)?\\s*(.*))?$").find(s)?.let { m ->
            val who = m.groupValues[1]
            if (!who.equals("a", true)) return Request.Text(who.trim(), m.groupValues[2].trim())
        }
        val m = rx("^(?:send\\s+(?:a\\s+)?(?:text|message|sms|text message)\\s+to|send\\s+(?:a\\s+)?(?:text|message|sms)|text message|text|message|sms)\\s+(?:to\\s+)?(.+)$")
            .find(s) ?: return null
        val rest = m.groupValues[1].trim()
        val split = bodySplit.find(rest)
        return if (split != null) {
            Request.Text(rest.substring(0, split.range.first).trim(), rest.substring(split.range.last + 1).trim())
        } else {
            Request.Text(rest, null)
        }
    }

    // ---------------------------------------------------------------- Call

    private fun parseCall(s: String): Request? {
        rx("^give\\s+(.+?)\\s+a\\s+(?:call|ring)$").find(s)?.let { return Request.Call(it.groupValues[1].trim()) }
        val m = rx("^(?:make\\s+a\\s+call\\s+to|place\\s+a\\s+call\\s+to|call|phone|dial|ring|facetime)\\s+(.+)$").find(s) ?: return null
        val who = m.groupValues[1]
            .replace(rx("\\s+(?:on speaker|on speakerphone|on the phone|mobile|cell)$"), "")
            .trim()
        return Request.Call(who)
    }

    // ---------------------------------------------------------------- Weather

    private fun parseWeather(s: String): Request? {
        if (rx("\\b(?:cold|hot|warm|chilly|freezing|nice) outside\\b|what's it like outside|how is it outside|need a (?:jacket|coat)").containsMatchIn(s)) {
            return Request.Weather(null, rx("\\btomorrow\\b").containsMatchIn(s), if (rx("\\b(?:cold|hot|warm|chilly|freezing)\\b").containsMatchIn(s)) WeatherAspect.TEMPERATURE else WeatherAspect.GENERAL)
        }
        if (!rx("\\b(?:weather|forecast|temperature|temp|raining|rain|rainfall|snowing|snow|snowfall|sunny|humid|humidity|umbrella|uv|u v|sunscreen|sunburn|wind|windy|breezy|gusts?|how (?:hot|cold|warm) is it)\\b").containsMatchIn(s)) return null
        val tomorrow = rx("\\btomorrow\\b").containsMatchIn(s)
        val amount = rx("\\b(?:how much|how many (?:inches|centimeters|millimeters)|amount of|inches of|centimeters of|millimeters of|accumulation|rainfall|snowfall|total)\\b").containsMatchIn(s)
        val aspect = when {
            rx("\\b(?:humid|humidity)\\b").containsMatchIn(s) -> WeatherAspect.HUMIDITY
            rx("\\b(?:temperature|temp|degrees|how (?:hot|cold|warm))\\b").containsMatchIn(s) -> WeatherAspect.TEMPERATURE
            rx("\\b(?:uv|u v|ultraviolet|sunscreen|sunburn|sun index)\\b").containsMatchIn(s) -> WeatherAspect.UV
            rx("\\b(?:wind|windy|breezy|gusts?|gusty)\\b").containsMatchIn(s) -> WeatherAspect.WIND
            rx("\\b(?:snow|snowing|snowfall|snowy)\\b").containsMatchIn(s) -> if (amount) WeatherAspect.SNOW_AMOUNT else WeatherAspect.SNOW_CHANCE
            rx("\\b(?:rain|raining|rainfall|rainy|umbrella|precipitation)\\b").containsMatchIn(s) -> if (amount) WeatherAspect.RAIN_AMOUNT else WeatherAspect.RAIN_CHANCE
            else -> WeatherAspect.GENERAL
        }
        val cityMatch = rx("\\b(?:in|for|at)\\s+([A-Za-z][A-Za-z .'-]*?)(?:\\s+(?:today|tomorrow|right now|now|this week|tonight|like))?$").find(s)
        var city = cityMatch?.groupValues?.get(1)?.trim()
        if (city != null && rx("^(?:the\\s+.*|my\\s+area|here|outside|my location|today|tomorrow)$").matches(city)) city = null
        return Request.Weather(city?.takeIf { it.isNotBlank() }, tomorrow, aspect)
    }

    // ---------------------------------------------------------------- Music

    private fun parseMusic(s: String): Request? {
        if (rx("^(?:pause|stop)(?:\\s+(?:the\\s+)?(?:music|song|playback|playing|track|audio))?$").matches(s)) {
            return Request.Music(MusicCommand.PAUSE, null)
        }
        if (rx("^(?:resume|unpause|continue)(?:\\s+(?:the\\s+)?(?:music|song|playback|playing|track))?$").matches(s)) {
            return Request.Music(MusicCommand.RESUME, null)
        }
        if (rx("^(?:next|skip)(?:\\s+(?:this\\s+|the\\s+)?(?:song|track))?$|^(?:play\\s+)?(?:the\\s+)?next\\s+(?:song|track)$").matches(s)) {
            return Request.Music(MusicCommand.NEXT, null)
        }
        if (rx("^(?:previous|go back|back)(?:\\s+(?:a\\s+)?(?:song|track))?$|^(?:play\\s+)?(?:the\\s+)?(?:previous|last)\\s+(?:song|track)$").matches(s)) {
            return Request.Music(MusicCommand.PREVIOUS, null)
        }
        val m = rx("^(?:play|put on|shuffle|listen to|i want to listen to|i want to hear)\\s+(.+)$").find(s)
            ?: return if (rx("^(?:play|play music|start music)$").matches(s)) Request.Music(MusicCommand.PLAY_ANY, null) else null
        var q = m.groupValues[1]
        // "... in Driver" / "... on Spotify" picks the app explicitly.
        var app = MusicApp.DEFAULT
        rx("\\s+(?:on|in|using|with|from|via)\\s+(?:the\\s+)?(driver|spotify)(?:\\s+app|\\s+music player|\\s+music)?$").find(q)?.let { t ->
            app = if (t.groupValues[1].equals("spotify", ignoreCase = true)) MusicApp.SPOTIFY else MusicApp.DRIVER
            q = q.substring(0, t.range.first)
        }
        // Keep a leading "the": it is often part of the title ("The Prince", "The Beatles").
        q = q.replace(rx("^(?:some|me some|me)\\s+"), "").trim()
        if (q.isEmpty() || rx("^(?:the\\s+)?(?:music|something|a song|songs|anything|my music)$").matches(q)) {
            return Request.Music(MusicCommand.PLAY_ANY, null, app = app)
        }
        return classifyPlayQuery(q).copy(app = app)
    }

    private fun classifyPlayQuery(q: String): Request.Music {
        fun play(kind: MediaKind, query: String, artist: String? = null) =
            Request.Music(MusicCommand.PLAY_QUERY, query.trim(), kind, artist?.trim()?.takeIf { it.isNotEmpty() })

        if (rx("^(?:my\\s+|the\\s+)?(?:liked|loved|favou?rite)\\s+(?:songs|music|tracks)$|^(?:my\\s+)?(?:likes|favou?rites)$").matches(q)) {
            return play(MediaKind.LIKED, "")
        }
        rx("^(?:my\\s+|the\\s+)?playlist\\s+(?:called\\s+|named\\s+)?(.+)$").find(q)?.let { return play(MediaKind.PLAYLIST, it.groupValues[1]) }
        rx("^(?:my\\s+|the\\s+)?(.+?)\\s+playlist$").find(q)?.let { return play(MediaKind.PLAYLIST, it.groupValues[1]) }
        rx("^(?:the\\s+)?album\\s+(?:called\\s+|named\\s+)?(.+?)(?:\\s+by\\s+(.+))?$").find(q)?.let {
            return play(MediaKind.ALBUM, it.groupValues[1], it.groupValues[2])
        }
        rx("^(?:the\\s+)?(.+?)\\s+album(?:\\s+by\\s+(.+))?$").find(q)?.let { return play(MediaKind.ALBUM, it.groupValues[1], it.groupValues[2]) }
        rx("^(?:some\\s+)?(?:songs|music|tracks|stuff|something|anything|hits)\\s+(?:by|from)\\s+(.+)$").find(q)?.let { return play(MediaKind.ARTIST, it.groupValues[1]) }
        rx("^(?:the\\s+)?artist\\s+(?:called\\s+|named\\s+)?(.+)$").find(q)?.let { return play(MediaKind.ARTIST, it.groupValues[1]) }
        rx("^(?:the\\s+)?(?:song|track)\\s+(?:called\\s+|named\\s+)?(.+?)(?:\\s+by\\s+(.+))?$").find(q)?.let {
            return play(MediaKind.SONG, it.groupValues[1], it.groupValues[2])
        }
        rx("^(.+?)\\s+by\\s+(.+)$").find(q)?.let { return play(MediaKind.SONG, it.groupValues[1], it.groupValues[2]) }
        return play(MediaKind.AUTO, q)
    }

    // ---------------------------------------------------------------- Driver audio effects, presets, lyrics

    /** Spoken effect names. Hyphens are already turned into spaces by [clean] ("low-pass" -> "low pass"). */
    private const val EFFECT =
        "(reverb|room reverb|echo|low ?pass(?: filter)?|lpf|high ?pass(?: filter)?|hpf|(?:graphic )?(?:eq|equali[sz]er)|" +
            "spatiali[sz](?:e|er|ation)|spatial(?: audio| sound)?|3d (?:audio|sound)|surround(?: sound)?|sound ?stage(?: widener)?)"

    fun effectOf(word: String): AudioEffect? {
        val w = word.lowercase()
        return when {
            w.contains("reverb") || w == "echo" -> AudioEffect.REVERB
            w.contains("low") || w == "lpf" -> AudioEffect.LOW_PASS
            w.contains("high") || w == "hpf" -> AudioEffect.HIGH_PASS
            w.contains("eq") || w.contains("equali") -> AudioEffect.EQUALIZER
            w.contains("spatial") || w.startsWith("3d") || w.contains("surround") || w.contains("stage") -> AudioEffect.SPATIAL
            else -> null
        }
    }

    private fun parseEffectPreset(s: String): Request? {
        fun preset(effectWord: String?, name: String): Request? {
            val clean = name.replace(rx("^(?:the|my)\\s+"), "").replace(rx("\\s+preset$"), "").trim()
            if (clean.isEmpty()) return null
            return Request.EffectPreset(effectWord?.takeIf { it.isNotBlank() }?.let(::effectOf), clean)
        }
        // "set the reverb (preset) to cathedral" / "switch the eq to bass boost"
        rx("^(?:set|change|switch|put)\\s+(?:the\\s+|my\\s+)?$EFFECT(?:\\s+preset)?\\s+to\\s+(.+)$").find(s)?.let {
            return preset(it.groupValues[1], it.groupValues[2])
        }
        // "use the reverb preset (called) cathedral"
        rx("^(?:use|apply|load|select|pick|choose|set|activate)\\s+(?:the\\s+|my\\s+)?$EFFECT\\s+preset\\s+(?:called\\s+|named\\s+)?(.+)$").find(s)?.let {
            return preset(it.groupValues[1], it.groupValues[2])
        }
        // "use the preset (called) cathedral (for the reverb)"
        rx("^(?:use|apply|load|select|pick|choose|switch to|change to|activate)\\s+(?:the\\s+|my\\s+)?preset\\s+(?:called\\s+|named\\s+)?(.+?)(?:\\s+(?:for|on)\\s+(?:the\\s+)?$EFFECT)?$").find(s)?.let {
            return preset(it.groupValues[2], it.groupValues[1])
        }
        // "use the cathedral reverb preset" / "apply my vocal boost preset (for the eq)"
        rx("^(?:use|apply|load|select|pick|choose|switch to|change to|activate|enable|turn on|set)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+(?:$EFFECT\\s+)?preset(?:\\s+(?:for|on)\\s+(?:the\\s+)?$EFFECT)?$").find(s)?.let {
            val effectWord = it.groupValues[2].ifBlank { it.groupValues[3] }
            return preset(effectWord, it.groupValues[1])
        }
        return null
    }

    /** Removes "in Driver", "on the Driver app", "Driver's" and "the Driver" from a request. */
    private fun withoutDriverMention(s: String): String = s
        .replace(rx("\\s+(?:in|on|with|using|inside|from|for|through|via)\\s+(?:the\\s+)?driver(?:\\s+(?:app|music player|player|music))?(?=\\s|$)"), "")
        .replace(rx("\\b(?:the\\s+)?driver'?s\\s+"), "")
        .replace(rx("\\b(?:the\\s+)?driver\\s+(?=$EFFECT|presets?\\b|lyrics\\b)"), "")
        .replace(re("\\s+"), " ")
        .trim()

    private fun parseEffect(s: String): Request? {
        fun effect(word: String, state: EffectState): Request? = effectOf(word)?.let { Request.Effect(it, state) }
        // Driver has no separate bass boost switch (it's part of the EQ and reverb presets).
        if (rx("^(?:(?:turn|switch)\\s+(?:on|off|up|down)\\s+|enable\\s+|disable\\s+|toggle\\s+|(?:can you\\s+)?(?:add|use)\\s+)?(?:the\\s+|some\\s+)?bass\\s*boost(?:\\s+(?:on|off))?$").matches(s)) {
            return Request.Chat("Driver has no separate bass boost. It comes from the equalizer and the reverb presets, so try an equalizer preset instead.")
        }
        // Bare "reverb on" / "eq off"
        rx("^(?:the\\s+)?$EFFECT\\s+(on|off)$").find(s)?.let {
            return effect(it.groupValues[1], if (it.groupValues[2].equals("on", true)) EffectState.ON else EffectState.OFF)
        }
        fun stateOf(word: String): EffectState = when (word.lowercase()) {
            "on", "enable", "activate", "start", "add", "apply", "use", "engage" -> EffectState.ON
            "off", "disable", "deactivate", "stop", "remove", "kill", "bypass", "mute" -> EffectState.OFF
            else -> EffectState.TOGGLE
        }
        rx("^(?:turn|switch|flip|toggle)\\s+(on|off)\\s+(?:the\\s+)?$EFFECT(?:\\s+effect)?$").find(s)?.let {
            return effect(it.groupValues[2], stateOf(it.groupValues[1]))
        }
        rx("^(?:turn|switch|flip)\\s+(?:the\\s+)?$EFFECT(?:\\s+effect)?\\s+(on|off)$").find(s)?.let {
            return effect(it.groupValues[1], stateOf(it.groupValues[2]))
        }
        rx("^(enable|activate|start|add|apply|use|engage|disable|deactivate|stop|remove|kill|bypass|mute|toggle)\\s+(?:the\\s+|some\\s+)?$EFFECT(?:\\s+effect)?$").find(s)?.let {
            return effect(it.groupValues[2], stateOf(it.groupValues[1]))
        }
        return null
    }

    /**
     * Any phrasing that names one or more Driver effects and says on or off: "turn on the lpf and spatialize
     * audio effects", "can you kill the reverb", "I want spatial audio on", "add some echo".
     */
    fun parseEffectLoose(s: String): Request? {
        val word = rx("\\b(reverb|echo|low ?pass(?: filter)?|lpf|high ?pass(?: filter)?|hpf|(?:graphic )?(?:eq|equali[sz]er)|spatiali[sz](?:e|er|ation|ed|ing)?|spatial(?: audio| sound)?|3d (?:audio|sound)|surround(?: sound)?|sound ?stage(?: widener)?)\\b")
        val found = word.findAll(s).mapNotNull { effectOf(it.value) }.distinct().toList()
        if (found.isEmpty()) return null
        // Not effects: presets, lyrics, music requests.
        if (rx("\\b(?:preset|lyrics|play|song|playlist|album)\\b").containsMatchIn(s)) return null
        val off = rx("\\b(?:off|disable|disabled|deactivate|stop|kill|remove|bypass|no more|get rid of|take off|turn down|without|cut)\\b").containsMatchIn(s)
        val on = rx("\\b(?:on|enable|enabled|activate|start|add|apply|use|engage|put on|give me|turn up|want|need|with)\\b").containsMatchIn(s)
        val toggle = rx("\\b(?:toggle|switch)\\b").containsMatchIn(s) && !on && !off
        val state = when {
            off && !rx("\\b(?:turn|switch|put)\\s+(?:\\w+\\s+){0,4}on\\b").containsMatchIn(s) -> EffectState.OFF
            on -> EffectState.ON
            toggle -> EffectState.TOGGLE
            else -> return null
        }
        return Request.Effect(found.first(), state, found.drop(1))
    }

    /** "Add this song to my gym playlist" / "put this on road trip" / "save this track to the chill playlist". */
    fun parseAddToPlaylist(s: String): Request? {
        val m = rx("^(?:add|put|save|throw|stick|drop)\\s+(?:this|that|it|the current|the)?\\s*(?:song|track|tune|one)?\\s*(?:to|in|into|on|onto)\\s+(?:my\\s+|the\\s+)?(.+?)(?:\\s+playlist)?$").find(s) ?: return null
        val name = m.groupValues[1].trim()
        val aboutSong = rx("^(?:add|put|save|throw|stick|drop)\\s+(?:this|that|it|the current|the song|the track)\\b").containsMatchIn(s) || rx("\\bplaylist\\b").containsMatchIn(s)
        if (!aboutSong || name.isEmpty()) return null
        // Likes go to "I like this song"; calendars and reminders aren't playlists.
        if (rx("^(?:likes|liked songs|liked|favou?rites|library|calendar|reminders|notes)$").matches(name)) return null
        return Request.AddToPlaylist(name)
    }

    private fun parseLyrics(s: String): Request? {
        val m = rx("^(?:(?:download|get|fetch|find|grab|pull|load|search for|look up|search)\\s+(?:me\\s+)?)?(?:the\\s+)?(?:synced\\s+|song\\s+)?lyrics(?:\\s+(?:for|to|of)\\s+(.+))?$").find(s)
            ?: return null
        val song = m.groupValues[1].trim()
        val current = song.isEmpty() || rx("^(?:this|that|it|this song|that song|the song|this track|the current song|the song that's playing|what's playing)$").matches(song)
        return Request.Lyrics(if (current) null else song)
    }

    private fun parseNowPlaying(s: String): Request? {
        val r = rx(
            "^(?:what(?:'s| is)\\s+(?:playing|this song|that song|the song|this track|the current song|currently playing)(?:\\s+right now)?" +
                "|what song is (?:this|that|playing)|which song is (?:this|that|playing)|what am i listening to|now playing|name (?:this|that) song)$",
        )
        return if (r.matches(s)) Request.NowPlaying else null
    }

    // ---------------------------------------------------------------- Search

    private fun searchQuery(s: String): String {
        val q = s.replace(
            rx("^(?:search\\s+(?:the\\s+)?(?:web|internet|online|google)\\s+for|search\\s+for|search|look\\s+up|google|find\\s+out|find)\\s+"),
            "",
        ).trim()
        return q.ifBlank { s }
    }

    private fun stripConnectors(text: String): String {
        var t = text.replace(re("\\s+"), " ").trim()
        val lead = rx("^(?:to|that|about|for|at|on|of|in|by|me)\\s+")
        val trail = rx("\\s+(?:at|on|for|in|by|to|from)$")
        repeat(3) {
            t = t.replace(lead, "").replace(trail, "").trim()
        }
        return t
    }
}

/** What kind of place [Request.FindPlace] looks for. */
enum class FindKind { FOOD, SHOPPING, OTHER }

/** Brick settings you can change by voice; [label] is how Brick says them. */
enum class BrickSetting(val label: String) {
    WAKE_WORD("Hey Brick"), POCKET_PAUSE("Pausing Hey Brick in a pocket"), HEADSET_MIC("The headphones' microphone"),
    OVERLAY("The floating card"), BRAND_GLOW("App animations"), ROBOT_VOICE("The robot unit voice"), FAHRENHEIT("Fahrenheit"),
}
