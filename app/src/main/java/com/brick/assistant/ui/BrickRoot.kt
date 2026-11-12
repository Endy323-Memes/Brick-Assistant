package com.brick.assistant.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brick.assistant.AssistantViewModel
import com.brick.assistant.R
import com.brick.assistant.brain.hasPermission

/** Permissions the assistant uses. SEND_SMS is requested separately when auto-send is turned on. */
fun corePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.SEND_SMS) // confirmed texts are sent by Brick
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT) // Bluetooth device battery
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun Context.missing(perms: Array<String>) = perms.filter { !hasPermission(it) }.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrickRoot(vm: AssistantViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true && vm.autoListenRequested) {
            vm.autoListenRequested = false
            vm.startListening()
        }
    }

    LaunchedEffect(Unit) {
        val missing = context.missing(corePermissions())
        if (missing.isNotEmpty()) permLauncher.launch(missing)
    }

    LaunchedEffect(vm.requestedTab) {
        vm.requestedTab?.let { tab = it; vm.requestedTab = null }
    }

    LaunchedEffect(vm.autoListenRequested) {
        if (vm.autoListenRequested && context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            vm.autoListenRequested = false
            tab = 0
            vm.startListening()
        }
    }

    BrickBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // Terminal prompt for the current tab, with a blinking block cursor.
                val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "cursor")
                val on by blink.animateFloat(
                    0f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.keyframes { durationMillis = 1060; 0f at 0; 1f at 530 },
                    ),
                    label = "cursor",
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                ) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = DavidColor, fontWeight = FontWeight.Bold)) { append("brick@android") }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = TextMid)) { append(":") }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = RobotColor)) {
                                append(when (tab) { 0 -> "~/assistant"; 1 -> "~/responses"; else -> "~/settings" })
                            }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = TextHigh)) { append("$ ") }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = if (on > 0.5f) TermBlue else Color.Transparent)) { append("\u2588") }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                }
            },
            bottomBar = {
                // Terminal tabs: [1] assistant  [2] responses  [3] settings
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TermTab(tab == 0, "1", "assistant") { tab = 0 }
                        TermTab(tab == 1, "2", "responses") { tab = 1 }
                        TermTab(tab == 2, "3", "settings") { tab = 2 }
                    }
                }
            },        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                when (tab) {
                    0 -> AssistantScreen(vm, requestPermissions = { permLauncher.launch(it) })
                    1 -> ResponsesScreen(vm)
                    else -> SettingsScreen(vm, requestPermissions = { permLauncher.launch(it) })
                }
            }
        }
    }
}

/** One terminal tab: the selected one is shown inverted (green block, dark text). */
@Composable
private fun RowScope.TermTab(selected: Boolean, key: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(TermShape)
            .background(if (selected) TermBlue else Color.Transparent, TermShape)
            .border(1.dp, if (selected) TermBlue else Hairline, TermShape)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("[$key]", style = MaterialTheme.typography.labelMedium, color = if (selected) Ink else DavidColor.copy(alpha = 0.8f))
        Spacer(Modifier.size(5.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Ink else TextMid, maxLines = 1)
    }
}