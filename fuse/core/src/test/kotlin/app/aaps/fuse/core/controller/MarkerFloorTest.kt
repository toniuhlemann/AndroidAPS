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
     * ER SENKT NIE - ABER DIE PROVENIENZ FAEHRT MIT.
     *
     * DIESER TEST HAT DEN FEHLER FESTGESCHRIEBEN, den er finden sollte: mit
     * einem vollstaendigen `assertEquals` verlangte er, dass NICHTS sich
     * aendert - also auch, dass `markerAuthorizedU` 0 bleibt.
     *
     * `verified` kann aus dem Rueckfall auf `vetted` stammen, der kleineren
     * Basis, die das Veto ueberlebt hat; die traegt keine Provenienz. Ohne den
     * Stempel sieht FuseTbrTranslator bei SAFETY_ZERO keine Autorisierung und
     * nullt die GANZE Menge - der Markerboden verschwindet also trotz
     * ausreichender Basis. Die Fortsetzung dieses Falls bis zur genullten
     * Menge steht in FuseTbrTranslatorTest.
     */
    @Test
    fun `eine groessere ueberlebende Menge wird nicht gesenkt aber gestempelt`() {
        val v = entscheidung(0.30, FuseController.Block.NONE, "smbRatio")
        val d = MarkerFloor.apply(v, authCapU = 0.05, kernelValid = true)

        assertEquals(0.30, d.smbU, 1e-9, "die Dosis bleibt unangetastet")
        assertEquals("smbRatio", d.bindingLimit, "und der Grund auch - hier hat nichts ueberstimmt")
        assertEquals(FuseController.Block.NONE, d.block)
        assertEquals(
            0.05, d.markerAuthorizedU, 1e-9,
            "aber die Autorisierungsgrenze muss mitfahren, sonst nullt der Translator alles",
        )
    }

    /** Eine BEREITS groessere Provenienz wird nicht heruntergesetzt. */
    @Test
    fun `eine groessere bestehende Grenze bleibt stehen`() {
        val v = entscheidung(0.30, FuseController.Block.NONE, "primeRelease")
            .copy(markerAuthorizedU = 0.20)
        assertEquals(v, MarkerFloor.apply(v, authCapU = 0.05, kernelValid = true))
    }

    /** Gleichstand erzeugt keinen irrefuehrenden `markerAuth|`-Grund fuer eine
     *  Menge, die ohnehin stand - die Grenze fährt aber auch hier mit. */
    @Test
    fun `bei Gleichstand bleibt der Grund unveraendert`() {
        val v = entscheidung(0.05, FuseController.Block.NONE, "primeRelease")
        val d = MarkerFloor.apply(v, authCapU = 0.05, kernelValid = true)
        assertEquals(0.05, d.smbU, 1e-9)
        assertEquals("primeRelease", d.bindingLimit, "hier hat nichts ueberstimmt")
        assertEquals(0.05, d.markerAuthorizedU, 1e-9)
    }
}
