package com.brick.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brick.assistant.data.Places
import com.brick.assistant.data.SavedPlace

/** Summary for the Places section header. */
@Composable
fun placesSummary(): String {
    val n = Places.all.collectAsState().value.size
    return when (n) { 0 -> "None saved"; 1 -> "1 place"; else -> "$n places" }
}

/**
 * Settings → Places: saved addresses with names you say ("Home", "Work", "Mom's house"), so "take me home" gets
 * directions there. As many as you like; export and import them as a JSON file.
 */
@Composable
fun PlacesContent() {
    val context = LocalContext.current
    val places by Places.all.collectAsState()
    var editing by remember { mutableStateOf<SavedPlace?>(null) }
    var adding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<List<SavedPlace>?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Places.exportJson().toByteArray(Charsets.UTF_8)) }
            "exported ${places.size} place(s)."
        } catch (e: Exception) {
            "export failed: ${e.message}"
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) {
            status = "import failed: ${e.message}"; return@rememberLauncherForActivityResult
        }
        val read = Places.read(text)
        if (read.isNullOrEmpty()) status = "That file doesn't have any Brick places in it." else pendingImport = read
    }

    Text(
        "Save an address under a name, then say \"take me home\", \"directions to work\" or \"show the gym on the map\".",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (places.isEmpty()) {
        Text("$ no places saved yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    places.forEachIndexed { i, p ->
        if (i > 0) HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text(p.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editing = p }) { Text("edit") }
        }
    }
    OutlinedButton(onClick = { adding = true }) { Text("+ add place") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { exporter.launch("brick-places.json") }, enabled = places.isNotEmpty()) { Text("export") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("import") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    if (adding || editing != null) {
        PlaceDialog(
            place = editing,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { p -> { Places.delete(p.id); status = "deleted \"${p.name}\"."; editing = null } },
            onSave = { name, address ->
                val err = Places.save(name, address, editing?.id)
                if (err == null) { status = "saved \"${name.trim()}\"."; adding = false; editing = null }
                err
            },
        )
    }

    pendingImport?.let { list ->
        val names = places.map { Places.key(it.name) }.toSet()
        val replaced = list.count { Places.key(it.name) in names }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("import places?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${list.size} place(s) in this file." + if (replaced > 0) " $replaced already saved by name; their addresses are replaced." else "")
                    list.take(8).forEach { Text("· ${it.name}: ${it.address}", style = MaterialTheme.typography.bodySmall) }
                    if (list.size > 8) Text("· and ${list.size - 8} more", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { status = "imported ${Places.import(list)} place(s)."; pendingImport = null }) { Text("import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("cancel") } },
        )
    }
}

@Composable
private fun PlaceDialog(place: SavedPlace?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (String, String) -> String?) {
    var name by remember { mutableStateOf(place?.name ?: "") }
    var address by remember { mutableStateOf(place?.address ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (place == null) "add place" else "edit place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Name you'll say (Home, Work…)") }, singleLine = true)
                OutlinedTextField(address, { address = it; error = null }, label = { Text("Address") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(name, address) }) { Text("save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("cancel") }
            }
        },
    )
}
