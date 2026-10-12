package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.ExpectationAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WELCHE ERWARTUNGSKURVE GILT (Toni): die vier Kurvenwerte haben
 * Vorrang vor der Expertenzeile; gesetzte, aber ungueltige Werte schalten die
 * Erwartung aus, statt still auf die Zeile zurueckzufallen. Synthetische Werte.
 */
class ExpectationCurveTextWirksamTest {

    private val zeile = (0..24).joinToString(";") { String.format(java.util.Locale.US, "%.2f", it * 0.1) }

    @Test
    fun `ohne Kurvenwerte gilt die Expertenzeile`() {
        val w = ExpectationCurveText.wirksam(zeile, 0.0, 0.0, 0.0, 0.0)
        assertEquals(zeile, w.text)
        assertEquals("Expertenzeile", w.quelle)
    }

    @Test
    fun `gesetzte Kurvenwerte haben Vorrang vor der Zeile`() {
        val w = ExpectationCurveText.wirksam(zeile, 0.6, 1.8, 3.0, 4.2)
        assertEquals("Kurvenwerte", w.quelle)
        val k = ExpectationAccount.Curve.parse(w.text)!!
        assertEquals(4.2, k.endU, 1e-9)
        assertEquals(1.8, k.at(60.0), 1e-9)
        assertTrue(ExpectationCurveText.inWorten(w).startsWith("gilt (Kurvenwerte)"))
    }

    @Test
    fun `ungueltige Kurvenwerte schalten die Erwartung aus statt auf die Zeile zu fallen`() {
        val w = ExpectationCurveText.wirksam(zeile, 1.0, 0.5, 2.0, 3.0)
        assertNull(ExpectationAccount.Curve.parse(w.text))
        assertTrue(ExpectationCurveText.inWorten(w).startsWith("keine gueltige Kurve"))
    }

    @Test
    fun `beides leer heisst nicht gesetzt`() {
        val w = ExpectationCurveText.wirksam("", 0.0, 0.0, 0.0, 0.0)
        assertNull(ExpectationAccount.Curve.parse(w.text))
        assertEquals("nicht gesetzt", w.quelle)
    }
}
