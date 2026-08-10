package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import app.aaps.fuse.plugin.FuseActivePump

/**
 * DIE EMULIERTE PUMPE IST KEINE PATCHPUMPE.
 *
 * `VirtualPumpPlugin.model()` gibt `pumpDescription.pumpType` zurueck, und das
 * folgt der Nutzerpreference `VirtualPumpType`. Auf dem Testgeraet steht sie
 * auf **Medtrum Nano** - absichtlich, damit der Bolusschritt 0,05 dem echten
 * Geraet entspricht.
 *
 * B3 las bis hierher nur den Typnamen. Damit galt die Emulation als physische
 * Patchpumpe: sie brauchte eine Patch-Epoche, konnte nie eine haben (es gibt
 * keinen CANNULA_CHANGE) und haette den gesamten Entwicklungspfad gesperrt -
 * jeder SMB mit `REAL_PUMP_EPOCH_UNKNOWN`, keine Bindung, ein Geraetelauf ohne
 * Aussagekraft.
 *
 * Es ist dieselbe Fehlerklasse wie ueberall in diesem Projekt, nur
 * spiegelverkehrt zu `MEDTRUM_UNTESTED`: dort sah ein UNBEKANNTER Typ nach
 * einem gueltigen aus, hier ein EMULIERTER nach einem echten. Gegen beides
 * hilft keine Wertpruefung am Typ, sondern nur ein zweites, getrennt erhobenes
 * Merkmal - und das muss PERSISTIERT werden, sonst wird es nach dem Neustart
 * wieder aus dem Typnamen geraten.
 */
class VirtualPumpEmulationTest {

    private val t0 = 1_700_000_000_000L
    private val medtrum = PumpType.MEDTRUM_NANO

    /** Die Serial der EMULATION - eine andere als die des echten Geraets. */
    private val vSerial = "eWAcAOrQTemFNR7M5j7F_8"

    // `pumpId` gesetzt: ohne pumpId ODER temporaryId ist ein Bolus gar kein
    // Bindungskandidat. Der Pixel-9-Befund "pumpId ist null" galt dem
    // CANNULA_CHANGE-Datensatz, nicht den Boli - die Stelle ist leicht zu
    // verwechseln und kostete hier einen Testdurchlauf.
    private fun bolus(ts: Long, u: Double, pumpId: Long = 4711L, s: String = vSerial) = BS(
        timestamp = ts, amount = u, type = BS.Type.SMB,
        ids = IDs(pumpType = medtrum, pumpSerial = s, pumpId = pumpId),
    )

    /** Die EMULATION: Medtrum-Typname, aber VirtualPump-Klasse. */
    private val emuliert = FuseActivePump(medtrum.name, virtualPump = true)

    /** Eine BEKANNTE Patch-Epoche - fuer die Gegenprobe zur Sperre. */
    private val bekannteEpoche = FusePatchEpoch.Result(
        epochTs = t0, reason = FusePatchEpoch.Reason.MATCHING_PUMP_IDENTITY,
    )

    /** Das ECHTE Geraet. */
    private val echt = FuseActivePump(medtrum.name, virtualPump = false)

    private fun FuseLedgerAdapter.publishEmuliert(id: String, u: Double, ts: Long) =
        onPublished(
            id, u, ts, 0L, 0.05,
            pumpTypeName = medtrum.name,
            pumpSerialHash = LedgerFacts.serialHashOf(vSerial, medtrum.name),
            virtualPump = true,
        )

    // ---- Der Kern --------------------------------------------------------

