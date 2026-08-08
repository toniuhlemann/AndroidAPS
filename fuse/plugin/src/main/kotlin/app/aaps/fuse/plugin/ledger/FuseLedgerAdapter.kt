package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.core.ledger.AmountStage
import app.aaps.fuse.core.ledger.IobAccountingSnapshot
import app.aaps.fuse.core.ledger.LedgerConfig
import app.aaps.fuse.core.ledger.LedgerEvent
import app.aaps.fuse.core.ledger.LedgerReducer
import app.aaps.fuse.core.ledger.LedgerState
import app.aaps.fuse.core.util.Sha
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

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
    val mealDeliveries: ArrayDeque<Pair<Long, Double>> = ArrayDeque()
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
 */
data class ProposalPumpEpoch(val pumpTypeName: String?, val pumpSerialHash: String?)

/** Was der Zyklus vom Ledger sieht: Sperre (mit Grund fuer Anzeige/Trail)
 *  und gebundene Transportmenge. */
data class LedgerView(val hold: Boolean, val transportCommitmentU: Double, val holdReason: String? = null)

/**
 * EINE Stelle fuer die Abbildung BS -> Ledger-Fakt. Identitaetsbindung und
 * Vollsicht muessen aus DERSELBEN Ableitung kommen - zwei getrennte
 * Abbildungen koennten denselben Datensatz als Konflikt lesen (R83-F3:
 * pumpType/serialHash gehen in den Kompatibilitaetsvergleich ein).
 */
object LedgerFacts {

    fun pumpTypeName(b: BS): String? = b.ids.pumpType?.name

    fun serialHash(b: BS): String? = b.ids.pumpSerial?.let { Sha.of(it) }

    fun fact(b: BS): AccountedTreatment =
        AccountedTreatment(b.ids.temporaryId, b.ids.pumpId, b.amount, pumpTypeName(b), serialHash(b))

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

