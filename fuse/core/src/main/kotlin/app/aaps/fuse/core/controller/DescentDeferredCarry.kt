package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * Der kontrollierte Uebertrag einer durch den gemessenen Abwaertsriegel
 * verpassten Phase-A-Versorgung.
 *
 * WICHTIG: Ein blockierter Prime-Vorschlag ist kein Verbrauchsereignis. Seine
 * offene Menge erscheint in jedem Folgezyklus erneut; sie pro Zyklus zu
 * addieren wuerde denselben Rueckstand mehrfach buchen. [observe] speichert
 * deshalb nur den Teil des Phase-A-Rueckstands, der bei maximal moeglicher
 * Restkadenz vor der Uebergabe nicht mehr aufgeholt werden kann.
 *
 * Das ist bewusst konservativ: andere Nullgruende, ausgefallene Zyklen oder
 * kleinere reale Vorschlaege werden nicht dem Abwaertsriegel zugerechnet. Der
 * Riegel bleibt hart; erst [eligibility] entscheidet in Phase B, ob der
 * gespeicherte Betrag nach bestaetigter Erholung wieder zur Erlaubnis gehoert.
 */
object DescentDeferredCarry {

    private const val CYCLE_MS = 60_000L

    enum class Eligibility {
        ELIGIBLE,
        NO_DEFERRED,
        NOT_PHASE_B,
        LATCH_ACTIVE,
        SIGNAL_UNHEALTHY,
        MEASURED_LOW,
        REBOUND_ACTIVE,
    }

    data class Allocation(
        val phaseAShortfallU: Double,
        val effectiveTransportCarryU: Double,
        val effectiveDescentCarryU: Double,
    ) {
        val totalEffectiveCarryU: Double
            get() = effectiveTransportCarryU + effectiveDescentCarryU

        companion object {
            fun none() = Allocation(0.0, 0.0, 0.0)
        }
    }

    /**
     * Fortschreibung waehrend Phase A. Nur ein FINALER
     * `MEASURED_DESCENT_RISK`-Block darf den Wert erhoehen.
     */
    fun observe(
        currentU: Double,
        phase: MealFoundation.Phase,
        blockedByMeasuredDescent: Boolean,
        phaseABudgetU: Double,
        deliveredPhaseAU: Double,
        nowTs: Long,
        handoverTs: Long,
        maxPositivePerCycleU: Double,
    ): Double {
        if (!currentU.isFinite() || currentU < 0.0) return 0.0
        if (phase != MealFoundation.Phase.PHASE_A || !blockedByMeasuredDescent) return currentU
        if (!phaseABudgetU.isFinite() || phaseABudgetU <= 0.0) return currentU
        if (!deliveredPhaseAU.isFinite() || deliveredPhaseAU < 0.0) return currentU
        if (!maxPositivePerCycleU.isFinite() || maxPositivePerCycleU <= 0.0) return currentU
        if (nowTs <= 0L || handoverTs <= nowTs) return currentU

        // Nur Zyklen STRICT vor der Uebergabe gehoeren noch zu Phase A.
        // `delta - 1` macht die Kante exakt: bei delta == 60 s gibt es keinen
        // weiteren Phase-A-Zyklus, bei delta == 60 s + 1 ms genau einen.
        val futurePhaseASlots = (handoverTs - nowTs - 1L) / CYCLE_MS
        val optimisticRecoverableU = futurePhaseASlots * maxPositivePerCycleU
        val shortfallU = max(0.0, phaseABudgetU - deliveredPhaseAU)
        val newlyUnavoidableU = max(0.0, shortfallU - optimisticRecoverableU)
        return max(currentU, min(newlyUnavoidableU, phaseABudgetU))
    }

    /**
     * Aktuelle Sicherheitsentscheidung fuer den gespeicherten Uebertrag.
     * Die Reihenfolge ist absichtlich typisiert und exportierbar.
     */
    fun eligibility(
        deferredU: Double,
        phase: MealFoundation.Phase,
        latchBlocksPositive: Boolean,
        signalHealthy: Boolean,
        measuredLow: Boolean,
        reboundRaw: Boolean,
    ): Eligibility = when {
        !deferredU.isFinite() || deferredU <= 0.0 -> Eligibility.NO_DEFERRED
        phase != MealFoundation.Phase.PHASE_B -> Eligibility.NOT_PHASE_B
        latchBlocksPositive -> Eligibility.LATCH_ACTIVE
        !signalHealthy -> Eligibility.SIGNAL_UNHEALTHY
        measuredLow -> Eligibility.MEASURED_LOW
        reboundRaw -> Eligibility.REBOUND_ACTIVE
        else -> Eligibility.ELIGIBLE
    }

    /**
     * Verteilt den noch offenen Phase-A-Rueckstand ohne Doppelzaehlung.
     * Der Transportbeweis hat Vorrang; der Sicherheitsuebertrag bekommt nur
     * den danach verbleibenden Rueckstand. Beide zusammen koennen nie mehr als
     * die tatsaechlich noch fehlende Phase-A-Menge freigeben.
     */
    fun allocate(
        phaseABudgetU: Double,
        deliveredPhaseAU: Double,
        confirmedNotSentPhaseAU: Double,
        descentDeferredPhaseAU: Double,
        descentEligibility: Eligibility,
    ): Allocation {
        if (!phaseABudgetU.isFinite() || phaseABudgetU < 0.0) return Allocation.none()
        if (!deliveredPhaseAU.isFinite() || deliveredPhaseAU < 0.0) return Allocation.none()
        if (!confirmedNotSentPhaseAU.isFinite() || confirmedNotSentPhaseAU < 0.0) return Allocation.none()
        if (!descentDeferredPhaseAU.isFinite() || descentDeferredPhaseAU < 0.0) return Allocation.none()

        val shortfallU = max(0.0, phaseABudgetU - deliveredPhaseAU)
        val transportU = min(confirmedNotSentPhaseAU, shortfallU)
        val remainingAfterTransportU = max(0.0, shortfallU - transportU)
        val descentU = if (descentEligibility == Eligibility.ELIGIBLE)
            min(descentDeferredPhaseAU, remainingAfterTransportU)
        else 0.0
        return Allocation(shortfallU, transportU, descentU)
    }
}
