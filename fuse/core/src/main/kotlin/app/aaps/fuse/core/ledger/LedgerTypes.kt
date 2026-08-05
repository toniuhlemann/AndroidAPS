package app.aaps.fuse.core.ledger

import kotlin.math.abs
import kotlin.math.floor

/**
 * Datentypen des Commitment-Ledgers (K2-C v0.3 §2-§4 + v0.3.1 C2/C3/C4/C6/C7).
 *
 * DIE EINE REGEL, aus der alles Uebrige folgt:
 *
 *     Was FUSE VORSCHLAEGT, was der Treiber ANFORDERT, was in IOB BILANZIERT
 *     und was physisch GELIEFERT wurde, sind VIER verschiedene Dinge.
 *
 * Der teuerste Fehler dieser Reviewrunde kam genau daher: `MedtrumPlugin`
 * setzt `enacted` NIE auf true (verifiziert: 0 Treffer in der Datei), sondern
 * meldet `success=true` und `bolusDelivered=0,25`. Eine Terminaltabelle, die
 * `success && !enacted` als "nichts geliefert" buchte, haette den Ledger
 * geleert und den naechsten Zyklus dieselbe Menge erneut freigeben lassen.
 * Auf der VirtualPump waere es nie aufgefallen — die setzt `enacted` selbst.
 * Deshalb ist fuer BOLI `bolusDelivered` die primaere Mengenwahrheit.
 */

/** Persistenzphasen (v0.3 §10). "Proposal existiert" ist NICHT "abgegeben". */
enum class LedgerPhase { PREPARED, PUBLISHED, QUEUE_ACCEPTED, TERMINAL }

/** Achse 1: steckt die Menge im AAPS-IOB? NUR hier endet das Commitment. */
enum class AccountingState { NOT_ACCOUNTED, IOB_ACCOUNTED }

/** Achse 2: was ist physisch geflossen? Laeuft unabhaengig weiter und kann
 *  spaeter eine KORREKTUR ausloesen — nie eine zweite Dosis. */
enum class DeliveryState {
    UNKNOWN,               // noch kein Terminalereignis
    UNKNOWN_ASSUMED,       // Ausgang nicht beweisbar -> konservativ als abgegeben gefuehrt
    REPORTED_PARTIAL,
    REPORTED_FULL,
    CONFIRMED_ZERO,        // NUR mit explizitem Pump-/History-Nachweis
    OVERDELIVERY_ANOMALY,  // mehr geliefert als kommandiert
}

enum class QueueRejectReason {
    CONSTRAINT_ZERO,     // v0.3.1 C4: Queue constrained auf < bolusStep
    TREATMENT_CHANGED,   // v0.3.1 C5: lastKnownBolusTime-Guard
    BOLUS_IN_QUEUE,
    GATE_BLOCKED,
    OTHER,
}

enum class LedgerError {
    UNKNOWN_PROPOSAL,          // Ereignis zu einer Id, die nie vorgeschlagen wurde
    DUPLICATE_PROPOSAL,        // dieselbe Id zweimal mit anderem Inhalt vorgeschlagen
    CONSTRAINT_CHAIN_INVALID,  // Stufeninvariante verletzt (C4)
    CONFLICTING_STAGE_AMOUNT,  // dieselbe Stufe zweimal mit anderer Menge
    PHASE_VIOLATION,           // z.B. QueueAccepted nach Constraint auf Null
    DUPLICATE_TERMINAL,        // zweites, abweichendes Terminalergebnis
    IDENTITY_CONFLICT,         // widersprechende temporaryId/pumpId
    PROPOSAL_ID_LOST,          // C6: Identitaet auf dem Weg verloren
    OVERDELIVERY_ANOMALY,      // C3
    NON_FINITE_AMOUNT,
    ACCOUNTING_WITHOUT_IDENTITY,
}

data class LedgerErrorRecord(val proposalId: String?, val error: LedgerError, val detail: String)

