package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.AccountedTreatment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C3-02 (P0, Codex Fix-Pass-5-Closure Abschnitt G.3/K): der Uebergang
 * TRANSPORT -> IOB braucht einen nachweisbaren INCLUSION-VERTRAG.
 *
 * Der gefaehrliche Fall ist NICHT die Doppelzaehlung - die ist konservativ -,
 * sondern die LUECKE: die Reconciliation sieht die Behandlung und senkt das
 * Commitment auf 0, das IOB-Array des Zyklus stammt aber noch aus einer
 * Lesung OHNE diese Behandlung. Dann steckt die Menge in KEINER Sicht.
 *
 * Der Vertrag lautet: eine Menge verlaesst die Transport-Modellierung NUR mit
 * positivem Nachweis, dass ihr Fakt in der Bolus-Lesung stand, die dem Bau der
 * IOB-Arrays VORAUSGING. Ist die Zugehoerigkeit nicht entscheidbar, bleibt der
 * Posten in voller Hoehe Transport - konservativ doppelt statt unsichtbar.
 */
class TransportInclusionTest {

    private val t0 = 1_700_000_000_000L
    private val fensterStart = t0 - 9 * 3600_000L

    private fun item(
        commitmentU: Double,
        grossU: Double,
        accountedU: Double,
        ts: Long = t0 - 60_000L,
        pumpId: Long? = 4711L,
        temporaryId: Long? = null,
        settledZero: Boolean = false,
    ) = OpenTransportItem(
        proposalId = "p1",
        commitmentU = commitmentU,
        grossLiabilityU = grossU,
        accountedAmountU = accountedU,
        bestKnownTs = ts,
        temporaryId = temporaryId,
        pumpId = pumpId,
        settledZero = settledZero,
    )

    private fun witness(vararg facts: AccountedTreatment) =
        TransportInclusion.witnessOf(facts.toList(), fromTs = fensterStart, readAtTs = t0)

    private fun fakt(pumpId: Long? = 4711L, temporaryId: Long? = null) =
        AccountedTreatment(temporaryId, pumpId, 0.30, "GENERIC_AAPS", "hash")

    // ---- Die Stale-Cache-Reihenfolge --------------------------------------

