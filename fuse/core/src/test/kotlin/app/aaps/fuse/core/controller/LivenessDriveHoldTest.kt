package app.aaps.fuse.core.controller

import app.aaps.fuse.core.predictor.DriveDecayModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

/** Die Halte-Anhebung als reine Rechnung - synthetische Zahlen. */
class LivenessDriveHoldTest {

    private fun input(
        enabled: Boolean = true,
        active: Boolean = true,
        drive: Double = 2.0,
        fast: Double? = 2.2,
        decay: DriveDecayModel = DriveDecayModel.ExponentialDecay(60.0),
        decayNeg: DriveDecayModel? = null,
        horizon: Int = 60,
        meal: Boolean = true,
        bestaetigt: Boolean = true,
    ) = LivenessDriveHold.Input(enabled, active, meal, drive, fast, decay, decayNeg, 60, horizon, bestaetigt)

    @Test
    fun `ohne MEAL-Autorisierung hebt nichts - CORRECTION ist ausgeschlossen`() {
        val r = LivenessDriveHold.decide(input(meal = false))
        assertEquals(LivenessDriveHold.Denial.NOT_MEAL_AUTHORIZED, r.denial)
        assertEquals(0.0, r.upliftMgdl, 0.0)
    }

    @Test
    fun `ohne Messbestaetigung hebt nichts - auch bei erfuelltem Bedarf`() {
        val r = LivenessDriveHold.decide(input(bestaetigt = false))
        assertEquals(LivenessDriveHold.Denial.MEASURED_NOT_CONFIRMED, r.denial)
        assertEquals(0.0, r.upliftMgdl, 0.0)
    }

    @Test
    fun `der Bedarf ist Antrieb positiv und bereinigte Rate mindestens Antrieb`() {
        assertTrue(LivenessDriveHold.needMet(2.0, 2.0))
        assertFalse(LivenessDriveHold.needMet(2.0, 1.99))
        assertFalse(LivenessDriveHold.needMet(0.0, 3.0))
        assertFalse(LivenessDriveHold.needMet(2.0, null))
        assertFalse(LivenessDriveHold.needMet(Double.NaN, 3.0))
    }

    @Test
    fun `das Zusatzgewicht ist die Differenz Halten minus Abklingen`() {
        val expSum = (1..60).sumOf { exp(-it / 60.0) }
        val holdSum = 20.0 + (21..60).sumOf { exp(-(it - 20) / 60.0) }
        assertEquals(holdSum - expSum, LivenessDriveHold.extraWeight(60, 60.0), 1e-9)
        assertEquals((1..10).sumOf { 1.0 - exp(-it / 60.0) }, LivenessDriveHold.extraWeight(10, 60.0), 1e-9)
        assertEquals(0.0, LivenessDriveHold.extraWeight(0, 60.0), 1e-12)
    }

    @Test
    fun `bestaetigter Anstieg hebt um Antrieb mal Zusatzgewicht`() {
        val r = LivenessDriveHold.decide(input(drive = 2.0, fast = 2.0))
        assertNull(r.denial)
        assertEquals(2.0 * LivenessDriveHold.extraWeight(60, 60.0), r.upliftMgdl, 1e-9)
    }

    @Test
    fun `die Anhebung ist gekappt`() {
        val r = LivenessDriveHold.decide(input(drive = 10.0, fast = 12.0))
        assertEquals(LivenessDriveHold.UPLIFT_CAP_MGDL, r.upliftMgdl, 1e-12)
    }

    @Test
    fun `gemessene Rate unter dem Antrieb hebt nicht - auch bestaetigt`() {
        val r = LivenessDriveHold.decide(input(drive = 2.0, fast = 1.99))
        assertEquals(LivenessDriveHold.Denial.FAST_BELOW_DRIVE, r.denial)
        assertEquals(0.0, r.upliftMgdl, 0.0)
    }

    @Test
    fun `jede Ablehnung nullt die Anhebung`() {
        val faelle = listOf(
            input(enabled = false) to LivenessDriveHold.Denial.DISABLED,
            input(active = false) to LivenessDriveHold.Denial.NOT_ACTIVE,
            input(decay = DriveDecayModel.ExponentialDecay(15.0)) to LivenessDriveHold.Denial.DECAY_NOT_BASELINE,
            input(decayNeg = DriveDecayModel.ExponentialDecay(60.0)) to LivenessDriveHold.Denial.DECAY_NOT_BASELINE,
            input(decay = DriveDecayModel.HoldThenExponentialDecay(10.0, 60.0)) to LivenessDriveHold.Denial.DECAY_NOT_BASELINE,
            input(horizon = 0) to LivenessDriveHold.Denial.HORIZON_INVALID,
            input(drive = 0.0) to LivenessDriveHold.Denial.DRIVE_NOT_POSITIVE,
            input(drive = -1.0, fast = 3.0) to LivenessDriveHold.Denial.DRIVE_NOT_POSITIVE,
            input(drive = Double.NaN) to LivenessDriveHold.Denial.DRIVE_NOT_POSITIVE,
            input(fast = null) to LivenessDriveHold.Denial.FAST_DRIVE_MISSING,
            input(fast = Double.POSITIVE_INFINITY) to LivenessDriveHold.Denial.FAST_DRIVE_MISSING,
        )
        for ((i, erwartet) in faelle) {
            val r = LivenessDriveHold.decide(i)
            assertEquals(erwartet, r.denial, "$i")
            assertEquals(0.0, r.upliftMgdl, 0.0, "$i")
        }
    }

    @Test
    fun `die Anhebung ist nie negativ und waechst nicht ueber den gehaltenen Antrieb`() {
        for (d in listOf(0.01, 0.5, 1.0, 2.5, 3.5)) {
            val r = LivenessDriveHold.decide(input(drive = d, fast = d))
            assertTrue(r.upliftMgdl >= 0.0)
            assertTrue(r.upliftMgdl <= d * (60.0 - (1..60).sumOf { exp(-it / 60.0) }) + 1e-9)
        }
    }
}
