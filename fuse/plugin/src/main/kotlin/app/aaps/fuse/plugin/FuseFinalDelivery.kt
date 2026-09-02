package app.aaps.fuse.plugin

/**
 * WAS AAPS AM ENDE TATSAECHLICH BEKOMMT.
 *
 * ===================================================================
 * DIE KETTE, UND WO SIE AUFHOERT
 * ===================================================================
 *     Runner -> applyBlock -> Pumpen-Gate -> PUBLIKATIONS-GATE -> APSResult
 *
 * Der Runner bildet seine Zahl VOR dem letzten Tor. Entfernt das
 * Publikations-Gate die Zeile danach, uebergibt FUSE gar keinen SMB an
 * AAPS - der Export zeigte trotzdem eine positive Menge, und die Anzeige
 * daneben "FREI". Genau diese Luecke schliesst diese Funktion.
 *
 * ===================================================================
 * WAS DIE ZAHL HEISST - UND WAS NICHT
 * ===================================================================
 * "AN AAPS UEBERGEBEN". NICHT "von der Pumpe bestaetigt": das ist die
 * naechste Grenze und eine andere Zahl. Zwischen dieser Uebergabe und
 * einer tatsaechlichen Abgabe liegen die AAPS-Constraints, die
 * Kommandowarteschlange und die Pumpe selbst.
 *
 * Die frueheren Mengen bleiben als DIAGNOSE erhalten (`requestedU` vor
 * der Endpruefung, `publishedU` danach) - sie sagen, wo etwas verloren
 * ging, aber keine von ihnen ist eine Abgabe.
 *
 * Reine Funktion, weil `FusePlugin.invoke()` keinen Testrahmen hat: so
 * ist die Entscheidung pruefbar, und im Plugin steht nur noch der Aufruf.
 */
object FuseFinalDelivery {

    /** Der Praefix, an dem die Anzeige das letzte Tor erkennt. */
    const val PUBLICATION_BLOCK = "PUBLICATION_GATE"

    /**
     * @param runnerActuatedU  was der Runner nach Riegel und Pumpen-Gate sah.
     * @param runnerFinalBlock sein Sperrgrund - eine Stufe vor dem letzten Tor.
     * @param publishedUnits   `publishRt.units` NACH dem Publikations-Gate;
     *                         `null` heisst: keine SMB-Zeile im Ergebnis.
     * @param gateAllowed      hat das Publikations-Gate die Zeile zugelassen?
     * @param angeforderteZeileVorhanden war ueberhaupt eine Menge da, die das
     *                         Gate haette entfernen koennen?
     * @param gateReason       der Grund des Gates, fuer die Diagnose.
     */
    data class Endstand(val actuatedU: Double, val finalBlock: String?)

    fun bestimme(
        runnerActuatedU: Double?,
        runnerFinalBlock: String?,
        publishedUnits: Double?,
        gateAllowed: Boolean,
        angeforderteZeileVorhanden: Boolean,
        gateReason: String?,
    ): Endstand {
        // Das Gate hat eine VORHANDENE Menge entfernt - dann ist SEIN Grund
        // der finale, und die uebergebene Menge ist null.
        val gestrichen = !gateAllowed && angeforderteZeileVorhanden
        if (gestrichen) return Endstand(
            actuatedU = 0.0,
            finalBlock = listOfNotNull(PUBLICATION_BLOCK, gateReason?.takeIf { it.isNotBlank() })
                .joinToString("|"),
        )
        // Sonst gilt, was tatsaechlich in der Zeile steht. `null` heisst
        // "keine SMB-Zeile", also 0 - nicht "unbekannt": an dieser Stelle
        // ist das Ergebnis fertig.
        val menge = publishedUnits ?: 0.0
        return Endstand(
            actuatedU = menge,
            // Ging etwas hinaus, gibt es keinen Sperrgrund - auch dann nicht,
            // wenn der Runner eine Stufe davor noch einen sah.
            finalBlock = if (menge > 0.0) null else runnerFinalBlock,
        )
    }
}
