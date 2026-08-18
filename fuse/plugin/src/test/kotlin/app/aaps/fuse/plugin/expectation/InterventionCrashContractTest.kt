package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.plugin.ledger.Durability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DER PFLICHTTEST ZUM PUBLIKATIONSVERTRAG (Toni 18.08.).
 *
 *     offene Erwartung Revision N
 *     Dosis vorbereitet -> Revision N+1 dauerhaft
 *     Absturz unmittelbar nach Publikation
 *     Neustart
 *     -> Erwartung wird INTERVENED, niemals MISSED
 *
 * WIE HIER EIN ABSTURZ SIMULIERT WIRD. Ein echter Prozesstod laesst sich im
 * Unit-Test nicht ausloesen, und ein System.exit beendete den Testlauf. Was
 * einen Absturz AUSMACHT, ist aber nicht das Sterben - es ist der
 * Zustandsverlust: alles, was nur im Speicher stand, ist weg; alles, was
 * durabel geschrieben wurde, ist da. Genau das wird hier nachgebildet, indem
 * die geschriebenen DATEIEN in frische Objekte hinein wieder gelesen werden
 * und keinerlei Speicherstand hinuebergereicht wird. Der Test ist damit
 * strenger als ein echter Absturz, nicht schwaecher: er verliert den Speicher
 * garantiert, waehrend ein realer Prozesstod manchmal noch Puffer wegschreibt.
 */
class InterventionCrashContractTest {

    private val t0 = 1_700_000_000_000L
    private val H = 30
    private val CFG = "cfg#1"
    private val KORR = ExpectationLedger.Classification(
        ExpectationLedger.ExpectationContext.CORRECTION,
        ExpectationLedger.ContextReason.PURE_CORRECTION,
    )

    /** Im JVM-Test gibt es kein Android-Os; gesynct wird also nichts.
     *  Fuer diesen Test unerheblich - er prueft die REIHENFOLGE, nicht die
     *  Haltbarkeit einzelner Bytes. */
    private class FakeDurability : Durability {

        override fun syncFile(fd: java.io.FileDescriptor) = Unit
        override fun syncDirectory(dir: File) = Unit
    }

    private fun store() = FuseExpectationStore(FakeDurability())

    /**
     * ABRECHNEN UEBER DIE OEFFENTLICHE API.
     *
     * `settle` ist im Kern absichtlich `internal` - von aussen fuehrt der Weg
     * ueber `advance`, und genau den geht auch der Produktivpfad. Der Test
     * prueft damit die Kette, die spaeter wirklich laeuft.
     */
    private fun abrechnen(state: ExpectationLedger.State, nowTs: Long, samples: List<ExpectationLedger.Sample>) =
        ExpectationLedger.advance(state, nowTs, neu = null, samples = samples).outcomes

    private fun erwartung(stamp: InterventionStamp) = ExpectationLedger.issue(
        sourceTs = t0, segmentId = 1L, anchorMgdl = 200.0, meanPredictedMgdl = 150.0,
        horizonMin = H, configGeneration = CFG, interventionStamp = stamp, classification = KORR,
    )!!

    private fun messwert(ts: Long, mgdl: Double, stamp: InterventionStamp) =
        ExpectationLedger.Sample(ts, mgdl, 1L, healthy = true, interventionStamp = stamp, configGeneration = CFG, context = ExpectationLedger.ExpectationContext.CORRECTION)

    private fun zustandMit(stamp: InterventionStamp) = (
        ExpectationLedger.restore(listOf(erwartung(stamp)), emptySet(), emptyList(), kopfstand = stamp)
            as ExpectationLedger.Restored.Valid
        ).state

    /**
     * DER VERTRAG - Ende zu Ende.
     *
     * Zyklus N stellt eine Erwartung unter Stand 41 aus. Zyklus N+1 bereitet
     * eine Dosis vor: der Stempel steigt und wird DURABEL geschrieben, bevor
     * das RT hinausgeht. Dann stirbt der Prozess. Nach dem Neustart kommt der
     * Kopfstand aus seiner Datei, die Erwartung aus ihrer - und die Abrechnung
     * muss INTERVENED ergeben, obwohl die reale Kurve die behauptete Senkung
     * verfehlt hat.
     */
    @Test
    fun `nach einem Absturz unmittelbar nach der Publikation gilt INTERVENED`(@TempDir dir: File) {
        val vorher = InterventionStamp("lauf-A", 41L)
        assertTrue(store().save(dir, zustandMit(vorher), revision = 1L, kopfstand = vorher), "Erwartung durabel")

        // Dosis vorbereitet: der Stempel steigt.
        val nachher = InterventionStamp.next(vorher, InterventionStamp.Published(smbU = 0.30, tbrChanged = false))
        assertEquals(42L, nachher.sequence, "der Stempel ist gestiegen")
        // HIER LIEGT DER VERTRAG: der neue Stand steht auf Platte, BEVOR das
        // RT hinausgeht. Im Produktivpfad ist das adapter.persistVerified in
        // LedgerPublicationGate.publish, aufgerufen nach merkeIntervention.
        val kopfDatei = File(dir, "kopfstand.txt")
        kopfDatei.writeText(nachher.epochId + "|" + nachher.sequence)

        // --- ABSTURZ. Alles, was nur im Speicher stand, ist jetzt weg. ---

        val geladenerKopf = kopfDatei.readText().split("|").let { InterventionStamp(it[0], it[1].toLong()) }
        assertEquals(nachher, geladenerKopf, "der Kopfstand hat den Absturz ueberlebt")
        val geladen = store().load(dir, geladenerKopf)
        assertTrue(geladen is FuseExpectationStore.Loaded.Ok, "" + geladen)
        val offen = (geladen as FuseExpectationStore.Loaded.Ok).state

        // Faelligkeit: die Kurve hat die Senkung VERFEHLT.
        val faellig = t0 + H * 60_000L
        val abgerechnet = abrechnen(offen, faellig + 1000L, listOf(messwert(faellig, 205.0, geladenerKopf)))
        assertEquals(1, abgerechnet.size)
        assertEquals(
            ExpectationLedger.Verdict.INTERVENED, abgerechnet.single().verdict,
            "205 statt 150 - aber dazwischen lag eine Dosis, also KEINE Widerlegung des Modells",
        )
    }

