package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.LedgerError
import app.aaps.fuse.core.ledger.LedgerErrorRecord
import app.aaps.fuse.core.ledger.LedgerState
import java.util.concurrent.atomic.AtomicReference

/**
 * WAS SICH QUITTIEREN LAESST - als reine Regel, damit sie pruefbar ist.
 *
 * Sie sitzt hier und nicht im Adapter, weil ihr Zustand (`state`) dort privat
 * ist: im Adapter waere genau die Zusicherung, auf die es ankommt, nur ueber
 * einen vollstaendig aufgebauten Ledger erreichbar gewesen - und ein Test, der
 * seinen Fall nicht sicher herstellt, prueft nichts (die Placebo-Falle, die das
 * Gesamtaudit mehrfach nachgewiesen hat).
 */
object HoldQuittungAuswahl {

    /**
     * DIE MENGE IST ENGER ALS `RECOVERABLE_ERRORS`, und das ist kein
     * Vorsichtsaufschlag, sondern Mechanik.
     *
     * Die Quittung ist ein `OfProposal`-Ereignis, und der Dispatch weist jedes
     * Ereignis ohne zugehoerige Zeile mit einem NEUEN fail-closed
     * `UNKNOWN_PROPOSAL` ab (`LedgerReducer.kt:147-151`). Wuerde die
     * Bedienoberflaeche einen Fehler ohne Zeile anbieten, machte die Quittung
     * die Lage SCHLECHTER: ein zusaetzlicher Sperrgrund, eine erhoehte
     * `holdGeneration` - und damit auch jede parallel vorbereitete Quittung
     * ungueltig.
     *
     * Betroffen sind die Fehler, die VOR oder OHNE Zeile entstehen:
     * `UNKNOWN_PROPOSAL` selbst (nur bei `entry == null` erhoben),
     * `NON_FINITE_AMOUNT` aus `onProposed` (`LedgerReducer.kt:157-165`, bevor
     * die Zeile existiert) und jeder global erhobene Fehler ohne `proposalId` -
     * `SNAPSHOT_ORDER_CONFLICT` wird ausschliesslich so erhoben.
     *
     * DESHALB WIRD GEFILTERT UND NICHT DIE DEKLARATION GEAENDERT: die Fehler
     * aus `RECOVERABLE_ERRORS` zu streichen waere eine Semantikaenderung am
     * Kern fuer ein Problem der Oberflaeche - und sie bleiben zu Recht
     * quittierbar, sobald es eine Zeile gibt.
     *
     * @param hatZeile ob zu dieser `proposalId` eine Zeile existiert.
     */
    fun quittierbar(
        errors: List<LedgerErrorRecord>,
        hatZeile: (String) -> Boolean,
    ): List<LedgerErrorRecord> = errors.filter {
        val id = it.proposalId
        it.active && it.error in LedgerState.RECOVERABLE_ERRORS && id != null && hatZeile(id)
    }
}

/**
 * DIE VORGEMERKTE HOLD-QUITTUNG.
 *
 * Zweck wie bei [FuseRepairScheduler]: der Bediener stimmt im Dialog zu, die
 * Wirkung tritt an der naechsten ZYKLUSGRENZE ein. Was dort wie Umstaendlichkeit
 * aussieht, ist der Kern der Sache.
 *
 * ## Warum nicht sofort am UI-Thread
 *
 * `FuseLedgerAdapter.state` und `revision` sind gewoehnliche `var` ohne Sperre,
 * und `reduce` ist lesen-aendern-schreiben. Rechnet gerade ein Zyklus, dann
 * liest er den Zustand, die Quittung aendert ihn, der Zyklus schreibt sein
 * Ergebnis darueber - und die Quittung ist verloren. Kein Absturz, keine
 * Meldung: der Hold steht einfach weiter, obwohl der Dialog "quittiert"
 * gesagt hat. Genau diese Fehlerklasse hat den Reparaturweg schon einmal
 * gekostet (Codex-P0 vom 10.08.).
 *
 * ## Warum NACH `loadOnce` und nicht davor - anders als die Reparatur
 *
 * Die Reparatur arbeitet auf DATEIEN und muss deshalb vor dem Laden laufen.
 * Die Quittung arbeitet auf dem GELADENEN ZUSTAND. Liefe sie davor, traefe sie
 * im ersten Zyklus eines Prozesses einen leeren Zustand: der Dispatch faende
 * die Zeile nicht, wiese die Quittung mit einem NEUEN `UNKNOWN_PROPOSAL` ab
 * (`LedgerReducer.kt:147-151`) und `loadOnce` verwuerfe das Ergebnis
 * anschliessend ohnehin. Und das ist nicht der Randfall, sondern DER Fall:
 * ein Hold ueberlebt den Neustart, die Quittung wird also typischerweise nach
 * einem Neustart erteilt.
 *
 * ## Warum compareAndSet
 *
 * Zweimal tippen darf nicht zwei Quittungen bedeuten. Die zweite wird
 * ABGEWIESEN statt eingereiht - sie traegt eine `holdGeneration`, die nach der
 * ersten ohnehin veraltet waere, und wuerde nur einen zweiten
 * `REJECTED`-Beleg erzeugen.
 */
class FuseHoldQuittungScheduler {

    /**
     * @param proposalId die Zeile, deren Fehler quittiert werden.
     * @param errors die AUSDRUECKLICH benannten Fehler. Der Reducer verbietet
     *   die Pauschalfreigabe (`LedgerReducer.kt:369`) - wer quittiert, muss
     *   sagen was.
     * @param expectedHoldGeneration Stand zum Zeitpunkt der Zustimmung. Steigt
     *   er bis zur Ausfuehrung, wird die Quittung abgewiesen: der Bediener hat
     *   dann eine andere Lage gesehen als die, die jetzt gilt.
     */
    data class Auftrag(
        val proposalId: String,
        val by: String,
        val reason: String,
        val errors: Set<LedgerError>,
        val expectedHoldGeneration: Long,
    )

    private val pending = AtomicReference<Auftrag?>(null)

    /** @return false, wenn schon einer aussteht - dann passiert NICHTS. */
    fun request(a: Auftrag): Boolean = pending.compareAndSet(null, a)

    val isPending: Boolean get() = pending.get() != null

    /**
     * An der Zyklusgrenze aufrufen, NACH `loadOnce`.
     *
     * Der Auftrag wird ZUERST entnommen und dann ausgefuehrt. Wirft die
     * Ausfuehrung, ist er trotzdem verbraucht - eine automatisch wiederholte
     * Quittung waere eine Zustimmung, die der Bediener nur einmal gegeben hat.
     * Der Hold bleibt dann stehen und er kann erneut entscheiden.
     *
     * @return `null` = kein Auftrag. Sonst das Ergebnis von [quittiere]:
     *   true, wenn die Zeile danach frei ist.
     */
    fun runIfDue(quittiere: (Auftrag) -> Boolean): Boolean? {
        val auftrag = pending.getAndSet(null) ?: return null
        return quittiere(auftrag)
    }
}
