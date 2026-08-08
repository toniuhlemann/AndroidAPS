package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.ledger.AccountingState
import app.aaps.fuse.core.ledger.DeliveryState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Die Aufrufstelle des Ledgers im Livepfad (Audit R95, Fix 3): Publizieren,
 * Binden, Vollsicht-Abgleich, RESTART - alles ohne Android, gegen echte
 * BS-Datensaetze.
 */
class FuseLedgerAdapterTest {

    private val t0 = 1_700_000_000_000L

    private fun smb(ts: Long, amount: Double, pumpId: Long) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", pumpId = pumpId),
    )

    private fun loadedAdapter(dir: File, session: String = "epoch-a", now: Long = t0): FuseLedgerAdapter =
        FuseLedgerAdapter().also { it.loadOnce(dir, session, now) }

    // ---- Publizieren, Binden, Schliessen ----------------------------------

    @Test
    fun `publizieren bindet die Menge, der Vollsicht-Nachweis loest sie`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, decisionTs = t0, latestBolusTs = 0L, bolusStepU = 0.05)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)

        // Naechster Zyklus: der SMB steht als BS-Datensatz in der Vollsicht.
        val b = smb(t0 + 5_000L, 0.30, pumpId = 4711L)
        a.bindIdentities(listOf(b))
        assertEquals(t0 + 5_000L, a.oldestOpenTs())
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)

        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)
        val e = a.state.entries.getValue("p1")
        assertEquals(AccountingState.IOB_ACCOUNTED, e.accounting)
        assertTrue(e.closed)
    }

    /** ZWEI passende Datensaetze im Fenster: es wird NICHT geraten - die
     *  Zeile bleibt offen und haelt konservativ ihre volle Haftung. */
    @Test
    fun `mehrdeutige Kandidaten binden nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        a.bindIdentities(listOf(smb(t0 + 10_000L, 0.30, 1L), smb(t0 + 40_000L, 0.30, 2L)))
        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    /** Das Fenster einer Zeile endet am NAECHSTEN Vorschlag: ein Bolus danach
     *  gehoert deterministisch zu dessen Zeile. */
    @Test
    fun `disjunkte Fenster ordnen zwei gleiche Mengen richtig zu`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        a.onPublished("p2", 0.30, t0 + 60_000L, 0L, 0.05)
        val b1 = smb(t0 + 5_000L, 0.30, 1L)
        val b2 = smb(t0 + 65_000L, 0.30, 2L)
        a.bindIdentities(listOf(b1, b2))
        assertEquals(1L, a.state.entries.getValue("p1").identity?.pumpId)
        assertEquals(2L, a.state.entries.getValue("p2").identity?.pumpId)
    }

    /** Eine vom Loop GEKAPPTE Menge bindet nicht - die Zeile haelt die volle
     *  publizierte Haftung, statt einen fremd aussehenden Datensatz zu raten. */
    @Test
    fun `abweichende Menge bindet nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        a.bindIdentities(listOf(smb(t0 + 5_000L, 0.20, 1L)))
        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    // ---- Neustart ---------------------------------------------------------

    /**
     * DAS Restart-Szenario des Auftrags: Marker aktiv, primeSpent > 0, offenes
     * Commitment - Neustart mit NEUER Session. Budgets bleiben belastet, das
     * Commitment bleibt gebunden, der Epochwechsel ist angekuendigt (kein
     * Dauer-Hold), Unbewiesenes gilt konservativ als abgegeben.
     */
    @Test
    fun `Neustart behaelt Budgets und Commitments`(@TempDir dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        // Snapshot OHNE unseren Datensatz (noch nicht geliefert) - setzt die
        // Ordnung der Epoche a.
        a.onCycleSnapshot(emptyList(), LedgerFacts.snapshotHash(emptyList()), t0 + 1_000L)
        a.episodes.primeSpentU = 0.45
        a.episodes.primeArmedTs = t0
        a.episodes.onsetSpentU = 0.10
        a.episodes.mealArmedTs = t0
        a.episodes.mealDeliveries.addLast(t0 + 30_000L to 0.15)
        assertTrue(a.persistVerified(dir))

        val b = loadedAdapter(dir, "epoch-b", t0 + 120_000L)
        // Budget bleibt belastet - die Huelle steht nach dem Neustart NICHT
        // ein zweites Mal zur Verfuegung.
        assertEquals(0.45, b.episodes.primeSpentU, 0.0)
        assertEquals(t0, b.episodes.primeArmedTs)
        assertEquals(0.10, b.episodes.onsetSpentU, 0.0)
        assertEquals(listOf(t0 + 30_000L to 0.15), b.episodes.mealDeliveries.toList())
        // Commitment bleibt gebunden, Unbewiesenes konservativ als abgegeben.
        assertEquals(0.30, b.view().transportCommitmentU, 1e-12)
        assertEquals(DeliveryState.UNKNOWN_ASSUMED, b.state.entries.getValue("p1").delivery)
        // Kein Hold: der Epochwechsel ist ANGEKUENDIGT (R95-F2), und der erste
        // Snapshot der neuen Epoche wird angenommen.
        assertFalse(b.view().hold)
        val bolus = smb(t0 + 5_000L, 0.30, 4711L)
        b.bindIdentities(listOf(bolus))
        b.onCycleSnapshot(listOf(LedgerFacts.fact(bolus)), LedgerFacts.snapshotHash(listOf(bolus)), t0 + 180_000L)
        assertFalse(b.view().hold)
        assertEquals("epoch-b", b.state.lastSnapshotOrder?.sourceEpochId)
        // ... und die alte Zeile wird ueber die Reconciliation geschlossen.
        assertEquals(0.0, b.view().transportCommitmentU, 1e-12)
    }

    @Test
    fun `loadOnce ist idempotent`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        val rev = a.revision
        a.loadOnce(dir, "epoch-x", t0 + 1L)
        assertEquals(rev, a.revision)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    /** ALLE Generationen unlesbar: leerer Start NUR mit Recovery-Hold
     *  (Audit 2d273cb, REG-01c) - "leer" waere sonst die Behauptung, es habe
     *  nie ein Commitment gegeben. Und weiterhin: kein Wurf. */
    @Test
    fun `korrupte Dateien starten leer aber nur mit Recovery-Hold`(@TempDir dir: File) {
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")
        val a = loadedAdapter(dir)
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertTrue(a.recoveryHold)
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, a.view().holdReason)
    }

    /** Semantisch UNGUELTIGE Datei (JSON-valide, aber negatives Budget):
     *  zaehlt wie Korruption - Hold, keine Uebernahme (REG-01d). */
    @Test
    fun `semantisch ungueltige Datei zaehlt als korrupt`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertTrue(a.persistVerified(dir))
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val tampered = org.json.JSONObject(target.readText())
        tampered.getJSONObject("episodes").put("primeSpentU", -1.0)
        target.writeText(tampered.toString())
        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-x", t0) }
        assertEquals(0.0, b.episodes.primeSpentU, 0.0)
        assertTrue(b.recoveryHold)
        assertTrue(b.view().hold)
    }

    /** Hauptdatei unlesbar, bak gueltig: geladen wird die bak-Generation,
     *  aber der STILLE Generationsverlust haelt trotzdem an - die gewaehlte
     *  Generation kann aelter sein als das zuletzt Publizierte. */
    @Test
    fun `unlesbare Hauptdatei mit gueltiger bak laedt bak und haelt trotzdem an`(@TempDir dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        assertTrue(a.persistVerified(dir))
        a.onPublished("p2", 0.15, t0 + 60_000L, 0L, 0.05)
        assertTrue(a.persistVerified(dir)) // dreht p1-Stand nach bak
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 120_000L) }
        // Die bak-Generation traegt p1, p2 ist verloren gegangen ...
        assertTrue("p1" in b.state.entries)
        assertFalse("p2" in b.state.entries)
        // ... und genau deshalb steht die Sperre.
        assertTrue(b.recoveryHold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, b.view().holdReason)
    }

    /** Echter Erststart (kein Kandidat existiert): KEIN Hold - sonst waere
     *  jede Neuinstallation dauerhaft gesperrt. */
    @Test
    fun `echter Erststart haelt nicht an`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertFalse(a.recoveryHold)
        assertFalse(a.view().hold)
        assertNull(a.view().holdReason)
    }

    // ---- Persistenzvertrag ------------------------------------------------

    /** REG-01a: ein fehlgeschlagener persistVerified sperrt STICKY ueber
     *  view().hold, bis der naechste Persist wieder durchgeht. */
    @Test
    fun `persistVerified-Fehlschlag sperrt sticky bis zum naechsten Erfolg`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        val blockiert = File(dir, "datei-statt-verzeichnis").also { it.writeText("x") }
        assertFalse(a.persistVerified(File(blockiert, "unter")))
        assertTrue(a.persistFailed)
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED, a.view().holdReason)
        // Der naechste ERFOLG loescht die Sperre wieder.
        assertTrue(a.persistVerified(dir))
        assertFalse(a.persistFailed)
        assertFalse(a.view().hold)
        assertNull(a.view().holdReason)
    }

    // ---- Bindungsfenster (Fix 6, NEU-BS-02) -------------------------------

    /** Das Fenster bleibt HART auf BIND_WINDOW_MS gekappt, auch wenn der
     *  naechste Vorschlag Stunden spaeter kommt (z.B. weil die Zwischenzeile
     *  dem prune zum Opfer fiel): ein Bolus jenseits der 5 min darf nie
     *  mehr binden. */
    @Test
    fun `Bindungsfenster bleibt hart gekappt trotz spaetem Folgevorschlag`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        a.onPublished("p2", 0.15, t0 + 10 * 3600_000L, 0L, 0.05)
        // 20 min nach p1: laege im alten Fenster [t0, p2), aber jenseits der
        // harten Kappe - bindet NICHT.
        a.bindIdentities(listOf(smb(t0 + 20 * 60_000L, 0.30, 1L)))
        assertNull(a.state.entries.getValue("p1").identity)
        // Innerhalb der Kappe bindet er.
        a.bindIdentities(listOf(smb(t0 + 2 * 60_000L, 0.30, 2L)))
        assertEquals(2L, a.state.entries.getValue("p1").identity?.pumpId)
    }

    /** Die Identitaet einer GEPRUNTEN gebundenen Zeile bleibt verbraucht -
     *  auch ueber persist + Neustart: der bereits verbuchte Bolus darf keine
     *  fremde Zeile mehr schliessen. */
    @Test
    fun `geprunte gebundene Identitaet bleibt dauerhaft ausgeschlossen`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        val b = smb(t0 + 5_000L, 0.30, pumpId = 77L)
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)
        a.prune(t0 + 12 * 3600_000L, diaHours = 9.0)
        assertFalse("p1" in a.state.entries)

        // Neue Zeile, deren Fenster den Bolus zeitlich einschliesst: die
        // verbrauchte Identitaet darf NICHT erneut binden.
        a.onPublished("p2", 0.30, t0 + 1_000L, 0L, 0.05)
        a.bindIdentities(listOf(b))
        assertNull(a.state.entries.getValue("p2").identity)

        // ... und die Ausschlussmenge ueberlebt den Neustart.
        assertTrue(a.persistVerified(dir))
        val fresh = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-c", t0 + 13 * 3600_000L) }
        fresh.bindIdentities(listOf(b))
        assertNull(fresh.state.entries.getValue("p2").identity)
    }

    // ---- Aufraeumen -------------------------------------------------------

    @Test
    fun `prune verwirft nur geschlossene fehlerfreie Zeilen jenseits von DIA plus 2h`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        // Geschlossene Zeile ...
        a.onPublished("alt", 0.30, t0, 0L, 0.05)
        val b = smb(t0 + 5_000L, 0.30, 1L)
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)
        // ... und eine offene juengere.
        a.onPublished("offen", 0.15, t0 + 10 * 3600_000L, 0L, 0.05)

        val now = t0 + 12 * 3600_000L // 12 h > DIA 9 + 2
        a.prune(now, diaHours = 9.0)
        assertFalse("alt" in a.state.entries)
        assertTrue("offen" in a.state.entries)

        // Eine OFFENE alte Zeile wird nie verworfen - sie ist Befund.
        a.prune(now + 48 * 3600_000L, diaHours = 9.0)
        assertTrue("offen" in a.state.entries)
    }
}
