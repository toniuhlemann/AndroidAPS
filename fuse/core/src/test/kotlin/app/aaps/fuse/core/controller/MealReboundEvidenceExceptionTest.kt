package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucoseStability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Die reine Entscheidung - jede Bedingung einzeln. Werte synthetisch. */
class MealReboundEvidenceExceptionTest {

    private val marker = 1_700_000_000_000L

    private fun gueltig() = MealReboundEvidenceException.Input(
        enabled = true,
        computeTs = marker + 10 * 60_000L,
        markerTs = marker,
        mealAuthorized = true,
        reboundOverridePinnedForTs = marker,
        reboundOverrideDeadlineTs = marker + 120 * 60_000L,
        lastLowTs = marker - 5 * 60_000L,
        evidencePhase = EvidenceStock.Phase.PENDING_SEAL,
        measuredVerdict = GlucoseStability.Verdict.STABLE,
    )

    private fun denial(i: MealReboundEvidenceException.Input) = MealReboundEvidenceException.decide(i).denial

    @Test
    fun `alle Bedingungen erfuellt - zugelassen`() {
        val d = MealReboundEvidenceException.decide(gueltig())
        assertTrue(d.allowed)
        assertNull(d.denial)
    }

    @Test
    fun `ACTIVE und PENDING_SEAL tragen, DORMANT SUSPENDED UNKNOWN NONE und fehlend nicht`() {
        assertTrue(MealReboundEvidenceException.decide(gueltig().copy(evidencePhase = EvidenceStock.Phase.ACTIVE)).allowed)
        for (p in listOf(
            EvidenceStock.Phase.DORMANT, EvidenceStock.Phase.SUSPENDED,
            EvidenceStock.Phase.UNKNOWN, EvidenceStock.Phase.NONE, null,
        )) assertEquals(MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK, denial(gueltig().copy(evidencePhase = p)), "$p")
    }

    @Test
    fun `die Messlage muss STABLE sein - FALLING und UNDETERMINED verweigern`() {
        assertEquals(
            MealReboundEvidenceException.Denial.MEASURED_NOT_STABLE,
            denial(gueltig().copy(measuredVerdict = GlucoseStability.Verdict.FALLING)),
        )
        assertEquals(
            MealReboundEvidenceException.Denial.MEASURED_NOT_STABLE,
            denial(gueltig().copy(measuredVerdict = GlucoseStability.Verdict.UNDETERMINED)),
        )
        assertEquals(MealReboundEvidenceException.Denial.MEASURED_NOT_STABLE, denial(gueltig().copy(measuredVerdict = null)))
    }

    @Test
    fun `Autorisierung - Schalter, Kontext, Pin und Frist`() {
        assertEquals(MealReboundEvidenceException.Denial.DISABLED, denial(gueltig().copy(enabled = false)))
        assertEquals(MealReboundEvidenceException.Denial.NOT_MEAL_AUTHORIZED, denial(gueltig().copy(mealAuthorized = false)))
        assertEquals(MealReboundEvidenceException.Denial.NOT_MEAL_AUTHORIZED, denial(gueltig().copy(markerTs = 0L)))
        assertEquals(MealReboundEvidenceException.Denial.OVERRIDE_PIN_MISMATCH, denial(gueltig().copy(reboundOverridePinnedForTs = 0L)))
        assertEquals(MealReboundEvidenceException.Denial.OVERRIDE_PIN_MISMATCH, denial(gueltig().copy(reboundOverridePinnedForTs = marker - 1L)))
        assertEquals(MealReboundEvidenceException.Denial.OVERRIDE_EXPIRED, denial(gueltig().copy(reboundOverrideDeadlineTs = 0L)))
        // Halb offen: exakt an der Frist ist das Recht vorbei.
        val g = gueltig()
        assertEquals(MealReboundEvidenceException.Denial.OVERRIDE_EXPIRED, denial(g.copy(computeTs = g.reboundOverrideDeadlineTs)))
        assertTrue(MealReboundEvidenceException.decide(g.copy(computeTs = g.reboundOverrideDeadlineTs - 1L)).allowed)
    }

