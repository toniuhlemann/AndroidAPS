package app.aaps.fuse.plugin.ledger

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * DIE REPARATUR WIRD VORGEMERKT, NICHT AUSGEFUEHRT.
 *
 * ## Der Fehler, gegen den das gebaut ist (P0, 10.08.2026)
 *
 * Der erste Entwurf verschob nur den OBJEKTTAUSCH an die Zyklusgrenze und liess
 * [FuseLedgerRepair.perform] weiterhin sofort auf dem UI-Thread laufen. Der
 * raeumt aber DATEIEN um: Hold-Marker weg, Generationen in Quarantaene, leere
 * Nachfolgerin schreiben.
 *
 * Ein gleichzeitig laufender Zyklus haelt seinen ALTEN Ledger im Speicher und
 * schreibt ihn nach der Reparatur mit `persistVerified()` in genau dieselben
 * Dateien zurueck. Der reparierte Zustand ist dann ueberschrieben, der alte
 * WIEDERBELEBT - inklusive der Fehlerzeilen, die den Hold ausgeloest haben. Der
 * naechste Zyklus laedt ihn und haelt wieder. Die Reparatur haette scheinbar
 * funktioniert und waere in Wahrheit rueckgaengig gemacht.
 *
 * Es genuegt also NICHT, den Objekttausch zu verzoegern: die Dateireparatur
 * gehoert an dieselbe Grenze. Vorgemerkt wird ein unveraenderlicher Auftrag;
 * ausgefuehrt wird er ganz am Anfang des naechsten Zyklus, bevor irgendetwas
 * gelesen, gerechnet oder geschrieben wird.
 *
 * ## Warum ein Auftrag und kein Flag
 *
 * Der GRUND entsteht beim Bediener - aus der Lage, die ihm der Dialog gezeigt
 * hat, und der er zugestimmt hat. Ihn zur Ausfuehrungszeit neu zu bilden waere
 * ein anderes Protokoll als die erteilte Zustimmung.
 *
 * ## Warum compareAndSet
 *
 * Zweimal tippen darf nicht zwei Reparaturen bedeuten. Der zweite Auftrag wird
 * ABGEWIESEN, nicht in eine Schlange gestellt - die zweite Reparatur faende
 * ohnehin nichts mehr vor und wuerde nur eine zweite Protokollzeile ohne
 * Vorgang erzeugen.
 */
class FuseRepairScheduler {

    private val pending = AtomicReference<FuseLedgerRepair.RepairRequest?>(null)

    /** @return false, wenn schon einer aussteht - dann passiert NICHTS. */
    fun request(r: FuseLedgerRepair.RepairRequest): Boolean = pending.compareAndSet(null, r)

    val isPending: Boolean get() = pending.get() != null

    /**
     * Am Zyklusanfang aufrufen. `null` heisst: kein Auftrag, nichts getan.
     *
     * Der Auftrag wird ZUERST entnommen und dann ausgefuehrt. Wirft die
     * Ausfuehrung, ist er trotzdem verbraucht - eine Reparatur, die auf einem
     * halb veraenderten Verzeichnis wiederholt wird, ist gefaehrlicher als
     * keine. Der Hold bleibt in dem Fall stehen und der Bediener kann erneut
     * entscheiden.
     *
     * [perform] bekommt den Auftrag herein und prueft SELBST erneut, ob die
     * Reparatur ueberhaupt noch zulaessig ist (s. `FuseLedgerRepair.inspect`):
     * zwischen Zustimmung und Ausfuehrung liegt ein Zyklus, in dem sich die
     * Lage geaendert haben kann.
     */
    fun runIfDue(
        perform: (FuseLedgerRepair.RepairRequest) -> FuseLedgerRepair.Result,
    ): FuseLedgerRepair.Result? {
        val auftrag = pending.getAndSet(null) ?: return null
        return perform(auftrag)
    }

    /**
     * Bequemlichkeit fuer den Regelfall - MIT der Realpump-Sperre.
     *
     * ## Warum die Reparatur an einer echten Pumpe (noch) nicht laufen darf
     *
     * `FuseLedgerRepair.perform` schreibt eine vollstaendig frische Generation.
     * Damit verschwinden GLEICHZEITIG: die offene Haftung, die Prime-/Onset-
     * Budgets, die Mahlzeitenhistorie, die Wende-Latches und
     * `lastAcceptedSourceTs`. An der VirtualPump ist das folgenlos. An einer
     * echten Pumpe heisst es:
     *
     *  - Haftung fuer Insulin, das gerade abgegeben, in der Behandlungstabelle
     *    aber noch nicht sichtbar ist, wird verworfen (gemessene Sichtbarkeit
     *    p90 56 s, max 854 s) - der naechste Zyklus dosiert obendrauf;
     *  - die Prime-Huelle derselben Mahlzeit oeffnet ein zweites Mal;
     *  - beide Stufen des "genau einmal je Glukose-Epoch"-Riegels fallen weg.
     *
     * Bis ein zustandserhaltender Weg steht, wird deshalb VERWEIGERT. Geprueft
     * wird AM AUSFUEHRUNGSPUNKT und nicht beim Oeffnen des Dialogs: zwischen
     * Zustimmung und Ausfuehrung liegt ein Zyklus, in dem der Bediener die
     * Pumpe wechseln kann.
     *
     * @param realPump ist JETZT eine echte Pumpe aktiv? Aus dem Zyklus-Snapshot.
     */
    fun runIfDue(dir: File, nowTs: Long, realPump: Boolean): FuseLedgerRepair.Result? =
        runIfDue {
            if (realPump) FuseLedgerRepair.Result.Refused(
                "an einer ECHTEN Pumpe gesperrt - die Reparatur verwirft Haftung, " +
                    "Mahlzeiten-Huelle und den Genau-einmal-Riegel; ein zustandserhaltender Weg fehlt noch"
            )
            else FuseLedgerRepair.perform(dir, nowTs, it.by, it.reason)
        }
}
