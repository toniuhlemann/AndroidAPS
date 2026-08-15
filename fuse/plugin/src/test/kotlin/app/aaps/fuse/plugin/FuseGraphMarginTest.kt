package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * DIE VIER REGELN DER SCHWANZ-LINIE - und vor allem die eine, die aus einer
 * Messung stammt statt aus einer Ueberlegung.
 *
 * Beide Ring-Pfade (live und Warmstart) gehen durch dieselbe Funktion; diese
 * Tests sind damit die Zusicherung fuer beide.
 */
class FuseGraphMarginTest {

    @Test
    fun `beide Zahlen auswertbar ergibt headroom mal ISF`() {
        assertEquals(-40.0, FuseGraphMargin.tailMarginMgdl(-0.5, 80.0, null))
        assertEquals(24.0, FuseGraphMargin.tailMarginMgdl(0.3, 80.0, null))
    }

    /**
     * DER GEMESSENE FALL (Trail 15.08., 73 von 131 Bloecken): der
     * unphysiologische Ausgang liefert `headroomU = -existing` und
     * `isfTailMgdlPerU = NaN`. Er SPERRT - eine Luecke waere die
     * gefaehrliche Richtung des Irrtums.
     */
    @Test
    fun `ohne ISF-Nenner aber sperrend liegt die Linie auf dem unteren Anschlag`() {
        assertEquals(
            FuseGraphMargin.LOWER_MGDL,
            FuseGraphMargin.tailMarginMgdl(-0.498, Double.NaN, null)
        )
        assertEquals(
            FuseGraphMargin.LOWER_MGDL,
            FuseGraphMargin.tailMarginMgdl(-0.498, null, null)
        )
    }

    /** Ohne Nenner "offen" zu behaupten waere dieselbe Richtung des Irrtums,
     *  nur umgekehrt - also Luecke. */
    @Test
    fun `ohne ISF-Nenner und ohne Sperre bleibt es eine Luecke`() {
        assertNull(FuseGraphMargin.tailMarginMgdl(0.4, Double.NaN, null))
        assertNull(FuseGraphMargin.tailMarginMgdl(0.0, Double.NaN, null))
    }

    /** `invalidReason` heisst: der Schwanz-Guard greift NICHT. Dann darf auch
     *  keine Linie eine Sperre behaupten - auch nicht bei negativem Headroom. */
    @Test
    fun `ein ungueltiger Bericht erzeugt nie eine Linie`() {
        assertNull(FuseGraphMargin.tailMarginMgdl(-0.5, 80.0, "NO_ISF"))
        assertNull(FuseGraphMargin.tailMarginMgdl(-0.5, Double.NaN, "NO_ISF"))
        assertNull(FuseGraphMargin.tailMarginMgdl(0.5, 80.0, "NO_ISF"))
    }

    /**
     * DER GERAETEFEHLER VOM 15.08., als Test festgehalten: Androids
     * `optString` gibt fuer ein JSON-null den String "null" zurueck (die JVM
     * gibt den Default) - der Warmstart hielt damit JEDE Trail-Zeile fuer
     * ungueltig, und die halbe Linie fehlte. Der Parser prueft jetzt mit
     * `isNull()`; diese Zusicherung ist der zweite Riegel darunter.
     */
    @Test
    fun `der String null gilt nicht als Ungueltigkeitsgrund`() {
        assertEquals(-40.0, FuseGraphMargin.tailMarginMgdl(-0.5, 80.0, "null"))
        assertEquals(-40.0, FuseGraphMargin.tailMarginMgdl(-0.5, 80.0, ""))
        assertEquals(-40.0, FuseGraphMargin.tailMarginMgdl(-0.5, 80.0, "  "))
    }

    @Test
    fun `fehlende oder unbrauchbare Zahlen ergeben eine Luecke`() {
        assertNull(FuseGraphMargin.tailMarginMgdl(null, 80.0, null))
        assertNull(FuseGraphMargin.tailMarginMgdl(Double.NaN, 80.0, null))
    }

    /** Dieselbe Klippung wie beim Guard-Abstand - sonst staucht ein einzelner
     *  Ausreisser die gemeinsame Skala beider Kanten. */
    @Test
    fun `die Werte werden auf denselben Bereich geklippt wie der Guard`() {
        assertEquals(FuseGraphMargin.LOWER_MGDL, FuseGraphMargin.tailMarginMgdl(-5.0, 80.0, null))
        assertEquals(FuseGraphMargin.UPPER_MGDL, FuseGraphMargin.tailMarginMgdl(5.0, 80.0, null))
    }
}
