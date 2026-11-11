package com.brick.assistant.brain

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class DeviceOp {
    FLASH_ON, FLASH_OFF, FLASH_TOGGLE,
    VOLUME_UP, VOLUME_DOWN, VOLUME_SET, VOLUME_MAX, MUTE, UNMUTE,
    RING_NORMAL, RING_VIBRATE, RING_SILENT,
    DND_ON, DND_OFF,
    BATTERY, BT_BATTERY,
    BRIGHT_UP, BRIGHT_DOWN, BRIGHT_SET,
    WIFI, BLUETOOTH,
}

// ==================================================================== Bluetooth battery

enum class BtKind(val one: String, val many: String) {
    HEADPHONES("headphones", "headphones"), WATCH("watch", "watches"), SPEAKER("speaker", "speakers"),
    CONTROLLER("controller", "controllers"), OTHER("device", "devices"),
}

data class BtDevice(val name: String, val kind: BtKind, val level: Int)

/** Picks which connected Bluetooth devices a battery question is about, and words the answer. */
object BtBattery {
    private val watchName = Regex("watch|gear s|fitbit|garmin|amazfit|band", RegexOption.IGNORE_CASE)
    private val headphoneName = Regex("buds|pods|headphone|headset|earbud|earphone|wh-|wf-|beats|bose|jabra|sennheiser|soundcore|\\bqc", RegexOption.IGNORE_CASE)
    private val speakerName = Regex("speaker|jbl|soundlink|boom|flip|bravia|soundbar|sonos|echo", RegexOption.IGNORE_CASE)
    private val controllerName = Regex("controller|xbox|dualsense|dualshock|joy-?con|gamepad|8bitdo", RegexOption.IGNORE_CASE)

    /** The kind from the name first (it's the most telling), then from the device's Bluetooth class. */
    fun kindOf(name: String, major: Int, deviceClass: Int): BtKind = when {
        watchName.containsMatchIn(name) -> BtKind.WATCH
        headphoneName.containsMatchIn(name) -> BtKind.HEADPHONES
        controllerName.containsMatchIn(name) -> BtKind.CONTROLLER
        speakerName.containsMatchIn(name) -> BtKind.SPEAKER
        major == 0x0700 -> BtKind.WATCH // wearable
        deviceClass == 0x0404 || deviceClass == 0x0408 || deviceClass == 0x0418 -> BtKind.HEADPHONES
        deviceClass == 0x0414 || deviceClass == 0x041C || deviceClass == 0x0428 -> BtKind.SPEAKER
        deviceClass == 0x0504 || deviceClass == 0x0508 -> BtKind.CONTROLLER
        else -> BtKind.OTHER
    }

    /** The kind a spoken word means ("earbuds" -> headphones), or null. */
    fun kindOfWord(w: String): BtKind? = when {
        Patterns.get("^(?:headphones?|earbuds?|ear ?buds?|buds|airpods|headset|earphones?)$").matches(w) -> BtKind.HEADPHONES
        Patterns.get("^watch(?:es)?$").matches(w) -> BtKind.WATCH
        Patterns.get("^speakers?$").matches(w) -> BtKind.SPEAKER
        Patterns.get("^controllers?$").matches(w) -> BtKind.CONTROLLER
        else -> null
    }

    private fun words(s: String) = s.lowercase().split(Patterns.get("[^a-z0-9]+")).filter { it.length >= 2 }

    /** The devices [target] means: a kind, a kind with a brand ("sony headphones"), a name, or all (null). */
    fun matching(target: String?, devices: List<BtDevice>): List<BtDevice> {
        if (target.isNullOrBlank()) return devices
        val ws = words(target)
        val kind = ws.lastOrNull()?.let { kindOfWord(it) } ?: kindOfWord(target.lowercase())
        val nameWords = ws.filter { kindOfWord(it) == null && it !in setOf("my", "the", "bluetooth", "wireless") }
        val byName = if (nameWords.isEmpty()) emptyList() else devices.filter { d ->
            val dn = d.name.lowercase()
            nameWords.all { w -> dn.contains(w) || words(dn).any { AppLauncher.similarity(it, w) >= 0.75 } }
        }
        if (kind == null) return byName
        val byKind = devices.filter { it.kind == kind }
        val both = byName.filter { it.kind == kind }
        return when {
            both.isNotEmpty() -> both
            byName.isNotEmpty() -> byName
            else -> byKind
        }
    }

