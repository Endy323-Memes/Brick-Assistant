package com.brick.assistant.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** "Ask Brick" tile: Brick's last answer, and a button that opens Brick listening. */
class BrickTileService : TileService() {
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val c = WatchColors.load(this)
        val device = req.deviceConfiguration
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("ask")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WatchActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val title = Text.Builder(this, "brick@watch")
            .setColor(argb(c.brick))
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .build()
        val last = WatchColors.lastReply(this)?.let { if (it.length > 90) it.take(90).substringBeforeLast(' ') + "…" else it }
        val body = Text.Builder(this, last ?: "Tap to ask Brick.")
            .setColor(argb(if (last == null) c.dim else c.text))
            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
            .setMaxLines(3)
            .build()
        val chip = Chip.Builder(this, open, device)
            .setPrimaryLabelContent("Ask Brick")
            .setChipColors(ChipColors(c.brick, c.background))
            .build()
        val layout = PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(title)
            .setContent(body)
            .setPrimaryChipContent(chip)
            .build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build(),
        )
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())

    private companion object {
        const val RES_VERSION = "1"
    }
}
