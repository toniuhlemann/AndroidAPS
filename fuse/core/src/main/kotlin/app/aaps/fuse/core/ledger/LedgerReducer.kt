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
     * KORREKTUR ZU R80 (R81-F4): Der Pfad ist NICHT `bolus()`. Dort kommt man
     * nie bis zum `removeAll(SMB_BOLUS)`, weil `bolusInQueue()` bereits true
     * meldet, sobald ein BOLUS/SMB queued ODER laufend ist, und die Methode
     * vorher zurueckkehrt. Der reale Entferner ist `cancelAllBoluses()`:
     * `removeAll(BOLUS)` + `removeAll(SMB_BOLUS)` und PARALLEL
     * `stopBolusDelivering()` — eine laufende Abgabe wird dort also gestoppt,
     * moeglicherweise mit Teilmenge.
     *
     * Was bestehen bleibt: `removeAll` iteriert nur ueber `queue`, das laufende
     * Kommando liegt in `performing` (`performing = queue.poll()`). Entfernt
     * werden kann also nur, was nie gestartet ist — aber genau DAS muss der
     * spaetere Hook ID-genau melden. Ein blosses Beobachten des Methodenaufrufs
     * waere eine Vermutung, kein Beleg; freier Evidenztext genuegt in
     * Produktion nicht.
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

    /**
     * AUSDRUECKLICHE Quittung eines Holds (R93-F5).
     *
     * Ein Hold verschwindet NIE von selbst. Ohne einen expliziten Weg zurueck
     * macht aber ein einziger transienter Fehler den Testpfad dauerhaft
     * unbenutzbar. Die Quittung loescht die Sperre — und NICHT die Historie:
     * Fehlerliste und Zaehler bleiben stehen.
     */
    data class HoldAcknowledged(
        override val proposalId: String,
        val acknowledgedBy: String,
        val reason: String,
        /** CAS gegen veraltete Bedienzustaende (R95-F1): quittiert wird die
         *  Hold-Generation, die der Quittierende GESEHEN hat. Ist inzwischen ein
         *  juengerer Fehler dazugekommen, greift die Quittung nicht. */
        val expectedHoldGeneration: Long,
        /** Die konkret quittierten Fehler. Eine pauschale Freigabe "alles an
         *  dieser Zeile" wuerde Ungesehenes mitfreigeben. */
        val acknowledgedErrors: Set<LedgerError>,
    ) : OfProposal

    /**
     * Belegter Neustart der Snapshot-Quelle (R95-F2).
     *
     * Ohne dieses Ereignis war die Epoch ein frei waehlbarer Reset-Knopf fuer
     * die Monotonie: A(100) -> B(1) -> A(50) waere dreimal als Rebase
     * durchgegangen. Ein Tippfehler oder ein Replay alter Daten haette die
     * Absicherung ausgehebelt.
     */
    data class SnapshotSourceRestarted(
        val oldEpochId: String?,
        val newEpochId: String,
        val evidence: String,
    ) : LedgerEvent

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
        is LedgerEvent.SnapshotSourceRestarted -> onSourceRestarted(state, event)
        is LedgerEvent.Proposed            -> onProposed(state, event, cfg)
        is LedgerEvent.OfProposal          -> {
            val entry = state.entries[event.proposalId]
            if (entry == null) fail(state, event.proposalId, LedgerError.UNKNOWN_PROPOSAL, event.toString())
            else onProposalEvent(state, entry, event, cfg)
        }
    }

    // ---- einzelne Ereignisse ---------------------------------------------

    private fun onProposed(state: LedgerState, e: LedgerEvent.Proposed, cfg: LedgerConfig): LedgerState {
        if (!e.proposedU.isFinite() || e.proposedU < 0.0)
            return fail(state, e.proposalId, LedgerError.NON_FINITE_AMOUNT, "proposedU=${e.proposedU}")
        // R93-F1: eine ungueltige Policy darf nicht in die Zeile gepinnt werden -
        // sie wuerde dort ueber deren gesamte Lebensdauer weiterwirken.
        if (!cfg.amountEpsU.isFinite() || cfg.amountEpsU < 0.0 || !cfg.bolusStepU.isFinite() || cfg.bolusStepU <= 0.0)
            return fail(
                state, e.proposalId, LedgerError.NON_FINITE_AMOUNT,
                "invalid policy: eps=${cfg.amountEpsU} step=${cfg.bolusStepU}"
            )
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
            contradicted = false,
            conservativeFloorU = null,
            accountedAmountU = null,
            terminalSeen = false,
            failClosed = false,
            corrections = 0,
            decisionTs = e.decisionTs,
            latestBolusTimestampAtDecision = e.latestBolusTimestamp,
            errors = emptyList(),
            amountEpsU = cfg.amountEpsU,
            bolusStepU = cfg.bolusStepU,
            firstAccountedSnapshotHash = null,
            lastReconciledViewHash = null,
            lastReconciledAtTs = null,
            lastPositiveFactTs = null,
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
        is LedgerEvent.HoldAcknowledged  -> onHoldAcknowledged(state, entry, e)
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
        if (entry.debtFreeingReject && entry.amounts.stage(e.stage) == null) {
            // R85-F1: auch der Widerspruchspfad durchlaeuft die Kettenpruefung.
            // Sonst verliert der Ledger seine zugesicherte Stufeninvariante
            // ausgerechnet dort, wo ein lueckenloses Audit am meisten zaehlt.
            val axis = entry.amounts.withStage(e.stage, e.amountU)
            val violation = LedgerRules.chainViolation(axis, entry.amountEpsU)
            var s = fail(
                state, entry.proposalId, LedgerError.PHASE_VIOLATION,
                "${e.stage} after ${entry.queueReject?.name ?: "withdrawal"}"
            )
            var next = entry.copy(
                amounts = axis,
                contradicted = true,
                // Die vorher bekannte Menge bleibt Untergrenze der Buchung: eine
                // widersprechende KLEINERE Stufe darf die Schuld nicht senken.
                conservativeFloorU = maxOf(entry.conservativeFloorU ?: 0.0, entry.amounts.latestKnownCommandU),
            ).failed(LedgerError.PHASE_VIOLATION)
            if (violation != null) {
                s = fail(s, entry.proposalId, LedgerError.CONSTRAINT_CHAIN_INVALID, violation)
                next = next.failed(LedgerError.CONSTRAINT_CHAIN_INVALID)
            }
            return put(s, next)
        }
        val known = entry.amounts.stage(e.stage)
        if (known != null) {
            if (LedgerRules.sameAmount(known, e.amountU, entry.amountEpsU)) return state
            // B2 (Gegenproben-Audit 09.08.): der widersprechende Betrag wurde
            // vorher ERSATZLOS verworfen - auch der GROESSERE. Damit gewann bei
            // einem Widerspruch der kleinere Wert, und das ist in diesem Modul
            // genau die falsche Richtung: BELASTEND SCHLAEGT ENTLASTEND.
            //
            // Die beiden Schwesterpfade machen es seit jeher richtig (s. die
            // Phasenverletzung oben und die Korrekturbuchung weiter unten) und
            // sagen auch warum: "eine widersprechende KLEINERE Stufe darf die
            // Schuld nicht senken". Es fehlte nur hier.
            //
            // Der Befund bleibt sichtbar und fail-closed - die Untergrenze ist
            // Schadensbegrenzung, keine Aufloesung des Widerspruchs.
            return put(
                fail(state, entry.proposalId, LedgerError.CONFLICTING_STAGE_AMOUNT, "${e.stage}: $known -> ${e.amountU}"),
                entry.copy(
                    conservativeFloorU = maxOf(
                        entry.conservativeFloorU ?: 0.0,
                        maxOf(known, e.amountU).takeIf { it.isFinite() } ?: (entry.conservativeFloorU ?: 0.0),
                    ),
                ).failed(LedgerError.CONFLICTING_STAGE_AMOUNT)
            )
        }
        val axis = entry.amounts.withStage(e.stage, e.amountU)
        val violation = LedgerRules.chainViolation(axis, entry.amountEpsU)
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
        if (q != null && LedgerRules.queueWouldRejectAsZero(q, entry.bolusStepU))
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
                entry.copy(contradicted = true).failed(LedgerError.PHASE_VIOLATION)
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
    /**
     * Die Quittung loest den GLOBALEN Hold — vorher tat sie das nachweislich
     * nicht (R95-F1): sie setzte nur `entry.failClosed = false`, waehrend
     * `holdActuation` weiter an der blossen Existenz der Fehlerzeile hing.
     */
    private fun onHoldAcknowledged(state: LedgerState, entry: ProposalEntry, e: LedgerEvent.HoldAcknowledged): LedgerState {
        fun reject(detail: String) =
            // Eine ungueltige Quittung darf NICHT selbst zu einem dauerhaften
            // globalen Fehler werden - sonst macht der Bedienfehler den Weg
            // zurueck endgueltig zu.
            state.copy(errors = state.errors + LedgerErrorRecord(
                entry.proposalId, LedgerError.HOLD_ACKNOWLEDGED,
                "REJECTED: $detail", "REJECTED: $detail", 1, active = false,
            ))

        if (e.acknowledgedBy.isBlank() || e.reason.isBlank()) return reject("empty acknowledgement")
        if (e.acknowledgedErrors.isEmpty()) return reject("no error keys named")
        if (e.expectedHoldGeneration != state.holdGeneration)
            return reject("stale: expected gen ${e.expectedHoldGeneration}, actual ${state.holdGeneration}")
        val notWaivable = e.acknowledgedErrors - LedgerState.RECOVERABLE_ERRORS
        if (notWaivable.isNotEmpty()) return reject("not waivable: $notWaivable")

        val target = state.errors.filter {
            it.active && it.proposalId == entry.proposalId && it.error in e.acknowledgedErrors
        }
        if (target.isEmpty()) return reject("nothing active to acknowledge")

        val errors = state.errors.map {
            if (it.active && it.proposalId == entry.proposalId && it.error in e.acknowledgedErrors)
                it.copy(
                    active = false,
                    resolvedBy = e.acknowledgedBy,
                    resolvedReason = e.reason,
                    resolvedGeneration = state.holdGeneration,
                )
            else it
        } + LedgerErrorRecord(
            entry.proposalId, LedgerError.HOLD_ACKNOWLEDGED,
            "${e.acknowledgedBy}: ${e.reason} (${e.acknowledgedErrors.joinToString(",")})",
            "${e.acknowledgedBy}: ${e.reason}", 1, active = false,
        )
        // Der Entry-Latch faellt nur, wenn KEIN aktiver Fehler dieser Zeile
        // mehr uebrig ist.
        val stillActive = errors.any { it.active && it.proposalId == entry.proposalId }
        return state.copy(
            errors = errors,
            entries = state.entries + (entry.proposalId to entry.copy(failClosed = stillActive)),
        )
    }

    /**
     * Ein Epochwechsel ist ein ANGEKUENDIGTES Ereignis, keine neue Zeichenkette
     * im naechsten Snapshot (R95-F2). Eine schon benutzte Epoch kann nicht
     * wiederbelebt werden.
     */
    private fun onSourceRestarted(state: LedgerState, e: LedgerEvent.SnapshotSourceRestarted): LedgerState {
        val problem = when {
            e.newEpochId.isBlank()                       -> "empty epoch"
            e.newEpochId == "default"                    -> "default epoch"
            e.evidence.isBlank()                         -> "no evidence"
            e.newEpochId in state.seenEpochs             -> "epoch already used: ${e.newEpochId}"
            state.lastSnapshotOrder != null && e.oldEpochId != state.lastSnapshotOrder.sourceEpochId ->
                "old epoch mismatch: ${e.oldEpochId} vs ${state.lastSnapshotOrder.sourceEpochId}"
            else                                         -> null
        }
        if (problem != null)
            return fail(state, null, LedgerError.SNAPSHOT_ORDER_CONFLICT, "source restart rejected: $problem")
        return fail(state, null, LedgerError.SNAPSHOT_EPOCH_REBASED, "${e.oldEpochId} -> ${e.newEpochId}: ${e.evidence}")
            .copy(announcedEpochId = e.newEpochId)
    }

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
        // G3 (Codex-Adjudication bae885f1): eine POSITIVE Liefermeldung nach
        // einem bewiesenen NULL-Nachweis ist kein normaler Zustandswechsel,
        // sondern ein unmoeglicher Zustand - zwei Beweislagen schliessen sich
        // aus. Vorher lief die Meldung wirkungslos durch: grossLiabilityU gab
        // dem Nachweis unbedingten Vorrang, und die Haftung blieb 0.
        val zeroProofConflict = entry.provenZeroDelivery && (e.bolusDeliveredU ?: 0.0) > entry.amountEpsU
        if (zeroProofConflict)
            s = fail(
                s, entry.proposalId, LedgerError.IMPOSSIBLE_STATE_CONFLICT,
                "proven zero vs reported delivery ${e.bolusDeliveredU}"
            )

        fun ProposalEntry.withZeroProofConflict(): ProposalEntry =
            if (zeroProofConflict) copy(contradicted = true).failed(LedgerError.IMPOSSIBLE_STATE_CONFLICT) else this

        // Der Latch bleibt auch dann noetig, wenn schon ein Lieferzeichen
        // vorliegt: er haelt den Widerspruch fuer das Audit fest.
        val commandU = entry.amounts.pumpCommandU ?: entry.amounts.latestKnownCommandU
        val delivery = LedgerRules.classifyDelivery(commandU, e.bolusDeliveredU, entry.bolusStepU)
        if (entry.terminalSeen) {
            val same = LedgerRules.sameAmount(entry.amounts.reportedDeliveredU, e.bolusDeliveredU, entry.amountEpsU)
            // Eine identische WIEDERHOLUNG ist kein DUPLICATE_TERMINAL - der
            // Null-Beweis-Widerspruch wird trotzdem festgehalten.
            if (same && !contradiction) return if (zeroProofConflict) put(s, entry.withZeroProofConflict()) else state
            val conservative = maxOf(entry.amounts.reportedDeliveredU ?: 0.0, e.bolusDeliveredU ?: 0.0)
            return put(
                fail(s, entry.proposalId, LedgerError.DUPLICATE_TERMINAL, "delivered ${entry.amounts.reportedDeliveredU} -> ${e.bolusDeliveredU}"),
                entry.copy(amounts = entry.amounts.copy(reportedDeliveredU = conservative))
                    .failed(LedgerError.DUPLICATE_TERMINAL).withZeroProofConflict()
            )
        }
        var next = entry.copy(
            amounts = entry.amounts.copy(reportedDeliveredU = e.bolusDeliveredU),
            delivery = delivery,
            terminalSeen = true,
            phase = LedgerPhase.TERMINAL,
        )
        if (contradiction) next = next.copy(contradicted = true).failed(LedgerError.PHASE_VIOLATION)
        next = next.withZeroProofConflict()
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
        if (LedgerRules.sameAmount(entry.amounts.provenDeliveredU, e.provenDeliveredU, entry.amountEpsU)) return state
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
        val ticks = LedgerRules.canonicalTicks(e.provenDeliveredU, entry.bolusStepU)
        val delivery = when {
            ticks == 0L                                                      -> DeliveryState.CONFIRMED_ZERO
            ticks > LedgerRules.canonicalTicks(commandU, entry.bolusStepU)      -> DeliveryState.OVERDELIVERY_ANOMALY
            ticks == LedgerRules.canonicalTicks(commandU, entry.bolusStepU)     -> DeliveryState.REPORTED_FULL
            else                                                             -> DeliveryState.REPORTED_PARTIAL
        }
        val corrections = if (entry.amounts.provenDeliveredU != null) entry.corrections + 1 else entry.corrections
        var next = entry.copy(
            amounts = entry.amounts.copy(provenDeliveredU = e.provenDeliveredU),
            delivery = delivery,
            corrections = corrections,
        )
        if (contradiction) next = next.copy(contradicted = true).failed(LedgerError.PHASE_VIOLATION)
        // G3, ANDERE Reihenfolge (Ordnungsinvarianz): der Null-Nachweis trifft
        // NACH einer positiven Terminalmeldung ein. Auch dann schliessen sich
        // die beiden Aussagen aus - der Nachweis darf die gemeldete Menge nicht
        // stillschweigend loeschen (CONFIRMED_ZERO wuerde die Zeile schliessen).
        if (delivery == DeliveryState.CONFIRMED_ZERO && (entry.amounts.reportedDeliveredU ?: 0.0) > entry.amountEpsU) {
            s0 = fail(
                s0, entry.proposalId, LedgerError.IMPOSSIBLE_STATE_CONFLICT,
                "proven zero vs reported delivery ${entry.amounts.reportedDeliveredU}"
            )
            next = next.copy(contradicted = true).failed(LedgerError.IMPOSSIBLE_STATE_CONFLICT)
        }
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
        if (LedgerRules.sameAmount(entry.amounts.dbAccountedU, e.dbAccountedU, entry.amountEpsU)) return state
        val corrections = if (entry.amounts.dbAccountedU != null) entry.corrections + 1 else entry.corrections
        return put(
            state,
            entry.copy(
                amounts = entry.amounts.copy(dbAccountedU = e.dbAccountedU),
                // Ist die Zugehoerigkeit zur IOB-Basis bereits bewiesen, ist die
                // Menge eine Eigenschaft DIESES Datensatzes: eine Korrektur
                // verschiebt den Restbetrag in beide Richtungen (R89-F1).
                accountedAmountU = if (entry.accounting == AccountingState.IOB_ACCOUNTED) e.dbAccountedU
                else entry.accountedAmountU,
                corrections = corrections,
            )
        )
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
        val viewHash = e.snapshot.treatmentSnapshotHash
        val order = e.snapshot.order
        val last = state.lastSnapshotOrder

        // R95-F4: eine unbrauchbare Ordnung wird abgewiesen, nicht interpretiert.
        if (order.sourceEpochId.isBlank() || order.sourceEpochId == "default" ||
            order.calculatorGeneration < 0L || order.calculatedAt <= 0L
        ) return fail(
            state, null, LedgerError.SNAPSHOT_ORDER_CONFLICT,
            "invalid order: epoch='${order.sourceEpochId}' gen=${order.calculatorGeneration} at=${order.calculatedAt}"
        )

        if (last != null && last.sourceEpochId == order.sourceEpochId) {
            // R93-F2: die Ordnung entscheidet EINMAL je Snapshot, vor jeder Zeile.
            if (order.sameOrderAs(last) && viewHash != state.lastSnapshotViewHash)
                return fail(
                    state, null, LedgerError.SNAPSHOT_ORDER_CONFLICT,
                    "gen=${order.calculatorGeneration} at=${order.calculatedAt}: $viewHash vs ${state.lastSnapshotViewHash}"
                )
            if (!order.isNewerThan(last) && !order.sameOrderAs(last))
                return fail(
                    state, null, LedgerError.STALE_SNAPSHOT_IGNORED,
                    "gen=${order.calculatorGeneration} at=${order.calculatedAt} behind gen=${last.calculatorGeneration} at=${last.calculatedAt}"
                )
        } else if (last != null) {
            // R95-F2: ein Epochwechsel gilt nur ANGEKUENDIGT. Sonst waere die
            // Epoch der Reset-Knopf fuer die Monotonie.
            if (order.sourceEpochId != state.announcedEpochId)
                return fail(
                    state, null, LedgerError.SNAPSHOT_ORDER_CONFLICT,
                    "unannounced epoch change ${last.sourceEpochId} -> ${order.sourceEpochId}"
                )
        }

        var s0 = state.copy(
            seenEpochs = state.seenEpochs + order.sourceEpochId,
            announcedEpochId = if (state.announcedEpochId == order.sourceEpochId) null else state.announcedEpochId,
        )
        var noteState = s0

        fun note(entry: ProposalEntry, error: LedgerError, detail: String, failClosed: Boolean): ProposalEntry {
            noteState = upsert(noteState, entry.proposalId, error, detail)
            return entry.copy(
                failClosed = entry.failClosed || failClosed,
                errors = if (entry.errors.contains(error)) entry.errors else entry.errors + error,
            )
        }

        val next = s0.entries.mapValues { (_, entry) ->
            val id = entry.identity ?: return@mapValues entry

            val compat = e.snapshot.containedTreatments.map { it to id.compatibility(it) }
            // R79-F3: ein Widerspruch darf nicht als Nichttreffer verschwinden.
            val conflict = compat.firstOrNull { it.second == IdentityMatch.CONFLICT }
            if (conflict != null)
                return@mapValues note(
                    entry, LedgerError.IDENTITY_CONFLICT,
                    "snapshot $viewHash: $id vs ${conflict.first}", failClosed = true
                )

            // R91-F2: NIE firstOrNull. Zwei passende Fakten fuer dieselbe
            // gebundene Identitaet haetten sonst je nach Listenreihenfolge
            // verschiedene Restmengen ergeben - aus demselben Inhalt.
            val hits = compat.filter { it.second == IdentityMatch.MATCH }.map { it.first }
            if (hits.size > 1)
                return@mapValues note(
                    entry, LedgerError.AMBIGUOUS_TREATMENT_IDENTITY,
                    "snapshot $viewHash: ${hits.size} facts for $id", failClosed = true
                )

            val hit = hits.firstOrNull()

            // R91-F1: FEHLT der zuvor nachgewiesene Fakt in der aktuellen
            // Vollsicht, wird die Buchung zurueckgenommen. Sonst waere die
            // Menge auf BEIDEN Seiten verschwunden: nicht mehr im Bestands-IOB
            // und wegen der veralteten Buchung auch nicht im Transportrest.
            if (hit == null) {
                // R93-F4: auch die BESTAETIGTE Abwesenheit ist eine Aussage der
                // aktuellen Vollsicht - sonst kann ein spaeterer Cycle-Snapshot
                // nicht belegen, dass alle Zeilen gegen dieselbe Sicht
                // abgeglichen wurden.
                val seen = entry.copy(lastReconciledViewHash = viewHash, lastReconciledAtTs = e.snapshot.calculatedAt)
                if ((entry.accountedAmountU ?: 0.0) <= entry.amountEpsU) return@mapValues seen
                return@mapValues note(
                    entry.copy(
                        accountedAmountU = 0.0,
                        accounting = AccountingState.NOT_ACCOUNTED,
                        lastReconciledViewHash = viewHash,
                        lastReconciledAtTs = e.snapshot.calculatedAt,
                    ),
                    LedgerError.MISSING_ACCOUNTED_TREATMENT,
                    "snapshot $viewHash: previously accounted ${entry.accountedAmountU} gone",
                    failClosed = true,
                )
            }

            // Ein kaputter Betrag im Snapshot bucht nicht aus: der Nachweis
            // "diese Menge steckt im IOB" waere dann selbst ungueltig.
            if (!LedgerRules.isStorableAmount(hit.amountU))
                return@mapValues note(
                    entry, LedgerError.NON_FINITE_AMOUNT, "snapshot amount=${hit.amountU}", failClosed = true
                )

            // R89-F2: fuer "Nullnachweis gegen positiven Fakt" entscheidet
            // Positivitaet, nicht die Pumpenrundung.
            val positiveFact = hit.amountU > entry.amountEpsU
            val sameAmount = LedgerRules.sameAmount(entry.accountedAmountU, hit.amountU, entry.amountEpsU)

            // R91-F1: der aktuelle Fakt gilt, AUCH wenn er 0 ist. Vorher liess
            // ein auf 0 korrigierter Fakt die alte Buchung stehen.
            var reconciled = entry.copy(
                accounting = if (positiveFact) AccountingState.IOB_ACCOUNTED else AccountingState.NOT_ACCOUNTED,
                accountedAmountU = hit.amountU,
                // Der ERSTE Nachweis bleibt historische Provenienz (R91-F5);
                // die aktuelle Mitgliedschaftsaussage traegt lastReconciled*.
                firstAccountedSnapshotHash =
                    if (positiveFact) entry.firstAccountedSnapshotHash ?: viewHash else entry.firstAccountedSnapshotHash,
                lastReconciledViewHash = viewHash,
                lastReconciledAtTs = e.snapshot.calculatedAt,
                // NUR der positive Fakt bewegt die Wirkfrist. Ein Fakt mit
                // Menge 0 ist so wenig ein Lebenszeichen wie gar kein Fakt -
                // beide sagen "hier wirkt nichts", und genau das soll die Zeile
                // verfallen lassen und nicht am Leben halten.
                lastPositiveFactTs = if (positiveFact) e.snapshot.calculatedAt else entry.lastPositiveFactTs,
                amounts = if (entry.amounts.dbAccountedU == null && positiveFact)
                    entry.amounts.copy(dbAccountedU = hit.amountU) else entry.amounts,
                corrections = if (entry.accountedAmountU != null && !sameAmount) entry.corrections + 1
                else entry.corrections,
            )

            if (positiveFact && entry.debtFreeingReject)
            // Die Menge steckt nachweislich im IOB, bindet also nicht mehr als
            // Transportmenge. Der Widerspruch bleibt trotzdem sichtbar.
                reconciled = note(
                    reconciled, LedgerError.PHASE_VIOLATION,
                    "accounted after ${entry.queueReject?.name ?: "withdrawal"}: snapshot $viewHash",
                    failClosed = true,
                ).copy(contradicted = true)

            // Zwei NACHWEISE koennen sich nicht widersprechen.
            if (entry.delivery == DeliveryState.CONFIRMED_ZERO && positiveFact)
                reconciled = note(
                    reconciled, LedgerError.IMPOSSIBLE_STATE_CONFLICT,
                    "CONFIRMED_ZERO vs accounted amount=${hit.amountU}", failClosed = true
                )

            // R91-F4: mehr gebucht als gehaftet ist konservativ, aber nicht
            // selbstverstaendlich - es kann auf eine Fehlbindung hindeuten.
            if (reconciled.overAccounted)
                reconciled = note(
                    reconciled, LedgerError.OVERACCOUNTED_CONSERVATIVE,
                    "accounted=${hit.amountU} > gross=${entry.grossLiabilityU}", failClosed = false
                )

            reconciled
        }

        // Wert-, nicht Flaggenvergleich: eine Reconciliation ohne inhaltliche
        // Folge darf den Zustand nicht veraendern.
        val touched = next.any { (k, v) -> v != s0.entries[k] }
        val orderChanged = state.lastSnapshotOrder != order || state.lastSnapshotViewHash != viewHash
        return if (touched || noteState.errors !== s0.errors || orderChanged || s0 !== state)
            noteState.copy(entries = next, lastSnapshotOrder = order, lastSnapshotViewHash = viewHash)
        else s0
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

    /**
     * Fehler-UPSERT statt Anhaengen (R93-F5). Derselbe Fehler derselben Zeile
     * bleibt EIN Eintrag mit Zaehler; nur die Detailprobe wandert mit.
     */
    /**
     * Fehler-UPSERT statt Anhaengen (R93-F5), jetzt mit Aktiv-Semantik (R95-F1).
     *
     * Ein bereits quittierter Fehler, der ERNEUT auftritt, wird wieder aktiv —
     * eine Unterschrift gilt fuer das Gesehene, nicht fuer die Zukunft.
     */
    private fun upsert(
        state: LedgerState,
        proposalId: String?,
        error: LedgerError,
        detail: String,
    ): LedgerState {
        val errors = state.errors
        val idx = errors.indexOfFirst { it.proposalId == proposalId && it.error == error }
        val failClosed = error in LedgerState.FAIL_CLOSED_ERRORS
        if (idx < 0) {
            val gen = if (failClosed) state.holdGeneration + 1 else state.holdGeneration
            return state.copy(
                errors = errors + LedgerErrorRecord(proposalId, error, detail, detail, 1, activeGeneration = gen),
                holdGeneration = gen,
            )
        }
        val old = errors[idx]
        // Der Zaehler waechst, die Darstellung nicht.
        val reactivated = !old.active && failClosed
        val gen = if (reactivated) state.holdGeneration + 1 else state.holdGeneration
        val updated = old.copy(
            lastDetail = detail,
            occurrences = old.occurrences + 1,
            active = true,
            activeGeneration = if (reactivated) gen else old.activeGeneration,
            resolvedBy = if (reactivated) null else old.resolvedBy,
            resolvedReason = if (reactivated) null else old.resolvedReason,
            resolvedGeneration = if (reactivated) null else old.resolvedGeneration,
        )
        return state.copy(
            errors = errors.toMutableList().also { it[idx] = updated },
            holdGeneration = gen,
        )
    }

    private fun fail(
        state: LedgerState,
        proposalId: String?,
        error: LedgerError,
        detail: String,
        markEntry: Boolean = false,
    ): LedgerState {
        val withError = upsert(state, proposalId, error, detail)
        val entries =
            if (markEntry && proposalId != null && withError.entries.containsKey(proposalId))
                withError.entries + (proposalId to withError.entries.getValue(proposalId).failed(error))
            else withError.entries
        return if (entries === withError.entries) withError else withError.copy(entries = entries)
    }

    private fun ProposalEntry.failed(error: LedgerError): ProposalEntry =
        if (failClosed && errors.contains(error)) this
        else copy(failClosed = true, errors = if (errors.contains(error)) errors else errors + error)
}
