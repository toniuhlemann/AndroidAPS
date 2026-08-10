package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * B3 VERDRAHTET: die Patch-Epoche in der Bindung.
 *
 * Die Regel selbst steht in [FusePatchEpochTest]. Hier geht es darum, dass sie
 * im Adapter WIRKT - und dass sie nur dort wirkt, wo sie hingehoert.
 *
 * Der Fall, um den es geht: die Medtrum-Seriennummer gehoert der BASIS und
 * ueberlebt den Patchwechsel. Ein Vorschlag von vor dem Wechsel koennte also
 * einen Bolus des NEUEN Patches binden und darueber geschlossen werden -
 * gehalten hat das bisher nur Wahrscheinlichkeit.
 */
class PatchEpochBindingTest {

    private val t0 = 1_700_000_000_000L
    private val medtrum = PumpType.MEDTRUM_NANO
    private val serial = "9c1de26d"

    private fun bolus(ts: Long, u: Double, pumpId: Long, typ: PumpType = medtrum, s: String? = serial) = BS(
        timestamp = ts, amount = u, type = BS.Type.SMB,
        ids = IDs(pumpType = typ, pumpSerial = s, pumpId = pumpId),
    )

    private fun adapter(dir: File, epoche: Long?) = FuseLedgerAdapter().also {
        it.loadOnce(dir, "epoch-a", t0)
        it.observePatchEpoch(epoche)
    }

    private fun FuseLedgerAdapter.publish(id: String, u: Double, ts: Long, typ: PumpType = medtrum) =
        onPublished(id, u, ts, 0L, 0.05, typ.name, LedgerFacts.serialHashOf(serial, typ.name))

    // ---- Der Kern: der Patchwechsel trennt -------------------------------

    /**
     * DER FALL, GEGEN DEN B3 GEBAUT IST.
     *
     * Vorschlag in Epoche A, danach Patchwechsel, dann ein Bolus der GLEICHEN
     * Menge im neuen Patch. Type und Serial passen - die Basis ist ja
     * dieselbe. Frueher haette die alte Zeile ihn gebunden und sich darueber
     * geschlossen.
     */
    @Test
    fun `nach einem Patchwechsel bindet die alte Zeile keinen neuen Bolus`(@TempDir dir: File) {
        val epocheA = t0 - 6 * 3600_000L
        val a = adapter(dir, epocheA)
        a.publish("alt", 0.30, t0)

        // PATCHWECHSEL: neue Epoche.
        val epocheB = t0 + 60_000L
        a.observePatchEpoch(epocheB)

        // Ein Bolus des NEUEN Patches - gleiche Menge, gleiche Basis-Serial.
        a.bindIdentities(listOf(bolus(t0 + 120_000L, 0.30, pumpId = 4711L)))

        assertNull(a.state.entries.getValue("alt").identity) {
            "die alte Zeile darf einen Bolus des neuen Patches nicht binden"
        }
        assertEquals(0.30, a.view().transportCommitmentU, 1e-9) {
            "und sie haelt konservativ ihre volle Haftung"
        }
    }

