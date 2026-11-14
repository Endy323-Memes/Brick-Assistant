package com.brick.assistant.brain

import java.util.Locale

/**
 * Everyday wording turned into the wording Brick's parsers know ("get me up at 8" -> "wake me up at 8",
 * "shoot a text to dad saying running late" -> "text dad saying running late", "throw on some kendrick" -> "play kendrick").
 *
 * [early] rules are narrow and anchored: they run before anything else, because without them a request can be
 * misread ("get me up at 8" looked like directions to "up at 8"). Their result is used only when it's understood.
 * [late] rules are broader ("throw on X" -> "play X") and only run when nothing understood the request as said.
 */
object Colloquial {
    private val SPOKEN = listOf(
        Regex("\\b(how long|how many|how much|how often|how come)'d\\b") to "$1 did",
        Regex("\\b(how|when|where|why|what|who)'d\\b") to "$1 did",
        // Typed fast or heard without the apostrophe: "howd", "how d".
        Regex("^(how|when|where|why)d\\b") to "$1 did",
        Regex("^(how|when|where|why|what|who) d\\b") to "$1 did",
        Regex("\\bwhats\\b") to "what's",
        Regex("\\bwhens\\b") to "when's",
        Regex("\\bhows\\b") to "how's",
        Regex("\\bgimme\\b") to "give me",
        Regex("\\bwanna\\b") to "want to",
        Regex("\\bgonna\\b") to "going to",
        Regex("\\bgotta\\b") to "have to",
        Regex("\\blemme\\b") to "let me",
        Regex("\\bya\\b") to "you",
        Regex("\\bu\\b") to "you",
        Regex("\\bdid (?:the )?(\\w+(?: \\w+)?) end up (winning|losing)\\b") to "did $1 $2",
    )

    /** Spoken shortcuts written out for the question parsers: "how'd" -> "how did", "whats" -> "what's", "gimme" -> "give me". */
    fun spoken(s: String): String = SPOKEN.fold(s) { t, (rx, to) -> rx.replace(t, to) }

    private const val IC = "(?i)"
    private fun r(p: String) = Regex(IC + p)
    private fun rule(p: String, f: (MatchResult) -> String): Pair<Regex, (MatchResult) -> String> = r(p) to f

    // Words that can't be a contact's name after "tell" / "let ... know".
    private const val NOT_NAME = "(?!me\\b|us\\b|you\\b|brick\\b|a\\b|an\\b|the\\b|it\\b|my\\b|her\\b|him\\b|them\\b|everyone\\b|everybody\\b|someone\\b|somebody\\b|people\\b|what\\b|how\\b|why\\b|when\\b|where\\b|who\\b|about\\b|more\\b|something\\b|anything\\b|this\\b|that\\b)"
    // Texts only: a messaging app named ("send a discord message to alex") has its own handler.
    private const val NO_APP = "(?!.*\\b(?:discord|whatsapp|telegram|signal|slack|messenger|instagram|snapchat|snap|teams|imessage)\\b)"
    private const val TEXT_NOUN = "(?:texts?|text messages?|messages?|msgs?|txts?|lines?|sms|dms?)"
    private const val WHEN = "(today|tomorrow|tonight|this morning|this afternoon|this evening|this week|this weekend|later)"

    private val EARLY: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // ----- alarms: "get me up at 8", "i need to be up by 7", "make sure i'm up at 6"
        rule("^(?:get|have) me up (?:at|by|around|for) ") { _ -> "wake me up at " },
        rule("^(?:i (?:need|have|want|got) to|i gotta|i've got to|i must|i should|i wanna) (?:be|get|wake) up (?:at|by|around|before) ") { _ -> "wake me up at " },
        rule("^make sure i(?:'m| am)? (?:up|awake|get up|wake up) (?:at|by|around) ") { _ -> "wake me up at " },

