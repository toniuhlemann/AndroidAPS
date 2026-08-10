package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.ledger.AccountingState
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * WANN EIN PUMPEN-SERIAL KEINE IDENTITAET IST - zwei belegte Faelle.
 *
 * Beide sitzen im selben Vergleich (`matchesPinnedEpoch` haelt `Sha(pin)` gegen
 * `Sha(fakt)`), und beide waren fuer eine Pruefung AM WERT unsichtbar, weil auf
 * beiden Seiten ein gueltiger 64-Zeichen-Hash steht. Dasselbe Muster wie die
 * erfundene IOB-Null.
 *
 * FALL 1 - LEER NACH PROZESSSTART (Testrig, 09.08.2026).
 * Von 169 publizierten Vorschlaegen im Trail schlossen 163 im unmittelbar
 * folgenden Zyklus. Die sechs uebrigen hatten eine Gemeinsamkeit: jeder war der
 * ERSTE publizierte Vorschlag seiner Sitzung. Ursache:
 * `VirtualPumpPlugin.serialNumber()` gibt `InstanceId.instanceId` zurueck, und
 * das Feld ist nach jedem Prozessstart `""`, bis die ASYNCHRONE
 * Firebase-Antwort eintrifft. In diesem Fenster wurde `Sha("")` als Epoch
 * gepinnt; der Bolus wurde Sekunden spaeter mit dem aufgeloesten Serial
 * gebucht. Im Log desselben Tages: 25 frisch geschriebene Pumpen-Datensaetze
 * mit leerem `pumpSerial`.
 *
 * FALL 2 - DIE SCHREIBWEISE (Medtrum, am Produktivsystem gemessen).
 * Derselbe Long wird an zwei Stellen unterschiedlich formatiert:
 * `MedtrumPlugin.serialNumber()` haengt `.uppercase()` an (`:406`), waehrend
 * der Bolus-Datensatz mit `pumpSN.toString(radix = 16)` geschrieben wird
 * (`MedtrumService.kt:383`). Traegt die Seriennummer Hexziffern a-f, binden die
 * beiden Seiten nie - dauerhaft, nicht nur in einem Startfenster.
 *
 * SYNTHETISCHE TESTWERTE, mit Absicht. Der reale Serial wurde gemessen, aber er
 * gehoert nicht in den Quelltext: der Test prueft eine EIGENSCHAFT (Hex mit
 * Buchstaben, zwei Schreibweisen), und die pruefen synthetische Werte genauso
 * gut - ohne den Test an ein bestimmtes Geraet zu binden, das morgen
 * ausgetauscht sein kann.
 */
class BlankSerialBindingTest {

    private val t0 = 1_700_000_000_000L

    /** Synthetisch. Hex MIT Buchstaben - genau die Eigenschaft, an der Fall 2 haengt. */
    private val serialGross = "A1B2C3D4"
    private val serialKlein = "a1b2c3d4"

    private val medtrum = PumpType.MEDTRUM_NANO.name