    /** REG-01c: beim Laden gab es eine Vorgeschichte, die nicht (vollstaendig)
     *  lesbar war - entweder ALLE Generationen unlesbar (Leerstart trotz
     *  Vorgeschichte) oder mindestens eine (stiller Generationsverlust).
     *  Sticky fuer die Prozesslebensdauer: der Verlust verschwindet nicht
     *  dadurch, dass der Prozess weiterlaeuft. */
    var recoveryHold: Boolean = false
        private set

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
     *  Recovery (Reducer-Holds stehen zusaetzlich im state). */
    fun view(): LedgerView {
        val reason = when {
            migrationPending    -> HOLD_REASON_MIGRATION
            persistFailed       -> HOLD_REASON_PERSIST_FAILED
            recoveryHold        -> HOLD_REASON_RECOVERY
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
     * Traegt der geladene Zustand eine Snapshot-Ordnung aus einer anderen
     * Epoch, wird der Epochwechsel VOR dem ersten Snapshot ANGEKUENDIGT
     * (R95-F2: eine unangekuendigte neue Epoch waere ein
     * SNAPSHOT_ORDER_CONFLICT und damit ein Dauer-Hold). Danach
     * RestartObserved: was offen und unbewiesen ist, gilt konservativ als
     * abgegeben - nicht als geloescht.
     */
    fun loadOnce(dir: File, sessionId: String, nowTs: Long, log: (String) -> Unit = {}) {
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
        val decoded = read.content?.let { runCatching { LedgerCodec.decode(JSONObject(it)) }.getOrNull() }
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
        if (read.anyCandidateExisted && decoded == null) {
            // Vorgeschichte existiert, aber KEINE Generation ist lesbar:
            // Leerstart NUR unter Recovery-Hold - moeglicherweise abgegebenes
            // Insulin darf nicht als "nie passiert" verbucht werden.
            recoveryHold = true
            log(
                "FUSE ledger RECOVERY_HOLD: Generationen vorhanden, aber keine lesbar/gueltig - " +
                    "Leerstart nur mit Sperre, Aktuation bleibt zu bis zum Neustart nach Klaerung (dir=$dir)"
            )
        } else if (!read.anyCandidateExisted && FuseLedgerStore.sentinelExists(dir)) {
            // Fix 1b (Re-Audit 6.1): der SENTINEL sagt "es gab schon einen
            // Ledger", aber KEINE Generation liegt mehr da - das ist
            // DATENVERLUST, kein Erststart. Ohne den Marker waere beides
            // ununterscheidbar, und verlorene offene Haftung wuerde still
            // als "nie passiert" verbucht.
            recoveryHold = true
            log(
                "FUSE ledger RECOVERY_HOLD: Sentinel vorhanden, aber keine Generation mehr lesbar - " +
                    "Datenverlust statt Erststart, Aktuation bleibt zu (dir=$dir)"
            )
        } else if (read.anyCandidateInvalid) {
            // Eine Generation war da, aber unlesbar - stiller
            // Generationsverlust: die gewaehlte Generation kann aelter sein
            // als das zuletzt Publizierte. Konservativ: Sperre.
            recoveryHold = true
            log(
                "FUSE ledger RECOVERY_HOLD: mindestens eine existierende Generation unlesbar " +
                    "(stiller Generationsverlust moeglich) - geladen wurde revision=$revision (dir=$dir)"
            )
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
    fun onPublished(
        proposalId: String,
        unitsU: Double,
        decisionTs: Long,
        latestBolusTs: Long,
        bolusStepU: Double,
        pumpTypeName: String? = null,
        pumpSerialHash: String? = null,
    ) {
        // Die Pumpenstufe wird je Zeile GEPINNT (R93-F1) - deshalb hier
        // aktualisieren, nicht im Konstruktor: die Pumpe steht erst zur
        // Laufzeit fest.
        if (bolusStepU.isFinite() && bolusStepU > 0.0) cfg = LedgerConfig(bolusStepU)
        // Fix 3: Pumpen-Epoch am Vorschlag pinnen, BEVOR irgendein BS-Fakt
        // binden kann - eine spaeter aktivierte andere Pumpe darf diese
        // Zeile nicht mehr treffen.
        if (pumpTypeName != null || pumpSerialHash != null)
            proposalPumpEpochs[proposalId] = ProposalPumpEpoch(pumpTypeName, pumpSerialHash)
        reduce(LedgerEvent.Proposed(proposalId, unitsU, decisionTs, latestBolusTs))
        reduce(LedgerEvent.AmountObserved(proposalId, AmountStage.RT_PUBLISHED, unitsU))
    }

    /**
     * Fix 3 (Re-Audit 6.3): passt der BS-Fakt zur gepinnten Pumpen-Epoch?
     *
     * Null-Toleranz mit RICHTUNG: fehlt die PINNUNG (Altbestand vor dem Fix),
     * gilt das bisherige Verhalten - fehlt aber die BS-Identitaet, obwohl
     * gepinnt wurde, ist das KEIN Treffer. Ein Datensatz, der seine Herkunft
     * nicht nennt, darf eine herkunftsgebundene Zeile nicht schliessen; die
     * Zeile haelt dann konservativ ihre volle Haftung.
     */
    private fun matchesPinnedEpoch(pinned: ProposalPumpEpoch?, b: BS): Boolean {
        if (pinned == null) return true
        val typeOk = pinned.pumpTypeName == null || LedgerFacts.pumpTypeName(b) == pinned.pumpTypeName
        val serialOk = pinned.pumpSerialHash == null || LedgerFacts.serialHash(b) == pinned.pumpSerialHash
        return typeOk && serialOk
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
    fun oldestOpenTs(): Long? = state.entries.values
        .filter { !it.closed }
        .minOfOrNull { it.identity?.treatmentTimestamp ?: it.decisionTs }

    /**
     * Aufraeumregel (Pflichtenheft h.8): verworfen wird eine Zeile erst,
     * wenn sie geschlossen ist, keine Fehler traegt UND ihre Entscheidung
     * aelter ist als DIA + 2 h - dann kann kein IOB-Snapshot sie mehr
     * betreffen. Fehlertragende Zeilen bleiben stehen: sie sind Befund.
     */
    fun prune(nowTs: Long, diaHours: Double) {
        if (!diaHours.isFinite() || diaHours <= 0.0) return
        val cutoff = nowTs - (diaHours * 3600_000.0).toLong() - 2L * 3600_000L
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
     * Fix 1b: nach jedem Erfolg wird der SENTINEL tolerant nachgezogen -
     * fehlt er (Altbestand vor dem Fix), traegt ihn der naechste Persist nach.
     */
    fun persistVerified(dir: File): Boolean {
        if (!loaded) {
            persistFailed = true
            return false
        }
        val ok = runCatching {
            store.writeVerified(
                dir,
                LedgerCodec.encode(state, episodes, revision, retiredBoundIds.toList(), proposalPumpEpochs.toMap()).toString(),
            )
        }.getOrDefault(false)
        persistFailed = !ok
        if (ok) FuseLedgerStore.writeSentinelTolerant(dir)
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
