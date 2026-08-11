package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.core.ledger.AmountStage
import app.aaps.fuse.core.ledger.IobAccountingSnapshot
import app.aaps.fuse.core.ledger.LedgerConfig
import app.aaps.fuse.core.ledger.LedgerError
import app.aaps.fuse.core.ledger.LedgerEvent
import app.aaps.fuse.core.ledger.LedgerReducer
import app.aaps.fuse.core.ledger.LedgerState
import app.aaps.fuse.core.util.Sha
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import app.aaps.fuse.plugin.FuseActivePump

/**
 * Episodenbudgets der Mahlzeit-Kanaele - RESTARTFEST (Audit R95, Fix 3).
 *
 * Vorher lebten sie als Felder im FuseCycleRunner: ein Neustart mitten im
 * Marker-Fenster setzte primeSpent/onsetSpent auf 0, und die Huelle stand
 * ein zweites Mal voll zur Verfuegung - dieselbe Wette doppelt finanziert.
 * Jetzt haengen sie am Ledger-Persistenzobjekt und ueberleben den Prozess.
 * Der Reset-ANLASS bleibt im Runner (neuer armedTs = neue Episode).
 */
class EpisodeBudgets {

    var primeSpentU: Double = 0.0
    var primeArmedTs: Long = 0L
    var onsetSpentU: Double = 0.0
    var onsetQuietMin: Int = 0
    var mealArmedTs: Long = 0L

    /** Fix 7 (Audit R95 NEU-01/02): Zeitpunkt der ersten nachhaltigen Wende
     *  nach Marker-Druck - beendet die Marker-Sonderrechte. 0 = keine Wende.
     *  Restartfest, damit ein Neustart nach der Wende die Rechte nicht
     *  wiederbelebt. */
    var markerTurnTs: Long = 0L

    /** Fix-Pass 2 Nr. 4: seit Marker-Druck wurde eine Anstiegsphase gesehen;
     *  erst danach darf eine Wende die Sonderrechte latchen. Gesetzt und beim
     *  Marker-Reset genullt im Runner (parallele Sitzung) - hier liegt nur
     *  Feld + Persistenz, damit ein Neustart die schon gesehene Anstiegs-
     *  phase nicht vergisst. */
    var markerRiseSeen: Boolean = false

    /**
     * Fix 5 (Re-Audit c750169, 6.5): die Dosing-Epoch - sourceTs des zuletzt
     * AKZEPTIERTEN Glukosepunkts. Genau-einmal je Glukose-Epoch UEBER
     * Prozessgrenzen: nach einem Neustart darf derselbe Sensoranker keine
     * zweite positive Entscheidung finanzieren, unabhaengig davon, ob der
     * erste Betrag inzwischen im IOB sichtbar ist. Lesen/Setzen verdrahtet
     * der Runner (parallele Sitzung); hier liegen nur Feld + Persistenz.
     */
    var lastAcceptedSourceTs: Long = 0L

    /**
     * Beginn des LIEFERBAREN Prime-Fensters [ms] - s.
     * [app.aaps.fuse.core.controller.PrimeRelease.WALL_CEILING_MIN].
     *
     * Restartfest, weil sonst ein Neustart mitten in einer gesperrten Phase
     * das Fenster neu aufziehen wuerde: die Wette liefe dann laenger als der
     * blinde Kopf der Mahlzeit, gegen den sie bemessen ist.
     */
    var primeWindowStartTs: Long = 0L

    /**
     * KUMULATIV in dieser Episode abgegebenes Insulin [U] - die Bezahlung
     * des Stoerungsbestands (s. `EvidenceStock`).
     *
     * WARUM NICHT `mealDeliveries` summiert, was naheliegend waere: das
     * sammelt nur, solange der MARKER laeuft (90 min). Eine Episode darf
     * bis 240 min gehen, und der gemessene Lauf vom 11.08. war nach 205
     * Minuten noch aktiv - ab Minute 90 waere die Bezahlung stillschweigend
     * ausgefallen und der Bestand haette weiter lizenziert.
     *
     * ALLE Kanaele zahlen darauf ein: Prime, Onset, Rest-Zaehler und die
     * gewoehnliche Korrektur wirken gegen DIESELBE Stoerung. Keine
     * Doppelanrechnung - die Huellen begrenzen, WIEVIEL ein Kanal geben
     * darf, dieser Zaehler misst, WIEVIEL Stoerung schon bezahlt ist.
     *
     * Wird wie [primeSpentU] bei einem Gate-Reject zurueckgedreht
     * ([resolveReservation]) und bei einer neuen Episode auf 0 gesetzt.
     */
    var evidenceCommittedU: Double = 0.0

    /**
     * Identitaet der Episode, zu der [evidenceCommittedU] gehoert - der
     * ERSTE Markerdruck dieser Episode.
     *
     * DER ANKER LIEGT HIER UND NICHT IM JEWEILS AKTUELLEN MARKER, und das
     * ist der Unterschied zwischen Buchfuehrung und Doppelfinanzierung:
     * eine Ruecknahme beendet die Marker-AUTORISIERUNG, nicht die Episode.
     * Wuerde die Identitaet am aktuellen `markerTs` haengen, erzeugte
     * Ruecknahme plus erneutes Druecken still eine neue Episode mit
     * Zaehler 0 - dieselbe Stoerung waere ein zweites Mal unbezahlt.
     *
     * Eine NEUE Episode beginnt erst, wenn diese eindeutig zu Ende ist:
     * nach dem harten Deckel. Bis dahin erbt jeder weitere Druck den
     * bestehenden Anker und den bestehenden Zaehler.
     */
    var evidenceEpisodeId: Long = 0L

    /**
     * Der DAUERHAFT VERBRAUCHTE Markeranker - juengster Knopfdruck, der je
     * eine Evidenz-Episode eroeffnet hat. 0 = noch keiner.
     *
     * WARUM ZUSAETZLICH ZU [evidenceEpisodeId], obwohl der doch dasselbe
     * Ereignis traegt: der Episodenanker VERFAELLT nach dem 240-Minuten-
     * Deckel, der Markerzeitpunkt in den Preferences aber NICHT. Danach steht
     * dort weiterhin dieselbe Zahl, die Episode ist weg, und derselbe
     * Knopfdruck saehe wieder aus wie ein neuer - eine zweite Episode mit
     * frischem Deckel und frischem Zaehler fuer dieselbe Mahlzeit.
     *
     * Dieses Feld ueberlebt den Deckel UND jede Episodenbereinigung. Es wird
     * nur vorwaerts geschrieben; ein Ruecksetzen gibt es nicht.
     *
     * WARUM EIN SKALAR UND KEINE MENGE, und das ist eine Korrektur meiner
     * eigenen frueheren Ansage: eine begrenzte "retired"-Menge muesste
     * irgendwann verdraengen, und genau der verdraengte Eintrag wuerde danach
     * wieder als neu gelten - die Menge waere also SCHWAECHER als die Schranke.
     * Monoton "alles bis hierher ist verbraucht" hat keine Verdraengung.
     *
     * PREIS, ausdruecklich: springt die Uhr rueckwaerts, liegt ein echter
     * neuer Druck vor dem Anker und eroeffnet keine Episode
     * (`MARKER_ALREADY_CONSUMED`). Die Marker-SONDERRECHTE - Prime-Fenster,
     * Onset, Prior - haengen nicht hieran und laufen weiter; es entfaellt nur
     * der Evidenz-Kredit, und der Grund steht im Tab.
     */
    var lastConsumedMarkerTs: Long = 0L
    val mealDeliveries: ArrayDeque<Pair<Long, Double>> = ArrayDeque()

    /**
     * Die Buchung DIESES Zyklus, solange die Publikation nicht feststeht.
     *
     * WARUM RESERVIEREN UND NICHT VERSCHIEBEN: der Runner belastet die
     * Budgets, bevor das PUBLIKATIONSgate gelaufen ist - das sitzt erst im
     * Plugin und kann die Menge noch entfernen (fehlende Vollsicht,
     * Persistenzfehler, Epochensperre). Die Buchung stattdessen nach hinten zu
     * schieben waere die FALSCHE Richtung: stirbt der Prozess dazwischen,
     * waere sie nie erfolgt - Budget frei, Insulin draussen.
     *
     * Also: sofort belasten, danach aufloesen.
     *
     *   RESERVED   hier gebucht, ueberlebt einen Absturz
     *   REQUESTED  Gate hat die Menge durchgelassen  -> bleibt
     *   REJECTED   Gate hat sie NACHWEISLICH entfernt -> wird freigegeben
     *   UNKNOWN    Ausgang offen                      -> bleibt Haftung
     *
     * Jede Fehlerrichtung landet damit auf "zu wenig", nie auf "zu viel".
     *
     * NICHT PERSISTENT, und das ist Absicht: geht sie beim Neustart verloren,
     * bleibt die Belastung stehen - genau der konservative Ausgang. Sie zu
     * persistieren wuerde Codec-Flaeche und Validierung kosten, um einen
     * bereits sicheren Fall sicherer zu machen.
     */
    var pendingReservation: Reservation? = null

    /** @param mealTs 0 = nicht in [mealDeliveries] gebucht. */
    class Reservation(
        /** Identitaet ueber `computeTs` - die `cycleId` entsteht erst im
         *  Plugin, der Runner kennt sie nicht. */
        val computeTs: Long,
        val amountU: Double,
        val prime: Boolean,
        val onset: Boolean,
        val mealTs: Long,
    )
}

/**
 * Fix 6 (Audit 2d273cb, NEU-BS-02): Identitaet einer beim [FuseLedgerAdapter.prune]
 * entfernten Zeile, die GEBUNDEN war. Diese IDs bleiben persistent
 * "verbraucht" - sonst leert der prune die Ausschlussmenge der Bindung, und
 * ein bereits verbuchter fremder Bolus koennte eine alte offene Zeile
 * schliessen, ohne dass je Insulin nachgewiesen wurde.
 */
data class RetiredBoundId(val temporaryId: Long?, val pumpId: Long?)

/**
 * Fix 3 (Re-Audit c750169, 6.3): die beim PUBLIKATIONSZEITPUNKT aktive
 * Pumpen-Epoch, je Vorschlag gepinnt. Der Kern ([app.aaps.fuse.core.ledger.ProposalEntry])
 * traegt bewusst kein Feld dafuer (core/ledger ist heute tabu) - deshalb
 * fuehrt der Adapter eine persistierte Map proposalId -> Epoch. Ohne die
 * Pinnung konnte ein gleich grosser SMB einer NACH dem Proposal aktivierten
 * anderen Pumpe die alte Zeile binden und ueber deren IOB-Fakt schliessen,
 * obwohl beide Pumpvorgaenge existiert haben koennen.
 *
 * DREI Zustaende statt "Pin oder nichts" (Codex R4-03):
 *  - normale Pinnung: Type/Serial gesetzt, bindet nur die eigene Epoch;
 *  - [UNPINNED]: die Pumpen-API war bei der Publikation nicht lesbar -
 *    FAIL-CLOSED, die Zeile bindet NIE (vorher fiel sie still auf das
 *    Legacy-"bindet alles" zurueck, und ein fremder Kontext konnte sie
 *    schliessen);
 *  - [LEGACY_OPEN]: EXPLIZIT migrierter Altbestand (Zeile aus einer
 *    Schemaversion-1-Datei ohne Pinnung) - nur er behaelt das alte
 *    Bindungsverhalten. Ab Schemaversion 2 traegt jede Zeile einen dieser
 *    drei Eintraege; eine Zeile OHNE Eintrag ist Fremdinhalt.
 */
