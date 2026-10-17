package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Erklaerte Absorption als BEDARF (Toni 09.08.): der Marker wirkt auf der
 * Mittelbahn, nicht auf der Sicherheitsbahn - und verbraucht sich an dem,
 * was die Episode schon geliefert hat.
 */
class DeclaredAbsorptionTest {

    private val isf = 95.0

    @Test
    fun `frischer Marker erzeugt den vollen erwarteten Anstieg`() {
        // Stufe M: 1,2 U * 95 / 60 min = 1,9 mg/dl/min
        assertEquals(1.9, MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 0.0, isf), 1e-9)
    }

    @Test
    fun `gelieferte Menge verbraucht den Kredit - EINE Huelle fuer beide Pfade`() {
        val voll = MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 0.0, isf)
        val halb = MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 0.6, isf)
        assertEquals(voll / 2.0, halb, 1e-9)
        // Huelle aufgebraucht -> kein Kredit mehr, egal wie viel darueber.
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 1.2, isf), 0.0)
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 5.0, isf), 0.0)
    }

    @Test
    fun `Monotonie - mehr geliefert heisst nie mehr Kredit`() {
        var prev = Double.MAX_VALUE
        for (d in listOf(0.0, 0.1, 0.3, 0.6, 0.9, 1.2, 2.0)) {
            val v = MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, d, isf)
            assertTrue(v <= prev + 1e-12) { "Kredit stieg bei geliefert=$d" }
            prev = v
        }
    }

    @Test
    fun `unbrauchbare Eingaben liefern keinen Kredit`() {
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(Double.NaN, 0.0, isf), 0.0)
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, Double.NaN, isf), 0.0)
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 0.0, Double.NaN), 0.0)
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(0.0, 0.0, isf), 0.0)
        assertEquals(0.0, MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 0.0, -95.0), 0.0)
    }

    @Test
    fun `Stufen skalieren linear - S kleiner als M kleiner als L`() {
        val s = MarkerScope.declaredAbsorptionDriveMgdlPerMin(0.8, 0.0, isf)
        val m = MarkerScope.declaredAbsorptionDriveMgdlPerMin(1.2, 0.0, isf)
        val l = MarkerScope.declaredAbsorptionDriveMgdlPerMin(2.0, 0.0, isf)
        assertTrue(s < m && m < l)
    }
}