/**
 * Pumpenidentitaet OHNE `temporaryId`-Zwang (v0.3.1 C2, R77-F1).
 *
 * Verifiziert: `VirtualPumpPlugin.deliverTreatment()` schreibt direkt ueber
 * `syncBolusWithPumpId(... pumpId = dateUtil.now() ...)` — einen Medtrum-artigen
 * Zwischenzustand gibt es dort nicht. Eine Identitaet, die `temporaryId`
 * voraussetzt, waere auf der einzigen fuer Alpha 1 zugelassenen Pumpe nie
 * erfuellbar.
 */
data class PumpTreatmentIdentity(
    val proposalId: String,
    val temporaryId: Long?,
    val pumpId: Long?,
    val pumpType: String,
    val pumpSerialHash: String,
    val treatmentTimestamp: Long,
) {

    init {
        require(temporaryId != null || pumpId != null) { "identity needs temporaryId or pumpId" }
    }

    /** Medtrum: erst temporaryId, spaeter pumpId. Die Zusammenfuehrung ist ein
     *  EIGENES Bindungsereignis, keine Korrelation ueber Zeit- und Mengennaehe. */
    fun mergedWith(other: PumpTreatmentIdentity): PumpTreatmentIdentity? {
        if (other.proposalId != proposalId) return null
        if (temporaryId != null && other.temporaryId != null && temporaryId != other.temporaryId) return null
        if (pumpId != null && other.pumpId != null && pumpId != other.pumpId) return null
        if (pumpSerialHash != other.pumpSerialHash || pumpType != other.pumpType) return null
        return copy(
            temporaryId = temporaryId ?: other.temporaryId,
            pumpId = pumpId ?: other.pumpId,
        )
    }

    fun matches(temporaryId: Long?, pumpId: Long?): Boolean =
        (this.temporaryId != null && this.temporaryId == temporaryId) ||
            (this.pumpId != null && this.pumpId == pumpId)
}

/** Die Mengenachse (v0.3 §2). Die Bolusmenge wird DREIMAL beschnitten —
 *  im Loop, in der Queue und noch einmal im Treiber. */
enum class AmountStage { PROPOSED, RT_PUBLISHED, LOOP_CONSTRAINED, QUEUE_CONSTRAINED, PUMP_COMMAND }

data class AmountAxis(
    val proposedU: Double,
    val rtPublishedU: Double? = null,
    val loopConstrainedU: Double? = null,
    val queueConstrainedU: Double? = null,
    val pumpCommandU: Double? = null,
    val reportedDeliveredU: Double? = null,
    val provenDeliveredU: Double? = null,
    val dbAccountedU: Double? = null,
) {

    fun stage(s: AmountStage): Double? = when (s) {
        AmountStage.PROPOSED          -> proposedU
        AmountStage.RT_PUBLISHED      -> rtPublishedU
        AmountStage.LOOP_CONSTRAINED  -> loopConstrainedU
        AmountStage.QUEUE_CONSTRAINED -> queueConstrainedU
        AmountStage.PUMP_COMMAND      -> pumpCommandU
    }

    fun withStage(s: AmountStage, v: Double): AmountAxis = when (s) {
        AmountStage.PROPOSED          -> copy(proposedU = v)
        AmountStage.RT_PUBLISHED      -> copy(rtPublishedU = v)
        AmountStage.LOOP_CONSTRAINED  -> copy(loopConstrainedU = v)
        AmountStage.QUEUE_CONSTRAINED -> copy(queueConstrainedU = v)
        AmountStage.PUMP_COMMAND      -> copy(pumpCommandU = v)
    }

    /** Die JUENGSTE BEKANNTE Kommandomenge — Bezugsgroesse fuer Budget und
     *  FULL/PARTIAL, solange die Lieferung unbekannt ist (v0.3 §2). */
    val latestKnownCommandU: Double
        get() = pumpCommandU ?: queueConstrainedU ?: loopConstrainedU ?: rtPublishedU ?: proposedU
}

