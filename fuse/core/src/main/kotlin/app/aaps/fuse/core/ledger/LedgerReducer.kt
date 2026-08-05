package app.aaps.fuse.core.ledger

/**
 * Der Ledger als PURER REDUCER (K2-C v0.3 §2, v0.2 §2).
 *
 * Nur die Hooks liegen spaeter im Android-/Loop-Pfad; die Buchhaltung selbst
 * ist eine Funktion (Zustand, Ereignis) -> Zustand und damit vollstaendig auf
 * der JVM pruefbar. Sie kennt keine Uhr: JEDER Zeitablauf ist hier bewusst
 * kein Ereignis, weil Zeit nichts beweist.
 */
sealed interface LedgerEvent {

    sealed interface OfProposal : LedgerEvent {

        val proposalId: String
    }

    data class Proposed(
        override val proposalId: String,
        val proposedU: Double,
        val decisionTs: Long,
        /** v0.3.1 C5: der im CycleSnapshot bekannte juengste Bolus — er wird bis
         *  zur Queue durchgetragen, damit deren vorhandener Guard das Rennen
         *  seit der FUSE-Rechnung ueberhaupt sehen kann. */
        val latestBolusTimestamp: Long,
    ) : OfProposal

    data class AmountObserved(
        override val proposalId: String,
        val stage: AmountStage,
        val amountU: Double,
    ) : OfProposal

    data class QueueAccepted(override val proposalId: String) : OfProposal

    data class QueueRejected(override val proposalId: String, val reason: QueueRejectReason) : OfProposal

    /**
     * Belegter Rueckzug NACH der Queue-Annahme (R79-F1 Punkt 3).
     *
     * Diesen Pfad gibt es in AAPS wirklich: `CommandQueueImplementation.bolus()`
     * ruft fuer SMBs `removeAll(CommandType.SMB_BOLUS)` und wirft bereits
     * eingereihte Kommandos OHNE Callback weg. Der Beleg, dass dabei kein
     * Pumpenkommando entstehen konnte, steckt in der Queue-Mechanik selbst:
     * das laufende Kommando liegt in `performing` und NICHT mehr in `queue`
     * (`performing = queue.poll()`), `removeAll` iteriert nur ueber `queue`.
     * Entfernt werden kann also ausschliesslich, was nie gestartet ist.
     *
     * Ein generischer Reject reicht dafuer NICHT — er traegt diesen Beleg nicht.
     * Das Ereignis kann erst mit dem (weiterhin gesperrten) Queue-Hook erzeugt
     * werden; der Vertrag steht hier schon.
     */
    data class QueueWithdrawnProven(override val proposalId: String, val evidence: String) : OfProposal

    /** EIN Terminalereignis mit vier Eigenschaften — keine Zustandsfolge.
     *  `success`/`enacted` sind Diagnose; die Menge steht in [bolusDeliveredU]. */
    data class ExecutionResult(
        override val proposalId: String,
        val success: Boolean,
        val enacted: Boolean,
        val bolusDeliveredU: Double?,
        val comment: String = "",
    ) : OfProposal

    /** Expliziter Pump-/History-Nachweis ueber die TATSAECHLICHE Menge.
     *  Nur hierueber kann eine Zeile ohne IOB-Buchung wieder frei werden. */
    data class DeliveryProven(
        override val proposalId: String,
        val provenDeliveredU: Double,
        val evidence: String,
    ) : OfProposal

    data class PumpIdentityBound(
        override val proposalId: String,
        val temporaryId: Long?,
        val pumpId: Long?,
        val pumpType: String,
        val pumpSerialHash: String,
        val treatmentTimestamp: Long,
    ) : OfProposal

    /** Menge im DB-Datensatz. Eine spaetere Revision ist eine KORREKTUR,
     *  niemals eine zweite Dosis. */
    data class DbAmountObserved(override val proposalId: String, val dbAccountedU: Double) : OfProposal

    /** v0.3.1 C6: die Identitaet ist an einer Station verloren gegangen. */
    data class ProposalIdLost(override val proposalId: String, val station: String) : OfProposal

