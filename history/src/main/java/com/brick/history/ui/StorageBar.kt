package com.brick.history.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** How much room History takes (its chats database, settings and cache) and how full the phone is. */
data class StorageUse(val history: Long, val used: Long, val total: Long) {
    companion object {
        /** Walks History's own folders and asks the phone how full its storage is. Call off the main thread. */
        fun measure(context: Context): StorageUse {
            val own = listOfNotNull(context.dataDir, context.externalCacheDir).sumOf { sizeOf(it) }
            val fs = StatFs(Environment.getDataDirectory().path)
            return StorageUse(own, fs.totalBytes - fs.availableBytes, fs.totalBytes)
        }

        private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { sizeOf(it) } ?: 0L

        /** "2.4 MB", "118 GB", "512 KB": base 1000, like the phone's own storage screen. */
        fun bytes(n: Long): String {
            val units = listOf("B", "KB", "MB", "GB", "TB")
            var v = n.toDouble(); var i = 0
            while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
            return when {
                i == 0 -> "$n B"
                v >= 100 -> String.format(Locale.US, "%.0f %s", v, units[i])
                else -> String.format(Locale.US, "%.1f %s", v, units[i]).replace(".0 ", " ")
            }
        }
    }
}

/**
 * The storage bar: the phone's storage, with History's share in amber (always at least a sliver so you can see it),
 * the rest of what's used in dim, and what's free empty. Reloads whenever the chats change ([version]).
 */
@Composable
fun StorageBar(version: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val use by produceState<StorageUse?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { StorageUse.measure(context) }.getOrNull() }
    }
    val u = use ?: return
    if (u.total <= 0) return
    Column(modifier.fillMaxWidth()) {
        Text(
            buildAnnotatedString {
                append("# storage  ")
                withStyle(SpanStyle(color = DavidColor)) { append("history ${StorageUse.bytes(u.history)}") }
                append(" · phone ${StorageUse.bytes(u.used)} of ${StorageUse.bytes(u.total)} used")
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
        )
        Row(
            Modifier.padding(top = 4.dp).fillMaxWidth().height(8.dp).clip(TermShape).border(1.dp, Hairline, TermShape).background(Ink),
        ) {
            val historyShare = (u.history.toFloat() / u.total).coerceIn(0.01f, 1f)
            val otherShare = ((u.used - u.history).toFloat() / u.total).coerceIn(0f, 1f - historyShare)
            Box(Modifier.fillMaxHeight().fillMaxWidth(historyShare).background(DavidColor))
            if (otherShare > 0f) Box(Modifier.fillMaxHeight().weight(otherShare).background(TextMid.copy(alpha = 0.45f)))
            if (1f - historyShare - otherShare > 0.001f) Box(Modifier.fillMaxHeight().weight(1f - historyShare - otherShare))
        }
    }
}