    /** "all"/"both" -> every candidate; a name or "the first one" -> that one; null when the answer names none. */
    fun pick(answer: String, candidates: List<BtDevice>): List<BtDevice>? {
        val a = answer.lowercase().trim()
        if (Patterns.get("\\b(?:all|both|every|each|everything|all of them|them all)\\b").containsMatchIn(a)) return candidates
        val ord = listOf("first|1st|one", "second|2nd|two", "third|3rd|three", "fourth|4th|four")
        if (Patterns.get("\\b(?:last|final)\\b").containsMatchIn(a)) return listOf(candidates.last())
        ord.forEachIndexed { i, p ->
            if (i < candidates.size && Patterns.get("\\bthe (?:$p)\\b|^(?:$p)(?: one)?$").containsMatchIn(a)) return listOf(candidates[i])
        }
        val aw = words(a).filter { it !in setOf("the", "my", "one", "please", "those", "these", "that", "this") }
        if (aw.isEmpty()) return null
        val scored = candidates.map { d ->
            val dw = words(d.name)
            d to aw.count { w -> dw.any { it == w || it.startsWith(w) || AppLauncher.similarity(it, w) >= 0.75 } }
        }
        val best = scored.maxOf { it.second }
        if (best == 0) return null
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.size == 1) top else null
    }

    private fun one(d: BtDevice): String = when {
        d.level in 0..100 -> "${d.name} is at ${d.level} percent"
        d.kind == BtKind.WATCH -> "${d.name} doesn't share its battery with the phone over Bluetooth"
        else -> "${d.name} doesn't report its battery"
    }

    fun report(devices: List<BtDevice>): String = when (devices.size) {
        0 -> "No Bluetooth devices are connected."
        1 -> one(devices[0]) + "."
        else -> devices.dropLast(1).joinToString(", ") { one(it) } + ", and " + one(devices.last()) + "."
    }

    fun askLine(target: String, found: List<BtDevice>): String {
        val kind = found.map { it.kind }.distinct().singleOrNull()
        val what = if (kind != null && kind != BtKind.OTHER) kind.many else "devices"
        val names = found.dropLast(1).joinToString(", ") { it.name } + " and " + found.last().name
        val count = listOf("", "one", "two", "three", "four", "five").getOrElse(found.size) { found.size.toString() }
        return "You have $count $what connected: $names. Which one, or all?"
    }

    fun noneLine(target: String): String {
        val kind = kindOfWord(words(target).lastOrNull() ?: "")
        return if (kind != null && words(target).size == 1) (if (kind == BtKind.HEADPHONES) "No headphones are connected." else "No ${kind.one} is connected.") else "I can't find your $target among the connected devices."
    }
}

// ==================================================================== Apps

data class AppEntry(val label: String, val pkg: String)

object AppLauncher {
    @Volatile private var cached: List<AppEntry>? = null
    @Volatile private var cachedSeq = -1

    /**
     * The launcher apps. Loading every label is slow, so the list is kept until an app is installed, removed
     * or updated (Android's package change counter moves).
     */
    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val prev = cached
        if (prev != null && cachedSeq >= 0 && android.os.Build.VERSION.SDK_INT >= 26) {
            val changed = runCatching { pm.getChangedPackages(cachedSeq) }.getOrNull()
            if (changed == null) return prev
        }
        val seq = if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { pm.getChangedPackages(0)?.sequenceNumber }.getOrNull() ?: -1 else -1
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(main, 0).map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .also { cached = it; cachedSeq = seq }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun norm(s: String) = s.lowercase(Locale.US).replace(Patterns.get("[^a-z0-9]+"), " ").trim()

    /** Best app for a spoken [name]: exact label, then label starting with it, then containing it, then close spelling. */
    fun find(apps: List<AppEntry>, name: String): AppEntry? {
        val q = norm(name).removePrefix("the ").removeSuffix(" app").trim()
        if (q.isEmpty()) return null
        val qs = q.replace(" ", "")
        // Each label normalized once (it used to be redone for every test below).
        val labels = apps.map { norm(it.label) }
        val squashed = labels.map { it.replace(" ", "") }
        apps.indices.firstOrNull { labels[it] == q || squashed[it] == qs }?.let { return apps[it] }
        apps.indices.filter { labels[it].startsWith(q) }.minByOrNull { apps[it].label.length }?.let { return apps[it] }
        apps.indices.filter { labels[it].contains(q) || squashed[it].contains(qs) }.minByOrNull { apps[it].label.length }?.let { return apps[it] }
        val scored = apps.indices.map { it to similarity(squashed[it], qs) }.maxByOrNull { it.second }
        return scored?.takeIf { it.second >= 0.75 }?.let { apps[it.first] }
    }