    /** Global: ein IOB-Snapshot MIT Treatment-Provenienz wurde gebaut. */
    data class IobSnapshotObserved(val snapshot: IobAccountingSnapshot) : LedgerEvent

    /** Global: Prozessstart. Was offen und nicht beweisbar ist, gilt ab jetzt
     *  konservativ als abgegeben — nicht als geloescht. */
    data class RestartObserved(val nowTs: Long) : LedgerEvent
}

data class LedgerConfig(val bolusStepU: Double, val amountEpsU: Double = 1e-9)

object LedgerReducer {

    fun reduceAll(state: LedgerState, events: List<LedgerEvent>, cfg: LedgerConfig): LedgerState =
        events.fold(state) { s, e -> reduce(s, e, cfg) }

    fun reduce(state: LedgerState, event: LedgerEvent, cfg: LedgerConfig): LedgerState = when (event) {
        is LedgerEvent.IobSnapshotObserved -> onSnapshot(state, event, cfg)
        is LedgerEvent.RestartObserved     -> onRestart(state)
        is LedgerEvent.Proposed            -> onProposed(state, event)
        is LedgerEvent.OfProposal          -> {
            val entry = state.entries[event.proposalId]
            if (entry == null) fail(state, event.proposalId, LedgerError.UNKNOWN_PROPOSAL, event.toString())
            else onProposalEvent(state, entry, event, cfg)
        }
    }

    // ---- einzelne Ereignisse ---------------------------------------------

    private fun onProposed(state: LedgerState, e: LedgerEvent.Proposed): LedgerState {
        if (!e.proposedU.isFinite() || e.proposedU < 0.0)
            return fail(state, e.proposalId, LedgerError.NON_FINITE_AMOUNT, "proposedU=${e.proposedU}")
        val existing = state.entries[e.proposalId]
        if (existing != null) {
            // Ein Wiederholungsversuch mit demselben Inhalt darf keinen zweiten
            // Eintrag erzeugen (KC2-09) — ein abweichender waere ein Bruch der
            // Id-Erzeugung und damit ein Grund, alles anzuhalten.
            val same = LedgerRules.sameAmount(existing.amounts.proposedU, e.proposedU, 1e-12) &&
                existing.decisionTs == e.decisionTs &&
                existing.latestBolusTimestampAtDecision == e.latestBolusTimestamp
            return if (same) state
            else fail(state, e.proposalId, LedgerError.DUPLICATE_PROPOSAL, "proposedU=${e.proposedU}", markEntry = true)
        }
        val entry = ProposalEntry(
            proposalId = e.proposalId,
            phase = LedgerPhase.PREPARED,
            amounts = AmountAxis(proposedU = e.proposedU),
            accounting = AccountingState.NOT_ACCOUNTED,
            delivery = DeliveryState.UNKNOWN,
            identity = null,
            queueReject = null,
            withdrawnProven = false,
            terminalSeen = false,
            failClosed = false,
            corrections = 0,
            decisionTs = e.decisionTs,
            latestBolusTimestampAtDecision = e.latestBolusTimestamp,
            errors = emptyList(),
            accountedSnapshotHash = null,
        )
        return state.copy(entries = state.entries + (e.proposalId to entry))
    }

    private fun onProposalEvent(
        state: LedgerState,
        entry: ProposalEntry,
        e: LedgerEvent.OfProposal,
        cfg: LedgerConfig,
    ): LedgerState = when (e) {
        is LedgerEvent.Proposed          -> state // oben behandelt
        is LedgerEvent.AmountObserved    -> onAmount(state, entry, e, cfg)
        is LedgerEvent.QueueAccepted     -> onQueueAccepted(state, entry, cfg)
        is LedgerEvent.QueueRejected     -> onQueueRejected(state, entry, e)
        is LedgerEvent.QueueWithdrawnProven -> onWithdrawn(state, entry, e)
        is LedgerEvent.ExecutionResult   -> onExecutionResult(state, entry, e, cfg)
        is LedgerEvent.DeliveryProven    -> onDeliveryProven(state, entry, e, cfg)
        is LedgerEvent.PumpIdentityBound -> onIdentity(state, entry, e)
        is LedgerEvent.DbAmountObserved  -> onDbAmount(state, entry, e, cfg)
        is LedgerEvent.ProposalIdLost    ->
            put(fail(state, entry.proposalId, LedgerError.PROPOSAL_ID_LOST, e.station), entry.failed(LedgerError.PROPOSAL_ID_LOST))
    }

