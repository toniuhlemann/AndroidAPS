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
}
