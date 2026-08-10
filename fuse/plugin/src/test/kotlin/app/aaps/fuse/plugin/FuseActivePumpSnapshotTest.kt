package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.FusePatchEpoch
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.LedgerPublicationGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DER PUMPENSNAPSHOT und die IDENTITAETSSPERRE.
 *
 * Zwei Auditbefunde in einem: die Pumpe wurde dreimal je Zyklus gelesen
 * (Plugin, Riegel, Bolusschritt), und ein unbekannter Serial lebte als
 * WILDCARD-Pin weiter, der jeden typgleichen Bolus band.
 */
class FuseActivePumpSnapshotTest {

    private val t0 = 1_700_000_000_000L
    private val medtrum = PumpType.MEDTRUM_NANO
    private val serial = "9c1de26d"

    private fun echt(serialHash: String? = LedgerFacts.serialHashOf(serial, medtrum.name)) =
        FuseActivePump(
            pumpTypeName = medtrum.name, virtualPump = false,
            gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED_REAL_MEDTRUM, medtrum.name),
            tempBasalCapable = true, bolusStepU = 0.05, basalStepUPerH = 0.05,
            serialHash = serialHash,
        )

    private fun emuliert(serialHash: String? = LedgerFacts.serialHashOf("vs", medtrum.name)) =
        FuseActivePump(
            pumpTypeName = medtrum.name, virtualPump = true,
            gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
            tempBasalCapable = true, bolusStepU = 0.05, basalStepUPerH = 0.05,
            serialHash = serialHash,
        )

    // ---- Die Identitaetssperre -------------------------------------------

    /**
     * ECHTE PUMPE OHNE SERIAL: KEIN POSITIVER SMB.
     *
     * Ohne Identitaet entstuende ein Pin, den `matchesPinnedEpoch` als
     * Wildcard behandelt - die Zeile band dann jeden typgleichen Bolus. An
     * einer echten Pumpe ist das ein Freibrief: eine Haftung koennte ueber
     * einen fremden Fakt ausgebucht werden.
     */
    @Test
    fun `eine echte Pumpe ohne Serial sperrt den positiven SMB`() {
        assertTrue(echt(serialHash = null).realPumpIdentityUnknown)
        assertFalse(echt().realPumpIdentityUnknown) { "mit Serial ist alles in Ordnung" }
    }

    /** Die Emulation bleibt ausgenommen - dort ist der leere Serial direkt
     *  nach einem Prozessstart der Normalfall (InstanceId laedt asynchron). */
    @Test
    fun `die Emulation ist von der Identitaetssperre ausgenommen`() {
        assertFalse(emuliert(serialHash = null).realPumpIdentityUnknown) {
            "sonst haette die Sperre den ganzen Entwicklungspfad stillgelegt"
        }
    }

    /**
     * DIE SPERRE TRIFFT NUR DEN BOLUS - die Schutz-TBR bleibt.
     *
     * Ein Provenienzproblem des BOLUS darf keine Schutz-Null verhindern:
     * dieselbe Trennung wie bei der unbekannten Epoche. Und sie bekommt einen
     * EIGENEN Grund, damit im Trail unterscheidbar bleibt, ob die Identitaet
     * oder die Epoche fehlt.
     */
    @Test
    fun `die Identitaetssperre entfernt den SMB mit eigenem Grund`() {
        val c = LedgerPublicationGate.commitmentOf(
            units = 0.20, treatmentViewPresent = true, proposalId = "p1",
            realPumpIdentityUnknown = true,
        )
        assertTrue(c is LedgerPublicationGate.Commitment.None)
        assertEquals(
            LedgerPublicationGate.REASON_REAL_PUMP_IDENTITY_UNKNOWN,
            (c as LedgerPublicationGate.Commitment.None).reason,
        )
    }

    /** Fehlen Identitaet UND Epoche, nennt der Trail die IDENTITAET - ohne sie
     *  ist die Epochenfrage gar nicht zu stellen. */
    @Test
    fun `bei beiden Maengeln steht die Identitaet vorn`() {
        val c = LedgerPublicationGate.commitmentOf(
            units = 0.20, treatmentViewPresent = true, proposalId = "p1",
            realPumpEpochUnknown = true, realPumpIdentityUnknown = true,
        )
        assertEquals(
            LedgerPublicationGate.REASON_REAL_PUMP_IDENTITY_UNKNOWN,
            (c as LedgerPublicationGate.Commitment.None).reason,
        )
    }

    // ---- Der Wildcard-Riegel ---------------------------------------------

    private fun bolus(ts: Long, u: Double, s: String?) = BS(
        timestamp = ts, amount = u, type = BS.Type.SMB,
        ids = IDs(pumpType = medtrum, pumpSerial = s, pumpId = 4711L),
    )

    /**
     * EIN PIN OHNE SERIAL BINDET AN EINER ECHTEN PUMPE NICHT.
     *
     * Neue solche Zeilen entstehen dort gar nicht mehr (die Publikationssperre
     * oben) - ALTE aber schon, etwa aus der Emulationszeit desselben Geraets.
     * Deshalb entscheidet die JETZT aktive Pumpe.
     */
    @Test
    fun `ein Pin ohne Serial ist an der echten Pumpe kein Wildcard`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "s-a", t0, emuliert())
            it.observePatchEpoch(null)
        }
        // Gepinnt OHNE Serial - so entsteht die Zeile im leeren InstanceId-Fenster.
        a.onPublished("p1", 0.30, t0, 0L, 0.05, medtrum.name, null, virtualPump = true)

        // Jetzt laeuft eine ECHTE Pumpe.
        a.observeRealPump(true)
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30, serial)))

        assertNull(a.state.entries.getValue("p1").identity) {
            "ohne Identitaet darf die Zeile keinen echten Bolus binden"
        }
        assertEquals(0.30, a.view().transportCommitmentU, 1e-9) {
            "und sie haelt konservativ ihre volle Haftung"
        }
    }

    /** An der Emulation bindet derselbe Pin weiter - sonst hielte jede Zeile
     *  aus dem Prozessstartfenster ihre Haftung bis zur Abschreibung. */
    @Test
    fun `derselbe Pin bindet an der Emulation weiter`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "s-a", t0, emuliert())
            it.observePatchEpoch(null)
        }
        a.onPublished("p1", 0.30, t0, 0L, 0.05, medtrum.name, null, virtualPump = true)
        a.observeRealPump(false)
        a.bindIdentities(listOf(bolus(t0 + 5_000L, 0.30, "vs")))

        assertNotNull(a.state.entries.getValue("p1").identity)
    }

    // ---- Restart und Pumpenwechsel ---------------------------------------

    /**
     * PERSISTENZ-GEGENPROBE: der Riegel haengt an der AKTIVEN Pumpe, nicht an
     * einem gespeicherten Zustand - er wirkt also auch nach einem Neustart.
     */
    @Test
    fun `der Wildcard-Riegel wirkt auch nach einem Neustart`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "s-a", t0, emuliert())
            it.observePatchEpoch(null)
        }
        a.onPublished("p1", 0.30, t0, 0L, 0.05, medtrum.name, null, virtualPump = true)
        assertTrue(a.persistVerified(dir))

        // Neustart, und jetzt haengt die echte Pumpe dran.
        val b = FuseLedgerAdapter().also {
            it.loadOnce(dir, "s-b", t0 + 60_000L, echt())
            it.observePatchEpoch(t0 - 3600_000L)
            it.observeRealPump(true)
        }
        b.bindIdentities(listOf(bolus(t0 + 120_000L, 0.30, serial)))
        assertNull(b.state.entries.getValue("p1").identity) {
            "der Riegel darf einen Prozesswechsel nicht ueberleben muessen - er wirkt aus der Lage"
        }
    }

    /**
     * PUMPENWECHSEL-GEGENPROBE: der Snapshot ist unveraenderlich, also kann
     * ein Wechsel MITTEN im Zyklus die Merkmale nicht mehr mischen.
     *
     * Genau das war der Befund: Riegel und Pinnung stammten aus zwei Lesungen.
     * Sah der Riegel die echte Medtrum und die Pinnung noch die Emulation,
     * entstand eine Zeile mit `virtualPump=true`, die ohne Patchpruefung band.
     */
    @Test
    fun `ein Snapshot mischt Riegel und Pinnung nicht`() {
        val s = echt()
        // Alle Merkmale stammen aus derselben Instanz - es gibt keinen Weg,
        // eines davon nachtraeglich zu aendern, ausser durch copy().
        assertEquals(FusePumpGate.Verdict.ALLOWED_REAL_MEDTRUM, s.gate.verdict)
        assertEquals(false, s.virtualPump)
        assertEquals(true, s.realPump)
        assertEquals(true, s.realPatchPump)

        // Und die Emulation traegt konsistent das Gegenteil.
        val e = emuliert()
        assertEquals(FusePumpGate.Verdict.ALLOWED, e.gate.verdict)
        assertEquals(false, e.realPump)
        assertEquals(false, e.realPatchPump)
    }

    /** Die Epoche wird ANGEHAENGT, nicht danebengelegt - danach beschreibt
     *  genau eine Groesse den Pumpenzustand des Zyklus. */
    @Test
    fun `die Epoche gehoert in den Snapshot`() {
        val ohne = echt()
        assertTrue(ohne.realPumpEpochUnknown) { "ohne Epoche sperrt die echte Patchpumpe" }

        val mit = ohne.withPatchEpoch(
            FusePatchEpoch.Result(t0, FusePatchEpoch.Reason.MATCHING_PUMP_IDENTITY)
        )
        assertFalse(mit.realPumpEpochUnknown)
        assertEquals(t0, mit.patchEpoch?.epochTs)
        assertNull(ohne.patchEpoch) { "der urspruengliche Snapshot bleibt unveraendert" }
    }

    /**
     * EIN ZICKENDER TREIBER DEGRADIERT ZU "UNBEKANNT" - UND ZWAR ALS NaN.
     *
     * `0.0` waere hier GEFAEHRLICHER als jede Sperre: `floor(x/0)*0` ergibt
     * NaN, und `NaN <= 0.0` ist false - die Pruefung im Runner
     * (`!bolusStep.isFinite() || bolusStep <= 0.0`) fiele durch statt zu
     * greifen, und der Zyklus rechnete mit einem Schritt von null weiter.
     *
     * Diesen Test gibt es, weil die Mutationsprobe ihn eingefordert hat: sie
     * blieb gruen, als der Rueckfallwert in `of()` auf 0.0 gesetzt wurde -
     * mein erster Test prueft nur `UNKNOWN`, also einen anderen Pfad.
     */
    @Test
    fun `ein werfender Treiber liefert NaN, nicht null`() {
        val zickig = org.mockito.Mockito.mock(app.aaps.core.interfaces.pump.Pump::class.java)
        org.mockito.kotlin.whenever(zickig.pumpDescription).thenThrow(IllegalStateException("keine Verbindung"))
        org.mockito.kotlin.whenever(zickig.model()).thenThrow(IllegalStateException("keine Verbindung"))

        val s = FuseActivePump.of(zickig)

        assertFalse(s.bolusStepU.isFinite()) { "0.0 wuerde die Pruefung im Runner UNTERLAUFEN, nicht ausloesen" }
        assertFalse(s.basalStepUPerH.isFinite())
        assertNull(s.pumpTypeName) { "unbekannt, nicht geraten" }
        assertFalse(s.tempBasalCapable) { "im Zweifel keine Faehigkeit annehmen" }
        assertFalse(s.gate.allowed) { "und der Riegel bleibt zu" }
    }

    /** Unlesbare Pumpe: alles unbekannt, Riegel zu - und die Identitaetssperre
     *  greift NICHT, weil schon der Riegel blockiert. Eine zweite Sperre mit
     *  eigenem Grund wuerde den Trail nur verschleiern. */
    @Test
    fun `eine unlesbare Pumpe ist unbekannt und gesperrt`() {
        val u = FuseActivePump.UNKNOWN
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNKNOWN_PUMP, u.gate.verdict)
        assertFalse(u.gate.allowed)
        assertFalse(u.realPump)
        assertNull(u.realPatchPump)
        assertFalse(u.realPumpIdentityUnknown) { "der Riegel blockiert bereits" }
        assertFalse(u.bolusStepU.isFinite()) { "NaN, nicht 0.0 - sonst faellt der Zyklus durch statt zu sperren" }
    }
}