    private fun onAmount(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.AmountObserved, cfg: LedgerConfig): LedgerState {
        if (!e.amountU.isFinite() || e.amountU < 0.0)
            return put(
                fail(state, entry.proposalId, LedgerError.NON_FINITE_AMOUNT, "${e.stage}=${e.amountU}"),
                entry.failed(LedgerError.NON_FINITE_AMOUNT)
            )
        // Eine weitere Mengenstufe nach einem Reject oder belegten Rueckzug
        // widerspricht diesem (R81-F3): die Kette lief offenbar weiter.
        if (entry.debtFreeingReject && entry.amounts.stage(e.stage) == null)
            return put(
                fail(
                    state, entry.proposalId, LedgerError.PHASE_VIOLATION,
                    "${e.stage} after ${entry.queueReject?.name ?: "withdrawal"}"
                ),
                entry.copy(amounts = entry.amounts.withStage(e.stage, e.amountU)).failed(LedgerError.PHASE_VIOLATION)
            )
        val known = entry.amounts.stage(e.stage)
        if (known != null) {
            if (LedgerRules.sameAmount(known, e.amountU, cfg.amountEpsU)) return state
            return put(
                fail(state, entry.proposalId, LedgerError.CONFLICTING_STAGE_AMOUNT, "${e.stage}: $known -> ${e.amountU}"),
                entry.failed(LedgerError.CONFLICTING_STAGE_AMOUNT)
            )
        }
        val axis = entry.amounts.withStage(e.stage, e.amountU)
        val violation = LedgerRules.chainViolation(axis, cfg.amountEpsU)
        if (violation != null)
            return put(
                fail(state, entry.proposalId, LedgerError.CONSTRAINT_CHAIN_INVALID, violation),
                entry.copy(amounts = axis).failed(LedgerError.CONSTRAINT_CHAIN_INVALID)
            )
        val phase = if (e.stage == AmountStage.RT_PUBLISHED && entry.phase == LedgerPhase.PREPARED)
            LedgerPhase.PUBLISHED else entry.phase
        return put(state, entry.copy(amounts = axis, phase = phase))
    }

    private fun onQueueAccepted(state: LedgerState, entry: ProposalEntry, cfg: LedgerConfig): LedgerState {
        val q = entry.amounts.queueConstrainedU
        // C4: eine auf Null constrainte Menge darf NIE in die Queue gelangen —
        // der Treiber scheitert danach an require(insulin > 0).
        if (q != null && LedgerRules.queueWouldRejectAsZero(q, cfg.bolusStepU))
            return put(
                fail(state, entry.proposalId, LedgerError.PHASE_VIOLATION, "queueConstrainedU=$q accepted"),
                entry.failed(LedgerError.PHASE_VIOLATION)
            )
        // Eine Annahme NACH Reject oder belegtem Rueckzug ist ein Widerspruch —
        // vorher lief sie still ins Leere, weil die Phase schon TERMINAL war
        // und das Commitment auf 0 stehen blieb (R81-F3).
        if (entry.debtFreeingReject)
            return put(
                fail(
                    state, entry.proposalId, LedgerError.PHASE_VIOLATION,
                    "accepted after ${entry.queueReject?.name ?: "withdrawal"}"
                ),
                entry.failed(LedgerError.PHASE_VIOLATION)
            )
        if (entry.phase == LedgerPhase.QUEUE_ACCEPTED || entry.phase == LedgerPhase.TERMINAL) return state
        return put(state, entry.copy(phase = LedgerPhase.QUEUE_ACCEPTED))
    }

