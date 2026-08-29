package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER NACHWEIS "der Schutzgrund ist weg" - die Bedingung, an der der
 * Null-Abbruch haengt.
 *
 * Sie ist ein STRUKTURARGUMENT ueber die Reihenfolge in
 * [FuseController.decide]: die drei erlaubten Bloecke sind nur UNTERHALB
 * aller Schutzpruefungen erreichbar. Dieser Test haelt fest, welche Bloecke
 * das sind - und vor allem, welche es NICHT sind.
 */
class FuseTbrReasonGoneTest {

    private fun entscheidung(block: FuseController.Block) = FuseController.Decision(
        smbU = 0.0,
        tbr = FuseController.TbrAction.NO_NEW_POSITIVE,
        block = block,
        insulinReqU = 0.0,
        predAtReleaseMgdl = 120.0,
        minLowerMgdl = 95.0,
        bindingLimit = "test",
    )

    @Test
    fun `nur die Bloecke unterhalb der Schutzkette gelten als Nachweis`() {
        val erlaubt = setOf(
            FuseController.Block.NO_DEMAND,
            FuseController.Block.IOB_TH_REACHED,
            FuseController.Block.MAX_IOB_REACHED,
            // B1 (Tonis Entscheid 29.08.): ein erschoepfter Exposure-Raum
            // ist kein Gefahrensignal - er beendet eine stehende Null wie
            // die beiden IOB-Grenzen (Ledger/Rebound-Bedingungen gelten
            // unveraendert im Aufrufer).
            FuseController.Block.EXPOSURE_LIMIT,
        )
        for (b in FuseController.Block.entries) {
            val ergebnis = FuseTbrTranslator.reasonGone(entscheidung(b), ledgerHold = false, reboundWindow = false)
            if (b in erlaubt) assertTrue(ergebnis) { "$b sollte als Nachweis gelten" }
            else assertFalse(ergebnis) { "$b darf NICHT als Nachweis gelten" }
        }
    }

    /**
     * Der Ledger-Hold ist aus dem Block NICHT ablesbar: LedgerHoldGate
     * ueberschreibt ihn bei smbU <= 0 nicht. Eine NO_DEMAND-Entscheidung unter
     * Hold sieht deshalb aus wie eine ohne - genau die Falle, gegen die
     * derselbe Riegel geschrieben wurde.
     */
    @Test
    fun `ein Ledger-Hold verhindert den Nachweis`() {
        val d = entscheidung(FuseController.Block.NO_DEMAND)
        assertTrue(FuseTbrTranslator.reasonGone(d, ledgerHold = false, reboundWindow = false))
        assertFalse(FuseTbrTranslator.reasonGone(d, ledgerHold = true, reboundWindow = false))
    }

    /**
     * REBOUND-AUFLAGE (Replay 15.08.): nach einem Tief ist der Guard formal
     * frei, inhaltlich nicht - das aufgeblaehte r ist genau der Grund, warum
     * das Fenster existiert. Ohne diese Bedingung fiel der Abbruch in den
     * ersten Zyklus nach dem SafetyHold, waehrend das Rebound-Totband noch
     * jede Menge blockte.
     */
    @Test
    fun `im Rebound-Fenster gilt der Nachweis nicht`() {
        for (b in listOf(
            FuseController.Block.NO_DEMAND,
            FuseController.Block.IOB_TH_REACHED,
            FuseController.Block.MAX_IOB_REACHED,
        )) {
            assertFalse(FuseTbrTranslator.reasonGone(entscheidung(b), ledgerHold = false, reboundWindow = true)) {
                "$b im Rebound-Fenster"
            }
        }
    }

    /**
     * Das NACHT-Totband ist ausdruecklich NICHT ausgeschlossen - es traegt
     * NO_DEMAND, und genau dieser Fall ist der gemessene Anlass der ganzen
     * Aenderung (~100 min konservierte Null je Nacht).
     */
    @Test
    fun `das Nacht-Totband bleibt ein gueltiger Nachweis`() {
        val nachtTotband = entscheidung(FuseController.Block.NO_DEMAND)
            .copy(bindingLimit = "nightDeadband")
        assertTrue(FuseTbrTranslator.reasonGone(nachtTotband, ledgerHold = false, reboundWindow = false))
    }
}
