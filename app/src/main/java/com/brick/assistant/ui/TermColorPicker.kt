package com.brick.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.verticalScroll
import com.brick.assistant.data.Palette
import kotlin.math.roundToInt

/** The 16 classic terminal (ANSI) colors, as quick picks. */
private val ansi = listOf(
    0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
    0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
).map { (0xFF000000 or it.toLong()).toInt() }

/**
 * Colors from the home screen wallpaper: the wallpaper's own main colors, then (Android 12+) the Material You
 * tones the system derives from it. No permission needed.
 */
fun wallpaperSwatches(context: android.content.Context): List<Int> {
    val out = LinkedHashSet<Int>()
    try {
        android.app.WallpaperManager.getInstance(context)
            .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
            ?.let { wc -> listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor).forEach { out += it.toArgb() } }
    } catch (_: Exception) {}
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        val ids = listOf(
            android.R.color.system_accent1_200, android.R.color.system_accent1_400, android.R.color.system_accent1_600,
            android.R.color.system_accent2_200, android.R.color.system_accent2_500,
            android.R.color.system_accent3_200, android.R.color.system_accent3_500,
            android.R.color.system_neutral1_50, android.R.color.system_neutral2_600,
            android.R.color.system_neutral1_800, android.R.color.system_neutral1_900, android.R.color.system_neutral2_900,
        )
        ids.forEach { id -> try { out += context.getColor(id) } catch (_: Exception) {} }
    }
    return out.map { it or 0xFF000000.toInt() }.distinct().take(16)
}

/**
 * Terminal-style color picker: a live preview with its hex code, HSV or RGB channels drawn as block-cell
 * bars (drag or tap a bar, or nudge with [-] / [+]), and the 16 ANSI colors as quick picks.
 */
