package app.aaps.plugins.aps.openAPSSMB

import kotlin.math.roundToLong
import kotlin.time.Clock

/**
 * Phone step counts in 5 minute blocks, for the autoISF 3.2.1 activity monitor (Tobias/ga-zelle).
 *
 * The counting is plain Kotlin. The phone step counter sensor feeds it through [onStepCounter]; on
 * Android that is `StepSensorListener`, registered in MainApp. A platform without a step counter never
 * calls it, and [stepsAvailable] stays false.
 *
 * Fork (v4 port): [stepsAvailable] is new. Without step data the activity monitor must stay off. In
 * 3.2.1 a missing sensor or a missing permission gave zero steps, and zero steps read as inactivity,
 * which raises basal and lowers ISF.
 */
object StepService {

    private var previousStepCount = -1
    private val stepsMap = LinkedHashMap<Long, Int>()
    private const val FIVE_MINUTES_IN_MS = 300000
    private const val NUM_OF_5_MIN_BLOCKS_TO_KEEP = 20

    /** Time of the last sensor value, 0 = never. */
    private var lastSensorValueAt = 0L

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun currentTimeIn5Min(): Long = (nowMillis() / FIVE_MINUTES_IN_MS.toDouble()).roundToLong()

    /** The step counter sensor reports the total since boot. */
    fun onStepCounter(stepCount: Int) {
        val now = currentTimeIn5Min()
        if (previousStepCount >= 0) {
            var recentStepCount = stepCount - previousStepCount
            if (stepsMap.contains(now)) {
                recentStepCount += stepsMap.getValue(now)
            }
            stepsMap[now] = recentStepCount
        }
        previousStepCount = stepCount
        lastSensorValueAt = nowMillis()

        if (stepsMap.size > NUM_OF_5_MIN_BLOCKS_TO_KEEP) {
            val removeBefore = now - NUM_OF_5_MIN_BLOCKS_TO_KEEP
            stepsMap.entries.removeAll { it.key < removeBefore }
        }
    }

    /** True when the step counter is registered and has delivered at least one value since the start. */
    var sensorRegistered = false

    fun stepsAvailable(): Boolean = sensorRegistered && lastSensorValueAt > 0L

    fun getRecentStepCount5Min(): Int {
        val now = currentTimeIn5Min() - 1
        return if (stepsMap.contains(now)) stepsMap.getValue(now) else 0
    }

    fun getRecentStepCount10Min(): Int {
        val tenMinAgo = currentTimeIn5Min() - 2
        return if (stepsMap.contains(tenMinAgo)) stepsMap.getValue(tenMinAgo) else 0
    }

    fun getRecentStepCount15Min(): Int {
        val fifteenMinAgo = currentTimeIn5Min() - 3
        return if (stepsMap.contains(fifteenMinAgo)) stepsMap.getValue(fifteenMinAgo) else 0
    }

    fun getRecentStepCount30Min(): Int = getStepsInLastXMin(6)

    fun getRecentStepCount60Min(): Int = getStepsInLastXMin(12)

    fun getRecentStepCount180Min(): Int = getStepsInLastXMin(36)

    private fun getStepsInLastXMin(numberOf5MinIncrements: Int): Int {
        var stepCount = 0
        val thirtyMinAgo = currentTimeIn5Min() - numberOf5MinIncrements
        for (entry in stepsMap.entries) {
            if (entry.key > thirtyMinAgo) {
                stepCount += entry.value
            }
        }
        return stepCount
    }
}
