package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.ledger.NotSentProof
import app.aaps.fuse.core.util.Sha
import app.aaps.fuse.plugin.FuseCycleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DER SERIEN-DECKEL AM ECHTEN LEDGER (Variante 2, Review-Auflage).
 *
 * WARUM DIESER TEST ZUSAETZLICH NOETIG IST, und der Grund ist eine
 * Annahme, die der pure Test stillschweigend gemacht hat: dort wurden
 * die beiden Listen SYNTHETISCH gestellt - "bestaetigt" hiess einfach
 * "kein Transportposten mehr". Das entspricht dem Ledger nicht
 * zwingend: eine im IOB nachgewiesene Zeile kann waehrend ihrer
 * Haftungsfrist weiter in [FuseLedgerAdapter.openTransportItems]
 * stehen. GENAU DANN muss der ID-Abgleich tragen, und genau das kann
 * nur ein Lauf ueber die echten Uebergaenge zeigen.
 *
 * Der Rollback wird hier ebenfalls echt gefahren ([revokeSettled] mit
 * einem [NotSentProof]-Grund) statt durch zwei leere Listen ersetzt.
 */
class SerienDeckelLedgerTest {

    private val t0 = 1_700_000_000_000L
    private val deckel = 1.0
    private val menge = 0.30
    private val pid = "korr#1"

    private fun smb(ts: Long, amount: Double, pumpId: Long) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", pumpId = pumpId),
    )

    /**
     * Ein Adapter mit EINER markerlosen Korrekturdosis, genau so gebucht
     * wie der Runner es tut: Serienzeile plus Reservierung, die auf sie
     * zeigt.
     */
    private fun adapterMitKorrektur(dir: File): FuseLedgerAdapter {
        val a = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }
        val e = a.episodes
        e.correctionDeliveries.addLast(EpisodeBudgets.CorrectionDelivery(t0, menge))
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = t0, amountU = menge, prime = false, onset = false,
            mealTs = 0L, correctionTs = t0,
        )
        return a
    }

    /** Der Headroom aus den ECHTEN Ledgersichten - kein synthetisches Paar. */
    private fun restAus(a: FuseLedgerAdapter, nowTs: Long = t0 + 60_000L): Double? =
        FuseCycleRunner.serienHeadroom(
            capU = deckel,
            fensterMin = 30,
            gebucht = a.episodes.correctionDeliveries,
            transport = FuseCycleRunner.transportDoses(a.openTransportItems(), null, nowTs),
            nowTs = nowTs,
        )

    @Test
    fun `Schritte 1 bis 5 am echten Ledger`(@TempDir dir: File) {
        val a = adapterMitKorrektur(dir)

        // (1)+(2) Publikation und Aufloesung - wie im echten Zyklus.
        a.onPublished(pid, menge, t0, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        a.resolveReservation(t0, publishedU = menge, proposalId = pid)
        val zeile = a.episodes.correctionDeliveries.single()
        assertEquals(pid, zeile.proposalId, "die Kennung MUSS nachgetragen sein")
        assertNotNull(a.episodes.settled, "und die Ablage entstanden")

        // (3) Der Posten steht in BEIDEN Sichten - abgezogen wird er einmal.
        val transport = a.openTransportItems()
        assertTrue(transport.any { it.proposalId == pid }) {
            "die Vorbedingung des Tests: der Posten MUSS offen sein"
        }
        assertEquals(0.70, restAus(a)!!, 1e-9) {
            "0,30 einmal - der skalare Abzug ergaebe 0,40"
        }

        // (4) PUMPENBESTAETIGUNG durch den echten Reducer.
        val bolus = smb(t0 + 1_000L, menge, pumpId = 4711L)
        a.bindIdentities(listOf(bolus))
        a.onCycleSnapshot(
            listOf(LedgerFacts.fact(bolus)),
            LedgerFacts.snapshotHash(listOf(bolus)),
            t0 + 60_000L,
        )
        // Die Zeile darf weiter offen gefuehrt werden - das ist gerade der
        // Fall, den der pure Test NICHT abbilden konnte.
        val nachBestaetigung = a.openTransportItems().firstOrNull { it.proposalId == pid }
        assertEquals(0.70, restAus(a)!!, 1e-9) {
            "der Rest bleibt 0,70 - egal ob die Zeile noch als Transport gefuehrt wird " +
                "(accounted=${nachBestaetigung?.accountedAmountU}, " +
                "commitment=${nachBestaetigung?.commitmentU})"
        }

        // (5) DER ECHTE RUECKDREHER. Wir nehmen den strengsten Beweis:
        // nie kommandiert.
        val grund = NotSentProof.reasonFor(
            NotSentProof.Observation(
                correlated = true,
                ledgerPublishedU = menge,
                gateStripped = false,
                gateSealed = true,
                gatePersistFailed = false,
                aapsConstrainedU = 0.0,
                smbSetByPumpPresent = false,
            )
        )
        assertNotNull(grund, "der Aufbau MUSS einen Nicht-Sende-Beweis liefern")
        a.onProvenNotSent(pid, grund!!)
        a.revokeSettled(pid)
        assertTrue(a.episodes.correctionDeliveries.isEmpty()) {
            "die Serienzeile MUSS verschwinden: " +
                a.episodes.correctionDeliveries.map { it.amountU }
        }
        assertEquals(1.00, restAus(a)!!, 1e-9, "und der Deckel ist wieder unberuehrt")
    }

    @Test
    fun `Schritt 6 - ohne auffindbare Serienzeile entsteht keine Ablage`(@TempDir dir: File) {
        // Der Widerspruchsfall: die Reservierung nennt eine Serienzeile,
        // die es nicht (mehr) gibt. Dann darf KEIN Settled entstehen -
        // sonst gaebe es eine Buchung, die widerrufbar aussieht, deren
        // Zeile revokeSettled aber nie faende.
        val a = adapterMitKorrektur(dir)
        a.episodes.correctionDeliveries.clear()
        a.onPublished(pid, menge, t0, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        a.resolveReservation(t0, publishedU = menge, proposalId = pid)
        assertNull(a.episodes.settled) {
            "ohne nachtragbare Serienzeile darf keine Ablage entstehen"
        }
    }

    @Test
    fun `der Gate-Verwurf raeumt die Serienzeile ab`(@TempDir dir: File) {
        // Die zweite Rollback-Stufe, ebenfalls echt: das Publikationsgate
        // hat die Menge entfernt (publishedU = 0).
        val a = adapterMitKorrektur(dir)
        a.resolveReservation(t0, publishedU = 0.0, proposalId = pid)
        assertTrue(a.episodes.correctionDeliveries.isEmpty()) {
            "verworfen heisst: nicht geflossen, also auch nicht gebucht"
        }
        assertEquals(1.00, restAus(a)!!, 1e-9)
    }

    @Test
    fun `eine fremde offene Dosis zaehlt zusaetzlich`(@TempDir dir: File) {
        // Gegenprobe zum ID-Abgleich: ein ANDERES Proposal darf sehr wohl
        // zusaetzlich belasten - sonst waere der Abgleich zu grosszuegig.
        val a = adapterMitKorrektur(dir)
        a.onPublished(pid, menge, t0, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        a.resolveReservation(t0, publishedU = menge, proposalId = pid)
        a.onPublished("fremd#2", 0.20, t0 + 30_000L, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        assertEquals(0.50, restAus(a)!!, 1e-9, "0,30 gebucht + 0,20 fremd offen")
    }
}