/**
 * IOB-Snapshot MIT Treatment-Provenienz (v0.3.1 C7, R77-F2).
 *
 * Eine beliebige neue IOB-Revision beweist nichts — sie kann von fremden
 * Treatments oder blossem Zeitfortschritt stammen. `IOB_ACCOUNTED` gilt erst,
 * wenn GENAU DIESE Provenienz den Datensatz mit temporaryId/pumpId und Menge
 * umfasst.
 *
 * Der pure Reducer bekommt den Typ hier bereits; die AAPS-seitige Quelle
 * (Provenienz beim Cache-Aufbau erzeugen ODER IOB aus derselben eingefrorenen
 * Treatment-Liste rechnen) ist noch nicht entschieden und bleibt gesperrt.
 */
data class AccountedTreatment(val temporaryId: Long?, val pumpId: Long?, val amountU: Double)

data class IobAccountingSnapshot(
    val treatmentSnapshotHash: String,
    val treatmentCursor: String,
    val calculatedAt: Long,
    val calculatorGeneration: Long,
    val containedTreatments: List<AccountedTreatment>,
)

/** Ein Vorschlag mit allem, was ueber ihn bewiesen ist. */
data class ProposalEntry(
    val proposalId: String,
    val phase: LedgerPhase,
    val amounts: AmountAxis,
    val accounting: AccountingState,
    val delivery: DeliveryState,
    val identity: PumpTreatmentIdentity?,
    val queueReject: QueueRejectReason?,
    val terminalSeen: Boolean,
    val failClosed: Boolean,
    val corrections: Int,
    val decisionTs: Long,
    val latestBolusTimestampAtDecision: Long,
    val errors: List<LedgerError>,
    val accountedSnapshotHash: String?,
) {

    /** Nichts mehr offen: es floss beweisbar nichts, oder die Menge steckt im IOB. */
    val closed: Boolean
        get() = queueReject != null || delivery == DeliveryState.CONFIRMED_ZERO || accounting == AccountingState.IOB_ACCOUNTED

    /**
     * Was diese Zeile noch AUSSERHALB des IOB bindet.
     *
     * Sie verlaesst das Commitment AUSSCHLIESSLICH ueber die Accounting-Achse
     * (oder mit Beweis, dass nichts floss). Ein Zeitablauf ist KEIN Beweis —
     * v0.1 liess den 20-min-Ablauf beide Groessen freigeben und haette damit
     * eine noch unsichtbare Dosis aus allen Grenzen entfernt.
     */
    val commitmentU: Double
        get() = when {
            queueReject != null                        -> 0.0
            delivery == DeliveryState.CONFIRMED_ZERO   -> 0.0
            accounting == AccountingState.IOB_ACCOUNTED -> 0.0
            provenExactly != null                      -> provenExactly!!
            else                                       -> maxOf(amounts.latestKnownCommandU, amounts.reportedDeliveredU ?: 0.0)
        }

    private val provenExactly: Double? get() = amounts.provenDeliveredU
}

data class LedgerState(
    val entries: Map<String, ProposalEntry> = emptyMap(),
    val errors: List<LedgerErrorRecord> = emptyList(),
) {

    /** Summe aller Mengen, die noch nicht im IOB-Snapshot stecken. Geht in
     *  effectiveCapIob, effectiveNetIob UND in die Kandidatenbahn ein — nicht
     *  nur ins Budget (R75-F3). */
    val transportCommitmentU: Double get() = entries.values.sumOf { it.commitmentU }

    /** Ein Vertragsbruch sperrt die Aktuation, statt sie still weiterlaufen zu
     *  lassen. "Ungetrackter normaler SMB" waere genau die Umgehung des Ledgers.
     *
     *  Auch Fehler OHNE zugehoerige Zeile sperren: ein Ereignis zu einer
     *  unbekannten proposalId heisst, dass die Zuordnung irgendwo verloren ging —
     *  dann weiss der Ledger gerade nicht mehr, was draussen unterwegs ist. */
    val holdActuation: Boolean
        get() = entries.values.any { it.failClosed } || errors.any { it.error in FAIL_CLOSED_ERRORS }

    companion object {

        val FAIL_CLOSED_ERRORS = setOf(
            LedgerError.UNKNOWN_PROPOSAL,
            LedgerError.DUPLICATE_PROPOSAL,
            LedgerError.CONSTRAINT_CHAIN_INVALID,
            LedgerError.CONFLICTING_STAGE_AMOUNT,
            LedgerError.PHASE_VIOLATION,
            LedgerError.DUPLICATE_TERMINAL,
            LedgerError.IDENTITY_CONFLICT,
            LedgerError.PROPOSAL_ID_LOST,
            LedgerError.OVERDELIVERY_ANOMALY,
            LedgerError.NON_FINITE_AMOUNT,
            LedgerError.ACCOUNTING_WITHOUT_IDENTITY,
        )
    }

    val openEntries: List<ProposalEntry> get() = entries.values.filter { !it.closed }
}

