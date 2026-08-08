package app.aaps.fuse.plugin.ledger

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
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

    @Test
    fun `erfolgreicher Persist publiziert die units unveraendert`(@TempDir dir: File) {
        val a = loadedAdapter(dir)
        val rt = rtWithSmb()
        val out = LedgerPublicationGate.publish(rt, a, dir, events = {
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
        val out = LedgerPublicationGate.publish(rtWithSmb(), a, unwritableDir(dir), events = {
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
            rtWithSmb(), a, dir,
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
        val out = LedgerPublicationGate.publish(rt, a, unwritableDir(dir), events = {})
        // Nichts zu entfernen - dasselbe Objekt kommt zurueck ...
        assertSame(rt, out)
        assertEquals(0.0, out.rate!!, 1e-12)
        // ... aber der naechste Zyklus ist ueber den Hold gedeckelt.
        assertTrue(a.view().hold)
        assertEquals(FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED, a.view().holdReason)
    }
}