        // ----- timers: "ping me in 20 minutes", "let me know in 10 minutes"
        rule("^(?:ping|buzz|nudge|alert|beep at|holler at|ding|poke) me in (.+)$") { m -> "set a timer for ${m.groupValues[1]}" },
        rule("^(?:let me know|tell me|remind me) in ((?:\\d+(?:\\.\\d+)?|a|an|one|half an?) (?:sec|secs|second|seconds|min|mins|minute|minutes|hour|hours|hr|hrs)(?: and (?:a half|\\d+ (?:sec|secs|seconds|min|mins|minutes)))?)$") { m -> "set a timer for ${m.groupValues[1]}" },

        // ----- reminders: "don't let me forget to...", "remember to buy milk tomorrow"
        rule("^(?:don't|dont|do not) (?:let me )?forget (?:to |that i (?:need|have) to )") { _ -> "remind me to " },
        rule("^(?:don't|dont|do not) (?:let me )?forget (?:about )?") { _ -> "remind me about " },
        rule("^(?:make sure i |help me |i (?:need|have|gotta|got) to |i should |i must )?remember to ") { _ -> "remind me to " },
        rule("^(?:i need|give me|set|make|create) (?:a )?reminder (?:to|that i need to|for me to) ") { _ -> "remind me to " },

        // ----- the date: "what year is it" (not a song's release year)
        rule("^what (?:year|month) is it(?: now| right now)?$|^what(?:'s| is) the (?:year|month)$") { _ -> "what's the date" },

        // ----- what to watch, play or read: "what should i watch tonight", "what should we play"
        rule("^what (?:should|can|could|do you think) (?:i|we) (?:watch|binge|stream)(?: tonight| today| right now| now| this weekend)?$") { _ -> "good movies to watch" },
        rule("^what (?:should|can|could|do you think) (?:i|we) (?:watch|binge|stream)(?: tonight| today| right now| now)? on ([a-z+ ]+)$") { m -> "good movies on ${m.groupValues[1]}" },
        rule("^what (?:should|can|could) (?:i|we) (?:play)(?: tonight| today| right now| now)?$") { _ -> "best games" },
        rule("^what (?:should|can|could) (?:i|we) read(?: next)?$") { _ -> "best books" },

        // ----- food: "where's a good place to eat", "where should we eat"
        rule("^(?:where(?:'s| is)|what(?:'s| is)) (?:a |some |the )?(?:good |nice |decent |great )?(?:place|spot|restaurant|somewhere) to eat(?: (?:near me|nearby|around here))?$|^where (?:should|can|could) (?:i|we) eat(?: tonight| today| now)?$") { _ -> "find good food near me" },

        // ----- notes: "write this down", "note to self: ...", "remember that the code is 1234", "add milk to my notes"
        rule("^(?:add|put|save|write|stick) (.+?) (?:to|in|on|into) (?:my |the )?notes?(?: app)?$") { m -> "take a note ${m.groupValues[1]}" },
        rule("^(?:write|jot|put|get|take|scribble) (?:this|that|it) down$") { _ -> "take a note" },
        rule("^(?:write|jot|scribble|get) (?:this|that|it) down:? (.+)$") { m -> "take a note ${m.groupValues[1]}" },
        rule("^(?:note to self|memo to self|memo|note)[:,]? (.+)$") { m -> "take a note ${m.groupValues[1]}" },
        rule("^(?:remember|memorize) (?:this|that)[:,]? (.+)$") { m -> "take a note ${m.groupValues[1]}" },
        rule("^remember that (.+)$") { m -> "take a note ${m.groupValues[1]}" },

