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
     * P0 (Codex 10.08.): EINE BESCHAEDIGTE v3-PATCHPINNUNG DARF NICHT ALS
     * "NICHT ANWENDBAR" DURCHGEHEN.
     *
     * Fehlten die Felder, las der Decoder `patchEpochApplicable = false` - und
     * `matchesPinnedEpoch` uebersprang die Patchpruefung VOLLSTAENDIG. Wieder
     * dieselbe Falle: ein fehlendes Feld sieht aus wie eine gueltige Aussage,
     * und zwar die harmloseste.
     *
     * v3 ist nie ohne sie ausgeliefert worden, also ist ihr Fehlen Korruption
     * und kein Altbestand.
     */
    @Test
    fun `eine v3-Patchpinnung ohne ihre Felder ist Korruption`(@TempDir dir: File) {
        for (feld in listOf("patchEpochApplicable", "patchEpochTs")) {
            val unter = File(dir, "ohne-$feld").also(File::mkdirs)
            val a = adapter(unter, t0 - 3600_000L)
            a.publish("p1", 0.30, t0)
            assertTrue(a.persistVerified(unter))

            val target = File(unter, FuseLedgerStore.FILE_NAME)
            val o = org.json.JSONObject(target.readText())
            val pins = o.getJSONArray("proposalPumpEpochs")
            for (i in 0 until pins.length()) pins.getJSONObject(i).remove(feld)
            target.writeText(o.toString())

            val b = FuseLedgerAdapter().also { it.loadOnce(unter, "epoch-b", t0 + 60_000L, medtrum.name) }
            assertTrue(b.recoveryHold) { "$feld fehlt in einer v3-Patchpinnung - das ist Korruption, kein Altbestand" }
        }
    }

    /**
     * DIE FEINERE VARIANTE DERSELBEN FALLE (Codex 10.08.): Feld VORHANDEN,
     * aber `false`.
     *
     * Eine reine Anwesenheitspruefung laesst das durch; danach wird der Pin
     * als "nicht anwendbar" dekodiert und umgeht die Patchpruefung erneut.
     * Der Schreiber setzt an einem Patchpumpen-Pin IMMER `true` - alles
     * andere ist Korruption.
     */
    @Test
    fun `eine v3-Patchpinnung mit applicable=false ist Korruption`(@TempDir dir: File) {
        val a = adapter(dir, t0 - 3600_000L)
        a.publish("p1", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText())
        val pins = o.getJSONArray("proposalPumpEpochs")
        for (i in 0 until pins.length()) {
            pins.getJSONObject(i).put("patchEpochApplicable", false)
            pins.getJSONObject(i).put("patchEpochTs", 123L)
        }
        target.writeText(o.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, medtrum.name) }
        assertTrue(b.recoveryHold) {
            "Praesenz genuegt nicht - der Wert muss stimmen, sonst ist die Pruefung wieder aus"
        }
    }

    /** Die Gegenprobe: eine NICHT-Patch-Pinnung traegt die Felder nie und
     *  bleibt gueltig - sonst haette die Pflicht die VirtualPump erschlagen. */
    @Test
    fun `eine v3-Pinnung ohne Patchbezug bleibt ohne diese Felder gueltig`(@TempDir dir: File) {
        val vp = PumpType.GENERIC_AAPS
        val a = adapter(dir, null)
        a.onPublished("p1", 0.30, t0, 0L, 0.05, vp.name, LedgerFacts.serialHashOf("vs", vp.name))
        assertTrue(a.persistVerified(dir))
        val roh = File(dir, FuseLedgerStore.FILE_NAME).readText()
        assertFalse(roh.contains("patchEpochApplicable")) { "fuer eine Nicht-Patch-Pumpe gibt es die Kategorie nicht" }

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, vp.name) }
        assertFalse(b.recoveryHold)
    }

    // ---- P0: die pinlose v1-Zeile ----------------------------------------

    /**
     * P0 (Codex 10.08.): EINE v1-ZEILE HAT GAR KEINEN PIN.
     *
     * `unmigratablePatchRows` uebersprang sie deshalb, und `coveredEpochs`
     * machte spaeter LEGACY_OPEN daraus - also "bindet wie bisher", ganz ohne
     * Typ-, Serial- und Patchpruefung. Auf einer echten Medtrum ist das exakt
     * das Loch, gegen das B3 gebaut ist.
     *
     * Ob sie gefahrlos weiterbinden darf, haengt daran, WELCHE Pumpe heute
     * laeuft - deshalb braucht die Migration den Pumpenkontext.
     */
    @Test
    fun `eine pinlose v1-Zeile haelt an, wenn heute eine Medtrum laeuft`(@TempDir dir: File) {
        val a = adapter(dir, t0 - 3600_000L)
        a.publish("alt", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText()).put("v", 1)
        o.remove("proposalPumpEpochs")   // v1 kannte die Pinnung noch nicht
        target.writeText(o.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, medtrum.name) }
        assertTrue(b.recoveryHold) {
            "ohne Pin und mit aktiver Patchpumpe wuerde die Zeile ungeprueft binden - Hold"
        }
    }

    /** Dieselbe Datei, aber heute laeuft eine Nicht-Patch-Pumpe: dann ist das
     *  alte Bindungsverhalten unbedenklich und die Migration laeuft durch. */
    @Test
    fun `dieselbe pinlose Zeile migriert unter einer Nicht-Patch-Pumpe`(@TempDir dir: File) {
        val vp = PumpType.GENERIC_AAPS
        val a = adapter(dir, null)
        a.onPublished("alt", 0.30, t0, 0L, 0.05, vp.name, LedgerFacts.serialHashOf("vs", vp.name))
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText()).put("v", 1)
        o.remove("proposalPumpEpochs")
        target.writeText(o.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, vp.name) }
        assertFalse(b.recoveryHold)
    }

    /** UNBEKANNTE aktive Pumpe: Hold. Eine VirtualPump anzunehmen waere
     *  geraten, und zwar in die gefaehrliche Richtung. */
    @Test
    fun `bei unbekannter aktiver Pumpe haelt die pinlose Zeile an`(@TempDir dir: File) {
        val vp = PumpType.GENERIC_AAPS
        val a = adapter(dir, null)
        a.onPublished("alt", 0.30, t0, 0L, 0.05, vp.name, LedgerFacts.serialHashOf("vs", vp.name))
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText()).put("v", 1)
        o.remove("proposalPumpEpochs")
        target.writeText(o.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, null) }
        assertTrue(b.recoveryHold) { "unbekannt ist nicht VirtualPump" }
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
