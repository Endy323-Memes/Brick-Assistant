package com.brick.history.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.history.data.BrickLink
import com.brick.history.data.Chat
import com.brick.history.data.Entry
import com.brick.history.data.Palettes
import com.brick.history.data.Rules
import com.brick.history.data.Settings
import com.brick.history.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

private fun plural(n: Int, one: String, many: String = one + "s") = "%,d %s".format(n, if (n == 1) one else many)

private val banner = """
 _   _ _     _
| | | (_)___| |_ ___  _ __ _   _
| |_| | / __| __/ _ \| '__| | | |
|  _  | \__ \ || (_) | |  | |_| |
|_| |_|_|___/\__\___/|_|   \__, |
                           |___/
""".trimIndent()

/** "weather · tokyo" for a chat: the kind of requests it holds and its main subject. */
private fun Chat.tag(): String = listOfNotNull(label.lowercase().ifBlank { null }, topic.joinToString(" ").ifBlank { null }).joinToString(" · ")

// ------------------------------------------------------------ [1] history

@Composable
fun HistoryScreen(onOpen: (String, String) -> Unit) {
    val version by Store.version.collectAsState()
    val settings by Store.settings.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val chats by produceState<List<Chat>?>(null, version, query) {
        value = withContext(Dispatchers.IO) { Store.chats(false, query) }
    }

    Column(Modifier.fillMaxSize()) {
        val list = chats
        if (list != null && list.isEmpty() && query.isBlank()) {
            // ---- Boot screen, like Brick's.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(banner, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 15.sp, letterSpacing = 0.sp), color = DavidColor, softWrap = false)
                Spacer(Modifier.height(14.dp))
                if (settings.saving) {
                    TermLine("[  OK  ]", TermBlue, "saving brick's requests and answers")
                } else {
                    TermLine("[ WARN ]", TermRed, "saving is off (settings)")
                }
                TermLine("[  OK  ]", TermBlue, "requests on the same topic are grouped into one query")
                TermLine("[ INFO ]", RobotColor, "no queries yet. ask brick something.")
            }
        } else {
            GrepBar(query, "search queries", onQuery = { query = it })
            if (list != null) {
                Text(
                    if (list.isEmpty()) "# nothing matches \"${query.trim()}\""
                    else "# " + plural(list.size, "query", "queries") + if (settings.maxCount > 0) " of ${"%,d".format(settings.maxCount)} kept" else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            // A chat that moved to the top (a new request) shows: the list follows it instead of the old first one.
            val listState = rememberLazyListState()
            LaunchedEffect(list?.firstOrNull()?.id) { listState.scrollToItem(0) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.orEmpty(), key = { it.id }) { c -> ChatLine(c, trash = false) { onOpen(c.id, c.label.lowercase()) } }
            }
        }
        TalkBar()
    }
}

// ------------------------------------------------------------ [2] trash

@Composable
fun TrashScreen(onOpen: (String, String) -> Unit) {
    val version by Store.version.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmEmpty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val chats by produceState<List<Chat>?>(null, version, query) {
        value = withContext(Dispatchers.IO) { Store.chats(true, query) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TermLine("[ INFO ]", RobotColor, "queries here are deleted for good ${Rules.TRASH_DAYS} days after they were moved.")
            TermLine("[ INFO ]", RobotColor, "open one to read it or restore it.")
        }
        val list = chats
        if (!list.isNullOrEmpty() || query.isNotBlank()) GrepBar(query, "search the trash", onQuery = { query = it })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    list == null -> ""
                    list.isEmpty() && query.isNotBlank() -> "# nothing matches \"${query.trim()}\""
                    list.isEmpty() -> "# the trash is empty"
                    else -> "# " + plural(list.size, "query", "queries")
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
            if (!list.isNullOrEmpty() && query.isBlank()) TermButton("empty trash", TermRed) { confirmEmpty = true }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list.orEmpty(), key = { it.id }) { c -> ChatLine(c, trash = true) { onOpen(c.id, c.label.lowercase()) } }
        }
    }

    if (confirmEmpty) {
        val n = chats?.size ?: 0
        ConfirmDialog(
            "Empty the trash?",
            "This deletes ${plural(n, "query", "queries")} for good. They can't be brought back.",
            "delete forever", danger = true,
            onConfirm = { confirmEmpty = false; scope.launch(Dispatchers.IO) { Store.emptyTrash() } },
            onDismiss = { confirmEmpty = false },
        )
    }
}

/**
 * A query in a list: a terminal box (tap it to see the whole history) with "you$ latest request", then
 * "# 9:43 AM · 3 requests · weather · tokyo", and "view >" in its corner.
 */
@Composable
private fun ChatLine(c: Chat, trash: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(TermShape, if (trash) TermRed else TermBlue)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatLineText(c, trash, Modifier.weight(1f))
        Text("view >", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = DavidColor)
    }
}

