package com.brick.assistant.optic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors

/**
 * Optic, the edge audio visualizer (com.optic.island). It already follows Brick's colors; through its
 * control provider Brick also turns it on and off and changes it by voice, and has it step aside while Brick's
 * card or edge animations are on screen.
 */
object OpticBridge {
    const val PACKAGE = "com.optic.island"
    private val URI: Uri = Uri.parse("content://com.optic.island.control")
    private val io = Executors.newSingleThreadExecutor()

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Optic's answer to [method], or null when Optic isn't installed or too old to have the controls. */
    fun call(context: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? = try {
        context.contentResolver.call(URI, method, arg, extras)
    } catch (e: Exception) {
        null
    }

    fun status(context: Context) = call(context, "status")

    fun set(context: Context, extras: Bundle) = call(context, "set", null, extras)

    // ---------------------------------------------------------------- stepping aside

    private val busyReasons = mutableSetOf<String>()
    private var sentBusy: Boolean? = null

    /**
     * Brick's card ("card"), intro ("intro") or app animation ("brand") is on screen ([on]) or gone: Optic hides
     * while any of them are up. Cheap to call often; only changes reach Optic, off the main thread.
     */
    fun busy(context: Context, reason: String, on: Boolean) {
        val app = context.applicationContext
        val now = synchronized(busyReasons) {
            if (on) busyReasons += reason else busyReasons -= reason
            val b = busyReasons.isNotEmpty()
            if (b == sentBusy) return
            sentBusy = b
            b
        }
        io.execute { call(app, "brick_busy", now.toString()) }
    }
}
