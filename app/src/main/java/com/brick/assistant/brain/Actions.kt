package com.brick.assistant.brain

import android.Manifest
import android.app.SearchManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.view.KeyEvent
import com.brick.assistant.data.SettingsStore
import com.brick.assistant.reminders.ReminderScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * @param robotLine the standard line the robot voice speaks after David.
 * @param after work that leaves the app (opening the dialer, a browser, ...). Runs once speech is done.
 */
/** Which voice speaks an action's result line. */
enum class LineVoice { UNIT, BRICK, NONE }

class ActionResult(
    val robotLine: String,
    val confirm: Confirmation? = null,
    /** The question is asked while another app is shown ([after] opens it): bring the card back to listen. */
    val returnToCard: Boolean = false,
    /** In Android Auto: a place to start car navigation to ("geo:" URI), used instead of [after]. */
    val navigateTo: String? = null,
    /** Brick takes dictation next: everything you say until 5 seconds of silence is passed here. */
    val dictate: (suspend (String) -> ActionResult)? = null,
    /** Who says [robotLine]: the unit (default), Brick's main voice, or nobody (shown only on the card). */
    val voice: LineVoice = LineVoice.UNIT,
    /** A terminal readout shown under the answer in the log (monospace, never spoken), e.g. the night sky panel. */
    val display: String? = null,
    /** The full infographic (box, picture, live controls); [display] is the plain-box shorthand. */
    val readout: Readout? = null,
    val after: (() -> Unit)? = null,
) {
    fun shown(): Readout? = readout ?: display?.let { Readout(it) }
}

/**
 * An action that waits for the user's yes or no (asked in [ActionResult.robotLine]).
 * [onYes] performs it and returns what the robot says next; [noLine] is said on "no".
 */
/** [onNext], when set, answers "next" / "another" separately from "yes". */
class Confirmation(
    val noLine: String,
    val onNext: (suspend () -> ActionResult)? = null,
    /** An answer other than yes/no ("the Sony ones", "all"): its result, or null to treat it as a new request. */
    val onAnswer: (suspend (String) -> ActionResult?)? = null,
    /** On yes, run this request as if you had said it ("Did you mean ...?"). */
    val retry: String? = null,
    val onYes: suspend () -> ActionResult,
)

class Actions(private val context: Context, private val settings: SettingsStore) {
    private object Wording {
        /** Requests worded as something to do, which a lookup can't answer. */
        val COMMAND = Regex(
            "^(?:order|make|buy|book|reserve|send|turn|set|change|switch|delete|remove|add|start|stop|cancel|pay|transfer|" +
                "schedule|move|install|uninstall|download|upload|record|take|share|post|email|message|text|clean|cook|fix|" +
                "write|draw|create|lock|unlock|close|enable|disable|connect|disconnect|charge|update|reset|restart|reboot|go|bring|get me)\b",
            RegexOption.IGNORE_CASE,
        )
    }

    /** The infographic for each kind of answer. */
    private val info = Infographics(context)

    /** Battery of just the Bluetooth device you asked about; asks which one when several match. */
    private fun btBattery(target: String?): ActionResult {
        val (devices, problem) = DeviceControls.bluetoothDevices(context)
        if (problem != null) return ActionResult(problem)
        if (devices.isEmpty()) return ActionResult("No Bluetooth devices are connected.")
        val found = BtBattery.matching(target, devices)
        if (found.isEmpty()) return ActionResult(BtBattery.noneLine(target ?: "device"))
        if (found.size == 1 || target == null) return ActionResult(BtBattery.report(found), readout = info.btBatteries(found))
        return ActionResult(
            BtBattery.askLine(target, found),
            readout = info.btBatteries(found),
            confirm = Confirmation(
                noLine = "Okay.",
                onYes = { ActionResult(BtBattery.report(found), readout = info.btBatteries(found)) },
                onAnswer = { a -> BtBattery.pick(a, found)?.let { ActionResult(BtBattery.report(it), readout = info.btBatteries(it)) } },
            ),
        )
    }


    // ---------------------------------------------------------------- discord

