package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucoseStability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Die reine Entscheidung - jede Voraussetzung einzeln. Werte synthetisch. */
class BookingExhaustionExitTest {

    private fun gueltig() = BookingExhaustionExit.Input(
        enabled = true,
        runActive = true,
        gateReason = "REBOUND_ACTIVE",
        exceptionDenial = MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK,
        evidencePhase = EvidenceStock.Phase.DORMANT,
        deductionMgdl = 18.3,
        stockBeforeDeductionMgdl = 12.0,
        declineMgdl = 0.0,
        measuredVerdict = GlucoseStability.Verdict.STABLE,
        ukfRatePerMin = 2.0,
        cause = EvidenceLossDiagnosis.Cause.BOOKING_DEDUCTION,
        hazards = emptyList(),
        otherBlockCause = null,
        manualIntervention = false,
        configChanged = false,
    )

    private fun denial(i: BookingExhaustionExit.Input) = BookingExhaustionExit.decide(i).denial

    @Test
    fun `alle Voraussetzungen erfuellt - keine neue Sperre`() {
        val d = BookingExhaustionExit.decide(gueltig())
        assertTrue(d.skipReArm)
        assertNull(d.denial)
    }

    @Test
    fun `Schalter, laufender Lauf und Rebound-Tor sind Pflicht`() {
        assertEquals(BookingExhaustionExit.Denial.DISABLED, denial(gueltig().copy(enabled = false)))
        assertEquals(BookingExhaustionExit.Denial.RUN_NOT_ACTIVE, denial(gueltig().copy(runActive = false)))
        for (g in listOf(null, "MEASURED_LOW", "FALLING", "EXCLUDED_LAGE", "LEDGER_HOLD"))
            assertEquals(BookingExhaustionExit.Denial.NOT_REBOUND_EXIT, denial(gueltig().copy(gateReason = g)), "$g")
    }

    @Test
    fun `nur ein am Bestand gescheiterter Ausnahmefall`() {
        for (d in MealReboundEvidenceException.Denial.values().filter { it != MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK } + listOf(null))
            assertEquals(BookingExhaustionExit.Denial.EXCEPTION_NOT_NO_STOCK, denial(gueltig().copy(exceptionDenial = d)), "$d")
    }

    @Test
    fun `unvollstaendige Diagnose verweigert`() {
        val unvollstaendig = listOf(
            gueltig().copy(evidencePhase = EvidenceStock.Phase.SUSPENDED),
            gueltig().copy(evidencePhase = null),
            gueltig().copy(deductionMgdl = null),
            gueltig().copy(stockBeforeDeductionMgdl = null),
            gueltig().copy(declineMgdl = null),
            gueltig().copy(measuredVerdict = null),
            gueltig().copy(ukfRatePerMin = Double.NaN),
            gueltig().copy(hazards = null),
        )
        unvollstaendig.forEach { assertEquals(BookingExhaustionExit.Denial.DIAGNOSIS_INCOMPLETE, denial(it), "$it") }
    }

    @Test
    fun `Buchungsursache allein reicht nicht - Gefahr, anderer Torgrund, manuell, Konfiguration`() {
        assertEquals(BookingExhaustionExit.Denial.CAUSE_NOT_BOOKING, denial(gueltig().copy(cause = EvidenceLossDiagnosis.Cause.DECAY_OR_DECLINE)))
        assertEquals(BookingExhaustionExit.Denial.CAUSE_NOT_BOOKING, denial(gueltig().copy(cause = null)))
        for (h in EvidenceLossDiagnosis.Hazard.values())
            assertEquals(BookingExhaustionExit.Denial.CAPTURED_HAZARD, denial(gueltig().copy(hazards = listOf(h))), "$h")
        assertEquals(BookingExhaustionExit.Denial.OTHER_BLOCK_CAUSE, denial(gueltig().copy(otherBlockCause = "EXCLUDED_LAGE")))
        assertEquals(BookingExhaustionExit.Denial.MANUAL_INTERVENTION, denial(gueltig().copy(manualIntervention = true)))
        assertEquals(BookingExhaustionExit.Denial.CONFIG_CHANGED, denial(gueltig().copy(configChanged = true)))
        assertFalse(BookingExhaustionExit.decide(gueltig().copy(configChanged = true)).skipReArm)
    }
}
