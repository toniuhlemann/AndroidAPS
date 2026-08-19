package app.aaps.fuse.core.controller

/**
 * Restartfeste Hysterese hinter [LowThreatGate.DescentRisk].
 *
 * Das Rohsignal darf den finalen Insulinriegel sofort SCHLIESSEN, aber nicht
 * mit einem einzelnen flachen oder knapp positiven UKF-Zyklus wieder
 * OEFFNEN. Der Replay vom 17.-19.08. trennt die drei Gegenlagen mit
 * `UKF >= +0,20 mg/dl/min` in drei aufeinanderfolgenden gesunden Zyklen:
 *
 *  - schneller Mahlzeitenanstieg nach anfaenglichem Fallen: Freigabe 09:20;
 *  - echte Wende nach dem Low: Freigabe 20:18;
 *  - Abendsturz 17:55: keine Freigabe waehrend Tief oder Signalbruch.
 *
 * Nur [State] wird persistiert. [Runtime] ist absichtlich prozesslokal: nach
 * einem Neustart bleibt ein aktiver Riegel erhalten, die drei
 * Bestaetigungszyklen beginnen aber neu. Eine unbeobachtete Prozessluecke
 * darf keine Erholung belegen.
 */
object DescentRecoveryLatch {

    const val RECOVERY_RATE_MGDL_PER_MIN = 0.20
    const val REQUIRED_CONSECUTIVE_CYCLES = 3
    const val MAX_CONTIGUOUS_GAP_MS = 90_000L

    data class State(
        val active: Boolean = false,
        val latchedAtTs: Long = 0L,
    ) {
        val valid: Boolean
            get() = if (active) latchedAtTs > 0L else latchedAtTs == 0L

        companion object {
            fun restore(active: Boolean, latchedAtTs: Long): State? =
                State(active, latchedAtTs).takeIf { it.valid }
        }
    }

    /** Prozesslokaler Nachweis einer lueckenlosen Erholung. */
    data class Runtime(
        val consecutiveRecoveryCycles: Int = 0,
        val lastSourceTs: Long = 0L,
    )

    enum class Reason {
        CLEAR,
        RISK_ACTIVE,
        WAITING_UNHEALTHY,
        WAITING_MEASURED_LOW,
        WAITING_RATE,
        WAITING_CONFIRMATION,
        RECOVERED,
    }

    data class Result(
        val state: State,
        val runtime: Runtime,
        val blocksPositive: Boolean,
        val reason: Reason,
    )

    fun advance(
        state: State,
        runtime: Runtime,
        riskActive: Boolean,
        signalHealthy: Boolean,
        measuredLow: Boolean,
        fallRatePerMin: Double?,
        sourceTs: Long,
    ): Result {
        require(state.valid) { "invalid descent latch state $state" }
        require(runtime.consecutiveRecoveryCycles >= 0) { "negative recovery count" }

        if (riskActive) {
            val latched = if (state.active) state else State(true, sourceTs.coerceAtLeast(1L))
            return Result(latched, Runtime(), true, Reason.RISK_ACTIVE)
        }
        if (!state.active) return Result(State(), Runtime(), false, Reason.CLEAR)

        val waitingReason = when {
            !signalHealthy -> Reason.WAITING_UNHEALTHY
            measuredLow -> Reason.WAITING_MEASURED_LOW
            fallRatePerMin == null || !fallRatePerMin.isFinite() ||
                fallRatePerMin < RECOVERY_RATE_MGDL_PER_MIN -> Reason.WAITING_RATE
            else -> null
        }
        if (waitingReason != null)
            return Result(state, Runtime(), true, waitingReason)

        val contiguous = runtime.lastSourceTs > 0L &&
            sourceTs > runtime.lastSourceTs &&
            sourceTs - runtime.lastSourceTs <= MAX_CONTIGUOUS_GAP_MS
        val count = if (contiguous) runtime.consecutiveRecoveryCycles + 1 else 1
        val nextRuntime = Runtime(count, sourceTs)
        if (count < REQUIRED_CONSECUTIVE_CYCLES)
            return Result(state, nextRuntime, true, Reason.WAITING_CONFIRMATION)

        return Result(State(), Runtime(), false, Reason.RECOVERED)
    }
}
