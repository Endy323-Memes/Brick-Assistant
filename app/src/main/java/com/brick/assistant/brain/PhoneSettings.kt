package com.brick.assistant.brain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** What a phone-setting request wants: switch it, say its state, or say where it is in Settings. */
enum class SettingAsk { SET, STATUS, WHERE }

/** How Brick can change a setting: by itself, through the assistant (voice) channel, or only by opening it. */
enum class SettingReach { DIRECT, ASSISTANT, OPEN }

/**
 * The phone settings Brick knows: what you might call each one ([words], a regex matched anywhere in the
 * sentence), where it lives in the Samsung Settings app ([path]) and the quick panel ([quick]), and the short
 * capital label for the readouts ([tag], at most 7 letters).
 */
enum class PhoneSetting(
    val title: String,
    val tag: String,
    val words: String,
    val path: String,
    val quick: String?,
    val reach: SettingReach,
) {
    WIFI("Wi-Fi", "WI-FI",
        "wi ?-?fi|wifi|wlan|wireless (?:internet|network|connection)",
        "Settings > Connections > Wi-Fi", "Wi-Fi", SettingReach.OPEN),
    BLUETOOTH("Bluetooth", "BT",
        "blue ?-?tooth|\\bbt\\b",
        "Settings > Connections > Bluetooth", "Bluetooth", SettingReach.OPEN),
    AIRPLANE("Airplane mode", "PLANE",
        "air ?plane(?: mode)?|aeroplane(?: mode)?|flight(?: safe)? mode|plane mode|offline mode",
        "Settings > Connections > Airplane mode", "Airplane mode", SettingReach.ASSISTANT),
    MOBILE_DATA("Mobile data", "DATA",
        "mobile data|cellular(?: data)?|cell data|cell service|data connection|mobile internet|\\bdata\\b(?! cent)|\\blte\\b|\\b5g\\b",
        "Settings > Connections > Data usage > Mobile data", "Mobile data", SettingReach.OPEN),
    HOTSPOT("Mobile hotspot", "HOTSPOT",
        "hot ?spot|tether(?:ing)?|share (?:my )?(?:internet|data|connection)",
        "Settings > Connections > Mobile Hotspot and Tethering > Mobile Hotspot", "Hotspot", SettingReach.OPEN),
    NFC("NFC", "NFC",
        "\\bnfc\\b|tap (?:to|and) pay|contactless(?: pay(?:ments?)?)?|near ?field",
        "Settings > Connections > NFC and contactless payments", "NFC", SettingReach.OPEN),
    QUICK_SHARE("Quick Share", "SHARE",
        "quick ?share|nearby share|air ?drop|share nearby",
        "Settings > Connected devices > Quick Share", "Quick Share", SettingReach.OPEN),
    MEDIA_OUTPUT("Media output", "OUTPUT",
        "media output|audio output|sound output|output device|audio device|cast (?:the )?(?:audio|sound)|" +
            "(?:switch|change|move) (?:the )?(?:audio|sound|speakers?|output)(?! mode)|which speaker|" +
            "play (?:the )?(?:sound|audio|music|media) (?:through|on|from|out of)",
        "Quick panel > Media output (under the media player)", "Media output", SettingReach.OPEN),
    LOCATION("Location", "GPS",
        "location(?: services| tracking| setting| access)?|\\bgps\\b",
        "Settings > Location", "Location", SettingReach.OPEN),
    AUTO_ROTATE("Auto rotate", "ROTATE",
        "auto ?-?rotat\\w*|(?:screen )?rotation(?: lock)?|rotate (?:the |my )?screen|screen (?:rotate|rotating|orientation)|" +
            "portrait (?:mode|lock)|landscape (?:mode|lock)|orientation lock",
        "Quick panel > Auto rotate (Portrait when off)", "Auto rotate", SettingReach.DIRECT),
    ADAPTIVE_BRIGHTNESS("Adaptive brightness", "ADAPT",
        "adaptive brightness|auto ?-?brightness|automatic brightness|brightness (?:auto|automatic(?:ally)?)",
        "Settings > Display > Adaptive brightness", null, SettingReach.DIRECT),
    DARK_MODE("Dark mode", "DARK",
        "dark (?:mode|theme)|night (?:mode|theme)|light (?:mode|theme)|dark ui",
        "Settings > Display > Light / Dark", "Dark mode", SettingReach.OPEN),
    AOD("Always On Display", "AOD",
        "always ?-?on(?: display| screen)?|\\baod\\b|clock on (?:the |my )?(?:lock ?screen|screen when it's off)",
        "Settings > Lock screen and AOD > Always On Display", null, SettingReach.OPEN),
    POWER_SAVING("Power saving", "SAVER",
        "(?:power|battery|energy) sav(?:ing|er|e)(?: mode)?|low power(?: mode)?|battery saving|eco mode|save (?:the |my )?battery",
        "Settings > Battery > Power saving", "Power saving", SettingReach.ASSISTANT),
    DND("Do Not Disturb", "DND",
        "do not disturb|\\bdnd\\b|quiet mode|focus mode|don't disturb",
        "Settings > Modes and Routines > Do not disturb", "Do not disturb", SettingReach.DIRECT),
    SOUND_MODE("Sound mode", "SOUND",
        "sound mode|ringer(?: mode)?|ring mode|vibrate mode|silent mode|mute mode",
        "Settings > Sounds and vibration > Sound / Vibrate / Mute", "Sound", SettingReach.DIRECT),
    VOLUME("Volume", "VOLUME",
        "volume(?: settings| levels?| panel)?",
        "Settings > Sounds and vibration > Volume", null, SettingReach.DIRECT),
    TOUCH_SOUNDS("Touch sounds", "TOUCH",
        "touch (?:sounds?|tones?|clicks?)|tap(?:ping)? sounds?|click(?:ing)? sounds?|screen (?:tap|touch) sounds?|sounds? when (?:i )?(?:touch|tap)",
        "Settings > Sounds and vibration > System sound > Touch interactions", null, SettingReach.DIRECT),
    HAPTICS("Touch vibration", "HAPTIC",
        "haptics?(?! settings| strength| intensity)|haptic feedback|touch vibrations?|vibrat(?:e|ion) (?:on|when) (?:i )?(?:touch|tap)|keyboard vibrations?|vibrat(?:e|ion) on (?:the )?keyboard",
        "Settings > Sounds and vibration > System vibration > Touch interactions", null, SettingReach.DIRECT),
    KEYPAD_TONES("Dialing keypad tones", "KEYPAD",
        "dial(?:ing)? ?(?:pad|keypad) (?:tones?|sounds?)|keypad (?:tones?|sounds?|beeps?)|dial ?tones?|(?:tones?|beeps?) when (?:i )?dial",
        "Settings > Sounds and vibration > System sound > Dialing keypad", null, SettingReach.DIRECT),
    ;

    /** Only the main switches go on the phone settings overview (these smaller ones are asked for by name). */
    val inOverview: Boolean get() = this != TOUCH_SOUNDS && this != HAPTICS && this != KEYPAD_TONES

    /** The pattern, compiled once. */
    val regex: Regex by lazy { Regex("\\b(?:$words)", RegexOption.IGNORE_CASE) }

    /** "Quick panel: swipe down twice, then Auto rotate", or null when it has no tile. */
    val quickLine: String? get() = quick?.let { "Quick panel: swipe down from the top twice, then the $it tile" }

    /** The spoken path: "Settings, Connections, NFC and contactless payments". */
    val spokenPath: String get() = path.replace(" > ", ", ").replace(" (", ", ").removeSuffix(")")

    companion object {
        /**
         * The setting [said] is about, or null. Several can match ("hotspot data"): the earliest in this list wins,
         * and Location is only picked when nothing else matched ("the location of the NFC setting" is NFC).
         */
        fun find(said: String): PhoneSetting? {
            val hits = entries.filter { it.regex.containsMatchIn(said) }
            if (hits.isEmpty()) return null
            return hits.firstOrNull { it != LOCATION && it != MOBILE_DATA && it != VOLUME }
                ?: hits.firstOrNull { it != LOCATION } ?: hits.first()
        }
    }
}

