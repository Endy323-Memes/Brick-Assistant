package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagesTest {
    private fun page(said: String, where: Boolean = false): SettingsPage {
        val r = IntentParser.parse(said)
        assertTrue("'$said' parsed as $r", r is Request.SettingsPageReq && r.where == where)
        return (r as Request.SettingsPageReq).page
    }

    @Test fun pages() {
        val cases = mapOf(
            "open display settings" to SettingsPage.DISPLAY,
            "take me to developer options" to SettingsPage.DEVELOPER,
            "i want to change my wallpaper" to SettingsPage.WALLPAPER,
            "change my ringtone" to SettingsPage.SOUNDS,
            "make the text bigger" to SettingsPage.FONT,
            "make everything on the screen bigger" to SettingsPage.SCREEN_ZOOM,
            "check for software updates" to SettingsPage.SOFTWARE_UPDATE,
            "update my phone" to SettingsPage.SOFTWARE_UPDATE,
            "battery settings" to SettingsPage.BATTERY,
            "show me the about phone page" to SettingsPage.ABOUT,
            "what's my imei" to SettingsPage.ABOUT,
            "open settings" to SettingsPage.MAIN,
            "settings" to SettingsPage.MAIN,
            "turn on eye comfort shield" to SettingsPage.EYE_COMFORT,
            "blue light filter" to SettingsPage.EYE_COMFORT,
            "change the refresh rate" to SettingsPage.MOTION_SMOOTHNESS,
            "open wifi calling" to SettingsPage.WIFI_CALLING,
            "data usage" to SettingsPage.DATA_USAGE,
            "pull up my storage" to SettingsPage.STORAGE,
            "free up space" to SettingsPage.STORAGE,
            "set up my fingerprint" to SettingsPage.BIOMETRICS,
            "change my lock screen" to SettingsPage.LOCK_SCREEN,
            "accessibility settings" to SettingsPage.ACCESSIBILITY,
            "change the language" to SettingsPage.LANGUAGE,
            "keyboard settings" to SettingsPage.KEYBOARD,
            "turn off autocorrect" to SettingsPage.KEYBOARD,
            "change the time zone" to SettingsPage.DATE_TIME,
            "open notification settings" to SettingsPage.NOTIFICATIONS,
            "app permissions" to SettingsPage.PERMISSIONS,
            "change my default browser" to SettingsPage.DEFAULT_APPS,
            "open digital wellbeing" to SettingsPage.WELLBEING,
            "factory reset" to SettingsPage.RESET,
            "go to the navigation bar settings" to SettingsPage.NAVIGATION_BAR,
            "edge panel settings" to SettingsPage.EDGE_PANELS,
            "open vpn settings" to SettingsPage.VPN,
            "turn on usb debugging" to SettingsPage.DEVELOPER,
            "open one handed mode" to SettingsPage.ONE_HANDED,
            "back up my phone" to SettingsPage.ACCOUNTS,
            "home screen settings" to SettingsPage.HOME_SCREEN,
            "side button settings" to SettingsPage.SIDE_BUTTON,
            "emergency sos settings" to SettingsPage.SAFETY,
            "open the vibration settings" to SettingsPage.VIBRATION,
            "open connected devices" to SettingsPage.CONNECTED_DEVICES,
            "can you take me to the security settings please" to SettingsPage.SECURITY,
        )
        for ((said, want) in cases) assertEquals(said, want, page(said))
    }

    @Test fun where() {
        assertEquals(SettingsPage.DEVELOPER, page("where are developer options", where = true))
        assertEquals(SettingsPage.WALLPAPER, page("how do i change my wallpaper", where = true))
        assertEquals(SettingsPage.ABOUT, page("where can i find my phone's model number", where = true))
    }

    @Test fun switchesStaySwitches() {
        for (said in listOf("turn off wifi", "open wifi settings", "turn on bluetooth", "airplane mode on", "turn on dark mode", "is nfc on",
                "where is the always on display setting", "turn off touch sounds", "turn off haptic feedback", "turn off the keypad tones")) {
            val r = IntentParser.parse(said)
            assertTrue("'$said' parsed as $r", r is Request.PhoneSettingReq)
        }
        assertEquals(PhoneSetting.TOUCH_SOUNDS, (IntentParser.parse("disable touch sounds") as Request.PhoneSettingReq).setting)
        assertEquals(false, (IntentParser.parse("turn off touch vibration") as Request.PhoneSettingReq).on)
    }

    @Test fun screenTimeout() {
        fun t(said: String) = IntentParser.parse(said).also { assertTrue("'$said' parsed as $it", it is Request.ScreenTimeoutReq) } as Request.ScreenTimeoutReq
        assertEquals(120_000L, t("set my screen timeout to 2 minutes").ms)
        assertEquals(120_000L, t("set the display timeout to two minutes").ms)
        assertEquals(30_000L, t("screen timeout 30 seconds").ms)
        assertEquals(600_000L, t("keep my screen on for 10 minutes").ms)
        assertEquals(60_000L, t("make my screen turn off after a minute").ms)
        assertEquals(300_000L, t("change the screen time out to 5 min").ms)
        assertEquals(1, t("my screen turns off too fast").step)
        assertEquals(1, t("make the screen stay on longer").step)
        assertEquals(-1, t("make my screen time out shorter").step)
        assertEquals(ScreenTimeout.MAX, t("never let my screen turn off").ms)
        assertEquals(SettingAsk.STATUS, t("what's my screen timeout").ask)
        assertEquals(SettingAsk.STATUS, t("how long does my screen stay on").ask)
        assertEquals(SettingAsk.WHERE, t("where is the screen timeout setting").ask)
        assertTrue(t("open screen timeout").open)
        assertFalse(IntentParser.parse("turn off the screen") is Request.ScreenTimeoutReq)
        assertFalse(IntentParser.parse("set a timer for 2 minutes") is Request.ScreenTimeoutReq)
    }

    @Test fun appSettings() {
        fun a(said: String) = IntentParser.parse(said).also { assertTrue("'$said' parsed as $it", it is Request.AppSettingsReq) } as Request.AppSettingsReq
        assertEquals("spotify", a("spotify app settings").app)
        assertEquals(AppPage.INFO, a("open app info for discord").page)
        assertEquals(AppPage.NOTIFICATIONS, a("notification settings for gmail").page)
        assertEquals(AppPage.NOTIFICATIONS, a("turn off notifications from instagram").page)
        assertEquals(AppPage.PERMISSIONS, a("spotify permissions").page)
        assertEquals(AppPage.STORAGE, a("clear the cache for chrome").page)
        assertEquals("tiktok", a("uninstall tiktok").app)
        assertEquals(AppPage.NOTIFICATIONS, a("stop youtube from sending notifications").page)
    }

    @Test fun notSettings() {
        for (said in listOf("what time is it", "play some music", "how much storage does the iphone 16 have", "sounds good",
                "what is a vpn", "set a timer for 5 minutes", "text mom that i'm on my way", "what's the weather", "open spotify",
                "who won the game", "where can i watch dune")) {
            val r = IntentParser.parse(said)
            assertTrue("'$said' parsed as $r", r !is Request.SettingsPageReq && r !is Request.ScreenTimeoutReq && r !is Request.AppSettingsReq)
        }
    }

    @Test fun durations() {
        assertEquals(90_000L, ScreenTimeout.duration("90 seconds"))
        assertEquals(30_000L, ScreenTimeout.duration("half a minute"))
        assertEquals("1 minute 30 seconds", ScreenTimeout.say(90_000))
        assertEquals(60_000L, ScreenTimeout.step(30_000, 1))
        assertEquals(15_000L, ScreenTimeout.step(30_000, -1))
        assertEquals(600_000L, ScreenTimeout.step(600_000, 1))
    }
}