    /**
     * Ein Reject befreit NUR, wenn er vor der Queue-Annahme, vor dem
     * Pumpenkommando und vor jedem Terminalereignis kommt (R79-F1).
     *
     * Vorher genuegte es, dass noch kein Pumpenkommando GEMELDET war — die Folge
     * `Proposed -> QueueAccepted -> QueueRejected` setzte die Verpflichtung auf
     * null, obwohl das Kommando bereits in der Queue lag. Nach der Annahme ist
     * ein generischer Reject ein Widerspruch, kein Beleg.
     */
    private fun onQueueRejected(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.QueueRejected): LedgerState {
        if (entry.queueReject == e.reason) return state          // genau EIN Reject-Ereignis
        val after = when {
            entry.queueReject != null              -> "reject ${entry.queueReject}"
            entry.phase == LedgerPhase.QUEUE_ACCEPTED -> "QUEUE_ACCEPTED"
            entry.terminalSeen                     -> "terminal"
            entry.amounts.pumpCommandU != null     -> "pump command"
            else                                   -> null
        }
        if (after != null)
            return put(
                fail(state, entry.proposalId, LedgerError.PHASE_VIOLATION, "reject ${e.reason} after $after"),
                entry.failed(LedgerError.PHASE_VIOLATION)
            )
        // Nichts wurde eingereiht, also floss nichts: die Schuld ist aufgeloest.
        return put(state, entry.copy(queueReject = e.reason, phase = LedgerPhase.TERMINAL))
    }

    /**
     * Der EINZIGE schuldbefreiende Uebergang ist `QUEUE_ACCEPTED -> withdrawn`
     * (R81-F3).
     *
     * Vorher genuegte "kein Lieferzeichen" — damit konnte schon
     * `Proposed -> QueueWithdrawnProven` die Verpflichtung auf null setzen, mit
     * einem beliebigen Freitext als Beleg. Ein Rueckzug, den es an dieser
     * Stelle gar nicht geben kann, darf nichts befreien.
     */
    private fun onWithdrawn(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.QueueWithdrawnProven): LedgerState {
        if (entry.withdrawnProven) return state
        val problem = when {
            e.evidence.isBlank()                        -> "empty evidence"
            entry.phase != LedgerPhase.QUEUE_ACCEPTED   -> "phase=${entry.phase}"
            entry.anyDeliverySignal                     -> "delivery signal present"
            entry.queueReject != null                   -> "already rejected"
            else                                        -> null
        }
        if (problem != null)
            return put(
                fail(state, entry.proposalId, LedgerError.PHASE_VIOLATION, "withdrawn invalid: $problem"),
                entry.failed(LedgerError.PHASE_VIOLATION)
            )
        return put(state, entry.copy(withdrawnProven = true, phase = LedgerPhase.TERMINAL))
    }