data class ProposalPumpEpoch(
    val pumpTypeName: String?,
    val pumpSerialHash: String?,
    val unpinned: Boolean = false,
    val legacyOpen: Boolean = false,
    /**
     * B3: die PATCH-Epoche zum Zeitpunkt der Publikation.
     *
     * Type und Serial erkennen einen Patchwechsel DERSELBEN Pumpe nicht - die
     * Medtrum-Seriennummer ist die der Basis und ueberlebt ihn. Dieses Feld
     * traegt deshalb zusaetzlich den Zeitpunkt des zugehoerigen
     * CANNULA_CHANGE (s. [FusePatchEpoch]).
     *
     * `null` heisst je nach Pumpe ZWEIERLEI, und der Unterschied ist der
     * Grund, warum daneben [patchEpochApplicable] steht:
     *  - bei einer Nicht-Patch-Pumpe (VirtualPump): NICHT ANWENDBAR. Es gibt
     *    keine Patches, also auch keine Epoche - die Zeile bindet wie bisher.
     *  - bei einer Patchpumpe: UNBEKANNT. Dann bindet sie nicht, und eine
     *    positive Dosis wird gar nicht erst publiziert.
     *
     * Ohne diese Trennung wuerde B3 den Entwicklungspfad gegen die
     * VirtualPump still mitsperren.
     */
    val patchEpochTs: Long? = null,
    /** Ist die Patch-Epoche fuer DIESE Pumpe ueberhaupt eine Kategorie? */
    val patchEpochApplicable: Boolean = false,
    /**
     * War die Pumpe zum Publikationszeitpunkt die EMULIERTE (VirtualPump)?
     *
     * Ohne dieses Feld waere `patchEpochApplicable = false` an einem
     * MEDTRUM-Pin doppeldeutig: **Emulation** oder **Korruption**. Die
     * Korruptionsdeutung ist die sicherheitsrelevante (eine beschaedigte
     * Pinnung darf die Patchpruefung nicht umgehen), also muss die Emulation
     * ihren EIGENEN Zustand bekommen - dieselbe Lehre wie an jeder anderen
     * Stelle dieses Projekts.
     *
     * PERSISTIERT, und das ist die eigentliche Anforderung: nach einem Restart
     * darf es NICHT erneut aus [pumpTypeName] geraten werden. Der Typname sagt
     * bei der VirtualPump nichts ueber die Emulation aus (s. [FuseActivePump]),
     * und eine spaeter umgestellte Preference wuerde alte Zeilen rueckwirkend
     * umdeuten.
     */
    val virtualPump: Boolean = false,
) {

    init {
        // Ein Marker-Pin traegt nie zusaetzlich Inhalt - er waere doppeldeutig.
        require(!(unpinned && legacyOpen)) { "pump epoch cannot be unpinned and legacyOpen" }
        if (unpinned || legacyOpen)
            require(pumpTypeName == null && pumpSerialHash == null) { "marker pin must not carry content" }
        // Eine Epoche ohne Zustaendigkeit waere ein Wert ohne Bedeutung.
        require(!(patchEpochTs != null && !patchEpochApplicable)) {
            "patchEpochTs without applicability: $patchEpochTs"
        }
        // Marker tragen auch dieses Feld nicht - sie sind die ABWESENHEIT einer
        // Aussage. Niemand liest es an ihnen (UNPINNED bindet nie, LEGACY_OPEN
        // entscheidet ueber die AKTIVE Pumpe), und ein gesetztes Flag waere der
        // Anfang einer zweiten Bedeutung.
        if (unpinned || legacyOpen) require(!virtualPump) { "marker pin must not carry virtualPump" }
        // Gegen die Emulation gibt es keine Patches - also auch keine Kategorie
        // und erst recht keinen Zeitstempel. Beides einzeln geprueft, damit die
        // Fehlermeldung sagt, WELCHE Haelfte gebrochen ist.
        require(!(virtualPump && patchEpochApplicable)) { "virtual pump cannot have an applicable patch epoch" }
        require(!(virtualPump && patchEpochTs != null)) { "virtual pump cannot carry a patch epoch: $patchEpochTs" }
        // DIE KORRUPTIONSGEGENPROBE: ein Patchpumpen-Pin, der NICHT emuliert
        // ist, MUSS die Kategorie fuehren. Genau diese Kombination -
        // `MEDTRUM_NANO + virtualPump=false + patchEpochApplicable=false` -
        // waere sonst der Weg, an der Patchpruefung vorbeizukommen.
        if (!virtualPump && appliesTo(pumpTypeName)) require(patchEpochApplicable) {
            "real patch pump pin without applicability: $pumpTypeName"
        }
    }

    companion object {

        val UNPINNED = ProposalPumpEpoch(null, null, unpinned = true)
        val LEGACY_OPEN = ProposalPumpEpoch(null, null, legacyOpen = true)

        /**
         * Fuer welche Pumpentypen ist eine Patch-Epoche ueberhaupt eine
         * Kategorie?
         *
         * `PumpType.isPatchPump` waere die richtige Quelle - das Feld ist aber
         * PRIVAT und von aussen nicht lesbar. Es oeffentlich zu machen waere
         * eine Kernaenderung fuer eine Zeile Bequemlichkeit; die Auflage
         * verlangt, Kernaenderungen auf das Noetige zu begrenzen.
         *
         * Deshalb ueber den Namen, genau wie [FusePumpGate] und die
         * Serial-Faltung in [LedgerFacts] es bereits tun. Das ist derselbe
         * Praezedenzfall und bleibt bei einem Merge stabil.
         *
         * TRAGWEITE: eine kuenftige, hier nicht gefuehrte Patchpumpe gilt als
         * NICHT anwendbar - die Patch-Pruefung entfiele fuer sie still. Das
         * ist vertretbar, weil [FusePumpGate] ohnehin nur die belegte
         * Medtrum-Familie durchlaesst: eine unbekannte Pumpe kommt gar nicht
         * bis hierher. Wer den Gate-Riegel erweitert, muss diese Liste
         * mitnehmen - der Test darunter haelt beides zusammen.
         */
        fun appliesTo(pumpTypeName: String?): Boolean =
            pumpTypeName != null && pumpTypeName.startsWith("MEDTRUM")
    }
}

/**
 * DER PUMPENKONTEXT DER BINDUNG - aus dem Zyklus-Snapshot, in EINEM Stueck.
 *
 * Vorher standen Epoche und Realpump-Merkmal als zwei getrennte Felder im
 * Adapter, jedes mit eigenem Setzer. Zwei Setzer sind zwei Gelegenheiten, sie
 * auseinanderlaufen zu lassen - und beide entscheiden ueber dieselbe Frage:
 * darf dieser Fakt diese Zeile schliessen.
 *
 * [virtualPump] ist DREIWERTIG und das ist der Kern der Sache. "Keine echte
 * Pumpe" ist NICHT dasselbe wie "nachgewiesene Emulation": zu ersterem
 * gehoeren auch die unbekannte und die gesperrte Fremdpumpe. Ein serialloser
 * Pin darf nur bei NACHGEWIESENER Emulation als Wildcard gelten - sonst
 * oeffnet ausgerechnet der unbekannte Kontext das Tor.
 */
data class LedgerPumpBindingContext(
    val virtualPump: Boolean?,
    val pumpTypeName: String?,
    val serialHash: String?,
    val patchEpochTs: Long?,
) {

    /** Nur bei NACHGEWIESENER Emulation - `null` (unbekannt) genuegt nicht. */
    val serialWildcardAllowed: Boolean get() = virtualPump == true

    companion object {

        /** Vorgabe: nichts bekannt. Kein Wildcard, keine Epoche. */
        val UNKNOWN = LedgerPumpBindingContext(null, null, null, null)

        /** Fuer Tests und den Entwicklungspfad: nachgewiesene Emulation. */
        fun emulation(patchEpochTs: Long? = null) =
            LedgerPumpBindingContext(true, null, null, patchEpochTs)
    }
}

/** Was der Zyklus vom Ledger sieht: Sperre (mit Grund fuer Anzeige/Trail)
 *  und gebundene Transportmenge. */
data class LedgerView(val hold: Boolean, val transportCommitmentU: Double, val holdReason: String? = null)

/**
 * EIN offener Transport-Posten - Menge UND eigener Zeitstempel (C3-01, Codex
 * Fix-Pass-5-Closure G.2).
 *
 * Bis Fix-Pass 5 kannte der Runner nur zwei Zahlen: `transportCommitmentU`
 * (Summe) und `oldestOpenTs()` (aeltester Anker). Damit modellierte er
 * mehrere Dosen verschiedener Lieferzeit als EINE Dosis am aeltesten Anker -
 * und unterschlug die Resthaftung der juengeren. Diese Sicht gibt jede Zeile
 * EINZELN heraus; `oldestOpenTs()` bleibt daneben bestehen, es hat mit dem
 * Fensteranfang der Vollsicht einen anderen Zweck.
 *
 * REIN LESEND. Die Zahlen stammen unveraendert aus
 * [app.aaps.fuse.core.ledger.ProposalEntry]; hier wird nichts neu bewertet.
 */
data class OpenTransportItem(
    val proposalId: String,
    /** Was der Ledger fuer diese Zeile noch als offen fuehrt [U]. */
    val commitmentU: Double,
    /** Die konservativ moegliche Gesamtmenge der Zeile [U]. Sie gilt, solange
     *  die Zugehoerigkeit zum IOB-Snapshot NICHT entscheidbar ist (C3-02). */
    val grossLiabilityU: Double,
    /** Was der Ledger als im IOB nachgewiesen gebucht hat [U]. 0 = nichts. */
    val accountedAmountU: Double,
    /** Bester bekannter Zeitstempel: die Treatment-Zeit der gebundenen
     *  Identitaet, sonst der Entscheidungszeitpunkt. */
    val bestKnownTs: Long,
    val temporaryId: Long?,
    val pumpId: Long?,
    /** Beweisbar floss nichts (bestaetigte Null bzw. unbestrittener Rueckzug).
     *  Dann haftet die Zeile in KEINER Sicht. */
    val settledZero: Boolean,
)

