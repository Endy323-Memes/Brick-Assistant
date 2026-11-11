package com.brick.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.brick.assistant.AssistantViewModel
import com.brick.assistant.brain.hasPermission
import com.brick.assistant.reminders.ReminderScheduler
import com.brick.assistant.voice.RobotFx
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val musicApps = listOf(
    "" to "Default / ask",
    "com.spotify.music" to "Spotify",
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "com.amazon.mp3" to "Amazon Music",
    "com.pandora.android" to "Pandora",
    "com.apple.android.music" to "Apple Music",
)

@Composable
fun SettingsScreen(vm: AssistantViewModel, requestPermissions: (Array<String>) -> Unit) {
    val s by vm.settings.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voices by produceState(emptyList<String>()) { value = vm.tts.voiceNames() }
    var serverStatus by remember { mutableStateOf("") }
    var accuStatus by remember { mutableStateOf("") }
    var prerenderProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var cacheCount by remember { mutableIntStateOf(vm.voice.cacheCount()) }
    val update = vm.settings::update
    var showPc by remember { mutableStateOf(false) }
    var showFx by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------ Colors
        ColorsSection()

        // ------------------------------------------------------------ Main voice
        Section("Main voice", DavidColor, if (s.mainVoice == "classic") "Classic assistant" else "Recorded") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.mainVoice == "classic", onClick = { update { it.copy(mainVoice = "classic") } }, label = { Text("Classic assistant") })
                FilterChip(selected = s.mainVoice == "david", onClick = { update { it.copy(mainVoice = "david") } }, label = { Text("Recorded") })
            }
            if (s.mainVoice == "classic") {
                Hint("A natural male voice with a calm, even delivery. Lower Expression for a flatter voice.")
                HearButton("Hear main voice") { vm.preview("Here's what I found. Would you like me to read it to you?", null) }
                VoicePicker("Voice (blank = automatic male)", s.classicVoice, voices) { v -> update { it.copy(classicVoice = v) } }
                LabeledSlider("Speed", s.classicRate, 0.7f..1.5f, format = { "%.2fx".format(it) }) { v -> update { it.copy(classicRate = v) } }
                LabeledSlider("Pitch", s.classicPitch, 0.75f..1.15f, format = { "%.2f".format(it) }) { v -> update { it.copy(classicPitch = v) } }
                LabeledSlider("Expression", s.classicExpression, 0f..1f, format = { "${(it * 100).roundToInt()}%" }) { v ->
                    update { it.copy(classicExpression = v) }
                }
            }
            if (s.mainVoice == "david") {
                Hint(
                    "A recorded desktop voice: ${vm.voice.bundledDavidCount()} built-in responses are recorded, and your own " +
                        "responses are stitched from recorded words.",
                )
                HearButton("Hear Brick") { vm.preview("Hello. I am Brick, your assistant.", null) }
                VoicePicker("Voice for missing words (blank = auto male)", s.davidFallbackVoice, voices) { v ->
                    update { it.copy(davidFallbackVoice = v) }
                }
                LabeledSlider("Custom response speed", s.davidStitchSpeed, 0.8f..1.6f, format = { "%.2fx".format(it) }) { v ->
                    update { it.copy(davidStitchSpeed = v) }
                }
                LabeledSlider("Missing-word voice speed", s.davidFallbackRate, 0.5f..1.5f) { v -> update { it.copy(davidFallbackRate = v) } }
                MoreToggle(showPc, "PC voice server") { showPc = it }
                if (showPc) {
                    Hint("Run tools\\start-david-server.bat on a Windows PC, enter its address, then download all responses once.")
                    OutlinedTextField(
                        value = s.davidServerUrl,
                        onValueChange = { v -> update { it.copy(davidServerUrl = v.trim()) } },
                        label = { Text("Voice server address") },
                        placeholder = { Text("http://192.168.1.20:5150") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.davidToken,
                        onValueChange = { v -> update { it.copy(davidToken = v.trim()) } },
                        label = { Text("Server token (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledSlider("PC voice speaking rate", s.davidRate.toFloat(), -5f..5f, 9, { it.roundToInt().toString() }) { v ->
                        update { it.copy(davidRate = v.roundToInt()) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            serverStatus = "Testing…"
                            scope.launch { serverStatus = vm.voice.testServer() }
                        }) { Text("Test") }
                        OutlinedButton(
                            enabled = prerenderProgress == null && s.davidServerUrl.isNotBlank(),
                            onClick = {
                                val texts = vm.responses.pools.value.values.flatten()
                                prerenderProgress = 0 to texts.size
                                scope.launch {
                                    val ok = vm.voice.prerender(texts) { d, t -> prerenderProgress = d to t }
                                    prerenderProgress = null
                                    cacheCount = vm.voice.cacheCount()
                                    serverStatus = "Cached $ok of ${texts.distinct().size} responses in the recorded voice."
                                }
                            },
                        ) { Text("Download all") }
                        TextButton(onClick = { vm.voice.clearCache(); cacheCount = 0 }) { Text("Clear ($cacheCount)") }
                    }
                    if (serverStatus.isNotEmpty()) Hint(serverStatus)
                    prerenderProgress?.let { (d, t) ->
                        LinearProgressIndicator(progress = { if (t == 0) 0f else d / t.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // ------------------------------------------------------------ Unit voice
        Section("Unit voice", RobotColor, if (s.unitStyle == "natural") "Natural female" else "Robot unit") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.unitStyle == "natural", onClick = { update { it.copy(unitStyle = "natural") } }, label = { Text("Natural female") })
                FilterChip(selected = s.unitStyle == "robot", onClick = { update { it.copy(unitStyle = "robot") } }, label = { Text("Robot unit") })
            }
            if (s.unitStyle == "natural") {
                Hint("A natural Google voice, the same family Google Maps uses. No effects.")
                HearButton("Hear unit voice") { vm.preview(null, "In 500 feet, turn right. Setting timer for 5 minutes.") }
                VoicePicker("Voice (blank = automatic)", s.naturalVoice, voices) { v -> update { it.copy(naturalVoice = v) } }
                LabeledSlider("Speed", s.naturalRate, 0.7f..1.5f, format = { "%.2fx".format(it) }) { v -> update { it.copy(naturalRate = v) } }
            }
            if (s.unitStyle == "robot") {
                Hint("A synthetic tutorial-style voice: a female base voice moved to the unit's pitch and tone.")
                HearButton("Hear robot") { vm.preview(null, "Calibration complete. Setting timer for 5 minutes.") }
                SwitchRow("Robot effects", s.robotFxEnabled) { v -> update { it.copy(robotFxEnabled = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !s.robotUseDavidBase, onClick = { update { it.copy(robotUseDavidBase = false) } }, label = { Text("Android base") })
                    FilterChip(selected = s.robotUseDavidBase, onClick = { update { it.copy(robotUseDavidBase = true) } }, label = { Text("Brick base") })
                }
                if (!s.robotUseDavidBase) {
                    VoicePicker("Base voice (blank = auto female)", s.robotVoice, voices) { v -> update { it.copy(robotVoice = v) } }
                    LabeledSlider("Speed", s.robotRate, 0.5f..1.6f) { v -> update { it.copy(robotRate = v) } }
                }
                LabeledSlider(
                    "Pitch (default ${RobotFx.UNIT_F0.roundToInt()} Hz)", s.robotTargetF0, 100f..320f,
                    format = { "${it.roundToInt()} Hz" },
                ) { v -> update { it.copy(robotTargetF0 = v) } }
                LabeledSlider("Expressiveness", s.robotExpressiveness, 0.5f..3f, format = { "%.1fx".format(it) }) { v ->
                    update { it.copy(robotExpressiveness = v) }
                }
                LabeledSlider("Tone match", s.robotEqStrength, 0f..1f, format = { "${(it * 100).roundToInt()}%" }) { v ->
                    update { it.copy(robotEqStrength = v) }
                }
                MoreToggle(showFx, "Extra effects") { showFx = it }
                if (showFx) {
                    LabeledSlider("Ring modulator frequency", s.ringFreq, 0f..200f, format = { "${it.roundToInt()} Hz" }) { v -> update { it.copy(ringFreq = v) } }
                    LabeledSlider("Ring modulator mix", s.ringMix, 0f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(ringMix = v) } }
                    LabeledSlider("Metal resonance delay", s.combDelayMs, 1f..20f, format = { "%.1f ms".format(it) }) { v -> update { it.copy(combDelayMs = v) } }
                    LabeledSlider("Metal resonance amount", s.combFeedback, 0f..0.9f, format = { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(combFeedback = v) } }
                    LabeledSlider("Bit depth", s.bitDepth.toFloat(), 4f..16f, 11, { "${it.roundToInt()} bit" }) { v -> update { it.copy(bitDepth = v.roundToInt()) } }
                    SwitchRow("Small speaker filter", s.speakerFilter) { v -> update { it.copy(speakerFilter = v) } }
                }
                TextButton(onClick = { update { it.withDefaultRobot() } }) { Text("Reset unit voice") }
            }
        }

        // ------------------------------------------------------------ Hey Brick
        Section("Hey Brick", TermBlue, if (s.wakeWordEnabled) "On" + (if (s.pocketPause) " · pauses in a pocket" else "") else "Off") {
            SwitchRow(
                "\"Hey Brick\" wake word",
                s.wakeWordEnabled,
                "Listens offline, even with the screen off. Uses more battery.",
            ) { v ->
                update { it.copy(wakeWordEnabled = v) }
                if (v) {
                    if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                    } else {
                        com.brick.assistant.wake.WakeWordService.start(context)
                    }
                } else {
                    com.brick.assistant.wake.WakeWordService.stop(context)
                }
            }
            if (s.wakeWordEnabled) {
                var wakeStatus by remember { mutableStateOf(com.brick.assistant.wake.WakeWordService.status) }
                var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
                LaunchedEffect(Unit) {
                    while (true) {
                        wakeStatus = com.brick.assistant.wake.WakeWordService.status
                        canOverlay = Settings.canDrawOverlays(context)
                        kotlinx.coroutines.delay(1000)
                    }
                }
                Text("Status: $wakeStatus", style = MaterialTheme.typography.bodySmall, color = RobotColor)
                if (com.brick.assistant.assist.BrickVoiceInteractionService.active == null && !canOverlay) {
                    Hint("To pop up over other apps when it hears you, allow \"Display over other apps\".")
                    OutlinedButton(onClick = { openOverlaySettings(context) }) { Text("Allow \"Display over other apps\"") }
                }
            }
            SwitchRow("Pause in a pocket", s.pocketPause, "Stops listening while the phone is in a pocket or bag.") { v ->
                update { it.copy(pocketPause = v) }
            }
            SwitchRow("Use Bluetooth headphones' mic", s.headsetMic, "For requests, when headphones with a mic are connected.") { v ->
                update { it.copy(headsetMic = v) }
            }
        }

        // ------------------------------------------------------------ Floating card
        val tapOn = com.brick.assistant.overlay.BrickTapService.isOn
        Section("Floating card", TermBlue, if (s.useOverlay) "On" + (if (s.brandGlow) " · app animations" else "") else "Off (opens Brick)") {
            SwitchRow("Answer over other apps", s.useOverlay, "The assist gesture, side key and Ask Brick tile show a floating card.") { v ->
                update { it.copy(useOverlay = v) }
            }
            SwitchRow("App animations", s.brandGlow, "Edge animation in Spotify, YouTube, Google Maps or Driver colors.") { v ->
                update { it.copy(brandGlow = v) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tap pass-through", style = MaterialTheme.typography.bodyMedium)
                    Hint((if (tapOn) "On. " else "Off. ") + "Taps outside the card reach the app underneath.")
                }
                TermButton(if (tapOn) "settings" else "turn on", if (tapOn) TextMid else TermBlue) { openAccessibilitySettings(context) }
            }
            if (s.useOverlay) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(context, com.brick.assistant.overlay.AssistEntryActivity::class.java)
                            .putExtra(com.brick.assistant.overlay.AssistEntryActivity.EXTRA_SOURCE, "settings test")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Try the floating card") }
            }
        }

        // ------------------------------------------------------------ Weather
        val weatherSummary = (if (s.useFahrenheit) "°F" else "°C") + " · " + s.homeCity.ifBlank { "your location" } +
            " · " + (if (s.accuWeatherKey.isBlank()) "Open-Meteo" else "AccuWeather")
        Section("Weather", TermBlue, weatherSummary) {
            SwitchRow("Fahrenheit", s.useFahrenheit) { v -> update { it.copy(useFahrenheit = v) } }
            OutlinedTextField(
                value = s.homeCity,
                onValueChange = { v -> update { it.copy(homeCity = v) } },
                label = { Text("Home city (blank = your location)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.accuWeatherKey,
                onValueChange = { v -> update { it.copy(accuWeatherKey = v.trim()) }; accuStatus = "" },
                label = { Text("AccuWeather API key (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Without a key, Brick uses Open-Meteo.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(context, "https://developer.accuweather.com/") }) { Text("Get a key") }
                OutlinedButton(enabled = s.accuWeatherKey.isNotBlank(), onClick = {
                    accuStatus = "Testing…"
                    scope.launch {
                        accuStatus = com.brick.assistant.brain.WeatherSource.testAccuWeatherKey(s.accuWeatherKey)
                            ?: "AccuWeather key works."
                    }
                }) { Text("Test key") }
            }
            if (accuStatus.isNotEmpty()) Text(accuStatus, style = MaterialTheme.typography.bodySmall, color = RobotColor)
        }

        // ------------------------------------------------------------ Music
        var notifAccess by remember { mutableStateOf(com.brick.assistant.media.SpotifyPlayer.hasAccess(context)) }
        LifecycleResumeEffect(Unit) {
            notifAccess = com.brick.assistant.media.SpotifyPlayer.hasAccess(context)
            onPauseOrDispose {}
        }
        Section("Music", TermBlue, musicApps.firstOrNull { it.first == s.musicPackage }?.second ?: s.musicPackage) {
            Picker("Music app", s.musicPackage, musicApps) { v -> update { it.copy(musicPackage = v) } }
            SpotifyAccountSection()
        }

        // ------------------------------------------------------------ Setup
        val missing = context.missing(corePermissions())
        var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
        LifecycleResumeEffect(Unit) {
            isDefault = isDefaultAssistant(context)
            onPauseOrDispose {}
        }
        val setupIssues = missing.size + (if (notifAccess) 0 else 1) + (if (isDefault) 0 else 1)
        Section("Setup", TermBlue, if (setupIssues == 0) "All set" else "$setupIssues thing(s) to set up") {
            SetupRow("Permissions", missing.isEmpty(), if (missing.isEmpty()) "All granted" else "${missing.size} not granted", "grant") {
                requestPermissions(corePermissions())
            }
            SetupRow("Notification access", notifAccess, "Starts Spotify songs, controls Clock timers, reads Discord", "allow") {
                openNotificationAccess(context)
            }
            SetupRow("Default digital assistant", isDefault, "For the assist gesture and side key", "open") {
                openDefaultAssistantSettings(context)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ReminderScheduler.canScheduleExact(context)) {
                SetupRow("On-time reminders", false, "Exact alarms for reminders", "allow") {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {}
                }
            }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Android app settings for Brick") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Optional Spotify account connection through the user's own Spotify developer app (Web API, PKCE). */
@Composable
private fun SpotifyAccountSection() {
    val context = LocalContext.current
    val acct = com.brick.assistant.media.SpotifyAccount
    var clientId by remember { mutableStateOf(acct.clientId(context)) }
    var connected by remember { mutableStateOf(acct.isConnected(context)) }
    var name by remember { mutableStateOf(acct.displayName(context)) }
    LifecycleResumeEffect(Unit) {
        connected = acct.isConnected(context)
        name = acct.displayName(context)
        onPauseOrDispose {}
    }
    HorizontalDivider()
    Text("Spotify account (for your playlists)", style = MaterialTheme.typography.labelLarge)
    if (connected) {
        Text(
            "Connected" + (name?.let { " as $it" } ?: "") + ". Brick finds your playlists by name and uses Spotify's " +
                "search for songs, albums and artists.",
            style = MaterialTheme.typography.bodySmall,
            color = RobotColor,
        )
        TextButton(onClick = { acct.disconnect(context); connected = false; name = null }) { Text("Disconnect Spotify") }
    } else {
        Text(
            "1. Open the Spotify developer dashboard and sign in with your Spotify account.\n" +
                "2. Create an app (any name). Under Redirect URIs add exactly: ${acct.REDIRECT_URI}\n" +
                "3. Tick Web API, save, then copy the app's Client ID here.\n" +
                "4. Tap Connect Spotify and approve. Brick only asks to read your playlists and library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open dashboard") }
            OutlinedButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Redirect URI", acct.REDIRECT_URI))
            }) { Text("Copy redirect URI") }
        }
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim(); acct.setClientId(context, clientId) },
            label = { Text("Spotify Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = clientId.length >= 16, onClick = { acct.startConnect(context) }) { Text("Connect Spotify") }
    }
}

private fun isDefaultAssistant(context: android.content.Context): Boolean = try {
    Settings.Secure.getString(context.contentResolver, "assistant").orEmpty().startsWith(context.packageName + "/") ||
        Settings.Secure.getString(context.contentResolver, "voice_interaction_service").orEmpty().startsWith(context.packageName + "/")
} catch (e: Exception) {
    false
}

/**
 * A settings section in the terminal look ("▸ MAIN VOICE ─────"). Closed, it shows only [summary]; tap the header
 * to open it.
 */
@Composable
private fun Section(title: String, accent: Color, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = accent.copy(alpha = 0.5f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (open) "▾ " else "▸ ", style = MaterialTheme.typography.labelLarge, color = accent)
                    Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = TextHigh, letterSpacing = 1.sp)
                    Spacer(Modifier.size(10.dp))
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.35f)))
                }
                if (!open && summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
            if (open) content()
        }
    }
}