    private fun onExecutionResult(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.ExecutionResult, cfg: LedgerConfig): LedgerState {
        // R79-F2: NaN ist nie "unbekannt". Ein kaputter Wert wird NICHT
        // gespeichert - die letzte gueltige konservative Menge bleibt stehen.
        if (!LedgerRules.isStorableAmount(e.bolusDeliveredU))
            return put(
                fail(state, entry.proposalId, LedgerError.NON_FINITE_AMOUNT, "bolusDelivered=${e.bolusDeliveredU}"),
                entry.copy(delivery = DeliveryState.UNKNOWN_ASSUMED, terminalSeen = true, phase = LedgerPhase.TERMINAL)
                    .failed(LedgerError.NON_FINITE_AMOUNT)
            )
        // R79-F1: ein Terminalereignis nach einem Reject widerspricht dem
        // Reject. Die Menge bleibt gebucht, der Widerspruch wird sichtbar.
        var s = state
        var contradiction = false
        if (entry.debtFreeingReject) {
            s = fail(s, entry.proposalId, LedgerError.PHASE_VIOLATION, "execution result after ${entry.queueReject ?: "withdrawal"}")
            contradiction = true
        }
        val commandU = entry.amounts.pumpCommandU ?: entry.amounts.latestKnownCommandU
        val delivery = LedgerRules.classifyDelivery(commandU, e.bolusDeliveredU, cfg.bolusStepU)
        if (entry.terminalSeen) {
            val same = LedgerRules.sameAmount(entry.amounts.reportedDeliveredU, e.bolusDeliveredU, cfg.amountEpsU)
            if (same && !contradiction) return state
            val conservative = maxOf(entry.amounts.reportedDeliveredU ?: 0.0, e.bolusDeliveredU ?: 0.0)
            return put(
                fail(s, entry.proposalId, LedgerError.DUPLICATE_TERMINAL, "delivered ${entry.amounts.reportedDeliveredU} -> ${e.bolusDeliveredU}"),
                entry.copy(amounts = entry.amounts.copy(reportedDeliveredU = conservative)).failed(LedgerError.DUPLICATE_TERMINAL)
            )
        }
        var next = entry.copy(
            amounts = entry.amounts.copy(reportedDeliveredU = e.bolusDeliveredU),
            delivery = delivery,
            terminalSeen = true,
            phase = LedgerPhase.TERMINAL,
        )
        if (contradiction) next = next.failed(LedgerError.PHASE_VIOLATION)
        if (delivery == DeliveryState.OVERDELIVERY_ANOMALY) {
            s = fail(s, entry.proposalId, LedgerError.OVERDELIVERY_ANOMALY, "command=$commandU delivered=${e.bolusDeliveredU}")
            next = next.failed(LedgerError.OVERDELIVERY_ANOMALY)
        }
        return put(s, next)
    }

    private fun onDeliveryProven(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.DeliveryProven, cfg: LedgerConfig): LedgerState {
        if (!e.provenDeliveredU.isFinite() || e.provenDeliveredU < 0.0)
            return put(
                fail(state, entry.proposalId, LedgerError.NON_FINITE_AMOUNT, "proven=${e.provenDeliveredU}"),
                entry.failed(LedgerError.NON_FINITE_AMOUNT)
            )
        if (LedgerRules.sameAmount(entry.amounts.provenDeliveredU, e.provenDeliveredU, cfg.amountEpsU)) return state
        // Ein Beleg ueber eine POSITIVE Lieferung nach einem Reject widerspricht
        // dem Reject (R79-F1). Der Beleg gilt trotzdem - er ist die staerkere
        // Aussage -, aber der Widerspruch wird sichtbar und sperrt.
        var contradiction = false
        var s0 = state
        if (entry.debtFreeingReject && e.provenDeliveredU > 0.0) {
            s0 = fail(s0, entry.proposalId, LedgerError.PHASE_VIOLATION, "proven delivery after ${entry.queueReject ?: "withdrawal"}")
            contradiction = true
        }
        val commandU = entry.amounts.pumpCommandU ?: entry.amounts.latestKnownCommandU
        val ticks = LedgerRules.canonicalTicks(e.provenDeliveredU, cfg.bolusStepU)
        val delivery = when {
            ticks == 0L                                                      -> DeliveryState.CONFIRMED_ZERO
            ticks > LedgerRules.canonicalTicks(commandU, cfg.bolusStepU)      -> DeliveryState.OVERDELIVERY_ANOMALY
            ticks == LedgerRules.canonicalTicks(commandU, cfg.bolusStepU)     -> DeliveryState.REPORTED_FULL
            else                                                             -> DeliveryState.REPORTED_PARTIAL
        }
        val corrections = if (entry.amounts.provenDeliveredU != null) entry.corrections + 1 else entry.corrections
        var next = entry.copy(
            amounts = entry.amounts.copy(provenDeliveredU = e.provenDeliveredU),
            delivery = delivery,
            corrections = corrections,
        )
        if (contradiction) next = next.failed(LedgerError.PHASE_VIOLATION)
        if (delivery == DeliveryState.OVERDELIVERY_ANOMALY) {
            s0 = fail(s0, entry.proposalId, LedgerError.OVERDELIVERY_ANOMALY, "command=$commandU proven=${e.provenDeliveredU}")
            next = next.failed(LedgerError.OVERDELIVERY_ANOMALY)
        }
        return put(s0, next)
    }

