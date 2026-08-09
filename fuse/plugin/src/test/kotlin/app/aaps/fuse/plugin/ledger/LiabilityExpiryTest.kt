package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * B1 (Gegenproben-Audit 09.08.2026): DIE WIRKFRIST HAENGT NICHT AM HINSEHEN.
 *
 * Der Fehler war fein und toedlich. `prune` bemass das Alter einer Zeile am
 * letzten Abgleich - richtig gedacht, denn ein spaeter Fakt soll die Frist
 * verlaengern. Der Reducer schrieb dieses Lebenszeichen aber AUCH, wenn der
 * Abgleich die ABWESENHEIT des Fakts feststellte. Damit hielt genau das die
 * Zeile am Leben, was sie verfallen lassen musste.
 *
 * Ab dem zweiten Zyklus lief der Fall durch den stillen `seen`-Zweig,
 * erneuerte jede Minute das Lebenszeichen, `expiredBeyondAction` fiel nie -
 * und weil `prune` nur FEHLERFREIE Zeilen entfernt, blieb die fehlertragende
 * Zeile fuer immer stehen. Ein Zustand, den FUSE nur durch Loeschen einer
 * Datei verlassen konnte, bei minuetlich zwischen 0 und voll springender
 * Transportmenge.
 *
 * Die Regel dahinter ist allgemein: **eine Feststellung ueber einen Fakt ist
 * nicht der Fakt.** Ein Abgleich, der nichts findet, hat nichts gefunden.
 */
class LiabilityExpiryTest {

    private val t0 = 1_700_000_000_000L
    private val dia = 9.0

    /** DIA 9 h + 2 h Spanne - derselbe Schnitt wie in `prune`. */
    private val cutoffMs = (dia * 3600_000L).toLong() + 2L * 3600_000L

    private val typ = PumpType.GENERIC_AAPS.name
    private val serial = Sha.of("vs")

    private fun adapter(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    private fun FuseLedgerAdapter.publish(id: String, u: Double, ts: Long) =
        onPublished(id, u, ts, 0L, 0.05, typ, serial)

    /** Eine Vollsicht OHNE jeden Fakt - der Abgleich bestaetigt die Abwesenheit. */
    private fun FuseLedgerAdapter.leereSicht(at: Long, hash: String) =
        onCycleSnapshot(emptyList(), hash, at)

    /** Eine Vollsicht MIT positivem Fakt fuer diese Identitaet. */
    private fun FuseLedgerAdapter.sichtMitFakt(at: Long, hash: String, tempId: Long, u: Double) =
        onCycleSnapshot(listOf(AccountedTreatment(tempId, null, u, typ, serial)), hash, at)

    // ---- Toni-Vorgabe 1: nie gebundener Vorschlag laeuft aus --------------

    @Test
    fun `ein nie gebundener Vorschlag laeuft nach DIA plus 2h aus`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publish("nie-gebunden", 0.20, t0 - cutoffMs - 60_000L)

        a.prune(t0, dia)

        assertEquals(0.0, a.view().transportCommitmentU, 1e-9) {
            "ohne jede Bindung kann die Menge nach DIA+Spanne nicht mehr wirken"
        }
    }

    // ---- Toni-Vorgabe 2: DER KERN ----------------------------------------

