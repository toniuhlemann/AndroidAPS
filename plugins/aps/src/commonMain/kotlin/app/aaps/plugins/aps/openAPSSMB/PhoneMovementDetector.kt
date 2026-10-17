package app.aaps.plugins.aps.openAPSSMB

import kotlin.time.Clock

/**
 * Whether the phone was moved in the last 15 minutes, for the autoISF 3.2.1 activity monitor.
 *
 * The accelerometer feeds it through [onAcceleration]; on Android that is `StepSensorListener`,
 * registered in MainApp.
 */
object PhoneMovementDetector {

    private var lastUpdateTimestamp: Long = 0
    private const val MOVEMENT_THRESHOLD: Double = 1.05

    /** Standard gravity in m/s^2, the value of Android's `SensorManager.GRAVITY_EARTH`. */
    private const val GRAVITY_EARTH = 9.80665f

    fun onAcceleration(x: Float, y: Float, z: Float) {
        val acceleration = (x * x + y * y + z * z) / (GRAVITY_EARTH * GRAVITY_EARTH)
        if (acceleration > MOVEMENT_THRESHOLD) {
            lastUpdateTimestamp = Clock.System.now().toEpochMilliseconds()
        }
    }

    fun phoneMoved(): Boolean = (lastUpdateTimestamp + 15 * 60000) > Clock.System.now().toEpochMilliseconds()
}