/**
 * DER INCLUSION-VERTRAG DES UEBERGANGS TRANSPORT -> IOB (C3-02, P0, Codex
 * Fix-Pass-5-Closure Abschnitt G.3/K).
 *
 * PROBLEM. Der Runner liest die Ledgersicht, baut danach die IOB-Arrays, und
 * die Reconciliation laeuft erst NACH dem Zyklus. Der gefaehrliche Fall ist
 * nicht die Doppelzaehlung - die ist konservativ -, sondern die LUECKE: eine
 * Behandlung ist fuer die Reconciliation sichtbar (Commitment faellt auf 0),
 * das IOB-Array stammt aber noch aus einer Lesung OHNE sie. Dann steckt die
 * Menge in KEINER Sicht, und der Guard rechnet, als gaebe es sie nicht.
 *
 * INVARIANTE, die hier hergestellt wird:
 *
 *     Eine Menge verlaesst die Transport-Modellierung NUR dann, wenn ihr
 *     Behandlungsfakt NACHWEISLICH in der Bolus-Lesung stand, die dem Bau der
 *     IOB-Arrays dieses Zyklus VORAUSGING. Ist die Zugehoerigkeit nicht
 *     entscheidbar, bleibt der Posten in voller Hoehe Transport.
 *
 * WARUM DIE REIHENFOLGE DEN NACHWEIS TRAEGT: der Zeuge wird VOR dem ersten
 * `calculateFromTreatmentsAndTemps` dieses Zyklus gelesen. Die
 * Behandlungstabelle waechst innerhalb eines Zyklus nur (Loeschungen erzeugt
 * der Nutzer, und sie schlagen ueber MISSING_ACCOUNTED_TREATMENT in einen Hold
 * um). Was der Zeuge sah, war also beim Arraybau in der Datenbank. Die
 * Umkehrung wird NICHT behauptet - ein Fakt, den der Zeuge nicht sah, gilt als
 * unentscheidbar, nicht als abwesend.
 *
 * DIESER SATZ WAR BIS 09.08.2026 FALSCH, und zwar als Tatsachenbehauptung:
 * der Zeuge stand vor dem ARRAYBAU, aber NACH der Signalstufe - und die setzt
 * je Rohpunkt des Fensters ein `calculateFromTreatmentsAndTemps` ab, das
 * letzte davon auf `sourceTs`. Damit SCHRIEB sie den iobTable-Eintrag am
 * Schluessel `roundUpTime(sourceTs)` selbst; genau den liest der Arraybau
 * spaeter fuer Punkt 0 wieder. Ein Bolus, der dazwischen gebucht wurde, war
 * fuer den Zeugen sichtbar (Posten faellt auf commitmentU), fuer den bereits
 * geschriebenen Eintrag aber nicht - die Menge steckte in KEINER Sicht.
 * Behoben durch Umstellung der Reihenfolge im Runner (Abschnitt "0 Zeuge",
 * vor "1 Signal"), festgehalten von `WitnessBeforeIobReadTest`. Der Nachweis
 * haengt seitdem an der Reihenfolge statt an einer Behauptung ueber sie.
 *
 * WAS DIESER VERTRAG NICHT LEISTET (ehrlich benannt): der AAPS-`iobTable`
 * kann am Schluessel `roundUpTime(sourceTs)` einen Eintrag tragen, den ein
 * FREMDER Schreiber (IobCobOref1Worker, PrepareIobAutosensGraphDataWorker)
 * vor diesem Zyklus abgelegt hat. Ist er aelter als der Zeuge und laeuft der
 * Zyklus innerhalb der 5-Sekunden-Entprellung der Entwertung
 * (IobCobCalculatorPlugin, newHistoryData), fehlt die Menge in Punkt 0.
 * Betroffen ist dann NUR `activity[0]`, also 2-3 Minutenaequivalente der
 * Wirkung in den Bahnminuten 1..5 - hoechstens 2,5 % der Menge, bei 0,3 U
 * rund 0,0075 U gegen 0,05 U Pumpenschritt. Die MENGE selbst tragen die
 * Punkte i>=1, `iobAtH` und die Kappenlesung; alle drei liegen in der Zukunft
 * bzw. am `now` und werden immer frisch gerechnet. Vollstaendig schliessen
 * liesse sich das nur, indem FUSE Punkt 0 selbst cachefrei rechnet - das ist
 * moeglich (getBolusesFromTimeToTime + BS.iobCalc), aber erst gerechtfertigt,
 * wenn die Messung es verlangt.
 *
 * ZWEITER, DAVON UNABHAENGIGER BEFUND (nicht C3-02, getrennt zu behandeln):
 * `calculateIobFromBolusToTime` liefert bei fehlendem Profil ein IobTotal mit
 * iob = 0 und activity = 0 - und dieser Nullwert wird in den Cache
 * GESCHRIEBEN. Er ist endlich, FUSE nimmt ihn also an. Das waere eine weit
 * groessere Unterberichtung als das hier gejagte Rennen.
 */
object TransportInclusion {

    /**
     * Was die Bolus-Lesung sah, die dem IOB-Arraybau VORAUSGING.
     *
     * [fromTs] ist der Fensteranfang genau dieser Abfrage - ein Fakt DAVOR ist
     * nicht "unbekannt", sondern aelter als das IOB-Fenster: seine Wirkung ist
     * in beiden Sichten ausgelaufen. Ohne diese Unterscheidung wuerde jede
     * zwischen DIA und DIA+2 h geschlossene Zeile bis zum Pruning erneut als
     * Transportmenge auftauchen.
     */
    data class IobSnapshotWitness(
        val fromTs: Long,
        val readAtTs: Long,
        val temporaryIds: Set<Long>,
        val pumpIds: Set<Long>,
    )

    fun witnessOf(facts: List<AccountedTreatment>, fromTs: Long, readAtTs: Long) = IobSnapshotWitness(
        fromTs = fromTs,
        readAtTs = readAtTs,
        temporaryIds = facts.mapNotNull { it.temporaryId }.toSet(),
        pumpIds = facts.mapNotNull { it.pumpId }.toSet(),
    )

    /** Steckt der Fakt dieses Postens NACHWEISLICH in der Lesung? Ein `false`
     *  heisst "nicht entscheidbar", nicht "nicht vorhanden". */
    fun inSnapshot(item: OpenTransportItem, witness: IobSnapshotWitness?): Boolean {
        if (witness == null) return false
        if (item.bestKnownTs < witness.fromTs || item.bestKnownTs > witness.readAtTs) return false
        val tempHit = item.temporaryId != null && item.temporaryId in witness.temporaryIds
        val pumpHit = item.pumpId != null && item.pumpId in witness.pumpIds
        return tempHit || pumpHit
    }

    /**
     * Die Menge, die dieser Posten in diesem Zyklus als Transport traegt [U].
     *
     * Ergebnis liegt IMMER in `[commitmentU, grossLiabilityU]` - die
     * Modellierung kann also nie unter den Ledgerwert fallen (kein Weg an
     * Haftung vorbei) und nie ueber die konservativ moegliche Gesamtmenge
     * steigen (keine erfundene Haftung).
     */
    fun modelledU(item: OpenTransportItem, witness: IobSnapshotWitness?): Double = when {
        // Es floss beweisbar nichts - dann haftet auch nichts.
        item.settledZero                                    -> 0.0
        // Nichts gebucht: der Ledgerwert IST die volle Haftung, es gibt gar
        // keine Menge, die in die IOB-Sicht abgewandert sein koennte.
        item.accountedAmountU <= 0.0                        -> item.commitmentU
        // Aelter als das IOB-Fenster: in beiden Sichten ausgelaufen.
        witness != null && item.bestKnownTs < witness.fromTs -> item.commitmentU
        // Nachweis vorhanden - die Buchung darf zaehlen.
        inSnapshot(item, witness)                           -> item.commitmentU
        // Nicht entscheidbar: konservativ doppelt statt unsichtbar.
        else                                                -> item.grossLiabilityU
    }
}

/**
 * EINE Stelle fuer die Abbildung BS -> Ledger-Fakt. Identitaetsbindung und
 * Vollsicht muessen aus DERSELBEN Ableitung kommen - zwei getrennte
 * Abbildungen koennten denselben Datensatz als Konflikt lesen (R83-F3:
 * pumpType/serialHash gehen in den Kompatibilitaetsvergleich ein).
 */
object LedgerFacts {

    /**
     * EIN LEERER SERIAL IST KEINE AUSSAGE UEBER DAS GERAET (Live-Befund 09.08.).
     *
     * `Sha.of("")` ist ein voellig normal aussehender Hash - und genau daran
     * ist die Reconciliation drei Tage lang blind vorbeigelaufen: es gab keinen
     * Zustand "Serial unbekannt", nur "Serial ist der Hash des leeren Strings".
     *
     * WOHER DER LEERE SERIAL KOMMT: `VirtualPumpPlugin.serialNumber()` gibt
     * `InstanceId.instanceId` zurueck, und das Feld ist nach jedem Prozessstart
     * `""`, bis die ASYNCHRONE Firebase-Antwort eintrifft
     * (`InstanceId.kt`: `FirebaseInstallations.getInstance().id.addOnCompleteListener`).
     * In diesem Fenster wird die Pumpen-Epoch eines Vorschlags mit `Sha("")`
     * gepinnt; der Bolus wird Sekunden spaeter mit dem inzwischen aufgeloesten
     * echten Serial in die Datenbank geschrieben. `matchesPinnedEpoch`
     * vergleicht dann `Sha(echt)` gegen `Sha("")`, findet NIE einen Treffer,
     * und die Zeile bindet nie - sie haelt ihre volle Haftung, bis die
     * Phantom-Abschreibung sie nach DIA plus Spanne als wirkungslos ausbucht.
     * Gemessen am Testgeraet: 6 von 169 Vorschlaegen, und JEDER davon war der
     * erste publizierte Vorschlag seiner Sitzung.
     *
     * Die Richtung des Fixes ist NICHT "weniger streng". Ein unbekannter Serial
     * ist kein ANDERER Serial - ihn als solchen zu behandeln ist keine
     * Vorsicht, sondern eine falsche Tatsachenbehauptung. Sie kostet in beide
     * Richtungen: hier eine Zeile, die nie abgeglichen wird, und in
     * [app.aaps.fuse.core.ledger.PumpTreatmentIdentity.compatibility] ein
     * `deviceConflict` gegen den eigenen Datensatz - also ein fail-closed Hold.
     * Der Pumpentyp pinnt weiter; er ist im leeren Fenster verfuegbar.
     *
     * ---
     *
     * ZWEITE AUSPRAEGUNG DERSELBEN FEHLERKLASSE: DIE SCHREIBWEISE
     * (Phase-A-Kartierung 09.08., am Produktivsystem gemessen).
     *
     * Derselbe Zahlenwert erreicht die beiden Vergleichsseiten in
     * VERSCHIEDENER Schreibweise, weil zwei Stellen des Medtrum-Treibers
     * denselben Long unterschiedlich formatieren:
     *
     *     Preference <hex mit Buchstaben>
     *       -> pumpSNFromSP = ....toLong(radix = 16)          MedtrumPump.kt:251
     *       -> serialNumber() = ...toString(16).uppercase()   MedtrumPlugin.kt:406
     *          = GROSS-Hex                                    <- FUSE pinnt Sha(dieses)
     *       -> BS.pumpSerial  = pumpSN.toString(16)           MedtrumService.kt:383
     *          = klein-Hex                                    <- Ledger vergleicht Sha(dieses)
     *
     * Der reale Serial des Produktivsystems traegt drei Hexziffern, deren
     * Schreibweise sich unterscheidet (belegt aus dem Einstellungsbildschirm
     * UND aus den Bolus-Datensaetzen im Log; der Wert selbst gehoert nicht in
     * den Quelltext - das Repository ist oeffentlich). An einer echten
     * Medtrum wuerde damit KEINE EINZIGE Zeile je binden - dauerhaft, nicht
     * nur in einem Startfenster. Wieder unsichtbar fuer jede Wertpruefung:
     * auf beiden Seiten steht ein gueltiger 64-Zeichen-Hash.
     *
     * WARUM PUMPENTYPABHAENGIG (Codex-Gegenpruefung F7): der Grund fuer die
     * Faltung ist eine MEDTRUM-Eigenschaft - dieser eine Treiber formatiert
     * denselben Long zweimal verschieden. Fuer andere Pumpen ist der
     * Identitaetsvertrag ihrer Seriennummer NICHT belegt; sie global
     * case-insensitiv zu behandeln wuerde zwei Geraete, die sich nur in der
     * Schreibweise unterscheiden, auf denselben Hash werfen. Eine unbelegte
     * Verallgemeinerung waere derselbe Fehler in neuer Richtung. Deshalb
     * faltet nur die Medtrum-Familie; alle anderen bleiben zeichengetreu.
     *
     * `lowercase()` ohne Argument ist bewusst gewaehlt - es ist die
     * locale-INVARIANTE Variante. `toLowerCase()` haette in einer
     * tuerkischen Locale aus "I" ein "i-ohne-Punkt" gemacht und denselben
     * Bruch nur verschoben.
     *
     * KOSTEN DER UMSTELLUNG, ehrlich benannt: eine Zeile, die VOR dieser
     * Aenderung mit gemischter Schreibweise gepinnt wurde und erst DANACH
     * binden soll, findet ihren Fakt nicht mehr - der persistierte Pin ist
     * ein Hash und laesst sich nicht nachtraeglich normalisieren. Betroffen
     * ist hoechstens die beim Flash gerade offene Zeile. Die Richtung ist
     * fail-closed (die Zeile haelt ihre Haftung und laeuft ueber die
     * Phantom-Abschreibung aus), nie eine Fehlbindung.
     */
    fun serialHashOf(serial: String?, pumpTypeName: String?): String? {
        val trimmed = serial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Sha.of(if (pumpTypeName in CASE_FOLDING_PUMP_TYPES) trimmed.lowercase() else trimmed)
    }