    /** 1 - (edit distance / longer length). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    fun launchIntent(context: Context, app: AppEntry): Intent? =
        context.packageManager.getLaunchIntentForPackage(app.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// ==================================================================== Time

object Clock {
    /** A time zone for a spoken city ("tokyo" -> Asia/Tokyo), or null. */
    fun zoneFor(city: String): ZoneId? {
        val key = city.trim().lowercase(Locale.US).replace(" ", "_")
        if (key.isEmpty()) return null
        val aliases = mapOf("new_york_city" to "America/New_York", "nyc" to "America/New_York", "la" to "America/Los_Angeles",
            "san_francisco" to "America/Los_Angeles", "seattle" to "America/Los_Angeles", "boston" to "America/New_York",
            "washington" to "America/New_York", "miami" to "America/New_York", "atlanta" to "America/New_York",
            "dallas" to "America/Chicago", "houston" to "America/Chicago", "london" to "Europe/London", "uk" to "Europe/London",
            "beijing" to "Asia/Shanghai", "china" to "Asia/Shanghai", "japan" to "Asia/Tokyo", "india" to "Asia/Kolkata",
            "mumbai" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "hawaii" to "Pacific/Honolulu", "germany" to "Europe/Berlin",
            "france" to "Europe/Paris", "australia" to "Australia/Sydney", "korea" to "Asia/Seoul", "south_korea" to "Asia/Seoul")
        aliases[key]?.let { return ZoneId.of(it) }
        return ZoneId.getAvailableZoneIds().firstOrNull { it.substringAfterLast('/').equals(key, ignoreCase = true) }?.let { ZoneId.of(it) }
    }

    fun speakTime(now: ZonedDateTime = ZonedDateTime.now(), place: String? = null): String {
        val t = TimeParser.speakTime(now.toLocalTime())
        return if (place == null) "It's $t." else {
            val day = if (now.toLocalDate() != LocalDate.now()) " on ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}" else ""
            "In ${place.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }}, it's $t$day."
        }
    }

    fun speakDate(today: LocalDate = LocalDate.now()): String =
        "Today is ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, " +
            "${today.month.getDisplayName(TextStyle.FULL, Locale.US)} ${today.dayOfMonth}, ${today.year}."
}

// ==================================================================== Device controls

object DeviceControls {
    @Volatile private var torchOn = false
    private var torchWatching = false

    private fun camera(context: Context) = context.getSystemService(CameraManager::class.java)

