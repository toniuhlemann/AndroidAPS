package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.Pump

/**
 * Die LESENDE Seite der Patch-Epoche (B3).
 *
 * Sie steht hier und nicht inline im Plugin, weil genau EINE Eigenschaft
 * nachweisbar sein muss: **es wird genau einmal gefragt, und es gibt keinen
 * zweiten Suchpfad.**
 *
 * `getLastTherapyRecordUpToNow` liefert den NEUESTEN gueltigen Wechsel. Passt
 * der nicht zur aktiven Pumpe, ist die Epoche unbekannt - auf einen aelteren
 * passenden auszuweichen waere die Behauptung, seither sei nichts geschehen,
 * und genau das ist unbekannt. Die Folge ist die Publikationssperre, nicht
 * eine geratene Epoche.
 *
 * Eine inline im Zyklus stehende Abfrage koennte man nicht darauf pruefen;
 * ein spaeterer "Fallback" waere dort eine Zeile und faellt niemandem auf.
 *
 * GEMESSEN AM GERAET (Pixel 9, 10.08.2026), damit die Annahmen hier belegt
 * sind und nicht aus dem Treibercode geraten:
 *  - der Datensatz ist gueltig und wird von AAPS erzeugt;
 *  - `pumpId` ist `null` - deshalb prueft [FusePatchEpoch] Typ+Serial und
 *    nicht `IDs.isPumpHistory()`;
 *  - der Serial kommt als KLEINBUCHSTABEN-Hex, daher der Vergleich ueber
 *    [LedgerFacts.serialHashOf];
 *  - der Typ liegt in der DB als `MEDTRUM` und wird von der oeffentlichen
 *    Persistence-API zu `MEDTRUM_NANO` konvertiert
 *    (`PumpTypeExtension.kt:40`) - der Vergleich mit `pump.model().name`
 *    passt also;
 *  - der Nightscout-Ruecklauf AKTUALISIERT denselben Datensatz, statt einen
 *    konkurrierenden anzulegen. Es gibt also keinen zweiten, neueren Wechsel,
 *    der die Epoche verdraengen koennte;
 *  - der Zeitstempel liegt rund 2,9 s NACH dem spaeter gemeldeten
 *    Pumpen-Startzeitpunkt. Unproblematisch: er bleibt ein eindeutiger Anker,
 *    und FUSE kann erst danach Vorschlaege erzeugen.
 */
object FusePatchEpochSource {

    fun current(
        persistenceLayer: PersistenceLayer,
        pump: Pump?,
        nowTs: Long,
    ): FusePatchEpoch.Result {
        // GENAU EINE Abfrage. Kein zweiter Aufruf, keine Schleife, kein
        // Rueckfall - der Test darunter haelt das fest.
        val letzterWechsel = runCatching {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        }.getOrNull()

        val typ = runCatching { pump?.model()?.name }.getOrNull()
        return FusePatchEpoch.of(
            event = letzterWechsel,
            activePumpTypeName = typ,
            activeSerialHash = LedgerFacts.serialHashOf(runCatching { pump?.serialNumber() }.getOrNull(), typ),
            nowTs = nowTs,
            serialHashOf = LedgerFacts::serialHashOf,
        )
    }
}
