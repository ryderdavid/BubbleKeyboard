package com.ryderdavid.voicebar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Whether the phone is in a hand or lying still (a desk, a stand), from half a second of the
 * gyroscope (the accelerometer on phones without one). A hand is never perfectly still: its tremor
 * turns the phone a little all the time, where a phone on a desk only shows the sensor's own noise.
 * Registered only for the sample, then released.
 */
class HeldProbe(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** [onResult] on the main thread: held (true), still (false), or null with no motion sensor; and the motion measured. */
    fun check(windowMs: Long = WINDOW_MS, onResult: (held: Boolean?, motion: Float, sensor: String) -> Unit) {
        val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sensor = gyro ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensors == null || sensor == null) { onResult(null, 0f, "none"); return }
        val samples = ArrayList<FloatArray>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { samples += e.values.copyOf(3) }
            override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, main)
        main.postDelayed({
            sensors.unregisterListener(listener)
            val m = motion(samples)
            val name = if (gyro != null) "gyroscope" else "accelerometer"
            onResult(if (samples.size < MIN_SAMPLES) null else m > (if (gyro != null) GYRO_HELD else ACCEL_HELD), m, name)
        }, windowMs)
    }

    companion object {
        const val WINDOW_MS = 500L
        /** Fewer readings than this in the window: can't tell. */
        const val MIN_SAMPLES = 8
        /** Held: the gyroscope wobbles more than this (rad/s, standard deviation of the busiest axis). */
        const val GYRO_HELD = 0.01f
        /** Held, from the accelerometer: more than this (m/s², standard deviation of the busiest axis). */
        const val ACCEL_HELD = 0.04f

        /** How much the readings move: the standard deviation of the axis that moves most (constant offsets and gravity drop out). */
        fun motion(samples: List<FloatArray>): Float {
            if (samples.size < 2) return 0f
            return (0..2).maxOf { axis ->
                val mean = samples.sumOf { it[axis].toDouble() } / samples.size
                sqrt(samples.sumOf { (it[axis] - mean) * (it[axis] - mean) } / samples.size).toFloat()
            }
        }
    }
}
