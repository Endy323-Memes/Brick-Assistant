package com.brick.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brick.assistant.brain.Control
import com.brick.assistant.brain.Readout
import com.brick.assistant.overlay.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A readout in the log: its picture, the ASCII box, and its live controls (switches, sliders, buttons). The box
 * redraws after a control is used, and every half second while it's [Readout.live].
 */
@Composable
fun ReadoutView(initial: Readout) {
    var r by remember(initial) { mutableStateOf(initial) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(r.live) {
        while (r.live) {
            delay(500)
            r = r.refresh?.invoke() ?: break
        }
    }
    fun changed() {
        tick++
        scope.launch { delay(180); r.refresh?.let { r = it() } }
    }

    Column(Modifier.padding(vertical = 4.dp).widthIn(max = 420.dp)) {
        r.image?.let { url ->
            val bmp by produceState(ImageLoader.cached(url), url) {
                if (value == null) value = withContext(Dispatchers.IO) { ImageLoader.fetch(url) }
            }
            bmp?.let {
                Image(
                    it.asImageBitmap(), null,
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            }
        }
        PanelLines(r.text)
        if (r.controls.isNotEmpty()) {
            @Suppress("UNUSED_EXPRESSION") tick // read so a control change recomposes the values below
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var i = 0
                val list = r.controls
                while (i < list.size) {
                    when (val c = list[i]) {
                        is Control.Button -> {
                            val run = ArrayList<Control.Button>()
                            while (i < list.size && list[i] is Control.Button && run.size < 3) { run += list[i] as Control.Button; i++ }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (b in run) TermButton(b.label, Modifier.weight(1f)) { b.run(); changed() }
                            }
                            continue
                        }
                        is Control.Toggle -> {
                            val on = remember(c, tick, r) { c.get() }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextHigh)
                                Switch(
                                    on, { v -> c.set(v); changed() },
                                    colors = SwitchDefaults.colors(checkedTrackColor = DavidColor, checkedThumbColor = TextHigh),
                                )
                            }
                        }
                        is Control.Slider -> {
                            var dragging by remember(c) { mutableStateOf(false) }
                            var v by remember(c) { mutableFloatStateOf(c.get()) }
                            if (!dragging) v = remember(c, tick, r) { c.get() }.coerceIn(c.range.start, c.range.endInclusive)
                            Column {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextHigh)
                                    Text(c.format(v), style = MaterialTheme.typography.bodySmall, color = RobotColor)
                                }
                                Slider(
                                    value = v,
                                    onValueChange = { x -> dragging = true; v = x; c.set(x) },
                                    onValueChangeFinished = { dragging = false; changed() },
                                    valueRange = c.range,
                                    steps = c.steps,
                                    colors = SliderDefaults.colors(thumbColor = DavidColor, activeTrackColor = DavidColor, inactiveTrackColor = TextMid),
                                )
                            }
                        }
                    }
                    i++
                }
            }
        }
    }
}

@Composable
private fun TermButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        "[ $label ]",
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(DavidColor.copy(alpha = 0.07f))
            .border(1.dp, DavidColor.copy(alpha = 0.47f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        color = DavidColor,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
    )
}
