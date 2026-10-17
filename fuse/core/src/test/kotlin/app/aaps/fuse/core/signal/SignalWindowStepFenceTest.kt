package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import app.aaps.fuse.core.observer.ObserverParams
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

    /** GEAENDERT (Codex Re-Review 603a15a): frueher liess eine harte
     *  3-Minuten-Kante alles darueber durch - genau dort sass das Loch bei
     *  dt = 3,1 min. Jetzt entscheidet die RATE, und 40 mg/dl in 4 min sind
     *  10 mg/dl/min, also eindeutig kein Zucker. */
    @Test
    fun `ein Sprung knapp jenseits der Segmentgrenze wird jetzt gezaeunt`() {
        val series = listOf(p(0, 100.0), p(4, 140.0), p(5, 141.0))
        assertEquals(4 * 60_000L, SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `genau an der Schwelle wird gezaeunt - kein ungedecktes Band`() {
        // 20,0 mg/dl bei exakt 3 min = 6,67 mg/dl/min: beide Bedingungen auf Kante.
        val series = listOf(p(0, 100.0), p(3, 120.0))
        assertEquals(3 * 60_000L, SignalWindow.stepBoundaryTs(series))
        // knapp unter der HOEHE bleibt frei
        assertNull(SignalWindow.stepBoundaryTs(listOf(p(0, 100.0), p(3, 119.9))))
    }

    /** Die SPRUNGHOEHE bleibt die geteilte Zahl. Das Zeitkriterium ist
     *  bewusst NICHT mehr geteilt: die Zustandsmaschine grenzt an der
     *  r-Segmentgrenze ab, die Signalkette an einer RATE - eine harte
     *  Zeitkante hatte an genau dieser Naht ihr Loch (dt = 3,1 min). */
    @Test
    fun `die Zustandsmaschine benutzt dieselbe Sprunghoehe`() {
        assertEquals(
            SignalWindow.INPUT_STEP_MGDL,
            app.aaps.fuse.core.observer.ObserverParams().inputStepMgdl,
            0.0
        )
        // Die Signalkette darf nie SCHWAECHER zaeunen als die Zustandsmaschine:
        // was diese bei <= 3 min als Sprung sieht, muss jene auch sehen.
        val p3 = ObserverParams().rSegmentBreakMin
        assertTrue(SignalWindow.INPUT_STEP_MGDL / p3 >= SignalWindow.STEP_MIN_RATE_MGDL_PER_MIN)
    }
}

/** C9-01 Rest (Codex Re-Review 603a15a): die harte 3-Minuten-Kante liess
 *  dt = 3,1 min durch. Die Rate hat kein solches Band. */
class SignalWindowStepRateTest {

    private fun at(ms: Long, value: Double) = UkfQ1.Point(ms, value)

    @Test
    fun `der gemeldete 3_1-Minuten-Fall wird jetzt gezaeunt`() {
        val t = 3L * 60_000L + 6_000L   // 3,1 min
        val series = listOf(at(0, 100.0), at(t, 121.0), at(t + 60_000L, 122.0))
        assertEquals(t, SignalWindow.stepBoundaryTs(series)) {
            "21 mg/dl in 3,1 min sind 6,8 mg/dl/min - das ist kein Zucker"
        }
    }

    @Test
    fun `eine schnelle aber moegliche Mahlzeit bleibt frei`() {
        // 20 mg/dl in 5 min = 4,0 mg/dl/min - physiologisch erreichbar.
        val series = listOf(at(0, 100.0), at(5 * 60_000L, 120.0))
        assertNull(SignalWindow.stepBoundaryTs(series))
    }

    @Test
    fun `beide Bedingungen muessen gelten`() {
        // Rate hoch, Hoehe zu klein: 15 mg/dl in 1 min = 15/min, aber < 20.
        assertNull(SignalWindow.stepBoundaryTs(listOf(at(0, 100.0), at(60_000L, 115.0))))
        // Hoehe gross, Rate klein: 40 mg/dl in 15 min = 2,7/min.
        assertNull(SignalWindow.stepBoundaryTs(listOf(at(0, 100.0), at(15 * 60_000L, 140.0))))
    }

    @Test
    fun `jenseits des Suchfensters wird nicht mehr gesucht`() {
        assertNull(SignalWindow.stepBoundaryTs(listOf(at(0, 100.0), at(25 * 60_000L, 300.0))))
    }
}
