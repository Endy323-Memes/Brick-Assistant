package com.brick.assistant.wear

import android.content.Context
import com.brick.assistant.BrickApp
import com.brick.assistant.brain.LogEntry
import com.brick.assistant.brain.Speaker
import com.brick.assistant.data.Palettes
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Brick on the watch asks through here: the phone's Brick handles the request and speaks as usual, and each
 * line goes back to the watch as it's said. The watch also uses the phone's colors: it asks for them when it
 * opens, and gets them again whenever the palette changes. See the watch app's PhoneLink for the message paths.
 */
class WatchBridgeService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        when (e.path) {
            HELLO -> send(applicationContext, e.sourceNodeId, PALETTE, paletteJson())
            ASK -> ask(String(e.data, Charsets.UTF_8).trim(), e.sourceNodeId)
        }
    }

    private fun ask(text: String, node: String) {
        val context = applicationContext
        val container = (application as BrickApp).container
        job?.cancel()
        job = scope.launch {
            if (text.isNotEmpty()) container.log.add(LogEntry(Speaker.USER, text))
            try {
                container.assistant.handle(text) { entry ->
                    container.log.add(entry)
                    send(context, node, LINE, JSONObject().put("who", who(entry.speaker)).put("text", entry.text))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                send(context, node, LINE, JSONObject().put("who", "unit").put("text", "Something went wrong."))
            }
            val awaiting = when {
                container.assistant.awaitingConfirmation -> "confirm"
                container.assistant.awaitingDictation -> "dictate"
                else -> ""
            }
            send(context, node, DONE, JSONObject().put("awaiting", awaiting).put("palette", paletteJson().optJSONObject("palette")))
        }
    }

    private fun who(s: Speaker) = when (s) {
        Speaker.USER -> "you"
        Speaker.DAVID -> "brick"
        Speaker.ROBOT -> "unit"
        Speaker.SYSTEM -> "system"
    }

    companion object {
        /**
         * Outlives the service: Android unbinds a listener service as soon as onMessageReceived returns, which
         * would cancel Brick's answer halfway through.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var job: Job? = null

        private const val HELLO = "/brick/hello"
        private const val ASK = "/brick/ask"
        private const val LINE = "/brick/line"
        private const val DONE = "/brick/done"
        private const val PALETTE = "/brick/palette"

        private fun paletteJson(): JSONObject = JSONObject().put("palette", Palettes.current.toJson().optJSONObject("colors"))

        /** Sends the current palette to every connected watch, so Brick there changes colors with the phone. */
        fun pushPalette(context: Context) {
            val app = context.applicationContext
            val body = paletteJson()
            scope.launch {
                try {
                    Wearable.getNodeClient(app).connectedNodes.await().forEach { send(app, it.id, PALETTE, body) }
                } catch (x: Exception) {
                    // No watch (or no Play services): nothing to update.
                }
            }
        }

        private fun send(context: Context, node: String, path: String, o: JSONObject) {
            val client = Wearable.getMessageClient(context)
            scope.launch {
                try {
                    client.sendMessage(node, path, o.toString().toByteArray(Charsets.UTF_8)).await()
                } catch (x: Exception) {
                    // The watch went out of range; Brick still answered on the phone.
                }
            }
        }
    }
}
