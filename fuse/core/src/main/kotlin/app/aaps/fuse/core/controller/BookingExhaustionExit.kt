package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucoseStability

/**
 * DARF EIN BUCHUNGSBEDINGT ERSCHOEPFTER LIVENESS-LAUF OHNE NEUE
 * WIEDERANLAUFSPERRE ENDEN? (Tonis Vertrag 15.09., Experiment, Default AUS)
 *
 * ANLASS: nach einem grossen Lift zieht der Buchungsabzug den Evidenzbestand
 * fuer einen Zyklus unter die Schwelle. Das rohe Rebound-Veto beendet den Lauf
 * dann mit REBOUND_ACTIVE und setzt die zeitliche Sperre - obwohl der Bestand
 * sofort wieder waechst und keine Gefahr anliegt. Im Rig verhinderte genau
 * diese Sperre eine sonst vollstaendig zulaessige fruehe Abgabe.
 *
 * DER VERTRAG:
 *  1. Der Lauf ENDET trotzdem: im Abbruchzyklus bleibt die Ausnahme
 *     verweigert, es wird nicht mit leerem Bestand weiterdosiert.
 *  2. Aktiver Zustand und Bestaetigungsserie werden zurueckgesetzt.
 *  3. Nur die NEUE Sperre entfaellt; bestehende Sperren bleiben unveraendert.
 *  4. Ein spaeterer Wiederanlauf muss Autorisierung, Evidenz, Messlage,
 *     Modell, Behandlungssicht, alle Tore und die Bestaetigung neu bestehen -
 *     das leistet der unveraenderte Runner.
 *  5. Evidenzabzug, Exposure/IOB, Foundation und Mengen bleiben unveraendert.
 *  6. Unvollstaendige Diagnose oder jede weitere Sperrursache verweigert die
 *     Sonderbehandlung. [EvidenceLossDiagnosis.bookingWithoutCapturedHazard]
 *     allein reicht ausdruecklich NICHT: andere Torgruende, manuelle
 *     Intervention und Konfigurationswechsel werden getrennt verlangt.
 *
 * Jeden Zyklus neu, ohne Gedaechtnis. Die Entscheidung setzt nur KEINE Sperre -
 * sie hebt nie eine auf.
 */
object BookingExhaustionExit {

    /** Reihenfolge = Pruefreihenfolge. */
    enum class Denial {
        DISABLED,
        RUN_NOT_ACTIVE,
        NOT_REBOUND_EXIT,
        EXCEPTION_NOT_NO_STOCK,
        DIAGNOSIS_INCOMPLETE,
        CAUSE_NOT_BOOKING,
        CAPTURED_HAZARD,
        OTHER_BLOCK_CAUSE,
        MANUAL_INTERVENTION,
        CONFIG_CHANGED,
    }

    data class Input(
        val enabled: Boolean,
        val runActive: Boolean,
        /** Der Grund, mit dem das harte Tor diesen Zyklus sperrt (null = offen). */
        val gateReason: String?,
        val exceptionDenial: MealReboundEvidenceException.Denial?,
        val evidencePhase: EvidenceStock.Phase?,
        val deductionMgdl: Double?,
        val stockBeforeDeductionMgdl: Double?,
        val declineMgdl: Double?,
        val measuredVerdict: GlucoseStability.Verdict?,
        val ukfRatePerMin: Double,
        val cause: EvidenceLossDiagnosis.Cause?,
        val hazards: List<EvidenceLossDiagnosis.Hazard>?,
        /** Was das Tor OHNE die Rebound-Zeile melden wuerde (null = nichts). */
        val otherBlockCause: String?,
        /** Ein manueller Bolus haette den Lauf beendet oder sperrt die Bewaffnung. */
        val manualIntervention: Boolean,
        val configChanged: Boolean,
    )

    data class Decision(val skipReArm: Boolean, val denial: Denial?)

    const val REBOUND_GATE_REASON = "REBOUND_ACTIVE"

    fun decide(i: Input): Decision {
        fun nein(d: Denial) = Decision(false, d)
        if (!i.enabled) return nein(Denial.DISABLED)
        if (!i.runActive) return nein(Denial.RUN_NOT_ACTIVE)
        if (i.gateReason != REBOUND_GATE_REASON) return nein(Denial.NOT_REBOUND_EXIT)
        if (i.exceptionDenial != MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK) return nein(Denial.EXCEPTION_NOT_NO_STOCK)
        if (i.evidencePhase != EvidenceStock.Phase.DORMANT || i.deductionMgdl == null || i.stockBeforeDeductionMgdl == null ||
            i.declineMgdl == null || i.measuredVerdict == null || !i.ukfRatePerMin.isFinite() || i.hazards == null
        ) return nein(Denial.DIAGNOSIS_INCOMPLETE)
        if (i.cause != EvidenceLossDiagnosis.Cause.BOOKING_DEDUCTION) return nein(Denial.CAUSE_NOT_BOOKING)
        if (i.hazards.isNotEmpty()) return nein(Denial.CAPTURED_HAZARD)
        if (i.otherBlockCause != null) return nein(Denial.OTHER_BLOCK_CAUSE)
        if (i.manualIntervention) return nein(Denial.MANUAL_INTERVENTION)
        if (i.configChanged) return nein(Denial.CONFIG_CHANGED)
        return Decision(true, null)
    }
}