    private fun onIdentity(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.PumpIdentityBound): LedgerState {
        if (e.temporaryId == null && e.pumpId == null)
            return put(
                fail(state, entry.proposalId, LedgerError.IDENTITY_CONFLICT, "neither temporaryId nor pumpId"),
                entry.failed(LedgerError.IDENTITY_CONFLICT)
            )
        val incoming = PumpTreatmentIdentity(
            proposalId = e.proposalId,
            temporaryId = e.temporaryId,
            pumpId = e.pumpId,
            pumpType = e.pumpType,
            pumpSerialHash = e.pumpSerialHash,
            treatmentTimestamp = e.treatmentTimestamp,
        )
        val current = entry.identity ?: return put(state, entry.copy(identity = incoming))
        if (current == incoming) return state
        // Medtrum: erst temporaryId, spaeter pumpId — beide falten auf dieselbe
        // proposalId. Widersprechen sie sich, wird NICHT geraten.
        val merged = current.mergedWith(incoming)
            ?: return put(
                fail(state, entry.proposalId, LedgerError.IDENTITY_CONFLICT, "$current vs $incoming"),
                entry.failed(LedgerError.IDENTITY_CONFLICT)
            )
        // Eine verschobene Behandlungszeit ist beim Binden der pumpId normal
        // (SyncBolusWithTempIdTransaction ueberschreibt timestamp und amount)
        // und wird als Korrektur gezaehlt, nicht als Widerspruch.
        val corrections =
            if (merged.treatmentTimestamp != current.treatmentTimestamp) entry.corrections + 1 else entry.corrections
        return put(state, entry.copy(identity = merged, corrections = corrections))
    }

    private fun onDbAmount(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.DbAmountObserved, cfg: LedgerConfig): LedgerState {
        // R79-F2: auch der DB-Wert wird nie ungeprueft zum Ledger-Fakt.
        if (!LedgerRules.isStorableAmount(e.dbAccountedU))
            return put(
                fail(state, entry.proposalId, LedgerError.NON_FINITE_AMOUNT, "dbAccounted=${e.dbAccountedU}"),
                entry.failed(LedgerError.NON_FINITE_AMOUNT)
            )
        if (LedgerRules.sameAmount(entry.amounts.dbAccountedU, e.dbAccountedU, cfg.amountEpsU)) return state
        val corrections = if (entry.amounts.dbAccountedU != null) entry.corrections + 1 else entry.corrections
        return put(state, entry.copy(amounts = entry.amounts.copy(dbAccountedU = e.dbAccountedU), corrections = corrections))
    }

