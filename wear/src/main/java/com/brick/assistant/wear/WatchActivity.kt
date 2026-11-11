package com.brick.assistant.wear

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch

/**
 * Brick on the watch: the same terminal look as the phone's card. It listens (or takes typing), sends the
 * request to Brick on the phone, and shows Brick's and the unit's replies as the phone speaks them.
 */
class WatchActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    enum class Status { IDLE, LISTENING, WORKING, NO_PHONE }

    class Line(val who: String, val text: String)

    private val lines = mutableStateListOf<Line>()
    private var status by mutableStateOf(Status.IDLE)
    /** "confirm" (yes / no buttons) or "dictate" (listen again for a note), or "". */
    private var awaiting by mutableStateOf("")
    private var colors by mutableStateOf(WatchColors())

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said.isNullOrBlank()) {
            if (status == Status.LISTENING) status = Status.IDLE
        } else {
            ask(said)
        }
    }

    private val keyboard = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val typed = r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(TYPED) }?.toString()
        if (typed.isNullOrBlank()) status = Status.IDLE else ask(typed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colors = WatchColors.load(this)
        setContent {
            WatchScreen(lines, status, awaiting, colors, onTalk = ::listen, onType = ::type, onAnswer = ::ask)
        }
        // Opening Brick (or its tile) starts listening right away, like the phone.
        if (savedInstanceState == null) listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listen()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        // Saved colors may be stale (changed while the watch was away): ask the phone for its current ones.
        colors = WatchColors.load(this)
        lifecycleScope.launch { PhoneLink.hello(this@WatchActivity) }
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    private fun listen() {
        status = Status.LISTENING
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (awaiting == "dictate") "Go ahead…" else "Ask Brick")
        // Go straight to Google's speech input when it's there, instead of a "complete action using" chooser
        // (Samsung's keyboard also answers this intent).
        Intent(intent).setPackage(GOOGLE_SPEECH).takeIf { it.resolveActivity(packageManager) != null }?.let { intent.setPackage(GOOGLE_SPEECH) }
        try {
            speech.launch(intent)
        } catch (e: Exception) {
            status = Status.IDLE
            lines += Line("system", "Speech input isn't available. Tap type instead.")
        }
    }

    private fun type() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(TYPED).setLabel("Ask Brick").build()))
        try {
            keyboard.launch(intent)
        } catch (e: Exception) {
            lines += Line("system", "The keyboard isn't available.")
        }
    }

    private fun ask(text: String) {
        lines += Line("you", text)
        trim()
        awaiting = ""
        status = Status.WORKING
        lifecycleScope.launch {
            if (!PhoneLink.ask(this@WatchActivity, text)) {
                status = Status.NO_PHONE
                lines += Line("system", "Can't reach Brick on your phone. Check that they're connected.")
            }
        }
    }

    override fun onMessageReceived(e: MessageEvent) {
        val o = PhoneLink.json(e.data) ?: return
        runOnUiThread {
            when (e.path) {
                PhoneLink.LINE -> {
                    val who = o.optString("who")
                    val text = o.optString("text")
                    // Your own words are already shown.
                    if (who != "you" && text.isNotBlank()) {
                        lines += Line(who, text)
                        trim()
                        if (who == "brick" || who == "unit") {
                            WatchColors.saveLastReply(this, text)
                            TileService.getUpdater(this).requestUpdate(BrickTileService::class.java)
                        }
                    }
                }
                PhoneLink.PALETTE -> o.optJSONObject("palette")?.let { p -> WatchColors.save(this, p); colors = WatchColors.from(p) }
                PhoneLink.DONE -> {
                    o.optJSONObject("palette")?.let { p -> WatchColors.save(this, p); colors = WatchColors.from(p) }
                    awaiting = o.optString("awaiting")
                    status = Status.IDLE
                    // Brick is taking a note: keep listening.
                    if (awaiting == "dictate") listen()
                }
            }
        }
    }

    private fun trim() {
        while (lines.size > 12) lines.removeAt(0)
    }

    private companion object {
        const val TYPED = "typed"
        const val GOOGLE_SPEECH = "com.google.android.tts"
    }
}

@Composable
private fun WatchScreen(
    lines: List<WatchActivity.Line>,
    status: WatchActivity.Status,
    awaiting: String,
    c: WatchColors,
    onTalk: () -> Unit,
    onType: () -> Unit,
    onAnswer: (String) -> Unit,
) {
    val list = rememberScalingLazyListState()
    val bg = Color(c.background)
    val mono = FontFamily.Monospace
    LaunchedEffect(lines.size, status, awaiting) {
        // Keep the newest line and the buttons in view.
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    Scaffold(
        modifier = Modifier.background(bg),
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = list) },
    ) {
        ScalingLazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().background(bg),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(c.brick), fontWeight = FontWeight.Bold)) { append("brick@watch") }
                        withStyle(SpanStyle(color = Color(c.dim))) { append(":") }
                        withStyle(SpanStyle(color = Color(c.unit))) { append("~") }
                        withStyle(SpanStyle(color = Color(c.text))) { append("$") }
                    },
                    fontFamily = mono,
                    fontSize = 13.sp,
                )
            }
            item {
                val (tag, color) = when (status) {
                    WatchActivity.Status.LISTENING -> "[ LISTENING ]" to c.unit
                    WatchActivity.Status.WORKING -> "[ WORKING ]" to c.unit
                    WatchActivity.Status.NO_PHONE -> "[ NO PHONE ]" to c.alert
                    WatchActivity.Status.IDLE -> (if (awaiting == "confirm") "[ YES / NO ]" else "[ READY ]") to c.dim
                }
                Text(tag, color = Color(color), fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (lines.isEmpty()) {
                item {
                    Text("Tap talk or type.", color = Color(c.dim), fontFamily = mono, fontSize = 12.sp)
                }
            }
            items(lines) { l ->
                val (prefix, pc) = when (l.who) {
                    "you" -> "you$ " to c.unit
                    "brick" -> "brick> " to c.brick
                    "unit" -> "unit> " to c.unit
                    else -> "! " to c.alert
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(pc), fontWeight = FontWeight.Bold)) { append(prefix) }
                        withStyle(SpanStyle(color = Color(if (l.who == "you") c.dim else c.text))) { append(l.text) }
                    },
                    fontFamily = mono,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                )
            }
            if (awaiting == "confirm" && status == WatchActivity.Status.IDLE) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TermButton("yes", c.unit, c) { onAnswer("yes") }
                        TermButton("no", c.alert, c) { onAnswer("no") }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TermButton("talk", c.brick, c, onTalk)
                    TermButton("type", c.unit, c, onType)
                }
            }
        }
    }
}

/** "[ talk ]" style button with a thin accent border, like the phone app's terminal buttons. */
@Composable
private fun TermButton(label: String, accent: Int, c: WatchColors, onClick: () -> Unit) {
    // A plain bordered box: Wear's Button is a fixed-size circle that wraps the label.
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(shape)
            .background(Color(c.panel))
            .border(1.dp, Color(accent).copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("[ $label ]", color = Color(accent), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}