    private fun flashCamera(cm: CameraManager): String? = cm.cameraIdList.firstOrNull {
        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    /** Keeps track of the flashlight (also when you switch it from quick settings). */
    fun watchTorch(context: Context) {
        if (torchWatching) return
        torchWatching = true
        try {
            camera(context)?.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    fun flashlight(context: Context, on: Boolean?): String {
        val cm = camera(context) ?: return "This phone has no flashlight I can use."
        val id = try { flashCamera(cm) } catch (e: Exception) { null } ?: return "This phone has no flashlight I can use."
        val target = on ?: !torchOn
        return try {
            cm.setTorchMode(id, target)
            torchOn = target
            if (target) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "The flashlight is busy right now (the camera may be in use)."
        }
    }

    fun volume(context: Context, op: DeviceOp, percent: Int?): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "I can't reach the volume controls."
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val flags = AudioManager.FLAG_SHOW_UI
        when (op) {
            DeviceOp.VOLUME_UP -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, if (it == 0) flags else 0) }
            DeviceOp.VOLUME_DOWN -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, if (it == 0) flags else 0) }
            DeviceOp.VOLUME_MAX -> am.setStreamVolume(s, max, flags)
            DeviceOp.VOLUME_SET -> am.setStreamVolume(s, ((percent ?: 50).coerceIn(0, 100) * max / 100.0).roundToInt(), flags)
            DeviceOp.MUTE -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, flags)
            DeviceOp.UNMUTE -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, flags)
            else -> {}
        }
        if (op == DeviceOp.MUTE) return "Media muted."
        val now = (am.getStreamVolume(s) * 100.0 / max).roundToInt()
        return if (op == DeviceOp.UNMUTE) "Media unmuted, at $now percent." else "Media volume is at $now percent."
    }

    /** Ringer mode. Silent and Do Not Disturb need Do Not Disturb access, which you grant once. */
    fun ringer(context: Context, op: DeviceOp): Pair<String, Intent?> {
        val am = context.getSystemService(AudioManager::class.java) ?: return "I can't reach the ringer." to null
        val nm = context.getSystemService(NotificationManager::class.java)
        val policy = nm?.isNotificationPolicyAccessGranted == true
        val needsAccess = op == DeviceOp.RING_SILENT || op == DeviceOp.DND_ON || op == DeviceOp.DND_OFF ||
            (nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        if (needsAccess && !policy) {
            return "I need Do Not Disturb access for that. Turn it on for Brick, then ask again." to
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        return try {
            when (op) {
                DeviceOp.RING_NORMAL -> { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; "Your phone will ring." to null }
                DeviceOp.RING_VIBRATE -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; "Vibrate mode on." to null }
                DeviceOp.RING_SILENT -> { am.ringerMode = AudioManager.RINGER_MODE_SILENT; "Silent mode on." to null }
                DeviceOp.DND_ON -> { nm!!.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); "Do Not Disturb is on." to null }
                DeviceOp.DND_OFF -> { nm!!.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); "Do Not Disturb is off." to null }
                else -> "Okay." to null
            }
        } catch (e: SecurityException) {
            "I need Do Not Disturb access for that. Turn it on for Brick, then ask again." to Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
    }

    /**
     * Connected Bluetooth devices with their kind and battery (-1 when a device doesn't report it), or a line
     * saying why they can't be read.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun bluetoothDevices(context: Context): Pair<List<BtDevice>, String?> {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return emptyList<BtDevice>() to "I need the nearby devices permission to check Bluetooth batteries. Open Brick once to allow it."
        }
        val bm = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val adapter = bm?.adapter ?: return emptyList<BtDevice>() to "Bluetooth isn't available."
        if (!adapter.isEnabled) return emptyList<BtDevice>() to "Bluetooth is off."
        val connected = adapter.bondedDevices.orEmpty().filter { d ->
            try { d.javaClass.getMethod("isConnected").invoke(d) as Boolean } catch (e: Exception) { false }
        }
        return connected.map { d ->
            val name = d.alias ?: d.name ?: "A device"
            val level = try { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int } catch (e: Exception) { -1 }
            val cls = d.bluetoothClass
            BtDevice(name, BtBattery.kindOf(name, cls?.majorDeviceClass ?: -1, cls?.deviceClass ?: -1), level)
        } to null
    }

    fun battery(context: Context): String {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return "I can't read the battery."
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tail = when {
            status == BatteryManager.BATTERY_STATUS_FULL && plugged -> ", full and plugged in."
            status == BatteryManager.BATTERY_STATUS_CHARGING || bm.isCharging -> " and charging."
            plugged -> " and plugged in."
            else -> "."
        }
        return "Your battery is at $pct percent$tail"
    }

    /** Screen brightness. Changing it needs the "Modify system settings" permission, granted once. */
    fun brightness(context: Context, op: DeviceOp, percent: Int?): Pair<String, Intent?> {
        if (!Settings.System.canWrite(context)) {
            return "I need permission to change system settings for that. Allow it for Brick, then ask again." to
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        }
        val cr = context.contentResolver
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = when (op) {
                DeviceOp.BRIGHT_UP -> cur + 50
                DeviceOp.BRIGHT_DOWN -> cur - 50
                else -> ((percent ?: 50).coerceIn(0, 100) * 255 / 100.0).roundToInt()
            }.coerceIn(5, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            "Brightness set to ${(next * 100 / 255.0).roundToInt()} percent." to null
        } catch (e: Exception) {
            "I couldn't change the brightness." to null
        }
    }

    /** Android doesn't let apps switch Wi-Fi or Bluetooth themselves; Brick opens the controls instead. */
    fun connectionPanel(op: DeviceOp): Pair<String, Intent> = when (op) {
        DeviceOp.WIFI -> "Android doesn't let apps switch Wi-Fi, so here's the Wi-Fi panel." to
            Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
        else -> "Android doesn't let apps switch Bluetooth, so here are the Bluetooth settings." to Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }
}
