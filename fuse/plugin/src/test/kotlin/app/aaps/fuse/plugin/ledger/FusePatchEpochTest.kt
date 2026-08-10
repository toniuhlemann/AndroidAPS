package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B3: DIE PATCH-EPOCHE.
 *
 * `PumpType` + Serial erkennen einen Patchwechsel derselben Pumpe nicht - die
 * Medtrum-Seriennummer ist die der BASIS und ueberlebt ihn. Gehalten hat das
 * bisher nur Wahrscheinlichkeit (5-min-Fenster + exakte Mengengleichheit).
 *
 * Die Epoche kommt deshalb aus dem neuesten gueltigen, PUMPENEIGENEN
 * CANNULA_CHANGE - nicht aus `patchId`, das im Treiber liegt und eine
 * FUSE-Invariante an eine private Stelle haengen wuerde.
 */
class FusePatchEpochTest {

    private val t0 = 1_700_000_000_000L
    private val typ = PumpType.MEDTRUM_NANO
    private val serial = "9C1DE26D"

    /** Dieselbe Normalisierung wie im Ledger - EINE Schreibweise fuer beide
     *  Seiten, sonst vergleicht man zwei Formen derselben Nummer. */
    private val hashOf: (String?, String?) -> String? = { s, t -> LedgerFacts.serialHashOf(s, t) }

    private fun wechsel(
        ts: Long = t0,
        pumpType: PumpType? = typ,
        pumpSerial: String? = serial,
        pumpId: Long? = 4711L,
        gueltig: Boolean = true,
    ) = TE(
        timestamp = ts,
        type = TE.Type.CANNULA_CHANGE,
        glucoseUnit = GlucoseUnit.MGDL,
        isValid = gueltig,
        ids = IDs(pumpType = pumpType, pumpSerial = pumpSerial, pumpId = pumpId),
    )

    private fun of(e: TE?, now: Long = t0 + 3600_000L) =
        FusePatchEpoch.of(e, typ.name, hashOf(serial, typ.name), now, hashOf)

    /**
     * DIE ECHTE FORM, wie der Medtrum-Treiber sie erzeugt
     * (`MedtrumPump.handleNewPatch`):
     *
     *     pumpSync.insertTherapyEventIfNewWithTimestamp(
     *         timestamp = newStartTime,
     *         type      = TE.Type.CANNULA_CHANGE,
     *         pumpType  = pumpType(),
     *         pumpSerial = pumpSN.toString(radix = 16),
     *     )
     *
     * KEIN `pumpId`. Und der Serial ist KLEINBUCHSTABEN-Hex - dieselbe
     * Faltungsfalle wie im Ledger, deshalb laeuft der Vergleich ueber
     * `LedgerFacts.serialHashOf`.
     */
    private fun echterMedtrumWechsel(ts: Long = t0) = TE(
        timestamp = ts,
        type = TE.Type.CANNULA_CHANGE,
        glucoseUnit = GlucoseUnit.MGDL,
        ids = IDs(pumpType = typ, pumpSerial = serial.lowercase(), pumpId = null),
    )

    /**
     * DER PFLICHTTEST (Codex-Re-Review 10.08., P0).
     *
     * Der erste Anlauf verlangte `IDs.isPumpHistory()`, also
     * `pumpSerial != null && pumpId != null`. Der Treiber setzt aber KEIN
     * `pumpId` - im Feld waere die Epoche damit IMMER unbekannt gewesen, und
     * die Publikationssperre aus Schritt 2 haette SMBs dauerhaft blockiert.
     *
     * Mein Test hatte das nicht gezeigt, weil er `pumpId = 4711` erfunden hat:
     * er prueft dann eine Form, die real nicht vorkommt.
     */
    @Test
    fun `der ECHTE Medtrum-Wechsel definiert die Epoche`() {
        val r = of(echterMedtrumWechsel())
        assertEquals(t0, r.epochTs) { "ohne diesen Fall waere die Epoche im Feld nie bekannt" }
        assertEquals(FusePatchEpoch.Reason.MATCHING_PUMP_IDENTITY, r.reason)
    }

    // ---- Wann ist eine Epoche bekannt? -----------------------------------

    @Test
    fun `ein pumpeneigener Wechsel der aktiven Pumpe definiert die Epoche`() {
        val r = of(wechsel())
        assertEquals(t0, r.epochTs)
        assertEquals(FusePatchEpoch.Reason.MATCHING_PUMP_IDENTITY, r.reason)
        assertTrue(r.known)
    }

    /**
     * EIN HANDEINTRAG DEFINIERT KEINE EPOCHE.
     *
     * Ohne Pumpenhistorie kann der Datensatz aus Nightscout, einem Import oder
     * einem Tippfehler stammen. Wuerde er die Epoche setzen, koennte eine
     * fremde Eintragung die Bindung eigener Zeilen umdeuten.
     */
    @Test
    fun `ein Handeintrag ohne jede Pumpenkennung definiert keine Epoche`() {
        val r = of(wechsel(pumpType = null, pumpSerial = null, pumpId = null))
        assertNull(r.epochTs)
        assertEquals(FusePatchEpoch.Reason.NOT_PUMP_ORIGIN, r.reason)
    }

    /** Halbe Kennung ist ein EIGENER Befund - nicht "fremd", sondern
     *  unvollstaendig. Wer den falschen Grund liest, sucht am falschen Ort. */
    @Test
    fun `eine halbe Pumpenkennung ist unvollstaendig, nicht fremd`() {
        assertEquals(FusePatchEpoch.Reason.EVENT_IDENTITY_INCOMPLETE, of(wechsel(pumpSerial = null)).reason)
        assertEquals(FusePatchEpoch.Reason.EVENT_IDENTITY_INCOMPLETE, of(wechsel(pumpType = null)).reason)
    }

