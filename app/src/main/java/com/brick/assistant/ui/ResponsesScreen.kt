package com.brick.assistant.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.brick.assistant.AssistantViewModel
import com.brick.assistant.data.Category
import com.brick.assistant.data.ResponseRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsesScreen(vm: AssistantViewModel) {
    val pools by vm.responses.pools.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Category?>(null) }
    var ruleKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var ruleExpanded by remember { mutableStateOf(false) }
    var newText by rememberSaveable { mutableStateOf("") }

    // ---- Export / import every pool as a JSON file.
    val context = androidx.compose.ui.platform.LocalContext.current
    var transferStatus by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferStatus = try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(vm.responses.exportJson().toByteArray(Charsets.UTF_8)) }
            val total = vm.responses.pools.value.values.sumOf { it.size }
            "exported ${vm.responses.pools.value.size} pools ($total lines)."
        } catch (e: Exception) {
            "export failed: ${e.message}"
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            pendingImport = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            transferStatus = "import failed: ${e.message}"
        }
    }
    pendingImport?.let { json ->
        val count = try { org.json.JSONObject(json).optJSONObject("pools")?.length() } catch (_: Exception) { null }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("import response pools?") },
            text = {
                Text(
                    if (count == null) "This file doesn't look like a Brick response pool export."
                    else "The file has $count pools. Each one replaces the matching pool in Brick; pools not in the file stay as they are.",
                )
            },
            confirmButton = {
                TextButton(enabled = count != null, onClick = {
                    transferStatus = try {
                        val r = vm.responses.importJson(json)
                        "imported ${r.pools} pools (${r.lines} lines)" + if (r.skipped > 0) ", skipped ${r.skipped} unknown." else "."
                    } catch (e: IllegalArgumentException) {
                        "import failed: ${e.message}"
                    }
                    pendingImport = null
                }) { Text("[ import ]") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("[ cancel ]") } },
        )
    }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val cat = selected
    val rule = ruleKey?.let { ResponseRules.get(it) }?.takeIf { it.category == cat }
    val poolKey = rule?.storeKey ?: cat?.name
    val poolName = rule?.label ?: cat?.label.orEmpty()
    // Where David falls back to when this pool is empty: parent rules, then the request type's pool.
    val fallbackKeys = if (rule == null) emptyList() else ResponseRules.chain(rule.parent).map { it.storeKey } + cat!!.name
    fun nameOf(key: String) = ResponseRules.all.firstOrNull { it.storeKey == key }?.label
        ?: Category.entries.firstOrNull { it.name == key }?.let { "all ${it.label}" } ?: key

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Build Brick's response pools. When you make a request, Brick says a random line from its pool, " +
                    "then the robot unit follows with the result. Rules give specific requests their own lines, " +
                    "for example a different line when Driver turns reverb on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermButton("export", TermBlue) {
                    exporter.launch("brick-response-pools-${java.time.LocalDate.now()}.json")
                }
                TermButton("import", RobotColor) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            }
            transferStatus?.let { Text("# $it", style = MaterialTheme.typography.bodySmall, color = TextMid) }
        }

        // ---- Request type dropdown (required before anything can be added)
        item {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Request type") },
                    placeholder = { Text("Choose a request type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    Category.entries.forEach { c ->
                        DropdownMenuItem(
                            text = { Text("${c.label}  (${pools[c.name]?.size ?: 0})") },
                            onClick = {
                                selected = c
                                ruleKey = null
                                expanded = false
                                newText = ""
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        }

        // ---- Rule dropdown (optional): different responses per app / per kind of request
        val rules = cat?.let { ResponseRules.forCategory(it) }.orEmpty()
        if (cat != null && rules.isNotEmpty()) {
            item {
                ExposedDropdownMenuBox(expanded = ruleExpanded, onExpandedChange = { ruleExpanded = it }) {
                    OutlinedTextField(
                        value = rule?.label ?: "All ${cat.label} requests",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Rule") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ruleExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = ruleExpanded, onDismissRequest = { ruleExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("All ${cat.label} requests  (${pools[cat.name]?.size ?: 0})") },
                            onClick = { ruleKey = null; ruleExpanded = false; newText = "" },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                        rules.forEach { r ->
                            val n = pools[r.storeKey]?.size ?: 0
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${"   ".repeat(r.depth)}${r.label.substringAfterLast("› ")}  ($n)",
                                        color = if (n > 0) DavidColor else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = { ruleKey = r.key; ruleExpanded = false; newText = "" },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }
        }

        if (cat == null || poolKey == null) {
            item {
                GlassCard() {
                    Text(
                        "Pick a request type from the dropdown above to view and create Brick's responses for it.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val list = pools[poolKey].orEmpty()

            if (rule != null) {
                item {
                    GlassCard() {
                        Column(Modifier.padding(16.dp)) {
                            Text("Rule: ${rule.label}", style = MaterialTheme.typography.labelLarge, color = DavidColor)
                            Spacer(Modifier.height(4.dp))
                            val fallback = fallbackKeys.firstOrNull { pools[it].orEmpty().isNotEmpty() }
                            Text(
                                if (list.isNotEmpty()) "Brick uses these lines for this rule (and any narrower rule that has no lines)."
                                else "No lines yet, so Brick uses " + (fallback?.let { "\"${nameOf(it)}\"" } ?: "nothing (only the unit answers)") + ".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- Robot follow-up preview
            item {
                GlassCard() {
                    Column(Modifier.padding(16.dp)) {
                        Text("Robot follow-up (standard)", style = MaterialTheme.typography.labelLarge, color = RobotColor)
                        Spacer(Modifier.height(4.dp))
                        Text("\u201C${cat.robotExample}\u201D", fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { vm.preview(vm.responses.pick(listOf(poolKey) + fallbackKeys), cat.robotExample) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Preview Brick + unit")
                        }
                    }
                }
            }

            // ---- Add new response
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        label = { Text("New Brick response for ${poolName.substringAfterLast("› ")}") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { vm.responses.add(poolKey, newText); newText = "" },
                        enabled = newText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, "Add")
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${list.size} response${if (list.size == 1) "" else "s"} in pool",
                        style = MaterialTheme.typography.labelLarge,
                        color = DavidColor,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Reset")
                    }
                }
            }

            if (list.isEmpty() && rule == null) {
                item {
                    Text(
                        "This pool is empty, so Brick will stay quiet for ${cat.label.lowercase()} unless a rule has lines, and only the unit will answer.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(list) { index, text ->
                GlassCard() {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text, Modifier.weight(1f))
                        IconButton(onClick = { vm.preview(text, null) }) {
                            Icon(Icons.Filled.PlayArrow, "Play with Brick", tint = DavidColor)
                        }
                        IconButton(onClick = { editing = index to text }) {
                            Icon(Icons.Filled.Edit, "Edit")
                        }
                        IconButton(onClick = { vm.responses.remove(poolKey, index) }) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    }
                }
            }
        }
    }

    editing?.let { (index, original) ->
        var value by remember(index, original) { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit response") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (poolKey != null) vm.responses.update(poolKey, index, value)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    if (confirmReset && poolKey != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset ${poolName.substringAfterLast("› ")}?") },
            text = { Text("This replaces these responses with the built-in defaults (empty for most rules).") },
            confirmButton = {
                TextButton(onClick = { vm.responses.resetToDefaults(poolKey); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}