    /** Innerhalb DERSELBEN Epoche bindet sie unveraendert. */
    @Test
    fun `innerhalb derselben Patch-Epoche bindet die Zeile weiter`(@TempDir dir: File) {
        val a = adapter(dir, t0 - 3600_000L)
        a.publish("p1", 0.30, t0)
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30, pumpId = 4711L)))
        assertNotNull(a.state.entries.getValue("p1").identity)
    }

    /**
     * UNBEKANNTE EPOCHE SPERRT DIE BINDUNG - bei einer Patchpumpe.
     *
     * Sie ist nicht "keine Epoche": ob seit der Entscheidung gewechselt wurde,
     * weiss dann niemand. Die Zeile haelt konservativ ihre Haftung.
     */
    @Test
    fun `bei unbekannter Epoche bindet eine Medtrum-Zeile nicht`(@TempDir dir: File) {
        val a = adapter(dir, null)
        a.publish("p1", 0.30, t0)
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30, pumpId = 4711L)))
        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-9)
    }

    /** Ein Bolus VOR dem Wechsel gehoert nicht zu einer Zeile danach. */
    @Test
    fun `ein Bolus vor der Epoche bindet nicht`(@TempDir dir: File) {
        val epoche = t0 + 60_000L
        val a = adapter(dir, epoche)
        a.publish("p1", 0.30, t0 + 120_000L)
        a.bindIdentities(listOf(bolus(epoche - 1_000L, 0.30, pumpId = 4711L)))
        assertNull(a.state.entries.getValue("p1").identity)
    }

    // ---- Die Abgrenzung: nur Patchpumpen ---------------------------------

    /**
     * DIE VIRTUALPUMP BINDET UNVERAENDERT.
     *
     * Sie hat keine Patches und erzeugt keinen CANNULA_CHANGE - ihre Epoche
     * waere IMMER unbekannt. Wuerde die Regel auch fuer sie gelten, haette B3
     * den ganzen Entwicklungspfad still stillgelegt. Genau deshalb traegt der
     * Pin `patchEpochApplicable` und nicht nur einen Zeitstempel.
     */
    @Test
    fun `die VirtualPump bindet ohne Patch-Epoche weiter`(@TempDir dir: File) {
        val a = adapter(dir, null)   // Epoche unbekannt - und das ist hier egal
        val vp = PumpType.GENERIC_AAPS
        a.onPublished("p1", 0.30, t0, 0L, 0.05, vp.name, LedgerFacts.serialHashOf("vs", vp.name))
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30, pumpId = 4711L, typ = vp, s = "vs")))
        assertNotNull(a.state.entries.getValue("p1").identity) {
            "ohne Patches gibt es keine Patch-Epoche - die Zeile bindet wie bisher"
        }
    }

    /** Und der Basiswechsel greift weiterhin: eine FREMDE Pumpe bindet auch
     *  innerhalb einer passenden Epoche nicht. Die Epoche ERSETZT die
     *  Identitaetspruefung nicht, sie ergaenzt sie. */
    @Test
    fun `ein Basiswechsel bindet auch bei passender Epoche nicht`(@TempDir dir: File) {
        val epoche = t0 - 3600_000L
        val a = adapter(dir, epoche)
        a.publish("p1", 0.30, t0)
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30, pumpId = 4711L, s = "aabbccdd")))
        assertNull(a.state.entries.getValue("p1").identity)
    }

    // ---- Restart ---------------------------------------------------------

    /**
     * Die gepinnte Epoche ueberlebt den Neustart - sonst waere die Trennlinie
     * nach jedem Prozesswechsel weg, und genau dann ist sie am wichtigsten
     * (ein Patchwechsel geht typisch mit einem Neustart einher).
     */
    @Test
    fun `die gepinnte Epoche ueberlebt den Neustart`(@TempDir dir: File) {
        val epocheA = t0 - 6 * 3600_000L
        val a = adapter(dir, epocheA)
        a.publish("alt", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        // Neustart, und inzwischen wurde der Patch gewechselt.
        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-b", t0 + 120_000L)
            it.observePatchEpoch(t0 + 60_000L)
        }
        assertFalse(b.recoveryHold)
        b.bindIdentities(listOf(bolus(t0 + 180_000L, 0.30, pumpId = 4711L)))
        assertNull(b.state.entries.getValue("alt").identity) {
            "auch nach dem Neustart trennt die Epoche"
        }
    }

    /**
     * MIGRATION ERFINDET KEINE EPOCHE.
     *
     * Eine Altgeneration (v1/v2) traegt fuer ihre Medtrum-Zeilen keine
     * Patch-Epoche, und die damalige ist nicht mehr feststellbar. Die AKTUELLE
     * rueckwirkend anzuheften waere die Behauptung, seither sei kein Patch
     * gewechselt worden - und die Zeile duerfte dann einen Bolus des neuen
     * Patches binden. Also: Hold statt Rateschluss.
     */
    @Test
    fun `eine Medtrum-Altzeile ohne Epoche geht in den Hold statt zu raten`(@TempDir dir: File) {
        val a = adapter(dir, t0 - 3600_000L)
        a.publish("alt", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        // Auf v2 zuruecksetzen UND die Patch-Epoche aus dem Pin entfernen -
        // so saehe eine Generation von vor B3 aus.
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText()).put("v", 2)
        val pins = o.getJSONArray("proposalPumpEpochs")
        for (i in 0 until pins.length()) {
            pins.getJSONObject(i).remove("patchEpochTs")
            pins.getJSONObject(i).remove("patchEpochApplicable")
        }
        target.writeText(o.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L) }
        assertTrue(b.recoveryHold) {
            "die aktuelle Epoche rueckwirkend anzuheften waere geraten - Hold ist die richtige Antwort"
        }
    }
}