    /**
     * Der Uebergang NOT_ACCOUNTED -> IOB_ACCOUNTED, und NUR hier.
     *
     * Eine fremde IOB-Revision beweist nichts: gepruet wird, ob GENAU DIESER
     * Treatment-Snapshot den Datensatz mit unserer gebundenen Identitaet
     * enthaelt. Ohne gebundene Identitaet gibt es keine Buchung — Zeit- und
     * Mengennaehe koennte einen gleichzeitigen fremden Bolus derselben Menge
     * falsch zuordnen.
     */
    private fun onSnapshot(state: LedgerState, e: LedgerEvent.IobSnapshotObserved, cfg: LedgerConfig): LedgerState {
        var changed = false
        var errors = state.errors
        val next = state.entries.mapValues { (_, entry) ->
            if (entry.accounting == AccountingState.IOB_ACCOUNTED || entry.closed) return@mapValues entry
            val id = entry.identity ?: return@mapValues entry
            val compat = e.snapshot.containedTreatments.map { it to id.compatibility(it.temporaryId, it.pumpId) }
            // R79-F3: ein Widerspruch darf nicht als Nichttreffer verschwinden.
            // {temp=7,pump=8} gegen {temp=7,pump=9} ist KEIN Treffer, sondern
            // ein Konflikt - vorher wurde er per ODER-Match wegbucht.
            val conflict = compat.firstOrNull { it.second == IdentityMatch.CONFLICT }
            if (conflict != null) {
                changed = true
                val record = LedgerErrorRecord(
                    entry.proposalId, LedgerError.IDENTITY_CONFLICT,
                    "snapshot ${e.snapshot.treatmentSnapshotHash}: $id vs ${conflict.first}"
                )
                if (!errors.contains(record)) errors = errors + record
                return@mapValues entry.copy(
                    failClosed = true,
                    errors = if (entry.errors.contains(LedgerError.IDENTITY_CONFLICT)) entry.errors
                    else entry.errors + LedgerError.IDENTITY_CONFLICT,
                )
            }
            val hit = compat.firstOrNull { it.second == IdentityMatch.MATCH }?.first
                ?: return@mapValues entry
            // Ein kaputter Betrag im Snapshot bucht nicht aus: der Nachweis
            // "diese Menge steckt im IOB" waere dann selbst ungueltig.
            if (!LedgerRules.isStorableAmount(hit.amountU)) {
                changed = true
                val record = LedgerErrorRecord(
                    entry.proposalId, LedgerError.NON_FINITE_AMOUNT,
                    "snapshot amount=${hit.amountU}"
                )
                if (!errors.contains(record)) errors = errors + record
                return@mapValues entry.copy(
                    failClosed = true,
                    errors = if (entry.errors.contains(LedgerError.NON_FINITE_AMOUNT)) entry.errors
                    else entry.errors + LedgerError.NON_FINITE_AMOUNT,
                )
            }
            changed = true
            entry.copy(
                accounting = AccountingState.IOB_ACCOUNTED,
                accountedSnapshotHash = e.snapshot.treatmentSnapshotHash,
                amounts = if (entry.amounts.dbAccountedU == null) entry.amounts.copy(dbAccountedU = hit.amountU)
                else entry.amounts,
                corrections = if (entry.amounts.dbAccountedU != null &&
                    !LedgerRules.sameAmount(entry.amounts.dbAccountedU, hit.amountU, cfg.amountEpsU)
                ) entry.corrections + 1 else entry.corrections,
            )
        }
        return if (changed) state.copy(entries = next, errors = errors) else state
    }

    /** Ein Absturzfenster bleibt epistemisch UNBEKANNT. Konservativ heisst:
     *  gebucht lassen, nicht loeschen — sonst gibt der naechste Zyklus dieselbe
     *  Menge erneut frei. */
    private fun onRestart(state: LedgerState): LedgerState {
        var changed = false
        val next = state.entries.mapValues { (_, entry) ->
            if (entry.closed || entry.delivery != DeliveryState.UNKNOWN) entry
            else {
                changed = true
                entry.copy(delivery = DeliveryState.UNKNOWN_ASSUMED)
            }
        }
        return if (changed) state.copy(entries = next) else state
    }

    // ---- Helfer ----------------------------------------------------------

    private fun put(state: LedgerState, entry: ProposalEntry): LedgerState =
        state.copy(entries = state.entries + (entry.proposalId to entry))

    private fun fail(
        state: LedgerState,
        proposalId: String?,
        error: LedgerError,
        detail: String,
        markEntry: Boolean = false,
    ): LedgerState {
        val record = LedgerErrorRecord(proposalId, error, detail)
        if (state.errors.contains(record) && !markEntry) return state
        val errors = if (state.errors.contains(record)) state.errors else state.errors + record
        val entries =
            if (markEntry && proposalId != null && state.entries.containsKey(proposalId))
                state.entries + (proposalId to state.entries.getValue(proposalId).failed(error))
            else state.entries
        return state.copy(entries = entries, errors = errors)
    }

    private fun ProposalEntry.failed(error: LedgerError): ProposalEntry =
        if (failClosed && errors.contains(error)) this
        else copy(failClosed = true, errors = if (errors.contains(error)) errors else errors + error)
}