    /**
     * Pumpentypen, bei denen die SCHREIBWEISE der Seriennummer keine Aussage
     * ist - heute genau die Medtrum-Familie (s. [serialHashOf]).
     *
     * Aus dem Enum abgeleitet statt als Textliste gepflegt: eine handgefuehrte
     * Liste waere beim naechsten Medtrum-Modell still unvollstaendig, und
     * genau solche stillen Luecken sucht dieser Ledger. Die Tests in
     * `BlankSerialBindingTest` halten dagegen, dass die Ableitung wirklich
     * jeden Medtrum-Wert trifft und keinen fremden.
     */
    val CASE_FOLDING_PUMP_TYPES: Set<String> =
        PumpType.entries.asSequence().map { it.name }.filter { it.startsWith("MEDTRUM") }.toSet()

    fun pumpTypeName(b: BS): String? = b.ids.pumpType?.name

    fun serialHash(b: BS): String? = serialHashOf(b.ids.pumpSerial, pumpTypeName(b))

    fun fact(b: BS): AccountedTreatment =
        AccountedTreatment(b.ids.temporaryId, b.ids.pumpId, b.amount, pumpTypeName(b), serialHash(b), b.timestamp)

    /** Kanonischer Hash der Vollsicht: deterministisch sortiert, verlustfreie
     *  Mengenform - zwei inhaltsgleiche Sichten bekommen denselben Hash,
     *  unabhaengig von der Reihenfolge der Datenbankantwort. */
    fun snapshotHash(boluses: List<BS>): String {
        val rows = boluses
            .map {
                listOf(
                    it.timestamp.toString(),
                    it.ids.temporaryId?.toString() ?: "-",
                    it.ids.pumpId?.toString() ?: "-",
                    Sha.lossless(it.amount),
                    it.type.name,
                    pumpTypeName(it) ?: "-",
                    serialHash(it) ?: "-",
                ).joinToString(";")
            }
            .sorted()
        return Sha.of("fuse-treatment-view-v1|" + rows.joinToString("|"))
    }
}

/**
 * Die Aufrufstelle des Commitment-Ledgers im Livepfad (Audit R95, Fix 3).
 *
 * Der Reducer bleibt pur; hier liegt ausschliesslich, was eine Uhr, eine
 * Datei oder AAPS-Datentypen braucht: Laden/Persistieren, die Epoch- und
 * Generationsverwaltung der Snapshot-Ordnung, die Identitaetsbindung gegen
 * BS-Datensaetze und die Aufraeumregel.
 *
 * Was hier AUSDRUECKLICH NICHT passiert (Pflichtenheft h.7): keine
 * QueueAccepted/ExecutionResult/DeliveryProven-Ereignisse - die sind ohne
 * AAPS-Hooks nicht belegbar, und ein erfundenes Terminalereignis wuerde
 * Zeilen schliessen, die niemand nachgewiesen hat. Zeilen schliessen
 * AUSSCHLIESSLICH ueber die IOB-Reconciliation (oder verjaehren nach
 * [prune], wenn sie geschlossen und fehlerfrei sind).
 *
 * Nebenlaeufigkeit: alle Aufrufe kommen aus `FusePlugin.invoke`, und der
 * Loop serialisiert seine Durchlaeufe - der Adapter braucht deshalb keine
 * eigene Synchronisation.
 */
class FuseLedgerAdapter(private val store: FuseLedgerStore = FuseLedgerStore()) {

    companion object {

        /** Bindungsfenster ohne juengeren Vorschlag [ms]: der Loop liefert
         *  einen SMB Sekunden nach invoke() aus und verwirft ihn nach ~1 min
         *  (deliverAt-Regel). 5 min sind grosszuegig fuer eine zaehe Queue,
         *  aber eng genug, dass ein spaeterer fremder SMB nicht mehr auf eine
         *  alte Zeile passt. */
        const val BIND_WINDOW_MS = 5 * 60_000L

        /** Mengen-Toleranz der Bindung [U]: die publizierte Menge landet
         *  unveraendert im BS-Datensatz; die Toleranz faengt nur
         *  Double-Darstellungsrauschen, nie eine echte Beschneidung - eine
         *  vom Loop gekappte Menge bleibt bewusst ungebunden (konservativ:
         *  die Zeile haelt ihre volle Haftung). */
        const val BIND_AMOUNT_EPS_U = 1e-4

        /** Obergrenze der persistierten [RetiredBoundId]-Menge (Fix 6):
         *  300 juengste Eintraege decken bei 1-min-Takt Tage von SMBs ab -
         *  weit laenger als jedes Bindungsfenster leben kann. */
        const val MAX_RETIRED_BOUND_IDS = 300

        /** Hold-Gruende fuer Anzeige/Trail - Konstanten, damit Publikations-
         *  Gating (RT-reason) und view() dieselbe Vokabel sprechen. */
        const val HOLD_REASON_PERSIST_FAILED = "LEDGER_PERSIST_FAILED"
        const val HOLD_REASON_RECOVERY = "LEDGER_RECOVERY_HOLD"
        const val HOLD_REASON_STATE = "LEDGER_STATE_HOLD"
        const val HOLD_REASON_MIGRATION = "LEDGER_MIGRATION_PENDING"

        /**
         * G5 (Codex-Adjudication bae885f1): ENTRYLOSE, globale Fehler
         * (Snapshot-Ordnungskonflikt, Struktur) haben keinen Vorschlag, an dem
         * sie haengen - im holdReason standen sie bisher pauschal als
         * [HOLD_REASON_STATE], und Tab/Trail zeigten nicht, WAS gehalten wird.
         * Der Grund traegt jetzt die Fehlerliste: `LEDGER_GLOBAL_HOLD:<fehler>`.
         *
         * AUSDRUECKLICH KEIN Quittungsweg: [app.aaps.fuse.core.ledger.LedgerEvent.HoldAcknowledged]
         * ist proposal-bezogen und bleibt es - eine globale Quittung waere der
         * Reparatur-Workflow, und der ist noch nicht gebaut (K1.4). Bis dahin
         * ist ein globaler Hold nur sichtbar, nicht aufloesbar.
         */
        const val HOLD_REASON_GLOBAL = "LEDGER_GLOBAL_HOLD"
    }

    var state: LedgerState = LedgerState()
        private set

    /** Monoton je STATE-AENDERUNG, persistiert - die Ledgerrevision des
     *  Exports (R89 §360). */
    var revision: Long = 0L
        private set

    var episodes: EpisodeBudgets = EpisodeBudgets()
        private set

    /** Fix 6 (NEU-BS-02): verbrauchte Bindungs-Identitaeten geprunter
     *  Zeilen. Persistiert, gekappt auf [MAX_RETIRED_BOUND_IDS] juengste. */
    val retiredBoundIds: ArrayDeque<RetiredBoundId> = ArrayDeque()

    /** Fix 3 (Re-Audit 6.3): je Vorschlag gepinnte Pumpen-Epoch - persistiert
     *  im Codec, aufgeraeumt mit [prune]. Fehlt ein Eintrag (Altbestand vor
     *  diesem Fix), bindet die Zeile wie bisher ohne Epoch-Vergleich. */
    val proposalPumpEpochs: MutableMap<String, ProposalPumpEpoch> = mutableMapOf()

    /** REG-01a: der letzte [persistVerified] ist FEHLGESCHLAGEN - sticky bis
     *  zum naechsten Erfolg. Solange gesetzt, sperrt view().hold die
     *  Aktuation: ein Ledger, der nicht auf Platte steht, darf keine neuen
     *  Verbindlichkeiten eingehen. */
    var persistFailed: Boolean = false
        private set

    /**
     * REG-01c: beim Laden gab es eine Vorgeschichte, die nicht (vollstaendig)
     * lesbar war - entweder ALLE Generationen unlesbar (Leerstart trotz
     * Vorgeschichte) oder mindestens eine (stiller Generationsverlust).
     * Sticky fuer die Prozesslebensdauer: der Verlust verschwindet nicht
     * dadurch, dass der Prozess weiterlaeuft.
     *
     * C8d (Codex-Adjudication bae885f1): das Feld allein reichte NICHT. Es
     * lebte nur im RAM, und die Rotation des Stores haelt genau EINE
     * Vorgeneration - zwei gehaltene Zyklen ersetzten also die korrupten
     * Beweis-Generationen durch saubere leere. Nach dem Neustart existierte
     * kein invalider Kandidat mehr, der Hold fiel weg und die unbekannte
     * moegliche Abgabe war refinanzierbar. Seitdem haengt der Hold an ZWEI
     * dauerhaften Dingen auf Platte: den QUARANTAENIERTEN Generationen
     * (Beweis) und dem HOLD-MARKER [FuseLedgerStore.HOLD_NAME] (Aussage).
     */
    var recoveryHold: Boolean = false
        private set

    /**
     * Inhalt des noch NICHT durabel geschriebenen Hold-Markers (C8d).
     *
     * Gesetzt beim Setzen von [recoveryHold]; solange er nicht auf Platte
     * steht, meldet [persistVerified] false - ein Persist, dessen Verlust-
     * beweis fehlt, ist kein vollstaendiger Persist (dieselbe Logik wie beim
     * Sentinel, R4-01).
     */
    private var pendingHoldMarker: String? = null

    /**
     * Fix 1a (Re-Audit c750169, REG-03): die Uebernahme der Vorgeschichte aus
     * dem alten Verzeichnis ist FEHLGESCHLAGEN und steht noch aus. Wirkt wie
     * [recoveryHold] (kein positiver SMB), solange die Vorgeschichte nicht
     * sicher uebernommen ist - ein Leerstart waere die Behauptung, es habe
     * nie ein Commitment gegeben. Zusaetzlich stellt der Zustand [loadOnce]
     * zurueck und blockiert [persistVerified]: ein Schreiben wuerde die alte
     * Vorgeschichte mit einem Leerzustand verdecken UND den naechsten
     * Migrationsversuch blockieren (das Ziel saehe "schon belegt" aus).
     * Geloescht durch [noteMigrationDone], sobald der Umzug verifiziert ist.
     */
    var migrationPending: Boolean = false
        private set

    fun noteMigrationFailed() {
        migrationPending = true
    }

    fun noteMigrationDone() {
        migrationPending = false
    }

    private var epochId: String = ""
    private var generation: Long = 0L
    private var cfg = LedgerConfig(bolusStepU = 0.05)
    private var loaded = false

    /** Sperre = Reducer-Holds ODER fehlgeschlagene Persistenz ODER
     *  Recovery-/Migrations-Vorbehalt. Der Grund ist fuer Anzeige/Trail; bei
     *  mehreren gewinnt der handlungsleitende: erst die ausstehende
     *  Migration (ohne sie ist alles Uebrige vorlaeufig), dann Persistenz/
     *  Recovery (Reducer-Holds stehen zusaetzlich im state).
     *
     *  G5 (Codex-Adjudication bae885f1): ENTRYLOSE Fehler (Snapshot-Ordnung,
     *  Struktur) haben keinen Vorschlag, an dem sie haengen - sie werden
     *  jetzt ausdruecklich als GLOBALER Hold mit Fehlerliste benannt, statt
     *  im pauschalen [HOLD_REASON_STATE] zu verschwinden. Ein Weg, sie zu
     *  quittieren, entsteht dadurch NICHT (s. [HOLD_REASON_GLOBAL]). */
    fun view(): LedgerView {
        val globalErrors = state.activeHoldErrors.filter { it.proposalId == null }
        val reason = when {
            migrationPending    -> HOLD_REASON_MIGRATION
            persistFailed       -> HOLD_REASON_PERSIST_FAILED
            recoveryHold        -> HOLD_REASON_RECOVERY
            state.holdActuation && globalErrors.isNotEmpty() ->
                HOLD_REASON_GLOBAL + ":" + globalErrors.joinToString(",") { it.error.name }
            state.holdActuation -> HOLD_REASON_STATE
            else                -> null
        }
        return LedgerView(
            state.holdActuation || persistFailed || recoveryHold || migrationPending,
            state.transportCommitmentU,
            reason,
        )
    }

