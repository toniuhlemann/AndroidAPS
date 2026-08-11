package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER BODEN, ohne Runner.
 *
 * Der Fall, auf den es ankommt - Basis GROESSER als der Markerboden, danach ein
 * Veto - war im Runner nicht herstellbar: er verlangt eine abtauchende Bahn
 * (fuer das Veto) UND Bedarf (fuer die groessere Basis), aber die
 * Kandidatensuche prueft denselben Guard und nullt die Basis schon vorher.
 * Gemessen bei BG 250 steigend: Schwanz-Headroom +2,4 U, kein Veto.
 *
 * Hier ist er eine Zeile.
 */
class MarkerFloorTest {

    private fun entscheidung(smb: Double, block: FuseController.Block, limit: String) =
        FuseController.Decision(
            smbU = smb, tbr = FuseController.TbrAction.ZERO_TEMP, block = block,
            insulinReqU = 0.0, predAtReleaseMgdl = null, minLowerMgdl = null,
            bindingLimit = limit,
        )

    /**
     * DER TESTFALL AUS TONIS DURCHSICHT: Basis 0,30, autorisiert 0,05, das Veto
     * hat die Basis verworfen -> es bleiben 0,05, nicht 0 und nicht 0,30.
     *
     * Beide Fehlrichtungen sind real gewesen: 0 U, solange der Lift bei
     * groesserer Basis nicht stempelte; 0,30 U, solange der Boden `lifted.smbU`
     * statt `authCapU` herstellte.
     */
    @Test
    fun `nach dem Veto bleibt genau der autorisierte Anteil`() {
        val d = MarkerFloor.apply(
            verified = entscheidung(0.0, FuseController.Block.CANDIDATE, "finalVerify:TAIL_VETO"),
            authCapU = 0.05,
            kernelValid = true,
        )
        assertEquals(0.05, d.smbU, 1e-9, "weder 0 noch die verworfene Basis")
        assertEquals(0.05, d.markerAuthorizedU, 1e-9)
        assertEquals(FuseController.Block.NONE, d.block)
        assertTrue(d.bindingLimit.startsWith("markerAuth|"), d.bindingLimit)
        assertTrue(
            d.bindingLimit.contains("TAIL_VETO"),
            "der ueberstimmte Einwand muss im Grund stehen bleiben: ${d.bindingLimit}",
        )
        assertEquals(FuseController.STAGE_PRIME, d.capsStage)
        assertTrue(d.caps.isEmpty(), "die Basiskappen haben diese Menge nicht bestimmt")
    }

    /** EIN VERWORFENER EINHEITSKERN ist kein Guard-Urteil, sondern ein
     *  Integritaetsbefund - der Boden schweigt dann. */
    @Test
    fun `ohne gueltigen Einheitskern hebt der Boden nichts`() {
        val d = MarkerFloor.apply(
            verified = entscheidung(0.0, FuseController.Block.CANDIDATE, "MODEL_HORIZON_TOO_SHORT"),
            authCapU = 0.05,
            kernelValid = false,
        )
        assertEquals(0.0, d.smbU, 1e-9)
        assertEquals("MODEL_HORIZON_TOO_SHORT", d.bindingLimit, "und der Grund bleibt unveraendert")
    }

    /** OHNE Autorisierung bleibt die verworfene Entscheidung, wie sie ist. */
    @Test
    fun `ohne Autorisierung hebt der Boden nichts`() {
        for (cap in listOf(0.0, -1.0)) {
            val v = entscheidung(0.0, FuseController.Block.CANDIDATE, "finalVerify:GUARD_FLOOR")
            assertEquals(v, MarkerFloor.apply(v, cap, kernelValid = true), "cap=$cap")
        }
    }

    /**
     * ER SENKT NIE. Ist die ueberlebende Menge schon groesser als der
     * autorisierte Anteil, bleibt sie unangetastet - der Boden ist ein Boden,
     * keine Kappe.
     */
    @Test
    fun `eine groessere ueberlebende Menge wird nicht gesenkt`() {
        val v = entscheidung(0.30, FuseController.Block.NONE, "smbRatio")
        assertEquals(v, MarkerFloor.apply(v, authCapU = 0.05, kernelValid = true))
    }

    /** Gleichstand aendert nichts - und erzeugt insbesondere keinen
     *  irrefuehrenden `markerAuth|`-Grund fuer eine Menge, die ohnehin stand. */
    @Test
    fun `bei Gleichstand bleibt der Grund unveraendert`() {
        val v = entscheidung(0.05, FuseController.Block.NONE, "primeRelease")
        assertEquals(v, MarkerFloor.apply(v, authCapU = 0.05, kernelValid = true))
    }
}
