package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.pump.defs.PumpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import app.aaps.fuse.plugin.FuseActivePump

/**
 * DER REPARATURWEG - der einzige Ausgang aus einem nicht quittierbaren Hold.
 *
 * Die interessanten Eigenschaften sind hier nicht "es funktioniert", sondern
 * die VERWEIGERUNGEN und die Spuren, die zurueckbleiben muessen. Ein
 * Reparaturweg, der auch ohne Not oeffnet oder der den verworfenen Zustand
 * spurlos verschwinden laesst, ist gefaehrlicher als gar keiner: er sieht aus
 * wie Sorgfalt und ist eine Hintertuer.
 */
class FuseLedgerRepairTest {

    private val t0 = 1_700_000_000_000L
    private val medtrum = PumpType.MEDTRUM_NANO
    private val emuliert = FuseActivePump(medtrum.name, virtualPump = true)

    /** Ein Ledger mit einer offenen Zeile UND einem Hold-Marker - die Lage,
     *  die auf dem Testtraeger tatsaechlich eingetreten ist. */
    private fun gehaltenerLedger(dir: File): FuseLedgerAdapter {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "r-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            medtrum.name, LedgerFacts.serialHashOf("abc", medtrum.name), virtualPump = true,
        )
        assertTrue(a.persistVerified(dir))
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, "Testbefund"))
        return a
    }

    // ---- Die Verweigerungen ---------------------------------------------

    /**
     * KEIN "LEDGER LEEREN" FUER DEN ALLTAG.
     *
     * Ohne Hold und ohne Verlust gibt es nichts zu reparieren - und dann darf
     * dieser Weg auch nichts anfassen. Waere er ein allgemeiner Loeschknopf,
     * liesse sich jede unbequeme Haftung damit wegdruecken, und der ganze
     * Ledger waere Zierde.
     */
    @Test
    fun `ohne Hold und ohne Verlust wird verweigert`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also { it.loadOnce(dir, "r-a", t0, emuliert) }
        a.onPublished("p1", 0.30, t0, 0L, 0.05, medtrum.name, LedgerFacts.serialHashOf("abc", medtrum.name), virtualPump = true)
        assertTrue(a.persistVerified(dir))

        val r = FuseLedgerRepair.perform(dir, t0, "test", "weil ich kann")
        assertTrue(r is FuseLedgerRepair.Result.Refused)
        assertTrue(File(dir, FuseLedgerStore.FILE_NAME).isFile) { "die Datei muss unangetastet bleiben" }
    }

    /** Ohne genannten Grund gar nichts - dieselbe Anforderung wie an eine
     *  Quittung. Eine Reparatur ohne Begruendung ist spaeter nicht mehr
     *  nachvollziehbar. */
    @Test
    fun `ohne Ausloeser oder Grund wird verweigert`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        assertTrue(FuseLedgerRepair.perform(dir, t0, "", "Grund") is FuseLedgerRepair.Result.Refused)
        assertTrue(FuseLedgerRepair.perform(dir, t0, "wer", "  ") is FuseLedgerRepair.Result.Refused)
        assertTrue(FuseLedgerStore.holdExists(dir)) { "nach einer Verweigerung steht der Hold unveraendert" }
    }

    // ---- Der Kern --------------------------------------------------------

    /** Der Hold geht auf, und der naechste Start laedt sauber. */
    @Test
    fun `die Reparatur oeffnet den Hold`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        assertTrue(FuseLedgerStore.holdExists(dir))

        val r = FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "Identitaets-Hashes aus altem Schema")
        assertTrue(r is FuseLedgerRepair.Result.Done)
        assertFalse(FuseLedgerStore.holdExists(dir)) { "genau dafuer gibt es den Weg" }

        val neu = FuseLedgerAdapter().also { it.loadOnce(dir, "r-b", t0 + 2_000L, emuliert) }
        assertFalse(neu.recoveryHold) { "der naechste Start faengt frei an" }
        assertTrue(neu.state.entries.isEmpty())
    }

    /**
     * ES WIRD NICHTS GELOESCHT.
     *
     * Der Inhalt ist der einzige Anhaltspunkt dafuer, WAS verworfen wurde. Wer
     * ihn wegwirft, macht die Reparatur unpruefbar - und eine unpruefbare
     * Reparatur ist von einer Vertuschung nicht zu unterscheiden.
     */
    @Test
    fun `der bisherige Ledger geht in Quarantaene statt zu verschwinden`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val vorher = File(dir, FuseLedgerStore.FILE_NAME).readText()

        val r = FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "Grund") as FuseLedgerRepair.Result.Done
        assertTrue(r.quarantined.isNotEmpty())
        assertTrue(r.freshLedgerWritten)

        // Unter dem Generationsnamen steht jetzt die LEERE Nachfolgerin, nicht
        // mehr der alte Inhalt.
        val jetzt = File(dir, FuseLedgerStore.FILE_NAME)
        assertTrue(jetzt.isFile)
        assertFalse(jetzt.readText() == vorher) { "die alte Generation darf nicht mehr in der Rotation stehen" }

        val quarantaene = dir.listFiles()!!.filter { FuseLedgerRepair.RESET_SUFFIX in it.name }
        assertTrue(quarantaene.isNotEmpty())
        assertTrue(quarantaene.any { it.readText() == vorher }) {
            "der verworfene Inhalt muss WORTGLEICH erhalten bleiben"
        }
    }

    /**
     * Die Nachfolgegeneration zaehlt die Revision WEITER.
     *
     * Sie ist die Auswahlgroesse zwischen den Generationen. Finge sie wieder
     * bei 0 an, waere das die einzige Stelle, an der eine wieder auftauchende
     * Altdatei die frische schlagen koennte.
     */
    @Test
    fun `die leere Nachfolgegeneration zaehlt die Revision weiter`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val alt = FuseLedgerRepair.inspect(dir).discarded.revision!!

        FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "Grund")

        val neu = FuseLedgerAdapter().also { it.loadOnce(dir, "r-b", t0 + 2_000L, emuliert) }
        assertTrue(neu.revision > alt) { "Revision $alt darf nicht zurueckfallen" }
        assertTrue(neu.state.entries.isEmpty())
    }

    /**
     * DER SENTINEL BLEIBT.
     *
     * Der Ledger HAT existiert. Naehme die Reparatur den Marker mit, saehe ein
     * spaeterer echter Dateiverlust wie ein Erststart aus - und verlorene
     * offene Haftung wuerde still als "nie passiert" verbucht. Genau dagegen
     * ist der Sentinel gebaut.
     */
    @Test
    fun `der Existenzmarker ueberlebt die Reparatur`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        assertTrue(FuseLedgerStore.sentinelExists(dir))
        FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "Grund")
        assertTrue(FuseLedgerStore.sentinelExists(dir)) { "der Ledger hat existiert - das bleibt wahr" }
    }

    /**
     * WAS VERWORFEN WURDE, WIRD BEZIFFERT UND BLEIBT LESBAR.
     *
     * Sonst sieht ein reparierter Ledger aus wie ein unbenutzter, und "keine
     * Haftung" waere von "hier wurde Haftung verworfen" nicht zu
     * unterscheiden.
     */
    @Test
    fun `die Reparatur protokolliert was sie verworfen hat`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val r = FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "Schemawechsel") as FuseLedgerRepair.Result.Done

        assertEquals(1, r.record.discarded.openEntries)
        assertEquals(0.30, r.record.discarded.grossLiabilityU!!, 1e-9)
        assertTrue(r.record.discarded.stateReadable)

        val gelesen = FuseLedgerRepair.lastReset(dir)
        assertNotNull(gelesen)
        assertEquals("Schemawechsel", gelesen!!.reason)
        assertEquals("Bediener", gelesen.by)
        assertEquals(0.30, gelesen.discarded.grossLiabilityU!!, 1e-9)
        assertEquals(1, gelesen.discarded.openEntries)
    }

    /** Das Protokoll haengt AN. Mehrere Reparaturen hintereinander sind genau
     *  das Muster, das jemand spaeter sehen koennen muss. */
    @Test
    fun `mehrere Reparaturen bleiben alle im Protokoll`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "erste")
        gehaltenerLedger(dir)
        FuseLedgerRepair.perform(dir, t0 + 2_000L, "Bediener", "zweite")

        val zeilen = File(dir, FuseLedgerRepair.LOG_NAME).readLines().filter { it.isNotBlank() }
        assertEquals(2, zeilen.size) { "die erste Reparatur darf nicht ueberschrieben werden" }
        assertEquals("zweite", FuseLedgerRepair.lastReset(dir)!!.reason) { "gelesen wird die juengste" }
        assertTrue(zeilen.first().contains("erste"))
    }

    /**
     * UNLESBAR IST NICHT NULL.
     *
     * Laesst sich der bisherige Ledger nicht mehr dekodieren, sind die
     * verworfenen Mengen UNBEKANNT. Sie als 0 zu melden waere die Aussage "es
     * war nichts offen" - und die ist es gerade nicht.
     */
    @Test
    fun `ein unlesbarer Ledger meldet unbekannt statt null`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        for (f in dir.listFiles()!!.filter { it.name.startsWith(FuseLedgerStore.FILE_NAME) })
            f.writeText("{kaputt")

        val r = FuseLedgerRepair.perform(dir, t0 + 1_000L, "Bediener", "Verlust") as FuseLedgerRepair.Result.Done
        assertFalse(r.record.discarded.stateReadable)
        assertEquals(null, r.record.discarded.grossLiabilityU) { "unbekannt, nicht 0" }
        assertEquals(null, r.record.discarded.openEntries)
        assertEquals(null, FuseLedgerRepair.lastReset(dir)!!.discarded.grossLiabilityU)
    }

    /** Die Vorschau veraendert nichts - sonst waere der Bestaetigungsdialog
     *  selbst schon der Eingriff. */
    @Test
    fun `die Vorschau fasst nichts an`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val vorher = dir.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first }

        val lage = FuseLedgerRepair.inspect(dir)
        assertTrue(lage.repairable)
        assertEquals(1, lage.discarded.openEntries)

        assertEquals(vorher, dir.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first })
        assertTrue(FuseLedgerStore.holdExists(dir))
    }
    /**
     * DER WRITE-AHEAD-MARKER IST EIN EIGENER REPARATURGRUND.
     *
     * Seit er klebt (12.08.), waere der Hold sonst dauerhaft, aber nicht
     * bedienbar - und ein Ausgang, den man nicht gehen kann, ist keiner.
     * Geprueft wird beides: dass `inspect` ihn nennt und dass `perform` ihn
     * samt der unversiegelten `.tmp`-Generation aufloest.
     */
    @Test
    fun `ein unterbrochener Versiegelungsvorgang ist reparabel`(@TempDir dir: File) {
        val store = FuseLedgerStore()
        assertTrue(
            store.writeVerified(
                dir,
                LedgerCodec.encode(app.aaps.fuse.core.ledger.LedgerState(), EpisodeBudgets(), 3L).toString(),
            )
        )
        assertTrue(FuseLedgerStore.writeSentinel(dir))
        // Der unterbrochene Vorgang: unversiegeltes .tmp mit hoeherer Revision
        // plus Marker.
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp")
            .writeText(LedgerCodec.encode(app.aaps.fuse.core.ledger.LedgerState(), EpisodeBudgets(), 9L).toString())
        assertTrue(FuseLedgerStore.markSealPendingForTest(dir))

        val i = FuseLedgerRepair.inspect(dir)
        assertTrue(i.repairable) { "sonst gaebe es keinen Ausweg: ${i.why}" }
        assertTrue(i.why.contains(FuseLedgerStore.SEAL_PENDING_NAME)) { i.why }

        val r = FuseLedgerRepair.perform(dir, 1_700_000_000_000L, by = "Test", reason = "unterbrochener Persist")
        assertTrue(r is FuseLedgerRepair.Result.Done) { "$r" }

        assertFalse(FuseLedgerStore.sealPendingExists(dir)) { "der Marker muss weg sein" }
        // Alle DREI Generationskandidaten sind in Quarantaene - auch das
        // unversiegelte .tmp, das den Marker verursacht hat.
        assertFalse(File(dir, FuseLedgerStore.FILE_NAME + ".tmp").isFile)
    }
}
