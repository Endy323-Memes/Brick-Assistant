package com.brick.assistant.brain

import android.Manifest
import android.app.SearchManager
import kotlinx.coroutines.launch
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
    val after: (() -> Unit)? = null,
)

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
    /** Battery of just the Bluetooth device you asked about; asks which one when several match. */
    private fun btBattery(target: String?): ActionResult {
        val (devices, problem) = DeviceControls.bluetoothDevices(context)
        if (problem != null) return ActionResult(problem)
        if (devices.isEmpty()) return ActionResult("No Bluetooth devices are connected.")
        val found = BtBattery.matching(target, devices)
        if (found.isEmpty()) return ActionResult(BtBattery.noneLine(target ?: "device"))
        if (found.size == 1 || target == null) return ActionResult(BtBattery.report(found))
        return ActionResult(
            BtBattery.askLine(target, found),
            confirm = Confirmation(
                noLine = "Okay.",
                onYes = { ActionResult(BtBattery.report(found)) },
                onAnswer = { a -> BtBattery.pick(a, found)?.let { ActionResult(BtBattery.report(it)) } },
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

    /** Changes one of Brick's settings by voice, exactly as the Settings screen would. */
    private fun setting(req: Request.Setting): ActionResult {
        val s = settings.current
        val label = req.setting.label
        val was = when (req.setting) {
            BrickSetting.WAKE_WORD -> s.wakeWordEnabled
            BrickSetting.POCKET_PAUSE -> s.pocketPause
            BrickSetting.HEADSET_MIC -> s.headsetMic
            BrickSetting.OVERLAY -> s.useOverlay
            BrickSetting.BRAND_GLOW -> s.brandGlow
            BrickSetting.ROBOT_VOICE -> s.unitStyle == "robot"
            BrickSetting.FAHRENHEIT -> s.useFahrenheit
        }
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

    suspend fun prepare(req: Request): ActionResult = try {
        brand = null
        when (req) {
            is Request.Chat -> ActionResult(req.reply)
            is Request.OpenApp -> openApp(req)
            is Request.TellTime -> tellTime(req)
            is Request.Device -> device(req)
            is Request.Note -> note(req)
            is Request.ReadNotifications -> readNotifications(req)
            is Request.ReadCalendar -> readCalendar(req)
            is Request.YouTube -> youtube(req)
            is Request.Timer -> timer(req)
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
            is Request.Weather -> weather(req)
            is Request.Music -> music(req)
            is Request.Effect -> effects(req)
            is Request.EffectPreset -> driver(
                DriverBridge.preset(req.effect, req.preset),
                openingLine = "Opening Driver and applying the ${req.preset} preset.",
            )
            is Request.Lyrics -> driver(
                DriverBridge.lyrics(req.song),
                openingLine = "Opening Driver to download lyrics" + (req.song?.let { " for $it." } ?: "."),
            )
            Request.NowPlaying -> driver(DriverBridge.nowPlaying(), openingLine = null)
            Request.LikeSong -> ActionResult(com.brick.assistant.media.LikeSong.like(context))
            is Request.AddToPlaylist -> ActionResult(com.brick.assistant.media.LikeSong.addToPlaylist(context, req.playlist))
            is Request.Visualizer -> visualizer(req)
        }
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

    private fun tellTime(req: Request.TellTime): ActionResult {
        if (req.date) return ActionResult(Clock.speakDate())
        val place = req.place
        if (place != null) {
            val zone = Clock.zoneFor(place) ?: return ActionResult("I don't know the time zone for $place.")
            return ActionResult(Clock.speakTime(java.time.ZonedDateTime.now(zone), place))
        }
        return ActionResult(Clock.speakTime())
    }

    private fun device(req: Request.Device): ActionResult = when (req.op) {
        DeviceOp.FLASH_ON -> ActionResult(DeviceControls.flashlight(context, true))
        DeviceOp.FLASH_OFF -> ActionResult(DeviceControls.flashlight(context, false))
        DeviceOp.FLASH_TOGGLE -> ActionResult(DeviceControls.flashlight(context, null))
        DeviceOp.VOLUME_UP, DeviceOp.VOLUME_DOWN, DeviceOp.VOLUME_SET, DeviceOp.VOLUME_MAX, DeviceOp.MUTE, DeviceOp.UNMUTE ->
            ActionResult(DeviceControls.volume(context, req.op, req.percent))
        DeviceOp.RING_NORMAL, DeviceOp.RING_VIBRATE, DeviceOp.RING_SILENT, DeviceOp.DND_ON, DeviceOp.DND_OFF -> {
            val (line, settings) = DeviceControls.ringer(context, req.op)
            if (settings != null) ActionResult(line) { launch(settings) } else ActionResult(line)
        }
        DeviceOp.BATTERY -> ActionResult(DeviceControls.battery(context))
        DeviceOp.BT_BATTERY -> btBattery(req.target)
        DeviceOp.BRIGHT_UP, DeviceOp.BRIGHT_DOWN, DeviceOp.BRIGHT_SET -> {
            val (line, settings) = DeviceControls.brightness(context, req.op, req.percent)
            if (settings != null) ActionResult(line) { launch(settings) } else ActionResult(line)
        }
        DeviceOp.WIFI, DeviceOp.BLUETOOTH -> {
            val (line, panel) = DeviceControls.connectionPanel(req.op)
            ActionResult(line) { launch(panel) }
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
        return ActionResult(NotificationReader.speak(items, req.email, req.from))
    }

    private fun readCalendar(req: Request.ReadCalendar): ActionResult {
        if (isLocked()) return lockedNotice()
        if (!CalendarReader.canRead(context)) return ActionResult("I need permission to read your calendar. You can grant it in Settings.")
        return ActionResult(CalendarReader.speak(CalendarReader.events(context, req.range), req.range))
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
        return if (ok) ActionResult("Setting timer for ${TimeParser.formatDuration(sec)}.")
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
                known.joinToString(" ") { x ->
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
        return ActionResult("Setting alarm for $time$whenText$label.$note")
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
        val dest = req.destination
        if (inCar) {
            // In the car, ask first, then hand the place to the car's navigation app.
            val uri = "geo:0,0?q=${Uri.encode(dest)}"
            return ActionResult(
                "Start directions to $dest? Say yes to go.",
                confirm = Confirmation("Okay, I won't start directions.") { ActionResult("Starting directions to $dest.", navigateTo = uri) },
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
        val line = if (req.directions) "Here's the route to $dest. Press Start when you're ready." else "Showing $dest on the map."
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
        return ActionResult("Reminder set: ${req.task}, $whenText.")
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

        if (CalendarHelper.insert(context, req.title, startMs, endMs, allDay)) return ActionResult(line)

        return ActionResult(line) {
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
        confirm = Confirmation(noLine = "Okay, I won't send it.") { sendConfirmedText(found, msg) },
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
        return ActionResult("Calling ${contact.name}.") {
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
        if (!req.explicit) {
            // Not asked to search: answer a question directly if possible, never open a web search.
            if (!question) return ActionResult("I can't do that yet.")
            return ActionResult(SearchService.quickAnswer(req.query) ?: "I can't answer that.")
        }
        val answer = SearchService.quickAnswer(req.query)
        val openBrowser = {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(req.query)}")))
            Unit
        }
        return if (answer != null) {
            ActionResult("Searching for ${req.query}. $answer")
        } else {
            ActionResult("Searching for ${req.query}. Opening the results in your browser.", after = openBrowser)
        }
    }

    // ---------------------------------------------------------------- math

    private fun math(req: Request.Math): ActionResult {
        val r = req.result
        if (r.value.isNaN() || r.value.isInfinite()) return ActionResult("${r.spoken} is undefined.")
        return ActionResult("${r.spoken} equals ${r.formatted}.")
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
        val data = WeatherSource.fetch(place, s.useFahrenheit, req.tomorrow, s.accuWeatherKey)
            ?: return ActionResult("The weather service is not responding right now.")
        return ActionResult(WeatherSource.speak(data, req.aspect))
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
            is Request.Lyrics -> "driver.lyrics"
            Request.NowPlaying -> "driver.nowplaying"
            Request.LikeSong -> "like"
            is Request.AddToPlaylist -> "playlist.add"
            is Request.Navigate -> {
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
            is Request.Search -> if (req.explicit) "search.web" else "search.question"
            is Request.TellTime -> "device.time"
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
            is Request.PlaceHours -> "maps.hours"
            is Request.PhotoSpots -> "maps.photos"
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