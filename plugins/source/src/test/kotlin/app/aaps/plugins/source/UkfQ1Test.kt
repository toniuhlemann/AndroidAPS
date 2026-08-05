package app.aaps.plugins.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Q1 ist der GELOCKTE Holdout-Kandidat (R58): jede Verhaltensaenderung hier
 * waere ein neuer, ungelockter Filter. Die Tests sichern deshalb die
 * Invarianten des Referenzports, nicht "schoene" Werte.
 */
class UkfQ1Test {

    private fun series(startMs: Long, vararg values: Double, stepMs: Long = 60_000L) =
        values.mapIndexed { i, v -> UkfQ1.Point(startMs + i * stepMs, v) }

    private val t0 = 1_785_000_000_000L

    @Test
    fun `deterministisch - gleiche Serie, gleiches Ergebnis`() {
        val s = series(t0, 100.0, 101.0, 103.0, 106.0, 110.0, 115.0, 121.0, 128.0)
        assertEquals(UkfQ1.leadingEdge(s), UkfQ1.leadingEdge(s))
    }

    @Test
    fun `Reihenfolge und Duplikate aendern nichts`() {
        val s = series(t0, 100.0, 102.0, 105.0, 109.0, 114.0, 120.0)
        val shuffled = (s.shuffled(kotlin.random.Random(7)) + s[2]).toList()
        assertEquals(UkfQ1.leadingEdge(s), UkfQ1.leadingEdge(shuffled))
    }

    @Test
    fun `flache Serie - Wert nahe Messung, Rate nahe null`() {
        val s = series(t0, *DoubleArray(60) { 120.0 }.toTypedArray().toDoubleArray())
        val r = UkfQ1.leadingEdge(s)!!
        assertTrue(abs(r.glucose - 120.0) < 1.0, "glucose=${r.glucose}")
        assertTrue(abs(r.ratePerMin) < 0.1, "rate=${r.ratePerMin}")
        assertFalse(r.outlier)
    }

    @Test
    fun `linearer Anstieg - Leading Edge folgt mit korrekter Rate`() {
        val s = series(t0, *DoubleArray(90) { 100.0 + it * 2.0 }.toTypedArray().toDoubleArray())
        val r = UkfQ1.leadingEdge(s)!!
        val newest = 100.0 + 89 * 2.0
        assertTrue(abs(r.glucose - newest) < 4.0, "glucose=${r.glucose} vs $newest")
        assertTrue(abs(r.ratePerMin - 2.0) < 0.5, "rate=${r.ratePerMin}")
    }

    @Test
    fun `Major-Gap startet Segment neu - Vorgeschichte irrelevant`() {
        val old = series(t0, 200.0, 210.0, 220.0, 230.0)
        val fresh = series(t0 + 4 * 60_000L + 61 * 60_000L, 100.0, 101.0, 102.0, 103.0)
        val withHistory = UkfQ1.leadingEdge(old + fresh)
        val alone = UkfQ1.leadingEdge(fresh)
        assertEquals(alone, withHistory)
    }

    @Test
    fun `38er-Sentinel bricht das Segment`() {
        val s = series(t0, 120.0, 121.0, 38.0, 100.0, 101.0, 102.0)
        val expected = UkfQ1.leadingEdge(series(t0 + 3 * 60_000L, 100.0, 101.0, 102.0))
        assertEquals(expected, UkfQ1.leadingEdge(s))
    }

    @Test
    fun `Einzelpunkt - Floor 39, Rate 0`() {
        val r = UkfQ1.leadingEdge(listOf(UkfQ1.Point(t0, 25.0)))!!
        assertEquals(39.0, r.glucose)
        assertEquals(0.0, r.ratePerMin)
    }

    @Test
    fun `80er-Sprung wird als Outlier markiert und stark gedaempft`() {
        val base = DoubleArray(60) { 110.0 }
        val s = series(t0, *base.toTypedArray().toDoubleArray()) +
            UkfQ1.Point(t0 + 60 * 60_000L, 190.0)
        val r = UkfQ1.leadingEdge(s)!!
        assertTrue(r.outlier, "80 mg/dl in 1 min muss als Outlier gelten")
        assertTrue(r.glucose < 150.0, "Sprung muss gedaempft sein: ${r.glucose}")
    }

    @Test
    fun `leere und unbrauchbare Eingaben liefern null`() {
        assertNull(UkfQ1.leadingEdge(emptyList()))
        assertNull(UkfQ1.leadingEdge(listOf(UkfQ1.Point(0L, 100.0))))
        assertNull(UkfQ1.leadingEdge(listOf(UkfQ1.Point(t0, Double.NaN))))
    }

    @Test
    fun `Fenster ist hart auf 180 Samples begrenzt`() {
        val long = series(t0, *DoubleArray(400) { 100.0 + (it % 7) }.toTypedArray().toDoubleArray())
        val windowOnly = long.takeLast(UkfQ1.WINDOW_SAMPLES)
        assertEquals(UkfQ1.leadingEdge(windowOnly), UkfQ1.leadingEdge(long))
    }
}
