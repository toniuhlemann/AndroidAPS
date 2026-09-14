package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucoseStability

/**
 * WARUM DIE REBOUND-EVIDENZ-AUSNAHME AM BESTAND SCHEITERT - UND WELCHE
 * GEFAHREN ZUGLEICH ANLIEGEN. REINE DIAGNOSE, NIE DOSIERWIRKSAM.
 *
 * Tonis Review 14.09. spaet: `BOOKING_DEDUCTION` heisst nur "ohne den
 * Buchungsabzug dieses Zyklus stuende der Bestand noch ueber der Schwelle".
 * Es heisst NICHT "ausschliesslich Buchung, keine Gefahr":
 *  - ein gleichzeitiger Rueckgang kann beteiligt sein;
 *  - die Ausnahme prueft den Bestand VOR der Messlage, und das Liveness-Tor
 *    meldet REBOUND_ACTIVE VOR Tief, Abwaertsrisiko, Riegel und Fallen - die
 *    Ablehnungskette verdeckt also gleichzeitige Gefahren.
 *
 * Deshalb werden Ursache und Gefahren UNABHAENGIG von beiden Reihenfolgen
 * erfasst. Das Label allein darf keine Wiederanlaufsperre aufheben; ob eine
 * Lage "nur Buchung, keine Gefahr" ist, sagt erst [bookingWithoutHazard].
 */
object EvidenceLossDiagnosis {

    enum class Cause {
        /** Ohne den Buchungsabzug dieses Zyklus stuende der Bestand ueber der Schwelle. */
        BOOKING_DEDUCTION,

        /** Auch ohne Abzug unter der Schwelle: Verfall oder Rueckgang. */
        DECAY_OR_DECLINE,
    }

    /** Gefahren in fester Reihenfolge - unabhaengig von der Torreihenfolge. */
    enum class Hazard {
        SIGNAL_UNHEALTHY,
        LEDGER_HOLD,
        MEASURED_LOW,
        DESCENT_RISK,
        DESCENT_RISK_MARKER,
        LATCH_ACTIVE,
        FALLING,
        MEASURED_NOT_STABLE,
        EVIDENCE_DECLINE,
        TURNING_DOWN,
    }

    /** null = die Ausnahme scheitert nicht an einem eingeschlafenen Bestand. */
    fun cause(
        denial: MealReboundEvidenceException.Denial?,
        phase: EvidenceStock.Phase?,
        deductionMgdl: Double?,
        stockBeforeDeductionMgdl: Double?,
        stockFloorMgdl: Double,
    ): Cause? {
        if (denial != MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK || phase != EvidenceStock.Phase.DORMANT) return null
        return if ((deductionMgdl ?: 0.0) > 0.0 && (stockBeforeDeductionMgdl ?: 0.0) >= stockFloorMgdl)
            Cause.BOOKING_DEDUCTION else Cause.DECAY_OR_DECLINE
    }

    data class Observation(
        val signalHealthy: Boolean,
        val ledgerHold: Boolean,
        val measuredLow: Boolean,
        val descentRisk: Boolean,
        val descentRiskMarker: Boolean,
        val latchBlocksPositive: Boolean,
        /** UKF-Rate [mg/dl/min]; nicht endlich zaehlt als fallend (wie am Tor). */
        val ukfRatePerMin: Double,
        val ukfFloorPerMin: Double,
        val measuredVerdict: GlucoseStability.Verdict?,
        val declineMgdl: Double?,
        val turningDown: Boolean,
    )

    fun hazards(o: Observation): List<Hazard> = buildList {
        if (!o.signalHealthy) add(Hazard.SIGNAL_UNHEALTHY)
        if (o.ledgerHold) add(Hazard.LEDGER_HOLD)
        if (o.measuredLow) add(Hazard.MEASURED_LOW)
        if (o.descentRisk) add(Hazard.DESCENT_RISK)
        if (o.descentRiskMarker) add(Hazard.DESCENT_RISK_MARKER)
        if (o.latchBlocksPositive) add(Hazard.LATCH_ACTIVE)
        if (!o.ukfRatePerMin.isFinite() || o.ukfRatePerMin < o.ukfFloorPerMin) add(Hazard.FALLING)
        if (o.measuredVerdict != GlucoseStability.Verdict.STABLE) add(Hazard.MEASURED_NOT_STABLE)
        if ((o.declineMgdl ?: 0.0) > 0.0) add(Hazard.EVIDENCE_DECLINE)
        if (o.turningDown) add(Hazard.TURNING_DOWN)
    }

    /** Buchungsbedingter Verlust UND keine einzige gleichzeitige Gefahr. */
    fun bookingWithoutHazard(cause: Cause?, hazards: List<Hazard>): Boolean =
        cause == Cause.BOOKING_DEDUCTION && hazards.isEmpty()
}