    /**
     * Der Fall, der vorher terminal war: eine gebundene Zeile, deren Fakt aus
     * der Sicht verschwindet, wird jede Minute erneut gegen eine leere Sicht
     * abgeglichen - und darf ihren Ablauf dadurch NICHT vor sich herschieben.
     */
    @Test
    fun `wiederholte leere Vollsichten verschieben den Ablauf nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        val alt = t0 - cutoffMs - 3600_000L
        a.publish("gebunden", 0.20, alt)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.20, ts = alt)))

        // 30 Zyklen im Minutentakt, jeder bestaetigt: der Fakt ist weg.
        // Vorher erneuerte JEDER davon das Lebenszeichen.
        for (i in 1..30) a.leereSicht(t0 - (30 - i) * 60_000L, "leer-$i")

        a.prune(t0, dia)

        assertEquals(0.0, a.view().transportCommitmentU, 1e-9) {
            "die bestaetigte Abwesenheit ist kein Lebenszeichen - die Frist laeuft ab der Entscheidung"
        }
        assertFalse(a.view().hold) { "eine abgelaufene Zeile darf den Regler nicht stilllegen" }
    }

    /** Und dasselbe eine Ebene tiefer: das Auditfeld wird gefuehrt, das
     *  Fristfeld nicht. Zwei Zeiten, zwei Bedeutungen. */
    @Test
    fun `die leere Sicht fuehrt die Auditzeit, aber nicht die Frist`(@TempDir dir: File) {
        val a = adapter(dir)
        val alt = t0 - cutoffMs - 3600_000L
        a.publish("gebunden", 0.20, alt)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.20, ts = alt)))
        a.leereSicht(t0, "leer")

        val e = a.entryForTest("gebunden")
        assertEquals(t0, e.lastReconciledAtTs) { "es wurde hingesehen - das ist Auditzeit" }
        assertEquals(null, e.lastPositiveFactTs) { "es war nichts da - also kein Fakt und keine Frist" }
    }

    // ---- Toni-Vorgabe 3: der positive Fakt zaehlt sehr wohl --------------

    /**
     * Die Gegenrichtung, ohne die der Fix zu scharf waere: ein ECHTER Fakt
     * verlaengert die Frist. Er darf es auch - er belegt, dass die Menge zu
     * diesem spaeteren Zeitpunkt noch im IOB stand.
     */
    @Test
    fun `ein positiver Fakt wird korrekt beruecksichtigt`(@TempDir dir: File) {
        val a = adapter(dir)
        val alt = t0 - cutoffMs - 3600_000L
        a.publish("gebunden", 0.20, alt)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.20, ts = alt)))

        // Der Fakt steht JETZT in der Sicht - also innerhalb der Wirkzeit.
        a.sichtMitFakt(t0, "mit-fakt", tempId = 4711L, u = 0.20)

        val e = a.entryForTest("gebunden")
        assertEquals(t0, e.lastPositiveFactTs) { "ein positiver Fakt setzt die Frist" }

        a.prune(t0, dia)

        // Die Zeile ist jetzt SAUBER erledigt - restlos gebucht, fehlerfrei,
        // und damit regulaer entfernt. Der Unterschied zum Phantomfall ist
        // genau der Befund: eine abgeschriebene Zeile traegt
        // UNRESOLVED_BEYOND_ACTION, eine eingeloeste nicht.
        assertEquals(0, a.unresolvedBeyondActionCount()) {
            "eine Zeile mit positivem Fakt wird eingeloest, nicht als wirkungslos abgeschrieben"
        }
        assertEquals(0.0, a.view().transportCommitmentU, 1e-9)
        assertFalse(a.view().hold) { "der Regelfall darf keinen Hold hinterlassen" }
    }

    /** Und der positive Fakt ueberdauert eine spaetere leere Sicht: er wird
     *  nicht zurueckgesetzt, nur nicht erneuert. */
    @Test
    fun `eine leere Sicht loescht einen frueheren positiven Fakt nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        val alt = t0 - 3600_000L
        a.publish("gebunden", 0.20, alt)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.20, ts = alt)))
        a.sichtMitFakt(alt + 60_000L, "mit-fakt", tempId = 4711L, u = 0.20)
        a.leereSicht(t0, "leer-danach")

        assertEquals(alt + 60_000L, a.entryForTest("gebunden").lastPositiveFactTs) {
            "der Fakt WAR da - das bleibt wahr, auch wenn er jetzt fehlt"
        }
    }

    // ---- L2: das Band zwischen DIA+30min und DIA+2h ----------------------

    /**
     * L2 (Gegenproben-Audit 09.08.2026): DIE EINGELOESTE ZEILE FIEL AUS DEM
     * ABFRAGEFENSTER.
     *
     * Der Fensteranfang der Behandlungssicht wurde ueber `oldestOpenTs()`
     * verlaengert, und das filtert `!closed`. Eine eingeloeste Zeile IST
     * `closed` - sie fiel also aus der Verlaengerung, obwohl der Reducer sie
     * weiter jeden Zyklus gegen die Vollsicht abgleicht. Zwischen dem
     * Regelfenster (DIA+30 min) und dem Prune-Schnitt (DIA+2 h) fehlte ihr
     * Fakt dadurch in der Sicht, und der Reducer las das als "Buchung
     * verschwunden" - MISSING_ACCOUNTED_TREATMENT auf eine voellig korrekt
     * gebuchte Zeile. Zusammen mit B1 war das terminal.
     *
     * Bei DIA 9 h liegt das Band rund 9,5 h nach dem ersten SMB. Also an
     * jedem normalen Tag, nicht in einem Randfall.
     */
    @Test
    fun `eine eingeloeste Zeile bleibt bis zum Prune-Schnitt im Abfragefenster`(@TempDir dir: File) {
        val a = adapter(dir)
        // Mitten im Band: aelter als DIA+30 min, juenger als DIA+2 h.
        val imBand = t0 - (dia * 3600_000L).toLong() - 45L * 60_000L
        a.publish("eingeloest", 0.20, imBand)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.20, ts = imBand)))
        a.sichtMitFakt(imBand + 60_000L, "mit-fakt", tempId = 4711L, u = 0.20)

        val e = a.entryForTest("eingeloest")
        assertTrue(e.closed) { "Ausgangslage: die Zeile ist restlos gebucht und damit closed" }

        // Der alte Fensterbegriff verliert sie genau hier...
        assertEquals(null, a.oldestOpenTs()) { "oldestOpenTs kennt nur OFFENE Zeilen" }
        // ...der neue nicht. Solange sie nicht geprunt ist, wird sie
        // abgeglichen und muss deshalb im Fenster bleiben.
        assertEquals(imBand, a.oldestReconcilableTs()) {
            "eine noch nicht geprunte Zeile bleibt abgleichsrelevant"
        }
    }

    /** Und die Gegenprobe: nach dem Prune-Schnitt verankert sie nichts mehr -
     *  sonst waechst das Abfragefenster unbegrenzt mit der Laufzeit. */
    @Test
    fun `nach dem Prune-Schnitt verankert die Zeile das Fenster nicht mehr`(@TempDir dir: File) {
        val a = adapter(dir)
        val alt = t0 - cutoffMs - 60_000L
        a.publish("alt", 0.20, alt)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.20, ts = alt)))
        a.sichtMitFakt(alt + 60_000L, "mit-fakt", tempId = 4711L, u = 0.20)

        a.prune(t0, dia)

        assertEquals(null, a.oldestReconcilableTs()) {
            "was geprunt ist, wird nicht mehr abgeglichen und darf das Fenster nicht mehr aufspannen"
        }
    }

    // ---- Hilfen ----------------------------------------------------------

    private fun bolus(tempId: Long, u: Double, ts: Long) = BS(
        timestamp = ts,
        amount = u,
        type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", temporaryId = tempId),
    )

    private fun FuseLedgerAdapter.entryForTest(id: String) =
        checkNotNull(state.entries[id]) { "Zeile $id fehlt" }
}
