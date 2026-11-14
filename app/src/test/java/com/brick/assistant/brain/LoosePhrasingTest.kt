package com.brick.assistant.brain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/** Everyday, loose and slangy wording for every kind of request Brick handles. */
class LoosePhrasingTest {
    @Test fun understandsEverydayWording() {
        // phrase -> acceptable request class names
        val cases = listOf(
            "set a timer for an hour and a half" to "Timer", "half hour timer" to "Timer", "timer for 90 seconds" to "Timer", "put a timer on for 15" to "Timer", "how long is left on the timer" to "TimerControl", "cancel my alarm" to "TimerControl|Alarm|Search", "snooze" to "TimerControl|Alarm|Search",
            "remind me tomorrow morning to call the bank" to "Reminder", "remind me when i get home to feed the dog" to "Reminder|Search", "can you remind me at 6 to start dinner" to "Reminder", "yo remind me to stretch in 30 mins" to "Reminder",
            "new note groceries eggs and bread" to "Note", "add milk to my notes" to "Note", "make a note" to "Note",
            "text my mom that i love her" to "Text", "send mom a text" to "Text", "reply to mom saying ok" to "Text|ReadNotifications", "let dad know i'm running late" to "Text", "shoot dad a quick text" to "Text",
            "call my mom" to "Call", "dial 911" to "Call", "can you call dad for me" to "Call", "ring my sister" to "Call",
            "is it gonna be cold tomorrow" to "Weather", "will i need an umbrella tomorrow" to "Weather", "how's it looking outside" to "Weather", "whats the weather like this weekend" to "Weather", "any rain today" to "Weather", "is it supposed to rain" to "Weather", "temperature outside" to "Weather", "weather in nyc" to "Weather", "how windy is it" to "Weather",
            "play the weeknd" to "Music", "put on some music" to "Music", "play my discover weekly" to "Music", "play the album thriller" to "Music", "pause it" to "Music", "stop the music" to "Music", "turn the music down" to "Device|Music", "skip this song" to "Music", "play the next song" to "Music", "restart the song" to "Music|Search", "shuffle" to "Music",
            "what's this song called" to "NowPlaying", "who is this" to "NowPlaying|MediaAsk", "name this song" to "NowPlaying|Search",
            "turn off the light" to "Device|SmartHome", "torch on" to "Device", "how bright is my screen" to "Device|PhoneSettingReq|Search", "is my wifi on" to "PhoneSettingReq|Device", "turn on dark mode" to "PhoneSettingReq", "silent mode" to "PhoneSettingReq|Device", "vibrate" to "PhoneSettingReq|Device", "unmute" to "Device|Music",
            "open the camera" to "OpenApp", "open my camera" to "OpenApp", "start youtube" to "OpenApp|YouTube", "open gmail" to "OpenApp",
            "what's the date today" to "TellTime", "what year is it" to "TellTime|Search", "what time is it in london" to "TellTime", "how many days till halloween" to "Countdown", "when is thanksgiving" to "Countdown|Search",
            "what's 20 percent of 150" to "Math", "what is 12 squared" to "Math", "100 divided by 7" to "Math", "how many ounces in a pound" to "Convert", "convert 100 usd to euros" to "Convert|Search", "how many feet in a mile" to "Convert", "what's 30 c in f" to "Convert",
            "where's a good place to eat" to "FindPlace", "find me a pharmacy" to "FindPlace", "coffee shops nearby" to "FindPlace", "is mcdonalds open" to "PlaceHours", "what time does target close" to "PlaceHours", "directions home" to "Navigate", "how far is chicago" to "Navigate|Search",
            "any news" to "News", "what's happening in the news" to "News", "tech news" to "News", "latest news on the election" to "News",
            "what should i watch tonight" to "Ranked|Search", "good shows to binge" to "Ranked", "where can i watch dune" to "MediaAsk",
            "hey" to "Chat", "hello brick" to "Chat", "thank you so much" to "Chat", "sup" to "Chat", "good night" to "Chat", "help" to "Chat",

            // timers / alarms / stopwatch
            "10 minute timer" to "Timer", "set a timer for ten minutes" to "Timer", "timer 5 min" to "Timer", "give me a 3 minute timer" to "Timer",
            "count down from 5 minutes" to "Timer", "start a countdown for 2 minutes" to "Timer", "ping me in 20 minutes" to "Timer|Reminder",
            "how much time is left" to "TimerControl", "kill the timer" to "TimerControl", "stop the timer" to "TimerControl",
            "wake me up at 7" to "Alarm", "wake me at 6:30 tomorrow" to "Alarm", "alarm for 7am" to "Alarm", "get me up at 8" to "Alarm",
            "set an alarm for 6" to "Alarm", "i need to be up by 7" to "Alarm",
            "start a stopwatch" to "Stopwatch", "time me" to "Stopwatch",
            // reminders / calendar / notes
            "remind me to call mom at 5" to "Reminder", "don't let me forget to take out the trash tonight" to "Reminder",
            "remember to buy milk tomorrow" to "Reminder", "remind me in an hour to check the oven" to "Reminder",
            "add dentist to my calendar friday at 3" to "CalendarEvent", "put lunch with sam on my calendar tomorrow at noon" to "CalendarEvent",
            "schedule a meeting monday at 10" to "CalendarEvent",
            "take a note" to "Note", "jot this down buy eggs" to "Note", "make a note that the wifi password is cheese" to "Note", "write this down" to "Note",
            "what's on my calendar today" to "ReadCalendar", "what do i have tomorrow" to "ReadCalendar", "am i free tomorrow" to "ReadCalendar",
            "what's my schedule look like" to "ReadCalendar", "anything on my calendar this week" to "ReadCalendar",
            // messages / calls
            "text mom i'm on my way" to "Text", "shoot a text to dad saying running late" to "Text", "send a message to sam" to "Text",
            "tell mom i'll be home soon" to "Text", "message alex hey what's up" to "Text", "hit up jake and say yo" to "Text",
            "call mom" to "Call", "ring dad" to "Call", "give sam a call" to "Call", "phone grandma" to "Call", "facetime alex" to "Call",
            "read my texts" to "ReadNotifications", "any new emails" to "ReadNotifications", "did anyone text me" to "ReadNotifications",
            "read my notifications" to "ReadNotifications", "what did mom say" to "ReadNotifications", "check my email" to "ReadNotifications",
            // weather
            "what's it like outside" to "Weather", "do i need an umbrella" to "Weather", "is it gonna rain" to "Weather", "is it cold out" to "Weather",
            "weather" to "Weather", "how hot is it" to "Weather", "should i wear a jacket" to "Weather", "will it snow tomorrow" to "Weather",
            "what's the forecast" to "Weather", "weather this weekend" to "Weather", "what's the temp" to "Weather", "how's the weather in chicago" to "Weather",
            // music
            "play some drake" to "Music", "throw on some kendrick" to "Music", "bump some lofi" to "Music", "put on my liked songs" to "Music",
            "skip this" to "Music", "skip" to "Music", "next song" to "Music", "go back a song" to "Music", "pause" to "Music", "pause the music" to "Music",
            "resume" to "Music", "keep playing" to "Music", "shuffle my music" to "Music", "play something chill" to "Music",
            "what song is this" to "NowPlaying", "what's playing" to "NowPlaying", "who sings this" to "NowPlaying|MediaAsk",
            "like this song" to "LikeSong", "i love this song" to "LikeSong", "save this song" to "LikeSong",
            "add this to my gym playlist" to "AddToPlaylist", "lyrics" to "Lyrics", "show me the lyrics" to "Lyrics", "what are the words to this song" to "Lyrics",
            "turn it up" to "Device|Music", "crank it up" to "Device|Music", "turn it down" to "Device|Music", "louder" to "Device|Music", "quieter" to "Device|Music",
            "volume 50" to "Device", "mute" to "Device|Music", "set the volume to half" to "Device",
            // device / settings
            "turn on the flashlight" to "Device|PhoneSettingReq", "flashlight" to "Device|PhoneSettingReq", "lights on" to "SmartHome|Device|PhoneSettingReq",
            "how much battery do i have" to "Device", "battery" to "Device", "am i charging" to "Device", "what's my battery at" to "Device",
            "turn up the brightness" to "Device|PhoneSettingReq", "make the screen brighter" to "Device|PhoneSettingReq", "dim the screen" to "Device|PhoneSettingReq",
            "wifi off" to "PhoneSettingReq", "turn off bluetooth" to "PhoneSettingReq", "do not disturb on" to "PhoneSettingReq|Device", "silence my phone" to "PhoneSettingReq|Device",
            "put my phone on vibrate" to "PhoneSettingReq|Device", "airplane mode" to "PhoneSettingReq|SettingsPageReq",
            "open wifi settings" to "SettingsPageReq|PhoneSettingReq", 
            "dim the living room lights to 30" to "SmartHome", "turn off the kitchen lights" to "SmartHome", "make the bedroom lights blue" to "SmartHome",
            "open spotify" to "OpenApp", "launch instagram" to "OpenApp", "pull up youtube" to "OpenApp|YouTube", "go to settings" to "OpenApp|SettingsPageReq",
            // time
            "what time is it" to "TellTime", "time" to "TellTime", "what's the date" to "TellTime", "what day is it" to "TellTime", "what time is it in tokyo" to "TellTime",
            "how many days until christmas" to "Countdown", "how long till my birthday" to "Countdown", "days until summer" to "Countdown",
            // math
            "what's 15% of 80" to "Math", "15 times 12" to "Math", "what's 2 plus 2" to "Math", "square root of 144" to "Math", "tip on 45 dollars" to "Math",
            "split 120 four ways" to "Math", "convert 5 miles to km" to "Convert", "how many cups in a gallon" to "Convert", "5 ft in cm" to "Convert",
            "what's 70 f in c" to "Convert", "how do you spell necessary" to "Spell", "spell restaurant" to "Spell",
            // navigation / places
            "take me home" to "Navigate", "navigate to the airport" to "Navigate", "directions to walmart" to "Navigate|FindPlace",
            "find coffee near me" to "FindPlace", "i'm hungry" to "FindPlace|Chat", "where's the nearest gas station" to "FindPlace|Navigate", "gas near me" to "FindPlace",
            "any good pizza places around here" to "FindPlace", "best tacos nearby" to "FindPlace", "is target open" to "PlaceHours", "when does walmart close" to "PlaceHours",
            "what time does costco open tomorrow" to "PlaceHours",
            "can i see stars tonight" to "Sky", "how dark is the sky tonight" to "Sky", "is it good for stargazing" to "Sky",
            // news / media
            "what's the news" to "News", "headlines" to "News", "any news about tesla" to "News", "what's going on in the world" to "News", "sports news" to "News",
            "good movies on netflix" to "Ranked", "best games of 2024" to "Ranked", "top songs right now" to "Ranked",
            "is dune worth watching" to "MediaAsk", "where can i watch the bear" to "MediaAsk", "where can i stream oppenheimer" to "MediaAsk",
            "what's the newest zelda game" to "LatestWork", "when does the next season of the bear come out" to "Latest",
            "who sang bohemian rhapsody" to "MediaAsk|Search", "who wrote this song" to "MediaAsk",
            "play baby shark on youtube" to "YouTube", "search youtube for cat videos" to "YouTube",
            // search
            "who is zendaya" to "Search", "look up the eiffel tower" to "Search", "google how to tie a tie" to "Search", "what is photosynthesis" to "Search",
            // chat
            "hi" to "Chat", "thanks" to "Chat", "how are you" to "Chat", "who made you" to "Chat", "tell me a joke" to "Chat", "good morning" to "Chat",
            "never mind" to "Chat", "you're awesome" to "Chat", "what can you do" to "Chat",
        )
        val out = StringBuilder()
        var bad = 0
        for ((s, want) in cases) {
            val r = runCatching { IntentParser.parse(s) }.getOrElse { out.append("CRASH $s => $it\n"); bad++; continue }
            val name = r::class.simpleName ?: "?"
            if (name !in want.split("|")) { bad++; out.append("MISS  \"$s\" want $want got $r\n") }
        }
        org.junit.Assert.assertTrue("$bad of ${cases.size} misread:\n$out", bad == 0)
    }