/** Reine Rechenregeln, die auch der spaetere Adapter benutzt — damit sie GENAU
 *  EINMAL existieren und schon heute auf der JVM pruefbar sind. */
object LedgerRules {

    private const val TICK_EPS = 1e-9

    /**
     * Mengen in PUMPENSTUFEN statt in Doubles (v0.3.1 C3, R77-F3).
     *
     * Eine ganze `bolusStep` als FULL-Toleranz waere zu grob: bei einer
     * 0,05-U-Pumpe koennte eine um fast eine volle Stufe kleinere Abgabe noch
     * als FULL gelten. Die Epsilon-Toleranz existiert NUR vor der
     * Kanonisierung, nie als ganze Stufe.
     */
    fun canonicalTicks(amountU: Double, bolusStepU: Double): Long {
        require(bolusStepU > 0.0) { "bolusStep must be positive" }
        require(amountU.isFinite()) { "amount not finite" }
        val raw = amountU / bolusStepU
        return floor(raw + 0.5 + TICK_EPS).toLong()
    }

    /**
     * Die Queue reiht heute auch eine auf 0 constrainte Menge ein
     * (`CommandQueueImplementation.bolus()` prueft nach `applyBolusConstraints`
     * NICHT erneut auf > 0), und der Treiber scheitert danach an
     * `require(detailedBolusInfo.insulin > 0)`. Deshalb muss FUSE selbst
     * terminal abfangen (C4).
     */
    fun queueWouldRejectAsZero(queueConstrainedU: Double, bolusStepU: Double): Boolean =
        !queueConstrainedU.isFinite() || queueConstrainedU < bolusStepU

    /** Stufeninvariante ueber die gesamte Kette (C4). Eine Verletzung ist kein
     *  normaler Mengenwechsel, sondern ein Vertragsbruch. */
    fun chainViolation(axis: AmountAxis, epsU: Double = 1e-9): String? {
        var previous: Double? = null
        var previousStage: AmountStage? = null
        for (stage in AmountStage.entries) {
            val v = axis.stage(stage) ?: continue
            if (!v.isFinite() || v < -epsU) return "$stage=$v"
            val p = previous
            if (p != null && v > p + epsU) return "$previousStage=$p < $stage=$v"
            previous = v
            previousStage = stage
        }
        return null
    }

    /** Terminalklassifikation eines BOLUS (v0.3 §0.1 + C3). */
    fun classifyDelivery(commandU: Double, reportedDeliveredU: Double?, bolusStepU: Double): DeliveryState {
        if (reportedDeliveredU == null || !reportedDeliveredU.isFinite()) return DeliveryState.UNKNOWN_ASSUMED
        val commandTicks = canonicalTicks(commandU, bolusStepU)
        val deliveredTicks = canonicalTicks(reportedDeliveredU, bolusStepU)
        return when {
            deliveredTicks > commandTicks  -> DeliveryState.OVERDELIVERY_ANOMALY
            deliveredTicks == commandTicks -> DeliveryState.REPORTED_FULL
            deliveredTicks > 0L            -> DeliveryState.REPORTED_PARTIAL
            // 0 Stufen ist NICHT automatisch "nichts geliefert" — ohne Nachweis
            // bleibt der Ausgang unbekannt und die Menge gebucht.
            else                           -> DeliveryState.UNKNOWN_ASSUMED
        }
    }

    fun sameAmount(a: Double?, b: Double?, epsU: Double = 1e-9): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else                   -> abs(a - b) <= epsU
    }
}