    /** Reads Discord messages from notifications, or replies with the notification's Reply button. */
    private fun discord(req: Request.Discord): ActionResult {
        if (!Discord.hasAccess()) {
            return ActionResult("I need notification access to see Discord messages. Turn it on for Brick in Settings, under Notification access.")
        }
        val locked = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked && !inCar) return ActionResult("Unlock your phone first, then ask me again.")
        val chats = Discord.chats()
        if (!req.reply) return ActionResult(Discord.speak(chats, req.who))
        val chat = Discord.find(chats, req.who) ?: return ActionResult(
            if (req.who != null) "I don't see a Discord message from ${req.who} to reply to. I can only reply to messages showing in your notifications."
            else "You don't have any Discord messages to reply to.",
        )
        if (chat.reply == null) return ActionResult("Discord doesn't offer a reply for that message. Open Discord to answer it.")
        val name = if (chat.isDm) chat.title else "${chat.lastSender} in ${chat.title}"
        val body = req.body?.trim().orEmpty()
        if (body.isEmpty()) {
            val last = chat.messages.lastOrNull()?.second?.let { " They said: ${it.take(140)}." } ?: ""
            return ActionResult(
                "What should I say to $name?$last",
                dictate = { said ->
                    if (said.isBlank()) ActionResult("I didn't hear anything, so I didn't send a reply.")
                    else confirmDiscord(chat, name, said.trim().replaceFirstChar { it.uppercase() })
                },
            )
        }
        return confirmDiscord(chat, name, body)
    }

    /** Nothing goes to Discord until you say yes. */
    private fun confirmDiscord(chat: Discord.Chat, name: String, msg: String): ActionResult = ActionResult(
        "Reply to $name on Discord: $msg? Say yes to send it, or no to cancel.",
        confirm = Confirmation(noLine = "Okay, I won't send it.") {
            if (Discord.reply(context, chat, msg)) ActionResult("Sent to $name on Discord.")
            else ActionResult("Discord didn't take the reply. That message may have been cleared; open Discord to answer it.")
        },
    )

    private fun isOn(setting: BrickSetting): Boolean {
        val s = settings.current
        return when (setting) {
            BrickSetting.WAKE_WORD -> s.wakeWordEnabled
            BrickSetting.POCKET_PAUSE -> s.pocketPause
            BrickSetting.HEADSET_MIC -> s.headsetMic
            BrickSetting.OVERLAY -> s.useOverlay
            BrickSetting.BRAND_GLOW -> s.brandGlow
            BrickSetting.ROBOT_VOICE -> s.unitStyle == "robot"
            BrickSetting.FAHRENHEIT -> s.useFahrenheit
        }
    }

    /** The answer, plus the setting's readout: a switch to flip it back and a button to Brick's Settings. */
    private fun setting(req: Request.Setting): ActionResult {
        val line = changeSetting(req).robotLine
        val f = req.setting == BrickSetting.FAHRENHEIT
        return ActionResult(
            line,
            readout = info.brickSetting(
                if (f) "Temperature unit" else req.setting.label,
                get = { isOn(req.setting) },
                set = { on -> changeSetting(Request.Setting(req.setting, on)) },
                onWord = if (f) "Fahrenheit" else "on",
                offWord = if (f) "Celsius" else "off",
            ),
        )
    }

    /** Changes one of Brick's settings by voice, exactly as the Settings screen would. */
    private fun changeSetting(req: Request.Setting): ActionResult {
        val label = req.setting.label
        val was = isOn(req.setting)
        if (req.setting == BrickSetting.FAHRENHEIT) {
            val unit = if (req.on) "Fahrenheit" else "Celsius"
            if (was == req.on) return ActionResult("I'm already using $unit.")
            settings.update { it.copy(useFahrenheit = req.on) }
            return ActionResult("Okay, I'll use $unit.")
        }
        val state = if (req.on) "on" else "off"
        if (was == req.on) return ActionResult("$label is already $state.")
        if (req.setting == BrickSetting.WAKE_WORD && req.on &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return ActionResult("Hey Brick needs the microphone permission. Open Brick once to allow it.")
        settings.update {
            when (req.setting) {
                BrickSetting.WAKE_WORD -> it.copy(wakeWordEnabled = req.on)
                BrickSetting.POCKET_PAUSE -> it.copy(pocketPause = req.on)
                BrickSetting.HEADSET_MIC -> it.copy(headsetMic = req.on)
                BrickSetting.OVERLAY -> it.copy(useOverlay = req.on)
                BrickSetting.BRAND_GLOW -> it.copy(brandGlow = req.on)
                BrickSetting.ROBOT_VOICE -> it.copy(unitStyle = if (req.on) "robot" else "natural")
                BrickSetting.FAHRENHEIT -> it
            }
        }
        if (req.setting == BrickSetting.WAKE_WORD) {
            try {
                if (req.on) com.brick.assistant.wake.WakeWordService.start(context) else com.brick.assistant.wake.WakeWordService.stop(context)
            } catch (_: Exception) {
                return ActionResult("Hey Brick is on. It starts listening the next time you open Brick.")
            }
        }
        val extra = when {
            req.setting == BrickSetting.WAKE_WORD && req.on -> " Say Hey Brick anytime."
            req.setting == BrickSetting.ROBOT_VOICE -> if (req.on) " This is how I sound now." else " Back to my natural voice."
            else -> ""
        }
        return ActionResult("$label is $state.$extra")
    }

    /** Optic, the edge visualizer: on / off, design, colors, sensitivity, or what it's set to. */
    private fun visualizer(req: Request.Visualizer): ActionResult {
        val optic = com.brick.assistant.optic.OpticBridge
        if (!optic.installed(context)) return ActionResult("Optic isn't installed, so there's no visualizer to change.")
        val asking = req.on == null && req.design == null && req.colors == null && req.sensitive == 0 && req.onlyMedia == null
        val before = optic.status(context)
            ?: return ActionResult("Optic didn't answer. It may need an update to work with me; open Optic once and try again.")
        if (asking) return ActionResult(describeOptic(before))
        // Optic always shows only while an app you whitelisted is playing; there's no "all the time" anymore.
        if (req.onlyMedia != null && req.on == null && req.design == null && req.colors == null && req.sensitive == 0) {
            return ActionResult(
                (if (req.onlyMedia) "It already does: the visualizer only shows while one of the apps you picked in Optic is playing."
                else "Optic only shows while one of the apps you picked in it is playing. Add more apps in Optic to see it more.") + opticWarnings(before)
            )
        }

        val wasOn = before.getBoolean("enabled") && before.getBoolean("running")
        val sens = before.getFloat("sensitivity")
        if (req.sensitive > 0 && sens >= before.getFloat("sensitivityMax") - 0.01f) return ActionResult("The visualizer is already as sensitive as it goes.")
        if (req.sensitive < 0 && sens <= before.getFloat("sensitivityMin") + 0.01f) return ActionResult("The visualizer is already at its lowest sensitivity.")
        if (req.on == true && wasOn) return ActionResult("The visualizer is already on.")
        if (req.on == false && !before.getBoolean("enabled")) return ActionResult("The visualizer is already off.")
        if (req.design != null && before.getString("design") == req.design.name && wasOn) return ActionResult("The visualizer is already on ${req.design.spoken}.")
        if (req.colors != null && before.getString("colorMode") == req.colors.name) return ActionResult("The visualizer already uses ${req.colors.spoken}.")

        val extras = android.os.Bundle().apply {
            req.on?.let { putBoolean("enabled", it) }
            // Picking a design or colors also turns it on: you want to see it.
            if (req.design != null) { putString("design", req.design.name); putBoolean("enabled", true) }
            if (req.colors != null) putString("colorMode", req.colors.name)
            if (req.sensitive != 0) putFloat("sensitivityStep", 0.3f * req.sensitive)
        }
        val after = optic.set(context, extras) ?: return ActionResult("Optic didn't take the change.")
        // Android wouldn't let Optic start itself in the background: opening it starts the visualizer.
        val startFailed = after.getBoolean("startFailed")
        val open = if (startFailed) context.packageManager.getLaunchIntentForPackage(com.brick.assistant.optic.OpticBridge.PACKAGE) else null
        val line = when {
            req.on == false -> "The visualizer is off."
            req.design != null -> "The visualizer is on ${req.design.spoken}."
            req.colors != null -> "The visualizer uses ${req.colors.spoken} now."
            req.sensitive != 0 -> "Visualizer sensitivity is %.1f times.".format(after.getFloat("sensitivity"))
            else -> "The visualizer is on, using ${after.getString("designLabel")}."
        }
        return ActionResult(
            line + if (open != null) " Opening Optic to start it." else opticWarnings(after),
            after = open?.let { i -> fun() { launch(i) } },
        )
    }

    /** "The visualizer is on, Edge Glow with Brick's colors, sensitivity 1.0x." */
    private fun describeOptic(b: android.os.Bundle): String {
        if (!b.getBoolean("enabled")) return "The visualizer is off. It's set to ${b.getString("designLabel")}."
        val colors = VizColors.entries.firstOrNull { it.name == b.getString("colorMode") }?.spoken ?: "its own colors"
        val state = if (b.getBoolean("running")) "on" else "turned on but not running; open Optic to start it,"
        return "The visualizer is $state using ${b.getString("designLabel")} with $colors, at %.1f times sensitivity.".format(b.getFloat("sensitivity")) +
            " It shows while one of the apps you picked is playing." + opticWarnings(b)
    }

    /** Why the visualizer may still not show up: Optic shows only while an app you picked is playing, and needs notification access to see that. */
    private fun opticWarnings(b: android.os.Bundle): String = when {
        !b.getBoolean("enabled") -> ""
        // older Optic builds don't send this key; they needed the accessibility layer instead
        b.containsKey("notificationAccess") && !b.getBoolean("notificationAccess") -> " Optic needs notification access to see what's playing."
        !b.containsKey("notificationAccess") && !b.getBoolean("accessibility") -> " Optic's accessibility layer is off, so it can't show over apps yet."
        b.getInt("apps") == 0 -> " Pick the apps it reacts to in Optic."
        else -> ""
    }

    /**
     * The app a request uses, for the bottom animation: Spotify, YouTube or Google Maps, or null. Set while
     * [prepare] runs (some, like "play X", only know once they pick the app).
     */
    @Volatile var brand: com.brick.assistant.overlay.Brand? = null
        private set

    /** The app a request will use, known before it runs (null when only the action knows). */
    fun brandFor(req: Request): com.brick.assistant.overlay.Brand? {
        val maps = defaultMapsPackage().let { it == null || it == "android" || it == "com.google.android.apps.maps" }
        return when (req) {
            is Request.YouTube -> com.brick.assistant.overlay.Brand.YOUTUBE
            is Request.Navigate, is Request.FindPlace -> if (maps) com.brick.assistant.overlay.Brand.GOOGLE_MAPS else null
            is Request.PlaceHours, is Request.PhotoSpots -> com.brick.assistant.overlay.Brand.GOOGLE_MAPS
            is Request.Music -> when {
                req.app == MusicApp.SPOTIFY -> com.brick.assistant.overlay.Brand.SPOTIFY
                req.command != MusicCommand.PLAY_ANY && req.command != MusicCommand.PLAY_QUERY -> playingBrand()
                else -> null
            }
            Request.LikeSong, is Request.AddToPlaylist -> playingBrand()
            is Request.OpenApp -> when {
                Regex("spotify", RegexOption.IGNORE_CASE).containsMatchIn(req.name) -> com.brick.assistant.overlay.Brand.SPOTIFY
                Regex("youtube", RegexOption.IGNORE_CASE).containsMatchIn(req.name) -> com.brick.assistant.overlay.Brand.YOUTUBE
                Regex("maps", RegexOption.IGNORE_CASE).containsMatchIn(req.name) -> com.brick.assistant.overlay.Brand.GOOGLE_MAPS
                Regex("^driver\\b", RegexOption.IGNORE_CASE).containsMatchIn(req.name.trim()) -> com.brick.assistant.overlay.Brand.DRIVER
                else -> null
            }
            else -> null
        }
    }

    /** Spotify or YouTube when that's what's playing now. */
    private fun playingBrand(): com.brick.assistant.overlay.Brand? = when (com.brick.assistant.media.LikeSong.current(context)?.pkg) {
        SPOTIFY_PACKAGE -> com.brick.assistant.overlay.Brand.SPOTIFY
        "com.google.android.youtube", "com.google.android.apps.youtube.music" -> com.brick.assistant.overlay.Brand.YOUTUBE
        DriverBridge.PACKAGE -> com.brick.assistant.overlay.Brand.DRIVER
        else -> null
    }

    /** True while Brick runs on the Android Auto screen: routes go to the car's navigation, texts send directly. */
    @Volatile var inCar = false

    private companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }

    /** Runs [req]. Every answer comes with a card: its own, or a summary of the request and answer (see [withCard]). */
    suspend fun prepare(req: Request): ActionResult = withCard(req, prepareInner(req))

    /** Gives [r] a summary card when it has none (errors, directions, apps, YouTube, notes, small talk...). */
    private fun withCard(req: Request, r: ActionResult): ActionResult {
        if (r.readout != null || r.display != null || r.robotLine.isBlank()) return r
        return ActionResult(r.robotLine, r.confirm, r.returnToCard, r.navigateTo, r.dictate, r.voice, null, info.summary(req, r.robotLine, r.after), r.after)
    }

    private suspend fun prepareInner(req: Request): ActionResult = try {
        brand = null
        when (req) {
            is Request.Chat -> ActionResult(req.reply)
            is Request.OpenApp -> openApp(req)
            is Request.TellTime -> tellTime(req)
            is Request.Countdown -> countdown(req)
            is Request.Device -> device(req)
            is Request.PhoneSettingReq -> phoneSetting(req)
            is Request.SmartHome -> smartHome(req)
            is Request.Note -> note(req)
            is Request.ReadNotifications -> readNotifications(req)
            is Request.ReadCalendar -> readCalendar(req)
            is Request.YouTube -> youtube(req)
            is Request.Timer -> timer(req)
            is Request.Stopwatch -> stopwatch(req)
            is Request.TimerControl -> timerControl(req)
            is Request.Alarm -> alarm(req)
            is Request.Navigate -> navigate(req)
            is Request.FindPlace -> findPlace(req)
            is Request.Setting -> setting(req)
            is Request.Discord -> discord(req)
            is Request.PlaceHours -> placeHours(req)
            is Request.PhotoSpots -> photoSpots(req)
            is Request.Reminder -> reminder(req)
            is Request.CalendarEvent -> calendar(req)
            is Request.Text -> text(req)
            is Request.Call -> call(req)
            is Request.Search -> search(req)
            is Request.Math -> math(req)
            is Request.Convert -> ActionResult(req.conversion.spoken, readout = info.conversion(req.conversion))
            is Request.Spell -> spell(req)
            is Request.MediaAsk -> media(req)
            is Request.Weather -> weather(req)
            is Request.Music -> music(req)
            is Request.Effect -> effects(req)
            is Request.EffectPreset -> driver(
                DriverBridge.preset(req.effect, req.preset),
                openingLine = "Opening Driver and applying the ${req.preset} preset.",
            )
            is Request.Lyrics -> if (req.download) driver(
                DriverBridge.lyrics(req.song),
                openingLine = "Opening Driver to download lyrics" + (req.song?.let { " for $it." } ?: "."),
            ) else showLyrics(req)
            Request.NowPlaying -> driver(DriverBridge.nowPlaying(), openingLine = null)
            Request.LikeSong -> ActionResult(com.brick.assistant.media.LikeSong.like(context))
            is Request.AddToPlaylist -> ActionResult(com.brick.assistant.media.LikeSong.addToPlaylist(context, req.playlist))
            is Request.Visualizer -> visualizer(req)
            is Request.Sky -> sky(req)
        }.let { withMusicReadout(req, it) }
    } catch (e: Exception) {
        ActionResult("Something went wrong while handling that request.")
    }

    // ---------------------------------------------------------------- helpers

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun millis(dt: LocalDateTime) = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- apps, time, device

    init { DeviceControls.watchTorch(context) }

    private fun openApp(req: Request.OpenApp): ActionResult {
        val app = AppLauncher.find(AppLauncher.installed(context), req.name)
            ?: return ActionResult("I couldn't find an app called ${req.name}.")
        val intent = AppLauncher.launchIntent(context, app) ?: return ActionResult("${app.label} can't be opened directly.")
        return ActionResult("Opening ${app.label}.") { launch(intent) }
    }

    /** Days until (or since) a holiday, date, time or calendar event; anything without a date is searched. */
    private suspend fun countdown(req: Request.Countdown): ActionResult {
        var t = req.target
        if (t == null && CalendarReader.canRead(context)) {
            CalendarReader.find(context, req.name, req.since)?.let { e ->
                val at = java.time.Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).toLocalDateTime()
                t = Countdown.Target(e.title, at.toLocalDate(), if (e.allDay) null else at.toLocalTime(), event = true)
            }
        }
        val target = t ?: return search(Request.Search(req.query))
        return ActionResult(Countdown.speak(target, req.unit, req.since, req.whenAsk), readout = info.countdown(target, req.since))
    }

    private fun tellTime(req: Request.TellTime): ActionResult {
        if (req.date) return ActionResult(Clock.speakDate(), readout = info.date())
        val place = req.place
        if (place != null) {
            val zone = Clock.zoneFor(place) ?: return ActionResult("I don't know the time zone for $place.")
            return ActionResult(Clock.speakTime(java.time.ZonedDateTime.now(zone), place), readout = info.time(zone, place))
        }
        return ActionResult(Clock.speakTime(), readout = info.time(ZoneId.systemDefault(), null))
    }

    // ---- Phone settings and smart home

    /** How a setting's name sits in a sentence: "airplane mode", but "Wi-Fi", "NFC", "Quick Share". */
    private fun spoken(s: PhoneSetting) = if (s.title.drop(1).any { it.isUpperCase() } || s in setOf(PhoneSetting.BLUETOOTH, PhoneSetting.QUICK_SHARE))
        s.title else s.title.lowercase()

    private fun phoneSetting(req: Request.PhoneSettingReq): ActionResult {
        val st = req.setting ?: return ActionResult("Here are your phone's settings.", readout = info.phoneSettings())
        val card = info.phoneSetting(st)
        val name = spoken(st)
        val Name = name.replaceFirstChar { it.uppercase() }
        val state = PhoneSettings.isOn(context, st)
        fun opened(line: String) = ActionResult(line, readout = card) { PhoneSettings.open(context, st) }
        when (req.ask) {
            SettingAsk.WHERE -> return ActionResult(
                "It's in ${st.spokenPath}." + (st.quick?.let { " It's also a tile in the quick panel." } ?: ""),
                readout = card,
            )
            SettingAsk.STATUS -> {
                if (req.open) return opened("Opening $name.")
                return ActionResult(
                    when (state) {
                        true -> "$Name is on."
                        false -> "$Name is off."
                        null -> "$Name: ${PhoneSettings.stateText(context, st)}."
                    },
                    readout = card,
                )
            }
            SettingAsk.SET -> Unit
        }
        val target = req.on ?: state?.let { !it }
        if (target != null && state == target) return ActionResult("$Name is already ${if (target) "on" else "off"}.", readout = card)
        val word = if (target == true) "on" else "off"
        return when (st.reach) {
            SettingReach.DIRECT -> when {
                target == null -> opened("Here's $name.")
                st == PhoneSetting.DND && PhoneSettings.setDirect(context, st, target) -> ActionResult("Do Not Disturb is $word.", readout = info.phoneSetting(st))
                st == PhoneSetting.DND -> assistantSwitch(st, target, card)
                !PhoneSettings.canWrite(context) -> ActionResult(
                    "I need permission to change system settings for that. Allow it for Brick, then ask again.", readout = card,
                ) { launch(PhoneSettings.writePermission(context)) }
                PhoneSettings.setDirect(context, st, target) -> ActionResult("$Name $word.", readout = info.phoneSetting(st))
                else -> opened("I couldn't change $name, so here's the setting.")
            }
            SettingReach.ASSISTANT -> if (target == null) opened("Here's $name.") else assistantSwitch(st, target, card)
            SettingReach.OPEN -> when {
                st == PhoneSetting.BLUETOOTH && target == true && PhoneSettings.requestBluetoothOn(context) ->
                    ActionResult("Tap Allow to turn on Bluetooth.", readout = card)
                st == PhoneSetting.MEDIA_OUTPUT -> opened("Pick where the sound should play.")
                st == PhoneSetting.QUICK_SHARE || st == PhoneSetting.AOD || st == PhoneSetting.DARK_MODE || st == PhoneSetting.HOTSPOT ->
                    opened("Android doesn't let apps switch $name, so here's the setting.")
                else -> opened("Android doesn't let apps switch $name, so here's the panel.")
            }
        }
    }

    /** Airplane mode, power saving or Do Not Disturb through the assistant session; the setting's page otherwise. */
    private fun assistantSwitch(st: PhoneSetting, on: Boolean, card: Readout): ActionResult {
        val intent = PhoneSettings.assistantIntent(st, on)
        if (intent != null && com.brick.assistant.assist.BrickVoiceInteractionService.runVoiceAction(intent)) {
            return ActionResult("Turning ${if (on) "on" else "off"} ${spoken(st)}.", readout = card)
        }
        return ActionResult(
            "Only the default digital assistant can switch ${spoken(st)}. Make Brick your digital assistant, or use this setting.",
            readout = card,
        ) { PhoneSettings.open(context, st) }
    }

    private suspend fun smartHome(req: Request.SmartHome): ActionResult {
        val key = settings.current.goveeKey
        val wantsChange = req.on != null || req.brightness != null || req.color != null
        val app = req.app
        // "Open Google Home", or a device in an app Brick can't switch.
        if (app != null && (app != HomeApp.GOVEE || !wantsChange || key.isBlank())) {
            val line = when {
                !app.installed(context) -> "${app.label} isn't installed."
                !wantsChange -> "Opening ${app.label}."
                app == HomeApp.GOVEE -> "Add your Govee API key in Brick's settings, under Smart home, and I can switch Govee lights myself. Here's Govee Home."
                else -> "${app.label} doesn't let other apps switch its devices, so here's ${app.label}."
            }
            return ActionResult(line, readout = info.smartHome(key, Govee.devices)) { app.open(context) }
        }
        if (key.isBlank()) {
            val fallback = HomeApp.entries.firstOrNull { it.installed(context) }
            val line = "I can switch Govee lights once you add a Govee API key in Brick's settings, under Smart home." +
                (fallback?.let { " Here's ${it.label}." } ?: "")
            return ActionResult(line, readout = info.smartHome(key, emptyList())) { fallback?.open(context) }
        }
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Govee.load(key) }
            ?: return ActionResult("Govee didn't accept the API key. Check it in Brick's settings, under Smart home.", readout = info.smartHome(key, emptyList()))
        if (list.isEmpty()) return ActionResult("I don't see any Govee lights on your account.", readout = info.smartHome(key, list))
        val found = Govee.match(req.target, list)
        if (found.isEmpty()) return ActionResult("I can't find a light called ${req.target}.", readout = info.smartHome(key, list))
        val names = found.map { it.name }.let { n -> if (n.size <= 2) n.joinToString(" and ") else n.dropLast(1).joinToString(", ") + ", and " + n.last() }
        val all = req.target == null && found.size > 1
        val what = if (all) "the lights" else names
        if (!wantsChange) {
            val onCount = found.count { it.on == true }
            val line = if (found.size == 1) "$names is ${if (found[0].on == true) "on" else "off"}." else "$onCount of ${found.size} lights are on."
            return ActionResult(line, readout = info.smartHome(key, list, found))
        }
        val color = req.color?.let { c -> HomeColors.named[c]?.let { c to it } }
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            found.count { d ->
                when {
                    req.on == false -> Govee.power(key, d, false)
                    color != null -> Govee.power(key, d, true) && Govee.color(key, d, color.second)
                    req.brightness != null -> (d.on == true || Govee.power(key, d, true)) && Govee.brightness(key, d, req.brightness)
                    else -> Govee.power(key, d, true)
                }
            }
        }
        val line = when {
            ok == 0 -> "Govee didn't answer, so nothing changed."
            req.on == false -> "Turned off $what."
            color != null -> "${what.replaceFirstChar { it.uppercase() }} ${if (all || found.size > 1) "are" else "is"} ${color.first} now."
            req.brightness != null -> "Set $what to ${req.brightness} percent."
            else -> "Turned on $what."
        } + if (ok in 1 until found.size) " ${found.size - ok} didn't answer." else ""
        return ActionResult(line, readout = info.smartHome(key, list, found))
    }

    private fun device(req: Request.Device): ActionResult = when (req.op) {
        DeviceOp.FLASH_ON -> DeviceControls.flashlight(context, true).let { ActionResult(it, readout = info.flashlight()) }
        DeviceOp.FLASH_OFF -> DeviceControls.flashlight(context, false).let { ActionResult(it, readout = info.flashlight()) }
        DeviceOp.FLASH_TOGGLE -> DeviceControls.flashlight(context, null).let { ActionResult(it, readout = info.flashlight()) }
        DeviceOp.VOLUME_UP, DeviceOp.VOLUME_DOWN, DeviceOp.VOLUME_SET, DeviceOp.VOLUME_MAX, DeviceOp.MUTE, DeviceOp.UNMUTE ->
            DeviceControls.volume(context, req.op, req.percent).let { ActionResult(it, readout = info.volume()) }
        DeviceOp.RING_NORMAL, DeviceOp.RING_VIBRATE, DeviceOp.RING_SILENT, DeviceOp.DND_ON, DeviceOp.DND_OFF -> {
            val (line, settings) = DeviceControls.ringer(context, req.op)
            val r = info.ringer()
            if (settings != null) ActionResult(line, readout = Readout(r.text, controls = r.controls + Control.Button("Grant access") { launch(settings) })) { launch(settings) }
            else ActionResult(line, readout = r)
        }
        DeviceOp.BATTERY -> ActionResult(DeviceControls.battery(context), readout = info.battery())
        DeviceOp.BT_BATTERY -> btBattery(req.target)
        DeviceOp.BRIGHT_UP, DeviceOp.BRIGHT_DOWN, DeviceOp.BRIGHT_SET -> {
            val (line, settings) = DeviceControls.brightness(context, req.op, req.percent)
            if (settings != null) ActionResult(line, readout = info.brightness()) { launch(settings) } else ActionResult(line, readout = info.brightness())
        }
        DeviceOp.WIFI, DeviceOp.BLUETOOTH -> {
            val (line, panel) = DeviceControls.connectionPanel(req.op)
            ActionResult(line, readout = info.connection(req.op == DeviceOp.WIFI, panel)) { launch(panel) }
        }
    }

    // ---------------------------------------------------------------- notes

    private fun note(req: Request.Note): ActionResult {
        req.text?.let { return saveNote(it) }
        // Notes use Brick's voice only: Brick's pool line, then it just listens; Brick confirms at the end.
        return ActionResult(
            "Go ahead, I'm writing.",
            voice = LineVoice.NONE,
            dictate = { said ->
                if (said.isBlank()) ActionResult("I didn't hear anything, so I didn't make a note.", voice = LineVoice.BRICK) else saveNote(said)
            },
        )
    }

    private fun saveNote(text: String): ActionResult {
        val clean = text.trim().replaceFirstChar { it.uppercase() }
        val preview = if (clean.length > 60) clean.take(60).substringBeforeLast(' ') + "…" else clean
        return ActionResult("Saving your note: $preview", voice = LineVoice.BRICK) {
            if (!NotesWriter.open(context, clean)) android.widget.Toast.makeText(context, "No notes app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- notifications, email, calendar

    private fun isLocked() = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Personal things are only read aloud when the phone is unlocked. */
    private fun lockedNotice(): ActionResult = ActionResult("Unlock your phone first, then ask me again.") { }

    private fun readNotifications(req: Request.ReadNotifications): ActionResult {
        if (isLocked()) return lockedNotice()
        if (!NotificationReader.hasAccess()) {
            return ActionResult("To read your notifications, Brick needs notification access. Opening the setting.") {
                launch(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val items = NotificationReader.current(context).let { all -> if (req.email) all.filter { it.email } else all }
        val shown = req.from?.let { f -> items.filter { it.title.contains(f, true) || it.app.contains(f, true) } }?.ifEmpty { null } ?: items
        return ActionResult(NotificationReader.speak(items, req.email, req.from), readout = info.notifications(shown, req.email))
    }

    private fun readCalendar(req: Request.ReadCalendar): ActionResult {
        if (isLocked()) return lockedNotice()
        if (!CalendarReader.canRead(context)) return ActionResult("I need permission to read your calendar. You can grant it in Settings.")
        val events = CalendarReader.events(context, req.range)
        return ActionResult(CalendarReader.speak(events, req.range), readout = info.agenda(events, req.range))
    }

    // ---------------------------------------------------------------- YouTube

    private fun enabled(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: Exception) {
        false
    }

    private suspend fun youtube(req: Request.YouTube): ActionResult {
        val yt = "com.google.android.youtube"
        val ytm = "com.google.android.apps.youtube.music"
        val q = req.query
        if (req.play) {
            val id = YouTubeSearch.firstVideoId(q)
            if (id != null) {
                val music = req.music && enabled(ytm)
                return ActionResult(if (music) "Playing $q on YouTube Music." else "Playing $q on YouTube.") {
                    val ok = if (music) launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$id")).setPackage(ytm))
                    else launch(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).setPackage(yt))
                    ok || launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
                }
            }
            // No result found online: fall back to search.
        }
        val music = req.music && enabled(ytm)
        return ActionResult(if (music) "Searching YouTube Music for $q." else "Searching YouTube for $q.") {
            val ok = when {
                music -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(q)}")).setPackage(ytm))
                enabled(yt) -> launch(Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra(SearchManager.QUERY, q))
                else -> false
            }
            if (!ok) launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")))
        }
    }

    // ---------------------------------------------------------------- timer

    private fun timer(req: Request.Timer): ActionResult {
        val sec = req.seconds ?: return ActionResult("Please tell me how long the timer should be.")
        if (sec < 1 || sec > 86_400) return ActionResult("Timers must be between one second and 24 hours.")
        val ok = launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, sec.toInt())
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Brick timer"),
        )
        if (ok) com.brick.assistant.timers.ClockTimers.started(context, sec)
        return if (ok) ActionResult("Setting timer for ${TimeParser.formatDuration(sec)}.", readout = info.timer(sec))
        else ActionResult("I could not find a clock app to set the timer.")
    }

    private fun timerControl(req: Request.TimerControl): ActionResult {
        val ct = com.brick.assistant.timers.ClockTimers
        if (req.op == TimerOp.STATUS) return timerStatus()
        if (!ct.canControl()) {
            return ActionResult(
                "To pause or resume your Clock timers, Brick needs notification access. Opening the Clock app for now.",
            ) { launch(Intent(AlarmClock.ACTION_SHOW_TIMERS)) }
        }
        val shown = ct.notifications(context)
        if (shown.isEmpty()) return ActionResult("You don't have any timers running.")
        fun many(n: Int) = if (n == 1) "your timer" else "$n timers"
        return when (req.op) {
            TimerOp.PAUSE -> {
                val n = ct.press(context, com.brick.assistant.timers.TimerPress.PAUSE, req.all)
                val left = ct.known(context).firstOrNull { it.paused }?.left()
                when {
                    n == 0 && shown.any { ct.offers(it, com.brick.assistant.timers.TimerPress.RESUME) } -> ActionResult("Your timer is already paused.")
                    n == 0 -> ActionResult("I couldn't find a pause button on your timer.")
                    n == 1 && left != null -> ActionResult("Timer paused with ${TimeParser.formatDuration((left + 999) / 1000)} left.")
                    else -> ActionResult("Paused ${many(n)}.")
                }
            }
            TimerOp.RESUME -> {
                val n = ct.press(context, com.brick.assistant.timers.TimerPress.RESUME, req.all)
                val left = ct.known(context).firstOrNull { !it.paused }?.left()
                when {
                    n == 0 -> ActionResult("Your timer is already running.")
                    n == 1 && left != null -> ActionResult("Resuming your timer. ${TimeParser.formatDuration((left + 999) / 1000)} to go.")
                    else -> ActionResult("Resumed ${many(n)}.")
                }
            }
            else -> {
                val n = ct.press(context, com.brick.assistant.timers.TimerPress.CANCEL, req.all)
                if (n == 0) ActionResult("I couldn't find a button to stop your timer.")
                else ActionResult(if (n == 1) "Timer canceled." else "Canceled $n timers.")
            }
        }
    }

    private fun timerStatus(): ActionResult {
        val ct = com.brick.assistant.timers.ClockTimers
        ct.syncWithClock(context)
        val known = ct.known(context)
        if (known.isNotEmpty()) {
            return ActionResult(
                readout = info.timers(known.map { it.durationMs to it.left() }, known.map { it.paused }),
                robotLine = known.joinToString(" ") { x ->
                    val left = TimeParser.formatDuration((x.left() + 999) / 1000)
                    (if (known.size > 1) "Your ${TimeParser.formatDuration(x.durationMs / 1000)} timer has " else "You have ") +
                        left + " left" + (if (x.paused) ", paused." else ".")
                },
            )
        }
        val shown = ct.notifications(context)
        val end = shown.firstNotNullOfOrNull { ct.shownEndTime(it) }
        return when {
            end != null -> ActionResult("Your timer ends at $end.")
            shown.isNotEmpty() -> ActionResult("Your timer is running. Here it is in the Clock app.") { launch(Intent(AlarmClock.ACTION_SHOW_TIMERS)) }
            !ct.canControl() -> ActionResult("Here are your timers.") { launch(Intent(AlarmClock.ACTION_SHOW_TIMERS)) }
            else -> ActionResult("You don't have any timers running.")
        }
    }
    // ---------------------------------------------------------------- alarm

    private fun alarm(req: Request.Alarm): ActionResult {
        val pt = req.time
        if (!pt.hasTime || pt.dateTime == null) return ActionResult("What time should the alarm be set for?")
        val at = pt.dateTime
        val now = LocalDateTime.now()
        val time = TimeParser.speakTime(at.toLocalTime())
        // The Clock app rings at the next occurrence of the time; it can't take a date. When you named a day
        // that isn't that next occurrence, say so instead of setting an alarm for the wrong day.
        if (req.days.isEmpty() && pt.hasDate) {
            if (!at.isAfter(now)) return ActionResult("That time has already passed.")
            var nextOcc = now.toLocalDate().atTime(at.toLocalTime())
            if (!nextOcc.isAfter(now)) nextOcc = nextOcc.plusDays(1)
            if (nextOcc.toLocalDate() != at.toLocalDate()) {
                val nextDay = if (nextOcc.toLocalDate() == now.toLocalDate()) "today" else "tomorrow"
                return ActionResult(
                    "The Clock app can only set the next $time alarm, which is $nextDay. Should I set a reminder for ${TimeParser.describe(at)} instead?",
                    confirm = Confirmation(noLine = "Okay, no alarm set.") {
                        reminder(Request.Reminder(req.label ?: "Alarm", pt))
                    },
                )
            }
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, req.label ?: "Brick alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (req.days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(req.days))
        if (!launch(i)) return ActionResult("I could not find a clock app to set the alarm.")

        val label = req.label?.let { ", labeled $it" } ?: ""
        val whenText = when {
            req.days.isNotEmpty() -> " " + describeDays(req.days)
            // Clock apps ring at the next occurrence of the time, so describe that.
            else -> {
                var next = now.toLocalDate().atTime(at.toLocalTime())
                if (!next.isAfter(now)) next = next.plusDays(1)
                if (next.toLocalDate() == now.toLocalDate()) " today" else " tomorrow"
            }
        }
        val note = if (req.days.isEmpty() && ChronoUnit.HOURS.between(now, at) > 24) {
            " Clock alarms ring at the next $time, so set a reminder for later days."
        } else {
            ""
        }
        return ActionResult(
            "Setting alarm for $time$whenText$label.$note",
            readout = info.alarm(at, req.days.takeIf { it.isNotEmpty() }?.let { describeDays(it) }, req.label),
        )
    }

    // ---------------------------------------------------------------- stopwatch

    private fun stopwatch(req: Request.Stopwatch): ActionResult {
        val c = context
        val s = when (req.op) {
            StopwatchOp.START -> Stopwatch.start(c)
            StopwatchOp.STOP -> Stopwatch.stop(c)
            StopwatchOp.RESET -> Stopwatch.reset(c)
            StopwatchOp.LAP -> Stopwatch.lap(c)
            StopwatchOp.STATUS -> Stopwatch.state(c)
        }
        val t = TimeParser.formatDuration(s.elapsed() / 1000)
        val line = when (req.op) {
            StopwatchOp.START -> if (s.banked > 0) "Stopwatch resumed at $t." else "Stopwatch started."
            StopwatchOp.STOP -> if (s.elapsed() > 0) "Stopwatch stopped at $t." else "The stopwatch isn't running."
            StopwatchOp.RESET -> "Stopwatch reset."
            StopwatchOp.LAP -> if (s.running) "Lap ${s.laps.size}: $t." else "The stopwatch isn't running."
            StopwatchOp.STATUS -> when {
                s.running -> "The stopwatch is at $t."
                s.elapsed() > 0 -> "The stopwatch is stopped at $t."
                else -> "The stopwatch isn't running."
            }
        }
        return ActionResult(line, readout = stopwatchReadout())
    }

    private fun stopwatchReadout(): Readout {
        val s = Stopwatch.state(context)
        val controls = listOf(
            Control.Button(if (s.running) "|| stop" else "> start") { if (Stopwatch.state(context).running) Stopwatch.stop(context) else Stopwatch.start(context) },
            Control.Button("lap") { Stopwatch.lap(context) },
            Control.Button("reset") { Stopwatch.reset(context) },
        )
        return info.stopwatch(s.running, s.elapsed(), s.firstStart, s.laps, controls).let { Readout(it.text, controls = it.controls, refresh = ::stopwatchReadout, live = s.running) }
    }

    private fun describeDays(days: List<Int>): String {
        val set = days.toSet()
        return when (set) {
            (1..7).toSet() -> "every day"
            (2..6).toSet() -> "every weekday"
            setOf(1, 7) -> "every weekend"
            else -> {
                val names = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                val list = days.sorted().map { names[it - 1] }
                "every " + if (list.size == 1) list[0] else list.dropLast(1).joinToString(", ") + " and " + list.last()
            }
        }
    }

    // ---------------------------------------------------------------- maps

    private fun navigate(req: Request.Navigate): ActionResult {
        // A saved place ("home", "work"): go to its address, and say its name rather than the address.
        val saved = com.brick.assistant.data.Places.find(req.destination)
        val dest = saved?.address ?: req.destination
        val home = saved != null && com.brick.assistant.data.Places.key(saved.name) == "home"
        val to = when {
            home -> "home"
            saved != null -> "to ${saved.name}"
            else -> "to $dest"
        }
        val shown = if (home) "home" else saved?.name ?: dest
        if (inCar) {
            // In the car, ask first, then hand the place to the car's navigation app.
            val uri = "geo:0,0?q=${Uri.encode(dest)}"
            return ActionResult(
                "Start directions $to? Say yes to go.",
                confirm = Confirmation("Okay, I won't start directions.") { ActionResult("Starting directions $to.", navigateTo = uri) },
            )
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
        val defaultMaps = try {
            context.packageManager.resolveActivity(geo, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        // Directions never start on their own: Google Maps opens its route preview and you press Start.
        val line = if (req.directions) "Here's the route $to. Press Start when you're ready." else "Showing $shown on the map."
        return ActionResult(line) {
            val ok = when {
                req.directions && (defaultMaps == "com.google.android.apps.maps" || defaultMaps == null || defaultMaps == "android") ->
                    launch(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(dest)}"))
                            .setPackage("com.google.android.apps.maps"),
                    )
                req.directions && defaultMaps == "com.waze" ->
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=${Uri.encode(dest)}")).setPackage(defaultMaps))
                else -> false
            } || launch(geo) ||
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(dest)}")))
            if (!ok) android.widget.Toast.makeText(context, "No maps app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Food, shops and other places: a Google Maps search near you (or in the place you named), best first. */
    private fun findPlace(req: Request.FindPlace): ActionResult {
        val q = FindPlaces.query(req)
        val line = FindPlaces.line(req)
        if (inCar) return ActionResult(line, navigateTo = "geo:0,0?q=${Uri.encode(q)}")
        return ActionResult(line) { openMapsSearch(q) }
    }

    private fun openMapsSearch(query: String) {
        launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"))
                .setPackage("com.google.android.apps.maps"),
        ) || launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")))
    }

    private suspend fun placeHours(req: Request.PlaceHours): ActionResult {
        val near = WeatherService.deviceLocation(context)
        val p = PlaceHours.find(req.place, near)
            ?: return ActionResult("I couldn't look up ${req.place}, so I'll show it in Google Maps.") { openMapsSearch(req.place) }
        val line = PlaceHours.speak(p, req.ask, req.tomorrow)
        // No posted hours: the Google Maps place page has them.
        return if (p.hours == null) ActionResult(line) { openMapsSearch(req.place) } else ActionResult(line)
    }

    /**
     * Night-sky darkness: the Bortle class here (or at a place), or the darkest sky within a radius / worldwide.
     * The details come from lightpollutionmap.app, opened at the right spot and zoom: the site asks apps to
     * link to it rather than pull its numbers, so Brick speaks a number only when it has its own sky data.
     */
    private suspend fun sky(req: Request.Sky): ActionResult {
        val center: WeatherService.Place = when {
            // Worldwide: the best skies are tied at pristine, so the nearest of them to you wins.
            req.worldwide -> WeatherService.deviceLocation(context) ?: WeatherService.Place(20.0, 0.0, null)
            req.place != null -> findPlace(req.place)
                ?: return ActionResult("I couldn't find ${req.place} on the map.")
            else -> WeatherService.deviceLocation(context)
                ?: return ActionResult("I need your location for that. Allow location access for Brick and try again.")
        }
        val where = req.place?.let { p -> center.name ?: p } ?: "your location"
        // "... hide cords", or always with the setting on: no latitude and longitude on the readout.
        val hide = req.hideCoords || settings.current.hideSkyCoords
        val zoom = when {
            req.worldwide -> 2
            req.darkest -> SkyMap.zoomForRadius(req.radiusMiles ?: IntentParser.DEFAULT_SKY_RADIUS)
            else -> 10
        }
        val url = SkyMap.link(center.lat, center.lon, zoom)
        val open = fun() { launch(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

        // "... obscure my location", or always with the setting on: the numbers are for where you are, the readout
        // names a decoy place some distance away. A place you named isn't your location, so it stays as it is.
        val s = settings.current
        val obscure = (req.obscure || s.obscureSkyLocation) && req.place == null

        if (!req.darkest) {
            // Brick's own sky data, when it has some for this spot.
            SkyData.sqmAt(context, center.lat, center.lon)?.let { sqm ->
                if (obscure) {
                    val d = decoy(center.lat, center.lon, decoyMiles(req.obscureMiles, s.obscureSkyLocation))
                    val (dLat, dLon) = d.lat to d.lon
                    val name = d.name ?: "somewhere far away"
                    return ActionResult(
                        SkyQuality.answer(req.aspect, sqm, name),
                        display = SkyQuality.panel(req.aspect, sqm, name, note = if (hide) null else coords(dLat, dLon)),
                    )
                }
                return ActionResult(
                    SkyQuality.answer(req.aspect, sqm, req.place?.let { where }),
                    display = SkyQuality.panel(req.aspect, sqm, where, note = if (hide) null else coords(center.lat, center.lon)),
                    // Answered from Brick's own data: the map opens only when you asked for it.
                    after = open.takeIf { req.map },
                )
            }
            if (SkyData.hasGrid(context) && !SkyData.covers(context, center.lat)) {
                return ActionResult("I don't have sky data that far ${if (center.lat < 0) "south" else "north"}. My map covers 85 north to 60 south.")
            }
            if (SkyData.hasGrid(context)) return ActionResult("$where is out at sea, and I only have sky data over land.")
            return ActionResult("Opening the light pollution map at $where.", after = open)
        }
        SkyData.darkest(context, center.lat, center.lon, if (req.worldwide) null else req.radiusMiles)?.let { spot ->
            val name = nearbyName(spot.lat, spot.lon)
            if (obscure) {
                // The spot is the answer, but its distance from you would give you away: leave that and "of you" out.
                val label = name ?: if (req.worldwide) "anywhere on earth" else "a dark spot"
                return ActionResult(
                    (if (req.worldwide) "Nearest pristine sky" else "Darkest nearby") + (name?.let { ": near $it. " } ?: ". ") +
                        SkyQuality.answer(req.aspect, spot.sqm),
                    display = SkyQuality.panel(req.aspect, spot.sqm, label, title = "DARKEST SKY", note = if (hide) null else coords(spot.lat, spot.lon)),
                    after = if (req.map) fun() { launch(Intent(Intent.ACTION_VIEW, Uri.parse(SkyMap.link(spot.lat, spot.lon, 9)))) } else null,
                )
            }
            // "Darkest within 100 miles: 73 miles away, near Phoenicia. Bortle 3.1, rural."
            val around = if (req.place != null) " of $where" else ""
            val intro = (if (req.worldwide) "Nearest pristine sky: " else "Darkest within ${req.radiusMiles ?: IntentParser.DEFAULT_SKY_RADIUS} miles$around: ") +
                (if (spot.miles < 1) "right here" else "%,d miles away".format(java.util.Locale.US, spot.miles)) +
                (name?.let { ", near $it" } ?: "") + ". "
            return ActionResult(
                intro + SkyQuality.answer(req.aspect, spot.sqm),
                display = SkyQuality.panel(
                    req.aspect, spot.sqm, name ?: if (req.worldwide) "anywhere on earth" else "within ${req.radiusMiles ?: IntentParser.DEFAULT_SKY_RADIUS} mi of you",
                    title = "DARKEST SKY",
                    note = "%,d mi away".format(java.util.Locale.US, spot.miles) + if (hide) "" else "  " + coords(spot.lat, spot.lon),
                ),
                after = if (req.map) fun() { launch(Intent(Intent.ACTION_VIEW, Uri.parse(SkyMap.link(spot.lat, spot.lon, 9)))) } else null,
            )
        }
        val line = if (req.worldwide) {
            "Opening the world light pollution map."
        } else {
            val r = req.radiusMiles ?: IntentParser.DEFAULT_SKY_RADIUS
            "Opening the light pollution map within $r miles."
        }
        return ActionResult(line, after = open)
    }

    /**
     * Any place in the world by name: towns, countries, parks, deserts, mountains ("Atacama Desert", "Mauna Kea").
     * Android's geocoder knows landmarks; Open-Meteo's (towns only) is the fallback, then just the first word or
     * two of the name ("paris france" -> "paris").
     */
    private suspend fun findPlace(name: String): WeatherService.Place? {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, java.util.Locale.US).getFromLocationName(name, 1)?.firstOrNull()
            } catch (e: Exception) {
                null
            }
        }?.let { a ->
            val main = a.featureName?.takeUnless { it.any(Char::isDigit) || it.equals(a.countryName, true) }
                ?: a.locality ?: a.adminArea
            val region = a.countryName?.takeUnless { it.equals(main, true) }
            val label = listOfNotNull(main, region).joinToString(", ").ifEmpty { null }
            return WeatherService.Place(a.latitude, a.longitude, label)
        }
        WeatherService.geocode(name)?.let { return it }
        val words = name.split(' ')
        for (n in minOf(2, words.size - 1) downTo 1) WeatherService.geocode(words.take(n).joinToString(" "))?.let { return it }
        return null
    }

    /**
     * A decoy point [miles] from you in a random direction, on land when Brick's sky grid can tell (so it names a
     * real-looking place rather than open ocean); after a dozen tries over water it takes the last one.
     */
    /** The distance you asked for, else random up to 3,000 miles from the settings minimum (toggle on) or 100. */
    private fun decoyMiles(asked: Int?, toggleOn: Boolean): Int {
        val lo = if (toggleOn) settings.current.obscureMinMiles.coerceIn(IntentParser.OBSCURE_MIN_MILES, IntentParser.OBSCURE_MAX_MILES) else IntentParser.OBSCURE_MIN_MILES
        return asked ?: (lo..IntentParser.OBSCURE_MAX_MILES).random()
    }

    private class Decoy(val lat: Double, val lon: Double, val name: String?)

    /** A decoy point [miles] away that has a name, trying a few directions (each one a geocoder lookup). */
    private suspend fun decoy(lat: Double, lon: Double, miles: Int): Decoy {
        var last: Pair<Double, Double>? = null
        for (tries in 0 until 30) {
            val p = SkyMap.offset(lat, lon, miles.toDouble(), Math.random() * 360)
            last = p
            // Skip polar and open-ocean points cheaply (Brick's sky grid knows land) before asking the geocoder.
            if (kotlin.math.abs(p.first) > 65 || (SkyData.hasGrid(context) && SkyData.sqmAt(context, p.first, p.second) == null)) continue
            nearbyName(p.first, p.second)?.let { return Decoy(p.first, p.second, it) }
            if (tries > 20) break
        }
        return Decoy(last!!.first, last.second, null)
    }

    /** "38.57N 109.55W" (ASCII, for the sky panel). */
    private fun coords(lat: Double, lon: Double) = String.format(
        java.util.Locale.US, "%.2f%s %.2f%s", kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W",
    )

    /** The town or area nearest a point ("Moab, Utah"), from Android's geocoder; null offline or out in the wild. */
    private suspend fun nearbyName(lat: Double, lon: Double): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val a = android.location.Geocoder(context, java.util.Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                ?: return@withContext null
            val local = a.locality ?: a.subAdminArea ?: a.featureName?.takeUnless { it.any(Char::isDigit) }
            listOfNotNull(local, a.adminArea ?: a.countryName).distinct().joinToString(", ").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun photoSpots(req: Request.PhotoSpots): ActionResult {
        val here = WeatherService.deviceLocation(context)
            ?: return ActionResult("I need your location to find photography spots. Allow location access for Brick and try again.")
        val spots = PhotoSpots.search(here.lat, here.lon, req.radiusMiles, req.kind)
            ?: return ActionResult("The map service didn't answer. Try again in a minute.")
        if (spots.isEmpty()) return ActionResult(PhotoSpots.speak(spots, req.radiusMiles, req.kind))
        return spotRoute(spots, 0)
    }

    /**
     * Opens the Google Maps route to spot [i] (you press Start), says a short line about it and asks whether
     * to see another; "yes" moves on to the next spot.
     */
    private fun spotRoute(spots: List<PhotoSpot>, i: Int): ActionResult {
        val s = spots[i]
        val more = i + 1 < spots.size
        if (inCar) {
            // In the car: describe it, then "yes" starts directions and "next" moves on.
            val uri = "geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.name)})"
            val line = PhotoSpots.describe(s, first = i == 0) +
                if (more) " Start directions there? Say yes, or say next for another location." else " Start directions there?"
            return ActionResult(
                line,
                confirm = Confirmation(
                    "Okay. Enjoy the shoot.",
                    onNext = if (more) ({ spotRoute(spots, i + 1) }) else null,
                ) { ActionResult("Starting directions to ${s.name}.", navigateTo = uri) },
            )
        }
        val line = PhotoSpots.describe(s, first = i == 0) + " Here's the route." +
            if (more) " Would you like to see another location?" else " That's the last spot I found."
        val ask = if (more) Confirmation("Okay. Enjoy the shoot.") { spotRoute(spots, i + 1) } else null
        return ActionResult(line, confirm = ask, returnToCard = more) {
            val dir = "https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}"
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(dir)).setPackage("com.google.android.apps.maps")) ||
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.name)})"))) ||
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(dir)))
        }
    }

    // ---------------------------------------------------------------- reminder

    private fun reminder(req: Request.Reminder): ActionResult {
        val pt = req.time
        val now = LocalDateTime.now()
        val at = pt.dateTime ?: now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        if (!at.isAfter(now)) return ActionResult("That time has already passed.")
        ReminderScheduler.schedule(context, req.task, millis(at))
        val whenText = when {
            pt.relativeSeconds != null -> "in " + TimeParser.formatDuration(pt.relativeSeconds)
            pt.dateTime == null -> "in 1 hour"
            else -> TimeParser.describe(at)
        }
        return ActionResult("Reminder set: ${req.task}, $whenText.", readout = info.reminder(req.task, at))
    }

    // ---------------------------------------------------------------- calendar

    private fun calendar(req: Request.CalendarEvent): ActionResult {
        val pt = req.time
        val allDay = pt.hasDate && !pt.hasTime
        val start = pt.dateTime ?: LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)
        val startMs: Long
        val endMs: Long
        if (allDay) {
            startMs = start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            endMs = startMs + 86_400_000L
        } else {
            startMs = millis(start)
            endMs = startMs + 3_600_000L
        }
        val whenText = when {
            pt.relativeSeconds != null -> "in " + TimeParser.formatDuration(pt.relativeSeconds)
            else -> TimeParser.describe(start, includeTime = !allDay)
        }
        val line = "Creating calendar event: ${req.title}, $whenText."

        if (CalendarHelper.insert(context, req.title, startMs, endMs, allDay)) return ActionResult(line, readout = info.newEvent(req.title, start, allDay, saved = true))

        return ActionResult(line, readout = info.newEvent(req.title, start, allDay, saved = false)) {
            val ok = launch(
                Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, req.title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay),
            )
            if (!ok) android.widget.Toast.makeText(context, "No calendar app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- texts

    private fun text(req: Request.Text): ActionResult {
        var contact: Contact? = null
        var body = req.body
        if (body == null) {
            // "text mom I'm running late": try the longest leading words that match a contact.
            val words = req.recipient.split(" ")
            for (n in minOf(3, words.size) downTo 1) {
                val c = ContactsLookup.find(context, words.take(n).joinToString(" "))
                if (c != null) {
                    contact = c
                    body = words.drop(n).joinToString(" ")
                    break
                }
            }
            if (contact == null) body = words.drop(1).joinToString(" ")
        } else {
            contact = ContactsLookup.find(context, req.recipient)
        }
        val who = if (req.body == null) req.recipient.split(" ").first() else req.recipient
        val found = contact ?: return notFound(who)
        val msg = body.orEmpty().trim()

        if (msg.isEmpty()) {
            // No message yet: ask for it, take it down until 5 seconds of silence, then confirm as usual.
            return ActionResult(
                "What should the text to ${found.name} say?",
                dictate = { said ->
                    if (said.isBlank()) ActionResult("I didn't hear a message, so I didn't send anything.")
                    else confirmText(found, said.trim().replaceFirstChar { it.uppercase() })
                },
            )
        }
        return confirmText(found, msg)
    }

    /** Always confirm first: nothing is sent until the user says yes. */
    private fun confirmText(found: Contact, msg: String): ActionResult = ActionResult(
        "Send this text to ${found.name}: $msg? Say yes to send it, or no to cancel.",
        readout = info.text(found, msg, "say yes to send"),
        confirm = Confirmation(noLine = "Okay, I won't send it.") {
            sendConfirmedText(found, msg).let { r ->
                val status = when {
                    r.robotLine.startsWith("Text sent") -> "sent"
                    r.robotLine.startsWith("Unlock") -> "sends after unlock"
                    r.robotLine.contains("Messages") -> "opened in Messages"
                    else -> "not sent"
                }
                ActionResult(r.robotLine, readout = info.text(found, msg, status), after = r.after)
            }
        },
    )

    /**
     * After you said yes: Brick sends the text. On the lock screen it asks you to unlock first, then sends
     * (nobody can text your contacts from a locked phone). Without the SMS permission, Messages opens
     * pre-filled instead.
     */
    private fun sendConfirmedText(to: Contact, msg: String): ActionResult {
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) {
            return ActionResult("Brick isn't allowed to send texts, so I opened it in Messages. Tap send, or allow SMS for Brick in Settings.") {
                launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(to.number)}")).putExtra("sms_body", msg))
            }
        }
        val send = {
            try {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION") SmsManager.getDefault()
                }
                sms.sendMultipartTextMessage(to.number, null, sms.divideMessage(msg), null, null)
                true
            } catch (e: Exception) {
                false
            }
        }
        val locked = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked && !inCar) {
            // The card's unlock prompt runs this only after you unlock.
            return ActionResult("Unlock your phone and I'll send it to ${to.name}.") {
                if (!send()) android.widget.Toast.makeText(context, "The text to ${to.name} could not be sent", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        return if (send()) ActionResult("Text sent to ${to.name}.") else ActionResult("The text to ${to.name} could not be sent.")
    }
    private fun notFound(name: String): ActionResult =
        if (!ContactsLookup.canRead(context)) {
            ActionResult("I need permission to read your contacts to reach $name. You can grant it in Settings.")
        } else {
            ActionResult("I could not find a contact named $name.")
        }

    // ---------------------------------------------------------------- calls

    private fun call(req: Request.Call): ActionResult {
        val contact = ContactsLookup.find(context, req.who) ?: return notFound(req.who)
        return ActionResult("Calling ${contact.name}.", readout = info.call(contact)) {
            val uri = Uri.parse("tel:${Uri.encode(contact.number)}")
            val direct = context.hasPermission(Manifest.permission.CALL_PHONE)
            if (!(direct && launch(Intent(Intent.ACTION_CALL, uri)))) launch(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    // ---------------------------------------------------------------- search

    private suspend fun search(req: Request.Search): ActionResult {
        val question = Regex("^(?:who|what|when|where|why|how|which|whose|is|are|was|were|do|does|did|can|could|will|would|should|has|have|tell me|define|meaning of)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(req.query)
        // A popular YouTube channel or video ("markiplier"): you most likely want it on YouTube.
        if ((req.explicit || !question) && req.query.split(" ").size <= 7) {
            YouTubeSearch.popular(req.query)?.let { p ->
                brand = com.brick.assistant.overlay.Brand.YOUTUBE
                val url = if (p.isChannel) "https://www.youtube.com/channel/${p.id}" else "https://www.youtube.com/watch?v=${p.id}"
                val line = if (p.isChannel) "Opening ${p.name}'s channel on YouTube." else "Playing ${p.name} on YouTube."
                return ActionResult(line) {
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")) ||
                        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
        val openBrowser = {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(req.query)}")))
            Unit
        }
        // Someone or something with a page (an actor, a movie, a show, an app, a company...): look it up by kind,
        // with its facts. "what is photosynthesis"-style questions keep the quick answer first.
        val lq = Lookup.understand(req.query)
        if (Lookup.isEntityLookup(req.query, lq) || req.explicit) {
            Lookup.find(lq)?.let { return lookupResult(req, it, openBrowser) }
        }
        if (!req.explicit) {
            // Not asked to search: answer a question directly if possible, never open a web search.
            if (!question) {
                // A command Brick can't carry out ("order a pizza") isn't a topic to look up.
                if (Wording.COMMAND.containsMatchIn(req.query)) return ActionResult("I can't do that yet.", readout = info.search(req.query, null, openBrowser))
                // A bare topic ("the twelve days of christmas"): what it is.
                Lookup.find(lq)?.let { return lookupResult(req, it, openBrowser) }
                SearchService.answer(req.query)?.let { return ActionResult(it.text, readout = info.search(req.query, it, openBrowser)) }
                return ActionResult("I couldn't find a quick answer for that.", readout = info.search(req.query, null, openBrowser))
            }
            val a = SearchService.answer(req.query)
                // No quick answer: maybe it's a page after all ("what's baywatch").
                ?: Lookup.find(lq)?.let { return lookupResult(req, it, openBrowser) }
                ?: return ActionResult("I can't answer that.", readout = info.search(req.query, null, openBrowser))
            return ActionResult(a.text, readout = info.search(req.query, a, openBrowser))
        }
        val answer = SearchService.answer(req.query)
        return if (answer != null) {
            ActionResult("Searching for ${req.query}. ${answer.text}", readout = info.search(req.query, answer, openBrowser))
        } else {
            ActionResult("Searching for ${req.query}. Opening the results in your browser.", after = openBrowser)
        }
    }

    /** A looked-up person, movie, show, platform...: the fact you asked for, or the summary, with its card. */
    private fun lookupResult(req: Request.Search, r: LookupResult, openBrowser: () -> Unit): ActionResult {
        val openPage = { launch(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))); Unit }
        val said = r.answer ?: r.summary
        val line = if (req.explicit) "Here's what I found on ${r.title}. $said" else said
        return ActionResult(line, readout = info.lookup(r, openPage, openBrowser))
    }

    // ---------------------------------------------------------------- ratings, where to watch / buy / listen, credits

    private fun country(): String = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    private suspend fun media(req: Request.MediaAsk): ActionResult {
        val open: (String) -> Unit = { u -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        when (req.kind) {
            MediaAskKind.RATING, MediaAskKind.WATCH, MediaAskKind.BUY_VIDEO -> {
                val title = req.title ?: return ActionResult("Which movie or show?")
                val key = settings.current.tmdbKey
                val v = MediaGuide.video(title, req.video, key, country())
                    ?: return if (req.kind == MediaAskKind.RATING && req.video == null) search(Request.Search(title)) else ActionResult("I couldn't find a movie or show called $title.")
                return when (req.kind) {
                    MediaAskKind.RATING -> ActionResult(MediaGuide.ratingLine(v), readout = MediaCards.rating(v, open))
                    MediaAskKind.WATCH -> ActionResult(MediaGuide.watchLine(v, req.free), readout = MediaCards.watch(v, req.free, key.isBlank(), open))
                    else -> ActionResult(MediaGuide.buyVideoLine(v, req.format, req.rent),
                        readout = MediaCards.buyVideo(v, MediaGuide.videoStores(v, req.format), req.rent, open))
                }
            }
            else -> {
                val playing = if (req.title == null) com.brick.assistant.media.LikeSong.current(context) else null
                val (t, a) = when {
                    req.title != null -> req.title to req.artist
                    playing?.title != null -> MediaGuide.cleanPlaying(playing.title, playing.artist)
                    playing != null -> return ActionResult("${playing.app} isn't sharing the song's name. Tell me the song and I'll look it up.")
                    !Discord.hasAccess() -> return ActionResult("To see what's playing, Brick needs notification access. Turn it on in Settings, under Notification access, or tell me the song.")
                    else -> return ActionResult("Nothing's playing right now. Tell me the song and I'll look it up.")
                }
                val song = MediaGuide.song(t, a)?.copy(playingIn = playing?.app)
                    ?: return ActionResult("I couldn't find the song $t" + (a?.let { " by $it" } ?: "") + ".")
                return when (req.kind) {
                    MediaAskKind.LISTEN -> ActionResult(MediaGuide.listenLine(song, req.free, req.service),
                        readout = MediaCards.listen(song, MediaGuide.listenSpots(song), req.free, open))
                    MediaAskKind.BUY_SONG -> ActionResult(MediaGuide.buySongLine(song), readout = MediaCards.buySong(song, MediaGuide.songStores(song), open))
                    else -> {
                        val basic = req.role in setOf(CreditRole.ALBUM, CreditRole.LABEL, CreditRole.RELEASE, CreditRole.GENRE)
                        val c = if (basic) SongCredits(LinkedHashMap(), null) else MediaGuide.credits(song)
                        ActionResult(MediaGuide.creditsLine(song, c, req.role, req.instrument), readout = MediaCards.credits(song, c, req.role))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- spelling

    private suspend fun spell(req: Request.Spell): ActionResult {
        val r = Spelling.lookup(req.word)
        return ActionResult(Spelling.spoken(r), readout = info.spelling(r))
    }

    // ---------------------------------------------------------------- math

    private fun math(req: Request.Math): ActionResult {
        val r = req.result
        if (r.value.isNaN() || r.value.isInfinite()) return ActionResult("${r.spoken} is undefined.", readout = info.math(r))
        return ActionResult("${r.spoken} equals ${r.formatted}.", readout = info.math(r))
    }

    // ---------------------------------------------------------------- weather

    private suspend fun weather(req: Request.Weather): ActionResult {
        val s = settings.current
        val city = req.city ?: s.homeCity.takeIf { it.isNotBlank() }
        val place = if (city != null) {
            WeatherService.geocode(city) ?: return ActionResult("I could not find a place called $city.")
        } else {
            WeatherService.deviceLocation(context)
                ?: return ActionResult("I need your location for the weather. Grant location access, or set a home city in Settings.")
        }
        if (req.span != WeatherSpan.DAY) return weatherWeek(req, place)
        val data = WeatherSource.fetch(place, s.useFahrenheit, req.tomorrow, s.weatherConfig())
            ?: return ActionResult("The weather service is not responding right now.")
        // Your own location (device or home city, not one you asked about): "hide my location" leaves the place
        // name out, "obscure my location" names a decoy some distance away. The forecast is always yours.
        if (req.city == null) {
            if (req.obscure || s.obscureWeatherLocation) {
                val d = decoy(place.lat, place.lon, decoyMiles(req.obscureMiles, s.obscureWeatherLocation))
                val shown = data.copy(place = d.name ?: "a place far away")
                return ActionResult(WeatherSource.speak(shown, req.aspect), readout = info.weather(shown, shown.place!!))
            }
            if (req.hidePlace || s.hideWeatherPlace) {
                val shown = data.copy(place = null)
                return ActionResult(WeatherSource.speak(shown, req.aspect), readout = info.weather(shown, "location hidden"))
            }
        }
        return ActionResult(WeatherSource.speak(data, req.aspect), readout = info.weather(data, data.place ?: place.name ?: "your location"))
    }

    /** "This week's weather" / "will it rain this weekend": day by day, with the same hide / obscure rules. */
    private suspend fun weatherWeek(req: Request.Weather, place: WeatherService.Place): ActionResult {
        val s = settings.current
        val week = WeatherSource.fetchWeek(place, s.useFahrenheit, s.weatherConfig())
            ?: return ActionResult("The weather service is not responding right now.")
        val shown = when {
            req.city != null -> week
            req.obscure || s.obscureWeatherLocation ->
                week.copy(place = decoy(place.lat, place.lon, decoyMiles(req.obscureMiles, s.obscureWeatherLocation)).name ?: "a place far away")
            req.hidePlace || s.hideWeatherPlace -> week.copy(place = null)
            else -> week
        }
        val where = shown.place ?: if (req.hidePlace || s.hideWeatherPlace) "location hidden" else place.name ?: "your location"
        return ActionResult(WeatherSource.speakWeek(shown, req.span, req.aspect), readout = info.weatherWeek(shown, req.span, where))
    }

    // ---------------------------------------------------------------- music

    private fun mediaKey(code: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playFromSearch(query: String): Boolean {
        val pkg = settings.current.musicPackage
        fun intent() = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (pkg.isNotBlank() && launch(intent().setPackage(pkg))) return true
        if (launch(intent())) return true
        if (query.isNotBlank()) {
            return launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")))
        }
        return false
    }

    // ---------------------------------------------------------------- response pool rules

    /**
     * Response pools for [req], most specific first: the matching rule, its parent rules, then the request
     * type's own pool. David speaks from the first pool that has lines.
     */
    fun responseKeys(req: Request): List<String> {
        val cat = req.category ?: return emptyList()
        fun kindKey(k: MediaKind?, any: Boolean) = if (any) "any" else when (k) {
            MediaKind.SONG, MediaKind.AUTO, null -> "song"
            MediaKind.ALBUM -> "album"
            MediaKind.ARTIST -> "artist"
            MediaKind.PLAYLIST -> "playlist"
            MediaKind.LIKED -> "liked"
        }
        fun effectKey(e: AudioEffect) = when (e) {
            AudioEffect.REVERB -> "reverb"
            AudioEffect.LOW_PASS -> "lpf"
            AudioEffect.HIGH_PASS -> "hpf"
            AudioEffect.EQUALIZER -> "eq"
            AudioEffect.SPATIAL -> "spatial"
        }
        val rule: String? = when (req) {
            is Request.Music -> when (req.command) {
                MusicCommand.PAUSE, MusicCommand.RESUME, MusicCommand.NEXT, MusicCommand.PREVIOUS -> "controls"
                MusicCommand.PLAY_ANY, MusicCommand.PLAY_QUERY -> {
                    val any = req.command == MusicCommand.PLAY_ANY
                    when {
                        req.app == MusicApp.SPOTIFY -> "spotify.play.${kindKey(req.kind, any)}"
                        req.app == MusicApp.DRIVER || musicGoesToDriver() -> "driver.play.${kindKey(req.kind, any)}"
                        settings.current.musicPackage == SPOTIFY_PACKAGE -> "spotify.play.${kindKey(req.kind, any)}"
                        else -> "otherapp"
                    }
                }
            }
            is Request.Effect -> when (req.state) {
                EffectState.ON -> "driver.effect.${effectKey(req.effect)}.on"
                EffectState.OFF -> "driver.effect.${effectKey(req.effect)}.off"
                EffectState.TOGGLE -> "driver.effect.${effectKey(req.effect)}"
            }
            is Request.EffectPreset -> req.effect?.let { "driver.preset.${effectKey(it)}" } ?: "driver.preset"
            is Request.Lyrics -> if (req.download) "driver.lyrics" else "music.lyrics"
            Request.NowPlaying -> "driver.nowplaying"
            Request.LikeSong -> "like"
            is Request.AddToPlaylist -> "playlist.add"
            is Request.Navigate -> if (req.directions && com.brick.assistant.data.Places.find(req.destination) != null) "maps.saved" else {
                val app = when (defaultMapsPackage()) {
                    "com.google.android.apps.maps" -> "google"
                    "com.waze" -> "waze"
                    else -> "other"
                }
                "maps.$app.${if (req.directions) "directions" else "show"}"
            }
            is Request.TimerControl -> "timers." + req.op.name.lowercase()
            is Request.YouTube -> when {
                !req.play -> "youtube.search"
                req.music -> "youtube.music"
                else -> "youtube.play"
            }
            is Request.ReadNotifications -> if (req.email) "read.email" else "read.notifications"
            is Request.ReadCalendar -> "read.calendar"
            is Request.Search -> Lookup.understand(req.query).kind?.let { "search.${it.name.lowercase()}" }
                ?: if (req.explicit) "search.web" else "search.question"
            is Request.Spell -> "search.spell"
            is Request.MediaAsk -> when (req.kind) {
                MediaAskKind.RATING -> "media.rating"
                MediaAskKind.WATCH -> "media.watch"
                MediaAskKind.BUY_VIDEO -> "media.buy"
                MediaAskKind.LISTEN -> "music.listen"
                MediaAskKind.BUY_SONG -> "music.buy"
                MediaAskKind.CREDITS -> "music.credits"
            }
            is Request.Convert -> "math.convert"
            is Request.Math -> "math.calc"
            is Request.Weather -> when {
                req.span == WeatherSpan.WEEK -> "weather.week"
                req.span == WeatherSpan.WEEKEND -> "weather.weekend"
                req.tomorrow -> "weather.tomorrow"
                else -> "weather.today"
            }
            is Request.Stopwatch -> "stopwatch"
            is Request.TellTime -> "device.time"
            is Request.Countdown -> "device.countdown"
            is Request.Device -> when (req.op) {
                DeviceOp.FLASH_ON -> "device.flashlight.on"
                DeviceOp.FLASH_OFF -> "device.flashlight.off"
                DeviceOp.FLASH_TOGGLE -> "device.flashlight"
                DeviceOp.VOLUME_UP, DeviceOp.VOLUME_DOWN, DeviceOp.VOLUME_SET, DeviceOp.VOLUME_MAX, DeviceOp.MUTE, DeviceOp.UNMUTE -> "device.volume"
                DeviceOp.RING_NORMAL, DeviceOp.RING_VIBRATE, DeviceOp.RING_SILENT, DeviceOp.DND_ON, DeviceOp.DND_OFF -> "device.ringer"
                DeviceOp.BATTERY, DeviceOp.BT_BATTERY -> "device.battery"
                DeviceOp.BRIGHT_UP, DeviceOp.BRIGHT_DOWN, DeviceOp.BRIGHT_SET -> "device.brightness"
                DeviceOp.WIFI, DeviceOp.BLUETOOTH -> "device.connections"
            }
            is Request.PhoneSettingReq -> if (req.ask == SettingAsk.WHERE) "device.settings.where" else "device.settings"
            is Request.SmartHome -> "device.smarthome"
            is Request.PlaceHours -> "maps.hours"
            is Request.PhotoSpots -> "maps.photos"
            is Request.Sky -> when {
                req.worldwide -> "sky.darkest.world"
                req.darkest -> "sky.darkest"
                else -> when (req.aspect) {
                    SkyAspect.BORTLE -> "sky.bortle"
                    SkyAspect.STARS -> "sky.stars"
                    SkyAspect.MILKY_WAY -> "sky.milkyway"
                    SkyAspect.REPORT -> "sky.report"
                }
            }
            is Request.FindPlace -> when (req.kind) {
                FindKind.FOOD -> "maps.food"
                FindKind.SHOPPING -> "maps.shopping"
                FindKind.OTHER -> "maps.places"
            }
            else -> null
        }
        val chain = com.brick.assistant.data.ResponseRules.chain(rule).map { it.storeKey }.toMutableList()
        // Effects: after the effect's own rules, try "any effect on/off" before the general effect rules.
        if (req is Request.Effect && req.state != EffectState.TOGGLE) {
            val generic = "rule:driver.effect.${if (req.state == EffectState.ON) "on" else "off"}"
            val at = chain.indexOf("rule:driver.effect").let { if (it < 0) chain.size else it }
            chain.add(at, generic)
        }
        return chain + cat.name
    }

    private fun defaultMapsPackage(): String? = try {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=home")),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- Driver music player

    /**
     * Driver handles music when it's installed, unless the user picked a different music app in Settings.
     * Effects, presets, lyrics and "what's playing" always go to Driver because only Driver has them.
     */
    /**
     * Pause / resume / next / previous. When Driver is the music app, Driver runs the control itself, so it plays
     * its artwork animations (grow, shrink, dissolve). Otherwise, or if Driver isn't running, a media key is sent.
     */
    private fun transport(driverAction: String, keyCode: Int) {
        if (!musicGoesToDriver()) {
            mediaKey(keyCode)
            return
        }
        Thread {
            val reply = DriverBridge.sendBlocking(context, DriverBridge.transport(driverAction))
            if (reply == null || reply.status != DriverBridge.STATUS_OK) mediaKey(keyCode)
        }.start()
    }

    private fun musicGoesToDriver(): Boolean {
        val pkg = settings.current.musicPackage
        return (pkg.isBlank() || pkg == DriverBridge.PACKAGE) && DriverBridge.isInstalled(context)
    }

    /**
     * Sends [command] to Driver and speaks Driver's own reply. If Driver isn't running, opens it with the
     * command (Driver runs it once its library loads) and speaks [openingLine]; a null [openingLine] means
     * the request only makes sense while Driver is running.
     */
    /** One or more Driver effects: each is sent in turn and Driver's replies are read together. */
    private suspend fun effects(req: Request.Effect): ActionResult {
        fun opening(e: AudioEffect) = when (req.state) {
            EffectState.ON -> "Opening Driver and turning on ${e.spoken}."
            EffectState.OFF -> "Opening Driver and turning off ${e.spoken}."
            EffectState.TOGGLE -> "Opening Driver and toggling ${e.spoken}."
        }
        if (req.others.isEmpty()) return driver(DriverBridge.effect(req.effect, req.state), opening(req.effect))
        if (!DriverBridge.isInstalled(context)) return ActionResult("That needs the Driver music player, which is not installed.")
        brand = com.brick.assistant.overlay.Brand.DRIVER
        val lines = ArrayList<String>()
        for (e in req.all) {
            val cmd = DriverBridge.effect(e, req.state)
            val reply = DriverBridge.send(context, cmd) ?: return ActionResult("I could not reach the Driver music player.")
            if (reply.status == DriverBridge.STATUS_APP_NOT_RUNNING) {
                // Driver isn't open: open it with the first effect; ask again for the rest once it's running.
                return ActionResult(opening(e) + " Ask again for the other effects once Driver is open.") { DriverBridge.launch(context, cmd) }
            }
            lines += reply.message.ifBlank { "Done." }
        }
        return ActionResult(lines.joinToString(" "))
    }

    /**
     * Lyrics on Brick's card: Driver's own copy, then a lyric file on the phone, then lrclib.net. For the song
     * playing now they follow along (line or word synced); a named song is shown as a list. Never read aloud.
     */
    private suspend fun showLyrics(req: Request.Lyrics): ActionResult = withContext(Dispatchers.IO) {
        val playing = if (req.song == null) com.brick.assistant.media.LikeSong.current(context) else null
        if (req.song == null && playing == null) {
            return@withContext if (!Discord.hasAccess()) ActionResult("I need notification access to see what's playing. You can turn it on in Settings.")
            else ActionResult("Nothing is playing. Which song do you want the lyrics for?")
        }
        val md = playing?.controller?.metadata
        val title = req.song ?: playing?.title ?: return@withContext ActionResult("I can't tell what song this is.")
        val artist = req.artist ?: playing?.artist
        val album = md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
        val durSec = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
        val doc = (if (playing == null || playing.pkg == DriverBridge.PACKAGE) Lyrics.fromDriver(context, title, artist) else null)
            ?: Lyrics.fromPhone(title, artist)
            ?: Lyrics.fromLrclib(title, artist, album, durSec)
        val who = artist?.let { " by $it" } ?: ""
        if (doc == null) return@withContext ActionResult("I couldn't find lyrics for $title$who.")
        if (doc.instrumental) return@withContext ActionResult("$title$who is an instrumental, so there are no lyrics.")
        val c = playing?.controller
        // The song playing now is still the one these lyrics are for (same title, and artist when both say).
        fun key(s: String?) = s?.let { Lyrics.cleanTitle(it) }?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]+"), "")?.ifEmpty { null }
        val sameSong: () -> Boolean = {
            val now = com.brick.assistant.media.LikeSong.current(context)
            val t = key(now?.title)
            t != null && (t == key(title) || t == key(doc.title)) &&
                (key(Lyrics.mainArtist(now?.artist)).let { a -> a == null || key(Lyrics.mainArtist(artist)) == null || a == key(Lyrics.mainArtist(artist)) })
        }
        val panel = LyricsPanel(
            doc,
            position = {
                c?.playbackState?.let { st ->
                    if (st.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                    } else st.position
                }
            },
            playing = { c?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING },
            seek = c?.let { ctl -> { ms: Long -> ctl.transportControls.seekTo(ms) } },
            toggle = c?.let { ctl -> {
                if (ctl.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) ctl.transportControls.pause() else ctl.transportControls.play()
            } },
            sameSong = sameSong,
            duration = { c?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } },
            app = playing?.app,
        )
        val kind = when (doc.sync) { LyricsSync.WORD -> "word synced"; LyricsSync.LINE -> "line synced"; LyricsSync.PLAIN -> "plain text" }
        val rows = listOf(
            doc.title + (doc.artist?.let { " - $it" } ?: ""),
            Box.kv("LYRICS", "$kind, from ${doc.source.label}"),
        )
        val line = "Here are the lyrics for ${doc.title}" + (doc.artist?.let { " by $it" } ?: "") +
            (if (doc.sync != LyricsSync.PLAIN && c != null) ", following along." else ".")
        val shown = if (c != null) panel else LyricsPanel(doc, { null }, { false }, sameSong = sameSong)
        LyricsHolder.current = shown
        val full = Control.Button("Full screen") {
            if (shown.sameSong()) {
                launch(Intent(context, com.brick.assistant.ui.LyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                // Another song is playing now: these lyrics would be the wrong ones full screen.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "A different song is playing now. Ask Brick for its lyrics.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        ActionResult(line, readout = Readout(Box.build("LYRICS", rows), controls = listOf(full), lyrics = shown))
    }

    private suspend fun driver(command: android.os.Bundle, openingLine: String?): ActionResult {
        if (!DriverBridge.isInstalled(context)) {
            return ActionResult("That needs the Driver music player, which is not installed.")
        }
        val reply = DriverBridge.send(context, command)
            ?: return ActionResult("I could not reach the Driver music player.")
        brand = com.brick.assistant.overlay.Brand.DRIVER
        return driverResult(command, reply, openingLine)
    }

    private fun driverResult(command: android.os.Bundle, reply: DriverBridge.Reply, openingLine: String?): ActionResult =
        when (reply.status) {
            DriverBridge.STATUS_APP_NOT_RUNNING ->
                if (openingLine == null) {
                    ActionResult("Driver is not playing anything right now.")
                } else {
                    ActionResult(openingLine) {
                        if (!DriverBridge.launch(context, command)) {
                            android.widget.Toast.makeText(context, "Could not open Driver", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            else -> ActionResult(reply.message.ifBlank { "Done." })
        }

    /**
     * Plays a named song, album, artist or playlist in Driver; if Driver doesn't have it (or isn't
     * installed), plays it on Spotify instead.
     */
    private suspend fun playInDriverOrSpotify(req: Request.Music): ActionResult {
        val command = DriverBridge.play(req.kind, req.query.orEmpty(), req.artist)
        val opening = "Opening Driver and playing ${describePlay(req)}."
        val hasSpotify = isPackageInstalled(SPOTIFY_PACKAGE)
        if (!DriverBridge.isInstalled(context)) {
            return if (hasSpotify) spotify(req) else ActionResult("That needs the Driver music player, which is not installed.")
        }
        val reply = DriverBridge.send(context, command)
        if (hasSpotify && (reply == null || reply.status == DriverBridge.STATUS_NOT_FOUND)) {
            val r = spotify(req.copy(app = MusicApp.SPOTIFY))
            val lead = if (reply == null) "I could not reach Driver." else "That isn't in your Driver library."
            return ActionResult("$lead ${r.robotLine}", after = r.after)
        }
        if (reply == null) return ActionResult("I could not reach the Driver music player.")
        brand = com.brick.assistant.overlay.Brand.DRIVER
        return driverResult(command, reply, opening)
    }

    /**
     * Music requests (any app: Driver, Spotify, Apple Music, YouTube Music...) get the music readout: what you
     * asked for, the app, and what's playing, with transport buttons and the media volume.
     */
    private fun withMusicReadout(req: Request, r: ActionResult): ActionResult {
        if (r.readout != null || r.display != null) return r
        val action: String = when (req) {
            is Request.Music -> when (req.command) {
                MusicCommand.PLAY_QUERY -> Box.kv("PLAY", describePlay(req))
                MusicCommand.PLAY_ANY -> Box.kv("PLAY", "music")
                MusicCommand.PAUSE -> Box.kv("PAUSE", "")
                MusicCommand.RESUME -> Box.kv("RESUME", "")
                MusicCommand.NEXT -> Box.kv("SKIP", "next track")
                MusicCommand.PREVIOUS -> Box.kv("BACK", "previous track")
            }
            is Request.Effect -> Box.kv("EFFECT", req.all.joinToString(", ") { it.spoken.removePrefix("the ") } + " " + req.state.name.lowercase())
            is Request.EffectPreset -> Box.kv("PRESET", req.preset)
            is Request.Lyrics -> Box.kv("LYRICS", req.song ?: "this song")
            Request.NowPlaying -> Box.kv("NOW", "playing")
            Request.LikeSong -> Box.kv("LIKE", "this song")
            is Request.AddToPlaylist -> Box.kv("ADD TO", req.playlist)
            is Request.YouTube -> if (req.music) Box.kv("PLAY", req.query) else return r
            else -> return r
        }
        val app = when (brand) {
            com.brick.assistant.overlay.Brand.DRIVER -> "Driver"
            com.brick.assistant.overlay.Brand.SPOTIFY -> "Spotify"
            com.brick.assistant.overlay.Brand.YOUTUBE -> if (req is Request.YouTube && req.music) "YouTube Music" else "YouTube"
            else -> if (req is Request.Music && req.command == MusicCommand.PLAY_QUERY && settings.current.musicPackage.isNotBlank()) appLabel(settings.current.musicPackage) else null
        }
        return ActionResult(
            r.robotLine, r.confirm, r.returnToCard, r.navigateTo, r.dictate, r.voice,
            readout = info.music(action, app), after = r.after,
        )
    }

    private fun appLabel(pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun describePlay(req: Request.Music): String {
        val q = req.query.orEmpty()
        val by = req.artist?.let { " by $it" } ?: ""
        return when (req.kind) {
            MediaKind.LIKED -> "your liked songs"
            MediaKind.PLAYLIST -> "your $q playlist"
            MediaKind.ALBUM -> "the album $q$by"
            MediaKind.ARTIST -> "songs by $q"
            MediaKind.SONG, MediaKind.AUTO -> "$q$by"
        }
    }

    private suspend fun music(req: Request.Music): ActionResult = when (req.command) {
        MusicCommand.PAUSE -> {
            transport("pause", KeyEvent.KEYCODE_MEDIA_PAUSE)
            ActionResult("Pausing music.")
        }
        MusicCommand.RESUME -> ActionResult("Resuming music.") { transport("resume", KeyEvent.KEYCODE_MEDIA_PLAY) }
        MusicCommand.NEXT -> ActionResult("Skipping to the next track.") { transport("next", KeyEvent.KEYCODE_MEDIA_NEXT) }
        MusicCommand.PREVIOUS -> ActionResult("Going back to the previous track.") { transport("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        MusicCommand.PLAY_ANY, MusicCommand.PLAY_QUERY ->
            when (req.app) {
                MusicApp.SPOTIFY -> spotify(req)
                MusicApp.DRIVER -> playInDriver(req)
                MusicApp.DEFAULT -> playWithDefaultApp(req)
            }
    }

    private suspend fun playInDriver(req: Request.Music): ActionResult =
        if (req.command == MusicCommand.PLAY_ANY) {
            driver(DriverBridge.play(null, "", null), openingLine = "Opening Driver and playing music.")
        } else {
            playInDriverOrSpotify(req)
        }

    // ---------------------------------------------------------------- Spotify

    /** Work that must outlive the request (starting Spotify after Brick has finished speaking). */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private suspend fun spotify(req: Request.Music): ActionResult {
        brand = com.brick.assistant.overlay.Brand.SPOTIFY
        if (!isPackageInstalled(SPOTIFY_PACKAGE)) return ActionResult("Spotify is not installed.")
        val sp = com.brick.assistant.media.SpotifyPlayer
        if (req.command == MusicCommand.PLAY_ANY) {
            return ActionResult("Playing music on Spotify.") { launchSpotifyFallback(req) }
        }
        val spoken = describePlay(req)
        val q = req.query.orEmpty()
        // Find the exact Spotify item, then open its link: Spotify starts playing it.
        val uri: String? = when (req.kind) {
            MediaKind.LIKED -> sp.LIKED_SONGS
            MediaKind.SONG -> sp.resolve(com.brick.assistant.media.SpotifyPlayer.Kind.TRACK, q, req.artist, context)
            MediaKind.ALBUM -> sp.resolve(com.brick.assistant.media.SpotifyPlayer.Kind.ALBUM, q, req.artist, context)
            MediaKind.ARTIST -> sp.resolve(com.brick.assistant.media.SpotifyPlayer.Kind.ARTIST, q, null, context)
            MediaKind.PLAYLIST -> sp.resolvePlaylist(context, q)
            // Your own playlists come first ("play gym" -> your Gym playlist), then songs, albums and artists.
            MediaKind.AUTO -> (if (req.artist == null) sp.ownPlaylist(context, q, 0.8) else null)
                ?: sp.resolve(com.brick.assistant.media.SpotifyPlayer.Kind.TRACK, q, req.artist, context)
                ?: sp.resolve(com.brick.assistant.media.SpotifyPlayer.Kind.ALBUM, q, req.artist, context)
                ?: sp.resolve(com.brick.assistant.media.SpotifyPlayer.Kind.ARTIST, q, null, context)
        }
        if (uri != null) {
            return ActionResult("Playing $spoken on Spotify.") {
                background.launch { if (!sp.open(context, uri)) launchSpotifyFallback(req) }
            }
        }
        val why = if (req.kind == MediaKind.PLAYLIST && !sp.isConnected(context)) {
            " Connect your Spotify account in Settings so I can find your own playlists."
        } else {
            ""
        }
        return ActionResult("I couldn't find $spoken on Spotify, so I'm opening Spotify's search.$why") { launchSpotifyFallback(req) }
    }

    private fun launchSpotifyFallback(req: Request.Music) {
        android.util.Log.d("BrickSpotify", "fallback: Spotify play-from-search intent")
        run {
            if (!launch(spotifyPlayIntent(req))) {
                // Older Spotify builds: open its search for the same text instead.
                val text = listOfNotNull(req.query?.takeIf { it.isNotBlank() }, req.artist).joinToString(" ")
                if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(text)}")).setPackage(SPOTIFY_PACKAGE))) {
                    android.widget.Toast.makeText(context, "Could not open Spotify", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Android's standard voice-search intent. Spotify starts playback directly from it, and the focus
     * extras tell it whether the user meant a track, artist, album or playlist.
     */
    private fun spotifyPlayIntent(req: Request.Music): Intent {
        val q = req.query.orEmpty()
        val artist = req.artist.orEmpty()
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY_PACKAGE)
        if (req.command == MusicCommand.PLAY_ANY) {
            return i.putExtra(SearchManager.QUERY, "").putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        when (req.kind) {
            MediaKind.SONG -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_TITLE, q)
                .apply { if (artist.isNotEmpty()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist) }
            MediaKind.ALBUM -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ALBUM, q)
                .apply { if (artist.isNotEmpty()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist) }
            MediaKind.ARTIST -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, q)
            MediaKind.PLAYLIST -> {
                @Suppress("DEPRECATION")
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                    .putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, q)
            }
            MediaKind.LIKED -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
            MediaKind.AUTO -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        val searchText = when (req.kind) {
            MediaKind.LIKED -> "Liked Songs"
            else -> listOfNotNull(q.takeIf { it.isNotBlank() }, artist.takeIf { it.isNotBlank() }).joinToString(" ")
        }
        return i.putExtra(SearchManager.QUERY, searchText)
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun playWithDefaultApp(req: Request.Music): ActionResult = when (req.command) {
        MusicCommand.PLAY_ANY ->
            if (musicGoesToDriver()) {
                driver(DriverBridge.play(null, "", null), openingLine = "Opening Driver and playing music.")
            } else {
                ActionResult("Playing music.") {
                    if (settings.current.musicPackage.isBlank()) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) else playFromSearch("")
                }
            }
        MusicCommand.PLAY_QUERY ->
            if (musicGoesToDriver()) {
                playInDriverOrSpotify(req)
            } else {
                val spoken = describePlay(req)
                val searchText = listOfNotNull(req.query?.takeIf { it.isNotBlank() }, req.artist).joinToString(" ")
                ActionResult("Playing $spoken.") { playFromSearch(searchText) }
            }
        else -> ActionResult("Playing music.")
    }
}