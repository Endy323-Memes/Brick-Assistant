package com.brick.history.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A terminal button: "[ label ]". */
@Composable
fun TermButton(label: String, color: Color = TermBlue, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        "[ $label ]",
        Modifier
            .clip(TermShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) color else TextMid.copy(alpha = 0.5f),
    )
}

@Composable
fun Hint(text: String, color: Color = TextMid) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
            if (help != null) Hint(help)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A setting that opens a list of choices: shows its label and the current choice. */
@Composable
fun ChoiceRow(label: String, value: String, help: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
            if (help != null) Hint(help)
        }
        Text("$value ▾", style = MaterialTheme.typography.labelLarge, color = DavidColor)
    }
}

/** A list of choices in a dialog; [selected] is marked. */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = TermShape,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = DavidColor) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(options) { i, o ->
                    Text(
                        (if (i == selected) "(*) " else "( ) ") + o,
                        Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (i == selected) TermBlue else TextHigh,
                        fontWeight = if (i == selected) FontWeight.Bold else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("[ cancel ]", color = TextMid) } },
    )
}

/** Asks before something that moves or deletes chats. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = TermShape,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) TermRed else DavidColor) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = TextHigh) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("[ $confirm ]", color = if (danger) TermRed else TermBlue) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("[ cancel ]", color = TextMid) } },
    )
}

/** "3:42 PM" today, "Yesterday 3:42 PM", "Nov 13" this year, else "Nov 13, 2025". */
fun whenText(t: Long, now: Long = System.currentTimeMillis()): String {
    val a = Calendar.getInstance().apply { timeInMillis = t }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
    val dayDiff = if (sameYear) b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR) else 999
    return when (dayDiff) {
        0 -> time
        1 -> "Yesterday $time"
        else -> SimpleDateFormat(if (sameYear) "MMM d" else "MMM d, yyyy", Locale.getDefault()).format(Date(t))
    }
}

private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** A picture from a saved answer (album cover, poster), loaded from the web and kept in memory. */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    val bmp by produceState(images.get(url), url) {
        if (value == null) value = withContext(Dispatchers.IO) { load(url) }
    }
    val b = bmp ?: return
    Image(b.asImageBitmap(), null, modifier.heightIn(max = 160.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
}

private fun load(url: String): Bitmap? = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "BrickAssistant/1.0 (Android personal assistant app)")
    val bytes = c.inputStream.use { it.readBytes() }
    c.disconnect()
    // Shrink big pictures: the chat shows them small.
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sample = 1
    while (opts.outWidth / (sample * 2) >= 480 && opts.outHeight / (sample * 2) >= 480) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.also { images.put(url, it) }
} catch (_: Exception) {
    null
}
