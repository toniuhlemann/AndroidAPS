package app.aaps.fuse.core.controller

/**
 * Restartfeste Hysterese hinter [LowThreatGate.DescentRisk].
 *
 * Das Rohsignal darf den finalen Insulinriegel sofort SCHLIESSEN, aber nicht
 * mit einem einzelnen flachen oder knapp positiven UKF-Zyklus wieder
 * OEFFNEN. Ein reiner Abwaertsriegel verlangt dafuer
 * `UKF >= +0,20 mg/dl/min` in drei aufeinanderfolgenden gesunden Zyklen.
 * Hat der Observer waehrenddessen ein echtes Tief bestaetigt, genuegt nach
 * dessen eigener fuenfminuetiger Exit-Bestaetigung ein aktueller positiver
 * UKF-Zyklus; sonst wuerden zwei Hysteresen dieselbe Erholung bezahlen lassen.
 * Der Replay vom 17.-19.08. hat die DREI-ZYKLEN-Regel an den drei Gegenlagen
 * validiert (schneller Mahlzeitenanstieg nach anfaenglichem Fallen: Freigabe
 * 09:20; echte Wende nach dem Low: Freigabe 20:18; Abendsturz 17:55: keine
 * Freigabe waehrend Tief oder Signalbruch). Der spaeter ergaenzte
 * Ein-Zyklus-Weg nach bestaetigtem Tief ist davon NICHT abgedeckt; ihn
 * rechtfertigt allein die bereits erbrachte fuenfminuetige Exit-Bestaetigung
 * des Observers im SELBEN Prozess.
 *
 * Nur [State] wird persistiert. [Runtime] ist absichtlich prozesslokal: nach
 * einem Neustart bleibt ein aktiver Riegel erhalten, aber die prozesslokalen
 * Bestaetigungszyklen beginnen neu - eine unbeobachtete Prozessluecke darf
 * keine Erholung belegen. DASSELBE GILT SEIT DEM 22.08. FUER DEN TIEF-KREDIT
 * [State.sawMeasuredLow]: sein Wert stuetzt sich auf die fuenfminuetige
 * Exit-Bestaetigung des Observers, und die ist prozesslokal. Ein Neustart
 * verwirft den Kredit beim [State.restore]; er entsteht neu, sobald der
 * Observer DIESES Prozesses das Tief wieder bestaetigt (Review 22.08. -
 * vorher haette der persistierte Kredit nach dem Neustart zwei von drei
 * Bestaetigungszyklen erlassen, auf einen Beweis hin, den der neue Prozess
 * nie gesehen hat).
 */
object DescentRecoveryLatch {

    const val RECOVERY_RATE_MGDL_PER_MIN = 0.20
    const val REQUIRED_CONSECUTIVE_CYCLES = 3
    const val MAX_CONTIGUOUS_GAP_MS = 90_000L

    data class State(
        val active: Boolean = false,
        val latchedAtTs: Long = 0L,
        /**
         * Der aktive Riegel hat waehrend seiner Lebensdauer ein vom Observer
         * bestaetigtes gemessenes Tief gesehen. Dessen Oeffnung ist bereits
         * eine fuenfminuetige Erholungsbestaetigung oberhalb der Low-Schwelle;
         * sie darf nicht noch einmal drei volle Zyklen bezahlen muessen.
         */
        val sawMeasuredLow: Boolean = false,
    ) {
        val valid: Boolean
            get() = if (active) latchedAtTs > 0L else latchedAtTs == 0L && !sawMeasuredLow

        companion object {
            /**
             * Die persistierte KOMBINATION wird erst validiert (eine
             * inkonsistente Datei bleibt abgewiesen), dann wird der
             * Tief-Kredit VERWORFEN: seine Rechtfertigung - die
             * Exit-Bestaetigung des Observers - lief im alten Prozess.
             * Der Riegel selbst bleibt bestehen; das ist die konservative
             * Richtung (laenger zu, nie frueher offen).
             */
            fun restore(active: Boolean, latchedAtTs: Long, sawMeasuredLow: Boolean = false): State? =
                State(active, latchedAtTs, sawMeasuredLow).takeIf { it.valid }
                    ?.copy(sawMeasuredLow = false)
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
            val latched = if (state.active)
                state.copy(sawMeasuredLow = state.sawMeasuredLow || measuredLow)
            else State(true, sourceTs.coerceAtLeast(1L), sawMeasuredLow = measuredLow)
            return Result(latched, Runtime(), true, Reason.RISK_ACTIVE)
        }
        if (!state.active) return Result(State(), Runtime(), false, Reason.CLEAR)

        val stateWithLow = if (measuredLow && !state.sawMeasuredLow)
            state.copy(sawMeasuredLow = true)
        else state

        val waitingReason = when {
            !signalHealthy -> Reason.WAITING_UNHEALTHY
            measuredLow -> Reason.WAITING_MEASURED_LOW
            fallRatePerMin == null || !fallRatePerMin.isFinite() ||
                fallRatePerMin < RECOVERY_RATE_MGDL_PER_MIN -> Reason.WAITING_RATE
            else -> null
        }
        if (waitingReason != null)
            return Result(stateWithLow, Runtime(), true, waitingReason)

        // Der Observer hat ein echtes Tief erst nach fuenf Minuten oberhalb
        // seiner Exit-Schwelle freigegeben. Nach diesem bereits erbrachten
        // Nachweis verlangt der Endriegel nur noch eine aktuell positive Rate;
        // die normale Drei-Zyklen-Hysterese waere eine doppelte Wartezeit.
        if (stateWithLow.sawMeasuredLow)
            return Result(State(), Runtime(), false, Reason.RECOVERED)

        val contiguous = runtime.lastSourceTs > 0L &&
            sourceTs > runtime.lastSourceTs &&
            sourceTs - runtime.lastSourceTs <= MAX_CONTIGUOUS_GAP_MS
        val count = if (contiguous) runtime.consecutiveRecoveryCycles + 1 else 1
        val nextRuntime = Runtime(count, sourceTs)
        if (count < REQUIRED_CONSECUTIVE_CYCLES)
            return Result(stateWithLow, nextRuntime, true, Reason.WAITING_CONFIRMATION)

        return Result(State(), Runtime(), false, Reason.RECOVERED)
    }
}