    /**
     * Restaurieren, GENAU EINMAL je Prozess, VOR dem ersten Zyklus.
     *
     * Der Leser betrachtet ALLE drei Generationen (tmp/target/bak) und
     * waehlt die juengste GUELTIGE (REG-01b: eine vollstaendige `.tmp` nach
     * Kill zwischen den Renames traegt den neuesten Vorschlag). Existierte
     * eine Vorgeschichte, die nicht oder nicht vollstaendig lesbar war, wird
     * NICHT still leer gestartet, sondern [recoveryHold] gesetzt (REG-01c):
     * "leer" waere die Behauptung, es habe nie ein Commitment gegeben. Nur
     * der echte Erststart (kein Kandidat existiert) startet ohne Hold.
     *
     * C8d (Codex-Adjudication bae885f1) macht diesen Hold DAUERHAFT:
     *  1. die ungueltigen Generationen werden SOFORT quarantaeniert
     *     (`<name>.corrupt.<ts>`) - danach kann die Rotation sie nicht mehr
     *     ueberschreiben, und ihr Inhalt bleibt als Beweis liegen;
     *  2. ein eigener Marker [FuseLedgerStore.HOLD_NAME] wird VERIFIZIERT
     *     geschrieben (Grund, Zeit, quarantaenierte Namen, letzte lesbare
     *     transportCommitmentU); schlaegt er fehl, ist der naechste
     *     [persistVerified] ungueltig;
     *  3. EXISTIERT dieser Marker beim Laden, gilt der Hold - unabhaengig
     *     davon, ob der Zustand sauber laedt.
     * Aufgeloest wird er NICHT durch Zeit, Zyklen oder Neustart; ein
     * Reparatur-Workflow dafuer ist noch nicht gebaut (fail-closed).
     *
     * Traegt der geladene Zustand eine Snapshot-Ordnung aus einer anderen
     * Epoch, wird der Epochwechsel VOR dem ersten Snapshot ANGEKUENDIGT
     * (R95-F2: eine unangekuendigte neue Epoch waere ein
     * SNAPSHOT_ORDER_CONFLICT und damit ein Dauer-Hold). Danach
     * RestartObserved: was offen und unbewiesen ist, gilt konservativ als
     * abgegeben - nicht als geloescht.
     */
    /**
     * @param activePump die JETZT aktive Pumpe. Die Migration braucht sie: eine
     *   v1-Zeile hat gar keinen Pin, und ob sie gefahrlos als Altbestand
     *   weiterbinden darf, haengt daran, WELCHE Pumpe heute laeuft.
     *
     *   Es ist ausdruecklich nicht nur der TYPNAME. Der beantwortet die Frage
     *   "haengt hier physisches Insulin dran?" nicht: an der VirtualPump ist er
     *   eine Preference (s. [FuseActivePump]). Der Vorgabewert
     *   [FuseActivePump.UNKNOWN] gilt als unbekannt und damit als Hold - eine
     *   VirtualPump anzunehmen waere geraten.
     */
    fun loadOnce(
        dir: File,
        sessionId: String,
        nowTs: Long,
        activePump: FuseActivePump = FuseActivePump.UNKNOWN,
        log: (String) -> Unit = {},
    ) {
        if (loaded) return
        // Fix 1a (REG-03): solange die Migration aussteht, wird NICHT geladen
        // und NICHT als geladen markiert - erst ein spaeterer invoke mit
        // abgeschlossener Migration darf die (dann vollstaendige)
        // Vorgeschichte restaurieren. Ein Laden des leeren Zielverzeichnisses
        // waere genau der "Erststart trotz Vorgeschichte" aus dem Re-Audit.
        if (migrationPending) return
        loaded = true
        epochId = sessionId
        val read = store.readNewestValid(dir) { text ->
            runCatching { LedgerCodec.decode(JSONObject(text)).revision }.getOrNull()
        }
        val readable = read.content?.let { runCatching { LedgerCodec.decode(JSONObject(it)) }.getOrNull() }
        // P0-B: eine Generation aus einem aelteren Schema ist LESBAR, aber
        // nicht uebernehmbar - sie darf nicht als schwaecherer Laufzeitzustand
        // durchrutschen. Sie wird hier ausdruecklich NICHT angewandt; der Hold
        // unten nennt den Grund.
        //
        // PUNKT 9: der Migrationsversuch. Er laeuft VOR der Uebernahme und
        // aendert am gelesenen Zustand nichts, was er nicht beweisen kann
        // (s. LedgerCodec.migrateToCurrent). Gelingt er nachweislich, gibt es
        // keinen Grund mehr fuer den Hold; misslingt er in irgendeinem
        // Schritt, bleibt alles wie es war und der Hold greift.
        val migriert = readable?.takeIf { it.migrationRequired != null }?.let { alt ->
            migriere(alt, dir, nowTs, activePump, log)
        }
        val decoded = migriert ?: readable?.takeIf { it.migrationRequired == null }
        if (decoded != null) {
            state = decoded.state
            revision = decoded.revision
            episodes = decoded.episodes
            retiredBoundIds.clear()
            retiredBoundIds.addAll(decoded.retiredBoundIds)
            proposalPumpEpochs.clear()
            // Nur Epochs zu tatsaechlich vorhandenen Zeilen: eine Pinnung ohne
            // Zeile ist bedeutungslos (geprunte Zeilen sperrt retiredBoundIds).
            proposalPumpEpochs.putAll(decoded.pumpEpochs.filterKeys { it in decoded.state.entries })
        }
        // C8d (3): der HOLD-MARKER gilt UNABHAENGIG davon, ob der Zustand
        // sauber laedt. Er ist der einzige Zeuge, der einen Prozesswechsel
        // ueberlebt - genau daran scheiterte der alte RAM-Hold.
        if (FuseLedgerStore.holdExists(dir)) {
            recoveryHold = true
            log(
                "FUSE ledger RECOVERY_HOLD: Hold-Marker ${FuseLedgerStore.HOLD_NAME} vorhanden - " +
                    "ein frueherer Lauf hat einen Verlust festgestellt; Aktuation bleibt zu, bis der " +
                    "(noch nicht gebaute) Reparatur-Workflow ihn aufloest (dir=$dir)"
            )
        }
        val cause = when {
            migriert != null -> null   // nachweislich migriert - kein Grund mehr

            readable?.migrationRequired != null -> {
                // Eigener Grund, NICHT "alle Generationen ungueltig": die Datei
                // ist in Ordnung, nur zu alt. Wer den falschen Grund liest,
                // sucht einen Defekt, wo eine Migration faellig ist.
                log(
                    "FUSE ledger MIGRATION_HOLD: ${readable.migrationRequired} - die Generation ist lesbar, " +
                        "wird aber nicht uebernommen; ein fehlendes Feld liesse sich nicht von einem " +
                        "gueltigen Wert unterscheiden. Aktuation bleibt zu (dir=$dir)"
                )
                "SCHEMA_MIGRATION_REQUIRED"
            }

            read.anyCandidateExisted && decoded == null -> {
                // Vorgeschichte existiert, aber KEINE Generation ist lesbar:
                // Leerstart NUR unter Recovery-Hold - moeglicherweise abgegebenes
                // Insulin darf nicht als "nie passiert" verbucht werden.
                log(
                    "FUSE ledger RECOVERY_HOLD: Generationen vorhanden, aber keine lesbar/gueltig - " +
                        "Leerstart nur mit Sperre, Aktuation bleibt zu bis zum Neustart nach Klaerung (dir=$dir)"
                )
                "ALL_GENERATIONS_INVALID"
            }

            !read.anyCandidateExisted && FuseLedgerStore.sentinelExists(dir) -> {
                // Fix 1b (Re-Audit 6.1): der SENTINEL sagt "es gab schon einen
                // Ledger", aber KEINE Generation liegt mehr da - das ist
                // DATENVERLUST, kein Erststart. Ohne den Marker waere beides
                // ununterscheidbar, und verlorene offene Haftung wuerde still
                // als "nie passiert" verbucht.
                log(
                    "FUSE ledger RECOVERY_HOLD: Sentinel vorhanden, aber keine Generation mehr lesbar - " +
                        "Datenverlust statt Erststart, Aktuation bleibt zu (dir=$dir)"
                )
                "SENTINEL_WITHOUT_GENERATION"
            }

            read.anyCandidateInvalid                   -> {
                // Eine Generation war da, aber unlesbar - stiller
                // Generationsverlust: die gewaehlte Generation kann aelter sein
                // als das zuletzt Publizierte. Konservativ: Sperre.
                log(
                    "FUSE ledger RECOVERY_HOLD: mindestens eine existierende Generation unlesbar " +
                        "(stiller Generationsverlust moeglich) - geladen wurde revision=$revision (dir=$dir)"
                )
                "SILENT_GENERATION_LOSS"
            }

            else                                       -> null
        }
        if (cause != null) {
            recoveryHold = true
            // C8d (1) QUARANTAENE: die ungueltigen Generationen SOFORT aus dem
            // Weg der Rotation nehmen. Bleiben sie unter ihrem
            // Generationsnamen liegen, ueberschreibt sie der naechste
            // (gehaltene) Persist - erst target->bak, dann bak.delete() - und
            // der Beweis ist weg.
            val quarantined = FuseLedgerStore.quarantineInvalid(read.invalidFiles, nowTs)
            if (quarantined.isNotEmpty())
                log("FUSE ledger QUARANTAENE: ${quarantined.joinToString(",")} (dir=$dir)")
            // C8d (2) DAUERHAFTER MARKER.
            val marker = JSONObject()
                .put("v", 1)
                .put("reason", cause)
                .put("ts", nowTs)
                .put("quarantined", org.json.JSONArray(quarantined))
                // Die letzte LESBARE offene Haftung - fehlt sie (nichts
                // dekodierbar), bleibt das Feld weg: unbekannt ist nicht 0.
                //
                // `readable`, NICHT `decoded`. Die beiden sind seit dem
                // Migrations-Hold verschiedene Dinge: `decoded` heisst
                // "uebernehmbar", `readable` heisst "lesbar". Genau im
                // Migrationsfall ist `decoded` absichtlich null, waehrend die
                // Menge sehr wohl bekannt ist - der Marker haette dann
                // ausgerechnet dort keine Haftungsmenge getragen, wo er sie am
                // dringendsten braucht.
                .putOpt("transportCommitmentU", readable?.state?.transportCommitmentU)
                .toString()
            if (!FuseLedgerStore.writeHoldVerified(dir, marker)) {
                pendingHoldMarker = marker
                log(
                    "FUSE ledger RECOVERY_HOLD: Hold-Marker konnte nicht geschrieben werden - " +
                        "persistVerified bleibt fail-closed, bis er auf Platte steht (dir=$dir)"
                )
            }
        }
        val oldEpoch = state.lastSnapshotOrder?.sourceEpochId
        if (oldEpoch != null && oldEpoch != sessionId)
            reduce(LedgerEvent.SnapshotSourceRestarted(oldEpoch, sessionId, "process restart"))
        reduce(LedgerEvent.RestartObserved(nowTs))
    }

