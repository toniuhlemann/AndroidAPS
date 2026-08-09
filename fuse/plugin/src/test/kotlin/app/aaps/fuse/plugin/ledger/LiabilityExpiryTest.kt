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

    /**
     * Eine Vollsicht MIT positivem Fakt fuer diese Identitaet.
     *
     * `at` ist die BEOBACHTUNGSZEIT (wann gelesen wurde), `ts` die LIEFERZEIT
     * des Datensatzes. Die Tests trennen beide bewusst - ihre Verwechslung war
     * der Fehler.
     */
    private fun FuseLedgerAdapter.sichtMitFakt(at: Long, hash: String, tempId: Long, u: Double, ts: Long = at) =
        onCycleSnapshot(listOf(AccountedTreatment(tempId, null, u, typ, serial, ts)), hash, at)

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

    // ---- P0-A: die Frist haengt an der LIEFERZEIT, nicht am Hinsehen -----

    /**
     * B1 IN NEUER KLEIDUNG (Codex-Re-Review 09.08.).
     *
     * Der erste Anlauf setzte `lastPositiveFactTs` auf die BEOBACHTUNGSZEIT
     * des Snapshots. Bei einer Zeile, die durch den Fakt vollstaendig
     * geschlossen wird, faellt das nicht auf. Bei einer TEILBUCHUNG schon:
     * dort bleibt die Zeile offen, der historische Fakt steht jede Minute
     * erneut in der Vollsicht - und verjuengte damit den offenen Rest
     * minuetlich. Derselbe Verjuengungsdefekt wie bei der bestaetigten
     * Abwesenheit, nur mit einem echten Fakt als Traeger.
     */
    @Test
    fun `derselbe historische Fakt verjuengt die Frist nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        val geliefert = t0 - 20 * 3600_000L
        a.publish("teil", 0.30, geliefert)
        // Gebunden wird ueber die MENGE - also mit der vollen Dosis. Die
        // Teilbuchung entsteht erst im Snapshot: die Pumpe hat weniger
        // abgegeben, als kommandiert wurde.
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.30, ts = geliefert)))

        // Derselbe Fakt, zwanzig Mal gelesen - zuletzt 20 h nach der Lieferung.
        for (i in 1..20) a.sichtMitFakt(geliefert + i * 3600_000L, "s-$i", 4711L, 0.20, ts = geliefert)

        assertEquals(geliefert, a.entryForTest("teil").lastPositiveFactTs) {
            "die Frist haengt an der Lieferzeit des Fakts, nicht daran, wie oft er gelesen wurde"
        }
    }

    /**
     * Und die Folge davon, die Codex ausdruecklich verlangt: der offene Rest
     * einer Teilbuchung laeuft nach Faktzeit + DIA + 2 h aus.
     */
    @Test
    fun `der Rest einer Teilbuchung laeuft nach Faktzeit plus DIA plus 2h aus`(@TempDir dir: File) {
        val a = adapter(dir)
        val geliefert = t0 - cutoffMs - 3600_000L
        a.publish("teil", 0.30, geliefert)
        // Gebunden wird ueber die MENGE - also mit der vollen Dosis. Die
        // Teilbuchung entsteht erst im Snapshot: die Pumpe hat weniger
        // abgegeben, als kommandiert wurde.
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.30, ts = geliefert)))
        // 0,20 U gebucht gegen 0,30 U Haftung -> 0,10 U bleiben offen.
        a.sichtMitFakt(t0, "jetzt-gelesen", 4711L, 0.20, ts = geliefert)

        assertEquals(0.10, a.view().transportCommitmentU, 1e-9) { "Ausgangslage: 0,10 U Rest" }

        a.prune(t0, dia)

        assertEquals(0.0, a.view().transportCommitmentU, 1e-9) {
            "der Rest laeuft ab der LIEFERZEIT aus - sonst nie, weil der Fakt jede Minute neu gelesen wird"
        }
    }

    /** Die Gegenrichtung: eine tatsaechlich spaetere Lieferzeit verschiebt die
     *  Frist konservativ nach hinten. PumpSync schreibt Zeitstempel um. */
    @Test
    fun `eine spaetere Lieferzeit verschiebt die Frist nach hinten`(@TempDir dir: File) {
        val a = adapter(dir)
        val entschieden = t0 - 3600_000L
        a.publish("spaet", 0.30, entschieden)
        a.bindIdentities(listOf(bolus(tempId = 4711L, u = 0.30, ts = entschieden)))
        a.sichtMitFakt(t0, "s1", 4711L, 0.20, ts = entschieden)
        // Korrigierter, SPAETERER Zeitstempel derselben Lieferung.
        a.sichtMitFakt(t0, "s2", 4711L, 0.20, ts = entschieden + 6_300L)

        assertEquals(entschieden + 6_300L, a.entryForTest("spaet").lastPositiveFactTs) {
            "eine spaetere Lieferzeit darf die Haftung verlaengern"
        }

        // ...und eine frueher korrigierte verkuerzt sie nicht.
        a.sichtMitFakt(t0, "s3", 4711L, 0.20, ts = entschieden - 60_000L)
        assertEquals(entschieden + 6_300L, a.entryForTest("spaet").lastPositiveFactTs) {
            "die Frist ist monoton - eine rueckwaerts korrigierte Zeit verkuerzt die Haftung nicht"
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
        a.sichtMitFakt(alt + 60_000L, "mit-fakt", tempId = 4711L, u = 0.20, ts = alt)

        a.prune(t0, dia)

        assertEquals(null, a.oldestReconcilableTs()) {
            "was geprunt ist, wird nicht mehr abgeglichen und darf das Fenster nicht mehr aufspannen"
        }
    }

    /**
     * L10 (Codex-Re-Review 09.08.): DIE ABGESCHRIEBENE FEHLERZEILE.
     *
     * `prune` behaelt fehlertragende Zeilen ABSICHTLICH - sie sind Befund.
     * Der erste L2-Fix nahm sie deshalb aber auch wieder ins Abfragefenster
     * auf, und das ist der Fall, den der vorhandene Test nicht traf: er prueft
     * eine FEHLERFREIE geschlossene Zeile, die regulaer verschwindet.
     *
     * Eine abgeschriebene Leiche verschwindet nie. Verankerte sie das Fenster,
     * wuechse die Bolusabfrage linear mit der Laufzeit - und der Reducer
     * belastete sie bei jedem Zyklus erneut, sobald ihr Fakt aus dem Fenster
     * gealtert ist. Aufbewahren ist nicht dasselbe wie weiter beobachten.
     */
    @Test
    fun `eine abgeschriebene Fehlerzeile bleibt im Audit, verankert aber nichts`(@TempDir dir: File) {
        val a = adapter(dir)
        // Nie gebunden, uralt -> wird als wirkungslos abgeschrieben und traegt
        // damit UNRESOLVED_BEYOND_ACTION. prune entfernt sie deshalb NICHT.
        a.publish("leiche", 0.20, t0 - 19 * 3600_000L)
        a.prune(t0, dia)

        assertEquals(1, a.unresolvedBeyondActionCount()) { "der Befund bleibt erhalten" }
        assertTrue(a.state.entries.containsKey("leiche")) { "und die Zeile auch - sie ist Audit" }

        assertEquals(null, a.oldestReconcilableTs()) {
            "aber sie verankert das Abfragefenster nicht - sonst waechst es linear mit der Laufzeit"
        }

        // Und sie wird nicht weiter abgeglichen: eine leere Sicht darf ihr
        // keinen neuen Befund mehr anhaengen.
        val vorher = a.entryForTest("leiche")
        a.leereSicht(t0 + 60_000L, "danach")
        assertEquals(vorher, a.entryForTest("leiche")) {
            "eine abgeschriebene Zeile wird nicht mehr angefasst"
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
