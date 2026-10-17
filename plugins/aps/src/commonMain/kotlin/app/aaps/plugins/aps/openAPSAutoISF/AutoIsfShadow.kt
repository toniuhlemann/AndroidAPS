package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Fork (IOB-Action viewer, "Build A" shadow telemetry): values the loop already computes, or
 * candidate variants computed next to them, that are EXPORT ONLY. Nothing in here is ever read back
 * into a dosing decision: the writers are the glucose status calculator and autoISF(), the only
 * reader is the state export. Single slot holders: every loop run overwrites them.
 */
object AutoIsfShadow {

    /**
     * Two horizons of the parabola fit, captured inside the EXISTING incremental fit loop: the
     * shortest valid window, the window nearest [MID_WINDOW_MIN], and the best rSqu winner (the one
     * that doses). rmse belongs to the winner.
     */
    data class FitShadow(
        val acceShort: Double, val winShortMin: Double,
        val acceMid: Double?, val winMidMin: Double?,
        val acceBest: Double, val winBestMin: Double, val rSquBest: Double,
        val rmseBest: Double?, val nPoints: Int,
        val signAgreeShortMid: Boolean?,
        val use1MinuteRaw: Boolean,
        val capturedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * The factor chain of autoISF(): the strongest factor BEFORE the acce brake, the brake itself,
     * the lifted value AFTER it and the final value after withinISFlimits - plus the pp persistence
     * CANDIDATE (shadow only: computed fresh from the current smoothed deltas, valid only while
     * delta, short and long average delta are all positive).
     */
    data class FactorShadow(
        val strongestPreBrake: Double,
        val acceBrake: Double?,          // the acce factor if it braked (< 1), else null
        val liftAfterBrake: Double,
        val finalAfterClamp: Double,
        val ppLive: Double,
        val ppCandidate: Double?,        // null when the candidate conditions are not met
        val persistentDelta: Double?,
        val capturedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    )

    const val MID_WINDOW_MIN = 25.0

    @Volatile var fit: FitShadow? = null
    @Volatile var factors: FactorShadow? = null
}
