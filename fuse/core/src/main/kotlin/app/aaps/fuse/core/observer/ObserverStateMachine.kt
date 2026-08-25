package app.aaps.fuse.core.observer

/**
 * K1-Zustandsmaschine (Spec v0.5 §3-§10, Addendum v0.5.1 A1-A6).
 *
 * Reihenfolge je Aufruf — GENAU EINE Phasen-Transition, first match:
 *   CLASSIFY  Zeitstempelbeziehung; Health-Clock NUR bei altem/gleichem ts
 *   DERIVE    Health-, Safety- und Reset-Gruende sammeln
 *   APPLY     P1 Gate/Reset | P2 TURN | P3 CARRY | P4 Ende | P5 Fortschritt
 *
 * Rein, deterministisch, ohne Zeitquelle: alle Dauern stammen aus
 * sourceTs-Differenzen, computeTs dient ausschliesslich der Health-Clock.
 * Damit ist jeder Lauf auf der JVM replaybar (Frozen-Input-Paritaet).
 */
class ObserverStateMachine(
    private val p: ObserverParams = ObserverParams(),
    private val sessionId: String,
) {

    private var lastAcceptedSourceTs: Long = Long.MIN_VALUE
    private var lastSignalInputBg: Double? = null
    private var lastSensorEpoch: Long? = null
    private var lastCalibrationEpoch: Long? = null

    private var phase: Phase = Phase.REARMING
    private val healthReasons = linkedSetOf(HealthReason.WARMUP)
    private val safetyReasons = linkedSetOf<SafetyReason>()

    private var quietAccumMin = 0.0
    private var confirmCount = 0
    private var carryDurMin = 0.0
    private var turnExitAccumMin = 0.0
    private var lowExitAccumMin = 0.0

    private var candidateId: String? = null
    private var eventId: String? = null
    private var eventStartTs: Long? = null
    private var livePeak: Peak? = null

    /** Nur fuer INPUT_STEP_RECOVERY/INSUFFICIENT_HISTORY: seit dem letzten
     *  Segmentbruch noch kein gueltiges R gesehen. */
    private var rValidSinceSegmentStart = false

    val currentPhase: Phase get() = phase
    val currentHealth: Health get() = deriveHealth()
    val currentEventId: String? get() = eventId

    fun step(input: ObserverInput): ObserverStep {
        // ---- CLASSIFY (Addendum A3 der v0.5 / R63-F3) -------------------
        // Die Health-Clock laeuft NUR, wenn KEIN frisches Sample vorliegt.
        // Sonst wuerde ein Recovery-Sample erst eine STALE-Flanke und
        // unmittelbar danach einen Gap-Reset ausloesen — zwei Transitionen
        // in einem Aufruf.
        val incomingIsNew = input.sourceTs > lastAcceptedSourceTs
        if (!incomingIsNew) {
            if (lastAcceptedSourceTs != Long.MIN_VALUE &&
                (input.computeTs - lastAcceptedSourceTs) / 60_000.0 > p.staleMin
            ) {
                val fresh = healthReasons.add(HealthReason.STALE)
                if (fresh) return gateEdge(input, setOf("stale"), accepted = false)
            }
            return snapshot(accepted = false, transition = null, resets = emptySet())
        }
        healthReasons.remove(HealthReason.STALE)

        // ---- DERIVE -----------------------------------------------------
        val first = lastAcceptedSourceTs == Long.MIN_VALUE
        val dtMin = if (first) 0.0 else (input.sourceTs - lastAcceptedSourceTs) / 60_000.0
        val resets = deriveResets(input, dtMin, first)
        val gateWasActive = deriveHealth() != Health.READY || safetyReasons.isNotEmpty()

        applySegmentEffects(resets)
        deriveHealthReasons(input, resets)
        deriveSafety(input, dtMin)

        val gateIsActive = deriveHealth() != Health.READY || safetyReasons.isNotEmpty()

        // ---- APPLY ------------------------------------------------------
        lastAcceptedSourceTs = input.sourceTs
        lastSignalInputBg = input.signalInputBg
        lastSensorEpoch = input.sensorEpoch
        lastCalibrationEpoch = input.calibrationEpoch

        // P1 — Gate- oder Reset-Flanke: genau EIN ABORT mit vereinigtem Grund.
        val phaseResets = resets - ResetCause.R_SEGMENT_BREAK  // reiner Signalbruch ohne Phasenwirkung gibt es nicht
        if (gateIsActive && (!gateWasActive || phaseResets.isNotEmpty())) {
            return gateEdge(input, phaseResets.map { it.name }.toSet() + gateReasonNames(), accepted = true, resets = resets)
        }
        if (gateIsActive) {
            // Gate haelt an: REARMING halten, KEINE Phasenevidenz. Die
            // lowExit-Uhr ist davon ausgenommen (Spec §7) und wurde in
            // deriveSafety bereits fortgeschrieben.
            return snapshot(accepted = true, transition = null, resets = resets)
        }
        if (phaseResets.isNotEmpty()) {
            return gateEdge(input, phaseResets.map { it.name }.toSet(), accepted = true, resets = resets)
        }
        // gateJustCleared (Addendum A4): Aufruf endet, das Exit-Sample laedt
        // KEINEN Rearm vor — weder Ruhe noch Confirm zaehlen an dieser Kante.
        if (gateWasActive) {
            resetPhaseTimers()
            phase = Phase.REARMING
            return snapshot(accepted = true, transition = null, resets = resets)
        }

        val r = (if (HealthReason.INSUFFICIENT_SIGNAL_HISTORY in healthReasons) null else input.rSigned)
            ?: return snapshot(accepted = true, transition = null, resets = resets)
        val bg = input.signalInputBg
        val continuous = dtMin <= p.stateContMaxMin

        updateLivePeak(input, bg)

        // P2 — TURN-Eintritt
        if ((phase == Phase.RISE_ACTIVE || phase == Phase.CARRY) && turnEntryHolds(input, r)) {
            return transitionTo(Phase.TURN, TransitionType.PHASE_CHANGE, input, setOf("turnEntry"), resets)
        }
        // P3 — Ereignisfenster geschlossen, Signal bleibt heiss
        if (phase == Phase.RISE_ACTIVE && r >= p.thr) {
            val ageMin = eventStartTs?.let { (input.sourceTs - it) / 60_000.0 } ?: 0.0
            if (ageMin >= p.eventWindowMin) {
                carryDurMin = 0.0
                return transitionTo(Phase.CARRY, TransitionType.PHASE_CHANGE, input, setOf("eventWindowClosed"), resets)
            }
        }
        // P4 — Schwellenverlust bzw. regulaeres Ende
        when {
            phase == Phase.CANDIDATE && r < p.thr ->
                return transitionTo(Phase.REARMING, TransitionType.ABORT, input, setOf("thresholdLost"), resets)

            (phase == Phase.RISE_ACTIVE || phase == Phase.CARRY) && r < p.thr ->
                return transitionTo(Phase.REARMING, TransitionType.PHASE_CHANGE, input, setOf("eventEnded"), resets)

            phase == Phase.TURN -> {
                turnExitAccumMin = if (r >= 0.0 && continuous) turnExitAccumMin + dtMin else 0.0
                if (turnExitAccumMin >= p.turnExitMin) {
                    return transitionTo(Phase.REARMING, TransitionType.PHASE_CHANGE, input, setOf("turnExit"), resets)
                }
                return snapshot(accepted = true, transition = null, resets = resets)
            }
        }

        // P5 — normale Fortschreibung
        when (phase) {
            Phase.REARMING  -> {
                // Ein bereits heisses Signal ist "hot-unarmed": R >= THR verletzt
                // die Ruhebedingung, es kann also nie ARMED werden, bevor das
                // Signal einmal unter THR faellt. Ausdruecklich KEIN Carry.
                quietAccumMin = if (r < p.thr && continuous) quietAccumMin + dtMin else 0.0
                if (quietAccumMin >= p.rearmMin) {
                    return transitionTo(Phase.ARMED, TransitionType.PHASE_CHANGE, input, setOf("rearmed"), resets)
                }
            }

            Phase.ARMED     -> if (r >= p.thr) {
                candidateId = sha256(p.candidateKey, sessionId, input.sensorEpoch, input.sourceTs)
                confirmCount = 1
                livePeak = bg?.let { Peak(input.sourceTs, it) }
                return transitionTo(Phase.CANDIDATE, TransitionType.PHASE_CHANGE, input, setOf("thresholdCrossed"), resets)
            }

            Phase.CANDIDATE -> if (r >= p.thr) {
                confirmCount++
                if (confirmCount >= p.confirmN) {
                    eventId = sha256(candidateId, input.sourceTs)
                    eventStartTs = input.sourceTs
                    return transitionTo(Phase.RISE_ACTIVE, TransitionType.RISE_CONFIRMED, input, setOf("confirmed"), resets)
                }
            }

            Phase.CARRY     -> carryDurMin += if (continuous) dtMin else 0.0
            else            -> Unit
        }
        return snapshot(accepted = true, transition = null, resets = resets)
    }

    // ---- DERIVE-Bausteine -----------------------------------------------

    private fun deriveResets(input: ObserverInput, dtMin: Double, first: Boolean): Set<ResetCause> {
        val out = linkedSetOf<ResetCause>()
        if (first) return out
        // Gap-Eigenschaften sind KUMULATIV, nicht first-match: eine 61-min-
        // Luecke ist gleichzeitig Continuity-, Segment- und Reinit-Bruch.
        if (dtMin > p.stateContMaxMin) out += ResetCause.CONTINUITY_BREAK
        // DER EIGENE PARAMETER, nicht ein globales Objekt (Review 25.08.
        // abends): die Politik wird beim Bauen des Observers injiziert und
        // ist danach unveraenderlich. Ein prozessweiter Schalter haette
        // parallele Runner und Tests denselben Wert teilen lassen - zwei
        // Matrixlaeufe koennten sich vermischen.
        val segmentBreakMin = p.rSegmentBreakMin
        if (dtMin > segmentBreakMin) out += ResetCause.R_SEGMENT_BREAK
        if (dtMin > p.reinitMin) out += ResetCause.FULL_REINIT
        if (input.inputGap) out += ResetCause.INPUT_DROP
        if (lastSensorEpoch != null && input.sensorEpoch != lastSensorEpoch) out += ResetCause.SENSOR_RESET
        if (lastCalibrationEpoch != null && input.calibrationEpoch != lastCalibrationEpoch) out += ResetCause.CALIBRATION_RESET
        // Unklassifizierter Sprung: NUR wenn keine Epoche wechselte, sonst
        // waere es schlicht die Kalibrierung (Spec §4.4, R64-F1).
        val prev = lastSignalInputBg
        val now = input.signalInputBg
        if (prev != null && now != null &&
            ResetCause.CALIBRATION_RESET !in out && ResetCause.SENSOR_RESET !in out &&
            // C9 (Codex-Adjudication 09.08.): das Sprungfenster reicht bis zur
            // R-SEGMENTGRENZE, nicht nur bis zur Zustands-Kontinuitaet. Mit
            // stateContMaxMin (1,5) blieb das Band (1,5 .. 3,0] min ungedeckt:
            // dort greift weder die Sprungerkennung noch der Segmentbruch der
            // r-Reihe - alter und neuer Messregime-Punkt lagen zusammen in
            // derselben Steigungsschaetzung. Jenseits von rSegmentBreakMin
            // bricht das Segment ohnehin, dort braucht es die Pruefung nicht.
            dtMin <= segmentBreakMin && kotlin.math.abs(now - prev) >= p.inputStepMgdl
        ) out += ResetCause.INPUT_STEP_SUSPECTED
        return out
    }

    private fun applySegmentEffects(resets: Set<ResetCause>) {
        val segmentBroken = ResetCause.R_SEGMENT_BREAK in resets ||
            ResetCause.SENSOR_RESET in resets ||
            ResetCause.CALIBRATION_RESET in resets ||
            ResetCause.INPUT_STEP_SUSPECTED in resets ||
            ResetCause.INPUT_DROP in resets
        if (segmentBroken) rValidSinceSegmentStart = false
        if (ResetCause.INPUT_STEP_SUSPECTED in resets) healthReasons += HealthReason.INPUT_STEP_RECOVERY
        if (ResetCause.FULL_REINIT in resets || ResetCause.SENSOR_RESET in resets) {
            healthReasons += HealthReason.WARMUP
            livePeak = null
        }
    }

    private fun deriveHealthReasons(input: ObserverInput, resets: Set<ResetCause>) {
        fun set(r: HealthReason, on: Boolean) = if (on) healthReasons.add(r) else healthReasons.remove(r)
        // Ein Segmentbruch verwirft die Theil-Sen-Historie (Spec §4.2). R ist damit in
        // DIESEM Zyklus nicht berechenbar — unabhaengig davon, was der Aufrufer liefert.
        // Die Maschine darf sich darauf nicht verlassen, sonst waere sie nach einer
        // 61-min-Luecke sofort wieder READY (Testfall T25a).
        val segmentBroken = resets.any {
            it in setOf(ResetCause.R_SEGMENT_BREAK, ResetCause.SENSOR_RESET,
                        ResetCause.CALIBRATION_RESET, ResetCause.INPUT_STEP_SUSPECTED,
                        ResetCause.INPUT_DROP)
        }
        val rUsable = if (segmentBroken) null else input.rSigned

        set(HealthReason.Q1_MISSING, input.q1 == null)
        set(HealthReason.ACTIVITY_MISSING, input.activity == ActivityValidity.MISSING)
        set(HealthReason.ACTIVITY_STALE, input.activity == ActivityValidity.STALE)
        set(HealthReason.ACTIVITY_FUTURE, input.activity == ActivityValidity.FUTURE)
        set(HealthReason.ISF_MISSING, !input.profileIsfValid)

        if (rUsable != null) rValidSinceSegmentStart = true
        set(HealthReason.INSUFFICIENT_SIGNAL_HISTORY, rUsable == null)

        val sinceSensorMin = (input.sourceTs - input.sensorEpoch) / 60_000.0
        set(HealthReason.SENSOR_EMBARGO, input.sensorEpoch > 0L && sinceSensorMin < p.sensorEmbargoMin)

        val sinceCalibMin = (input.sourceTs - input.calibrationEpoch) / 60_000.0
        set(
            HealthReason.CALIBRATION_BLIND,
            input.calibrationEpoch > 0L &&
                (sinceCalibMin < p.calibBlindMin || ResetCause.CALIBRATION_RESET in resets)
        )

        // Addendum A1: INPUT_STEP_RECOVERY endet erst mit gueltigem R im NEUEN
        // Segment — nicht schon mit dem naechsten Sample.
        if (HealthReason.INPUT_STEP_RECOVERY in healthReasons &&
            ResetCause.INPUT_STEP_SUSPECTED !in resets && rValidSinceSegmentStart
        ) healthReasons -= HealthReason.INPUT_STEP_RECOVERY

        // WARMUP endet, wenn Q1, Activity, ISF und R gleichzeitig gueltig sind
        // (Zeitgrenzen tragen SENSOR_EMBARGO/CALIBRATION_BLIND selbst).
        if (HealthReason.WARMUP in healthReasons &&
            input.q1 != null && rUsable != null &&
            input.activity == ActivityValidity.VALID && input.profileIsfValid
        ) healthReasons -= HealthReason.WARMUP
    }

    private fun deriveSafety(input: ObserverInput, dtMin: Double) {
        val bg = input.signalInputBg ?: return
        if (SafetyReason.LOW !in safetyReasons) {
            if (bg < p.lowEnterMgdl) {
                safetyReasons += SafetyReason.LOW
                lowExitAccumMin = 0.0
            }
            return
        }
        // Waehrend HOLD ist die lowExit-Uhr die EINZIGE Evidenz, die laufen
        // darf — sonst koennte LOW nie verlassen werden (R64-F2).
        lowExitAccumMin = if (bg >= p.lowExitMgdl && dtMin <= p.stateContMaxMin) lowExitAccumMin + dtMin else 0.0
        if (lowExitAccumMin >= p.lowExitMin) {
            safetyReasons -= SafetyReason.LOW
            lowExitAccumMin = 0.0
        }
    }

    // ---- Peak und TURN ---------------------------------------------------

    private fun updateLivePeak(input: ObserverInput, bg: Double?) {
        if (bg == null) return
        if (phase == Phase.CANDIDATE || phase == Phase.RISE_ACTIVE ||
            phase == Phase.CARRY || phase == Phase.TURN
        ) {
            // Strikt groesser: bei Gleichstand bleibt der FRUEHESTE Peak stehen.
            val cur = livePeak
            if (cur == null || bg > cur.value) livePeak = Peak(input.sourceTs, bg)
        }
    }

    private fun turnEntryHolds(input: ObserverInput, r: Double): Boolean {
        val peak = livePeak ?: return false
        val bg = input.signalInputBg ?: return false
        val peakAgeMin = (input.sourceTs - peak.sourceTs) / 60_000.0
        val dropFromPeak = peak.value - bg
        return peakAgeMin >= p.turnPeakAgeMin && dropFromPeak >= p.turnDropMgdl && r <= 0.0
    }

    // ---- Transitionen ----------------------------------------------------

    private fun gateReasonNames(): Set<String> =
        healthReasons.map { it.name }.toSet() + safetyReasons.map { it.name }.toSet()

    private fun gateEdge(
        input: ObserverInput,
        reasons: Set<String>,
        accepted: Boolean,
        resets: Set<ResetCause> = emptySet(),
    ): ObserverStep {
        val from = phase
        val emitAbort = from in setOf(Phase.CANDIDATE, Phase.RISE_ACTIVE, Phase.CARRY, Phase.TURN)
        resetPhaseTimers()
        phase = Phase.REARMING
        val t = if (emitAbort) Transition(
            type = TransitionType.ABORT, from = from, to = Phase.REARMING, reasons = reasons,
            triggerSourceTs = if (accepted) input.sourceTs else lastAcceptedSourceTs,
            triggerComputeTs = input.computeTs, candidateId = candidateId, eventId = eventId,
        ) else null
        if (emitAbort) { candidateId = null; eventId = null; eventStartTs = null; livePeak = null }
        return snapshot(accepted, t, resets)
    }

    private fun transitionTo(
        to: Phase,
        type: TransitionType,
        input: ObserverInput,
        reasons: Set<String>,
        resets: Set<ResetCause>,
    ): ObserverStep {
        val from = phase
        phase = to
        if (to == Phase.REARMING) {
            resetPhaseTimers()
            candidateId = null; eventId = null; eventStartTs = null; livePeak = null
        }
        val t = Transition(
            type = type, from = from, to = to, reasons = reasons,
            triggerSourceTs = input.sourceTs, triggerComputeTs = input.computeTs,
            candidateId = candidateId, eventId = eventId,
        )
        return snapshot(accepted = true, transition = t, resets = resets)
    }

    private fun resetPhaseTimers() {
        quietAccumMin = 0.0; confirmCount = 0; carryDurMin = 0.0; turnExitAccumMin = 0.0
    }

    private fun deriveHealth(): Health = when {
        HealthReason.WARMUP in healthReasons -> Health.WARMUP
        healthReasons.isEmpty()              -> Health.READY
        else                                 -> Health.DEGRADED
    }

    private fun snapshot(accepted: Boolean, transition: Transition?, resets: Set<ResetCause>) =
        ObserverStep(
            accepted = accepted, health = deriveHealth(),
            healthReasons = healthReasons.toSet(), safetyReasons = safetyReasons.toSet(),
            phase = phase, transition = transition,
            candidateId = candidateId, eventId = eventId, livePeak = livePeak,
            quietAccumMin = quietAccumMin, confirmCount = confirmCount, carryDurMin = carryDurMin,
            resetCauses = resets,
        )
}
