package com.scenacam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Măsoară cu giroscopul cât de stabil e ținut telefonul (rad/s, netezit). */
class SteadinessMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Fără giroscop presupunem o ținere obișnuită din mână. */
    @Volatile
    var value = 0.1f
        private set

    fun start() {
        val g = gyro ?: return
        sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        // Urcă repede (o mișcare bruscă contează imediat), coboară lent.
        val alpha = if (magnitude > value) 0.5f else 0.05f
        value += alpha * (magnitude - value)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