    private fun smb(ts: Long, amount: Double, pumpId: Long, serial: String?, type: PumpType = PumpType.MEDTRUM_NANO) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = type, pumpSerial = serial, pumpId = pumpId),
    )

    /**
     * B3: der Adapter bekommt eine BEKANNTE Patch-Epoche.
     *
     * Seit B3 bindet eine MEDTRUM-Zeile nur innerhalb ihrer Patch-Epoche -
     * Type und Serial reichen dort nicht, weil die Seriennummer der Basis
     * gehoert und den Wechsel ueberlebt. Ohne diesen Aufruf waere die Epoche
     * unbekannt und KEINE dieser Zeilen wuerde binden; die Tests hier
     * untersuchen aber die Serial-Faltung, nicht die Epoche.
     */
    private fun adapter(dir: File) = FuseLedgerAdapter().also {
        it.loadOnce(dir, "epoch-a", t0)
        it.observeBindingContext(LedgerPumpBindingContext.emulation(t0 - 3600_000L))
    }

    private fun FuseLedgerAdapter.publish(serialHash: String?, typeName: String? = medtrum) =
        onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L,
            bolusStepU = 0.05, pumpTypeName = typeName, pumpSerialHash = serialHash,
        )

    private fun FuseLedgerAdapter.reconcile(b: BS) {
        bindIdentities(listOf(b))
        onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)
    }

    // ---- Fall 1: leer ist keine Aussage -----------------------------------

    @Test
    fun `ein leerer Serial ist keine Aussage - nicht der Hash des leeren Strings`() {
        assertNull(LedgerFacts.serialHashOf("", medtrum))
        assertNull(LedgerFacts.serialHashOf("   ", medtrum))
        assertNull(LedgerFacts.serialHashOf(null, medtrum))
        // Die Falle in einer Zeile: vor dem Fix war DAS hier der gepinnte Wert.
        assertTrue(Sha.of("").length == 64)
    }

    @Test
    fun `im leeren Fenster publiziert - der Bolus mit echtem Serial bindet trotzdem`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publish(LedgerFacts.serialHashOf("", medtrum))   // Firebase hat noch nicht geantwortet
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)

        a.reconcile(smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = serialKlein))

        val e = a.state.entries.getValue("p1")
        assertEquals(AccountingState.IOB_ACCOUNTED, e.accounting) {
            "die Zeile haette binden muessen - der leere Serial darf keine fremde Identitaet vortaeuschen"
        }
        assertTrue(e.closed)
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)
    }

    @Test
    fun `Pinnung und Datensatz beide ohne Serial - bindet und erzeugt keinen Widerspruch`(@TempDir dir: File) {
        // Der Abgleich darf danach keinen `deviceConflict` gegen den EIGENEN
        // Datensatz erzeugen - der waere fail-closed und wuerde die Aktuation
        // anhalten.
        val a = adapter(dir)
        a.publish(LedgerFacts.serialHashOf("", medtrum))
        a.reconcile(smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = ""))

        assertEquals(AccountingState.IOB_ACCOUNTED, a.state.entries.getValue("p1").accounting)
        assertFalse(a.view().hold) { "ein unbekannter Serial ist kein Geraetewechsel und darf nicht sperren" }
    }

    /**
     * GEGENPROBE ZUR RICHTUNG: ein Datensatz OHNE Herkunft schliesst eine an
     * eine Herkunft gebundene Zeile weiterhin NICHT (dokumentierte Regel in
     * [FuseLedgerAdapter], Fix 3 / Re-Audit 6.3).
     *
     * Diese Richtung ist im Livebetrieb unerreichbar - `InstanceId` geht nur
     * von "" zum echten Wert, nie zurueck. Der Test haelt die Asymmetrie
     * trotzdem fest, damit der Fix oben nicht spaeter zu "Serial egal"
     * verallgemeinert wird.
     */
    @Test
    fun `ein Datensatz ohne Herkunft schliesst eine gepinnte Zeile nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publish(LedgerFacts.serialHashOf(serialGross, medtrum))
        a.bindIdentities(listOf(smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = "")))

        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)
    }

    // ---- Fall 2: die Schreibweise -----------------------------------------

    @Test
    fun `bei Medtrum ist die Schreibweise keine Identitaetsaussage`() {
        assertEquals(
            LedgerFacts.serialHashOf(serialGross, medtrum),   // wie serialNumber() meldet
            LedgerFacts.serialHashOf(serialKlein, medtrum),   // wie es in der BS-Zeile steht
        ) { "Gross- und Kleinschreibung derselben Hex-Seriennummer muessen denselben Hash ergeben" }

        // Die Falle in einer Zeile: ohne Faltung sind es zwei voellig gueltig
        // aussehende, aber verschiedene Hashes.
        assertNotEquals(Sha.of(serialGross), Sha.of(serialKlein))
    }

    @Test
    fun `Pinnung GROSS und Datensatz klein binden zusammen`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publish(LedgerFacts.serialHashOf(serialGross, medtrum))
        a.reconcile(smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = serialKlein))

        assertEquals(AccountingState.IOB_ACCOUNTED, a.state.entries.getValue("p1").accounting) {
            "an einer echten Medtrum haette hier vor dem Fix NIE eine Zeile gebunden"
        }
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)
    }

    /**
     * DIE WICHTIGSTE GEGENPROBE (Codex-Gegenpruefung F7).
     *
     * Die Faltung ist mit einer MEDTRUM-Eigenschaft begruendet - ein Treiber,
     * der denselben Long zweimal verschieden formatiert. Fuer andere Pumpen
     * ist dieser Identitaetsvertrag NICHT belegt. Sie mitzufalten waere eine
     * unbelegte Verallgemeinerung und koennte zwei Geraete, die sich nur in
     * der Schreibweise unterscheiden, auf denselben Hash werfen - derselbe
     * Fehler in neuer Richtung.
     */
    @Test
    fun `bei anderen Pumpentypen bleibt die Schreibweise unterscheidend`() {
        val fremd = PumpType.DANA_RS.name
        assertNotEquals(
            LedgerFacts.serialHashOf(serialGross, fremd),
            LedgerFacts.serialHashOf(serialKlein, fremd),
        ) { "ohne Beleg fuer diesen Pumpentyp darf die Schreibweise nicht eingeebnet werden" }

        // Auch ohne bekannten Typ wird nicht gefaltet - unbekannt ist kein Freibrief.
        assertNotEquals(
            LedgerFacts.serialHashOf(serialGross, null),
            LedgerFacts.serialHashOf(serialKlein, null),
        )
    }

    @Test
    fun `die Faltungsliste trifft jeden Medtrum-Typ und keinen fremden`() {
        val gefaltet = LedgerFacts.CASE_FOLDING_PUMP_TYPES
        val ausDemEnum = PumpType.entries.map { it.name }
        assertTrue(gefaltet.isNotEmpty()) { "die Ableitung darf nicht leer laufen" }
        assertEquals(
            ausDemEnum.filter { it.startsWith("MEDTRUM") }.toSet(), gefaltet,
        ) { "jeder Medtrum-Wert muss gefaltet werden - eine stille Luecke waere genau der gesuchte Fehler" }
        assertTrue(gefaltet.none { !it.startsWith("MEDTRUM") }) { "kein fremder Typ darf mitgefaltet werden" }
    }

    // ---- gemeinsame Gegenproben -------------------------------------------

    @Test
    fun `fuehrende und nachlaufende Leerzeichen aendern die Identitaet nicht`() {
        assertEquals(
            LedgerFacts.serialHashOf(serialGross, medtrum),
            LedgerFacts.serialHashOf("  $serialGross  ", medtrum),
        )
    }

    @Test
    fun `verschiedene Seriennummern bleiben verschieden`() {
        assertNotEquals(LedgerFacts.serialHashOf(serialKlein, medtrum), LedgerFacts.serialHashOf("a1b2c3d5", medtrum))
        assertNotEquals(LedgerFacts.serialHashOf(serialKlein, medtrum), LedgerFacts.serialHashOf("a1b2c3d40", medtrum))
    }

    @Test
    fun `ein echter Geraetewechsel bindet weiterhin nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publish(LedgerFacts.serialHashOf(serialGross, medtrum))
        a.reconcile(smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = "beefbeef"))

        assertEquals(AccountingState.NOT_ACCOUNTED, a.state.entries.getValue("p1").accounting)
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)
    }

    @Test
    fun `ohne Serial pinnt weiterhin der Pumpentyp`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publish(LedgerFacts.serialHashOf("", medtrum))
        a.bindIdentities(listOf(smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = serialKlein, type = PumpType.DANA_RS)))

        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)
        assertNull(a.state.entries.getValue("p1").identity)
    }
}
