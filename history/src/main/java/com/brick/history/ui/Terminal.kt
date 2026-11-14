package com.brick.history.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.history.R
import com.brick.history.data.BrickLink

@Composable
fun cursorOn(): Boolean {
    val t = rememberInfiniteTransition(label = "cursor")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 1060; 0f at 0; 1f at 530 }), label = "cursor")
    return v > 0.5f
}

/** A boot-screen line: "[  OK  ] text". */
@Composable
fun TermLine(tag: String, tagColor: Color, text: String) {
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

/**
 * The bottom of Brick's assistant screen: [ ● listen ] (talks to Brick) and the "$ type a request" prompt, whose
 * request runs in Brick's card.
 */
@Composable
fun TalkBar() {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    fun run() {
        val t = input.trim()
        if (t.isEmpty()) return
        BrickLink.ask(ctx, t)?.let { toast(it) } ?: run { input = "" }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .height(46.dp)
                    .clip(TermShape)
                    .border(1.dp, TermBlue, TermShape)
                    .clickable { BrickLink.open(ctx, voice = true)?.let { toast(it) } }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_mic), "Talk to Brick", Modifier.size(18.dp), tint = TermBlue)
                Spacer(Modifier.size(10.dp))
                Text("listen", style = MaterialTheme.typography.labelLarge, color = TermBlue)
            }
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
                    Text((if (cursorOn()) "█" else " ") + " ask brick", color = TextMid, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextHigh),
                    cursorBrush = SolidColor(TermBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run() }),
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
                    .clickable(enabled = canSend) { run() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("⏎ run", style = MaterialTheme.typography.labelLarge, color = if (canSend) Ink else TextMid)
            }
        }
    }
}

/** The search row at the top of a list: "grep " + what you type. */
@Composable
fun GrepBar(query: String, hint: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .glass(TermShape, if (query.isNotBlank()) DavidColor else null)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("grep ", style = MaterialTheme.typography.bodyMedium, color = DavidColor, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, color = TextMid, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextHigh),
                cursorBrush = SolidColor(DavidColor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Text("x", Modifier.clip(TermShape).clickable { onQuery("") }.padding(horizontal = 8.dp), color = TextMid, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Brick's settings section ("▸ CLEANUP RULES ─────"). Closed, it shows only [summary]; tap the header to open it.
 */
@Composable
fun Section(title: String, accent: Color, summary: String? = null, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
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
                    Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.35f)))
                }
                if (!open && summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
            if (open) content()
        }
    }
}

private val PANEL_LABEL = Regex("^[A-Z][A-Z/-]{1,6}(?= |$)")

/**
 * A terminal readout, drawn as Brick draws it: the frame dim, the "[ TITLE ]" in Brick's color, row labels in the
 * unit's color and bar fill ("#") in Brick's color. Never wraps, so the box stays square.
 */
@Composable
fun PanelLines(text: String, modifier: Modifier = Modifier) {
    val mid = TextMid; val high = TextHigh; val brick = DavidColor; val unit = RobotColor
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
                if (!line.startsWith("| ")) { withStyle(SpanStyle(color = high)) { append(line) }; return@forEachIndexed }
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
                if (line.endsWith(" |")) withStyle(SpanStyle(color = mid)) { append(" |") }
            }
        }
    }
    Text(styled, modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, softWrap = false, maxLines = Int.MAX_VALUE)
}
