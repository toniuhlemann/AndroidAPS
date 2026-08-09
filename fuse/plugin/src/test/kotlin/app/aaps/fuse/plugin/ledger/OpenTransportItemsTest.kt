package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * C3-01 (P0, Codex Fix-Pass-5-Closure G.2): die LESENDE Zusatz-API, die jeden
 * offenen Posten EINZELN herausgibt - Menge plus bester bekannter Zeitstempel.
 *
 * `oldestOpenTs()` bleibt daneben bestehen (Vollsicht-Fensteranfang); die
 * Transport-Modellierung benutzt es nicht mehr, weil ein einziger Anker fuer
 * mehrere Dosen die Resthaftung der juengeren unterschlaegt.
 */
class OpenTransportItemsTest {

    private val t0 = 1_700_000_000_000L

    private fun smb(ts: Long, amount: Double, pumpId: Long) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", pumpId = pumpId),
    )

    private fun FuseLedgerAdapter.publishVs(id: String, u: Double, ts: Long, latest: Long = 0L) =
        onPublished(id, u, ts, latest, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))

    private fun adapter(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    @Test
    fun `die Summe der Posten ist genau der Ledgerwert`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.10, t0 - 12 * 60_000L)
        a.publishVs("p2", 0.30, t0 - 2 * 60_000L)
        val items = a.openTransportItems()
        assertEquals(2, items.size)
        assertEquals(a.view().transportCommitmentU, items.sumOf { it.commitmentU }, 1e-12)
    }

    @Test
    fun `jeder Posten traegt seine eigene Menge und seinen eigenen Zeitstempel`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.10, t0 - 12 * 60_000L)
        a.publishVs("p2", 0.30, t0 - 2 * 60_000L)
        val byId = a.openTransportItems().associateBy { it.proposalId }
        assertEquals(0.10, byId.getValue("p1").commitmentU, 1e-12)
        assertEquals(t0 - 12 * 60_000L, byId.getValue("p1").bestKnownTs)
        assertEquals(0.30, byId.getValue("p2").commitmentU, 1e-12)
        assertEquals(t0 - 2 * 60_000L, byId.getValue("p2").bestKnownTs)
        // oldestOpenTs bleibt unveraendert bestehen - additive API.
        assertEquals(t0 - 12 * 60_000L, a.oldestOpenTs())
    }

    /** Nach der Bindung gilt der TREATMENT-Zeitstempel, nicht mehr der
     *  Entscheidungszeitpunkt - er ist der bessere Beleg fuer die Lieferung. */
    @Test
    fun `nach der Bindung gilt der Treatment-Zeitstempel`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.30, t0)
        a.bindIdentities(listOf(smb(t0 + 5_000L, 0.30, pumpId = 4711L)))
        val item = a.openTransportItems().single()
        assertEquals(t0 + 5_000L, item.bestKnownTs)
        assertEquals(4711L, item.pumpId)
    }

    /**
     * C3-02: eine GEBUCHTE Zeile verschwindet nicht aus der Liste. Genau sie
     * ist der Posten, dessen Snapshot-Zugehoerigkeit der Runner pruefen muss -
     * waere sie hier schon weg, koennte er die Luecke gar nicht mehr sehen.
     */
    @Test
    fun `eine gebuchte Zeile bleibt als Posten sichtbar`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.30, t0)
        val b = smb(t0 + 5_000L, 0.30, pumpId = 4711L)
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)

        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        val item = a.openTransportItems().single()
        assertEquals(0.0, item.commitmentU, 1e-12)
        assertEquals(0.30, item.grossLiabilityU, 1e-12)
        assertEquals(0.30, item.accountedAmountU, 1e-12)
        assertTrue(item.pumpId == 4711L)
    }

    /** Teilbuchung: der Rest bleibt offen, die Buchung steht daneben. */
    @Test
    fun `eine Teilbuchung laesst den Rest offen`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.30, t0)
        val b = smb(t0 + 5_000L, 0.30, pumpId = 4711L)
        a.bindIdentities(listOf(b))
        val teil = smb(t0 + 5_000L, 0.20, pumpId = 4711L)
        a.onCycleSnapshot(listOf(LedgerFacts.fact(teil)), LedgerFacts.snapshotHash(listOf(teil)), t0 + 60_000L)

        val item = a.openTransportItems().single()
        assertEquals(0.10, item.commitmentU, 1e-12)
        assertEquals(0.30, item.grossLiabilityU, 1e-12)
        assertEquals(0.20, item.accountedAmountU, 1e-12)
    }

    @Test
    fun `ohne offene Zeile ist die Liste leer`(@TempDir dir: File) {
        assertTrue(adapter(dir).openTransportItems().isEmpty())
    }
}
