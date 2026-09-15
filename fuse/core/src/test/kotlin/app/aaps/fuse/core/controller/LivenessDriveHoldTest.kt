package app.aaps.fuse.core.controller

import app.aaps.fuse.core.predictor.DriveDecayModel
import org.junit.jupiter.api.Assertions.assertEquals
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
        prev: Int = 1,
        meal: Boolean = true,
        auth: Long = 1_000L,
        prevAuth: Long = 1_000L,
    ) = LivenessDriveHold.Input(enabled, active, meal, auth, prevAuth, drive, fast, decay, decayNeg, 60, horizon, prev)

    @Test
    fun `ohne MEAL-Autorisierung hebt nichts - CORRECTION ist ausgeschlossen`() {
        for (i in listOf(input(meal = false, prev = 5), input(auth = 0L, prevAuth = 0L, prev = 5))) {
            val r = LivenessDriveHold.decide(i)
            assertEquals(LivenessDriveHold.Denial.NOT_MEAL_AUTHORIZED, r.denial)
            assertEquals(0, r.streak)
            assertEquals(0.0, r.upliftMgdl, 0.0)
        }
    }

    @Test
    fun `eine andere Autorisierung setzt die Bestaetigung zurueck`() {
        val r = LivenessDriveHold.decide(input(prev = 7, auth = 2_000L, prevAuth = 1_000L))
        assertEquals(LivenessDriveHold.Denial.NOT_CONFIRMED, r.denial)
        assertEquals(1, r.streak)
        assertEquals(2_000L, r.authorizationId)
        assertEquals(0.0, r.upliftMgdl, 0.0)
        // Dieselbe Autorisierung fuehrt die Folge fort.
        val weiter = LivenessDriveHold.decide(input(prev = r.streak, auth = 2_000L, prevAuth = r.authorizationId))
        assertNull(weiter.denial)
        assertEquals(2, weiter.streak)
    }

    @Test
    fun `das Zusatzgewicht ist die Differenz Halten minus Abklingen`() {
        val expSum = (1..60).sumOf { exp(-it / 60.0) }
        val holdSum = 20.0 + (21..60).sumOf { exp(-(it - 20) / 60.0) }
        assertEquals(holdSum - expSum, LivenessDriveHold.extraWeight(60, 60.0), 1e-9)
        // Horizont kuerzer als die Haltezeit: gehalten wird nur bis zum Horizont.
        assertEquals((1..10).sumOf { 1.0 - exp(-it / 60.0) }, LivenessDriveHold.extraWeight(10, 60.0), 1e-9)
        assertEquals(0.0, LivenessDriveHold.extraWeight(0, 60.0), 1e-12)
    }

    @Test
    fun `bestaetigter Anstieg hebt um Antrieb mal Zusatzgewicht`() {
        val r = LivenessDriveHold.decide(input(drive = 2.0, fast = 2.0, prev = 1))
        assertNull(r.denial)
        assertEquals(2, r.streak)
        assertEquals(2.0 * LivenessDriveHold.extraWeight(60, 60.0), r.upliftMgdl, 1e-9)
    }

    @Test
    fun `die Anhebung ist gekappt`() {
        val r = LivenessDriveHold.decide(input(drive = 10.0, fast = 12.0, prev = 5))
        assertEquals(LivenessDriveHold.UPLIFT_CAP_MGDL, r.upliftMgdl, 1e-12)
    }

    @Test
    fun `erster bestaetigter Zyklus hebt noch nicht`() {
        val r = LivenessDriveHold.decide(input(prev = 0))
        assertEquals(LivenessDriveHold.Denial.NOT_CONFIRMED, r.denial)
        assertEquals(1, r.streak)
        assertEquals(0.0, r.upliftMgdl, 0.0)
    }

    @Test
    fun `gemessene Rate unter dem Antrieb bricht die Folge`() {
        val r = LivenessDriveHold.decide(input(drive = 2.0, fast = 1.99, prev = 7))
        assertEquals(LivenessDriveHold.Denial.FAST_BELOW_DRIVE, r.denial)
        assertEquals(0, r.streak)
        assertEquals(0.0, r.upliftMgdl, 0.0)
    }

    @Test
    fun `jede Ablehnung nullt Anhebung und Folge`() {
        val faelle = listOf(
            input(enabled = false, prev = 5) to LivenessDriveHold.Denial.DISABLED,
            input(active = false, prev = 5) to LivenessDriveHold.Denial.NOT_ACTIVE,
            input(decay = DriveDecayModel.ExponentialDecay(15.0), prev = 5) to LivenessDriveHold.Denial.DECAY_NOT_BASELINE,
            input(decayNeg = DriveDecayModel.ExponentialDecay(60.0), prev = 5) to LivenessDriveHold.Denial.DECAY_NOT_BASELINE,
            input(decay = DriveDecayModel.HoldThenExponentialDecay(10.0, 60.0), prev = 5) to LivenessDriveHold.Denial.DECAY_NOT_BASELINE,
            input(horizon = 0, prev = 5) to LivenessDriveHold.Denial.HORIZON_INVALID,
            input(drive = 0.0, prev = 5) to LivenessDriveHold.Denial.DRIVE_NOT_POSITIVE,
            input(drive = -1.0, fast = 3.0, prev = 5) to LivenessDriveHold.Denial.DRIVE_NOT_POSITIVE,
            input(drive = Double.NaN, prev = 5) to LivenessDriveHold.Denial.DRIVE_NOT_POSITIVE,
            input(fast = null, prev = 5) to LivenessDriveHold.Denial.FAST_DRIVE_MISSING,
            input(fast = Double.POSITIVE_INFINITY, prev = 5) to LivenessDriveHold.Denial.FAST_DRIVE_MISSING,
        )
        for ((i, erwartet) in faelle) {
            val r = LivenessDriveHold.decide(i)
            assertEquals(erwartet, r.denial, "$i")
            assertEquals(0, r.streak, "$i")
            assertEquals(0.0, r.upliftMgdl, 0.0, "$i")
        }
    }

    @Test
    fun `die Anhebung ist nie negativ und waechst nicht ueber den gehaltenen Antrieb`() {
        for (d in listOf(0.01, 0.5, 1.0, 2.5, 3.5)) {
            val r = LivenessDriveHold.decide(input(drive = d, fast = d, prev = 3))
            assertTrue(r.upliftMgdl >= 0.0)
            // Nie mehr als ein ueber den ganzen Horizont ungedaempfter Antrieb.
            assertTrue(r.upliftMgdl <= d * (60.0 - (1..60).sumOf { exp(-it / 60.0) }) + 1e-9)
        }
    }
}
