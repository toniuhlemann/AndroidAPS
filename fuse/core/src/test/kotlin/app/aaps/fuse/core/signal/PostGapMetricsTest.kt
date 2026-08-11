package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostGapMetricsTest {

    private val t0 = 1_786_000_000_000L
    private fun min(n: Double) = t0 + (n * 60_000).toLong()

    /**
     * DIE ECHTE SEQUENZ VOM 10.08., als Pflichtgegenprobe.
     *
     *   105  ->  35-min-Luecke  ->  90  ->  3 min spaeter 105
     *
     * Der Punkt dieses Tests ist, was er ueber den ERSTEN Punkt nach der Luecke
     * zeigt: seine Rate ist voellig unauffaellig. Wer nur Raten prueft, sieht
     * diesen Fall nie - und genau deshalb gibt es die vier Zahlen.
     */
    @Test
    fun `die Sequenz vom 10 August - erster Punkt harmlos, Ruecksprung auffaellig`() {
        val ts = listOf(min(0.0), min(35.0))
        val v = listOf(105.0, 90.0)
        val bruch = min(35.0)          // segmentStart schneidet AUF den Punkt nach der Luecke

        val ersterPunkt = PostGapMetrics.of(ts, v, bruch)
        assertEquals(35.0, ersterPunkt.gapBeforeMin, 1e-9)
        assertEquals(-15.0, ersterPunkt.stepFromLastMgdl, 1e-9)
        assertEquals(-15.0 / 35.0, ersterPunkt.stepRateActualMgdlPerMin, 1e-9)
        assertEquals(1, ersterPunkt.postGapIndex, "der erste Punkt nach der Luecke")

        // DAS IST DIE AUSSAGE: -0,43 mg/dl/min. Ein Ratenwaechter mit JEDER
        // vernuenftigen Schwelle laesst das durch.
        assertTrue(
            kotlin.math.abs(ersterPunkt.stepRateActualMgdlPerMin) < 0.5,
            "die Rate des ersten Punktes ist unauffaellig: ${ersterPunkt.stepRateActualMgdlPerMin}"
        )

        // Drei Minuten spaeter der Ruecksprung.
        val nachher = PostGapMetrics.of(
            ts + listOf(min(38.0)), v + listOf(105.0), bruch
        )
        assertEquals(3.0, nachher.gapBeforeMin, 1e-9)
        assertEquals(+15.0, nachher.stepFromLastMgdl, 1e-9)
        assertEquals(5.0, nachher.stepRateActualMgdlPerMin, 1e-9)
        assertEquals(2, nachher.postGapIndex, "zweiter Punkt seit dem Bruch")

        // UND HIER SCHLIESST SICH DIE DIAGNOSE: die Luecke ist zu diesem
        // Zeitpunkt schon klein (3 min), die Rate dafuer gross. Nur BEIDE
        // zusammen mit dem kleinen postGapIndex beschreiben den Fall.
        assertTrue(nachher.gapBeforeMin < 5.0 && nachher.postGapIndex <= 3)
    }

    /** Ein normaler Verlauf ohne Luecke sieht anders aus - sonst waere die
     *  Unterscheidung wertlos. */
    @Test
    fun `ein normaler Verlauf hat kleinen Schritt und hohen Index`() {
        val ts = (0..20).map { min(it.toDouble()) }
        val v = (0..20).map { 100.0 + it * 1.5 }
        val r = PostGapMetrics.of(ts, v, min(0.0))
        assertEquals(1.0, r.gapBeforeMin, 1e-9)
        assertEquals(1.5, r.stepFromLastMgdl, 1e-9)
        assertEquals(1.5, r.stepRateActualMgdlPerMin, 1e-9)
        assertEquals(21, r.postGapIndex, "reifes Segment")
    }

    /**
     * Ein MAHLZEITENKOPF darf nicht wie der Luecken-Fall aussehen. Tonis
     * gemessene 4,85 mg/dl/min - dieselbe Rate wie der Ruecksprung oben, aber
     * mit reifem Segment.
     */
    @Test
    fun `ein steiler Mahlzeitenkopf ist am Index unterscheidbar`() {
        val ts = (0..20).map { min(it.toDouble()) }
        val v = (0..20).map { 100.0 + it * 4.85 }
        val r = PostGapMetrics.of(ts, v, min(0.0))
        assertEquals(4.85, r.stepRateActualMgdlPerMin, 1e-9)
        assertTrue(r.postGapIndex > 10, "kein frischer Bruch - genau das trennt ihn vom Artefakt")
    }

    @Test
    fun `ein einzelner Punkt liefert keine Schrittgroessen`() {
        val r = PostGapMetrics.of(listOf(min(0.0)), listOf(100.0), min(0.0))
        assertEquals(0.0, r.gapBeforeMin)
        assertEquals(0.0, r.stepFromLastMgdl)
        assertEquals(0.0, r.stepRateActualMgdlPerMin)
        assertEquals(1, r.postGapIndex)
    }

    @Test
    fun `eine leere Reihe wirft nicht`() {
        val r = PostGapMetrics.of(emptyList(), emptyList(), t0)
        assertEquals(0, r.postGapIndex)
    }

    /** Gleiche Zeitstempel duerfen keine Division durch null erzeugen. */
    @Test
    fun `zwei Punkte mit gleichem Zeitstempel geben Rate null`() {
        val r = PostGapMetrics.of(listOf(min(0.0), min(0.0)), listOf(100.0, 110.0), min(0.0))
        assertEquals(0.0, r.gapBeforeMin)
        assertEquals(10.0, r.stepFromLastMgdl)
        assertEquals(0.0, r.stepRateActualMgdlPerMin, "keine Division durch null")
    }
}
