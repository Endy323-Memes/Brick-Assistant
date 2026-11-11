package com.brick.assistant.brain

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.brick.assistant.media.BrickNotificationListener

/**
 * Discord through its notifications, the way Android Auto and smartwatches do it: Brick reads the messages
 * Discord shows and replies with the notification's own Reply button, so the reply comes from your account
 * without Brick ever holding your Discord login (signing in as you with a token is against Discord's rules).
 * Only conversations that have a notification right now can be read or answered.
 */
object Discord {
    const val PACKAGE = "com.discord"

    /** One Discord conversation from a notification: a DM ("Alex") or a channel ("#general, My Server"). */
    class Chat(
        /** Who or where: the DM's name, or the channel. */
        val title: String,
        /** Latest messages, oldest first: (sender, text). */
        val messages: List<Pair<String, String>>,
        val time: Long,
        val reply: Notification.Action?,
    ) {
        val lastSender: String get() = messages.lastOrNull()?.first ?: title
        val isDm: Boolean get() = !title.startsWith("#") && !title.contains(",")
    }

    fun hasAccess(): Boolean = BrickNotificationListener.instance != null

    /** Discord conversations in your notifications, newest first. */
    fun chats(): List<Chat> {
        val l = BrickNotificationListener.instance ?: return emptyList()
        val all = try { l.activeNotifications.orEmpty().toList() } catch (e: Exception) { emptyList() }
        return all.filter { it.packageName == PACKAGE && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { toChat(it) }
            .sortedByDescending { it.time }
    }

    private fun toChat(sbn: StatusBarNotification): Chat? {
        val n = sbn.notification
        val e = n.extras ?: return null
        val convo = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = (convo ?: e.getCharSequence(Notification.EXTRA_TITLE)?.toString()).orEmpty().trim()
        val messages = mutableListOf<Pair<String, String>>()
        @Suppress("DEPRECATION")
        val bundles = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        bundles?.forEach { p ->
            val b = p as? Bundle ?: return@forEach
            val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            val sender = (b.getCharSequence("sender") ?: b.getParcelable<android.app.Person>("sender_person")?.name)?.toString()?.trim()
            if (text.isNotEmpty()) messages += (sender ?: title) to text
        }
        if (messages.isEmpty()) {
            val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return null
            // Channel notifications often read "Alex: hi there".
            val m = Regex("^([^:]{1,40}):\\s+(.+)$").find(text)
            messages += if (m != null && !title.equals(m.groupValues[1], true)) m.groupValues[1] to m.groupValues[2] else title to text
        }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        return Chat(title.ifEmpty { messages.last().first }, messages.takeLast(5), sbn.postTime, reply)
    }

    private fun clean(s: String) = s.lowercase().replace(Patterns.get("[^a-z0-9 ]"), " ").replace(Patterns.get("\\s+"), " ").trim()

    /** The conversation [who] names: a DM, a channel, or someone who wrote in one. Null [who] = the newest. */
    fun find(chats: List<Chat>, who: String?): Chat? {
        if (who.isNullOrBlank()) return chats.firstOrNull()
        val w = clean(who).removePrefix("the ").removeSuffix(" channel").removeSuffix(" server").trim()
        if (w.isEmpty()) return chats.firstOrNull()
        fun score(name: String): Double {
            val n = clean(name)
            if (n.isEmpty()) return 0.0
            if (n == w || n.split(" ").contains(w) || n.startsWith("$w ") || n.contains(w) && w.length >= 4) return 1.0
            return n.split(" ").maxOf { AppLauncher.similarity(it, w) }
        }
        return chats.map { c -> c to maxOf(score(c.title), c.messages.maxOfOrNull { score(it.first) } ?: 0.0) }
            .filter { it.second >= 0.75 }
            .maxByOrNull { it.second }?.first
    }

    fun speak(chats: List<Chat>, who: String?): String {
        val list = if (who != null) listOfNotNull(find(chats, who)) else chats
        if (list.isEmpty()) {
            return if (who != null) "I don't see any Discord messages from $who." else "You have no Discord messages."
        }
        val shown = list.take(4)
        val head = if (list.size == 1) "" else "You have Discord messages in ${list.size} conversations. "
        return head + shown.joinToString(" ") { c ->
            val recent = c.messages.takeLast(3)
            if (c.isDm) "${c.title} said: " + recent.joinToString(" ") { end(short(it.second)) }
            else "In ${c.title}, " + recent.joinToString(" ") { "${it.first}: " + end(short(it.second)) }
        }
    }

    private fun end(s: String) = if (s.endsWith("?") || s.endsWith("!") || s.endsWith("…")) s else "$s."

    private fun short(s: String, max: Int = 120): String =
        (if (s.length <= max) s else s.take(max).substringBeforeLast(' ') + "…").trimEnd('.', '!', '?', ' ').let { s2 -> if (s.endsWith("?")) "$s2?" else s2 }

    /** Sends [text] with the notification's Reply button. False if Discord didn't take it. */
    fun reply(context: Context, chat: Chat, text: String): Boolean {
        val action = chat.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