@Composable
private fun ChatLineText(c: Chat, trash: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TermBlue, fontWeight = FontWeight.Bold)) { append("you$ ") }
                withStyle(SpanStyle(color = TextHigh)) { append(c.title.ifBlank { "(no request)" }) }
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOf(whenText(c.lastUsed), plural(c.requests, "request"), c.tag()).filter { it.isNotBlank() }.joinToString(" · ")
        Text("# $meta", style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (trash && c.trashed != null) {
            val left = Rules.daysLeft(c.trashed, System.currentTimeMillis())
            Text(
                "! " + if (left <= 1) "deleted within a day" else "deleted in $left days",
                style = MaterialTheme.typography.bodySmall,
                color = if (left <= 3) TermRed else DavidColor,
            )
        }
    }
}

// ------------------------------------------------------------ one chat

@Composable
fun ChatScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by Store.version.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val data by produceState<Pair<Chat?, List<Entry>>?>(null, version, id) {
        value = withContext(Dispatchers.IO) { Store.chat(id) to Store.entries(id) }
    }
    val chat = data?.first
    val entries = data?.second.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.size) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TermButton("< back", TextMid, onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (chat != null) {
                if (chat.trashed == null) {
                    TermButton("move to trash", TermRed) {
                        scope.launch(Dispatchers.IO) { Store.trash(listOf(id)) }
                        toast(ctx, "Moved to the trash. It stays there ${Rules.TRASH_DAYS} days.")
                        onBack()
                    }
                } else {
                    TermButton("restore") {
                        scope.launch {
                            val moved = withContext(Dispatchers.IO) { Store.restore(listOf(id)) }
                            toast(ctx, if (moved > 0) "Restored. " + plural(moved, "older query", "older queries") + " moved to the trash to stay under your limit." else "Restored.")
                        }
                    }
                    TermButton("delete", TermRed) { confirmDelete = true }
                }
            }
        }
        if (data != null && chat == null) {
            Text("! this query was deleted", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = TextMid)
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat != null) item {
                Text(
                    "# " + listOf(chat.tag(), plural(chat.requests, "request"), "since " + whenText(chat.started)).filter { it.isNotBlank() }.joinToString(" · ") +
                        (chat.trashed?.let { "\n! in the trash, deleted in ${Rules.daysLeft(it, System.currentTimeMillis())} days" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (chat.trashed != null) TermRed else TextMid,
                )
            }
            items(entries) { e -> LogLine(e) }
        }
        TalkBar()
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete this query for good?",
            "It can't be brought back.",
            "delete forever", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch(Dispatchers.IO) { Store.deleteForever(listOf(id)) }
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** One line of a chat, drawn like Brick's log: "you$ ...", "brick> ..." with its "# category", "unit> ...", a readout. */
@Composable
private fun LogLine(e: Entry) {
    val ctx = LocalContext.current
    val style = MaterialTheme.typography.bodyLarge
    when (e.speaker) {
        "you" -> Column(Modifier.padding(top = 6.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TermBlue, fontWeight = FontWeight.Bold)) { append("you$ ") }
                    withStyle(SpanStyle(color = TextHigh)) { append(e.text) }
                },
                style = style,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("     # " + whenText(e.time), style = MaterialTheme.typography.bodySmall, color = TextMid)
                Spacer(Modifier.weight(1f))
                TermButton("↻ ask again", DavidColor) { BrickLink.ask(ctx, e.text)?.let { toast(ctx, it) } }
            }
        }
        "box" -> Column(Modifier.padding(vertical = 4.dp)) {
            e.image?.let { RemoteImage(it, Modifier.padding(bottom = 6.dp)) }
            PanelLines(e.text, Modifier.horizontalScroll(rememberScrollState()))
        }
        else -> {
            val isBrick = e.speaker == "brick"
            val accent = if (isBrick) DavidColor else RobotColor
            Column(Modifier.padding(start = 2.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(if (isBrick) "brick> " else "unit>  ") }
                        withStyle(SpanStyle(color = TextHigh)) { append(e.text) }
                    },
                    style = style,
                )
                if (isBrick) e.category?.let {
                    Text("        # ${it.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
        }
    }
}

