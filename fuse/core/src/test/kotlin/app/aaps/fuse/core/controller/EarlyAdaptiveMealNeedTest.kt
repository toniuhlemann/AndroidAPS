package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Frueher adaptiver MEAL-Bedarf (H8) als reine Entscheidung - synthetische Zahlen. */
class EarlyAdaptiveMealNeedTest {

    private val marker = 1_000_000_000_000L
    private val min = 60_000L

    private fun input(
        horizon: Int = 8,
        meal: Boolean = true,
        authId: Long = marker,
        markerTs: Long = marker,
        expiresAt: Long = marker + 120 * min,
        now: Long = marker + 20 * min,
        ready: Boolean = true,
        mature: Boolean = true,
        w10: Double? = 2.0,
        ukf: Double = 0.4,
        threshold: Double = LivenessChannel.R_MIN_MGDL_PER_MIN,
        target: Double = 100.0,
    ) = EarlyAdaptiveMealNeed.Input(horizon, meal, authId, markerTs, expiresAt, now, ready, mature, w10, ukf, threshold, target)

    private fun denial(i: EarlyAdaptiveMealNeed.Input) = EarlyAdaptiveMealNeed.decide(i).denial

    // ---- B: Eintrittsgrenzen ------------------------------------------------

    @Test
    fun `Marker plus 9_59 ist gesperrt, plus 10_00 zulaessig`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.MARKER_TOO_YOUNG, denial(input(now = marker + 10 * min - 1_000L)))
        assertNull(denial(input(now = marker + 10 * min)))
    }

    @Test
    fun `Marker plus 45_00 ist zulaessig, danach gesperrt`() {
        assertNull(denial(input(now = marker + 45 * min)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.MARKER_TOO_OLD, denial(input(now = marker + 45 * min + 1L)))
    }

    @Test
    fun `fehlende Autorisierung sperrt`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.NOT_MEAL_AUTHORIZED, denial(input(meal = false)))
    }

    @Test
    fun `falsche oder fehlende Kennung sperrt`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.AUTHORIZATION_MISMATCH, denial(input(authId = marker - 5 * min)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.AUTHORIZATION_MISMATCH, denial(input(authId = 0L)))
    }

    @Test
    fun `abgelaufene Autorisierung sperrt, halb offen an der Frist`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.AUTHORIZATION_EXPIRED, denial(input(expiresAt = marker + 20 * min)))
        assertNull(denial(input(expiresAt = marker + 20 * min + 1L)))
    }

    @Test
    fun `CORRECTION-Kontext sperrt - dort ist mealAuthorized false`() {
        // Der Kontext wird ausschliesslich ueber [DosingContext] getragen.
        val korrektur = DosingContext.decide(nowMs = marker + 20 * min, markerTs = marker, pinnedFor = marker, deadlineTs = marker + 15 * min)
        assertFalse(korrektur.mealAuthorized)
        assertEquals(
            EarlyAdaptiveMealNeed.Denial.NOT_MEAL_AUTHORIZED,
            denial(input(meal = korrektur.mealAuthorized, expiresAt = korrektur.authorizationExpiresAt)),
        )
    }

    @Test
    fun `die Eintrittsschwelle ist die Liveness-Druckschwelle 1,0`() {
        assertEquals(1.0, LivenessChannel.R_MIN_MGDL_PER_MIN, 0.0)
    }

    @Test
    fun `W10 0,99 sperrt, 1,00 ist zulaessig`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.DRIVE_BELOW_THRESHOLD, denial(input(w10 = 0.99)))
        assertNull(denial(input(w10 = 1.00)))
        // Zwischen 1,0 und riseRampLowR (live 1,5) grundsaetzlich zulaessig.
        assertNull(denial(input(w10 = 1.25)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.DRIVE_UNAVAILABLE, denial(input(w10 = null)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.DRIVE_UNAVAILABLE, denial(input(w10 = Double.NaN)))
    }

    @Test
    fun `negative UKF sperrt, null ist zulaessig`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.UKF_NEGATIVE, denial(input(ukf = -0.01)))
        assertNull(denial(input(ukf = 0.0)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.UKF_UNAVAILABLE, denial(input(ukf = Double.NaN)))
    }

    @Test
    fun `unreifes oder ungesundes Signal sperrt`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.SIGNAL_NOT_READY, denial(input(ready = false)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.SIGNAL_NOT_MATURE, denial(input(mature = false)))
    }

    @Test
    fun `Schalter AUS und unzulaessige Horizonte sperren`() {
        assertEquals(EarlyAdaptiveMealNeed.Denial.DISABLED, denial(input(horizon = 0)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.DISABLED, denial(input(horizon = 7)))
        assertEquals(EarlyAdaptiveMealNeed.Denial.DISABLED, denial(input(horizon = 60)))
    }

    @Test
    fun `nur 0, 6, 8 und 10 sind wirksam - alles andere faellt auf AUS`() {
        assertEquals(listOf(0, 6, 8, 10, 0, 0, 0, 0), listOf(0, 6, 8, 10, -1, 7, 60, Int.MAX_VALUE).map(EarlyAdaptiveMealNeed::effectiveHorizonMin))
        assertEquals("EARLY_ADAPTIVE_MEAL_H8", EarlyAdaptiveMealNeed.sourceId(8))
    }

    // ---- C: Mengenvertrag ---------------------------------------------------

    @Test
    fun `Bruttobahn ist target plus W10 mal H`() {
        val d = EarlyAdaptiveMealNeed.decide(input(w10 = 2.5, target = 100.0))
        assertTrue(d.active)
        assertEquals(120.0, d.earlyReleaseMeanMgdl!!, 1e-12)
        assertEquals(20.0, d.markerAgeMin!!, 1e-12)
        // H6 und H10 sind dieselbe Formel mit anderem Horizont.
        assertEquals(115.0, EarlyAdaptiveMealNeed.decide(input(horizon = 6, w10 = 2.5)).earlyReleaseMeanMgdl!!, 1e-12)
        assertEquals(125.0, EarlyAdaptiveMealNeed.decide(input(horizon = 10, w10 = 2.5)).earlyReleaseMeanMgdl!!, 1e-12)
    }

    @Test
    fun `Zusammenfuehrung ist max, nie Summe`() {
        val d = EarlyAdaptiveMealNeed.decide(input(w10 = 2.5, target = 100.0))
        assertEquals(130.0, EarlyAdaptiveMealNeed.effectiveReleaseMean(130.0, d), 1e-12)
        assertEquals(120.0, EarlyAdaptiveMealNeed.effectiveReleaseMean(95.0, d), 1e-12)
        val aus = EarlyAdaptiveMealNeed.decide(input(horizon = 0))
        assertEquals(95.0, EarlyAdaptiveMealNeed.effectiveReleaseMean(95.0, aus), 0.0)
    }

    @Test
    fun `der Horizont ist H, nicht der globale Release-Horizont`() {
        val d = EarlyAdaptiveMealNeed.decide(input(w10 = 2.0, target = 100.0))
        assertEquals(116.0, d.earlyReleaseMeanMgdl!!, 1e-12)
        // Ein Rueckfall auf 60 min ergaebe 220 mg/dl.
        assertTrue(d.earlyReleaseMeanMgdl!! < 100.0 + 2.0 * 60 - 1.0)
    }
}
