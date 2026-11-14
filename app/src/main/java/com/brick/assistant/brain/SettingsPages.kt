package com.brick.assistant.brain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Pages of the phone's Settings app that Brick can take you to: what you might call each one ([words], a regex
 * matched anywhere in the sentence), where it is in Samsung's Settings ([path]) and the screens to try, best
 * first ([targets]: "c:package/class" a screen, "a:action" an Android settings action, "p:package" an app).
 * The on/off switches (Wi-Fi, Bluetooth...) are [PhoneSetting]s; this is everything else.
 */
enum class SettingsPage(val title: String, val words: String, val path: String, vararg val targets: String) {
    // Display
    SCREEN_TIMEOUT("Screen timeout",
        "(?:screen|display|phone) ?time ?outs?|time ?out (?:settings?|setting|length|time)|screen (?:sleep|lock) (?:time|timer|delay)|auto ?-?lock|" +
            "how long (?:does |do |will |can )?(?:the |my )?(?:screen|display|phone) (?:stays?|is|stay) (?:on|awake)|(?:screen|display) (?:turns?|goes|shuts?) off",
        "Settings > Display > Screen timeout", "c:com.android.settings/.Settings\$ScreenTimeoutActivity", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    FONT("Font size and style",
        "fonts?(?: size| style| settings?)?|text size|(?:text|letters|words|writing) (?:bigger|smaller|larger)|(?:bigger|smaller|larger) (?:text|font|letters|words)|" +
            "(?:make|change) (?:the |my )?(?:text|font|letters|writing)\\b|bold (?:text|font)|high contrast (?:text|fonts?)",
        "Settings > Display > Font size and style", "a:com.samsung.settings.FontPreview", "a:android.settings.TEXT_READING_SETTINGS", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    SCREEN_ZOOM("Screen zoom",
        "screen zoom|display size|zoom (?:the |my )?(?:screen|display)|(?:make )?(?:everything|icons?|the (?:whole )?screen) (?:bigger|smaller|larger)|(?:bigger|smaller) (?:icons|everything)",
        "Settings > Display > Screen zoom", "c:com.android.settings/.Settings\$SecScreenZoomActivity", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    MOTION_SMOOTHNESS("Motion smoothness",
        "motion smoothness|refresh rate|1[02]0 ?hz|60 ?hz|high refresh|adaptive refresh|smooth(?:er)? (?:screen|scrolling|display)|frame ?rate of (?:the |my )?screen",
        "Settings > Display > Motion smoothness", "c:com.android.settings/.Settings\$HighRefreshRatesSettingsActivity", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    EYE_COMFORT("Eye comfort shield",
        "eye comfort(?: shield)?|blue ?light(?: filter)?|night ?light|night shift|eye (?:protection|strain|care)|warm(?:er)? (?:screen|colou?rs|tone)|yellow (?:screen|tint)",
        "Settings > Display > Eye comfort shield", "c:com.android.settings/.Settings\$EyeComfortSettingsActivity", "a:${Settings.ACTION_NIGHT_DISPLAY_SETTINGS}"),
    NAVIGATION_BAR("Navigation bar",
        "navigation (?:bar|buttons?|type|gestures?|mode)|nav ?bar|gesture navigation|swipe gestures|(?:back|home|recents?) buttons?|button order",
        "Settings > Display > Navigation bar", "c:com.android.settings/.Settings\$NavigationBarSettingsActivity", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    EDGE_PANELS("Edge panels",
        "edge (?:panels?|screen|bar|handle)|side panels?|apps edge",
        "Settings > Display > Edge panels",
        "c:com.sec.android.app.launcher/com.samsung.app.honeyspace.edge.edgepanel.app.setting.EdgeSettingMainActivity", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    SCREEN_SAVER("Screen saver",
        "screen ?saver|daydream",
        "Settings > Display > Screen saver", "a:${Settings.ACTION_DREAM_SETTINGS}", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),
    DISPLAY("Display",
        "display(?: settings?)?|screen settings|screen options|(?:screen )?resolution|colou?r (?:mode|profile|balance)|screen mode|vivid|camera cutout|full screen apps",
        "Settings > Display", "c:com.android.settings/.Settings\$DisplaySettingsActivity", "a:${Settings.ACTION_DISPLAY_SETTINGS}"),

    // Look
    WALLPAPER("Wallpaper and style",
        "wall ?papers?|background (?:picture|image|photo)|(?:lock ?screen|home ?screen) (?:picture|image|photo|background)|colou?r palette|wallpaper and style",
        "Settings > Wallpaper and style", "a:android.settings.WALLPAPER_SETTINGS", "a:${Intent.ACTION_SET_WALLPAPER}"),
    THEMES("Themes",
        "themes?(?! park)|icon packs?|galaxy themes",
        "Settings > Themes", "p:com.samsung.android.themestore"),
    HOME_SCREEN("Home screen",
        "home ?screen(?: settings?| layout| grid)?|app (?:icon )?grid|apps? screen grid|launcher settings|app drawer|badges?|notification badges",
        "Settings > Home screen", "c:com.sec.android.app.launcher/com.android.homescreen.settings.HomeScreenSettingsActivity", "a:android.settings.HOME_SETTINGS"),
    LOCK_SCREEN("Lock screen",
        "lock ?screen(?: settings?| type| clock| widgets?| shortcuts?)?|screen lock(?: type)?|(?:change|set|reset) (?:my |the )?(?:pin|password|pattern|passcode)|unlock (?:method|type)",
        "Settings > Lock screen and AOD", "c:com.android.settings/.Settings\$LockscreenSettingsActivity", "a:${Settings.ACTION_SECURITY_SETTINGS}"),

    // Security and privacy
    BIOMETRICS("Biometrics",
        "biometrics?|fingerprints?(?: scanner| reader| unlock)?|face (?:unlock|recognition|id)",
        "Settings > Security and privacy > Biometrics", "c:com.android.settings/.Settings\$BiometricsSettingsActivity", "a:${Settings.ACTION_SECURITY_SETTINGS}"),
    PERMISSIONS("Permission manager",
        "permission manager|app permissions|permissions(?! for)|which apps (?:can|have) (?:access|use)|who can (?:use|access) my (?:camera|mic|microphone|location)",
        "Settings > Security and privacy > Privacy > Permission manager", "a:android.intent.action.MANAGE_PERMISSIONS", "a:${Settings.ACTION_PRIVACY_SETTINGS}"),
    PRIVACY("Privacy",
        "privacy(?: settings| dashboard)?|permission (?:history|usage)|ad (?:id|settings)|advertising id",
        "Settings > Security and privacy > Privacy", "c:com.android.settings/.Settings\$PrivacyDashboardActivity", "a:${Settings.ACTION_PRIVACY_SETTINGS}"),
    SECURITY("Security and privacy",
        "security(?: and privacy| settings| status)?|find my (?:mobile|phone|device)|secure folder|auto blocker|app security|lost (?:phone|device)",
        "Settings > Security and privacy", "c:com.android.settings/.Settings\$SecurityAndPrivacySettingsActivity", "a:${Settings.ACTION_SECURITY_SETTINGS}"),
    SAFETY("Safety and emergency",
        "safety(?: and emergency)?|emergency (?:sos|contacts?|alerts?|settings|mode|sharing)|medical info|wireless emergency alerts|amber alerts?|earthquake alerts?|sos",
        "Settings > Safety and emergency", "c:com.android.settings/.Settings\$EmergencyDashboardActivity", "a:${Settings.ACTION_SETTINGS}"),

    // Sound and notifications
    VIBRATION("Vibration",
        "vibrations?(?: settings| pattern| intensity| strength| feedback)?|haptics? (?:settings|strength|intensity)",
        "Settings > Sounds and vibration > Vibration intensity", "c:com.android.settings/.Settings\$VibrationSettingsActivity", "a:${Settings.ACTION_SOUND_SETTINGS}"),
    SYSTEM_SOUNDS("System sound",
        "system sounds?|touch sounds? settings|keyboard sounds?|charging sound|lock sounds?|dial(?:ing)? (?:pad|keypad) (?:tones|sounds)",
        "Settings > Sounds and vibration > System sound", "c:com.android.settings/.Settings\$SecSoundSystemSoundSettingsActivity", "a:${Settings.ACTION_SOUND_SETTINGS}"),
    SOUND_QUALITY("Sound quality and effects",
        "sound quality|dolby(?: atmos)?|adapt sound|sound effects settings|ups(?:cal(?:e|er)|caling)",
        "Settings > Sounds and vibration > Sound quality and effects", "a:${Settings.ACTION_SOUND_SETTINGS}"),
    SOUNDS("Sounds and vibration",
        "sounds?(?: and vibration| settings)?|ring ?tones?|notification (?:sounds?|tones?)|alarm (?:sound|tone)s?|text (?:tones?|sounds?)|message (?:tones?|sounds?)",
        "Settings > Sounds and vibration", "a:${Settings.ACTION_SOUND_SETTINGS}"),
    NOTIFICATIONS("Notifications",
        "notifications?(?: settings| history| popups?| style| bar)?|app notifications|status bar|notification (?:access|permissions)",
        "Settings > Notifications", "c:com.android.settings/.Settings\$ConfigureNotificationSettingsActivity", "a:android.settings.NOTIFICATION_SETTINGS"),
    APP_NOTIFICATIONS("App notifications",
        "app notifications|notifications (?:for|from) (?:my |all )?apps|which apps (?:can )?(?:send|show) notifications",
        "Settings > Notifications > App notifications", "c:com.android.settings/.Settings\$NotificationAppListActivity", "a:android.settings.ALL_APPS_NOTIFICATION_SETTINGS"),

    // Apps and features
    DEFAULT_APPS("Default apps",
        "default (?:apps?|browser|phone app|dialer|sms app|messag\\w+ app|launcher|home app|camera app)|change (?:my |the )?default",
        "Settings > Apps > Choose default apps", "a:${Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS}", "a:${Settings.ACTION_APPLICATION_SETTINGS}"),
    ASSISTANT("Digital assistant app",
        "(?:default )?(?:digital |voice )?assistant(?: app| settings)?|default assistant|side (?:key|button) assistant",
        "Settings > Apps > Choose default apps > Digital assistant app", "c:com.android.settings/.Settings\$DefaultDigitalAssistantAppActivity", "a:${Settings.ACTION_VOICE_INPUT_SETTINGS}"),
    APPS("Apps",
        "apps (?:list|settings|manager)|app (?:list|manager)|all (?:my |the )?apps|installed apps|my apps|application(?:s| manager)|uninstall (?:an? )?apps?|manage (?:my )?apps",
        "Settings > Apps", "a:${Settings.ACTION_APPLICATION_SETTINGS}"),
    SIDE_BUTTON("Side button",
        "side (?:key|button)|power (?:key|button)(?: settings)?|double (?:press|click) (?:the )?(?:side|power)|press and hold (?:the )?(?:side|power)",
        "Settings > Advanced features > Side button", "c:com.android.settings/.Settings\$SideAndVolumeUpPreferenceActivity", "c:com.android.settings/.Settings\$UsefulFeatureMainActivity"),
    MOTIONS("Motions and gestures",
        "motions?(?: and gestures)?|gestures?(?: settings)?|lift to wake|double tap to (?:wake|turn (?:on|off)(?: the screen)?)|palm swipe|keep screen on while viewing|smart alert|easy mute",
        "Settings > Advanced features > Motions and gestures", "c:com.android.settings/.Settings\$MotionAndGestureSettingsActivity", "c:com.android.settings/.Settings\$UsefulFeatureMainActivity"),
    ONE_HANDED("One-handed mode",
        "(?:one|1) ?-?hand(?:ed)?(?: mode)?|reachability",
        "Settings > Advanced features > One-handed mode", "c:com.android.settings/.Settings\$OneHandedSettingsActivity", "c:com.android.settings/.Settings\$UsefulFeatureMainActivity"),
    ADVANCED("Advanced features",
        "advanced features|labs|screenshots? (?:settings|toolbar|format)|screen recorder settings|video call effects|game booster|multi ?window|split screen|pop ?up view|dual messenger",
        "Settings > Advanced features", "c:com.android.settings/.Settings\$UsefulFeatureMainActivity", "a:${Settings.ACTION_SETTINGS}"),
    GALAXY_AI("Galaxy AI",
        "galaxy ai|ai features|intelligen(?:ce|t) (?:features|settings)|live translate|interpreter|note assist|circle to search|writing assist|photo assist",
        "Settings > Galaxy AI", "a:${Settings.ACTION_SETTINGS}"),
    MODES("Modes and routines",
        "modes(?: and routines)?|routines?|bixby routines|sleep mode|driving mode|work mode|exercise mode|relax mode|theater mode",
        "Settings > Modes and routines", "a:android.settings.ZEN_MODE_SETTINGS", "p:com.samsung.android.app.routines"),
    WELLBEING("Digital Wellbeing",
        "digital wellbeing|well ?being|screen time|parental controls?|app timers?|bed ?time mode|wind down|focus time|how much (?:time )?(?:i've|have i|i) (?:spent|used) (?:on )?(?:my )?phone",
        "Settings > Digital Wellbeing and parental controls", "p:com.samsung.android.forest", "c:com.samsung.android.forest/.ui.home.DefaultActivity", "a:${Settings.ACTION_SETTINGS}"),

    // Battery and care
    BATTERY("Battery",
        "battery(?: settings| usage| health| info| life settings| stats| protection| options)?|power usage|charging(?: settings| options| speed)?|fast charging|super fast charging|protect (?:the |my )?battery|(?:what's|what is) (?:using|draining) (?:my |the )?battery",
        "Settings > Battery", "c:com.android.settings/.Settings\$BatteryInfoActivity", "a:${Intent.ACTION_POWER_USAGE_SUMMARY}"),
    STORAGE("Storage",
        "storage|free (?:up )?space|(?:phone |internal )?memory|disk space|(?:how much )?space (?:is )?left|full storage|clear (?:out )?(?:space|junk)",
        "Settings > Device care > Storage", "a:com.samsung.android.sm.ACTION_STORAGE", "c:com.android.settings/.Settings\$StorageDashboardActivity"),
    DEVICE_CARE("Device care",
        "device care|optimi[sz]e (?:my |the )?phone|clean (?:up )?my phone|speed up (?:my |the )?phone|\\bram\\b|memory cleaner|phone (?:is )?(?:slow|lagging)",
        "Settings > Device care", "c:com.samsung.android.lool/com.samsung.android.sm.score.ui.ScoreBoardActivity", "a:${Settings.ACTION_SETTINGS}"),

    // Accounts
    ACCOUNTS("Accounts and backup",
        "accounts?(?: and backup)?|back ?ups?|backing up|restore (?:my )?(?:data|phone|backup)|samsung cloud|smart switch|sync(?: settings| my account)?|google account|samsung account|transfer (?:data|files) from (?:my )?old phone",
        "Settings > Accounts and backup", "c:com.android.settings/.Settings\$AccountsAndBackupDashboardActivity", "a:${Settings.ACTION_SYNC_SETTINGS}"),
    GOOGLE("Google",
        "google (?:settings|services)|google play services|nearby devices|find hub",
        "Settings > Google", "c:com.google.android.gms/.app.settings.GoogleSettingsLink", "a:${Settings.ACTION_SETTINGS}"),

    // General
    LANGUAGE("Language",
        "languages?|region|locale|(?:change|set) (?:the |my )?(?:phone(?:'s)? )?language|phone language",
        "Settings > General management > Language", "c:com.android.settings/.Settings\$LocalePickerActivity", "a:${Settings.ACTION_LOCALE_SETTINGS}"),
    KEYBOARD("Keyboard",
        "keyboards?(?: settings)?|samsung keyboard|gboard|auto ?-?correct(?:ion)?|predictive text|input methods?|spell ?-?check(?:er)?|text replacement|keyboard (?:size|theme)",
        "Settings > General management > Keyboard list and default", "c:com.android.settings/.Settings\$KeyboardSettingsActivity", "a:${Settings.ACTION_INPUT_METHOD_SETTINGS}"),
    DATE_TIME("Date and time",
        "date (?:and|&) time|time ?zones?|24 ?hour (?:time|clock|format)|12 ?hour (?:time|clock|format)|(?:set|change|fix) (?:the |my )?(?:phone(?:'s)? )?(?:date|clock)|automatic (?:date|time)|wrong time|clock is wrong",
        "Settings > General management > Date and time", "c:com.android.settings/.Settings\$DateTimeSettingsActivity", "a:${Settings.ACTION_DATE_SETTINGS}"),
    TEXT_TO_SPEECH("Text-to-speech",
        "text ?-?to ?-?speech|tts(?: engine)?|speech rate|reading voice",
        "Settings > General management > Text-to-speech", "c:com.android.settings/.Settings\$TextToSpeechSettingsActivity", "a:com.android.settings.TTS_SETTINGS"),
    RESET("Reset",
        "reset(?: options| settings| network settings| all settings)?|factory reset|wipe (?:my |the )?phone|erase (?:everything|all data)|reset (?:my |the )?(?:phone|network|wi-?fi)",
        "Settings > General management > Reset", "c:com.android.settings/.Settings\$ResetDashboardFragmentActivity", "c:com.android.settings/.Settings\$GeneralDeviceSettingsActivity"),
    GENERAL("General management",
        "general management|general settings",
        "Settings > General management", "c:com.android.settings/.Settings\$GeneralDeviceSettingsActivity", "a:${Settings.ACTION_SETTINGS}"),
    ACCESSIBILITY("Accessibility",
        "accessibility|talk ?back|screen ?reader|magnif\\w*|hearing (?:aids?|enhancements)|colou?r (?:correction|inversion|filters?|adjustment|blind\\w*)|invert(?:ed)? colou?rs?|high contrast|" +
            "live (?:caption|transcribe)|mono audio|sound balance|interaction and dexterity|assistant menu",
        "Settings > Accessibility", "c:com.android.settings/.Settings\$AccessibilitySettingsActivity", "a:${Settings.ACTION_ACCESSIBILITY_SETTINGS}"),
    SOFTWARE_UPDATE("Software update",
        "software (?:updates?|version)|system updates?|(?:is )?my phone up to date|any (?:new )?updates|updates? (?:my |the )?(?:phone|software|android|os|system)|check (?:for )?(?:an? )?(?:updates?|software update)|android (?:update|upgrade)|one ui (?:update|upgrade)|security (?:update|patch)|firmware",
        "Settings > Software update", "a:com.samsung.settings.SOFTWARE_UPDATE_SETTINGS"),
    ABOUT("About phone",
        "about (?:my |this |the )?(?:phone|device)|phone info(?:rmation)?|device info(?:rmation)?|model (?:number|name)|imei|serial number|android version|one ui version|build number|status information|my phone(?:'s)? (?:model|name)|(?:rename|change) (?:my |the )?(?:phone|device)(?:'s)? name|device name",
        "Settings > About phone", "c:com.android.settings/.Settings\$MyDeviceInfoActivity", "a:${Settings.ACTION_DEVICE_INFO_SETTINGS}"),
    DEVELOPER("Developer options",
        "developer (?:options|mode|settings)|dev options|usb debugging|wireless debugging|animation scale",
        "Settings > Developer options", "a:${Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS}", "a:${Settings.ACTION_DEVICE_INFO_SETTINGS}"),

    // Connections
    CALLS("Call settings",
        "call settings|caller id|voicemail(?: settings)?|call (?:blocking|forwarding|waiting|background)|block(?:ed)? (?:numbers|callers|calls)|spam (?:calls|protection)|phone app settings",
        "Phone app > More (three dots) > Settings", "c:com.samsung.android.dialer/.DialtactsActivity"),
    WIFI_CALLING("Wi-Fi calling",
        "wi ?-?fi calling|wlan calling|calling over wi ?-?fi",
        "Settings > Connections > Wi-Fi Calling", "c:com.android.settings/.Settings\$WifiCallingSettingsActivity", "a:${Settings.ACTION_WIRELESS_SETTINGS}"),
    DATA_USAGE("Data usage",
        "data (?:usage|saver|limit|warning|plan)|how much data|(?:mobile |cell(?:ular)? )?data (?:i've|i have) used|apps? using (?:my )?data",
        "Settings > Connections > Data usage", "c:com.android.settings/.Settings\$DataUsageSummaryActivity", "a:${Settings.ACTION_DATA_USAGE_SETTINGS}"),
    MOBILE_NETWORKS("Mobile networks",
        "mobile networks?|cell(?:ular)? networks?|network (?:mode|operators?|type)|(?:data )?roaming|\\bapns?\\b|access point names?|carrier settings|sim (?:cards?|manager|settings)|e ?sims?|dual sim|5g settings",
        "Settings > Connections > Mobile networks", "a:${Settings.ACTION_NETWORK_OPERATOR_SETTINGS}", "c:com.android.settings/.Settings\$MobileNetworkActivity", "a:${Settings.ACTION_WIRELESS_SETTINGS}"),
    VPN("VPN",
        "\\bvpns?\\b|virtual private network",
        "Settings > Connections > More connection settings > VPN", "a:${Settings.ACTION_VPN_SETTINGS}", "a:${Settings.ACTION_WIRELESS_SETTINGS}"),
    SMART_VIEW("Smart View",
        "smart view|screen mirroring|mirror (?:my |the )?(?:screen|phone)|cast (?:my |the )?(?:screen|phone)|(?:screen )?cast(?:ing)? settings",
        "Quick panel > Smart View", "a:${Settings.ACTION_CAST_SETTINGS}", "a:${Settings.ACTION_WIRELESS_SETTINGS}"),
    CONNECTED_DEVICES("Connected devices",
        "connected devices|android auto|samsung dex|\\bdex\\b|link to windows|phone link|continue apps on other devices|call (?:and|&) text on other devices|auracast|smartthings",
        "Settings > Connected devices", "c:com.android.settings/.Settings\$AdvancedConnectedDeviceActivity", "a:${Settings.ACTION_BLUETOOTH_SETTINGS}"),
    CONNECTIONS("Connections",
        "connections(?: settings)?|network settings|internet settings|network (?:and|&) internet|more connection settings",
        "Settings > Connections", "c:com.android.settings/.Settings\$ConnectionsSettingsActivity", "a:${Settings.ACTION_WIRELESS_SETTINGS}"),

    // The app itself
    MAIN("Settings",
        "(?:phone |android |system |device )?settings(?: app| menu| page)?",
        "Settings", "a:${Settings.ACTION_SETTINGS}"),
    ;

    val regex: Regex by lazy { Regex("\\b(?:$words)(?![a-z])", RegexOption.IGNORE_CASE) }

    /** "Settings, Display, Screen timeout". */
    val spokenPath: String get() = path.replace(" > ", ", ")

    /** How the page sits in a sentence: "display settings", "Digital Wellbeing", "the Battery page". */
    val spoken: String get() = when (this) {
        MAIN -> "Settings"
        else -> if (title.drop(1).any { it.isUpperCase() }) title else title.lowercase()
    }

    companion object {
        /** The page [said] names, with how many letters matched (the longest match wins: "wifi calling" beats "wifi"). */
        fun find(said: String): Pair<SettingsPage, Int>? {
            var best: Pair<SettingsPage, Int>? = null
            for (p in entries) {
                val m = p.regex.findAll(said).maxOfOrNull { it.value.length } ?: continue
                // MAIN ("settings") only when nothing more specific matched.
                if (p == MAIN && best != null) continue
                if (best == null || m > best.second) best = p to m
            }
            return best
        }
    }
}

/** Opens [SettingsPage]s and apps' own settings pages. */
object SettingsPages {
    /** The intents for [page], best first, ending with the main Settings page. */
    fun intents(context: Context, page: SettingsPage): List<Intent> = (page.targets.mapNotNull { t ->
        val v = t.substring(2)
        when (t.take(2)) {
            "c:" -> {
                val (pkg, cls) = v.split('/', limit = 2)
                Intent().setComponent(ComponentName(pkg, if (cls.startsWith(".")) pkg + cls else cls))
            }
            "a:" -> Intent(v)
            "p:" -> context.packageManager.getLaunchIntentForPackage(v)
            else -> null
        }
    } + Intent(Settings.ACTION_SETTINGS))

    /** Opens [page] (the first of its screens this phone has). True when one opened. */
    fun open(context: Context, page: SettingsPage): Boolean {
        for (i in intents(context, page)) {
            try {
                context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    /** The intent for one app's settings: its App info page, or its notifications, permissions, storage or battery. */
    fun appIntent(pkg: String, page: AppPage): Intent = when (page) {
        AppPage.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
    }

    fun openApp(context: Context, pkg: String, page: AppPage): Boolean {
        for (i in listOf(appIntent(pkg, page), appIntent(pkg, AppPage.INFO))) {
            try {
                context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { }
        }
        return false
    }
}

/** Which page of an app's settings: App info (where permissions, storage, battery and uninstall are) or its notifications. */
enum class AppPage(val label: String) { INFO("app info"), NOTIFICATIONS("notifications"), PERMISSIONS("permissions"), STORAGE("storage"), BATTERY("battery") }

/** The screen timeout: how long the screen stays on with nothing touched. */
object ScreenTimeout {
    /** Samsung's choices, shortest first (in milliseconds). */
    val STEPS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
    const val MIN = 10_000L
    const val MAX = 30 * 60_000L

    fun get(context: Context): Long? = try {
        Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (_: Exception) {
        null
    }

    /** Sets it (Brick needs "modify system settings"). False when Android refused. */
    fun set(context: Context, ms: Long): Boolean = try {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.coerceIn(MIN, MAX).toInt())
    } catch (_: Exception) {
        false
    }

    /** One step longer (+1) or shorter (-1) than [now] in Samsung's list. */
    fun step(now: Long, dir: Int): Long = if (dir > 0) STEPS.firstOrNull { it > now } ?: STEPS.last()
    else STEPS.lastOrNull { it < now } ?: STEPS.first()

    /** "30 seconds", "1 minute", "2 minutes", "1 minute 30 seconds". */
    fun say(ms: Long): String {
        val total = (ms / 1000).toInt()
        val m = total / 60
        val s = total % 60
        fun unit(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
        return when {
            m == 0 -> unit(s, "second")
            s == 0 -> unit(m, "minute")
            else -> unit(m, "minute") + " " + unit(s, "second")
        }
    }

    /** "30 sec", "2 min" for buttons. */
    fun short(ms: Long): String = (ms / 1000).let { if (it < 60) "$it sec" else if (it % 60 == 0L) "${it / 60} min" else "${it / 60}m ${it % 60}s" }

    private val DURATION = Regex(
        "\\b(\\d+(?:\\.\\d+)?|an?|half an?|one and a half) ?(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "2 minutes", "30 seconds", "a minute", "half an hour", "90 secs" in [s], as milliseconds; null when none. */
    fun duration(s: String): Long? {
        val m = DURATION.find(s) ?: return null
        val n = when (val v = m.groupValues[1].lowercase()) {
            "a", "an" -> 1.0
            "half a", "half an" -> 0.5
            "one and a half" -> 1.5
            else -> v.toDoubleOrNull() ?: return null
        }
        val unit = m.groupValues[2].lowercase()
        val per = when {
            unit.startsWith("h") -> 3_600_000.0
            unit.startsWith("m") -> 60_000.0
            else -> 1_000.0
        }
        return (n * per).toLong()
    }
}
