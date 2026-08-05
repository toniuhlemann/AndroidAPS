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
    /** Zwei NACHWEISE widersprechen sich (R87-F1): bestaetigte Nullabgabe und
     *  ein bilanzierter Datensatz mit positiver Menge. Kein normaler
     *  Zustandswechsel, sondern ein unmoeglicher Zustand. */
    IMPOSSIBLE_STATE_CONFLICT,
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

    /**
     * Medtrum: erst temporaryId, spaeter pumpId. Die Zusammenfuehrung ist ein
     * EIGENES Bindungsereignis, keine Korrelation ueber Zeit- und Mengennaehe.
     *
     * `treatmentTimestamp` DARF sich dabei aendern, und das ist kein Konflikt:
     * `SyncBolusWithTempIdTransaction.run()` ueberschreibt beim Binden der
     * pumpId sowohl `timestamp` als auch `amount` des vorhandenen Datensatzes.
     * Die spaetere, pumpenbestaetigte Zeit gewinnt; die Abweichung wird als
     * KORREKTUR gezaehlt, nicht als Widerspruch. (R79-F3 verlangte eine
     * explizite Policy — das ist sie, mit Quelle.)
     */
    fun mergedWith(other: PumpTreatmentIdentity): PumpTreatmentIdentity? {
        if (other.proposalId != proposalId) return null
        if (temporaryId != null && other.temporaryId != null && temporaryId != other.temporaryId) return null
        if (pumpId != null && other.pumpId != null && pumpId != other.pumpId) return null
        if (pumpSerialHash != other.pumpSerialHash || pumpType != other.pumpType) return null
        return copy(
            temporaryId = temporaryId ?: other.temporaryId,
            pumpId = pumpId ?: other.pumpId,
            treatmentTimestamp = other.treatmentTimestamp,
        )
    }

    /**
     * Vertraeglichkeit statt ODER-Treffer (R79-F3), aber nur bei tatsaechlicher
     * Verknuepfung (R81-F2).
     *
     * Ein logisches ODER haette `{temp=7, pump=8}` gegen `{temp=7, pump=9}` als
     * Treffer gewertet und ausgebucht, obwohl die pumpId widerspricht.
     * Ein reiner Widerspruchstest wiederum haette JEDEN fremden Bolus im
     * Snapshot zum Konflikt gemacht — und ein normaler IOB-Snapshot enthaelt
     * fremde Boli. Beides ist falsch:
     *
     *     kein gemeinsamer Anker            -> NO_MATCH  (fremder Datensatz)
     *     Anker passt, nichts widerspricht  -> MATCH
     *     Anker passt UND etwas widerspricht -> CONFLICT
     */
    fun compatibility(other: AccountedTreatment): IdentityMatch {
        val tempHit = temporaryId != null && temporaryId == other.temporaryId
        val pumpHit = pumpId != null && pumpId == other.pumpId
        if (!tempHit && !pumpHit) return IdentityMatch.NO_MATCH
        val tempConflict = temporaryId != null && other.temporaryId != null && temporaryId != other.temporaryId
        val pumpConflict = pumpId != null && other.pumpId != null && pumpId != other.pumpId
        // R83-F3: die Geraeteprovenienz war bisher im Ledger vorhanden, aber
        // beim eigentlichen Nachweis wirkungslos — verglichen wurden nur die
        // beiden Zahlen. Eine gleiche temporaryId auf einer ANDEREN Pumpe ist
        // kein Treffer, sondern ein Widerspruch.
        val deviceConflict = (other.pumpType != null && other.pumpType != pumpType) ||
            (other.pumpSerialHash != null && other.pumpSerialHash != pumpSerialHash)
        return if (tempConflict || pumpConflict || deviceConflict) IdentityMatch.CONFLICT else IdentityMatch.MATCH
    }
}

enum class IdentityMatch { NO_MATCH, MATCH, CONFLICT }

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
/**
 * Ein Datensatz, der nachweislich in DIESEM Treatment-Snapshot steckt.
 *
 * [pumpType] und [pumpSerialHash] sind nullbar, weil eine Quelle sie nicht
 * immer mitliefert: `null` heisst "keine Aussage" und erzeugt keinen Konflikt.
 * Traegt die Quelle sie dagegen, MUESSEN sie zur gebundenen Identitaet passen —
 * sonst waere eine gleiche temporaryId auf einer anderen Pumpe ein Treffer
 * (R83-F3). Vor dem produktionsnahen Adapter sind sie Pflichtfeld der Quelle.
 */
