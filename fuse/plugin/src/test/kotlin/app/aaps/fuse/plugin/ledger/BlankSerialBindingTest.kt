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
 * DER LEERE SERIAL NACH EINEM PROZESSSTART (Live-Befund 09.08.2026).
 *
 * BEFUND AM GERAET. Von 169 publizierten Vorschlaegen im Trail schlossen 163
 * im unmittelbar folgenden Zyklus. Die sechs uebrigen hatten eine
 * Gemeinsamkeit: JEDER war der erste publizierte Vorschlag seiner Sitzung.
 * Sie hielten ihre volle Haftung, bis die Phantom-Abschreibung sie nach DIA
 * plus Spanne als wirkungslos ausbuchte - obwohl das Insulin geflossen war
 * (der Bolus stand mit passender Menge, Zeit und pumpId in der Datenbank).
 *
 * URSACHE. `VirtualPumpPlugin.serialNumber()` gibt `InstanceId.instanceId`
 * zurueck. Das Feld ist nach jedem Prozessstart `""`, bis die ASYNCHRONE
 * Firebase-Antwort eintrifft. In diesem Fenster pinnt der Vorschlag
 * `Sha("")` als Pumpen-Epoch; der Bolus wird Sekunden spaeter mit dem
 * inzwischen aufgeloesten echten Serial geschrieben. `matchesPinnedEpoch`
 * vergleicht `Sha(echt)` gegen `Sha("")` und findet nie einen Treffer.
 *
 * Belegt im Log desselben Tages: 25 frisch geschriebene Pumpen-Datensaetze
 * mit `pumpSerial=` (leer), und in jeder betroffenen Sitzung liegt der erste
 * Datensatz MIT gefuelltem Serial wenige Sekunden NACH dem haengenden
 * Vorschlag.
 *
 * WARUM DAS KEINE PRUEFUNG AM WERT FINDEN KONNTE: `Sha.of("")` ist ein
 * voellig normal aussehender 64-Zeichen-Hash. Es gab keinen Zustand
 * "unbekannt" - nur "der Hash des leeren Strings". Genau dasselbe Muster wie
 * bei der erfundenen IOB-Null.
 */
class BlankSerialBindingTest {

    private val t0 = 1_700_000_000_000L
    private val echterSerial = "eWAcAOrQTemFNR7M5j7F_8"

