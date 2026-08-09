package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** C9-01: derselbe Sprungzaun fuer Zustandsmaschine UND Signalkette. */
class SignalWindowStepFenceTest {

    private fun p(minute: Long, value: Double) = UkfQ1.Point(minute * 60_000L, value)

    @Test
    fun `eine ruhige Reihe hat keine Grenze`() {
        val series = (0L..10L).map { p(it, 100.0 + it) }
        assertNull(SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `der Sprung setzt die Grenze auf den Punkt DANACH`() {
        val series = listOf(p(0, 100.0), p(1, 102.0), p(2, 104.0), p(3, 131.0), p(4, 133.0))
        assertEquals(3 * 60_000L, SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `der JUENGSTE Sprung gewinnt`() {
        val series = listOf(p(0, 100.0), p(1, 130.0), p(2, 132.0), p(3, 90.0), p(4, 92.0))
        assertEquals(3 * 60_000L, SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `Abwaertssprung zaehlt genauso`() {
        val series = listOf(p(0, 180.0), p(1, 155.0), p(2, 154.0))
        assertEquals(60_000L, SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `jenseits der Segmentgrenze greift die Pruefung nicht`() {
        // 4 min Abstand: dort bricht das Segment ohnehin, kein Doppelzaun.
        val series = listOf(p(0, 100.0), p(4, 140.0), p(5, 141.0))
        assertNull(SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `genau an der Schwelle wird gezaeunt - kein ungedecktes Band`() {
        // 20,0 mg/dl bei exakt 3 min: BEIDE Grenzwerte auf Kante.
        val series = listOf(p(0, 100.0), p(3, 120.0))
        assertEquals(3 * 60_000L, SignalWindow.stepBoundaryTs(series))
        // knapp darunter bleibt frei
        assertNull(SignalWindow.stepBoundaryTs(listOf(p(0, 100.0), p(3, 119.9))))
    }

    @Test
    fun `die Zustandsmaschine benutzt exakt dieselbe Zahl`() {
        assertEquals(
            SignalWindow.INPUT_STEP_MGDL,
            app.aaps.fuse.core.observer.ObserverParams().inputStepMgdl,
            0.0
        )
        assertEquals(
            SignalWindow.STEP_MAX_GAP_MS,
            (app.aaps.fuse.core.observer.ObserverParams().rSegmentBreakMin * 60_000L).toLong()
        )
    }
}
