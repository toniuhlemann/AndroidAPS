package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER A2-VERTRAG: die gemeinsame Sicht ist eine EXTRAKTION, keine neue
 * Semantik. Bitgleichheit ist hier woertlich gemeint - deshalb vergleichen
 * die Identitaetsproben mit ==, nicht mit einer Toleranz.
 */
class ExposureViewTest {

    /** Exakt dieselbe Ausdrucksreihenfolge wie die frueheren Inline-Kopien:
     *  `Grenze - capIob - transport`. Krumme Gleitkommawerte, damit eine
     *  umgeklammerte Variante (`Grenze - (capIob + transport)`) auffiele. */
    @Test
    fun `die Headrooms sind bitgleich zur Inline-Form`() {
        val iobTh = 4.8
        val maxIob = 8.0
        val capIob = 3.1 + 0.25
        val transport = 0.1 + 0.2
        val v = ExposureView.of(iobThU = iobTh, maxIobU = maxIob, capIobU = capIob, transportU = transport)
        assertTrue(v.iobThHeadroomU == iobTh - capIob - transport, "iobTH bitgleich")
        assertTrue(v.maxIobHeadroomU == maxIob - capIob - transport, "maxIOB bitgleich")
    }

    /** UNGEKLEMMT: ein negativer Headroom IST die Information "schon
     *  drueber" - die Aufrufstellen entscheiden selbst, was daraus folgt
     *  (die geklemmte Variante lebt bewusst nur im LivenessChannel). */
    @Test
    fun `ein ueberschrittener Deckel liefert den negativen Rest`() {
        val v = ExposureView.of(iobThU = 4.0, maxIobU = 8.0, capIobU = 4.5, transportU = 0.1)
        assertTrue(v.iobThHeadroomU < 0.0)
        assertEquals(4.0 - 4.5 - 0.1, v.iobThHeadroomU)
    }

    /** C3-02-Richtung: mehr Transporthaftung macht die Headrooms NIE
     *  weiter - die konservative Doppelung in die engere Richtung bleibt
     *  konstruktiv erlaubt, raumSCHAFFEND ist sie nie. */
    @Test
    fun `mehr Transport verengt beide Headrooms monoton`() {
        val eng = ExposureView.of(iobThU = 4.8, maxIobU = 8.0, capIobU = 2.0, transportU = 0.55)
        val weit = ExposureView.of(iobThU = 4.8, maxIobU = 8.0, capIobU = 2.0, transportU = 0.05)
        assertTrue(eng.iobThHeadroomU < weit.iobThHeadroomU)
        assertTrue(eng.maxIobHeadroomU < weit.maxIobHeadroomU)
    }
}
