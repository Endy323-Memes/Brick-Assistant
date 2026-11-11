package com.brick.assistant.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Keeps the phone's colors even while Brick isn't open on the watch, and recolors the tile. */
class PaletteListenerService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != PhoneLink.PALETTE) return
        val palette = PhoneLink.json(e.data)?.optJSONObject("palette") ?: return
        WatchColors.save(this, palette)
        TileService.getUpdater(this).requestUpdate(BrickTileService::class.java)
    }
}