    @Test
    fun `ein Tief nach oder am Markerdruck ist neue Information`() {
        assertEquals(MealReboundEvidenceException.Denial.LOW_AFTER_AUTHORIZATION, denial(gueltig().copy(lastLowTs = marker)))
        assertEquals(MealReboundEvidenceException.Denial.LOW_AFTER_AUTHORIZATION, denial(gueltig().copy(lastLowTs = marker + 60_000L)))
        assertTrue(MealReboundEvidenceException.decide(gueltig().copy(lastLowTs = marker - 1L)).allowed)
    }

    @Test
    fun `zugelassen ist nicht aufgehoben - das Veto faellt nur im rohen Fenster ohne Sonderrecht`() {
        val ja = MealReboundEvidenceException.Decision(true, null)
        val nein = MealReboundEvidenceException.Decision(false, MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK)
        assertTrue(MealReboundEvidenceException.vetoLifted(reboundRaw = true, evidenceOverride = false, exception = ja))
        assertFalse(MealReboundEvidenceException.vetoLifted(reboundRaw = false, evidenceOverride = false, exception = ja))
        assertFalse(MealReboundEvidenceException.vetoLifted(reboundRaw = true, evidenceOverride = true, exception = ja))
        assertFalse(MealReboundEvidenceException.gateBlocks(true, false, ja))
        assertTrue(MealReboundEvidenceException.gateBlocks(true, false, nein))
        assertFalse(MealReboundEvidenceException.gateBlocks(true, true, nein))
        assertFalse(MealReboundEvidenceException.gateBlocks(false, false, nein))
    }

    @Test
    fun `NoLift-Grund trennt Bedarf, Raster, Deckel und Normalpfad`() {
        assertNull(LivenessNoLiftReason.of(needU = 1.0, candidateU = 0.3, headroomU = 2.0, liveU = 0.3, normalU = 0.05))
        assertEquals(LivenessNoLiftReason.Reason.NORMAL_COVERS, LivenessNoLiftReason.of(1.0, 0.3, 2.0, 0.3, 0.3))
        assertEquals(LivenessNoLiftReason.Reason.NO_NEED, LivenessNoLiftReason.of(0.0, 0.0, 2.0, 0.0, 0.0))
        assertEquals(LivenessNoLiftReason.Reason.BELOW_PUMP_STEP, LivenessNoLiftReason.of(0.1, 0.02, 2.0, 0.0, 0.0))
        assertEquals(LivenessNoLiftReason.Reason.CAP_EXHAUSTED, LivenessNoLiftReason.of(1.0, 0.3, 0.0, 0.0, 0.0))
        assertEquals(LivenessNoLiftReason.Reason.CAP_EXHAUSTED, LivenessNoLiftReason.of(1.0, 0.3, 0.02, 0.0, 0.0))
    }

    // ---- Verlustdiagnose (Tonis Review 14.09. spaet) -------------------------

    private val keineGefahr = EvidenceLossDiagnosis.Observation(
        signalHealthy = true, ledgerHold = false, measuredLow = false, descentRisk = false,
        descentRiskMarker = false, latchBlocksPositive = false, ukfRatePerMin = 2.0,
        ukfFloorPerMin = -0.5, measuredVerdict = GlucoseStability.Verdict.STABLE,
        declineMgdl = 0.0, turningDown = false,
    )

    private fun ursache(abzug: Double?, vorAbzug: Double?) = EvidenceLossDiagnosis.cause(
        MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK, EvidenceStock.Phase.DORMANT, abzug, vorAbzug, stockFloorMgdl = 1.0,
    )