/** A short line of help under a setting. */
@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HearButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(label)
    }
}

/** "▸ more: Extra effects" link that shows or hides advanced options. */
@Composable
private fun MoreToggle(shown: Boolean, label: String, onChange: (Boolean) -> Unit) {
    TextButton(onClick = { onChange(!shown) }) { Text((if (shown) "▾ hide " else "▸ show ") + label.lowercase()) }
}

/** One setup item: a check or a cross, what it's for, and a button to fix it. */
@Composable
private fun SetupRow(label: String, done: Boolean, help: String, action: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "[✓] " else "[ ] ", style = MaterialTheme.typography.bodyMedium, color = if (done) RobotColor else TermBlue)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Hint(help)
        }
        if (!done) TermButton(action, TermBlue, onFix)
    }
}

private fun startFirst(context: android.content.Context, vararg intents: Intent?) {
    for (i in intents) {
        if (i == null) continue
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {}
    }
}

private fun openUrl(context: android.content.Context, url: String) = startFirst(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private fun openOverlaySettings(context: android.content.Context) =
    startFirst(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))

private fun openAccessibilitySettings(context: android.content.Context) =
    startFirst(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

private fun openNotificationAccess(context: android.content.Context) = startFirst(
    context,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            android.content.ComponentName(context, com.brick.assistant.media.BrickNotificationListener::class.java).flattenToString(),
        )
    } else {
        null
    },
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
)

private fun openDefaultAssistantSettings(context: android.content.Context) = startFirst(
    context,
    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
)

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = TermBlue,
                activeTrackColor = TermBlue,
                inactiveTrackColor = Hairline,
                activeTickColor = Ink.copy(alpha = 0.6f),
                inactiveTickColor = TextMid.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VoicePicker(label: String, selected: String, voices: List<String>, onSelect: (String) -> Unit) {
    val options = listOf("" to "System default") + voices.map { it to it }
    Picker(label, selected, options, onSelect)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { options.firstOrNull()?.second ?: "" }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
