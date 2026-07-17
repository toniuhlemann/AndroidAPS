package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Build A shadow telemetry (Paket_final.md Punkt 5): values the loop already computes (or
 * candidate variants computed alongside) that are EXPORT-ONLY. Nothing in here is ever read
 * back into a dosing decision — writers are the calculator/autoISF/determine paths, the only
 * reader is IobActionExporter. Volatile single-slot holders: every loop run overwrites, the
 * exporter reads whatever the current cycle produced (same-thread within a determine pass).
 */
object AutoIsfShadow {

    /**
     * Dual-horizon parabola shadow, captured inside the EXISTING incremental fit loop:
     * shortest valid window (>= fslMinDur), the window nearest MID_WINDOW_MIN, and the
     * legacy best-rSqu winner (identical to what doses). rmse belongs to the winner.
     */
    data class FitShadow(
        val acceShort: Double, val winShortMin: Double,
        val acceMid: Double?, val winMidMin: Double?,
        val acceBest: Double, val winBestMin: Double, val rSquBest: Double,
        val rmseBest: Double?, val nPoints: Int,
        val signAgreeShortMid: Boolean?,
        val use1MinuteRaw: Boolean,
        val capturedAtMs: Long = System.currentTimeMillis(),   // 0054: formal staleness check
    )

    /**
     * Factor-chain shadow from autoISF(): strongest factor BEFORE the acce brake, the brake
     * itself, the lifted value AFTER it, the final after withinISFlimits — plus the pp
     * persistence CANDIDATE (Paket_final Punkt 2, shadow-only: freshly computed from current
     * smoothed deltas, no stored peak, only valid while delta/short/long are all positive).
     */
    data class FactorShadow(
        val strongestPreBrake: Double,
        val acceBrake: Double?,          // acce factor if it braked (<1), else null
        val liftAfterBrake: Double,
        val finalAfterClamp: Double,
        val ppLive: Double,
        val ppCandidate: Double?,        // null when candidate conditions not met (== live then)
        val persistentDelta: Double?,
        val capturedAtMs: Long = System.currentTimeMillis(),   // 0054: formal staleness check
    )

    const val MID_WINDOW_MIN = 25.0

    @Volatile var fit: FitShadow? = null
    @Volatile var factors: FactorShadow? = null
}
