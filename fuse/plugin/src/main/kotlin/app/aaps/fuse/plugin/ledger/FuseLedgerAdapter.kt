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
    val mealDeliveries: ArrayDeque<Pair<Long, Double>> = ArrayDeque()
}

/** Was der Zyklus vom Ledger sieht: Sperre und gebundene Transportmenge. */
data class LedgerView(val hold: Boolean, val transportCommitmentU: Double)

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
    }

    var state: LedgerState = LedgerState()
        private set

    /** Monoton je STATE-AENDERUNG, persistiert - die Ledgerrevision des
     *  Exports (R89 §360). */
    var revision: Long = 0L
        private set

    var episodes: EpisodeBudgets = EpisodeBudgets()
        private set

    private var epochId: String = ""
    private var generation: Long = 0L
    private var cfg = LedgerConfig(bolusStepU = 0.05)
    private var loaded = false

    fun view(): LedgerView = LedgerView(state.holdActuation, state.transportCommitmentU)

    /**
     * Restaurieren, GENAU EINMAL je Prozess, VOR dem ersten Zyklus.
     *
     * Traegt der geladene Zustand eine Snapshot-Ordnung aus einer anderen
     * Epoch, wird der Epochwechsel VOR dem ersten Snapshot ANGEKUENDIGT
     * (R95-F2: eine unangekuendigte neue Epoch waere ein
     * SNAPSHOT_ORDER_CONFLICT und damit ein Dauer-Hold). Danach
     * RestartObserved: was offen und unbewiesen ist, gilt konservativ als
     * abgegeben - nicht als geloescht.
     */
    fun loadOnce(dir: File, sessionId: String, nowTs: Long) {
        if (loaded) return
        loaded = true
        epochId = sessionId
        // Kandidaten in Vertrauensreihenfolge; die erste decodierbare gewinnt.
        // Beide unlesbar -> leerer Start (dokumentierter Datenverlust, kein
        // geratener Zustand).
        for (text in store.read(dir)) {
            val decoded = runCatching { LedgerCodec.decode(JSONObject(text)) }.getOrNull() ?: continue
            state = decoded.state
            revision = decoded.revision
            episodes = decoded.episodes
            break
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
     */
    fun onPublished(proposalId: String, unitsU: Double, decisionTs: Long, latestBolusTs: Long, bolusStepU: Double) {
        // Die Pumpenstufe wird je Zeile GEPINNT (R93-F1) - deshalb hier
        // aktualisieren, nicht im Konstruktor: die Pumpe steht erst zur
        // Laufzeit fest.
        if (bolusStepU.isFinite() && bolusStepU > 0.0) cfg = LedgerConfig(bolusStepU)
        reduce(LedgerEvent.Proposed(proposalId, unitsU, decisionTs, latestBolusTs))
        reduce(LedgerEvent.AmountObserved(proposalId, AmountStage.RT_PUBLISHED, unitsU))
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
        val boundTemp = state.entries.values.mapNotNull { it.identity?.temporaryId }.toMutableSet()
        val boundPump = state.entries.values.mapNotNull { it.identity?.pumpId }.toMutableSet()
        for (entry in unbound.sortedBy { it.decisionTs }) {
            val amountU = entry.amounts.rtPublishedU ?: entry.amounts.proposedU
            // Obergrenze: die Entscheidung des NAECHSTEN Vorschlags. Ein Bolus
            // danach kann nur noch zu dessen Zeile gehoeren - das macht die
            // Fenster disjunkt und die Zuordnung deterministisch.
            val upper = decisionTimes.firstOrNull { it > entry.decisionTs }
                ?: (entry.decisionTs + BIND_WINDOW_MS)
            val hits = boluses.filter { b ->
                b.isValid && b.type == BS.Type.SMB &&
                    (b.ids.pumpId != null || b.ids.temporaryId != null) &&
                    b.timestamp >= entry.decisionTs && b.timestamp < upper &&
                    b.timestamp > entry.latestBolusTimestampAtDecision &&
                    abs(b.amount - amountU) <= BIND_AMOUNT_EPS_U &&
                    b.ids.temporaryId?.let { it in boundTemp } != true &&
                    b.ids.pumpId?.let { it in boundPump } != true
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
            state = state.copy(entries = keep)
            revision += 1
        }
    }

    /** Synchron, nie werfend. Ein fehlgeschlagenes Schreiben laesst die
     *  letzte Generation stehen - schlechter als frisch, besser als kaputt. */
    fun persist(dir: File) {
        runCatching { store.write(dir, LedgerCodec.encode(state, episodes, revision).toString()) }
    }

    private fun reduce(e: LedgerEvent) {
        val next = LedgerReducer.reduce(state, e, cfg)
        if (next !== state) {
            state = next
            revision += 1
        }
    }
}