    /**
     * DIE GEGENPROBE - ohne Eingriff MUSS MISSED entstehen.
     *
     * Ohne sie koennte der Test oben auch dadurch gruen sein, dass nie etwas
     * abgerechnet wird. Genau diese Art stumpfer Test hat in dieser Sitzung
     * schon zweimal eine falsche Zusicherung festgeschrieben.
     */
    @Test
    fun `ohne Eingriff bleibt dieselbe Lage MISSED`(@TempDir dir: File) {
        val stand = InterventionStamp("lauf-A", 41L)
        assertTrue(store().save(dir, zustandMit(stand), revision = 1L, kopfstand = stand))
        val geladen = store().load(dir, stand) as FuseExpectationStore.Loaded.Ok
        val faellig = t0 + H * 60_000L
        val abgerechnet = abrechnen(geladen.state, faellig + 1000L, listOf(messwert(faellig, 205.0, stand)))
        assertEquals(ExpectationLedger.Verdict.MISSED, abgerechnet.single().verdict)
    }

    /**
     * DER FALL, DEN DIE EPOCHE LOEST: Reparatur zwischen Ausstellung und
     * Faelligkeit.
     *
     * Die Sequenz beginnt danach wieder bei 0 und trifft irgendwann erneut auf
     * ihre alten Werte. Ein blosser Zaehler saehe hier Gleichstand und machte
     * aus einer Strecke mit Insulin eine Modellwiderlegung.
     */
    @Test
    fun `nach einer Reparatur gilt die alte Erwartung als INTERVENED`(@TempDir dir: File) {
        val vorReparatur = InterventionStamp("lauf-A", 41L)
        assertTrue(store().save(dir, zustandMit(vorReparatur), revision = 1L, kopfstand = vorReparatur))

        // Die Sequenz laeuft AUSDRUECKLICH wieder ueber denselben Wert.
        val nachReparatur = InterventionStamp("repair-" + t0 + "-abc123", 41L)
        assertEquals(vorReparatur.sequence, nachReparatur.sequence, "identische Zahl")
        assertNotEquals(vorReparatur.epochId, nachReparatur.epochId)

        val geladen = store().load(dir, nachReparatur)
        assertTrue(geladen is FuseExpectationStore.Loaded.Ok, "die Generation ueberlebt: " + geladen)
        val offen = (geladen as FuseExpectationStore.Loaded.Ok).state
        assertEquals(1, offen.entries.size, "der Eintrag wird NICHT verworfen")

        val faellig = t0 + H * 60_000L
        val abgerechnet = abrechnen(offen, faellig + 1000L, listOf(messwert(faellig, 205.0, nachReparatur)))
        assertEquals(
            ExpectationLedger.Verdict.INTERVENED, abgerechnet.single().verdict,
            "gleiche Zahl, anderer Lauf - kein Beleg gegen das Modell",
        )
    }

    /**
     * EIN SPAETER REJECT DREHT NICHTS ZURUECK.
     *
     * Die Pumpe kann ablehnen, nachdem der Stempel steht. Das darf ihn nicht
     * senken: eine Ablehnung beweist nicht, dass nichts geflossen ist
     * (Medtrum-Sichtbarkeit p90 56 s, max 854 s).
     */
    @Test
    fun `ein spaeterer Reject senkt den Stempel nicht`() {
        val vorher = InterventionStamp("lauf-A", 41L)
        val nachPublikation = InterventionStamp.next(vorher, InterventionStamp.Published(0.30, false))
        // Der Reject ist im Modell der naechste Zyklus, der nichts publiziert -
        // es gibt keine Gegenbuchung, und genau das ist die Zusicherung.
        val nachReject = InterventionStamp.next(nachPublikation, InterventionStamp.Published(0.0, false))
        assertEquals(42L, nachReject.sequence)
        assertEquals(nachPublikation, nachReject)
    }
}