    /**
     * DER FALL, DER DEN PIXEL-6-LAUF BLOCKIERT HAETTE.
     *
     * Epoche unbekannt (auf der VirtualPump ist sie das immer), Typname
     * MEDTRUM_NANO - und die Zeile muss trotzdem binden.
     */
    @Test
    fun `die emulierte Medtrum bindet ohne Patch-Epoche`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.publishEmuliert("p1", 0.30, t0)
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30)))

        assertNotNull(a.state.entries.getValue("p1").identity) {
            "gegen die Emulation gibt es keine Patches - die Zeile bindet wie bisher"
        }
    }

    /** Und die Sperre der Publikation greift dort ebenfalls nicht. */
    @Test
    fun `die Emulation loest keine Epochensperre aus`() {
        assertFalse(emuliert.realPumpEpochUnknown) {
            "auf der Emulation ist eine unbekannte Epoche der Normalzustand, kein Sperrgrund"
        }
        assertTrue(echt.realPumpEpochUnknown) {
            "am ECHTEN Geraet bleibt sie die Sperre, sonst waere B3 wirkungslos"
        }
        assertFalse(echt.copy(patchEpoch = bekannteEpoche).realPumpEpochUnknown)
    }

    /** Die Gegenprobe zur Fehlerklasse: der Typname allein traegt die
     *  Unterscheidung NICHT - beide heissen MEDTRUM_NANO. */
    @Test
    fun `Emulation und echtes Geraet sind am Typnamen nicht zu unterscheiden`() {
        assertEquals(emuliert.pumpTypeName, echt.pumpTypeName)
        assertTrue(ProposalPumpEpoch.appliesTo(emuliert.pumpTypeName)) {
            "genau deshalb reicht appliesTo() allein nicht"
        }
        assertEquals(false, emuliert.realPatchPump)
        assertEquals(true, echt.realPatchPump)
    }

    // ---- Persistenz ------------------------------------------------------

    /**
     * PFLICHTNACHWEIS: das Flag ueberlebt den Neustart und wird NICHT erneut
     * aus dem Typnamen geraten.
     *
     * Wuerde es nach dem Laden aus `pumpTypeName` rekonstruiert, waere die
     * Zeile wieder eine Patchpumpen-Zeile, verlangte eine Epoche und bliebe
     * ungebunden - der Fehler waere nur um einen Prozessstart verschoben.
     */
    @Test
    fun `das Emulationsflag ueberlebt den Neustart`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.publishEmuliert("p1", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        // Es steht wirklich in der Datei - nicht nur im Speicher. Genau diese
        // Haelfte fehlte bei einem frueheren Codec-Patch: dekodiert wurde das
        // neue Feld, geschrieben nie.
        val roh = File(dir, FuseLedgerStore.FILE_NAME).readText()
        assertTrue(roh.contains("\"virtualPump\":true")) {
            "ohne persistiertes Flag muesste es nach dem Neustart geraten werden"
        }

        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-b", t0 + 60_000L, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        assertFalse(b.recoveryHold)
        b.bindIdentities(listOf(bolus(t0 + 120_000L, 0.30)))
        assertNotNull(b.state.entries.getValue("p1").identity) {
            "nach dem Neustart bindet sie weiterhin ohne Patch-Epoche"
        }
    }

    /**
     * Und das Flag wird beim Lesen VERLANGT. Fehlt es an einer v3-Pinnung, ist
     * das Korruption und nicht "war halt eine echte Pumpe" - sonst waere das
     * fehlende Feld wieder eine stille, harmlos aussehende Aussage.
     */
    @Test
    fun `eine v3-Pinnung ohne virtualPump ist Korruption`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.publishEmuliert("p1", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText())
        val pins = o.getJSONArray("proposalPumpEpochs")
        for (i in 0 until pins.length()) pins.getJSONObject(i).remove("virtualPump")
        target.writeText(o.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "e-b", t0 + 60_000L, emuliert) }
        assertTrue(b.recoveryHold) { "ein fehlendes Flag darf nicht still als 'echt' gelten" }
    }

    // ---- Die Korruptionsgegenprobe ---------------------------------------

    /**
     * `MEDTRUM_NANO + virtualPump=false + patchEpochApplicable=false` BLEIBT
     * UNGUELTIG.
     *
     * Das ist die Kombination, mit der man sonst an der Patchpruefung
     * vorbeikaeme: als echt deklariert, aber ohne Zustaendigkeit. Genau davor
     * warnte Codex' P0, und genau diese Deutung darf das neue Flag nicht
     * verwaessern.
     */
    @Test
    fun `eine als echt deklarierte Medtrum-Pinnung ohne Anwendbarkeit ist ungueltig`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProposalPumpEpoch(medtrum.name, "hash", patchEpochApplicable = false, virtualPump = false)
        }
    }

    /** Die beiden anderen Invarianten: gegen die Emulation gibt es weder eine
     *  Zustaendigkeit noch einen Zeitstempel. */
    @Test
    fun `die Emulation kann keine Patch-Epoche tragen`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProposalPumpEpoch(medtrum.name, "hash", patchEpochApplicable = true, virtualPump = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProposalPumpEpoch(
                medtrum.name, "hash",
                patchEpochTs = t0, patchEpochApplicable = true, virtualPump = true,
            )
        }
    }

    /**
     * PUNKT 9 DER AUFLAGE: eine UNPINNED-Zeile darf durch `virtualPump=true`
     * NICHT ploetzlich bindbar werden.
     *
     * UNPINNED entsteht, wenn die Identitaet unbekannt blieb (z.B. der noch
     * nicht aufgeloeste Serial nach einem Prozessstart). Diese Zeile bindet
     * NIE - und die Emulation ist kein Grund, daran etwas zu lockern.
     */
    @Test
    fun `eine UNPINNED-Zeile bleibt auch unter der Emulation unbindbar`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        // Weder Typ noch Serial lesbar -> UNPINNED, trotz laufender Emulation.
        a.onPublished("p1", 0.30, t0, 0L, 0.05, null, null, virtualPump = true)
        assertEquals(ProposalPumpEpoch.UNPINNED, a.proposalPumpEpochs["p1"])

        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30)))
        assertNull(a.state.entries.getValue("p1").identity) {
            "UNPINNED bindet nie - die Emulation aendert daran nichts"
        }
        assertEquals(0.30, a.view().transportCommitmentU, 1e-9) {
            "und die Zeile haelt konservativ ihre volle Haftung"
        }
    }

    /** Ein Marker traegt ueberhaupt keinen Inhalt - auch nicht dieses Flag. */
    @Test
    fun `ein Marker-Pin traegt kein Emulationsflag`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProposalPumpEpoch(null, null, unpinned = true, virtualPump = true)
        }
    }

    // ---- Altbestand ------------------------------------------------------

    /**
     * BEFUND BEIM BAU DIESES FIXES: eine BESTEHENDE v2-Datei der Emulation
     * waere dauerhaft im Hold gelandet.
     *
     * Ihre Pins tragen `pumpType: MEDTRUM_NANO` - geschrieben von der
     * emulierten Pumpe. Die Migration prueft aber "ist der Typ eine
     * Patchpumpe?" und nicht "war es eine?", fand keine Epoche und brach ab.
     * Auf dem Testgeraet mit VirtualPump=Medtrum Nano waere das jede
     * vorhandene Datei gewesen, und der Hold haette den ganzen Lauf entwertet.
     *
     * Ob die Zeile gefahrlos migrieren darf, haengt daran, WELCHE Pumpe heute
     * laeuft - dieselbe Frage wie bei der pinlosen v1-Zeile.
     */
    @Test
    fun `eine v2-Altzeile migriert unter der Emulation`(@TempDir dir: File) {
        schreibeV2Altbestand(dir)
        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "e-b", t0 + 60_000L, emuliert) }
        assertFalse(b.recoveryHold) {
            "die Pins stammen von der Emulation - sie duerfen nicht dauerhaft blockieren"
        }
    }

    /** Dieselbe Datei, aber heute laeuft das ECHTE Geraet: dann ist nicht
     *  beweisbar, dass die Zeile zur aktuellen Patchgeneration gehoert. */
    @Test
    fun `dieselbe v2-Altzeile haelt unter dem echten Geraet an`(@TempDir dir: File) {
        schreibeV2Altbestand(dir)
        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "e-b", t0 + 60_000L, echt) }
        assertTrue(b.recoveryHold) {
            "am echten Geraet waere das Migrieren die Behauptung, seither sei kein Patch gewechselt worden"
        }
    }

    /** Und bei unbekannter aktiver Pumpe ebenfalls Hold - raten waere hier in
     *  die gefaehrliche Richtung. */
    @Test
    fun `dieselbe v2-Altzeile haelt bei unbekannter Pumpe an`(@TempDir dir: File) {
        schreibeV2Altbestand(dir)
        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-b", t0 + 60_000L, FuseActivePump.UNKNOWN)
        }
        assertTrue(b.recoveryHold)
    }

    /**
     * Eine Generation VOR B3: Medtrum-Typname im Pin, aber keines der
     * Patchfelder und kein Emulationsflag - die gab es damals nicht.
     */
    private fun schreibeV2Altbestand(dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "e-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.publishEmuliert("alt", 0.30, t0)
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val o = org.json.JSONObject(target.readText()).put("v", 2)
        val pins = o.getJSONArray("proposalPumpEpochs")
        for (i in 0 until pins.length()) {
            pins.getJSONObject(i).remove("patchEpochApplicable")
            pins.getJSONObject(i).remove("patchEpochTs")
            pins.getJSONObject(i).remove("virtualPump")
        }
        target.writeText(o.toString())
    }
}