        // ----- texts: "shoot a text to dad", "shoot dad a text", "hit up jake", "let mom know i'm late", "tell mom i'll be home soon"
        rule("^${NO_APP}(?:shoot|send|fire off|drop|flick|write) (?:a |an )?(?:quick |short )?$TEXT_NOUN (?:over )?to ") { _ -> "text " },
        // "shoot dad a text" -> "text dad"; "shoot dad a text saying hi" -> "text dad saying hi" (never "a" as part of the name).
        rule("^${NO_APP}(?:shoot|send|drop|fire off|flick) $NOT_NAME([a-z']+(?: (?!a\\b|an\\b|quick\\b|short\\b)[a-z']+)?) (?:a |an )?(?:quick |short )?$TEXT_NOUN\\b( (?:saying|that says|and say|that|to say))?") { m ->
            "text ${m.groupValues[1]}" + if (m.groupValues[2].isNotBlank()) " saying" else ""
        },
        rule("^${NO_APP}hit ([a-z']+) up\\b(?: and (?:say|tell (?:him|her|them)(?: that)?))?") { m -> "text ${m.groupValues[1]} saying" },
        rule("^${NO_APP}hit up ([a-z']+)\\b(?: and (?:say|tell (?:him|her|them)(?: that)?))?") { m -> "text ${m.groupValues[1]} saying" },
        rule("^${NO_APP}let $NOT_NAME([a-z']+) know (?:that )?") { m -> "text ${m.groupValues[1]} saying " },
        rule("^${NO_APP}tell $NOT_NAME([a-z']+) (?:that )?(?=(?:i|i'm|im|i'll|ill|i've|ive|we|we're|we'll|you|it's|its|the|my|dinner|lunch|happy|good|sorry|thanks|thank|love|running|be|see|call|don't|dont|can't|cant|will|gonna)\\b)") { m -> "text ${m.groupValues[1]} saying " },
        rule("^${NO_APP}(text [a-z']+) (?:and say|and tell (?:him|her|them)(?: that)?|that says|saying that|and let (?:him|her|them) know(?: that)?)\\b") { m -> "${m.groupValues[1]} saying" },

        rule("^${NO_APP}(?:reply|respond|write back|answer|text back|reply back) (?:to )?$NOT_NAME([a-z']+) (?:saying|with|and say|that)\\b") { m -> "text ${m.groupValues[1]} saying" },