data class AccountedTreatment(
    val temporaryId: Long?,
    val pumpId: Long?,
    val amountU: Double,
    val pumpType: String? = null,
    val pumpSerialHash: String? = null,
)

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
    /** Belegter Rueckzug VOR der Ausfuehrung (R79-F1 Punkt 3). */
    val withdrawnProven: Boolean,
    /**
     * Nach dem Reject/Rueckzug ging es doch weiter (R83-F2).
     *
     * Der Hold allein genuegte nicht: er verhinderte zwar die naechste Freigabe,
     * liess den Ledgerwert aber bei 0 U — und damit sachlich falsch fuer Audit,
     * Cap-Rechnung und Wiederanlauf. Ein Widerspruch ist gerade KEIN Beweis
     * mehr, dass nichts floss.
     */
    val contradicted: Boolean,
    /**
     * Untergrenze der Buchung nach einem Widerspruch (R85-F1).
     *
     * Eine widersprechende Stufe kann auch KLEINER sein als die zuletzt
     * bekannte. Dann darf sie die Schuld nicht senken — im Widerspruchsfall ist
     * die groessere bekannte Menge die konservative Wahrheit.
     */
    val conservativeFloorU: Double?,
    val terminalSeen: Boolean,
    val failClosed: Boolean,
    val corrections: Int,
    val decisionTs: Long,
    val latestBolusTimestampAtDecision: Long,
    val errors: List<LedgerError>,
    val accountedSnapshotHash: String?,
) {

    /**
     * Gibt es IRGENDEIN Anzeichen, dass ein Pumpenkommando stattgefunden hat?
     *
     * Ein Terminalereignis zaehlt auch dann, wenn es 0 meldet: dass die Pumpe
     * geantwortet hat, heisst, dass sie gefragt wurde.
     */
    val anyDeliverySignal: Boolean
        get() = terminalSeen || amounts.reportedDeliveredU != null || amounts.provenDeliveredU != null ||
            amounts.pumpCommandU != null

    /** Nichts mehr offen: es floss beweisbar nichts, oder die Menge steckt im IOB. */
    val closed: Boolean
        get() = accounting == AccountingState.IOB_ACCOUNTED ||
            delivery == DeliveryState.CONFIRMED_ZERO ||
            debtReleaseEffective

    /** Ein Reject/Rueckzug befreit nur, solange KEIN Lieferzeichen vorliegt.
     *  R79-F1: sonst konnte ein spaeteres positives Terminalereignis die
     *  Nullbuchung nicht mehr korrigieren — die Menge verschwand aus
     *  `transportCommitmentU`, bevor sie im IOB nachgewiesen war. */
    val debtFreeingReject: Boolean get() = queueReject != null || withdrawnProven

    /** ... und er befreit nur, solange ihm nicht widersprochen wurde (R83-F2). */
    val debtReleaseEffective: Boolean
        get() = debtFreeingReject && !contradicted && !anyDeliverySignal

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
            accounting == AccountingState.IOB_ACCOUNTED       -> 0.0
            delivery == DeliveryState.CONFIRMED_ZERO          -> 0.0
            debtReleaseEffective                              -> 0.0
            // Ein Nachweis schlaegt jede Schaetzung - auch die Untergrenze.
            amounts.provenDeliveredU != null                  -> amounts.provenDeliveredU!!
            else                                              -> maxOf(
                amounts.latestKnownCommandU,
                amounts.reportedDeliveredU ?: 0.0,
                conservativeFloorU ?: 0.0,
            )
        }
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
            LedgerError.IMPOSSIBLE_STATE_CONFLICT,
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

    /**
     * `null` heisst UNBEKANNT und ist zulaessig. `NaN` heisst nie unbekannt —
     * es ist ein kaputter Wert und darf nie zu einem Ledger-Fakt werden
     * (R79-F2): `maxOf(command, NaN)` ergibt NaN, und damit waere die gesamte
     * `transportCommitmentU` ungueltig statt konservativ.
     */
    fun isStorableAmount(u: Double?): Boolean = u == null || (u.isFinite() && u >= 0.0)

    fun sameAmount(a: Double?, b: Double?, epsU: Double = 1e-9): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else                   -> abs(a - b) <= epsU
    }
}
