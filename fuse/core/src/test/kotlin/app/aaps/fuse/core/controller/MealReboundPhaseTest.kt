package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Halbzeit der Kurve und die Regel "Rebound nach der Anfangsphase" (synthetische Werte). */
class MealReboundPhaseTest {

    private val minute = 60_000L
    private val marker = 1_000_000_000_000L

    /** Synthetische Kurve aus vier Eckwerten: 1 / 2 / 3 / 4 U nach 30 / 60 / 90 / 120 min - gerade Linie. */
    private val gerade = ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)!!

    /** Synthetische Kurve, die spaet ansteigt: 0,5 / 1,5 / 3 / 4 U. */
    private val spaet = ExpectationAccount.Curve.ausEckwerten(0.5, 1.5, 3.0, 4.0)!!

    @Test
    fun `Halbzeit einer geraden Kurve liegt in der Mitte`() {
        assertEquals(60.0, gerade.halfTimeMin(), 1e-9)
    }

    @Test
    fun `Halbzeit folgt der Kurvenform`() {
        // Haelfte = 2 U, zwischen 60 min (1,5) und 90 min (3,0): 60 + 30 * 0,5 / 1,5 = 70 min.
        assertEquals(70.0, spaet.halfTimeMin(), 1e-9)
    }

    @Test
    fun `Halbzeit folgt der Streckung`() {
        assertEquals(105.0, gerade.stretched(1.75).halfTimeMin(), 1e-9)
        assertEquals(30.0, gerade.stretched(0.5).halfTimeMin(), 1e-9)
    }

    @Test
    fun `Halbzeit stimmt mit at ueberein`() {
        for (k in listOf(spaet, spaet.stretched(1.3), gerade.stretched(2.2))) {
            val t = k.halfTimeMin()
            assertEquals(k.endU / 2.0, k.at(t), 1e-9)
        }
    }

    private fun grund(tiefNachMin: Long, a0U: Double = 4.0, restU: Double = 1.0, halbzeit: Double? = 70.0) =
        MealReboundPhase.rearmReason(marker, marker + tiefNachMin * minute, a0U, restU, halbzeit)

    @Test
    fun `Tief in der Anfangsphase laesst die Aufhebung stehen`() {
        assertNull(grund(tiefNachMin = 30))
        assertNull(grund(tiefNachMin = 69))
    }

    @Test
    fun `Tief nach der Halbzeit macht den Rebound wieder scharf`() {
        assertEquals(MealReboundPhase.Reason.AFTER_HALF_TIME, grund(tiefNachMin = 70))
        assertEquals(MealReboundPhase.Reason.AFTER_HALF_TIME, grund(tiefNachMin = 150, restU = 2.0))
    }

    @Test
    fun `leeres Konto nach einem Tief macht den Rebound wieder scharf`() {
        assertEquals(MealReboundPhase.Reason.ACCOUNT_EMPTY, grund(tiefNachMin = 40, restU = 0.0))
        assertEquals(MealReboundPhase.Reason.ACCOUNT_EMPTY, grund(tiefNachMin = 40, restU = MealReboundPhase.EMPTY_REST_U))
        assertNull(grund(tiefNachMin = 40, restU = 0.1))
    }

    @Test
    fun `Tief vor oder am Marker bleibt unveraendert`() {
        assertNull(MealReboundPhase.rearmReason(marker, marker - 5 * minute, 4.0, 0.0, 70.0))
        assertNull(MealReboundPhase.rearmReason(marker, marker, 4.0, 0.0, 70.0))
        assertNull(MealReboundPhase.rearmReason(marker, 0L, 4.0, 0.0, 70.0))
    }

    @Test
    fun `ohne Budget (Nur Marker) bleibt unveraendert`() {
        assertNull(grund(tiefNachMin = 150, a0U = 0.0, restU = 0.0))
        assertNull(grund(tiefNachMin = 150, a0U = MealReboundPhase.EMPTY_REST_U, restU = 0.0))
    }

    @Test
    fun `ohne gueltige Kurve zaehlt nur das leere Konto`() {
        assertNull(grund(tiefNachMin = 150, restU = 1.0, halbzeit = null))
        assertEquals(MealReboundPhase.Reason.ACCOUNT_EMPTY, grund(tiefNachMin = 150, restU = 0.0, halbzeit = null))
        assertNull(grund(tiefNachMin = 150, restU = 1.0, halbzeit = Double.NaN))
    }

    @Test
    fun `kein Marker kein Grund`() {
        assertNull(MealReboundPhase.rearmReason(0L, marker, 4.0, 0.0, 70.0))
        assertNotNull(MealReboundPhase.rearmReason(marker, marker + 80 * minute, 4.0, 1.0, 70.0))
    }
}
