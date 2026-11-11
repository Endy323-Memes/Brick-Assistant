package com.brick.assistant.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Tells when the phone is in a pocket (or bag): the ambient light sensor reads near-dark for a moment, and
 * (when the phone has one) the proximity sensor is covered, so a phone lying face up in a dark room doesn't
 * count. Leaving the pocket counts right away.
 */
class PocketDetector(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val light: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    // Prefer a wake-up proximity sensor: it wakes the phone when you take it out, so Brick can let the CPU sleep.
    private val proximity: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    @Volatile var lux = Float.MAX_VALUE
        private set
    @Volatile private var near = false
    @Volatile private var coveredSince = 0L

    val available: Boolean get() = light != null
    /** True when taking the phone out wakes it up by itself (so listening needn't keep the CPU awake). */
    val wakesOnExit: Boolean get() = proximity?.isWakeUpSensor == true

    fun start() {
        if (sm == null || light == null) return
        // Readings may be batched up to a second, so the sensors wake the CPU less often.
        sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000)
        proximity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000) }
    }

    fun stop() { sm?.unregisterListener(this) }

    val inPocket: Boolean
        get() = coveredSince != 0L && SystemClock.elapsedRealtime() - coveredSince >= ENTER_MS

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = e.values[0]
            Sensor.TYPE_PROXIMITY -> near = e.values[0] < minOf(e.sensor.maximumRange, 5f)
        }
        val covered = lux < DARK_LUX && (proximity == null || near)
        coveredSince = if (covered) (if (coveredSince == 0L) SystemClock.elapsedRealtime() else coveredSince) else 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val DARK_LUX = 5f
        const val ENTER_MS = 1500L
    }
}