    private fun smb(ts: Long, amount: Double, pumpId: Long, serial: String?) = BS(
        timestamp = ts,
        amount = amount,
        type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.MEDTRUM_NANO, pumpSerial = serial, pumpId = pumpId),
    )

    private fun adapter(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    @Test
    fun `ein leerer Serial ist keine Aussage - nicht der Hash des leeren Strings`() {
        assertNull(LedgerFacts.serialHashOf(""))
        assertNull(LedgerFacts.serialHashOf("   "))
        assertNull(LedgerFacts.serialHashOf(null))
        assertEquals(Sha.of(echterSerial.lowercase()), LedgerFacts.serialHashOf(echterSerial))
        // Die Falle in einer Zeile: vor dem Fix war DAS hier der gepinnte Wert.
        assertTrue(Sha.of("").length == 64)
    }

    /**
     * DIE SCHREIBWEISE - zweite Auspraegung derselben Fehlerklasse
     * (Phase-A-Kartierung 09.08., an der Produktivpumpe gemessen).
     *
     * Derselbe Zahlenwert erreicht die beiden Vergleichsseiten unterschiedlich
     * formatiert: `MedtrumPlugin.serialNumber()` haengt `.uppercase()` an
     * (`MedtrumPlugin.kt:406`), waehrend der Bolus-Datensatz mit
     * `pumpSN.toString(radix = 16)` geschrieben wird (`MedtrumService.kt:383`).
     * Tonis realer Serial `9C1DE26D` traegt drei betroffene Hexziffern.
     *
     * Die Werte hier sind die ECHTEN aus dem Produktivsystem, damit der Test
     * den gemessenen Fall prueft und nicht einen ausgedachten. Der Test haengt
     * bewusst NICHT vom Medtrum-Modul ab - fuse/plugin darf kein Pumpenmodul
     * importieren.
     */
    @Test
    fun `dieselbe Seriennummer in zwei Schreibweisen ist dieselbe Identitaet`() {
        val wieDerTreiberSieMeldet = "9C1DE26D"   // MedtrumPlugin.serialNumber()
        val wieSieInDerZeileSteht = "9c1de26d"    // BS.ids.pumpSerial

        assertEquals(
            LedgerFacts.serialHashOf(wieDerTreiberSieMeldet),
            LedgerFacts.serialHashOf(wieSieInDerZeileSteht),
        ) { "Gross- und Kleinschreibung derselben Hex-Seriennummer muessen denselben Hash ergeben" }

        // Und die Falle in einer Zeile: ohne Normalisierung sind es zwei
        // voellig gueltig aussehende, aber verschiedene Hashes.
        assertNotEquals(Sha.of(wieDerTreiberSieMeldet), Sha.of(wieSieInDerZeileSteht))
    }

    /** Umschliessende Leerzeichen sind ebenfalls keine Identitaetsaussage. */
    @Test
    fun `fuehrende und nachlaufende Leerzeichen aendern die Identitaet nicht`() {
        assertEquals(LedgerFacts.serialHashOf(echterSerial), LedgerFacts.serialHashOf("  $echterSerial  "))
    }

    /** GEGENPROBE: die Normalisierung darf nur die Schreibweise einebnen,
     *  nicht zwei verschiedene Seriennummern verschmelzen. */
    @Test
    fun `verschiedene Seriennummern bleiben verschieden`() {
        assertNotEquals(LedgerFacts.serialHashOf("9c1de26d"), LedgerFacts.serialHashOf("9c1de26e"))
        assertNotEquals(LedgerFacts.serialHashOf("9c1de26d"), LedgerFacts.serialHashOf("9c1de26d0"))
    }

    /** DER LIVE-FALL AN DER REALPUMPE: gepinnt in der Schreibweise des
     *  Treibers, gebucht in der Schreibweise der Datenbank - muss binden. */
    @Test
    fun `Pinnung GROSS und Datensatz klein binden zusammen`(@TempDir dir: File) {
        val a = adapter(dir)
        a.onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L, bolusStepU = 0.05,
            pumpTypeName = PumpType.MEDTRUM_NANO.name,
            pumpSerialHash = LedgerFacts.serialHashOf("9C1DE26D"),
        )
        val b = smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = "9c1de26d")
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)

        assertEquals(AccountingState.IOB_ACCOUNTED, a.state.entries.getValue("p1").accounting) {
            "an einer echten Medtrum haette hier vor dem Fix NIE eine Zeile gebunden"
        }
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)
    }

    /** DER LIVE-FALL. Publikation im leeren Fenster, Bolus danach mit echtem
     *  Serial - die Zeile muss binden und schliessen. */
    @Test
    fun `im leeren Fenster publiziert - der Bolus mit echtem Serial bindet trotzdem`(@TempDir dir: File) {
        val a = adapter(dir)
        a.onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L, bolusStepU = 0.05,
            pumpTypeName = PumpType.MEDTRUM_NANO.name,
            // Firebase hat noch nicht geantwortet.
            pumpSerialHash = LedgerFacts.serialHashOf(""),
        )
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)

        // 1,2 s spaeter: der Bolus steht in der Datenbank - jetzt mit Serial.
        val b = smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = echterSerial)
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)

        val e = a.state.entries.getValue("p1")
        assertEquals(AccountingState.IOB_ACCOUNTED, e.accounting) {
            "die Zeile haette binden muessen - der leere Serial darf keine fremde Identitaet vortaeuschen"
        }
        assertTrue(e.closed)
        assertEquals(0.0, a.view().transportCommitmentU, 1e-12)
        assertFalse(a.view().hold)
    }

    /** DER ZWEITE LIVE-FALL: Pinnung UND Datensatz liegen im leeren Fenster.
     *  Er muss ohne Umweg durchlaufen - und der Abgleich darf danach keinen
     *  `deviceConflict` gegen den EIGENEN Datensatz erzeugen, denn der waere
     *  fail-closed und wuerde die Aktuation anhalten. */
    @Test
    fun `Pinnung und Datensatz beide ohne Serial - bindet und erzeugt keinen Widerspruch`(@TempDir dir: File) {
        val a = adapter(dir)
        a.onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L, bolusStepU = 0.05,
            pumpTypeName = PumpType.MEDTRUM_NANO.name, pumpSerialHash = LedgerFacts.serialHashOf(""),
        )
        val b = smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = "")
        a.bindIdentities(listOf(b))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(b)), LedgerFacts.snapshotHash(listOf(b)), t0 + 60_000L)

        assertEquals(AccountingState.IOB_ACCOUNTED, a.state.entries.getValue("p1").accounting)
        assertFalse(a.view().hold) { "ein unbekannter Serial ist kein Geraetewechsel und darf nicht sperren" }
    }

    /**
     * GEGENPROBE ZUR RICHTUNG: ein Datensatz OHNE Herkunft schliesst eine an
     * eine Herkunft gebundene Zeile weiterhin NICHT (dokumentierte Regel in
     * [FuseLedgerAdapter], Fix 3 / Re-Audit 6.3).
     *
     * Diese Richtung ist im Livebetrieb ohnehin unerreichbar: `InstanceId`
     * geht nur von "" zum echten Wert, nie zurueck - eine Pinnung MIT Serial
     * kann also keinen Datensatz OHNE Serial nach sich ziehen. Der Test haelt
     * die Asymmetrie trotzdem fest, damit der Fix oben nicht spaeter zu
     * "Serial egal" verallgemeinert wird.
     */
    @Test
    fun `ein Datensatz ohne Herkunft schliesst eine gepinnte Zeile nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        a.onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L, bolusStepU = 0.05,
            pumpTypeName = PumpType.MEDTRUM_NANO.name, pumpSerialHash = Sha.of(echterSerial),
        )
        val b = smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = "")
        a.bindIdentities(listOf(b))

        assertNull(a.state.entries.getValue("p1").identity)
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)
    }

    /** GEGENPROBE: die Epoch-Pinnung bleibt scharf. Ein Datensatz einer
     *  tatsaechlich ANDEREN Pumpe darf die Zeile weiterhin nicht schliessen -
     *  der Fix lockert nur den UNBEKANNTEN Fall, nicht den widersprechenden. */
    @Test
    fun `ein echter Geraetewechsel bindet weiterhin nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        a.onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L, bolusStepU = 0.05,
            pumpTypeName = PumpType.MEDTRUM_NANO.name, pumpSerialHash = Sha.of(echterSerial),
        )
        val fremd = smb(t0 + 1_200L, 0.15, pumpId = 4711L, serial = "eine-andere-pumpe")
        a.bindIdentities(listOf(fremd))
        a.onCycleSnapshot(listOf(LedgerFacts.fact(fremd)), LedgerFacts.snapshotHash(listOf(fremd)), t0 + 60_000L)

        assertEquals(AccountingState.NOT_ACCOUNTED, a.state.entries.getValue("p1").accounting)
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)
    }

    /** Und der Typ bleibt die tragende Pinnung, solange der Serial fehlt. */
    @Test
    fun `ohne Serial pinnt weiterhin der Pumpentyp`(@TempDir dir: File) {
        val a = adapter(dir)
        a.onPublished(
            proposalId = "p1", unitsU = 0.15, decisionTs = t0, latestBolusTs = t0 - 60_000L, bolusStepU = 0.05,
            pumpTypeName = PumpType.MEDTRUM_NANO.name, pumpSerialHash = LedgerFacts.serialHashOf(""),
        )
        val andererTyp = BS(
            timestamp = t0 + 1_200L, amount = 0.15, type = BS.Type.SMB,
            ids = IDs(pumpType = PumpType.DANA_RS, pumpSerial = echterSerial, pumpId = 4711L),
        )
        a.bindIdentities(listOf(andererTyp))
        assertEquals(0.15, a.view().transportCommitmentU, 1e-12)
        assertNull(a.state.entries.getValue("p1").identity)
    }
}

