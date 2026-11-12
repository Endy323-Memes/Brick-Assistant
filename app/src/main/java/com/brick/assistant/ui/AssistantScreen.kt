package com.brick.assistant.ui

import android.Manifest
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.brick.assistant.AssistantViewModel
import com.brick.assistant.R
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.brain.Speaker
import com.brick.assistant.brain.hasPermission
import com.brick.assistant.overlay.PulseView
import com.brick.assistant.overlay.TermCard

private val examples = listOf(
    "Set a timer for 10 minutes",
    "Remind me to call the vet tomorrow at 9 am",
    "Text Mom saying I'm on my way",
    "What's the weather in Seattle",
    "When does Target close",
    "Show me photography spots in a 30 mile radius",
    "Play classic rock",
    "What's 15 percent of 80",
)

private val banner = """
 ____       _      _
| __ ) _ __(_) ___| | __
|  _ \| '__| |/ __| |/ /
| |_) | |  | | (__|   <
|____/|_|  |_|\___|_|\_\
""".trimIndent()

@Composable
private fun cursorOn(): Boolean {
    val t = rememberInfiniteTransition(label = "cursor")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 1060; 0f at 0; 1f at 530 }), label = "cursor")
    return v > 0.5f
}

@Composable
fun AssistantScreen(vm: AssistantViewModel, requestPermissions: (Array<String>) -> Unit) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.log.size) {
        if (vm.log.isNotEmpty()) listState.animateScrollToItem(vm.log.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        if (vm.log.isEmpty()) {
            // ---- Boot screen: banner, status and example commands you can tap.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(banner, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 15.sp, letterSpacing = 0.sp), color = DavidColor, softWrap = false)
                Spacer(Modifier.height(14.dp))
                TermLine("[  OK  ]", TermBlue, "voices loaded: brick (main), unit (follow-up)")
                TermLine("[  OK  ]", TermBlue, "listening for \"hey brick\" or the mic button")
                TermLine("[ INFO ]", RobotColor, "ready. awaiting your request.")
                Spacer(Modifier.height(18.dp))
                Text("# try:", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                Spacer(Modifier.height(6.dp))
                examples.forEach { ex ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = DavidColor)) { append("$ ") }
                            append(ex.lowercase())
                        },
                        Modifier
                            .fillMaxWidth()
                            .clip(TermShape)
                            .clickable { vm.submit(ex) }
                            .padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextHigh,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                TermButton("clear", TextMid) { vm.clearLog() }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(vm.log) { _, entry -> LogLine(entry) }
            }
        }

        // ---- The listening card: the same terminal card as the floating assistant.
        if (vm.listening || vm.busy) {
            val last = vm.log.lastOrNull()
            val mode = when {
                vm.listening -> PulseView.Mode.LISTENING
                else -> when (last?.speaker) {
                    Speaker.DAVID -> PulseView.Mode.DAVID
                    Speaker.ROBOT -> PulseView.Mode.ROBOT
                    else -> PulseView.Mode.THINKING
                }
            }
            val lastUser = vm.log.lastOrNull { it.speaker == Speaker.USER }?.text
            val main = when (mode) {
                PulseView.Mode.LISTENING -> vm.partial.ifEmpty { "Go ahead\u2026" }
                PulseView.Mode.THINKING -> lastUser.orEmpty()
                else -> last?.text.orEmpty()
            }
            val sub = when (mode) {
                PulseView.Mode.ROBOT -> vm.log.lastOrNull { it.speaker == Speaker.DAVID }?.text ?: lastUser
                PulseView.Mode.DAVID -> lastUser
                else -> null
            }
            AndroidView(
                factory = { TermCard(it) },
                update = { v ->
                    v.setState(mode, main, sub)
                    v.setMicLevel(vm.micLevel)
                    v.setOutputLevel { vm.container.player.outputLevel }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        // ---- Controls: [ ● listen ] and [ x stop ].
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val listening = vm.listening
            val on = cursorOn()
            Row(
                Modifier
                    .height(46.dp)
                    .clip(TermShape)
                    .background(if (listening) TermBlue else Color.Transparent, TermShape)
                    .border(1.dp, TermBlue, TermShape)
                    .clickable {
                        when {
                            listening -> vm.stopListening()
                            !context.hasPermission(Manifest.permission.RECORD_AUDIO) ->
                                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                            else -> vm.startListening()
                        }
                    }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_mic), "Speak", Modifier.size(18.dp), tint = if (listening) Ink else TermBlue)
                Spacer(Modifier.size(10.dp))
                Text(
                    if (listening) "listening" + if (on) "_" else " " else "listen",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (listening) Ink else TermBlue,
                )
            }
            if (vm.busy) TermButton("x stop", TermRed) { vm.stop() }
        }

        // ---- Prompt: "$ " + what you type, with a blinking block cursor.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .glass(TermShape, if (input.isNotBlank()) TermBlue else null)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$ ", style = MaterialTheme.typography.bodyLarge, color = TermBlue, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) {
                    Text(
                        (if (cursorOn()) "█" else " ") + " type a request",
                        color = TextMid,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextHigh),
                    cursorBrush = SolidColor(TermBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.submit(input); input = "" }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val canSend = input.isNotBlank()
            Box(
                Modifier
                    .height(40.dp)
                    .clip(TermShape)
                    .background(if (canSend) TermBlue else Color.Transparent, TermShape)
                    .border(1.dp, if (canSend) TermBlue else Hairline, TermShape)
                    .clickable(enabled = canSend) { vm.submit(input); input = "" }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("⏎ run", style = MaterialTheme.typography.labelLarge, color = if (canSend) Ink else TextMid)
            }
        }
    }
}