    /**
     * GENAU DIE REIHENFOLGE AUS G.3: der Ledger hat die Menge schon gebucht
     * (Commitment 0), die Lesung vor dem IOB-Bau kennt den Fakt aber nicht.
     * Der Posten darf NICHT verschwinden.
     */
    @Test
    fun `gebucht aber nicht in der Snapshot-Lesung - der Posten bleibt voll modelliert`() {
        val gebucht = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.30)
        val ohneFakt = witness(fakt(pumpId = 9999L))
        assertFalse(TransportInclusion.inSnapshot(gebucht, ohneFakt))
        assertEquals(0.30, TransportInclusion.modelledU(gebucht, ohneFakt), 1e-12)
    }

    /** Mit Nachweis faellt der Posten - das ist der Normalbetrieb und darf
     *  sich nicht aendern, sonst blieben Mengen ewig doppelt gebunden. */
    @Test
    fun `mit Nachweis in der Lesung faellt der Posten auf den Ledgerwert`() {
        val gebucht = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.30)
        val mitFakt = witness(fakt(pumpId = 4711L))
        assertTrue(TransportInclusion.inSnapshot(gebucht, mitFakt))
        assertEquals(0.0, TransportInclusion.modelledU(gebucht, mitFakt), 1e-12)
    }

    /** Die temporaryId zaehlt genauso - Medtrum legt zuerst nur sie an. */
    @Test
    fun `auch die temporaryId ist ein Nachweis`() {
        val gebucht = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.30, pumpId = null, temporaryId = 77L)
        assertTrue(TransportInclusion.inSnapshot(gebucht, witness(fakt(pumpId = null, temporaryId = 77L))))
        assertFalse(TransportInclusion.inSnapshot(gebucht, witness(fakt(pumpId = 77L, temporaryId = null))))
    }

    /** KEINE Lesung heisst NICHT ENTSCHEIDBAR - und damit voll modelliert.
     *  Eine scheiternde Datenbankabfrage darf keine Haftung loeschen. */
    @Test
    fun `ohne Lesung ist nichts entscheidbar und der Posten haftet voll`() {
        val gebucht = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.30)
        assertFalse(TransportInclusion.inSnapshot(gebucht, null))
        assertEquals(0.30, TransportInclusion.modelledU(gebucht, null), 1e-12)
    }

    // ---- Was der Vertrag NICHT anfasst ------------------------------------

    /** Eine Zeile ohne jede Buchung stellt die Frage gar nicht: ihr
     *  Ledgerwert IST die volle Haftung. Ohne diese Regel wuerde jeder
     *  normale offene SMB unnoetig ueber den Zeugen laufen. */
    @Test
    fun `eine ungebuchte Zeile braucht keinen Nachweis`() {
        val offen = item(commitmentU = 0.30, grossU = 0.30, accountedU = 0.0)
        assertEquals(0.30, TransportInclusion.modelledU(offen, null), 1e-12)
        assertEquals(0.30, TransportInclusion.modelledU(offen, witness()), 1e-12)
    }

    /** Bewiesene Nullabgabe: es floss nichts, also haftet nichts - unabhaengig
     *  von jeder Snapshot-Zugehoerigkeit. */
    @Test
    fun `eine bewiesene Nullabgabe haftet nicht`() {
        val nichts = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.0, settledZero = true)
        assertEquals(0.0, TransportInclusion.modelledU(nichts, null), 1e-12)
    }

    /**
     * Ein Fakt VOR dem Fensteranfang der Lesung ist nicht "unbekannt", sondern
     * aelter als das IOB-Fenster: seine Wirkung ist in beiden Sichten
     * ausgelaufen. Ohne diese Regel wuerde jede zwischen DIA und DIA+2 h
     * geschlossene Zeile bis zum Pruning erneut als Transport auftauchen.
     */
    @Test
    fun `ein Fakt aelter als das IOB-Fenster gilt als ausgelaufen`() {
        val alt = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.30, ts = fensterStart - 60_000L)
        assertEquals(0.0, TransportInclusion.modelledU(alt, witness()), 1e-12)
    }

    /** Ein Zeitstempel NACH der Lesung kann in ihr nicht gestanden haben. */
    @Test
    fun `ein Fakt nach der Lesung ist nicht nachgewiesen`() {
        val zukunft = item(commitmentU = 0.0, grossU = 0.30, accountedU = 0.30, ts = t0 + 60_000L)
        assertFalse(TransportInclusion.inSnapshot(zukunft, witness(fakt())))
        assertEquals(0.30, TransportInclusion.modelledU(zukunft, witness(fakt())), 1e-12)
    }

    // ---- Einseitigkeit ----------------------------------------------------

    /** DER RIEGEL: die modellierte Menge liegt NIE unter dem Ledgerwert. Ein
     *  Inclusion-Vertrag, der weniger modelliert als der Ledger fuehrt, waere
     *  ein Weg an Haftung vorbei. */
    @Test
    fun `Property - die modellierte Menge unterschreitet nie den Ledgerwert`() {
        val zeugen = listOf(null, witness(), witness(fakt()), witness(fakt(pumpId = 1L)))
        for (gross in listOf(0.05, 0.30, 1.20)) for (acc in listOf(0.0, 0.01, gross / 2, gross)) {
            val commitment = maxOf(0.0, gross - acc)
            for (ts in listOf(fensterStart - 1L, fensterStart, t0 - 60_000L, t0, t0 + 1L)) {
                val i = item(commitment, gross, acc, ts = ts)
                for (w in zeugen) {
                    val m = TransportInclusion.modelledU(i, w)
                    assertTrue(m >= commitment - 1e-12) { "gross=$gross acc=$acc ts=$ts -> $m < $commitment" }
                    assertTrue(m <= gross + 1e-12) { "gross=$gross acc=$acc ts=$ts -> $m > $gross" }
                }
            }
        }
    }
}
