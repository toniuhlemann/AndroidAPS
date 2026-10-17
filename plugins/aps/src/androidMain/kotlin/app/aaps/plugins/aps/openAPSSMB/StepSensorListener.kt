package app.aaps.plugins.aps.openAPSSMB

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat

/**
 * Android side of the autoISF 3.2.1 activity monitor: forwards the step counter and the accelerometer
 * to [StepService] and [PhoneMovementDetector].
 *
 * The step counter needs ACTIVITY_RECOGNITION. Without it the listener is not registered, and
 * [StepService.stepsAvailable] stays false, so the activity monitor stays off.
 */
object StepSensorListener : SensorEventListener {

    private var accelerometerRegistered = false

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(sensorEvent: SensorEvent?) {
        sensorEvent ?: return
        when (sensorEvent.sensor.type) {
            Sensor.TYPE_STEP_COUNTER  -> StepService.onStepCounter(sensorEvent.values[0].toInt())
            Sensor.TYPE_ACCELEROMETER -> PhoneMovementDetector.onAcceleration(sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2])
        }
    }

    /** Registers the sensors that are there. Safe to call again, e.g. after the permission was granted. */
    fun register(context: Context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (!accelerometerRegistered) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            accelerometerRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (permitted && stepSensor != null && !StepService.sensorRegistered) {
            StepService.sensorRegistered = sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }
}