    /**
     * Ein RT mit `units` hat den Prozess verlassen - ab jetzt ist die Menge
     * Schuld, bis der IOB-Snapshot sie nachweist. Proposed + RT_PUBLISHED in
     * einem Zug: mehr WEISS dieser Prozess nicht (h.7), die weiteren Stufen
     * kommen erst mit AAPS-Hooks.
     *
     * [pumpTypeName]/[pumpSerialHash] (Fix 3, Re-Audit 6.3): die beim
     * Publikationszeitpunkt aktive Pumpe, abgeleitet wie [LedgerFacts] es aus
     * dem BS-Datensatz tut (PumpType.name / Sha des Serials). Null heisst
     * "keine Aussage" - dann bindet die Zeile wie vor dem Fix.
     */
    /**
     * Loest die Reservierung dieses Zyklus auf, sobald die Publikation
     * feststeht - s. [EpisodeBudgets.pendingReservation].
     *
     * @param publishedU was WIRKLICH hinausgegangen ist. 0 heisst "das Gate
     *   hat die Menge entfernt", und nur dann wird freigegeben.
     *
     * Wird sie NICHT gerufen (Absturz, Ausnahme, fremder Pfad), bleibt die
     * Belastung stehen. Das ist der gewollte Ausgang fuer UNKNOWN.
     */
    fun resolveReservation(computeTs: Long, publishedU: Double) {
        val r = episodes.pendingReservation ?: return
        // Fremder Zyklus: nicht anfassen. Ohne diese Pruefung koennte ein
        // spaeter Aufruf die Reservierung eines ANDEREN Zyklus freigeben.
        if (r.computeTs != computeTs) return
        episodes.pendingReservation = null

        val frei = (r.amountU - (if (publishedU.isFinite()) publishedU else r.amountU)).coerceAtLeast(0.0)
        if (frei <= 0.0) return

        if (r.prime) episodes.primeSpentU = (episodes.primeSpentU - frei).coerceAtLeast(0.0)
        if (r.onset) episodes.onsetSpentU = (episodes.onsetSpentU - frei).coerceAtLeast(0.0)
        // Der Evidenz-Zaehler wird IMMER zurueckgedreht, ohne Kanal-Bedingung:
        // er zaehlt jede Abgabe der Episode, also muss er auch jede
        // abgelehnte zurueckgeben.
        //
        // ZWEI BEGRIFFE, DIE NICHT DASSELBE SIND: bliebe eine nie geflossene
        // Dosis als bezahlt stehen, waere das SICHERHEITSTECHNISCH
        // konservativ - der Bestand waere zu klein, es gaebe weniger
        // zusaetzliche Freigabe. THERAPEUTISCH ist es trotzdem falsch, und
        // die Bilanz ist verletzt: die Buecher behaupten eine Bezahlung, die
        // es nicht gab. Konservativ heisst nicht richtig.
        episodes.evidenceCommittedU = (episodes.evidenceCommittedU - frei).coerceAtLeast(0.0)
        if (r.mealTs > 0L) {
            // Den EIGENEN Eintrag zurueckdrehen, nicht den letzten: zwei
            // Zyklen koennen denselben sourceTs tragen, wenn ein Punkt
            // wiederholt wird. Gesucht wird deshalb von hinten der Eintrag
            // mit genau diesem Zeitstempel.
            val idx = episodes.mealDeliveries.indexOfLast { it.first == r.mealTs }
            if (idx >= 0) {
                val rest = episodes.mealDeliveries[idx].second - frei
                if (rest > 1e-9) episodes.mealDeliveries[idx] = r.mealTs to rest
                else episodes.mealDeliveries.removeAt(idx)
            }
        }
    }

    fun onPublished(
        proposalId: String,
        unitsU: Double,
        decisionTs: Long,
        latestBolusTs: Long,
        bolusStepU: Double,
        pumpTypeName: String? = null,
        pumpSerialHash: String? = null,
        virtualPump: Boolean? = null,
    ) {
        // Die Pumpenstufe wird je Zeile GEPINNT (R93-F1) - deshalb hier
        // aktualisieren, nicht im Konstruktor: die Pumpe steht erst zur
        // Laufzeit fest.
        if (bolusStepU.isFinite() && bolusStepU > 0.0) cfg = LedgerConfig(bolusStepU)
        // Fix 3: Pumpen-Epoch am Vorschlag pinnen, BEVOR irgendein BS-Fakt
        // binden kann - eine spaeter aktivierte andere Pumpe darf diese
        // Zeile nicht mehr treffen.
        //
        // R4-03: schlagen BEIDE API-Lesungen fehl, entsteht ein expliziter
        // UNPINNED-Pin - die Zeile bindet dann NIE und haelt konservativ ihre
        // volle Haftung. Der fruehere stille Verzicht auf die Pinnung machte
        // aus dem API-Fehler Legacy-Verhalten ("bindet alles"), und ein
        // fremder Pumpenkontext konnte die Zeile schliessen.
        //
        // REALPUMP-PUNKT (nicht Teil dieses Fixes): das echte Medtrum-
        // patchId-Pinning braucht einen Pumpen-Hook - `serialNumber()` ist
        // dort die Pumpen-SN, `patchId` ein separates Feld. Type+Serial
        // erkennen einen Patchwechsel derselben Pumpe NICHT (Codex R4-03).
        proposalPumpEpochs[proposalId] =
            if (pumpTypeName != null || pumpSerialHash != null) {
                // B3: die Patch-Epoche wird MITGEPINNT, nicht spaeter
                // nachgeschlagen. Sie ist der Zustand ZUM ZEITPUNKT DER
                // ENTSCHEIDUNG; ein spaeterer Nachschlag wuerde nach einem
                // Wechsel die neue Epoche an eine alte Zeile heften und die
                // Trennlinie genau dort aufheben, wo sie gebraucht wird.
                //
                // [virtualPump] entscheidet MIT: `MEDTRUM_NANO` an der
                // VirtualPump ist eine Preference, keine Patchpumpe (s.
                // [FuseActivePump]). Ohne diese Unterscheidung verlangte die
                // Emulation eine Epoche, faende nie eine und bliebe ewig
                // ungebunden.
                //
                // UNBEKANNT (`null`) gilt hier als NICHT emuliert. Das ist die
                // vorsichtige Richtung und NICHT die uebliche Falle: die Zeile
                // bekommt dadurch eine STRENGERE Pruefung (Epoche noetig), sie
                // erschleicht sich keine Erlaubnis. Praktisch tritt der Fall
                // ohnehin kaum auf - ist die Pumpe lesbar, ist auch ihre
                // Klasse eindeutig; ist sie es nicht, gibt es gar keinen Typ
                // und die Zeile wird UNPINNED.
                val emuliert = virtualPump == true
                val anwendbar = !emuliert && ProposalPumpEpoch.appliesTo(pumpTypeName)
                ProposalPumpEpoch(
                    pumpTypeName, pumpSerialHash,
                    patchEpochTs = if (anwendbar) currentPatchEpochTs else null,
                    patchEpochApplicable = anwendbar,
                    virtualPump = emuliert,
                )
            } else ProposalPumpEpoch.UNPINNED
        reduce(LedgerEvent.Proposed(proposalId, unitsU, decisionTs, latestBolusTs))
        reduce(LedgerEvent.AmountObserved(proposalId, AmountStage.RT_PUBLISHED, unitsU))
    }

    /**
     * Fix 3 (Re-Audit 6.3): passt der BS-Fakt zur gepinnten Pumpen-Epoch?
     *
     * Null-Toleranz mit RICHTUNG: fehlt die PINNUNG (Altbestand aus einer
     * Schemaversion-1-Datei, in-memory `null` oder expliziter
     * [ProposalPumpEpoch.LEGACY_OPEN]), gilt das bisherige Verhalten - fehlt
     * aber die BS-Identitaet, obwohl gepinnt wurde, ist das KEIN Treffer.
     * Ein Datensatz, der seine Herkunft nicht nennt, darf eine
     * herkunftsgebundene Zeile nicht schliessen; die Zeile haelt dann
     * konservativ ihre volle Haftung.
     *
     * R4-03: [ProposalPumpEpoch.UNPINNED] (API-Fehler bei der Publikation)
     * bindet NIE - auch keinen scheinbar passenden Datensatz. Ohne bekannte
     * Publikations-Epoch ist jeder Treffer eine Vermutung, und eine falsche
     * Bindung wuerde offene Haftung ueber einen fremden IOB-Fakt loeschen.
     */
    private fun matchesPinnedEpoch(pinned: ProposalPumpEpoch?, b: BS): Boolean {
        // KEINE IDENTITAET, KEINE BINDUNG AN EINER ECHTEN PUMPE
        // (Auditbefund 10.08.2026).
        //
        // `null` (v1-Zeile ohne Pin) und `legacyOpen` (ausdruecklicher
        // Altbestand) tragen WEDER Typ NOCH Serial NOCH Epoche. Frueher
        // gaben beide bedingungslos `true` - eine solche Zeile band an
        // einer echten Medtrum jeden mengengleichen SMB im Zeitfenster
        // und buchte ihre Haftung ueber einen fremden physischen Fakt
        // aus. Derselbe Freibrief, gegen den REAL_PUMP_IDENTITY_UNKNOWN
        // und der Wildcard-Riegel gebaut wurden, nur eine Ebene darueber.
        //
        // Dieselbe Regel wie beim seriallosen Pin: ohne Identitaet bindet
        // nur, wenn die Emulation NACHGEWIESEN ist. An einer echten Pumpe
        // haelt die Zeile konservativ ihre Haftung bis zur
        // Phantom-Abschreibung - das ist der Preis dafuer, dass sie nie
        // eine Identitaet bekommen hat.
        if (pinned == null || pinned.legacyOpen) return bindingContext.serialWildcardAllowed
        if (pinned.unpinned) return false
        val typeOk = pinned.pumpTypeName == null || LedgerFacts.pumpTypeName(b) == pinned.pumpTypeName
        // WILDCARD NUR BEI NACHGEWIESENER EMULATION.
        //
        // `pumpSerialHash == null` heisst "Identitaet war beim Pinnen nicht
        // bekannt" - typisch der leere Serial direkt nach einem Prozessstart
        // (InstanceId laedt asynchron nach). An der VirtualPump muss so eine
        // Zeile weiter binden koennen, sonst haelt sie ihre Haftung bis zur
        // Phantom-Abschreibung.
        //
        // Frueher stand hier "nicht reale Pumpe" - das umfasste aber auch die
        // UNBEKANNTE und die gesperrte Fremdpumpe, und ausgerechnet der
        // unbekannte Kontext haette das Tor geoeffnet. Es zaehlt der NACHWEIS.
        if (pinned.pumpSerialHash == null && !bindingContext.serialWildcardAllowed) return false
        // EINE EMULATIONSZEILE BINDET KEINEN ECHTEN FAKT.
        //
        // Das Feld wird seit B3 persistiert, aber bisher nirgends gelesen. Die
        // Richtung ist asymmetrisch und das ist Absicht: ein Pin AUS der
        // Emulation darf nichts binden, sobald die Emulation nicht mehr laeuft.
        // Der umgekehrte Fall (echter Pin, Emulation aktiv) faellt schon ueber
        // den Serialvergleich - der emulierte Bolus traegt die Serial der
        // VirtualPump. Eine strenge Gleichheit wuerde dagegen jede Altzeile
        // ohne das Feld (v1/v2, virtualPump=false) unter der Emulation sperren.
        if (pinned.virtualPump && bindingContext.virtualPump != true) return false
        val serialOk = pinned.pumpSerialHash == null || LedgerFacts.serialHash(b) == pinned.pumpSerialHash
        if (!typeOk || !serialOk) return false
        // B3: bei PATCHPUMPEN zusaetzlich die Patch-Epoche. Type und Serial
        // sind dort nicht unterscheidungskraeftig genug - die Seriennummer
        // gehoert der Basis und ueberlebt den Wechsel.
        //
        // Bei allen anderen Pumpen bleibt es beim bisherigen Verhalten. Ohne
        // diese Einschraenkung wuerde B3 den Entwicklungspfad gegen die
        // VirtualPump still mitsperren, denn dort gibt es weder Patches noch
        // CANNULA_CHANGE - die Epoche waere immer unbekannt.
        if (!pinned.patchEpochApplicable) return true
        return FusePatchEpoch.sameEpoch(pinned.patchEpochTs, currentPatchEpochTs, b.timestamp)
    }

