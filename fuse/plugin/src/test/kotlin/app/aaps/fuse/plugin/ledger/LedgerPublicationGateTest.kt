package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PUBLIKATIONS-GATING (Audit 2d273cb, REG-01a): kein Pfad darf bei
 * nicht-durablem Ledger positive units publizieren. Getestet gegen den
 * ECHTEN Store mit einem unbeschreibbaren Verzeichnis - der Store-Test
 * allein beweist nur `false`, nicht die gesperrte Publikation (Codex 4.4).
 */
class LedgerPublicationGateTest {

    private val t0 = 1_700_000_000_000L

    /** RT mit SMB UND Safety-TBR (Null-Temp) - genau die Kombination, bei
     *  der das Gating nur die eine Haelfte entfernen darf. */
    private fun rtWithSmb() = RT(
        algorithm = APSResult.Algorithm.FUSE,
        timestamp = t0,
        reason = StringBuilder("FUSE test"),
        rate = 0.0,
        duration = 30,
        units = 0.30,
        deliverAt = t0,
    )

    private fun loadedAdapter(dir: File): FuseLedgerAdapter =
        FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    /** Ein Pfad, in den der Store nicht schreiben kann: Datei statt
     *  Verzeichnis als Elternteil. */
    private fun unwritableDir(parent: File): File {
        val blockiert = File(parent, "datei-statt-verzeichnis")
        blockiert.writeText("x")
        return File(blockiert, "unter")
    }

    /** Der Regelfall: dieser Zyklus bucht "p1". */
    private val bucht = LedgerPublicationGate.Commitment.Proposal("p1")

