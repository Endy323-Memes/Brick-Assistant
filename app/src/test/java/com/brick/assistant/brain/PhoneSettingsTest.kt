package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone settings and smart home in loose, everyday wording. */
class PhoneSettingsTest {
    private fun p(s: String) = IntentParser.parse(s)
    private fun set(s: PhoneSetting, on: Boolean?) = Request.PhoneSettingReq(s, on)
    private fun status(s: PhoneSetting) = Request.PhoneSettingReq(s, ask = SettingAsk.STATUS)
    private fun where(s: PhoneSetting) = Request.PhoneSettingReq(s, ask = SettingAsk.WHERE)

    @Test
    fun switchingInAnyWording() {
        assertEquals(set(PhoneSetting.WIFI, false), p("kill the wifi"))
        assertEquals(set(PhoneSetting.WIFI, true), p("can you turn my wifi back on"))
        assertEquals(set(PhoneSetting.WIFI, true), p("I need wi-fi"))
        assertEquals(set(PhoneSetting.BLUETOOTH, false), p("shut off bluetooth"))
        assertEquals(set(PhoneSetting.AIRPLANE, true), p("put my phone in airplane mode"))
        assertEquals(set(PhoneSetting.AIRPLANE, true), p("flight mode on"))
        assertEquals(set(PhoneSetting.AIRPLANE, false), p("get me out of airplane mode"))
        assertEquals(set(PhoneSetting.HOTSPOT, true), p("start my hotspot"))
        assertEquals(set(PhoneSetting.HOTSPOT, true), p("I want to share my internet"))
        assertEquals(set(PhoneSetting.MOBILE_DATA, false), p("turn off mobile data"))
        assertEquals(set(PhoneSetting.NFC, true), p("enable tap to pay"))
        assertEquals(set(PhoneSetting.QUICK_SHARE, true), p("turn on quick share"))
        assertEquals(set(PhoneSetting.AUTO_ROTATE, true), p("turn on auto rotate"))
        assertEquals(set(PhoneSetting.AUTO_ROTATE, false), p("lock the screen rotation"))
        assertEquals(set(PhoneSetting.AUTO_ROTATE, false), p("turn on rotation lock"))
        assertEquals(set(PhoneSetting.ADAPTIVE_BRIGHTNESS, false), p("disable auto brightness"))
        assertEquals(set(PhoneSetting.DARK_MODE, true), p("switch to dark mode"))
        assertEquals(set(PhoneSetting.DARK_MODE, false), p("switch to light mode"))
        assertEquals(set(PhoneSetting.AOD, false), p("turn off always on display"))
        assertEquals(set(PhoneSetting.AOD, true), p("enable AOD"))
        assertEquals(set(PhoneSetting.POWER_SAVING, true), p("turn on battery saver"))
        assertEquals(set(PhoneSetting.POWER_SAVING, true), p("I need to save battery"))
        assertEquals(set(PhoneSetting.POWER_SAVING, false), p("get rid of power saving mode"))
        assertEquals(set(PhoneSetting.LOCATION, false), p("turn off location"))
        assertEquals(set(PhoneSetting.DND, true), p("enable quiet mode"))
        assertEquals(set(PhoneSetting.WIFI, null), p("toggle wifi"))
        assertEquals(set(PhoneSetting.MEDIA_OUTPUT, null), p("switch audio to my headphones"))
    }

    @Test
    fun statusWhereAndOverview() {
        assertEquals(status(PhoneSetting.NFC), p("is NFC on"))
        assertEquals(status(PhoneSetting.HOTSPOT), p("is my hotspot on"))
        assertEquals(status(PhoneSetting.AIRPLANE), p("airplane mode"))
        assertEquals(where(PhoneSetting.AOD), p("where is the always on display setting"))
        assertEquals(where(PhoneSetting.NFC), p("how do I turn on NFC"))
        assertEquals(where(PhoneSetting.QUICK_SHARE), p("where can I find quick share"))
        assertEquals(where(PhoneSetting.HOTSPOT), p("which menu is hotspot in"))
        assertEquals(Request.PhoneSettingReq(PhoneSetting.NFC, ask = SettingAsk.STATUS, open = true), p("open NFC settings"))
        assertEquals(Request.PhoneSettingReq(null, ask = SettingAsk.STATUS), p("show my phone settings"))
        assertEquals(Request.PhoneSettingReq(null, ask = SettingAsk.STATUS), p("quick settings"))
    }

    @Test
    fun volumeAndSoundModeStayWithTheirControls() {
        assertEquals(Request.Device(DeviceOp.VOLUME_UP), p("bump the volume up a little"))
        assertEquals(Request.Device(DeviceOp.VOLUME_SET, 30), p("can you put the volume at 30"))
        assertEquals(Request.Device(DeviceOp.RING_VIBRATE), p("switch the sound mode to vibrate"))
    }

    @Test
    fun smartHome() {
        assertEquals(Request.SmartHome("living room", on = true), p("turn on the living room lights"))
        assertEquals(Request.SmartHome(null, on = false), p("lights off"))
        assertEquals(Request.SmartHome("bedroom", on = false), p("kill the bedroom lamp"))
        assertEquals(Request.SmartHome(null, on = true, brightness = 30), p("dim the lights to 30 percent"))
        assertEquals(Request.SmartHome(null, on = true, color = "blue"), p("make the lights blue"))
        assertEquals(Request.SmartHome("desk", on = true, color = "warm white"), p("set the desk light to warm white"))
        assertEquals(Request.SmartHome(null, app = HomeApp.GOOGLE_HOME), p("open google home"))
        assertEquals(Request.SmartHome(null), p("show my smart home"))
    }

    @Test
    fun otherRequestsLeftAlone() {
        assertEquals(Request.Device(DeviceOp.FLASH_ON), p("turn on the flashlight"))
        assertFalse(p("text mom I'm turning off my wifi") is Request.PhoneSettingReq)
        assertFalse(p("remind me to turn on airplane mode at 9") is Request.PhoneSettingReq)
        assertFalse(p("share my location with dad") is Request.PhoneSettingReq)
        assertFalse(p("what is NFC") is Request.PhoneSettingReq)
        assertFalse(p("bortle level and light pollution here") is Request.SmartHome)
        assertFalse(p("turn off hey brick") is Request.PhoneSettingReq)
        assertTrue(p("play some music") is Request.Music)
    }

    @Test
    fun goveeMatching() {
        val list = listOf(
            GoveeDevice("H6159", "a", "Living Room Strip", true, true),
            GoveeDevice("H6008", "b", "Bedroom Lamp", true, true),
            GoveeDevice("H5080", "c", "Desk Plug", false, false),
        )
        assertEquals(3, Govee.match(null, list).size)
        assertEquals(listOf("Bedroom Lamp"), Govee.match("bedroom", list).map { it.name })
        assertEquals(listOf("Living Room Strip"), Govee.match("the living room lights", list).map { it.name })
        assertTrue(Govee.match("garage", list).isEmpty())
        val parsed = Govee.parseDevices(org.json.JSONArray(
            """[{"sku":"H6159","device":"a","deviceName":"Strip","capabilities":[{"instance":"powerSwitch"},{"instance":"brightness"},{"instance":"colorRgb"}]},
                {"sku":"H7","device":"x","deviceName":"Sensor","capabilities":[{"instance":"sensorTemperature"}]}]""",
        ))
        assertEquals(listOf("Strip"), parsed.map { it.name })
        assertTrue(parsed[0].canDim && parsed[0].canColor)
    }
}