        // ----- reading messages: "did anyone text me", "what did mom say", "read my texts"
        rule("^(?:did|has|have) (?:anyone|anybody|someone|somebody|any(?:one)? new) (?:text(?:ed)?|messaged?|dm(?:ed|'d)?|hit me up|written|wrote|write|sent me (?:a |any )?$TEXT_NOUN)(?: me)?\\b.*$") { _ -> "read my notifications" },
        rule("^(?:did|has|have) (?!you\\b|i\\b)([a-z']+(?: [a-z']+)?) (?:text(?:ed)?|messaged?|dm(?:ed|'d)?|hit me up|sent me (?:a |any )?$TEXT_NOUN)(?: me)?(?: back| today| yet| lately)?$") { m -> "read my notifications from ${m.groupValues[1]}" },
        rule("^what did (?!you\\b|i\\b)([a-z']+(?: [a-z']+)?) (?:say|text(?: me)?|send(?: me)?|write|message me|want)$") { m -> "read my notifications from ${m.groupValues[1]}" },
        rule("^(?:read|check|show|any|got any|do i have any|did i get any|read me|go through)(?: me)?(?: all)?(?: my| the)?(?: new| unread| latest| recent)? (?:texts|text messages|messages|msgs|dms|sms)(?: from (.+))?$") { m ->
            "read my notifications" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { " from $it" } ?: "")
        },

        // ----- calendar: "am i free tomorrow", "what am i doing tonight", "what's the plan for today"
        rule("^(?:am i|are we) (?:free|busy|available|booked|doing anything|up to anything)(?: (?:on |for )?(.+))?$") { m -> "what's on my calendar ${m.groupValues[1].ifBlank { "today" }}" },
        rule("^(?:what am i doing|what are we doing|what(?:'s| is) the plan|what are my plans|what're my plans|what(?:'s| is) (?:going on|happening))(?: for)? $WHEN$") { m -> "what's on my calendar ${m.groupValues[1]}" },

        // ----- weather: "how cold will it be tomorrow", "is it cold out", "should i wear a jacket"
        rule("^how (cold|hot|warm|windy|humid|chilly|nice|rainy|snowy|sunny) (?:will it be|is it gonna be|is it going to be|it gonna be|it's gonna be|it is going to be|does it get|will it get)\\b") { m -> "how ${m.groupValues[1]} is it" },
        rule("^(?:will it be|is it gonna be|is it going to be|it gonna be) (?:really |very |super |too |kinda |pretty )?(cold|hot|warm|chilly|freezing|cool|nippy|humid|muggy) $WHEN$") { m ->
            val c = m.groupValues[1].lowercase(Locale.US)
            "how ${if (c in setOf("hot", "warm", "humid", "muggy")) "hot" else "cold"} is it ${m.groupValues[2]}"
        },
        rule("^(?:is it|will it be|is it gonna be|is it going to be|gonna be|it gonna be) (?:really |very |super |too |kinda |pretty )?(cold|hot|warm|chilly|freezing|cool|nice|sunny|windy|humid|cloudy|rainy|snowy|muggy|nippy)(?: (?:out|outside|out there))?(?: $WHEN)?$") { m ->
            val w = m.groupValues[2].ifBlank { "today" }
            when (m.groupValues[1].lowercase(Locale.US)) {
                "cold", "chilly", "freezing", "cool", "nippy" -> "how cold is it $w"
                "hot", "warm", "humid", "muggy" -> "how hot is it $w"
                "rainy" -> "will it rain $w"
                "snowy" -> "will it snow $w"
                "windy" -> "how windy is it $w"
                else -> "what's the weather $w"
            }
        },
        rule("^(?:should i|do i need to|do i need|should i bring|should i wear|will i need|do i have to)(?: to)? (?:wear |bring |take |grab |pack |put on )?(?:an? |my |some )?(jacket|coat|hoodie|sweater|sweatshirt|layers?|gloves|scarf|hat|beanie|boots|shorts|raincoat|rain jacket|rain boots)(?: $WHEN)?$") { m ->
            val w = m.groupValues[2].ifBlank { "today" }
            when (m.groupValues[1].lowercase(Locale.US)) {
                "raincoat", "rain jacket", "rain boots" -> "will it rain $w"
                "shorts" -> "how hot is it $w"
                "sunscreen", "sunglasses" -> "what's the weather $w"
                else -> "how cold is it $w"
            }
        },

        // ----- battery: "am i charging", "how much juice do i have", "is my phone dying"
        rule("^(?:am i|is (?:my |the )?phone|is it|is my battery|are we) (?:charging|plugged in)$") { _ -> "battery" },
        rule("^(?:how much (?:juice|charge|battery)(?: do i have| is left| left| have i got| does my phone have)?|how(?:'s| is) my (?:battery|charge|phone battery|phone's battery)|is my phone (?:dying|about to die|gonna die|low)|(?:my )?battery (?:level|life|status|percentage|percent)|(?:what's|what is) my (?:battery|charge)(?: level| at)?|am i (?:low on battery|gonna die|dying))$") { _ -> "battery" },

        // ----- flashlight: "put on the flashlight" (not a song called "the flashlight"), "i need a light"
        rule("^(?:put on|start up|fire up|boot up|throw on|light up|hit) (?:the |my )?(?:flashlight|flash light|torch)$|^(?:i need (?:a |some )?light|it's (?:too )?dark in here|give me (?:some )?light)$") { _ -> "turn on the flashlight" },

        // ----- brightness: "turn up the brightness", "the screen is too dark"
        rule("^turn (up|down) (?:the |my )?(?:screen |display |phone )?brightness$") { m -> "brightness ${m.groupValues[1]}" },
        rule("^(?:the |my )?(?:screen|display|phone) (?:is )?(?:way |really |so )?too (bright|dark|dim)$|^(?:it's|its) too (bright|dark)(?: in here)?$") { m ->
            if ((m.groupValues[1] + m.groupValues[2]).lowercase(Locale.US) == "bright") "brightness down" else "brightness up"
        },

        // ----- volume: "crank it up", "it's too loud", "set the volume to half"
        rule("^(?:crank|pump|bump|turn) (?:it|the volume|the music|this|that) (?:way |all the way )?up$|^(?:crank|pump) (?:it|the volume|the music|this)$|^make (?:it|this) louder$|^(?:it's|its) too quiet$|^i can(?:'t|not) hear (?:it|this|that|anything)$") { _ -> "turn it up" },
        rule("^(?:it's|its|this is|that's) (?:way |really |so )?too loud$|^turn (?:it|this|that) way down$|^make (?:it|this) quieter$|^keep it down$|^(?:tone|bring) it down$") { _ -> "turn it down" },
        rule("^(?:set |put |turn )?(?:the )?volume (?:to |at |on )?(half(?:way)?|max(?:imum)?|full|all the way(?: up)?|a quarter|quarter|three quarters)$") { m ->
            val p = when {
                m.groupValues[1].startsWith("half") -> 50
                m.groupValues[1].contains("quarter") && m.groupValues[1].startsWith("three") -> 75
                m.groupValues[1].contains("quarter") -> 25
                else -> 100
            }
            "volume to $p"
        },

        // ----- math: "tip on 45 dollars", "18 percent tip on 60", "split 120 four ways"
        rule("^(?:what(?:'s| is| would be) )?(?:the |a )?(\\d+(?:\\.\\d+)?) ?(?:%|percent) tip (?:on|for) \\$?(\\d+(?:\\.\\d+)?)(?: dollars| bucks)?$") { m -> "${m.groupValues[1]}% of ${m.groupValues[2]}" },
        rule("^(?:what(?:'s| is| would be)|how much is|how much should i)? ?(?:the |a |i )?(?:tip|leave as a tip|leave for a tip) (?:on|for) \\$?(\\d+(?:\\.\\d+)?)(?: dollars| bucks)?$") { m -> "20% of ${m.groupValues[1]}" },
        rule("^(?:split|divide) \\$?(\\d+(?:\\.\\d+)?)(?: dollars| bucks)? (\\d+) ways$|^(?:split|divide) \\$?(\\d+(?:\\.\\d+)?)(?: dollars| bucks)? (?:between|among|by|with) (\\d+)(?: people| of us| friends| ways)?$") { m ->
            val a = m.groupValues[1].ifBlank { m.groupValues[3] }; val b = m.groupValues[2].ifBlank { m.groupValues[4] }
            "$a divided by $b"
        },
    )

    private val LATE: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // ----- music: "throw on some kendrick", "bump some lofi", "skip this", "keep playing"
        rule("^(?:throw on|bump|blast|spin|queue up|crank|let's hear|lets hear|lemme hear|let me hear|i wanna hear|i want to hear|jam to|jam out to|put on|play me|gimme|give me|i'm in the mood for|im in the mood for|i feel like listening to) (?:some |a little |a bit of |the )?(.+)$") { m -> "play ${m.groupValues[1]}" },
        rule("^(?:skip (?:this|it|that)(?: one| song| track)?|next one|next track|change the song|different song|another song|skip ahead|move on)$") { _ -> "next song" },
        rule("^(?:keep playing|keep going|continue(?: playing)?|unpause|carry on|hit play|press play|back on|start it back up|turn it back on)$") { _ -> "resume" },
        rule("^(?:pause|stop|hold|freeze) (?:it|this|that|this song|the song|the track|this track|playback)$") { _ -> "pause the music" },
        rule("^turn (?:the )?(?:music|song|sound|volume|audio|tunes|track) (up|down)$|^turn (up|down) (?:the )?(?:music|song|sound|audio|tunes|track)$") { m -> "turn it ${m.groupValues[1].ifBlank { m.groupValues[2] }}" },
        rule("^(?:shuffle|shuffle it|shuffle mode|put it on shuffle|mix it up)$") { _ -> "shuffle my music" },
        rule("^(?:what(?:'s| is) this (?:song|track|tune)(?: called)?|what(?:'s| is) the name of this (?:song|track|tune)|who(?:'s| is) this|name (?:this|that) (?:song|track|tune)|what am i listening to|what(?:'s| is) on right now|what(?:'s| is) this)$") { _ -> "what song is this" },
        rule("^(?:vibrate|vibrate mode|vibration mode|vibrate only)$") { _ -> "put my phone on vibrate" },
        rule("^(?:silent|silent mode|silence|go silent)$") { _ -> "silence my phone" },
        rule("^how(?:'s| is) it (?:looking|look) (?:out|outside|out there)$") { _ -> "what's the weather today" },
        rule("^(?:last song|previous one|play the last song|run it back|play that again|replay that|again)$") { _ -> "previous song" },
        rule("^(?:stop (?:it|this|that)|shut (?:it|this|that) off|enough music|kill the music|cut the music|stop the music)$") { _ -> "pause the music" },
    )

    private fun apply(s: String, rules: List<Pair<Regex, (MatchResult) -> String>>): String {
        var t = s
        for ((re, f) in rules) {
            val m = re.find(t) ?: continue
            t = t.substring(0, m.range.first) + f(m) + t.substring(m.range.last + 1)
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun early(s: String) = apply(s, EARLY)
    fun late(s: String) = apply(s, LATE)

    /** "gas near me", "best tacos nearby": a bare "near me" search reads as a find-places request. */
    fun nearby(s: String): String? =
        r("^(?!find|show|where|navigate|directions|take|drive|get|is|are|any|how|what|weather|news|events?)(.+?) (?:near me|nearby|around here|close by|around me|in the area)$").find(s)?.let { "find ${it.groupValues[1]} near me" }

    private val JOKES = listOf(
        "I tried to write a joke about the cloud. It went over everyone's head.",
        "My battery and I have a lot in common. We both run low by 3 PM.",
        "I'd tell you a joke about timers, but the timing has to be right.",
        "Why did the phone wear glasses? It lost its contacts.",
        "I asked the weather service for a joke. It said the chance of laughter is 40 percent.",
        "There are 10 kinds of people: those who read binary, and those who don't.",
        "Autocorrect has never been wrong. It has just been creative.",
    )

    /** Small talk Brick answers itself: "how are you", "good morning", "what can you do", "tell me a joke". */
    fun chat(s: String): String? {
        val t = s.lowercase(Locale.US).replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            Regex("^(?:how are you|how are you doing|how's it going|how is it going|how you doing|how ya doing|what's up|whats up|sup|wassup|what's good|you good|you okay|how have you been)(?: brick)?(?: today)?$").matches(t) ->
                "Operational. Awaiting your request."
            Regex("^good (morning|afternoon|evening)(?: brick)?$").matches(t) -> "Good ${Regex("morning|afternoon|evening").find(t)!!.value}. What do you need?"
            Regex("^(?:good night|goodnight|night night|nighty night)(?: brick)?$").matches(t) -> "Good night."
            Regex("^(?:who (?:made|created|built|programmed|designed) you|who are you|what are you|what's your name|what is your name|who's brick|who is brick)$").matches(t) ->
                "I'm Brick, the assistant on this phone."
            Regex("^(?:what can you do|what do you do|what are you capable of|what can i ask(?: you)?|what can i say|help|help me|what are your commands|what commands do you know|how do i use you)$").matches(t) ->
                "Timers, alarms, reminders, notes, texts and calls, music, weather, directions and places nearby, news, " +
                    "movie, show, game and book lookups, math and conversions, and your phone's settings. Just ask the way you'd say it."
            Regex("^(?:never ?mind|forget it|forget about it|nothing|no thanks|nah|nope|scratch that|ignore that|my bad|oops)$").matches(t) -> "Okay."
            Regex("^(?:you're|you are|ur|youre) (?:awesome|great|the best|amazing|cool|smart|helpful|a legend|goated)$|^(?:good job|nice job|great job|well done|nice work|love you|i love you|good bot|nice|cool|awesome|perfect|great)(?: brick)?$").matches(t) ->
                "Noted. Thank you."
            Regex("^(?:you're|you are|ur|youre) (?:dumb|stupid|useless|bad|annoying|wrong)$|^(?:bad bot|that's wrong|thats wrong|wrong)$").matches(t) ->
                "Understood. Try asking another way and I'll take another run at it."
            Regex("^(?:tell me a joke|say something funny|make me laugh|got any jokes|know any jokes|tell me another joke|another joke|joke)$").matches(t) -> JOKES.random()
            else -> null
        }
    }
}