@Composable
fun TermColorPicker(title: String, initial: Int, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableStateOf(start[0]) }
    var s by remember { mutableStateOf(start[1]) }
    var v by remember { mutableStateOf(start[2]) }
    var rgbMode by remember { mutableStateOf(false) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    fun setArgb(c: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        // Keep the hue when a gray is picked, so the hue bar doesn't jump.
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) h = hsv[0]
        s = hsv[1]
        v = hsv[2]
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(TermShape, TermBlue)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$ ", style = MaterialTheme.typography.labelLarge, color = TermBlue)
                Text("pick-color ", style = MaterialTheme.typography.labelLarge, color = TextHigh)
                Text("--${title.lowercase().replace(' ', '-').replace(Regex("[()]"), "")}", style = MaterialTheme.typography.labelMedium, color = TextMid, maxLines = 1)
            }

            // ---- Preview: old | new, with the hex code
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 44.dp, height = 44.dp).clip(TermShape).background(Color(initial)).border(1.dp, Hairline, TermShape))
                Text("  ->  ", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                Box(Modifier.size(width = 72.dp, height = 44.dp).clip(TermShape).background(Color(argb)).border(1.dp, TextMid, TermShape))
                Spacer(Modifier.width(12.dp))
                Text(Palette.hex(argb), style = MaterialTheme.typography.titleMedium, color = TextHigh, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeTab("hsv", !rgbMode) { rgbMode = false }
                ModeTab("rgb", rgbMode) { rgbMode = true }
            }

            if (!rgbMode) {
                ChannelBar("H", h / 360f, "${h.roundToInt()}°", step = 1f / 72f, spectrum = true,
                    cellColor = { f -> Color(android.graphics.Color.HSVToColor(floatArrayOf(f * 360f, 1f, 1f))) }) { h = (it * 360f).coerceIn(0f, 359.9f) }
                ChannelBar("S", s, "${(s * 100).roundToInt()}%", step = 0.02f,
                    cellColor = { f -> Color(android.graphics.Color.HSVToColor(floatArrayOf(h, f, v.coerceAtLeast(0.35f)))) }) { s = it }
                ChannelBar("V", v, "${(v * 100).roundToInt()}%", step = 0.02f,
                    cellColor = { f -> Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, f))) }) { v = it }
            } else {
                val r = android.graphics.Color.red(argb)
                val g = android.graphics.Color.green(argb)
                val b = android.graphics.Color.blue(argb)
                fun rgb(rr: Int, gg: Int, bb: Int) = android.graphics.Color.rgb(rr.coerceIn(0, 255), gg.coerceIn(0, 255), bb.coerceIn(0, 255))
                ChannelBar("R", r / 255f, "$r", step = 4f / 255f, cellColor = { f -> Color(rgb((f * 255).roundToInt(), g, b)) }) { setArgb(rgb((it * 255).roundToInt(), g, b)) }
                ChannelBar("G", g / 255f, "$g", step = 4f / 255f, cellColor = { f -> Color(rgb(r, (f * 255).roundToInt(), b)) }) { setArgb(rgb(r, (it * 255).roundToInt(), b)) }
                ChannelBar("B", b / 255f, "$b", step = 4f / 255f, cellColor = { f -> Color(rgb(r, g, (f * 255).roundToInt())) }) { setArgb(rgb(r, g, (it * 255).roundToInt())) }
            }

            // ---- From the home screen wallpaper
            val context = androidx.compose.ui.platform.LocalContext.current
            val fromWallpaper = remember { wallpaperSwatches(context) }
            if (fromWallpaper.isNotEmpty()) {
                Text("# wallpaper", style = MaterialTheme.typography.bodySmall, color = TextMid)
                for (row in fromWallpaper.chunked(8)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(22.dp)
                                    .clip(TermShape)
                                    .background(Color(c))
                                    .border(1.dp, if (c == argb) TextHigh else Hairline, TermShape)
                                    .clickable { setArgb(c) },
                            )
                        }
                        repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // ---- ANSI quick picks
            Text("# ansi", style = MaterialTheme.typography.bodySmall, color = TextMid)
            for (row in ansi.chunked(8)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { c ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(22.dp)
                                .clip(TermShape)
                                .background(Color(c))
                                .border(1.dp, if (c == argb) TextHigh else Hairline, TermShape)
                                .clickable { setArgb(c) },
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TermButton("cancel", TextMid) { onDismiss() }
                TermButton("apply", TermBlue) { onApply(argb) }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        "[$label]",
        Modifier
            .clip(TermShape)
            .background(if (selected) TermBlue else Color.Transparent, TermShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Ink else TextMid,
    )
}

/**
 * One channel as a row of block cells: lit up to the value (each cell shows the color at that point), dim
 * after it. Drag or tap the bar to set it; [-] and [+] nudge it.
 */
@Composable
private fun ChannelBar(
    label: String,
    value: Float,
    readout: String,
    step: Float,
    /** Show every cell at full strength (hue): the cursor marks the value instead of a fill. */
    spectrum: Boolean = false,
    cellColor: (Float) -> Color,
    onChange: (Float) -> Unit,
) {
    val cells = 24
    val latest by androidx.compose.runtime.rememberUpdatedState(onChange)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(18.dp), style = MaterialTheme.typography.labelLarge, color = TermBlue)
        Text("[-]", Modifier.clip(TermShape).clickable { onChange((value - step).coerceIn(0f, 1f)) }.padding(4.dp),
            style = MaterialTheme.typography.labelMedium, color = TextMid)
        Canvas(
            Modifier
                .weight(1f)
                .height(26.dp)
                .pointerInput(Unit) { detectTapGestures { o -> latest((o.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ -> latest((change.position.x / size.width).coerceIn(0f, 1f)) }
                },
        ) {
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (cells - 1)) / cells
            val lit = (value * cells).roundToInt()
            for (i in 0 until cells) {
                val f = (i + 0.5f) / cells
                val c = cellColor(f)
                val on = spectrum || i < lit
                drawRect(
                    color = if (on) c else c.copy(alpha = 0.22f),
                    topLeft = Offset(i * (w + gap), if (on) 0f else size.height * 0.35f),
                    size = Size(w, if (on) size.height else size.height * 0.3f),
                )
            }
            // Cursor at the exact value.
            val x = (value * size.width).coerceIn(0f, size.width - 2f)
            drawRect(Color.White, Offset(x, 0f), Size(2.dp.toPx(), size.height))
        }
        Text("[+]", Modifier.clip(TermShape).clickable { onChange((value + step).coerceIn(0f, 1f)) }.padding(4.dp),
            style = MaterialTheme.typography.labelMedium, color = TextMid)
        Text(readout, Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = TextHigh, maxLines = 1)
    }
}
