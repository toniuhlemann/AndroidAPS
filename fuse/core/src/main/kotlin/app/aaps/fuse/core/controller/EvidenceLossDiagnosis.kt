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
 * erfasst. Das Label allein darf keine Wiederanlaufsperre aufheben.
 *
 * Konsolidierung 2.0: die Rebound-Evidenz-Ausnahme ist entfallen und mit ihr die Verlustursache und
 * `bookingWithoutCapturedHazard`. Es bleibt die Gefahrenliste - unabhaengig von Tor- und Ablehnungsreihenfolge
 * erfasst, rein beschreibend.
 */
object EvidenceLossDiagnosis {

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
}