    @Test
    fun `Ursache - Buchung nur wenn der Bestand ohne Abzug ueber der Schwelle stuende`() {
        assertEquals(EvidenceLossDiagnosis.Cause.BOOKING_DEDUCTION, ursache(18.3, 12.0))
        assertEquals(EvidenceLossDiagnosis.Cause.DECAY_OR_DECLINE, ursache(18.3, 0.5))
        assertEquals(EvidenceLossDiagnosis.Cause.DECAY_OR_DECLINE, ursache(0.0, 12.0))
        assertEquals(EvidenceLossDiagnosis.Cause.DECAY_OR_DECLINE, ursache(null, null))
        // Nur fuer den eingeschlafenen Bestand - andere Ablehnungen haben keine Bestandsursache.
        assertNull(EvidenceLossDiagnosis.cause(MealReboundEvidenceException.Denial.MEASURED_NOT_STABLE, EvidenceStock.Phase.DORMANT, 18.3, 12.0, 1.0))
        assertNull(EvidenceLossDiagnosis.cause(MealReboundEvidenceException.Denial.NO_EVIDENCE_STOCK, EvidenceStock.Phase.SUSPENDED, 18.3, 12.0, 1.0))
        assertNull(EvidenceLossDiagnosis.cause(null, EvidenceStock.Phase.DORMANT, 18.3, 12.0, 1.0))
    }

    @Test
    fun `Gefahren - jede einzeln und unabhaengig von der Ursache erfasst`() {
        assertTrue(EvidenceLossDiagnosis.hazards(keineGefahr).isEmpty())
        val einzeln = mapOf(
            EvidenceLossDiagnosis.Hazard.SIGNAL_UNHEALTHY to keineGefahr.copy(signalHealthy = false),
            EvidenceLossDiagnosis.Hazard.LEDGER_HOLD to keineGefahr.copy(ledgerHold = true),
            EvidenceLossDiagnosis.Hazard.MEASURED_LOW to keineGefahr.copy(measuredLow = true),
            EvidenceLossDiagnosis.Hazard.DESCENT_RISK to keineGefahr.copy(descentRisk = true),
            EvidenceLossDiagnosis.Hazard.DESCENT_RISK_MARKER to keineGefahr.copy(descentRiskMarker = true),
            EvidenceLossDiagnosis.Hazard.LATCH_ACTIVE to keineGefahr.copy(latchBlocksPositive = true),
            EvidenceLossDiagnosis.Hazard.FALLING to keineGefahr.copy(ukfRatePerMin = -0.6),
            EvidenceLossDiagnosis.Hazard.MEASURED_NOT_STABLE to keineGefahr.copy(measuredVerdict = GlucoseStability.Verdict.FALLING),
            EvidenceLossDiagnosis.Hazard.EVIDENCE_DECLINE to keineGefahr.copy(declineMgdl = 0.4),
            EvidenceLossDiagnosis.Hazard.TURNING_DOWN to keineGefahr.copy(turningDown = true),
        )
        einzeln.forEach { (erwartet, beob) -> assertEquals(listOf(erwartet), EvidenceLossDiagnosis.hazards(beob), "$erwartet") }
        assertEquals(listOf(EvidenceLossDiagnosis.Hazard.FALLING), EvidenceLossDiagnosis.hazards(keineGefahr.copy(ukfRatePerMin = Double.NaN)))
        assertEquals(
            listOf(EvidenceLossDiagnosis.Hazard.MEASURED_NOT_STABLE),
            EvidenceLossDiagnosis.hazards(keineGefahr.copy(measuredVerdict = GlucoseStability.Verdict.UNDETERMINED)),
        )
    }

    @Test
    fun `Buchung plus Gefahr ist nicht buchungsbedingt ohne Gefahr`() {
        val buchung = ursache(18.3, 12.0)
        assertTrue(EvidenceLossDiagnosis.bookingWithoutCapturedHazard(buchung, emptyList()))
        for (g in listOf(
            keineGefahr.copy(declineMgdl = 2.0),
            keineGefahr.copy(measuredVerdict = GlucoseStability.Verdict.FALLING),
            keineGefahr.copy(descentRisk = true),
        )) assertFalse(EvidenceLossDiagnosis.bookingWithoutCapturedHazard(buchung, EvidenceLossDiagnosis.hazards(g)), "$g")
        assertFalse(EvidenceLossDiagnosis.bookingWithoutCapturedHazard(ursache(0.0, 12.0), emptyList()))
        assertFalse(EvidenceLossDiagnosis.bookingWithoutCapturedHazard(null, emptyList()))
    }
}
