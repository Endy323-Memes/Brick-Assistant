package com.brick.history.ui

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.brick.history.data.Palettes
import com.brick.history.data.Store

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        setContent { BrickTheme { HistoryRoot() } }
    }

    override fun onResume() {
        super.onResume()
        Palettes.refreshFromBrick()
        Thread { Store.cleanup() }.apply { isDaemon = true }.start()
    }
}

/** Brick's main app layout: a terminal prompt on top, the screen, and numbered terminal tabs at the bottom. */
@Composable
private fun HistoryRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var openLabel by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = open != null || tab != 0) {
        if (open != null) open = null else tab = 0
    }

    BrickBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                val path = when {
                    open != null -> (if (tab == 1) "~/trash/" else "~/history/") + openLabel
                    tab == 0 -> "~/history"
                    tab == 1 -> "~/trash"
                    else -> "~/settings"
                }
                PromptBar(path)
            },
            bottomBar = {
                // Terminal tabs: [1] history  [2] trash  [3] settings
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TermTab(tab == 0, "1", "history") { tab = 0; open = null }
                        TermTab(tab == 1, "2", "trash") { tab = 1; open = null }
                        TermTab(tab == 2, "3", "settings") { tab = 2; open = null }
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                val id = open
                val onOpen = { chatId: String, label: String -> open = chatId; openLabel = label }
                when {
                    id != null && tab != 2 -> ChatScreen(id, onBack = { open = null })
                    tab == 0 -> HistoryScreen(onOpen)
                    tab == 1 -> TrashScreen(onOpen)
                    else -> SettingsScreen()
                }
            }
        }
    }
}

/** "brick@android:~/history$ █" with a blinking block cursor, over a hairline. */
@Composable
private fun PromptBar(path: String) {
    val blink = rememberInfiniteTransition(label = "cursor")
    val on by blink.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 1060; 0f at 0; 1f at 530 }), label = "cursor")
    Column(
        Modifier
            .fillMaxWidth()
            // Takes the first focus (after a key press the phone isn't in touch mode), so coming back to the app
            // never puts the cursor in a text field and opens the keyboard.
            .focusable()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = DavidColor, fontWeight = FontWeight.Bold)) { append("brick@android") }
                withStyle(SpanStyle(color = TextMid)) { append(":") }
                withStyle(SpanStyle(color = RobotColor)) { append(path) }
                withStyle(SpanStyle(color = TextHigh)) { append("$ ") }
                withStyle(SpanStyle(color = if (on > 0.5f) TermBlue else Color.Transparent)) { append("█") }
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
    }
}

/** One terminal tab: the selected one is shown inverted. */
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
