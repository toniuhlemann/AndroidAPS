package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C1b (Codex-Adjudication 09.08.): ein einzelner Punkt nach Segmentbruch
 * traegt KEINE Rate. Vorher meldete [UkfQ1] dafuer 0.0 - eine Behauptung
 * ("flach"), die die schnelle Bremsbahn fuer eine gueltige Zahl hielt.
 */
class SegmentReadinessTest {

    private val t0 = 1_786_000_000_000L
    private val m = 60_000L

    @Test
    fun `einzelner Punkt nach Segmentbruch meldet die Rate als nicht verfuegbar`() {
        // Grosse Luecke (> MAJOR_GAP) trennt: das juengste Segment hat 1 Punkt.
        val pts = listOf(
            UkfQ1.Point(t0, 100.0),
            UkfQ1.Point(t0 + 5 * m, 102.0),
            UkfQ1.Point(t0 + 200 * m, 150.0),
        )
        val r = UkfQ1.leadingEdge(pts)!!
        assertTrue(r.rateUnavailable) { "Einpunkt-Segment muss die Rate als unbekannt melden" }
        assertEquals(150.0, r.glucose, 1e-9) { "Der Glukosewert selbst ist bekannt" }
    }

    @Test
    fun `zwei Punkte im Segment liefern wieder eine belastbare Rate`() {
        val pts = listOf(
            UkfQ1.Point(t0, 100.0),
            UkfQ1.Point(t0 + 200 * m, 150.0),
            UkfQ1.Point(t0 + 201 * m, 152.0),
        )
        val r = UkfQ1.leadingEdge(pts)!!
        assertFalse(r.rateUnavailable) { "Ab zwei Punkten ist die Rate belegbar" }
    }

    @Test
    fun `ununterbrochene Reihe meldet nie unavailable`() {
        val pts = (0..20).map { UkfQ1.Point(t0 + it * m, 100.0 + it) }
        assertFalse(UkfQ1.leadingEdge(pts)!!.rateUnavailable)
    }
}
