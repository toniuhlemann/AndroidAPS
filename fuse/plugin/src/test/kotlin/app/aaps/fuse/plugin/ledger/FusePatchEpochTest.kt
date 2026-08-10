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

    private fun of(e: TE?) = FusePatchEpoch.of(e, typ.name, hashOf(serial, typ.name), hashOf)

    // ---- Wann ist eine Epoche bekannt? -----------------------------------

    @Test
    fun `ein pumpeneigener Wechsel der aktiven Pumpe definiert die Epoche`() {
        val r = of(wechsel())
        assertEquals(t0, r.epochTs)
        assertEquals(FusePatchEpoch.Reason.PUMP_ORIGIN, r.reason)
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
    fun `ein Eintrag ohne Pumpenhistorie definiert keine Epoche`() {
        for (e in listOf(
            wechsel(pumpId = null),
            wechsel(pumpSerial = null),
            wechsel(pumpId = null, pumpSerial = null),
        )) {
            val r = of(e)
            assertNull(r.epochTs)
            assertEquals(FusePatchEpoch.Reason.NOT_PUMP_ORIGIN, r.reason)
        }
    }

    @Test
    fun `ein Wechsel einer FREMDEN Pumpe definiert keine Epoche`() {
        assertEquals(FusePatchEpoch.Reason.FOREIGN_PUMP, of(wechsel(pumpSerial = "AAAAAAAA")).reason)
        assertEquals(FusePatchEpoch.Reason.FOREIGN_PUMP, of(wechsel(pumpType = PumpType.OMNIPOD_DASH)).reason)
    }

    /** Unbekannt auf EINER Seite genuegt - ein fehlender Wert darf nicht als
     *  "passt schon" durchgehen. */
    @Test
    fun `unbekannte aktive Pumpe laesst die Epoche unbekannt`() {
        assertFalse(FusePatchEpoch.of(wechsel(), null, hashOf(serial, typ.name), hashOf).known)
        assertFalse(FusePatchEpoch.of(wechsel(), typ.name, null, hashOf).known)
    }

    @Test
    fun `ungueltige oder fehlende Datensaetze lassen die Epoche unbekannt`() {
        assertEquals(FusePatchEpoch.Reason.NO_EVENT, of(null).reason)
        assertEquals(FusePatchEpoch.Reason.INVALID, of(wechsel(gueltig = false)).reason)
        assertEquals(FusePatchEpoch.Reason.INVALID, of(wechsel(ts = 0L)).reason)
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