    /**
     * Die AKTUELLE Patch-Epoche, vom Aufrufer je Zyklus gesetzt.
     *
     * Sie liegt am Adapter und nicht am Pin, weil sie sich aendert, waehrend
     * die Pins stehenbleiben - genau daran haengt die Trennlinie: nach einem
     * Wechsel unterscheidet sich die aktuelle von der gepinnten, und die alte
     * Zeile bindet nichts Neues mehr.
     *
     * `null` heisst UNBEKANNT und sperrt die Bindung fuer Patchpumpen.
     */
    /**
     * Der Pumpenkontext dieses Zyklus - EINE Groesse, vom Aufrufer gesetzt,
     * bevor gebunden wird.
     *
     * Vorgabe [LedgerPumpBindingContext.UNKNOWN]: ohne gesetzten Kontext gibt
     * es keine Epoche und kein Wildcard. Das ist die vorsichtige Seite.
     */
    var bindingContext: LedgerPumpBindingContext = LedgerPumpBindingContext.UNKNOWN
        private set

    /** Die AKTUELLE Patch-Epoche. `null` heisst UNBEKANNT und sperrt die
     *  Bindung fuer Patchpumpen. */
    val currentPatchEpochTs: Long? get() = bindingContext.patchEpochTs

    /** Vom Zyklus gesetzt, BEVOR gebunden wird. */
    fun observeBindingContext(ctx: LedgerPumpBindingContext) {
        bindingContext = ctx
    }

    /**
     * Offene Vorschlaege gegen die BS-Datensaetze binden.
     *
     * OHNE Queue-Hook ist die Bindung eine eingegrenzte Zuordnung, keine
     * Korrelation ins Blaue: SMB-Typ, Zeitfenster [decisionTs, naechster
     * Vorschlag), juenger als der bei der Entscheidung bekannte Bolus,
     * exakt die publizierte Menge, und der Datensatz darf noch an keine
     * andere Zeile gebunden sein. Bei NULL Treffern wird gewartet (naechster
     * Zyklus), bei MEHREREN wird NICHT geraten - die Zeile bleibt offen und
     * haelt konservativ ihre volle Haftung.
     */
    fun bindIdentities(boluses: List<BS>) {
        val unbound = state.entries.values.filter { it.identity == null && !it.closed }
        if (unbound.isEmpty()) return
        val decisionTimes = state.entries.values.map { it.decisionTs }.sorted()
        // Fix 6 (NEU-BS-02): auch die Identitaeten GEPRUNTER Zeilen bleiben
        // ausgeschlossen - sonst wuerde ein prune die Ausschlussmenge leeren
        // und ein bereits verbuchter Bolus koennte eine fremde Zeile binden.
        val boundTemp = (state.entries.values.mapNotNull { it.identity?.temporaryId } +
            retiredBoundIds.mapNotNull { it.temporaryId }).toMutableSet()
        val boundPump = (state.entries.values.mapNotNull { it.identity?.pumpId } +
            retiredBoundIds.mapNotNull { it.pumpId }).toMutableSet()
        for (entry in unbound.sortedBy { it.decisionTs }) {
            val amountU = entry.amounts.rtPublishedU ?: entry.amounts.proposedU
            // Obergrenze: die Entscheidung des NAECHSTEN Vorschlags, HART
            // gekappt auf BIND_WINDOW_MS (Fix 6, NEU-BS-02): faellt eine
            // Nachbarzeile dem prune zum Opfer, darf sich das Fenster nicht
            // auf Stunden aufblaehen - "der naechste Vorschlag" ist nur so
            // lange eine gueltige Grenze, wie er auch wirklich der zeitlich
            // naechste war.
            val upper = minOf(
                decisionTimes.firstOrNull { it > entry.decisionTs } ?: (entry.decisionTs + BIND_WINDOW_MS),
                entry.decisionTs + BIND_WINDOW_MS,
            )
            // Fix 3 (Re-Audit 6.3): nur Fakten der beim Proposal gepinnten
            // Pumpen-Epoch kommen ueberhaupt als Kandidaten in Frage.
            val pinned = proposalPumpEpochs[entry.proposalId]
            val hits = boluses.filter { b ->
                b.isValid && b.type == BS.Type.SMB &&
                    (b.ids.pumpId != null || b.ids.temporaryId != null) &&
                    b.timestamp >= entry.decisionTs && b.timestamp < upper &&
                    b.timestamp > entry.latestBolusTimestampAtDecision &&
                    abs(b.amount - amountU) <= BIND_AMOUNT_EPS_U &&
                    b.ids.temporaryId?.let { it in boundTemp } != true &&
                    b.ids.pumpId?.let { it in boundPump } != true &&
                    matchesPinnedEpoch(pinned, b)
            }
            if (hits.size != 1) continue
            val b = hits[0]
            b.ids.temporaryId?.let { boundTemp += it }
            b.ids.pumpId?.let { boundPump += it }
            reduce(
                LedgerEvent.PumpIdentityBound(
                    proposalId = entry.proposalId,
                    temporaryId = b.ids.temporaryId,
                    pumpId = b.ids.pumpId,
                    pumpType = LedgerFacts.pumpTypeName(b) ?: "UNKNOWN",
                    pumpSerialHash = LedgerFacts.serialHash(b) ?: "none",
                    treatmentTimestamp = b.timestamp,
                )
            )
        }
    }

