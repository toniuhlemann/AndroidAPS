package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.ledger.AccountingState
import app.aaps.fuse.core.ledger.DeliveryState
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import app.aaps.fuse.plugin.FuseActivePump

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

    /**
     * B3: der Pumpenkontext gehoert zum Laden.
     *
     * Die Migration braucht ihn, weil eine v1-Zeile GAR KEINEN Pin hat: ob sie
     * gefahrlos als Altbestand weiterbinden darf, haengt daran, welche Pumpe
     * heute laeuft. `null` gilt als unbekannt und damit als Hold - hier laeuft
     * die Testpumpe GENERIC_AAPS, also eine Nicht-Patch-Pumpe.
     */
    private fun loadedAdapter(dir: File, session: String = "epoch-a", now: Long = t0): FuseLedgerAdapter =
        FuseLedgerAdapter().also { it.loadOnce(dir, session, now, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }

    /** Publikation MIT Pumpen-Info der Testpumpe (GENERIC_AAPS/"vs", passend
     *  zum [smb]-Helfer). Seit R4-03 bindet eine Publikation OHNE Info einen
     *  expliziten UNPINNED-Pin und bindet NIE - der Normalfall der
     *  Bindungstests ist deshalb die gepinnte Publikation, wie im Livepfad
     *  (FusePlugin liefert die aktive Pumpe mit). */
    private fun FuseLedgerAdapter.publishVs(id: String, u: Double, ts: Long, latest: Long = 0L) =
        onPublished(id, u, ts, latest, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))

    // ---- Publizieren, Binden, Schliessen ----------------------------------

    @Test
    fun `publizieren bindet die Menge, der Vollsicht-Nachweis loest sie`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.publishVs("p1", 0.30, ts = t0)
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
        a.publishVs("p1", 0.30, t0)
        a.bindIdentities(listOf(smb(t0 + 10_000L, 0.30, 1L), smb(t0 + 40_000L, 0.30, 2L)))
        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    /** Das Fenster einer Zeile endet am NAECHSTEN Vorschlag: ein Bolus danach
     *  gehoert deterministisch zu dessen Zeile. */
    @Test
    fun `disjunkte Fenster ordnen zwei gleiche Mengen richtig zu`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.publishVs("p1", 0.30, t0)
        a.publishVs("p2", 0.30, t0 + 60_000L)
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
        a.publishVs("p1", 0.30, t0)
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
        a.publishVs("p1", 0.30, t0)
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
        a.loadOnce(dir, "epoch-x", t0 + 1L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
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
        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-x", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
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

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 120_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
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
        a.publishVs("p1", 0.30, t0)
        a.publishVs("p2", 0.15, t0 + 10 * 3600_000L)
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
        a.publishVs("p1", 0.30, t0)
        val b = smb(t0 + 5_000L, 0.30, pumpId = 77L)
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)
        a.prune(t0 + 12 * 3600_000L, diaHours = 9.0)
        assertFalse("p1" in a.state.entries)

        // Neue Zeile, deren Fenster den Bolus zeitlich einschliesst: die
        // verbrauchte Identitaet darf NICHT erneut binden.
        a.publishVs("p2", 0.30, t0 + 1_000L)
        a.bindIdentities(listOf(b))
        assertNull(a.state.entries.getValue("p2").identity)

        // ... und die Ausschlussmenge ueberlebt den Neustart.
        assertTrue(a.persistVerified(dir))
        val fresh = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-c", t0 + 13 * 3600_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        fresh.bindIdentities(listOf(b))
        assertNull(fresh.state.entries.getValue("p2").identity)
    }

    // ---- Pumpen-Epoch (Fix 3, Re-Audit c750169, 6.3) ----------------------

    private fun smbFrom(ts: Long, amount: Double, pumpId: Long, pumpType: PumpType?, serial: String?) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = pumpType, pumpSerial = serial, pumpId = pumpId),
    )

    /** DER Re-Audit-Repro (Fault-Injection I): Pumpenwechsel im
     *  Bindungsfenster, gleicher Betrag - der Fakt der NEUEN Pumpe darf die
     *  alte Zeile nicht binden und nicht ueber deren IOB-Fakt schliessen. */
    @Test
    fun `gleicher Betrag von anderer Pumpe bindet nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = Sha.of("vs"),
        )
        // Andere Pumpe (Typ UND Serial fremd), gleicher Betrag, im Fenster.
        a.bindIdentities(listOf(smbFrom(t0 + 5_000L, 0.30, 9L, PumpType.DANA_R, "other")))
        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
        // Gleicher Typ, anderes Geraet (Serial): ebenfalls KEIN Treffer.
        a.bindIdentities(listOf(smbFrom(t0 + 10_000L, 0.30, 10L, PumpType.GENERIC_AAPS, "other")))
        assertNull(a.state.entries.getValue("p1").identity)
        // Die gepinnte Pumpe bindet weiterhin.
        a.bindIdentities(listOf(smb(t0 + 20_000L, 0.30, 1L)))
        assertEquals(1L, a.state.entries.getValue("p1").identity?.pumpId)
    }

    /**
     * Fehlt die BS-Identitaet bei GEPINNTER Zeile: KEIN Match - ein Fakt, der
     * seine Herkunft nicht nennt, schliesst keine herkunftsgebundene Zeile.
     *
     * Fehlt dagegen die PINNUNG (Altbestand), bindet er - ABER NUR BEI
     * NACHGEWIESENER EMULATION (Auditbefund 10.08.2026). Frueher band eine
     * pinlose Zeile bedingungslos; an einer echten Medtrum haette sie damit
     * jeden mengengleichen SMB im Zeitfenster gebunden und ihre Haftung ueber
     * einen fremden physischen Fakt ausgebucht.
     */
    @Test
    fun `Null-Toleranz der Epoch-Pinnung hat eine Richtung`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = Sha.of("vs"),
        )
        a.bindIdentities(listOf(smbFrom(t0 + 5_000L, 0.30, 9L, pumpType = null, serial = null)))
        assertNull(a.state.entries.getValue("p1").identity)

        // Altbestand: eine Zeile aus einer Version-1-Datei traegt KEINE
        // Pinnung - hier simuliert durch Entfernen des Pins nach der
        // Publikation (seit R4-03 erzeugt eine Publikation ohne Pumpen-Info
        // UNPINNED und bindet nie; nur der explizit pinlose Altbestand
        // behaelt das alte Verhalten). Der bindet denselben Fakt wie bisher.
        a.publishVs("p2", 0.15, t0 + 60_000L)
        a.proposalPumpEpochs.remove("p2")

        // OHNE nachgewiesene Emulation bindet sie NICHT - der Vorgabekontext
        // ist "unbekannt", und unbekannt ist kein Nachweis.
        a.bindIdentities(listOf(smbFrom(t0 + 65_000L, 0.15, 10L, pumpType = null, serial = null)))
        assertNull(a.state.entries.getValue("p2").identity) {
            "ohne Nachweis der Emulation darf eine pinlose Zeile nichts binden"
        }

        // MIT Nachweis bindet sie wie eh und je - die Regel ist eine
        // Unterscheidung, keine pauschale Sperre.
        a.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        a.bindIdentities(listOf(smbFrom(t0 + 65_000L, 0.15, 10L, pumpType = null, serial = null)))
        assertEquals(10L, a.state.entries.getValue("p2").identity?.pumpId)
    }

    /**
     * DASSELBE FUER DEN AUSDRUECKLICHEN ALTBESTANDS-MARKER.
     *
     * `legacyOpen` ist der Marker, den `coveredEpochs` fuer pinlose Zeilen
     * setzt - er traegt per Konstruktion WEDER Typ NOCH Serial NOCH Epoche.
     * Er gab in matchesPinnedEpoch bedingungslos `true` zurueck, genau wie der
     * fehlende Pin. Zwei Wege in dasselbe Loch, und der Test deckte nur einen.
     */
    @Test
    fun `eine legacyOpen-Zeile bindet an einer echten Pumpe nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.publishVs("alt", 0.15, t0)
        a.proposalPumpEpochs["alt"] = ProposalPumpEpoch.LEGACY_OPEN

        a.observeBindingContext(
            LedgerPumpBindingContext(
                virtualPump = false, pumpTypeName = PumpType.MEDTRUM_NANO.name,
                serialHash = Sha.of("9c1de26d"), patchEpochTs = t0 - 3600_000L,
            )
        )
        a.bindIdentities(listOf(smbFrom(t0 + 5_000L, 0.15, 78L, PumpType.MEDTRUM_NANO, "9c1de26d")))
        assertNull(a.state.entries.getValue("alt").identity) {
            "der Altbestands-Marker traegt keine Identitaet - er darf keinen echten Fakt verbrauchen"
        }

        // Unter nachgewiesener Emulation bindet er weiter.
        a.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        a.bindIdentities(listOf(smbFrom(t0 + 6_000L, 0.15, 79L, PumpType.MEDTRUM_NANO, "9c1de26d")))
        assertEquals(79L, a.state.entries.getValue("alt").identity?.pumpId)
    }

    /**
     * DIE GEGENPROBE ZUM ALTBESTAND: an einer ECHTEN Pumpe bindet eine
     * pinlose Zeile nicht - auch nicht, wenn Menge und Zeitfenster passen.
     *
     * Das war das Loch: eine v1-Zeile ohne Pin gab in matchesPinnedEpoch
     * bedingungslos `true` zurueck, ganz ohne Typ-, Serial- oder
     * Epochenpruefung. Der reale Fakt waere danach ueber boundPump verbraucht
     * gewesen und haette seiner EIGENEN Zeile nicht mehr zur Verfuegung
     * gestanden.
     */
    @Test
    fun `eine pinlose Altzeile bindet an einer echten Pumpe nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.publishVs("alt", 0.15, t0)
        a.proposalPumpEpochs.remove("alt")

        a.observeBindingContext(
            LedgerPumpBindingContext(
                virtualPump = false, pumpTypeName = PumpType.MEDTRUM_NANO.name,
                serialHash = Sha.of("9c1de26d"), patchEpochTs = t0 - 3600_000L,
            )
        )
        a.bindIdentities(listOf(smbFrom(t0 + 5_000L, 0.15, 77L, PumpType.MEDTRUM_NANO, "9c1de26d")))

        assertNull(a.state.entries.getValue("alt").identity) {
            "ohne Identitaet darf die Altzeile keinen echten Fakt verbrauchen"
        }
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12) {
            "und sie haelt konservativ ihre volle Haftung"
        }
    }

    /** Die Pinnung ueberlebt persist + Neustart - sonst waere genau das
     *  Neustart-Fenster wieder ungeschuetzt. */
    @Test
    fun `Epoch-Pinnung ueberlebt den Neustart`(@TempDir dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = Sha.of("vs"),
        )
        assertTrue(a.persistVerified(dir))

        val b = loadedAdapter(dir, "epoch-b", t0 + 60_000L)
        b.bindIdentities(listOf(smbFrom(t0 + 5_000L, 0.30, 9L, PumpType.DANA_R, "other")))
        assertNull(b.state.entries.getValue("p1").identity)
        b.bindIdentities(listOf(smb(t0 + 10_000L, 0.30, 1L)))
        assertEquals(1L, b.state.entries.getValue("p1").identity?.pumpId)
    }

    // ---- Sentinel + Migration (Fix 1, Re-Audit c750169, 6.1/REG-03) -------

    /** Der SENTINEL unterscheidet Datenverlust vom Erststart: Generationen
     *  weg, Marker noch da -> Hold, kein stiller Leerstart. */
    @Test
    fun `Sentinel ohne Generation heisst Datenverlust nicht Erststart`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        assertTrue(a.persistVerified(dir))
        // Der erste erfolgreiche Persist hat den Marker geschrieben.
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).exists())

        // Alle Generationen verschwinden (Aufraeumen, Bug, Fremdzugriff) -
        // der Marker bleibt.
        assertTrue(File(dir, FuseLedgerStore.FILE_NAME).delete())
        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertEquals(0.0, b.view().transportCommitmentU, 1e-12)
        assertTrue(b.recoveryHold)
        assertTrue(b.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, b.view().holdReason)
    }

    /** Ausstehende Migration (Fix 1a): loadOnce wird zurueckgestellt, der
     *  Lauf haelt wie unter recoveryHold an, und persistVerified schreibt
     *  NICHTS - ein Leerzustand wuerde die Vorgeschichte verdecken und den
     *  naechsten Migrationsversuch als "schon belegt" blockieren. */
    @Test
    fun `ausstehende Migration haelt an und persistiert nicht`(@TempDir dir: File) {
        val a = FuseLedgerAdapter()
        a.noteMigrationFailed()
        a.loadOnce(dir, "epoch-a", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_MIGRATION, a.view().holdReason)
        assertFalse(a.persistVerified(dir))
        assertFalse(File(dir, FuseLedgerStore.FILE_NAME).exists())

        // Naechster invoke: Migration gelungen -> regulaeres Laden, und der
        // naechste erfolgreiche Persist loescht die sticky Persist-Sperre.
        a.noteMigrationDone()
        a.loadOnce(dir, "epoch-a", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        assertTrue(a.persistVerified(dir))
        assertFalse(a.view().hold)
        assertNull(a.view().holdReason)
    }

    // ---- R4-01: Sentinel als Vertragsbestandteil des Persists -------------

    /** ROT gegen den Altstand (writeSentinelTolerant ohne Erfolgswert):
     *  kann der Sentinel nicht entstehen, MUSS persistVerified false melden -
     *  die Publikation strippt dann den SMB. Ein Persist, dessen
     *  Verlustmarker fehlt, ist kein vollstaendiger Persist. */
    @Test
    fun `persistVerified scheitert wenn der Sentinel nicht entstehen kann`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        // Blockade: ein VERZEICHNIS besetzt den Sentinel-Namen - writeText
        // wirft, und "existiert" darf nicht als Marker durchgehen.
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).mkdirs())
        assertFalse(a.persistVerified(dir))
        assertTrue(a.persistFailed)
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED, a.view().holdReason)
        // Blockade weg -> der naechste Persist gelingt und loest die Sperre.
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).delete())
        assertTrue(a.persistVerified(dir))
        assertFalse(a.view().hold)
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).isFile)
    }

    /** Regression (Codex (c), existierte schon): Sentinel vorhanden, aber
     *  ALLE Generationen ungueltig -> recoveryHold, kein stiller Leerstart. */
    @Test
    fun `Sentinel mit nur ungueltigen Generationen haelt an`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05)
        assertTrue(a.persistVerified(dir))
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).exists())
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertTrue(b.recoveryHold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, b.view().holdReason)
        assertEquals(0.0, b.view().transportCommitmentU, 1e-12)
    }

    // ---- C8d (Codex-Adjudication bae885f1): DAUERHAFTER Recovery-Hold -----

    /** Vorgeschichte mit OFFENER Verpflichtung, danach werden ALLE
     *  Generationen unlesbar gemacht. Die Inhalte sind unterscheidbar, damit
     *  der Beweis-Erhalt pruefbar ist. */
    private fun korrupteVorgeschichte(dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.publishVs("p1", 0.30, t0)
        assertTrue(a.persistVerified(dir))
        a.publishVs("p2", 0.15, t0 + 60_000L)
        assertTrue(a.persistVerified(dir)) // target + bak liegen
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt-target")
        File(dir, FuseLedgerStore.FILE_NAME + ".bak").writeText("{kaputt-bak")
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp").writeText("{kaputt-tmp")
    }

    /**
     * DER C8d-Repro: zwei GEHALTENE Zyklen rotierten die korrupten
     * Beweis-Generationen durch saubere leere - nach dem Neustart existierte
     * kein invalider Kandidat mehr, der Hold fiel weg und die moeglicherweise
     * abgegebene Menge war refinanzierbar. Der Hold muss beliebig viele
     * gehaltene Zyklen UND den Prozesswechsel ueberleben.
     */
    @Test
    fun `C8d der Recovery-Hold ueberlebt zwei gehaltene Zyklen und den Neustart`(@TempDir dir: File) {
        korrupteVorgeschichte(dir)

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 120_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertTrue(b.recoveryHold)
        assertTrue(b.persistVerified(dir))
        assertTrue(b.persistVerified(dir))

        val c = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-c", t0 + 180_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertTrue(c.recoveryHold, "Recovery-Hold nach zwei gehaltenen Zyklen verloren")
        assertTrue(c.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, c.view().holdReason)
    }

    /** BEWEIS-ERHALT: die urspruenglich korrupten Inhalte duerfen nach den
     *  gehaltenen Persists nicht spurlos verschwunden sein - sie sind der
     *  einzige Anhaltspunkt fuer die spaetere Reparatur. */
    @Test
    fun `C8d die korrupten Generationen bleiben als Beweis erhalten`(@TempDir dir: File) {
        korrupteVorgeschichte(dir)

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 120_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertTrue(b.persistVerified(dir))
        assertTrue(b.persistVerified(dir))

        val beweise = dir.listFiles().orEmpty()
            .filter { it.name.contains(FuseLedgerStore.CORRUPT_SUFFIX) }
            .map { it.readText() }
            .toSet()
        assertEquals(setOf("{kaputt-target", "{kaputt-bak", "{kaputt-tmp"), beweise)

        // Der Marker nennt die quarantaenierten Dateien.
        val marker = org.json.JSONObject(File(dir, FuseLedgerStore.HOLD_NAME).readText())
        assertEquals(3, marker.getJSONArray("quarantined").length())
        assertTrue(marker.getString("reason").isNotBlank())
    }

    /** Der Marker allein sperrt: er gilt UNABHAENGIG davon, ob der Zustand
     *  sauber laedt - sonst waere der Hold wieder eine Prozesslaune. */
    @Test
    fun `C8d ein vorhandener Hold-Marker sperrt auch bei sauberem Zustand`(@TempDir dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.publishVs("p1", 0.30, t0)
        assertTrue(a.persistVerified(dir))
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, """{"reason":"TEST","ts":1}"""))

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        // Der Zustand laedt vollstaendig ...
        assertEquals(0.30, b.view().transportCommitmentU, 1e-12)
        // ... und ist trotzdem gesperrt.
        assertTrue(b.recoveryHold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, b.view().holdReason)
        // Ein normaler Persist loescht den Marker NIE (fail-closed, die
        // Aufloesung ist ein eigener Reparatur-Workflow).
        assertTrue(b.persistVerified(dir))
        assertTrue(File(dir, FuseLedgerStore.HOLD_NAME).isFile)
        assertTrue(b.view().hold)
    }

    /** Kann der Marker nicht entstehen, ist der Persist NICHT gueltig - sonst
     *  entstuenden saubere Generationen, waehrend der Verlustbeweis fehlt. */
    @Test
    fun `C8d ein nicht schreibbarer Hold-Marker macht den Persist ungueltig`(@TempDir dir: File) {
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")
        assertTrue(File(dir, FuseLedgerStore.HOLD_NAME).mkdirs())

        val a = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertTrue(a.recoveryHold)
        assertFalse(a.persistVerified(dir))
        assertTrue(a.persistFailed)
        // Es wurde auch keine saubere Generation geschrieben.
        assertFalse(File(dir, FuseLedgerStore.FILE_NAME).exists())

        // Blockade weg -> der naechste Persist zieht den Marker nach ...
        assertTrue(File(dir, FuseLedgerStore.HOLD_NAME).delete())
        assertTrue(a.persistVerified(dir))
        assertTrue(File(dir, FuseLedgerStore.HOLD_NAME).isFile)
        // ... und der Hold bleibt trotzdem stehen.
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_RECOVERY, a.view().holdReason)
    }

    // ---- G5: globale Hold-Identitaet --------------------------------------

    /** Ein entryloser (globaler) Fehler - hier die WIEDERVERWENDUNG einer
     *  schon benutzten Epoch - muss im holdReason als solcher benannt sein.
     *  Vorher stand dort pauschal LEDGER_STATE_HOLD, und Tab/Trail zeigten
     *  nicht, WAS gehalten wird. */
    @Test
    fun `G5 ein globaler Hold wird als solcher benannt`(@TempDir dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.onCycleSnapshot(emptyList(), LedgerFacts.snapshotHash(emptyList()), t0 + 1_000L)
        assertTrue(a.persistVerified(dir))
        val b = loadedAdapter(dir, "epoch-b", t0 + 60_000L)
        b.onCycleSnapshot(emptyList(), LedgerFacts.snapshotHash(emptyList()), t0 + 61_000L)
        assertTrue(b.persistVerified(dir))

        // Dritter Start mit einer BEREITS BENUTZTEN Epoch: der Rebase wird
        // abgewiesen (SNAPSHOT_ORDER_CONFLICT ohne proposalId).
        val c = loadedAdapter(dir, "epoch-a", t0 + 120_000L)
        assertFalse(c.recoveryHold)
        assertTrue(c.view().hold)
        val reason = c.view().holdReason
        assertTrue(reason!!.startsWith(FuseLedgerAdapter.HOLD_REASON_GLOBAL), "holdReason=$reason")
        assertTrue(reason.contains("SNAPSHOT_ORDER_CONFLICT"), "holdReason=$reason")
    }

    // ---- R4-03: UNPINNED statt API-Fallback --------------------------------

    /** ROT gegen den Altstand: eine Publikation OHNE Pumpen-Info (beide
     *  API-Lesungen fehlgeschlagen) durfte bisher wie Altbestand binden.
     *  Jetzt traegt sie einen expliziten UNPINNED-Pin und bindet NIE -
     *  weder einen fremden noch einen scheinbar passenden Datensatz. */
    @Test
    fun `Publikation ohne Pumpen-Info pinnt UNPINNED und bindet nie`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished("p1", 0.30, t0, 0L, 0.05) // keine Pumpen-Info
        // Passender Datensatz mit voller Herkunft: bindet NICHT.
        a.bindIdentities(listOf(smb(t0 + 5_000L, 0.30, 1L)))
        assertNull(a.state.entries.getValue("p1").identity)
        // Datensatz ganz ohne Herkunft: bindet ebenfalls NICHT.
        a.bindIdentities(listOf(smbFrom(t0 + 10_000L, 0.30, 2L, pumpType = null, serial = null)))
        assertNull(a.state.entries.getValue("p1").identity)
        // Die Zeile haelt konservativ ihre volle Haftung.
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    /** Der UNPINNED-Pin ist persistiert: auch nach Neustart bindet die Zeile
     *  nicht - sonst waere der Fail-Closed-Pin nur eine Prozesslaune. */
    @Test
    fun `UNPINNED ueberlebt Persist und Neustart`(@TempDir dir: File) {
        val a = loadedAdapter(dir, "epoch-a")
        a.onPublished("p1", 0.30, t0, 0L, 0.05) // keine Pumpen-Info
        assertTrue(a.persistVerified(dir))

        val b = loadedAdapter(dir, "epoch-b", t0 + 60_000L)
        b.bindIdentities(listOf(smb(t0 + 5_000L, 0.30, 1L)))
        assertNull(b.state.entries.getValue("p1").identity)
        assertEquals(0.30, b.view().transportCommitmentU, 1e-12)
    }

    /** Ab Schemaversion 2 MUSS jede Zeile einen Pin-Eintrag tragen (auch
     *  UNPINNED/legacyOpen). Eine v2-Datei, aus der die Pinnung entfernt
     *  wurde, ist Fremdinhalt - Hold statt Legacy-Deutung (Codex R4-02:
     *  "Pin entfernt und als Legacy akzeptiert"). */
    @Test
    fun `v2-Datei ohne Pin-Abdeckung haelt an`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = Sha.of("vs"),
        )
        assertTrue(a.persistVerified(dir))
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val tampered = org.json.JSONObject(target.readText())
        tampered.remove("proposalPumpEpochs")
        target.writeText(tampered.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertTrue(b.recoveryHold)
        assertTrue(b.view().hold)
    }

    /** Altdatei (Schemaversion 1) ohne Pins bleibt ladbar und bindet wie
     *  bisher - die Fail-Closed-Verschaerfung gilt nur fuer NEUE Zeilen
     *  und fuer v2-Dateien. */
    @Test
    fun `Altdatei Version 1 mit Zeilen wird migriert, nicht gesperrt`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = Sha.of("vs"),
        )
        assertTrue(a.persistVerified(dir))
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val tampered = org.json.JSONObject(target.readText())
        tampered.put("v", 1)
        tampered.remove("proposalPumpEpochs")
        target.writeText(tampered.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }

        // UMGEKEHRT gegenueber dem frueheren Stand (Codex-Re-Review P0-B).
        // Vorher band diese Datei "wie bisher" weiter. Seit Schema v3 traegt
        // jede Zeile `lastPositiveFactTs`, und sein FEHLEN laesst sich nicht
        // von "es gab nie einen positiven Fakt" unterscheiden - die Wirkfrist
        // liefe dann ab decisionTs statt ab einer moeglicherweise spaeteren
        // Lieferzeit, die Haftung also ZU FRUEH aus. Deshalb wird die
        // Generation gar nicht erst uebernommen.
        // ZWEIMAL UMGEKEHRT, beide Male absichtlich:
        //  - urspruenglich band diese Datei "wie bisher" weiter. Falsch, seit
        //    v3 `lastPositiveFactTs` traegt.
        //  - dann ging sie in den Migrations-Hold. Richtig, solange es keine
        //    Migration gab - als Dauerzustand aber eine Sackgasse.
        //  - jetzt wird sie MIGRIERT: gelesen, konservativ ergaenzt, atomar
        //    geschrieben, zurueckgelesen, validiert. Erst danach faellt der Hold.
        assertFalse(b.recoveryHold) { "eine migrierbare Altgeneration wird migriert, nicht gesperrt" }
        assertTrue(b.state.entries.containsKey("p1")) { "und ihr Zustand steht danach zur Verfuegung" }
        assertEquals(0.30, b.view().transportCommitmentU, 1e-9) {
            "die offene Haftung ueberlebt die Migration unveraendert"
        }
        assertEquals(
            LedgerCodec.VERSION,
            org.json.JSONObject(File(dir, FuseLedgerStore.FILE_NAME).readText()).getInt("v"),
        ) { "die Datei auf Platte ist danach wirklich v3" }
    }

    /**
     * ABGESCHRIEBENE ZEILEN durch die Migration: Flag und Befund bleiben,
     * Haftung bleibt null, und sie verankert kein Abfragefenster.
     *
     * Sie sind der Fall, der NIE verschwindet (prune behaelt fehlertragende
     * Zeilen absichtlich). Genau deshalb muessen sie migrierbar sein - sonst
     * waere der Hold fuer jeden, der je eine Phantomzeile hatte, dauerhaft.
     */
    @Test
    fun `abgeschriebene Zeilen ueberstehen die Migration und blockieren nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.publishVs("leiche", 0.20, ts = t0 - 19 * 3600_000L)
        a.prune(t0, 9.0)
        assertEquals(1, a.unresolvedBeyondActionCount()) { "Ausgangslage: abgeschrieben mit Befund" }
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val tampered = org.json.JSONObject(target.readText())
        tampered.put("v", 1)
        tampered.remove("proposalPumpEpochs")
        target.writeText(tampered.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }

        assertFalse(b.recoveryHold) { "eine abgeschriebene Zeile darf den Betrieb nicht dauerhaft sperren" }
        assertEquals(1, b.unresolvedBeyondActionCount()) { "der Befund bleibt erhalten" }
        assertTrue(b.state.entries.getValue("leiche").expiredBeyondAction) { "und das Flag auch" }
        assertEquals(0.0, b.view().transportCommitmentU, 1e-9) { "die Haftung bleibt null" }
        assertNull(b.oldestReconcilableTs()) { "und sie verankert kein Abfragefenster" }
    }

    /**
     * CRASH-MATRIX der Dateirotation. Alle drei Kombinationen tragen dieselbe
     * REVISION - die Auswahl darf also nicht darauf hereinfallen, sondern muss
     * die v3-Generation waehlen und darf keinen dauerhaften Hold erzeugen.
     */
    @Test
    fun `die Crash-Matrix waehlt die v3-Generation`(@TempDir dir: File) {
        // Eine gueltige v3-Generation und ihr v1-Zwilling mit gleicher Revision.
        val a = loadedAdapter(dir)
        a.publishVs("p1", 0.30, t0)
        assertTrue(a.persistVerified(dir))
        val v3 = File(dir, FuseLedgerStore.FILE_NAME).readText()
        val v1 = org.json.JSONObject(v3).also { it.put("v", 1); it.remove("proposalPumpEpochs") }.toString()

        fun lage(name: String, haupt: String?, tmp: String?, bak: String?) {
            val d = File(dir, name).also(File::mkdirs)
            haupt?.let { File(d, FuseLedgerStore.FILE_NAME).writeText(it) }
            tmp?.let { File(d, "${FuseLedgerStore.FILE_NAME}.tmp").writeText(it) }
            bak?.let { File(d, "${FuseLedgerStore.FILE_NAME}.bak").writeText(it) }
            val l = FuseLedgerAdapter().also { it.loadOnce(d, "epoch-x", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
            assertFalse(l.recoveryHold) { "$name: kein dauerhafter Hold" }
            assertEquals(0.30, l.view().transportCommitmentU, 1e-9) { "$name: Haftung erhalten" }
        }

        // Absturz NACH dem tmp-Schreiben, VOR dem Austausch.
        lage("tmp-neu-haupt-alt", haupt = v1, tmp = v3, bak = null)
        // Absturz mitten in der Rotation - nur tmp neu, bak alt.
        lage("tmp-neu-bak-alt", haupt = null, tmp = v3, bak = v1)
        // Absturz NACH dem Austausch, bak noch alt.
        lage("haupt-neu-bak-alt", haupt = v3, tmp = null, bak = v1)
    }

    /** WIEDERHOLBAR: ein zweiter Start auf der migrierten Generation findet
     *  nichts mehr zu tun. Eine Migration, die beim zweiten Lauf etwas anderes
     *  tut, waere keine. */
    @Test
    fun `die Migration ist wiederholbar und beim zweiten Start gegenstandslos`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = Sha.of("vs"),
        )
        assertTrue(a.persistVerified(dir))
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val tampered = org.json.JSONObject(target.readText())
        tampered.put("v", 1)
        tampered.remove("proposalPumpEpochs")
        target.writeText(tampered.toString())

        val erster = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertFalse(erster.recoveryHold)
        val nachErstem = target.readText()

        val zweiter = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-c", t0 + 120_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertFalse(zweiter.recoveryHold)
        assertEquals(0.30, zweiter.view().transportCommitmentU, 1e-9)
        assertEquals(nachErstem, target.readText()) { "der zweite Start schreibt die Generation nicht neu" }
    }

    /**
     * Die Gegenprobe zum Migrations-Hold: eine LEERE Altgeneration hat nichts
     * zu verlieren und migriert still. Genau so kann vor einem Realpump-Lauf
     * eine frische, belegte Generation starten, ohne dass jemand eine Datei
     * loeschen muss - ein Migrations-Hold ohne Ausweg waere kein Schutz,
     * sondern eine Sackgasse.
     */
    @Test
    fun `eine leere Altdatei migriert ohne Hold`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertTrue(a.persistVerified(dir))
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val tampered = org.json.JSONObject(target.readText())
        tampered.put("v", 1)
        tampered.remove("proposalPumpEpochs")
        target.writeText(tampered.toString())

        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false)) }
        assertFalse(b.recoveryHold) { "ohne offene Zeilen ist nichts zu retten - also kein Hold" }
    }

    // ---- Aufraeumen -------------------------------------------------------

    @Test
    fun `prune verwirft nur geschlossene fehlerfreie Zeilen jenseits von DIA plus 2h`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        // Geschlossene Zeile ...
        a.publishVs("alt", 0.30, t0)
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
    // ---- v3 -> v4: der ganze Weg, nicht nur der Codec --------------------

    /**
     * DER MIGRATIONSPFAD, WIE ER BEIM NAECHSTEN FLASH LAEUFT (Toni 12.08.).
     *
     * Der Codectest prueft Decode und Re-Encode. Was beim ersten Start auf dem
     * Geraet wirklich passiert, ist eine Kette: `loadOnce` -> Migration ->
     * durabler Schreibvorgang -> Rueckleseprobe -> kein Hold. Genau die war
     * ungeprueft, und der erste Flash sollte die Migration BESTAETIGEN, nicht
     * ihr erster vollstaendiger Test sein.
     *
     * Der Pflichtfall: eine v3-Datei mit laufendem Episodenanker und
     * kumulativer Bezahlung.
     */
    @Test
    fun `eine v3-Generation migriert beim Laden vollstaendig auf v4`(@TempDir dir: File) {
        // 1. Eine echte v3-Datei bauen: aktuelle Generation schreiben, dann
        //    Version zuruecksetzen und das Evidenzfeld entfernen.
        val a = loadedAdapter(dir)
        a.episodes.evidenceEpisodeId = t0
        a.episodes.lastConsumedMarkerTs = t0
        a.episodes.evidenceCommittedU = 2.35
        a.episodes.evidenceRevoked = true
        a.episodes.primeSpentU = 1.10
        assertTrue(a.persistVerified(dir))

        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val v3 = org.json.JSONObject(target.readText())
        v3.put("v", 3)
        v3.getJSONObject("episodes").remove("evidenceState")
        target.writeText(v3.toString())
        // Die .bak-Generation ebenfalls, sonst gewinnt sie beim Laden.
        File(dir, FuseLedgerStore.FILE_NAME + ".bak").let { if (it.exists()) it.writeText(v3.toString()) }

        // 2. Laden.
        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-migration", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }

        // 3. KEIN Hold - die Migration ist gelungen.
        assertFalse(b.recoveryHold) { "die Migration haette den Ledger nicht sperren duerfen" }
        assertFalse(b.view().hold)

        // 4. Auf der Platte liegt v4.
        assertEquals(LedgerCodec.VERSION, org.json.JSONObject(target.readText()).getInt("v"))

        // 5. Anker, Zaehler und Widerruf unveraendert, Bestand null.
        assertEquals(t0, b.episodes.evidenceEpisodeId)
        assertEquals(t0, b.episodes.lastConsumedMarkerTs)
        assertEquals(2.35, b.episodes.evidenceCommittedU, 1e-9)
        assertTrue(b.episodes.evidenceRevoked)
        assertEquals(1.10, b.episodes.primeSpentU, 1e-9)
        assertEquals(app.aaps.fuse.core.controller.EvidenceStock.State(), b.episodes.evidenceState)

        // 6. Der ZWEITE Start ist migrationsfrei und liefert dasselbe.
        val c = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-danach", t0 + 120_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertFalse(c.recoveryHold)
        assertEquals(b.episodes.evidenceEpisodeId, c.episodes.evidenceEpisodeId)
        assertEquals(b.episodes.evidenceCommittedU, c.episodes.evidenceCommittedU, 1e-9)
        assertEquals(b.episodes.evidenceState, c.episodes.evidenceState)
    }

    /**
     * UND EINE v4-DATEI OHNE BESTAND WIRD NICHT NOCH EINMAL MIGRIERT.
     *
     * Sie ist korrupt, nicht alt. Wuerde der Adapter hier migrieren, machte er
     * aus einer beschaedigten Buchhaltung stillschweigend eine gueltige.
     */
    @Test
    fun `eine v4-Datei ohne Evidenzbestand haelt an statt zu migrieren`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertTrue(a.persistVerified(dir))
        val target = File(dir, FuseLedgerStore.FILE_NAME)
        val kaputt = org.json.JSONObject(target.readText())
        kaputt.getJSONObject("episodes").remove("evidenceState")
        target.writeText(kaputt.toString())
        File(dir, FuseLedgerStore.FILE_NAME + ".bak").let { if (it.exists()) it.writeText(kaputt.toString()) }

        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-x", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertTrue(b.recoveryHold) { "korrupt heisst Hold, nicht Migration" }
    }
    // ---- SEAL_CYCLE_STATE ------------------------------------------------

    private fun bestand(stock: Double) = app.aaps.fuse.core.controller.EvidenceStock.State(
        stockMgdl = stock, episodeId = t0, episodeStartTs = t0,
        lastAcceptedTs = t0, lastAdjusted = 130.0, segmentStartTs = t0, lastDecayTs = t0,
    )

    /**
     * SCHEITERT DAS VERSIEGELN, DARF DER FOLGESTAND NICHT IM RAM WEITERLEBEN.
     *
     * Der Runner hat den Bestand bereits auf den Folgestand gesetzt, damit der
     * Persist ihn mitnimmt. Bleibt er nach einem Fehlschlag stehen, rechnete
     * der naechste Zyklus auf Evidenz, die nie auf Platte stand - der
     * Ein-Zyklus-Verzug waere ausgerechnet im Fehlerfall wirkungslos.
     */
    @Test
    fun `ein gescheitertes Seal rollt den Evidenzbestand zurueck`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val versiegelt = bestand(10.0)
        a.episodes.evidenceState = versiegelt
        // Der Runner schreibt den Folgestand.
        a.episodes.evidenceState = bestand(35.0)

        assertTrue(a.sealCycleState(sealed = false, before = versiegelt))
        assertEquals(versiegelt, a.episodes.evidenceState)
    }

    /** Bei GELUNGENEM Seal bleibt der Folgestand - sonst waere die
     *  Messinformation jedes Zyklus verloren. */
    @Test
    fun `ein gelungenes Seal laesst den Folgestand stehen`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val versiegelt = bestand(10.0)
        val folge = bestand(35.0)
        a.episodes.evidenceState = folge

        assertFalse(a.sealCycleState(sealed = true, before = versiegelt))
        assertEquals(folge, a.episodes.evidenceState)
    }

    /**
     * EIN GATE-REJECT NACH GELUNGENEM SEAL DREHT DIE MESSINFORMATION NICHT
     * ZURUECK.
     *
     * Abgelehnt wurde die INSULINRESERVIERUNG, nicht die Beobachtung. Beides
     * zusammenzuwerfen hiesse, eine gemessene Stoerung zu vergessen, weil eine
     * Dosis nicht durchging - und dieselbe Stoerung muesste danach erneut
     * beobachtet werden, bevor sie wieder zaehlt.
     */
    @Test
    fun `ein Gate-Reject nach gelungenem Seal laesst die Messinformation stehen`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val folge = bestand(35.0)
        a.episodes.evidenceState = folge
        a.episodes.evidenceCommittedU = 0.30

        // Das Gate hat die Menge entfernt - der Zustand war aber versiegelt.
        assertFalse(a.sealCycleState(sealed = true, before = bestand(10.0)))
        a.resolveReservation(computeTs = t0, publishedU = 0.0)   // das Gate hat die Menge entfernt

        assertEquals(folge, a.episodes.evidenceState) { "die Beobachtung bleibt" }
    }

    /** Ohne erreichten Kern gibt es nichts zurueckzurollen - dann hat auch
     *  niemand etwas veraendert. */
    @Test
    fun `ohne vorherigen Stand rollt nichts zurueck`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val stand = bestand(35.0)
        a.episodes.evidenceState = stand
        assertFalse(a.sealCycleState(sealed = false, before = null))
        assertEquals(stand, a.episodes.evidenceState)
    }
    // ---- Spaeter Schreibfehler unter dem Write-ahead-Marker ---------------

    /**
     * SPAETER FEHLER: MARKER LIEGT, ZUSTANDS-SYNC SCHEITERT.
     *
     * Der gefaehrliche Fall - der Rename ist gelaufen, der Zielname kann den
     * neuen Inhalt tragen, aber nichts davon ist bestaetigt. Der Speicher
     * rollt zurueck; ohne durablen Marker gewaenne beim Neustart trotzdem der
     * neue Zustand, und unversiegelte Evidenz kaeme wieder.
     */
    @Test
    fun `ein spaeter Persistfehler sperrt den Neustart dauerhaft`(@TempDir dir: File) {
        // Der erste Verzeichnis-Sync (der Marker) gelingt, der zweite (der
        // Zustand) scheitert.
        val store = FuseLedgerStore(FakeDurability(dirFailsFrom = 2))
        val a = FuseLedgerAdapter(store).also {
            it.loadOnce(dir, "epoch-a", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        a.episodes.evidenceState = bestand(35.0)

        assertFalse(a.persistVerified(dir)) { "der Verzeichnis-Sync des Zustands scheitert" }
        assertEquals(FuseLedgerStore.PersistOutcome.DIR_SYNC_FAILED, store.lastPersistStats?.outcome)
        assertTrue(FuseLedgerStore.sealPendingExists(dir)) { "der Marker muss liegenbleiben" }
        assertTrue(a.sealCycleState(sealed = false, before = bestand(10.0)))

        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertTrue(b.recoveryHold) { "der spaete Fehler muss den Neustart erreichen" }
    }

    /**
     * AUCH DER FRUEHE FEHLER HAELT JETZT AN - und das ist eine bewusste
     * Verschaerfung gegenueber der ersten Fassung.
     *
     * Dort unterschied ich frueh und spaet: bei einem gescheiterten
     * Datei-fsync zeigt der Zielname noch auf die Vorgeneration, ein Neustart
     * faende ohnehin den alten Stand. Die Unterscheidung hat aber DREIMAL
     * hintereinander einen Fall uebrig gelassen (zweiter Rename, Marker selbst
     * nicht durabel, Sentinel nach gelungenem Persist). Der Write-ahead-Marker
     * kennt die Faelle nicht einzeln - er sagt nur, dass der Vorgang nicht
     * sauber geendet hat.
     *
     * PREIS, ausdruecklich: ein harmloser frueher Fehler sperrt jetzt
     * ebenfalls, und das kostet eine Mahlzeit. Bezahlt wird damit, dass kein
     * unklarer Ausgang mehr durchrutscht.
     */
    @Test
    fun `auch ein frueher Persistfehler haelt den Neustart an`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-a", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertTrue(a.persistVerified(dir)) { "erst eine gesunde Generation" }

        val store = FuseLedgerStore(FakeDurability(fileFailsFrom = 2))
        val b = FuseLedgerAdapter(store).also {
            it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertFalse(b.persistVerified(dir))
        assertEquals(FuseLedgerStore.PersistOutcome.FILE_SYNC_FAILED, store.lastPersistStats?.outcome)

        val c = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-c", t0 + 120_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertTrue(c.recoveryHold) { "unklar ist unklar" }
    }

    /** Und laesst sich der MARKER nicht durabel setzen, wird gar nicht erst
     *  rotiert - ein Persist ohne Beweisspur waere der Vorgang, den wir
     *  hinterher nicht mehr einordnen koennten. */
    @Test
    fun `ohne durablen Marker wird nicht rotiert`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertTrue(a.persistVerified(dir))
        val vorher = File(dir, FuseLedgerStore.FILE_NAME).readText()

        val store = FuseLedgerStore(FakeDurability(fileFails = true))
        val b = FuseLedgerAdapter(store).also {
            it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        b.episodes.evidenceState = bestand(99.0)
        assertFalse(b.persistVerified(dir))
        assertEquals(vorher, File(dir, FuseLedgerStore.FILE_NAME).readText()) { "nichts angefasst" }
    }

    // ---- Write-ahead: SEAL_PENDING (Toni 12.08., drei P0) ----------------

    /**
     * GEGENPROBE 1: DER RENAME-ZWISCHENZUSTAND.
     *
     * `RENAME_FAILED` fasste zwei verschiedene Fehler zusammen. Scheitert
     * `target -> bak`, bleibt der Altstand und alles ist gut. Scheitert danach
     * `tmp -> target`, liegt der NEUE, unversiegelte Zustand weiterhin in
     * `.tmp` - und die Revisionsauswahl beim Start zieht `.tmp` ausdruecklich
     * heran. Der unversiegelte Bestand kaeme also zurueck.
     *
     * Hier nachgestellt ohne Rename-Fehler: `.tmp` traegt eine hoehere
     * Revision als das Ziel, so wie nach einem gescheiterten zweiten Rename.
     */
    @Test
    fun `ein unversiegeltes tmp darf beim Start nicht gewinnen`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        a.episodes.evidenceState = bestand(10.0)
        assertTrue(a.persistVerified(dir))
        val alt = File(dir, FuseLedgerStore.FILE_NAME).readText()

        // Der Zustand, dessen Versiegelung NICHT abgeschlossen wurde.
        val neu = org.json.JSONObject(alt)
        neu.put("revision", neu.getLong("revision") + 5)
        neu.getJSONObject("episodes").getJSONObject("evidenceState").put("stockMgdl", 99.0)
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp").writeText(neu.toString())
        // Und der Write-ahead-Marker, den ein unterbrochener Persist stehen
        // laesst.
        FuseLedgerStore.markSealPendingForTest(dir)

        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertTrue(b.recoveryHold) { "ein unterbrochener Persist muss den Start anhalten" }
    }

    /**
     * GEGENPROBE 2: DER MARKER MUSS SELBST DURABEL SEIN.
     *
     * Er wurde mit `writeText` geschrieben und aus demselben Page Cache
     * zurueckgelesen - ohne Datei- und ohne Verzeichnis-fsync. Nach einem
     * Stromausfall waere ausgerechnet der Beweis weg, der den Neustart
     * anhalten soll.
     */
    @Test
    fun `der Write-ahead-Marker wird durabel geschrieben`(@TempDir dir: File) {
        val fake = FakeDurability()
        val store = FuseLedgerStore(fake)
        val a = FuseLedgerAdapter(store).also {
            it.loadOnce(dir, "epoch-a", t0, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        val vorher = fake.fileSyncs to fake.dirSyncs

        a.persistVerified(dir)

        assertTrue(fake.fileSyncs > vorher.first + 1) { "Marker UND Zustand brauchen je einen Datei-fsync" }
        assertTrue(fake.dirSyncs > vorher.second + 1) { "und je einen Verzeichnis-fsync" }
    }

    /**
     * GEGENPROBE 3: DER SENTINEL SCHEITERT NACH GELUNGENEM LEDGER-PERSIST.
     *
     * Dann ist `ok = false`, waehrend `lastPersistStats.outcome` OK meldet -
     * die Fallunterscheidung nach Fehlerklasse greift also nicht, und es
     * entstuende kein Hold. Der Speicher rollt zurueck, beim Neustart gewinnt
     * trotzdem der neue Zustand.
     *
     * Der Sentinel wird hier durch ein VERZEICHNIS gleichen Namens am
     * Schreiben gehindert - ein echter Dateisystemfehler, kein Mock.
     */
    @Test
    fun `ein Sentinelfehler nach gelungenem Persist haelt den Neustart an`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertTrue(a.persistVerified(dir))
        File(dir, FuseLedgerStore.SENTINEL_NAME).delete()
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).mkdirs()) { "Sentinel blockieren" }

        a.episodes.evidenceState = bestand(35.0)
        assertFalse(a.persistVerified(dir)) { "der Sentinel scheitert" }
        assertEquals(FuseLedgerStore.PersistOutcome.OK, a.lastPersistStats?.outcome) {
            "und der Ledger-Persist selbst meldet OK - genau die Luecke"
        }

        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertTrue(b.recoveryHold) { "ein unvollstaendiger Persist muss den Start anhalten" }
    }

    /** GEGENPROBE 4: nach einem VOLLSTAENDIGEN Persist ist der Marker weg -
     *  sonst hielte FUSE nach jedem gesunden Zyklus an. */
    @Test
    fun `ein vollstaendiger Persist raeumt den Marker ab`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertTrue(a.persistVerified(dir))
        assertFalse(FuseLedgerStore.sealPendingExists(dir)) { "kein Rueckstand nach gesundem Zyklus" }

        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "epoch-b", t0 + 60_000L, FuseActivePump(PumpType.GENERIC_AAPS.name, false))
        }
        assertFalse(b.recoveryHold)
    }
}