    @Test fun rewritesKeepTheDetails() {
        fun p(s: String) = IntentParser.parse(s)
        assertEquals(Request.Text("dad", "running late"), p("shoot a text to dad saying running late"))
        assertEquals("mom", (p("let mom know i'm running late") as Request.Text).recipient)
        assertEquals("jake", (p("hit up jake and say yo") as Request.Text).recipient)
        assertEquals(Request.Text("dad", null), p("shoot dad a text"))
        assertEquals(Request.Text("dad", "on my way"), p("shoot dad a quick text saying on my way"))
        assertEquals("mom", (p("what did mom say") as Request.ReadNotifications).from)
        assertEquals(CalendarRange.TOMORROW, (p("am i free tomorrow") as Request.ReadCalendar).range)
        assertEquals(50, (p("set the volume to half") as Request.Device).percent)
        assertEquals(DeviceOp.BRIGHT_UP, (p("the screen is too dark") as Request.Device).op)
        assertTrue((p("should i wear a jacket tomorrow") as Request.Weather).tomorrow)
        assertEquals(WeatherAspect.UV, (p("do i need sunscreen") as Request.Weather).aspect)
        // The day's high and low, never a city called "temperature".
        for (s in listOf("Whats the high and low for temperature today?", "what's the high today", "what's the low tonight", "what are the highs and lows today",
            "how low will it get tonight", "what's today's high", "high and low for today")) {
            val w = p(s) as Request.Weather
            assertEquals(s, WeatherAspect.HIGH_LOW, w.aspect)
            assertEquals(s, null, w.city)
        }
        (p("what's tomorrow's high in chicago") as Request.Weather).let { assertEquals("chicago", it.city); assertTrue(it.tomorrow) }
        assertTrue(p("what's the high and low for tesla stock") !is Request.Weather)
        assertEquals(null, (p("what's the weather for temperature") as Request.Weather).city)
        assertEquals(1200L, (p("ping me in 20 minutes") as Request.Timer).seconds)
        assertEquals("take out the trash", (p("don't let me forget to take out the trash tonight") as Request.Reminder).task)
        assertEquals("milk", (p("add milk to my notes") as Request.Note).text?.lowercase())
        // Messaging apps keep their own handling; music commands that already worked aren't touched.
        assertTrue(p("send a discord message to Alex saying sounds good") is Request.Discord)
        assertEquals(Request.LikeSong, p("save this song"))
        assertTrue(p("put on the flashlight") is Request.Device)
        assertTrue(p("tell me about the eiffel tower") is Request.Search)
        assertTrue(p("weather near me") is Request.Weather)
    }
}