    /** Die Vollsicht dieses Zyklus abgleichen. Ordnung: Prozess-Epoch plus
     *  ein monoton je Aufruf steigender Zaehler - zwei Sichten desselben
     *  Prozesses sind damit immer streng geordnet. */
    fun onCycleSnapshot(facts: List<AccountedTreatment>, snapshotHash: String, calculatedAt: Long) {
        check(loaded) { "loadOnce must run before the first snapshot" }
        generation += 1
        reduce(
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot(
                    treatmentSnapshotHash = snapshotHash,
                    treatmentCursor = "persistence.getBolusesFromTimeToTime",
                    calculatedAt = calculatedAt,
                    calculatorGeneration = generation,
                    containedTreatments = facts,
                    sourceEpochId = epochId,
                )
            )
        )
    }

    /** Fensteranfang der Vollsicht: der aelteste Fakt, der an eine OFFENE
     *  Zeile gebunden ist (Vollsicht-Vertrag R93-F3 - er darf nicht aus dem
     *  Fenster herausaltern, solange die Zeile offen ist). */
    /**
     * Wieviele Zeilen als wirkungslos abgeschrieben wurden (Phantomhaftung).
     *
     * LESEND, nicht sperrend. Sie gehoert in den Export: eine Zeile, die nach
     * DIA plus Spanne nie abgeglichen wurde, ist ein Befund ueber die
     * Abgleichung - auch wenn ihre Menge nicht mehr haftet.
     */
    fun unresolvedBeyondActionCount(): Int =
        state.entries.values.count { it.expiredBeyondAction }

    /**
     * Traegt der Ledger fuer diese proposalId eine OFFENE Zeile?
     *
     * Fuer das Publikationsgate (B0a): bevor positive units hinausgehen,
     * muss die Haftung wirklich gebucht sein - nicht nur beabsichtigt. Der
     * Weg dorthin hat mehrere stille Ausgaenge: [onPublished] wird gar nicht
     * erst gerufen (kein Vorschlag), oder `onProposed` weist ihn ab und legt
     * KEINE Zeile an (nicht-endliche Menge, unbrauchbare Policy - s.
     * [app.aaps.fuse.core.ledger.LedgerReducer]). In beiden Faellen meldete
     * das Gate bisher Erfolg, weil Ereignisse und Persist fehlerfrei liefen -
     * es hat nie gefragt, ob dabei etwas ENTSTANDEN ist.
     *
     * `!closed` gehoert dazu: eine bereits geschlossene Zeile bucht keine
     * Haftung mehr. Fuer einen soeben publizierten Vorschlag waere das ein
     * Widerspruch, und Widerspruch heisst hier nicht dosieren.
     */
    fun hasOpenProposal(proposalId: String): Boolean =
        state.entries[proposalId]?.closed == false

    fun oldestOpenTs(): Long? = state.entries.values
        .filter { !it.closed }
        .minOfOrNull { it.identity?.treatmentTimestamp ?: it.decisionTs }

    /**
     * Fensteranfang fuer die BEHANDLUNGSSICHT - und zwar ohne `closed`-Filter.
     *
     * L2 (Gegenproben-Audit 09.08.): [oldestOpenTs] filtert `!closed`, eine
     * eingeloeste Zeile ist aber `closed`. Damit fiel sie aus der
     * Fensterverlaengerung, obwohl der Reducer sie JEDEN Zyklus weiter gegen
     * die Vollsicht abgleicht - solange sie nicht geprunt ist, ist sie
     * abgleichsrelevant. Zwischen DIA+30 min (Regelfenster) und DIA+2 h
     * (Prune-Schnitt) fehlte ihr Fakt in der Sicht, der Reducer las das als
     * "Buchung verschwunden" und setzte MISSING_ACCOUNTED_TREATMENT auf eine
     * voellig korrekt gebuchte Zeile. Bei DIA 9 h ist das rund 9,5 h nach dem
     * ersten SMB - also an jedem normalen Tag.
     *
     * Massgeblich ist deshalb JEDE noch vorhandene Zeile - MIT EINER
     * AUSNAHME.
     *
     * L10: `prune` behaelt fehlertragende Zeilen ABSICHTLICH, auch die als
     * wirkungslos abgeschriebenen ([ProposalEntry.expiredBeyondAction]). Sie
     * sind Befund und gehoeren in den Export. Aber sie sind fertig: ihre Menge
     * haftet nicht mehr, sie werden nicht mehr abgeglichen, und sie duerfen
     * deshalb auch das Abfragefenster nicht mehr verankern. Sonst waechst das
     * Fenster linear mit der Laufzeit - eine einzige alte Leiche wuerde die
     * Bolusabfrage dauerhaft an ihrem `decisionTs` festnageln.
     *
     * Der Unterschied in einem Satz: aufbewahren ist nicht dasselbe wie
     * weiter beobachten.
     */
    fun oldestReconcilableTs(): Long? = state.entries.values
        .filter { !it.expiredBeyondAction }
        .minOfOrNull { it.identity?.treatmentTimestamp ?: it.decisionTs }

    /**
     * JEDER Posten einzeln - Menge, eigener Zeitstempel, Buchungsstand
     * (C3-01/C3-02, Codex Fix-Pass-5-Closure G.2/G.3).
     *
     * ADDITIV: [oldestOpenTs] und [view] bleiben unveraendert. Diese Liste ist
     * die Grundlage der Transport-Modellierung im Runner, weil ein einziger
     * Sammelbetrag an einem einzigen Anker die Resthaftung der juengeren Dosen
     * unterschlaegt.
     *
     * GESCHLOSSENE ZEILEN BLEIBEN DRIN, solange sie eine Bruttohaftung tragen:
     * genau sie sind der Fall, dessen Snapshot-Zugehoerigkeit der Runner
     * pruefen muss (C3-02). Waeren sie hier schon herausgefiltert, koennte er
     * die Luecke gar nicht mehr sehen. Zeilen mit bewiesener Nullabgabe
     * ([OpenTransportItem.settledZero]) und Zeilen ohne jede Haftung fallen
     * weg - sie haetten in keiner Rechnung einen Beitrag.
     *
     * INVARIANTE: `sumOf { commitmentU } == view().transportCommitmentU`.
     */
    fun openTransportItems(): List<OpenTransportItem> = state.entries.values
        .map { e ->
            OpenTransportItem(
                proposalId = e.proposalId,
                commitmentU = e.commitmentU,
                grossLiabilityU = e.grossLiabilityU,
                accountedAmountU = e.accountedAmountU ?: 0.0,
                bestKnownTs = e.identity?.treatmentTimestamp ?: e.decisionTs,
                temporaryId = e.identity?.temporaryId,
                pumpId = e.identity?.pumpId,
                settledZero = e.confirmedZeroEffective || e.debtReleaseEffective,
            )
        }
        .filter { it.grossLiabilityU > 0.0 && !it.settledZero }

    /**
     * Aufraeumregel (Pflichtenheft h.8): verworfen wird eine Zeile erst,
     * wenn sie geschlossen ist, keine Fehler traegt UND ihre Entscheidung
     * aelter ist als DIA + 2 h - dann kann kein IOB-Snapshot sie mehr
     * betreffen. Fehlertragende Zeilen bleiben stehen: sie sind Befund.
     */
    fun prune(nowTs: Long, diaHours: Double) {
        if (!diaHours.isFinite() || diaHours <= 0.0) return
        val cutoff = nowTs - (diaHours * 3600_000.0).toLong() - 2L * 3600_000L

        // PHANTOMHAFTUNG (Kontroll-Audit 09.08.): eine Zeile, die nach DIA plus
        // Spanne IMMER NOCH offen ist, band bisher unbegrenzt weiter Spielraum.
        // Am 09.08. waren das 0,35 U aus drei Posten vom Vorabend - 19 Stunden
        // alt, ueber `transportAnchorTs` auf 30 min zurueckgeklemmt und
        // behandelt, als koennten sie jetzt noch liefern; 0,086 U davon landeten
        // im Schwanz, das Siebenfache des damals verbliebenen Headrooms.
        //
        // Es wird NICHT behauptet, sie seien geliefert worden - nur, dass sie
        // nicht mehr WIRKEN koennen (s. ProposalEntry.expiredBeyondAction).
        //
        // B1 (Gegenproben-Audit 09.08.): das Alter zaehlt ab der ENTSCHEIDUNG
        // oder ab einem POSITIVEN Fakt - NICHT ab dem letzten Abgleich.
        // Vorher stand hier `lastReconciledAtTs`, und der Reducer schreibt den
        // auch dann, wenn der Abgleich die ABWESENHEIT des Fakts feststellt.
        // Damit hielt genau das die Zeile am Leben, was sie verfallen lassen
        // muesste: ab dem zweiten Zyklus lief der Fall durch den stillen
        // seen-Zweig, erneuerte jede Minute das Lebenszeichen, `expired` fiel
        // nie - und weil unten nur fehlerfreie Zeilen entfernt werden, blieb
        // eine fehlertragende Zeile fuer immer stehen. Ein Zustand, den FUSE
        // nur durch Loeschen einer Datei verlassen konnte.
        val expired = state.entries.mapValues { (_, e) ->
            val lastSign = maxOf(e.decisionTs, e.lastPositiveFactTs ?: e.decisionTs)
            if (e.expiredBeyondAction || e.closed || lastSign >= cutoff) e
            else e.copy(
                expiredBeyondAction = true,
                // Sichtbar, aber NICHT sperrend - eine 19 h alte Leiche darf
                // den Regler nicht stilllegen. Der Befund gehoert in den
                // Export, damit ein kaputter Abgleich auffaellt.
                errors = e.errors + LedgerError.UNRESOLVED_BEYOND_ACTION,
            )
        }
        if (expired != state.entries) {
            state = state.copy(entries = expired)
            revision += 1
        }

        val keep = state.entries.filterValues { !(it.closed && it.errors.isEmpty() && it.decisionTs < cutoff) }
        if (keep.size != state.entries.size) {
            // Fix 6 (NEU-BS-02): getragene Identitaeten der entfernten Zeilen
            // in die persistente Ausschlussmenge uebernehmen, BEVOR sie mit
            // der Zeile verschwinden.
            for (removed in state.entries.values) {
                if (removed.proposalId in keep) continue
                val id = removed.identity ?: continue
                retiredBoundIds.addLast(RetiredBoundId(id.temporaryId, id.pumpId))
                while (retiredBoundIds.size > MAX_RETIRED_BOUND_IDS) retiredBoundIds.removeFirst()
            }
            state = state.copy(entries = keep)
            // Fix 3: Epochs geprunter Zeilen mit entsorgen - ihre Bindung
            // bleibt ueber retiredBoundIds ausgeschlossen.
            proposalPumpEpochs.keys.retainAll(keep.keys)
            revision += 1
        }
    }

    /**
     * Synchron, NIE werfend, mit RUECKLESEPROBE (Audit 2d273cb, REG-01a):
     * das Ergebnis ist der Persistenzvertrag des Zyklus - nur nach `true`
     * darf der Aufrufer einen SMB publizieren. Ein Fehlschlag setzt
     * [persistFailed] (sticky bis zum naechsten Erfolg) und sperrt damit
     * auch KUENFTIGE Zyklen ueber view().hold, bis der Ledger wieder
     * durabel ist.
     *
     * Fix 1 (Re-Audit REG-03): VOR [loadOnce] wird NIE geschrieben - dieser
     * Prozess darf keine Generation ueberschreiben, die er nie gelesen hat.
     * Bei ausstehender Migration wuerde ein Leerzustand die alte
     * Vorgeschichte verdecken und den naechsten Migrationsversuch dauerhaft
     * blockieren (das Ziel saehe "schon belegt" aus).
     *
     * C8d: derselbe Vertragsgedanke gilt fuer den HOLD-MARKER. Steht er nach
     * einem Recovery-Hold noch aus, wird gar nicht erst geschrieben und der
     * Persist meldet false - eine saubere neue Generation OHNE Verlustbeweis
     * ist genau der Zustand, in dem der naechste Start nichts mehr merkt.
     *
     * R4-01 (vorher Fix 1b, tolerant): der SENTINEL ist BESTANDTEIL des
     * Persist-Erfolgs. Erfolg = writeVerified UND (Marker existiert oder
     * wurde jetzt verifiziert geschrieben). Ein Persist ohne Verlustmarker
     * meldete frueher true - ging danach die Generation verloren, sah der
     * naechste Start einen Erststart statt Datenverlust, und offene Haftung
     * verschwand still. Der Fehlschlag strippt ueber das Publikations-Gate
     * den SMB und sperrt sticky wie jeder andere Persist-Fehlschlag.
     */
    /**
     * DIE MIGRATION (Punkt 9). Gibt den uebernehmbaren Zustand zurueck - oder
     * `null`, und dann bleibt es beim Hold.
     *
     * Die Reihenfolge ist der ganze Schutz:
     *
     *  1. migrieren (rein, beweisbar, s. [LedgerCodec.migrateToCurrent])
     *  2. pruefen, dass die offene Haftung dabei NICHT kleiner geworden ist
     *  3. atomar schreiben (tmp -> target, derselbe Weg wie jeder Persist)
     *  4. die geschriebene Datei ERNEUT LESEN und validieren
     *  5. erst wenn die zurueckgelesene Generation v3-vollstaendig ist UND
     *     dieselbe Haftung traegt, gilt die Migration als gelungen
     *
     * CRASH-VERHALTEN: vor dem Austausch liegt weiter die Altgeneration -
     * beim naechsten Start wird erneut migriert. Nach dem Austausch liegt eine
     * gueltige v3-Generation - dann faellt gar keine Migration mehr an. Beide
     * Faelle sind wiederholbar, keiner braucht ein Loeschen von Hand.
     *
     * ABGESCHRIEBENE ZEILEN bleiben unangetastet erhalten: sie tragen ihren
     * Befund weiter, blockieren aber nach gelungener Migration nichts mehr -
     * der Hold haengt an der Migration, nicht an ihrer Existenz.
     */
    private fun migriere(
        alt: LedgerCodec.Decoded,
        dir: File,
        nowTs: Long,
        activePump: FuseActivePump,
        log: (String) -> Unit,
    ): LedgerCodec.Decoded? {
        val vorher = alt.state.transportCommitmentU
        // B3: Zeilen einer PATCHPUMPE ohne persistierte Patch-Epoche sind
        // nicht migrierbar. Die aktuelle rueckwirkend anzuheften waere die
        // Behauptung, seither sei kein Patch gewechselt worden - unbekannt.
        val unmigrierbar = LedgerCodec.unmigratablePatchRows(alt.state, alt.pumpEpochs, activePump)
        if (unmigrierbar.isNotEmpty()) {
            log(
                "FUSE ledger MIGRATION abgebrochen: ${unmigrierbar.size} Zeile(n) einer Patchpumpe ohne " +
                    "persistierte Patch-Epoche (${unmigrierbar.take(3).joinToString()}). Die aktuelle Epoche " +
                    "rueckwirkend anzuheften waere geraten - Hold statt Rateschluss (dir=$dir)"
            )
            return null
        }
        val neu = runCatching { LedgerCodec.migrateToCurrent(alt.state, nowTs) }.getOrNull() ?: return null
        // Die Migration darf Haftung nur ERHALTEN oder verlaengern, nie senken.
        if (!(neu.transportCommitmentU >= vorher - 1e-9)) {
            log("FUSE ledger MIGRATION abgebrochen: Haftung waere von $vorher auf ${neu.transportCommitmentU} gefallen")
            return null
        }
        val text = runCatching {
            LedgerCodec.encode(neu, alt.episodes, alt.revision, alt.retiredBoundIds, alt.pumpEpochs).toString()
        }.getOrNull() ?: return null
        if (!store.writeVerified(dir, text)) {
            log("FUSE ledger MIGRATION abgebrochen: die neue Generation liess sich nicht durabel schreiben (dir=$dir)")
            return null
        }
        // ZURUECKLESEN. Erst was von der Platte kommt und sich sauber
        // validieren laesst, zaehlt - der Zustand im Speicher beweist nichts
        // ueber die Datei.
        val zurueck = runCatching {
            val gelesen = store.readNewestValid(dir) { t -> runCatching { LedgerCodec.decode(JSONObject(t)).revision }.getOrNull() }
            gelesen.content?.let { LedgerCodec.decode(JSONObject(it)) }
        }.getOrNull()
        if (zurueck == null || zurueck.migrationRequired != null) {
            log("FUSE ledger MIGRATION abgebrochen: die zurueckgelesene Generation ist nicht v3-vollstaendig (dir=$dir)")
            return null
        }
        if (zurueck.state.transportCommitmentU < vorher - 1e-9) {
            log("FUSE ledger MIGRATION abgebrochen: zurueckgelesene Haftung ${zurueck.state.transportCommitmentU} < $vorher")
            return null
        }
        log(
            "FUSE ledger MIGRATION gelungen: ${alt.state.entries.size} Zeilen nach v${LedgerCodec.VERSION}, " +
                "Haftung $vorher unveraendert uebernommen (dir=$dir)"
        )
        return zurueck
    }

    fun persistVerified(dir: File): Boolean {
        if (!loaded) {
            persistFailed = true
            return false
        }
        // C8d (2): der HOLD-MARKER ist Vertragsbestandteil wie der Sentinel -
        // und er steht VOR dem Zustandsschreiben. Solange der Verlustbeweis
        // nicht durabel ist, entsteht auch keine neue saubere Generation:
        // genau diese Kombination (saubere Generationen ohne Verlustbeweis)
        // war der C8d-Pfad, auf dem der Hold verschwand.
        val ok = writeHoldMarkerIfPending(dir) && runCatching {
            store.writeVerified(
                dir,
                LedgerCodec.encode(state, episodes, revision, retiredBoundIds.toList(), proposalPumpEpochs.toMap()).toString(),
            )
        }.getOrDefault(false) && FuseLedgerStore.writeSentinel(dir)
        persistFailed = !ok
        return ok
    }

    /**
     * Den beim Laden entstandenen Hold-Marker nachziehen (C8d).
     *
     * Steht nichts aus, ist das Ergebnis true - der Normalfall kostet nichts.
     * Ein noch ausstehender Marker wird bei JEDEM Persist erneut versucht;
     * bis er liegt, ist der Persist ungueltig (fail-closed).
     *
     * Der Marker wird hier NIE geloescht - auch nicht nach einem gelungenen
     * Persist. Es gibt bewusst noch keinen Aufloesungsweg; er gehoert in den
     * eigenen Reparatur-Workflow (Adjudication K1.1/G4).
     */
    private fun writeHoldMarkerIfPending(dir: File): Boolean {
        val marker = pendingHoldMarker ?: return true
        val ok = FuseLedgerStore.writeHoldVerified(dir, marker)
        if (ok) pendingHoldMarker = null
        return ok
    }

    private fun reduce(e: LedgerEvent) {
        val next = LedgerReducer.reduce(state, e, cfg)
        if (next !== state) {
            state = next
            revision += 1
        }
    }
}