// ------------------------------------------------------------ [3] settings

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    val version by Store.version.collectAsState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    /** A rule change that would move chats to the trash, waiting for a yes. */
    var pending by remember { mutableStateOf<Pair<Settings, Int>?>(null) }
    val counts by produceState(0 to 0, version) {
        value = withContext(Dispatchers.IO) { Store.chats(false).size to Store.chats(true).size }
    }

    fun apply(next: Settings) {
        scope.launch {
            val n = withContext(Dispatchers.IO) { Store.preview(next) }
            if (n > 0) pending = next to n else Store.update { next }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Saving", TermBlue, (if (s.saving) "on" else "off") + " · ${plural(counts.first, "query", "queries")}, ${counts.second} in the trash") {
            SwitchRow("Save new requests", s.saving, "Brick sends each request and answer here, filed by topic. Off: nothing new is saved (saved queries stay).") { v ->
                Store.update { it.copy(saving = v) }
            }
            Hint("${plural(counts.first, "query", "queries")} saved, ${counts.second} in the trash. To leave one request out, tap \"Don't save\" on its infographic in Brick.")
        }

        Section(
            "Cleanup rules",
            DavidColor,
            "trash after: ${s.age.label.lowercase()} · keep at most: ${Rules.countLabel(s.maxCount).lowercase()}",
        ) {
            Hint("Both rules work together. A query moves to the trash when either one says so; the trash keeps it ${Rules.TRASH_DAYS} more days.")
            ChoiceRow("Move to trash after", s.age.label, "Counted from when the query was last used or restored.") { picker = "age" }
            ChoiceRow("Keep at most", Rules.countLabel(s.maxCount), "When there are more, the least recently used go to the trash.") { picker = "count" }
        }

        val brick = Palettes.brick
        Section("Colors", RobotColor, if (s.followBrick) "following Brick" + (brick?.let { " (${it.name})" } ?: "") else Palettes.current.name) {
            SwitchRow(
                "Follow Brick's colors",
                s.followBrick,
                if (brick != null) "Brick is using ${brick.name}. Changes there show here right away." else "Brick can't be reached, so History uses its own pick.",
            ) { v -> Store.update { it.copy(followBrick = v) } }
            Hint(if (s.followBrick) "Or pick one of Brick's palettes (this turns following off):" else "Brick's palettes:")
            Palettes.choices.forEach { p ->
                val on = Palettes.current.name == p.name && (!s.followBrick || brick?.name == p.name)
                Row(
                    Modifier.fillMaxWidth().clip(TermShape).clickable { Store.update { it.copy(followBrick = false, palette = p.name) } }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (on) "(*)" else "( )", style = MaterialTheme.typography.bodyMedium, color = if (on) TermBlue else TextMid)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(p.brick, p.unit, p.background, p.text).forEach { c ->
                            Box(Modifier.size(14.dp).background(Color(c), TermShape).border(1.dp, Hairline, TermShape))
                        }
                    }
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
                }
            }
        }

        Section("Brick", TermBlue, if (BrickLink.installed(ctx)) "talk, type or ask again" else "Brick isn't installed") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TermButton("🎙 talk to brick", DavidColor) { BrickLink.open(ctx, voice = true)?.let { toast(ctx, it) } }
                TermButton("⌨ type to brick") { BrickLink.open(ctx, voice = false)?.let { toast(ctx, it) } }
            }
            Hint("Brick's own settings keep web answers (prices, news, lookups) for quick repeats. History keeps the requests.")
        }
        Spacer(Modifier.height(24.dp))
    }

    when (picker) {
        "age" -> ChoiceDialog(
            "Move queries to trash after",
            Rules.AgeRule.entries.map { it.label },
            s.age.ordinal,
            onPick = { i -> picker = null; apply(s.copy(age = Rules.AgeRule.entries[i])) },
            onDismiss = { picker = null },
        )
        "count" -> {
            val presets = Rules.COUNT_PRESETS
            val idx = presets.indexOf(s.maxCount).let { if (it < 0) presets.size else it }
            val custom = if (idx == presets.size) "Custom (${"%,d".format(s.maxCount)})" else "Custom..."
            ChoiceDialog(
                "Keep at most",
                presets.map { Rules.countLabel(it) } + custom,
                idx,
                onPick = { i ->
                    picker = null
                    if (i == presets.size) customOpen = true else apply(s.copy(maxCount = presets[i]))
                },
                onDismiss = { picker = null },
            )
        }
    }

    if (customOpen) CustomCountDialog(
        initial = if (s.maxCount > 0 && s.maxCount !in Rules.COUNT_PRESETS) s.maxCount.toString() else "",
        onDone = { n -> customOpen = false; apply(s.copy(maxCount = n)) },
        onDismiss = { customOpen = false },
    )

    pending?.let { (next, n) ->
        ConfirmDialog(
            "Move queries to the trash?",
            "This moves ${plural(n, "query", "queries")} to the trash now. They stay there ${Rules.TRASH_DAYS} days, and you can restore them.",
            "move them",
            onConfirm = { pending = null; Store.update { next } },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun CustomCountDialog(initial: String, onDone: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val n = Rules.parseCount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = TermShape,
        title = { Text("Keep at most", style = MaterialTheme.typography.titleMedium, color = DavidColor) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. 60 or 1.2k", color = TextMid) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = TermShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermBlue, unfocusedBorderColor = Hairline,
                        focusedTextColor = TextHigh, unfocusedTextColor = TextHigh, cursorColor = TermBlue,
                    ),
                )
                Hint(
                    when {
                        text.isBlank() -> "Any number from ${Rules.CUSTOM_MIN} to ${"%,d".format(Rules.CUSTOM_MAX)}."
                        n == null -> "Pick a number from ${Rules.CUSTOM_MIN} to ${"%,d".format(Rules.CUSTOM_MAX)}."
                        else -> "Keeps the ${Rules.countLabel(n).lowercase()} you used last."
                    },
                    if (text.isNotBlank() && n == null) TermRed else TextMid,
                )
            }
        },
        confirmButton = { TextButton(onClick = { n?.let(onDone) }, enabled = n != null) { Text("[ set ]", color = if (n != null) TermBlue else TextMid) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("[ cancel ]", color = TextMid) } },
    )
}