    @Test
    fun `erfolgreicher Persist publiziert die units unveraendert`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val rt = rtWithSmb()
        val out = LedgerPublicationGate.publish(rt, a, dir, bucht, events = {
            a.onPublished("p1", 0.30, t0, 0L, 0.05)
        })
        assertEquals(0.30, out.units!!, 1e-12)
        assertEquals(t0, out.deliverAt)
        assertFalse(out.reason.contains(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED))
        // Der Vorschlag steht in der Datei, BEVOR das RT zurueckkommt.
        assertTrue(File(dir, FuseLedgerStore.FILE_NAME).exists())
        assertFalse(a.view().hold)
    }

    @Test
    fun `Persist-Fehlschlag entfernt units und deliverAt, TBR und Grund bleiben`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val out = LedgerPublicationGate.publish(rtWithSmb(), a, unwritableDir(dir), bucht, events = {
            a.onPublished("p1", 0.30, t0, 0L, 0.05)
        })
        assertNull(out.units)
        assertNull(out.deliverAt)
        // Die Safety-TBR darf NICHT mit verloren gehen.
        assertEquals(0.0, out.rate!!, 1e-12)
        assertEquals(30, out.duration)
        assertTrue(out.reason.endsWith(" | " + FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED))
        // Sticky: kuenftige Zyklen sind ueber view().hold zu, bis der
        // Ledger wieder durabel ist.
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED, a.view().holdReason)
        // Die Verbindlichkeit bleibt im Speicher stehen (konservativ) -
        // publiziert wurde sie nur nicht.
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    @Test
    fun `Wurf in den Ledger-Schritten entfernt units auch bei erfolgreichem Persist`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        var gesehen: Throwable? = null
        val out = LedgerPublicationGate.publish(
            rtWithSmb(), a, dir, bucht,
            events = { error("ledger step explodiert") },
            onError = { gesehen = it },
        )
        assertNull(out.units)
        assertNull(out.deliverAt)
        assertTrue(out.reason.endsWith(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED))
        assertNotNull(gesehen)
        // Der Persist lief trotzdem: der letzte konsistente Stand liegt auf
        // Platte, und weil er durabel ist, sperrt NUR dieser Zyklus.
        assertTrue(File(dir, FuseLedgerStore.FILE_NAME).exists())
        assertFalse(a.view().hold)
    }

    // ---- G2 (Codex-Adjudication bae885f1): Hold im SELBEN Zyklus ----------

    private fun smb(ts: Long, amount: Double, pumpId: Long) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", pumpId = pumpId),
    )

    /**
     * DER G2-Repro: die Reconciliation entdeckt WAEHREND der Events einen
     * Hold (hier MISSING_ACCOUNTED_TREATMENT - der zuvor nachgewiesene Fakt
     * fehlt in der neuen Vollsicht), der Persist gelingt - und das Gate gab
     * das positive RT trotzdem unveraendert zurueck. Der Hold griff erst im
     * NAECHSTEN Zyklus, also eine Dosis zu spaet.
     */
    @Test
    fun `G2 ein waehrend der Events entdeckter Hold strippt den SMB im selben Zyklus`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val b = smb(t0 + 5_000L, 0.30, pumpId = 4711L)
        val out = LedgerPublicationGate.publish(rtWithSmb(), a, dir, bucht, events = {
            a.onPublished("p1", 0.30, t0, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            a.bindIdentities(listOf(b))
            // Erst nachgewiesen ...
            a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)
            // ... dann aus der Vollsicht verschwunden: MISSING_ACCOUNTED_TREATMENT.
            a.onCycleSnapshot(emptyList(), LedgerFacts.snapshotHash(emptyList()), t0 + 120_000L)
        })
        assertTrue(a.view().hold, "der Ledger haelt nicht - Testaufbau falsch")
        assertNull(out.units, "der SMB wurde trotz Hold publiziert")
        assertNull(out.deliverAt)
        // Die Safety-TBR bleibt, wie bei den anderen Strip-Pfaden.
        assertEquals(0.0, out.rate!!, 1e-12)
        assertEquals(30, out.duration)
        assertTrue(out.reason.contains(LedgerPublicationGate.REASON_LATE_HOLD), "reason=${out.reason}")
        // Der Persist selbst war erfolgreich - es ist KEIN Persist-Fehlschlag.
        assertFalse(a.persistFailed)
    }

    @Test
    fun `RT ohne units bleibt unveraendert aber ein Persist-Fehlschlag sperrt kuenftige Zyklen`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val rt = RT(
            algorithm = APSResult.Algorithm.FUSE,
            timestamp = t0,
            reason = StringBuilder("FUSE test"),
            rate = 0.0,
            duration = 30,
        )
        // Ohne units zieht das Gate den Commitment gar nicht heran.
        val out = LedgerPublicationGate.publish(rt, a, unwritableDir(dir), bucht, events = {})
        // Nichts zu entfernen - dasselbe Objekt kommt zurueck ...
        assertSame(rt, out)
        assertEquals(0.0, out.rate!!, 1e-12)
        // ... aber der naechste Zyklus ist ueber den Hold gedeckelt.
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED, a.view().holdReason)
    }

    // ---- B0a: die Haftung muss ENTSTANDEN sein, nicht nur beabsichtigt ----

    /**
     * DER KERN VON B0a. Ereignisse und Persist laufen fehlerfrei - es entsteht
     * nur keine Zeile. Vorher schloss das Gate aus "kein Fehler" auf "Haftung
     * gebucht" und gab die Menge frei. Ein SMB waere hinausgegangen, dessen
     * Verbindlichkeit in keinem Buch steht.
     */
    @Test
    fun `ohne gebuchtes Proposal gehen keine units hinaus`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val out = LedgerPublicationGate.publish(rtWithSmb(), a, dir, bucht, events = { /* nichts gebucht */ })

        assertNull(out.units, "units ohne jede gebuchte Haftung publiziert")
        assertNull(out.deliverAt)
        // Safety-TBR bleibt - wie bei jedem anderen Strip-Pfad.
        assertEquals(0.0, out.rate!!, 1e-12)
        assertEquals(30, out.duration)
        assertTrue(out.reason.contains(LedgerPublicationGate.REASON_PROPOSAL_MISSING), "reason=${out.reason}")
        // Und es darf auch kein Phantom-Commitment entstanden sein.
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold, "ein fehlender Vorschlag ist kein Ledger-Hold")
    }

    /** Die Gegenprobe: eine ANDERE als die erwartete Zeile zaehlt nicht. */
    @Test
    fun `eine fremde gebuchte Zeile ersetzt die erwartete nicht`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val out = LedgerPublicationGate.publish(rtWithSmb(), a, dir, bucht, events = {
            a.onPublished("EIN-ANDERER", 0.30, t0, 0L, 0.05)
        })
        assertNull(out.units)
        assertTrue(out.reason.contains(LedgerPublicationGate.REASON_PROPOSAL_MISSING))
        // Die fremde Zeile haftet weiter - sie ist ja gebucht.
        assertEquals(0.30, a.view().transportCommitmentU, 1e-12)
    }

    /**
     * `Commitment.None`: der Aufrufer weiss selbst, dass er nichts buchen
     * kann (B0a: fehlende Behandlungs-Vollsicht). Dann darf keine Menge
     * hinausgehen, und der Grund muss der seine sein.
     */
    @Test
    fun `ein Zyklus ohne Buchungsabsicht publiziert keine units`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val out = LedgerPublicationGate.publish(
            rtWithSmb(), a, dir,
            LedgerPublicationGate.Commitment.None(LedgerPublicationGate.REASON_TREATMENT_VIEW_UNAVAILABLE),
            events = { /* bewusst kein onPublished */ },
        )
        assertNull(out.units)
        assertNull(out.deliverAt)
        assertEquals(0.0, out.rate!!, 1e-12)
        assertEquals(30, out.duration)
        assertTrue(
            out.reason.endsWith(" | " + LedgerPublicationGate.REASON_TREATMENT_VIEW_UNAVAILABLE),
            "reason=${out.reason}",
        )
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)
        // Der Persist lief trotzdem - die Reconciliation dieses Zyklus gehoert
        // auf Platte, auch wenn nicht dosiert wird.
        assertTrue(File(dir, FuseLedgerStore.FILE_NAME).exists())
    }

    @Test
    fun `hasOpenProposal kennt nur offene Zeilen`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        assertFalse(a.hasOpenProposal("p1"))
        a.onPublished("p1", 0.30, t0, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        assertTrue(a.hasOpenProposal("p1"))

        // Nachgewiesen und geschlossen -> keine Haftung mehr, also auch kein
        // Freibrief fuer eine neue Menge.
        val b = smb(t0 + 5_000L, 0.30, pumpId = 4711L)
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)
        assertTrue(a.state.entries.getValue("p1").closed, "Testaufbau: die Zeile sollte geschlossen sein")
        assertFalse(a.hasOpenProposal("p1"))
    }
}