@Composable
private fun TermLine(tag: String, tagColor: Color, text: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) { append(tag) }
            append(" ")
            append(text)
        },
        style = MaterialTheme.typography.bodySmall,
        color = TextHigh,
    )
}

/** A small bracketed terminal button: [ label ]. */
@Composable
fun TermButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        "[ $label ]",
        Modifier
            .clip(TermShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

private val PANEL_LABEL = Regex("^[A-Z][A-Z/-]{1,6}(?= |$)")

/**
 * A terminal readout (the night sky panel): the frame dim, the "[ TITLE ]" in Brick's color, row labels in the
 * unit's color and bar fill ("#") in Brick's color. Never wraps, so the box stays square.
 */
@Composable
internal fun PanelLines(text: String) {
    val mid = TextMid; val high = TextHigh; val brick = DavidColor; val unit = RobotColor
    // Built once per text and palette, one style per run of a color (a live readout recomposes twice a second).
    val styled = remember(text, mid, high, brick, unit) {
        buildAnnotatedString {
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append("\n")
                if (line.startsWith("+")) {
                    val t0 = line.indexOf("[ ")
                    val t1 = line.indexOf(" ]")
                    if (t0 >= 0 && t1 > t0) {
                        withStyle(SpanStyle(color = mid)) { append(line.substring(0, t0)) }
                        withStyle(SpanStyle(color = brick, fontWeight = FontWeight.Bold)) { append(line.substring(t0, t1 + 2)) }
                        withStyle(SpanStyle(color = mid)) { append(line.substring(t1 + 2)) }
                    } else withStyle(SpanStyle(color = mid)) { append(line) }
                    return@forEachIndexed
                }
                val body = line.removePrefix("| ").removeSuffix(" |")
                withStyle(SpanStyle(color = mid)) { append("| ") }
                val lab = PANEL_LABEL.find(body)?.value.orEmpty()
                if (lab.isNotEmpty()) withStyle(SpanStyle(color = unit, fontWeight = FontWeight.Bold)) { append(lab) }
                val rest = body.substring(lab.length)
                var inBar = false
                var from = 0
                var run = mid
                rest.forEachIndexed { j, c ->
                    val col = when {
                        c == '[' -> { inBar = true; mid }
                        c == ']' -> { inBar = false; mid }
                        inBar && c == '#' -> brick
                        inBar -> mid
                        else -> high
                    }
                    if (j > from && col != run) { withStyle(SpanStyle(color = run)) { append(rest.substring(from, j)) }; from = j }
                    run = col
                }
                if (from < rest.length) withStyle(SpanStyle(color = run)) { append(rest.substring(from)) }
                withStyle(SpanStyle(color = mid)) { append(" |") }
            }
        }
    }
    Text(
        styled,
        Modifier.padding(vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        softWrap = false,
        maxLines = Int.MAX_VALUE,
    )
}

/** One line of the session: "$ your request", "brick> ...", "unit> ...", "# system note". */
@Composable
private fun LogLine(entry: LogEntry) {
    val style = MaterialTheme.typography.bodyLarge
    if (entry.panel) { ReadoutView(entry.readout ?: com.brick.assistant.brain.Readout(entry.text)); return }
    when (entry.speaker) {
        Speaker.SYSTEM -> Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TermRed)) { append("! ") }
                append(entry.text)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
        )
        Speaker.USER -> Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TermBlue, fontWeight = FontWeight.Bold)) { append("you$ ") }
                withStyle(SpanStyle(color = TextHigh)) { append(entry.text) }
            },
            Modifier.padding(top = 6.dp),
            style = style,
        )
        Speaker.DAVID, Speaker.ROBOT -> {
            val isBrick = entry.speaker == Speaker.DAVID
            val accent = if (isBrick) DavidColor else RobotColor
            Column(Modifier.padding(start = 2.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(if (isBrick) "brick> " else "unit>  ") }
                        withStyle(SpanStyle(color = TextHigh)) { append(entry.text) }
                    },
                    style = style,
                )
                if (isBrick) entry.category?.let {
                    Text("        # ${it.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
        }
    }
}
