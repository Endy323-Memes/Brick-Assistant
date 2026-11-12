package com.brick.assistant.voice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * "Quiet outdoors": when the ambient light says you're outside, Brick answers in text only. The light sensor is
 * read once per request (a few hundred milliseconds at most), never kept running, so it costs no battery.
 */
object Outdoors {
    /** One ambient light reading in lux, or null without a light sensor (or no reading in [timeoutMs]). */
    suspend fun lux(context: Context, timeoutMs: Long = 400): Float? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0])
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(listener, light, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    /** Headphones (wired, USB or Bluetooth) are connected: only you hear Brick, so it can still talk outdoors. */
    fun headphones(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val private = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in private }
    }

    /** True when Brick should stay silent: the setting is on, it's bright enough to be outdoors, and no headphones. */
    suspend fun quiet(context: Context, enabled: Boolean, thresholdLux: Int): Boolean {
        if (!enabled || headphones(context)) return false
        val lux = lux(context) ?: return false
        return lux >= thresholdLux
    }
}
