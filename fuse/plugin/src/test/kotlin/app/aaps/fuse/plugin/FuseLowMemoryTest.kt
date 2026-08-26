package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * DER VORFALL VOM 15.08. ALS TEST - mit den echten Zahlen aus dem
 * Produktiv-Trail (q1 fiel von 75,9 auf 70,2 und stieg wieder ueber 75; der
 * Flash um 17:56 loeschte das Gedaechtnis, obwohl das Fenster bis 18:35 lief).
 */
class FuseLowMemoryTest {

    private val jetzt = 1_000_000_000L

    private fun zeile(offsetMin: Long, q1: Double?): String {
        val ts = jetzt - offsetMin * 60_000L
        val sig = if (q1 == null) """"signal":{"rSigned":0.1}""" else """"signal":{"q1":$q1}"""
        return """{"sourceTs":$ts,$sig}"""
    }

    private fun lies(vararg zeilen: String) =
        FuseLowMemory.lastLowTsFromTrail(zeilen.asSequence(), jetzt, FuseController.REBOUND_WINDOW_MIN)

    private fun liesMit(fensterMin: Int, vararg zeilen: String) =
        FuseLowMemory.lastLowTsFromTrail(zeilen.asSequence(), jetzt, fensterMin)

    /** Die gemessene Reihe: das juengste Tief war 11 min alt. */
    @Test
    fun `das juengste Tief im Fenster wird zurueckgeholt`() {
        val ts = lies(
            zeile(20, 75.88), zeile(18, 74.70), zeile(14, 70.21),
            zeile(11, 73.40), zeile(9, 75.09), zeile(4, 83.07),
        )
        assertEquals(jetzt - 11 * 60_000L, ts)
    }

    /** Aelter als das Fenster heisst folgenlos - der Zeitstempel wuerde nichts
     *  mehr oeffnen und gehoert dann nicht in den Zustand. */
    @Test
    fun `ein Tief ausserhalb des Fensters zaehlt nicht`() {
        val zuAlt = FuseController.REBOUND_WINDOW_MIN + 5L
        assertEquals(0L, lies(zeile(zuAlt, 68.0)))
    }

    @Test
    fun `ohne Tief bleibt es bei null`() {
        assertEquals(0L, lies(zeile(20, 90.0), zeile(10, 105.0)))
    }

    /** Die Schwelle ist strikt: genau 75 ist KEIN Tief (so steht es auch in
     *  der laufenden Bedingung `q1 < REBOUND_LOW_MGDL`). */
    @Test
    fun `genau auf der Schwelle zaehlt nicht`() {
        assertEquals(0L, lies(zeile(10, FuseController.REBOUND_LOW_MGDL)))
        assert(lies(zeile(10, FuseController.REBOUND_LOW_MGDL - 0.01)) > 0L)
    }

    /** Zeilen ohne q1 (Abbruchzyklen, aeltere Exporte) sind kein Tief. */
    @Test
    fun `Zeilen ohne q1 werden uebersprungen`() {
        assertEquals(jetzt - 12 * 60_000L, lies(zeile(12, 70.0), zeile(5, null), "kaputt{"))
    }

    /** Eine Zeile aus der Zukunft (verstellte Uhr) darf das Fenster nicht ueber
     *  seine Laufzeit hinaus offen halten. */
    @Test
    fun `Zeitstempel aus der Zukunft werden verworfen`() {
        assertEquals(0L, lies(zeile(-30, 65.0)))
    }

    /**
     * DIE EIGENTLICHE NEUERUNG (26.08.): dasselbe Tief, zwei Fensterdauern.
     *
     * Waere die Dauer hier weiterhin die Konstante, gaebe der laengere Lauf
     * dieselbe Null zurueck wie der kuerzere - und ein auf 120 gestelltes
     * Fenster waere nach jedem Neustart still wieder 45 Minuten lang. Genau
     * dieser stille Verlust ist der Grund, warum das Gedaechtnis die Dauer
     * als Parameter bekommt und nicht selbst kennt.
     */
    @Test
    fun `ein laengeres Fenster holt ein aelteres Tief zurueck`() {
        val alt = zeile(70, 68.0)
        assertEquals(0L, liesMit(45, alt))
        assertEquals(jetzt - 70 * 60_000L, liesMit(90, alt))
        assertEquals(jetzt - 70 * 60_000L, liesMit(120, alt))
        // Die Kante bleibt strikt: exakt auf der Grenze zaehlt nicht mehr.
        assertEquals(0L, liesMit(70, alt))
        assert(liesMit(71, alt) > 0L)
    }

    /**
     * FAIL-CLOSED statt fail-open: ein fehlerhafter Aufruf mit 0 oder negativ
     * darf das Gedaechtnis nicht ABSCHALTEN, sondern faellt auf den Default
     * zurueck. Ein ausgeschaltetes Tief-Gedaechtnis oeffnet den SMB-Kanal
     * genau in der Lage, fuer die das Fenster gebaut wurde.
     */
    @Test
    fun `unbrauchbare Dauer faellt auf den Default zurueck`() {
        val drin = zeile(20, 68.0)
        assertEquals(jetzt - 20 * 60_000L, liesMit(0, drin))
        assertEquals(jetzt - 20 * 60_000L, liesMit(-5, drin))
        // ... und faellt eben NICHT auf "unbegrenzt" zurueck.
        assertEquals(0L, liesMit(0, zeile(FuseController.REBOUND_WINDOW_MIN + 5L, 68.0)))
    }
}
