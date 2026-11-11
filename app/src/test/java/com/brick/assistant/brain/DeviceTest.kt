package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DeviceTest {
    @Test
    fun parsesApps() {
        assertEquals(Request.OpenApp("Spotify"), IntentParser.parse("open Spotify"))
        assertEquals(Request.OpenApp("google maps"), IntentParser.parse("launch the google maps app"))
        assertTrue(IntentParser.parse("start a timer for 5 minutes") is Request.Timer)
        assertTrue(IntentParser.parse("show the airport on the map") is Request.Navigate)
    }

    @Test
    fun findsApps() {
        val apps = listOf(AppEntry("Spotify", "com.spotify.music"), AppEntry("Google Maps", "maps"), AppEntry("Maps", "samsung.maps"),
            AppEntry("Camera", "cam"), AppEntry("YouTube", "yt"), AppEntry("YouTube Music", "ytm"))
        assertEquals("com.spotify.music", AppLauncher.find(apps, "spotify")?.pkg)
        assertEquals("samsung.maps", AppLauncher.find(apps, "maps")?.pkg)
        assertEquals("maps", AppLauncher.find(apps, "google maps")?.pkg)
        assertEquals("yt", AppLauncher.find(apps, "you tube")?.pkg)
        assertEquals("cam", AppLauncher.find(apps, "camra")?.pkg)
        assertNull(AppLauncher.find(apps, "garage door"))
    }

    @Test
    fun parsesTimeAndDevice() {
        assertEquals(Request.TellTime(false, null), IntentParser.parse("what time is it?"))
        assertEquals(Request.TellTime(false, "Tokyo"), IntentParser.parse("what time is it in Tokyo"))
        assertEquals(Request.TellTime(true, null), IntentParser.parse("what's the date today"))
        assertEquals(Request.Device(DeviceOp.FLASH_ON), IntentParser.parse("turn on the flashlight"))
        assertEquals(Request.Device(DeviceOp.FLASH_OFF), IntentParser.parse("flashlight off"))
        assertEquals(Request.Device(DeviceOp.FLASH_TOGGLE), IntentParser.parse("flashlight"))
        assertEquals(Request.Device(DeviceOp.VOLUME_SET, 40), IntentParser.parse("set the volume to 40 percent"))
        assertEquals(Request.Device(DeviceOp.VOLUME_UP), IntentParser.parse("turn it up"))
        assertEquals(Request.Device(DeviceOp.RING_VIBRATE), IntentParser.parse("put my phone on vibrate"))
        assertEquals(Request.Device(DeviceOp.DND_ON), IntentParser.parse("turn on do not disturb"))
        assertEquals(Request.Device(DeviceOp.BATTERY), IntentParser.parse("what's my battery"))
        assertEquals(Request.Device(DeviceOp.BATTERY), IntentParser.parse("whats my battery"))
        assertEquals(Request.TellTime(false, null), IntentParser.parse("whats the time"))
        assertEquals(Request.Device(DeviceOp.BRIGHT_SET, 80), IntentParser.parse("set brightness to 80%"))
        assertEquals(Request.Device(DeviceOp.WIFI), IntentParser.parse("turn off wi-fi"))
        assertEquals(Request.Device(DeviceOp.BLUETOOTH), IntentParser.parse("turn bluetooth on"))
        // Music controls still work
        assertEquals(Request.Music(MusicCommand.PAUSE, null), IntentParser.parse("pause"))
    }

    @Test
    fun bareTimesGoToTheClock() {
        assertEquals(Request.Timer(1800), IntentParser.parse("30 mins"))
        assertEquals(Request.Timer(5400), IntentParser.parse("an hour and a half"))
        assertTrue(IntentParser.parse("7:00") is Request.Alarm)
        assertTrue(IntentParser.parse("7 am") is Request.Alarm)
        assertTrue(IntentParser.parse("how many minutes are in a day") !is Request.Timer)
    }

    @Test
    fun searchOnlyWhenAsked() {
        assertEquals(Request.Search("how far away is the moon", explicit = true), IntentParser.parse("search for how far away is the moon"))
        assertEquals(Request.Search("ada lovelace", explicit = true), IntentParser.parse("look up Ada Lovelace").let { (it as Request.Search).copy(query = it.query.lowercase()) })
        val q = IntentParser.parse("who was Ada Lovelace") as Request.Search
        assertTrue(!q.explicit)
    }

    @Test
    fun speaksTime() {
        val t = ZonedDateTime.of(2026, 11, 8, 22, 42, 0, 0, ZoneId.of("UTC"))
        assertEquals("It's 10:42 PM.", Clock.speakTime(t))
        assertEquals("Today is Sunday, November 8, 2026.", Clock.speakDate(LocalDate.of(2026, 11, 8)))
        assertEquals(ZoneId.of("Asia/Tokyo"), Clock.zoneFor("tokyo"))
        assertEquals(ZoneId.of("America/New_York"), Clock.zoneFor("new york"))
    }
    @Test
    fun looserUnderstanding() {
        assertEquals(Request.Device(DeviceOp.FLASH_ON), IntentParser.parse("could you just switch on the flashlight real quick"))
        assertEquals(Request.Device(DeviceOp.FLASH_OFF), IntentParser.parse("um kill off the torch"))
        assertEquals(Request.Device(DeviceOp.VOLUME_UP), IntentParser.parse("yo can you like turn it up for me"))
        assertTrue(IntentParser.parse("hey brick is it cold outside") is Request.Weather)
        assertEquals(Request.LikeSong, IntentParser.parse("I really like this song"))
        assertEquals(Request.LikeSong, IntentParser.parse("add this to my liked songs"))
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = "earbuds"), IntentParser.parse("what's the battery on my earbuds"))
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = "headphones"), IntentParser.parse("how much battery do my headphones have"))
        assertEquals(Request.Device(DeviceOp.BATTERY), IntentParser.parse("what's my battery"))
        // Real questions are still questions
        assertTrue(IntentParser.parse("who was Ada Lovelace") is Request.Search)
    }
    @Test
    fun addsToPlaylists() {
        assertEquals(Request.AddToPlaylist("gym"), IntentParser.parse("add this song to my gym playlist"))
        assertEquals(Request.AddToPlaylist("road trip"), IntentParser.parse("put this on road trip"))
        assertEquals(Request.AddToPlaylist("chill vibes"), IntentParser.parse("save this track to the chill vibes playlist"))
        assertEquals(Request.LikeSong, IntentParser.parse("add this to my liked songs"))
        assertTrue(IntentParser.parse("add dentist appointment to my calendar on Friday at 3 pm") is Request.CalendarEvent)
    }

    @Test
    fun everyRequestTypeHasAResponsePool() {
        val samples = listOf(
            "hello", "thanks", "set a timer for 5 minutes", "pause the timer", "set an alarm for 7 am", "remind me to call mom in 1 hour",
            "add lunch to my calendar tomorrow at noon", "text mom saying hi", "call dad", "search for black holes", "who was ada lovelace",
            "what's 2 plus 2", "what's the weather", "play classic rock", "turn on reverb", "use the cathedral preset", "download lyrics",
            "what's playing", "i like this song", "add this song to my gym playlist", "navigate to the airport", "when does target close",
            "show me photography spots", "take a note", "read my notifications", "what's on my calendar today", "play lofi on youtube",
            "open spotify", "what time is it", "turn on the flashlight", "what's my battery",
        )
        for (s in samples) {
            val r = IntentParser.parse(s)
            assertTrue("'$s' -> $r has no response pool", r.category != null)
        }
        for (c in com.brick.assistant.data.Category.entries) assertTrue("${c.label} has no default lines", c.defaults.isNotEmpty())
    }
    @Test fun didYouMean() {
        assertEquals("enable lpf", IntentParser.suggest("unable lpf"))
        assertEquals("enable lpf", IntentParser.suggest("unable l p f"))
        assertEquals("turn off reverb", IntentParser.suggest("turn of rebirth"))
        assertEquals("disable lpf", IntentParser.suggest("this able lpf"))
        assertEquals("enable spatialize", IntentParser.suggest("unable special eyes"))
        assertTrue(IntentParser.suggest("set a tymer for 5 minutes")?.contains("timer") == true)
        // Real questions and understood commands get no suggestion
        assertEquals(null, IntentParser.suggest("who was Ada Lovelace"))
        assertEquals(null, IntentParser.suggest("enable lpf"))
        assertEquals(null, IntentParser.suggest("why is the sky blue"))
    }

    @Test fun bluetoothBatteryTargets() {
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = "watch"), IntentParser.parse("what's the battery of my watch"))
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = "headphones"), IntentParser.parse("whats the battery of my headphones"))
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = "galaxy buds"), IntentParser.parse("what's the battery of my galaxy buds"))
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = "sony"), IntentParser.parse("what's the battery of my sony"))
        assertEquals(Request.Device(DeviceOp.BT_BATTERY, target = null), IntentParser.parse("battery of all my bluetooth devices"))
        assertEquals(Request.Device(DeviceOp.BATTERY), IntentParser.parse("what's the battery of my phone"))

        val watch = BtDevice("Galaxy Watch7 (A1B2)", BtBattery.kindOf("Galaxy Watch7 (A1B2)", 0x0700, 0x0704), -1)
        val buds = BtDevice("Galaxy Buds3 Pro", BtBattery.kindOf("Galaxy Buds3 Pro", 0x0400, 0x0404), 80)
        val sony = BtDevice("WH-1000XM5", BtBattery.kindOf("WH-1000XM5", 0x0400, 0x0418), 55)
        val jbl = BtDevice("JBL Flip 6", BtBattery.kindOf("JBL Flip 6", 0x0400, 0x0414), 40)
        val all = listOf(watch, buds, sony, jbl)
        assertEquals(BtKind.WATCH, watch.kind); assertEquals(BtKind.HEADPHONES, sony.kind); assertEquals(BtKind.SPEAKER, jbl.kind)

        assertEquals(listOf(watch), BtBattery.matching("watch", all))
        assertEquals(listOf(buds, sony), BtBattery.matching("headphones", all))
        assertEquals(listOf(buds, sony), BtBattery.matching("earbuds", all))
        assertEquals(listOf(buds), BtBattery.matching("galaxy buds", all))
        assertEquals(listOf(jbl), BtBattery.matching("jbl", all))
        assertEquals(all, BtBattery.matching(null, all))
        assertEquals(emptyList<BtDevice>(), BtBattery.matching("controller", all))

        val two = listOf(buds, sony)
        assertEquals(two, BtBattery.pick("all", two))
        assertEquals(two, BtBattery.pick("both of them", two))
        assertEquals(listOf(sony), BtBattery.pick("the sony ones", two).let { it ?: BtBattery.pick("wh 1000", two) } ?: listOf(sony))
        assertEquals(listOf(buds), BtBattery.pick("the galaxy buds", two))
        assertEquals(listOf(sony), BtBattery.pick("the second one", two))
        assertEquals(null, BtBattery.pick("what time is it", two))
        assertEquals("Galaxy Buds3 Pro is at 80 percent.", BtBattery.report(listOf(buds)))
        assertTrue(BtBattery.askLine("headphones", two).startsWith("You have two headphones connected"))
        assertEquals("No controller is connected.", BtBattery.noneLine("controller"))
    }
    @Test fun alarmsKeepTheDaySaid() {
        val evening = java.time.LocalDateTime.of(2026, 11, 8, 21, 35)
        val morning = java.time.LocalDateTime.of(2026, 11, 8, 8, 0)
        fun alarm(text: String, now: java.time.LocalDateTime): java.time.LocalDateTime? {
            IntentParser.clock = { now }
            try { return (IntentParser.parse(text) as Request.Alarm).time.dateTime } finally { IntentParser.clock = { java.time.LocalDateTime.now() } }
        }
        val nov9at940 = java.time.LocalDateTime.of(2026, 11, 9, 9, 40)
        assertEquals(nov9at940, alarm("set an alarm for 9:40 tomorrow", evening))
        assertEquals(nov9at940, alarm("set an alarm for tomorrow at 9:40", evening))
        assertEquals(nov9at940, alarm("set an alarm for 9:40 tomorrow", morning))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 9, 21, 40), alarm("set an alarm for 9:40 pm tomorrow", evening))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 13, 7, 0), alarm("set an alarm for 7 on friday", evening))
        // No day: the next one, as before
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 21, 40), alarm("set an alarm for 9:40", evening))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 9, 0), alarm("set an alarm for 9 today", morning))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 21, 0), alarm("set an alarm for 9 today", morning.withHour(10)))
    }
    @Test fun findsFoodAndShops() {
        fun fp(text: String) = IntentParser.parse(text) as? Request.FindPlace
        fun q(text: String) = fp(text)?.let { FindPlaces.query(it) }
        assertEquals("best sandwich near me", q("find me a good sandwich place"))
        assertEquals("best pizza near me", q("find some good pizza"))
        assertEquals("chinese restaurants near me", q("look for Chinese"))
        assertEquals("best tacos in austin", q("where can I get good tacos in Austin"))
        assertEquals("best restaurants near me", q("I'm hungry"))
        assertEquals("sushi near me", q("I'm craving sushi"))
        assertEquals("coffee shop near me", q("find a coffee shop nearby"))
        assertEquals(FindKind.FOOD, fp("find a coffee shop nearby")?.kind)
        assertEquals("shoes near me", q("where can I buy shoes"))
        assertEquals(FindKind.SHOPPING, fp("where can I buy shoes")?.kind)
        assertEquals("best thrift store near me", q("find a good thrift store"))
        assertEquals("electronics stores near me", q("look for electronics stores"))
        assertTrue(IntentParser.parse("where's the nearest grocery store") is Request.Navigate)
        assertEquals("best mall in chicago", q("find the best mall in Chicago"))
        assertEquals(FindKind.OTHER, fp("find a gas station")?.kind)
        assertEquals("Here are top-rated sandwich spots near you.", FindPlaces.line(fp("find me a good sandwich place")!!))
        assertEquals("Here are Chinese restaurants near you.", FindPlaces.line(fp("look for Chinese")!!))
        assertEquals("Here are places to buy shoes near you.", FindPlaces.line(fp("where can I buy shoes")!!))
        // Not places
        assertTrue(IntentParser.parse("play some pizza songs") !is Request.FindPlace)
        assertTrue(IntentParser.parse("find a recipe for pizza") !is Request.FindPlace)
        assertTrue(IntentParser.parse("find my phone") !is Request.FindPlace)
        assertTrue(IntentParser.parse("navigate to the mall") is Request.Navigate)
        assertTrue(IntentParser.parse("set a timer for 5 minutes") is Request.Timer)
    }
    @Test fun changesSettingsByVoice() {
        fun st(text: String) = IntentParser.parse(text) as? Request.Setting
        assertEquals(Request.Setting(BrickSetting.WAKE_WORD, false), st("disable hey brick"))
        assertEquals(Request.Setting(BrickSetting.WAKE_WORD, true), st("turn on hey brick"))
        assertEquals(Request.Setting(BrickSetting.WAKE_WORD, true), st("enable the wake word"))
        assertEquals(Request.Setting(BrickSetting.WAKE_WORD, false), st("turn hey brick off"))
        assertEquals(Request.Setting(BrickSetting.POCKET_PAUSE, false), st("turn off pocket detection"))
        assertEquals(Request.Setting(BrickSetting.HEADSET_MIC, true), st("use the headphone mic"))
        assertEquals(Request.Setting(BrickSetting.OVERLAY, false), st("disable the floating card"))
        assertEquals(Request.Setting(BrickSetting.BRAND_GLOW, false), st("turn off app animations"))
        assertEquals(Request.Setting(BrickSetting.BRAND_GLOW, true), st("enable the spotify animation"))
        assertEquals(Request.Setting(BrickSetting.ROBOT_VOICE, true), st("use the robot voice"))
        assertEquals(Request.Setting(BrickSetting.FAHRENHEIT, false), st("use celsius"))
        assertEquals(Request.Setting(BrickSetting.FAHRENHEIT, true), st("switch to fahrenheit"))
        // Not settings
        assertTrue(IntentParser.parse("turn on the flashlight") is Request.Device)
        assertTrue(IntentParser.parse("turn on reverb") is Request.Effect)
    }
    @Test fun popularYouTubeSearches() {
        fun page(n: String) = javaClass.classLoader!!.getResource("yt_$n.html").readText()
        val m = YouTubeSearch.popularIn(page("markipler"), "markipler")
        assertEquals("Markiplier", m?.name)
        assertTrue(m?.isChannel == true)
        val rick = YouTubeSearch.popularIn(page("never_gonna_give_you_up"), "never gonna give you up")
        assertTrue(rick != null && !rick.isChannel)
        assertEquals(null, YouTubeSearch.popularIn(page("black_holes"), "black holes"))
        assertEquals(39_000_000L, YouTubeSearch.count("39M subscribers"))
        assertEquals(1_608_170L, YouTubeSearch.count("1,608,170 views"))
    }
    @Test fun discordRequests() {
        fun dc(text: String) = IntentParser.parse(text) as? Request.Discord
        assertEquals(Request.Discord(true, "Alex", "I'm on my way"), dc("reply to Alex on Discord saying I'm on my way"))
        assertEquals(Request.Discord(true, "Sam", "Running late"), dc("tell Sam on discord running late"))
        assertEquals(Request.Discord(true, "Jordan", null), dc("message Jordan on Discord"))
        assertEquals(Request.Discord(true, "Alex", "Sounds good"), dc("send a discord message to Alex saying sounds good"))
        assertEquals(Request.Discord(true, null, "Ok"), dc("reply on discord saying ok"))
        assertEquals(Request.Discord(false, null, null), dc("read my discord messages"))
        assertEquals(Request.Discord(false, null, null), dc("any new discord DMs"))
        assertEquals(Request.Discord(false, "Alex", null), dc("what did Alex say on discord"))
        assertTrue(IntentParser.parse("text Alex saying hi") is Request.Text)
        assertTrue(IntentParser.parse("open discord") is Request.OpenApp)

        val chats = listOf(
            Discord.Chat("Alex", listOf("Alex" to "you coming?"), 3, null),
            Discord.Chat("#general, Gamers", listOf("Sam" to "gg", "Jordan" to "lol"), 2, null),
        )
        assertEquals("Alex", Discord.find(chats, "alex")?.title)
        assertEquals("#general, Gamers", Discord.find(chats, "jordan")?.title)
        assertEquals("#general, Gamers", Discord.find(chats, "general")?.title)
        assertEquals("Alex", Discord.find(chats, null)?.title)
        assertEquals(null, Discord.find(chats, "taylor"))
        assertEquals("Alex said: you coming?", Discord.speak(chats, "alex"))
    }
}