/** Reads and changes the phone's settings, as far as Android lets an assistant app. */
object PhoneSettings {
    /** The state as on (true) / off (false), or null when it isn't an on/off setting or can't be read. */
    fun isOn(context: Context, s: PhoneSetting): Boolean? = try {
        val cr = context.contentResolver
        when (s) {
            PhoneSetting.WIFI -> context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled
            PhoneSetting.BLUETOOTH -> context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled
            PhoneSetting.AIRPLANE -> Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            PhoneSetting.MOBILE_DATA -> Settings.Global.getInt(cr, "mobile_data", -1).takeIf { it >= 0 }?.let { it == 1 }
            PhoneSetting.HOTSPOT -> hotspotOn(context)
            PhoneSetting.NFC -> android.nfc.NfcAdapter.getDefaultAdapter(context)?.isEnabled
            PhoneSetting.QUICK_SHARE -> null
            PhoneSetting.MEDIA_OUTPUT -> null
            PhoneSetting.LOCATION -> context.getSystemService(android.location.LocationManager::class.java)?.let {
                if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled else null
            }
            PhoneSetting.AUTO_ROTATE -> Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            PhoneSetting.ADAPTIVE_BRIGHTNESS ->
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            PhoneSetting.DARK_MODE ->
                (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            PhoneSetting.AOD -> Settings.System.getInt(cr, "aod_mode", -1).takeIf { it >= 0 }?.let { it == 1 }
            PhoneSetting.POWER_SAVING -> context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode
            PhoneSetting.DND -> context.getSystemService(android.app.NotificationManager::class.java)?.let {
                it.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            }
            PhoneSetting.SOUND_MODE, PhoneSetting.VOLUME -> null
            PhoneSetting.TOUCH_SOUNDS -> Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1
            PhoneSetting.HAPTICS -> Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
            PhoneSetting.KEYPAD_TONES -> Settings.System.getInt(cr, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1
        }
    } catch (_: Exception) {
        null
    }

    /** A short state for the readouts: "on", "off", "vibrate", "Galaxy Buds3 Pro", "unknown". */
    fun stateText(context: Context, s: PhoneSetting): String = when (s) {
        PhoneSetting.MEDIA_OUTPUT -> mediaOutput(context)
        PhoneSetting.SOUND_MODE -> soundMode(context)
        PhoneSetting.VOLUME -> context.getSystemService(AudioManager::class.java)?.let { am ->
            "media " + (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)) + "%"
        } ?: "unknown"
        PhoneSetting.QUICK_SHARE -> "open to check"
        else -> when (isOn(context, s)) { true -> "on"; false -> "off"; null -> "unknown" }
    }

    fun soundMode(context: Context): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "mute"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "sound"
        }
    }

    /** Where media plays now: a connected headset or speaker's name, or the phone speaker. */
    fun mediaOutput(context: Context): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        val external = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_HEARING_AID,
        ) + (if (Build.VERSION.SDK_INT >= 31) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) else emptySet())
        val d = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in external } } catch (_: Exception) { null }
        return d?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "phone speaker"
    }

    private fun hotspotOn(context: Context): Boolean? = try {
        val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val state = wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int
        state == 13 // WIFI_AP_STATE_ENABLED
    } catch (_: Exception) {
        null
    }

    /** Whether Brick may write system settings (auto rotate, adaptive brightness). */
    fun canWrite(context: Context) = Settings.System.canWrite(context)

    fun writePermission(context: Context) =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))

    /** Changes a [SettingReach.DIRECT] setting. False when Android refused. */
    fun setDirect(context: Context, s: PhoneSetting, on: Boolean): Boolean = try {
        val cr = context.contentResolver
        when (s) {
            PhoneSetting.AUTO_ROTATE -> Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            PhoneSetting.ADAPTIVE_BRIGHTNESS -> Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            PhoneSetting.TOUCH_SOUNDS -> Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0).also { ok ->
                if (ok) context.getSystemService(AudioManager::class.java)?.let { if (on) it.loadSoundEffects() else it.unloadSoundEffects() }
            }
            PhoneSetting.HAPTICS -> Settings.System.putInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (on) 1 else 0)
            PhoneSetting.KEYPAD_TONES -> Settings.System.putInt(cr, Settings.System.DTMF_TONE_WHEN_DIALING, if (on) 1 else 0)
            PhoneSetting.DND -> context.getSystemService(android.app.NotificationManager::class.java)?.let { nm ->
                if (!nm.isNotificationPolicyAccessGranted) false else {
                    nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    true
                }
            } ?: false
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /**
     * The intent the assistant can run to switch airplane mode, power saving or Do Not Disturb (Android lets only the
     * default digital assistant do this, through its voice session).
     */
    fun assistantIntent(s: PhoneSetting, on: Boolean): Intent? = when (s) {
        PhoneSetting.AIRPLANE -> Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE).putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, on)
        PhoneSetting.POWER_SAVING -> Intent(Settings.ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE).putExtra(Settings.EXTRA_BATTERY_SAVER_MODE_ENABLED, on)
        PhoneSetting.DND -> Intent(Settings.ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE).putExtra(Settings.EXTRA_DO_NOT_DISTURB_MODE_ENABLED, on)
        else -> null
    }?.addCategory(Intent.CATEGORY_VOICE)

    /** The screens to try, best first: a quick panel where Android has one, then the Samsung page, then the general page. */
    fun screens(s: PhoneSetting): List<Intent> {
        fun settings(cls: String) = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.$cls"))
        fun pkg(p: String, cls: String) = Intent().setComponent(ComponentName(p, cls))
        val panel = Build.VERSION.SDK_INT >= 29
        return when (s) {
            PhoneSetting.WIFI -> listOfNotNull(if (panel) Intent(Settings.Panel.ACTION_WIFI) else null, Intent(Settings.ACTION_WIFI_SETTINGS))
            PhoneSetting.BLUETOOTH -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            PhoneSetting.AIRPLANE -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            PhoneSetting.MOBILE_DATA -> listOfNotNull(if (panel) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else null,
                Intent(Settings.ACTION_DATA_USAGE_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            PhoneSetting.HOTSPOT -> listOf(settings("Settings\$SecTetherSettingsActivity"), settings("TetherSettings"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            PhoneSetting.NFC -> listOfNotNull(if (panel) Intent(Settings.Panel.ACTION_NFC) else null, Intent(Settings.ACTION_NFC_SETTINGS))
            PhoneSetting.QUICK_SHARE -> listOf(
                pkg("com.samsung.android.app.sharelive", "com.samsung.android.app.sharelive.presentation.settings.SettingsActivity"),
                Intent("com.google.android.gms.settings.SHARING"), Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            )
            PhoneSetting.MEDIA_OUTPUT -> listOf(settings("Settings\$MediaRoutingActivity"), Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            PhoneSetting.LOCATION -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            PhoneSetting.AUTO_ROTATE -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            PhoneSetting.ADAPTIVE_BRIGHTNESS -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            PhoneSetting.DARK_MODE -> listOf(settings("Settings\$DarkModeSettingsActivity"), Intent(Settings.ACTION_DISPLAY_SETTINGS))
            PhoneSetting.AOD -> listOf(
                pkg("com.samsung.android.app.aodservice", "com.samsung.android.app.aodservice.settings.AODSettingsMainActivity"),
                Intent("android.settings.LOCK_SCREEN_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS),
            )
            PhoneSetting.POWER_SAVING -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            PhoneSetting.DND -> listOf(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS), Intent(Settings.ACTION_SOUND_SETTINGS))
            PhoneSetting.SOUND_MODE -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))
            PhoneSetting.VOLUME -> listOfNotNull(if (panel) Intent(Settings.Panel.ACTION_VOLUME) else null, Intent(Settings.ACTION_SOUND_SETTINGS))
            PhoneSetting.TOUCH_SOUNDS, PhoneSetting.KEYPAD_TONES -> listOf(settings("Settings\$SecSoundSystemSoundSettingsActivity"), Intent(Settings.ACTION_SOUND_SETTINGS))
            PhoneSetting.HAPTICS -> listOf(settings("Settings\$VibrationSettingsActivity"), Intent(Settings.ACTION_SOUND_SETTINGS))
        } + Intent(Settings.ACTION_SETTINGS)
    }

    /** Opens [s]'s best screen that this phone has. */
    fun open(context: Context, s: PhoneSetting) {
        if (s == PhoneSetting.MEDIA_OUTPUT && outputSwitcher(context)) return
        for (i in screens(s)) {
            try {
                context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { }
        }
    }

    /** Android 14+'s media output picker (the same one as the quick panel's). */
    private fun outputSwitcher(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 34) {
        try { android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher() } catch (_: Exception) { false }
    } else false

    /** Asks to turn Bluetooth on (Android shows its own Allow dialog). */
    fun requestBluetoothOn(context: Context): Boolean = try {
        context.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    fun setSoundMode(context: Context, mode: Int): Boolean = try {
        context.getSystemService(AudioManager::class.java)?.ringerMode = mode
        true
    } catch (_: Exception) {
        false
    }
}
