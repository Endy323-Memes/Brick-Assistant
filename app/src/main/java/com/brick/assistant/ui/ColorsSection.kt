package com.brick.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.brick.assistant.data.Palette
import com.brick.assistant.data.Palettes

/**
 * Settings → Colors: pick a palette preset, fine-tune any color by hex code, and export or import palettes.
 * The palette colors everything: the app, the floating card, the in-app listening card and the intro.
 */
@Composable
fun ColorsSection() {
    val context = LocalContext.current
    val current = Palettes.current
    var status by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Palettes.exportJson().toByteArray(Charsets.UTF_8)) }
            "exported \"${Palettes.current.name}\"."
        } catch (e: Exception) {
            "export failed: ${e.message}"
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            pendingImport = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            status = "import failed: ${e.message}"
        }
    }
    pendingImport?.let { json ->
        val preview = try { Palette.fromJson(org.json.JSONObject(json)) } catch (_: Exception) { null }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("import palette?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preview == null) Text("This file doesn't look like a Brick color palette.")
                    else {
                        Text("\"${preview.name}\" is added to your presets and applied.")
                        Swatches(preview)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = preview != null, onClick = {
                    status = try { "imported \"${Palettes.importJson(json).name}\"." } catch (e: IllegalArgumentException) { "import failed: ${e.message}" }
                    pendingImport = null
                }) { Text("[ import ]") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("[ cancel ]") } },
        )
    }

    GlassCard(Modifier.fillMaxWidth(), accent = TermBlue.copy(alpha = 0.5f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (open) "▾ " else "▸ ", style = MaterialTheme.typography.labelLarge, color = TermBlue)
                    Text("COLORS", style = MaterialTheme.typography.labelLarge, color = TextHigh)
                    Spacer(Modifier.size(10.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(TermBlue.copy(alpha = 0.35f)))
                }
                if (!open) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextMid)
                        Swatches(current)
                    }
                }
            }
            if (!open) return@Column

            // ---- Presets
            Palettes.all.forEach { p ->
                val selected = p.name == current.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(TermShape)
                        .background(if (selected) TermBlue.copy(alpha = 0.12f) else Color.Transparent, TermShape)
                        .border(1.dp, if (selected) TermBlue else Hairline, TermShape)
                        .clickable { Palettes.select(p); status = null }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (selected) "> " else "  ", style = MaterialTheme.typography.bodyMedium, color = TermBlue)
                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (selected) TextHigh else TextMid)
                    Swatches(p)
                }
            }

            // ---- Fine-tune the selected palette
            Text("# edit \"${current.name}\": tap a swatch to pick, or type a hex code", style = MaterialTheme.typography.bodySmall, color = TextMid)
            Palette.FIELDS.forEach { field -> ColorRow(field, current) }
            if (Palettes.builtIn.any { it.name == current.name }) {
                Text("# editing a built-in preset saves your changes as a new preset.", style = MaterialTheme.typography.bodySmall, color = TextMid)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TermButton("export", TermBlue) { exporter.launch("brick-palette-${current.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.json") }
                TermButton("import", DavidColor) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                if (Palettes.custom.any { it.name == current.name }) {
                    TermButton("delete", TermRed) { Palettes.delete(current); status = "deleted the preset." }
                }
            }
            status?.let { Text("# $it", style = MaterialTheme.typography.bodySmall, color = TextMid) }
        }
    }
}

@Composable
private fun Swatches(p: Palette) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(p.brick, p.unit, p.background, p.panel, p.text).forEach { c ->
            Box(Modifier.size(14.dp).clip(TermShape).background(Color(c)).border(1.dp, Hairline, TermShape))
        }
    }
}

/** One color: swatch, label and an editable hex code (applied as soon as it's a valid #RRGGBB). */
@Composable
private fun ColorRow(field: String, palette: Palette) {
    val value = Palette.hex(palette.get(field))
    var text by remember(palette.name, field, value) { mutableStateOf(value) }
    val valid = Palette.parseHex(text) != null
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        TermColorPicker(
            title = Palette.LABELS[field] ?: field,
            initial = palette.get(field),
            onApply = { c -> picking = false; if (c != palette.get(field)) Palettes.edit(field, c) },
            onDismiss = { picking = false },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tap the swatch for the terminal color picker.
        Box(
            Modifier
                .size(28.dp)
                .clip(TermShape)
                .background(Color(palette.get(field)))
                .border(1.dp, TextMid, TermShape)
                .clickable { picking = true },
        )
        Spacer(Modifier.width(10.dp))
        Text(Palette.LABELS[field] ?: field, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = text,
            onValueChange = { v ->
                text = v.take(9)
                Palette.parseHex(text)?.let { c -> if (c != palette.get(field)) Palettes.edit(field, c) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (valid) TextHigh else TermRed),
            cursorBrush = SolidColor(TermBlue),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier
                .width(104.dp)
                .border(1.dp, if (valid) Hairline else TermRed, TermShape)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}