    /** Ein anderer Ereignistyp definiert keine Patch-Epoche - auch nicht der
     *  INSULIN_CHANGE, den derselbe Treiberaufruf gleich mit erzeugt. */
    @Test
    fun `ein anderer Ereignistyp definiert keine Epoche`() {
        val insulin = TE(
            timestamp = t0, type = TE.Type.INSULIN_CHANGE, glucoseUnit = GlucoseUnit.MGDL,
            ids = IDs(pumpType = typ, pumpSerial = serial.lowercase()),
        )
        assertEquals(FusePatchEpoch.Reason.WRONG_TYPE, of(insulin).reason)
    }

    @Test
    fun `ein Wechsel einer FREMDEN Pumpe definiert keine Epoche`() {
        assertEquals(FusePatchEpoch.Reason.FOREIGN_PUMP, of(wechsel(pumpSerial = "AAAAAAAA")).reason)
        assertEquals(FusePatchEpoch.Reason.FOREIGN_PUMP, of(wechsel(pumpType = PumpType.OMNIPOD_DASH)).reason)
    }

    /** Unbekannt auf EINER Seite genuegt - ein fehlender Wert darf nicht als
     *  "passt schon" durchgehen. */
    @Test
    fun `unbekannte aktive Pumpe ist ein eigener Befund`() {
        for (r in listOf(
            FusePatchEpoch.of(wechsel(), null, hashOf(serial, typ.name), t0 + 3600_000L, hashOf),
            FusePatchEpoch.of(wechsel(), typ.name, null, t0 + 3600_000L, hashOf),
        )) {
            assertFalse(r.known)
            assertEquals(FusePatchEpoch.Reason.ACTIVE_PUMP_UNKNOWN, r.reason) {
                "das liegt NICHT am Datensatz - der Grund muss das sagen"
            }
        }
    }

    @Test
    fun `ungueltige oder fehlende Datensaetze lassen die Epoche unbekannt`() {
        assertEquals(FusePatchEpoch.Reason.NO_EVENT, of(null).reason)
        assertEquals(FusePatchEpoch.Reason.INVALID, of(wechsel(gueltig = false)).reason)
        assertEquals(FusePatchEpoch.Reason.INVALID, of(wechsel(ts = 0L)).reason)
        // Ein Wechsel aus der ZUKUNFT ist kein Wechsel. Die DB-Abfrage filtert
        // das zwar schon, aber die Grenze prueft es selbst.
        assertEquals(FusePatchEpoch.Reason.INVALID, of(wechsel(ts = t0 + 7200_000L), now = t0).reason)
    }

    /**
     * KEIN RUECKFALL AUF EINEN AELTEREN DATENSATZ (Codex-Auflage 10.08.).
     *
     * `getLastTherapyRecordUpToNow` liefert den NEUESTEN gueltigen Wechsel.
     * Ist der von Hand eingetragen oder fremd, bleibt die Epoche unbekannt -
     * es wird NICHT auf einen aelteren passenden zurueckgegriffen. Das waere
     * die Behauptung, seither sei nichts geschehen, und genau das ist
     * unbekannt. Die Folge ist die Publikationssperre, nicht eine geratene
     * Epoche.
     */
    @Test
    fun `ein neuerer fremder Wechsel macht die Epoche unbekannt`() {
        // Der neueste Datensatz ist ein Handeintrag - der aeltere passende
        // wird gar nicht erst befragt, weil er nie hereingereicht wird.
        val handeintrag = wechsel(ts = t0 + 60_000L, pumpType = null, pumpSerial = null, pumpId = null)
        val r = of(handeintrag)
        assertFalse(r.known) { "unbekannt heisst unbekannt - kein Rueckfall auf frueher" }
        assertEquals(FusePatchEpoch.Reason.NOT_PUMP_ORIGIN, r.reason)
    }

    // ---- Die Trennlinie --------------------------------------------------

    /** Der Kern von B3: nach einem Wechsel bindet die alte Zeile nichts Neues. */
    @Test
    fun `ein Vorschlag aus der alten Epoche bindet keinen Datensatz der neuen`() {
        val alt = t0
        val neu = t0 + 6 * 3600_000L
        assertFalse(FusePatchEpoch.sameEpoch(pinnedEpochTs = alt, currentEpochTs = neu, treatmentTs = neu + 60_000L))
    }

    @Test
    fun `innerhalb derselben Epoche bindet er weiter`() {
        assertTrue(FusePatchEpoch.sameEpoch(pinnedEpochTs = t0, currentEpochTs = t0, treatmentTs = t0 + 60_000L))
    }

    /** Ein Datensatz VOR dem Wechsel gehoert nie zu einem Vorschlag DANACH. */
    @Test
    fun `ein Datensatz vor dem Wechsel bindet nicht`() {
        assertFalse(FusePatchEpoch.sameEpoch(pinnedEpochTs = t0, currentEpochTs = t0, treatmentTs = t0 - 1L))
    }

    /** Beide Unbekannten sperren - im Zweifel wird nicht gebunden. */
    @Test
    fun `unbekannte Epochen binden nie`() {
        assertFalse(FusePatchEpoch.sameEpoch(null, t0, t0 + 1))
        assertFalse(FusePatchEpoch.sameEpoch(t0, null, t0 + 1))
        assertFalse(FusePatchEpoch.sameEpoch(null, null, t0 + 1))
    }
}
