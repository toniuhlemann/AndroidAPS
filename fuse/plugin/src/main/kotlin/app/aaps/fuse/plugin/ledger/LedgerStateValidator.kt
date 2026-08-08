package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.DeliveryState
import app.aaps.fuse.core.ledger.LedgerError
import app.aaps.fuse.core.ledger.LedgerPhase
import app.aaps.fuse.core.ledger.LedgerRules
import app.aaps.fuse.core.ledger.LedgerState
import app.aaps.fuse.core.ledger.ProposalEntry

/**
 * GANZHEITLICHE Zustandspruefung nach dem JSON-Decode (Re-Audit c750169,
 * REG-04 / 6.2).
 *
 * Der Codec prueft Feld-RANGES (finite, >= 0, <= Obergrenze); hier stehen die
 * INVARIANTEN ZWISCHEN den Feldern. Der Unterschied ist nicht kosmetisch:
 * eine Datei kann in jedem Einzelfeld gueltig sein und trotzdem eine
 * Buchhaltung behaupten, die dieser Code nie geschrieben haben kann - etwa
 * ein zweiter Eintrag derselben proposalId, der ein offenes Commitment per
 * `CONFIRMED_ZERO` ohne jeden Nachweis auf 0 setzt (der Re-Audit-Repro).
 *
 * JEDER Verstoss wirft: der Wurf zaehlt beim Laden als "nicht lesbar"
 * (readNewestValid weicht auf eine aeltere gueltige Generation aus bzw. der
 * Adapter setzt recoveryHold), NIE als Leerstart oder als Uebernahme.
 *
 * TOLERANZ-GRUNDSATZ: was der Reducer selbst als BEFUND persistiert (die
 * Zeile traegt den zugehoerigen [LedgerError] und sperrt ueber failClosed/
 * holdActuation), ist ein GUELTIGER gespeicherter Zustand - der Hold steckt
 * dann schon im State selbst. Abgelehnt wird nur, was KEINE selbst
 * geschriebene Datei tragen kann.
 */
object LedgerStateValidator {

    /**
     * Toleranz fuer `identity.treatmentTimestamp` VOR `decisionTs` (R4-02 c).
     *
     * WARUM es die Toleranz braucht: beim Binden der pumpId ueberschreibt
     * `SyncBolusWithTempIdTransaction` timestamp UND amount des Datensatzes -
     * die pumpenbestaetigte Zeit gewinnt und kann hinter der Telefonuhr
     * liegen. Die Bindung selbst verlangte `timestamp >= decisionTs`; mehr
     * als dieser begrenzte Uhrenversatz rueckwaerts kann keine eigene Datei
     * tragen.
     */
    const val TREATMENT_BEFORE_DECISION_TOLERANCE_MS = 10 * 60_000L

    /**
     * Die Entry-LISTE pruefen, BEVOR associateBy sie zur Map faltet
     * (Re-Audit REG-04): zwei Eintraege derselben Id waeren nach dem Falten
     * unsichtbar - der letzte gewinnt still, und genau so kann ein spaeterer
     * unbewiesener Eintrag einen frueheren offenen ueberschreiben.
     */
    fun requireUniqueIds(entries: List<ProposalEntry>) {
        val seen = HashSet<String>(entries.size * 2)
        for (e in entries) {
            require(e.proposalId.isNotBlank()) { "blank proposalId in ledger entries" }
            require(seen.add(e.proposalId)) { "duplicate proposalId ${e.proposalId} in ledger entries" }
        }
    }

    /**
     * @param schemaVersion Herkunftsversion der Datei (R4-02): Version 1
     * (Bestandsdateien) behaelt die Legacy-Toleranzen, ab [LedgerCodec.STRICT_VERSION]
     * gelten zusaetzlich die strikten Lade-Invarianten ([validateStrict]).
     * Die ZUSTANDSMASCHINEN-Pruefungen ([validateReducerReachability]) gelten
     * fuer JEDE Version - sie bilden Kombinationen ab, die KEINE je
     * ausgelieferte Reducer-Version schreiben konnte (der Reject-vor-Annahme-
     * Vertrag ist aelter als die Persistenz selbst).
     */
    fun validate(state: LedgerState, schemaVersion: Int = LedgerCodec.VERSION) {
        for (e in state.entries.values) {
            validateEntry(e)
            validateReducerReachability(e)
            if (schemaVersion >= LedgerCodec.STRICT_VERSION) validateStrict(state, e)
        }
        state.lastSnapshotOrder?.let { order ->
            require(order.sourceEpochId.isNotBlank()) { "snapshot order with blank sourceEpochId" }
            require(order.calculatorGeneration >= 0L) { "negative calculatorGeneration ${order.calculatorGeneration}" }
            require(order.calculatedAt >= 0L) { "negative calculatedAt ${order.calculatedAt}" }
        }
    }

    /**
     * Ab Schemaversion 2 MUSS jede Zeile einen Pin-Eintrag tragen - auch
     * UNPINNED oder legacyOpen sind explizite Aussagen (Codex R4-02/R4-03:
     * eine aus einer aktuellen Datei ENTFERNTE Pinnung wurde vorher still
     * als Legacy gedeutet und band wieder alles).
     */
    fun requirePinCoverage(state: LedgerState, coveredIds: Set<String>) {
        for (id in state.entries.keys)
            require(id in coveredIds) { "entry $id without pump epoch pin (schema >= ${LedgerCodec.STRICT_VERSION} requires explicit pin)" }
    }

    /**
     * Zustandsmaschine des Reducers als LADE-INVARIANTEN (Codex R4-02).
     *
     * Der Massstab ist WOERTLICH der Reducer: jede Regel hier bildet die
     * Vorbedingung eines Handlers ab, unter der er den jeweiligen Zustand
     * ueberhaupt schreiben kann. Der Codex-Repro war ein Debt-Release per
     * Handauflegen: `PUBLISHED + queueReject` ohne Liefersignal passierte den
     * Decode, und `debtReleaseEffective` setzte ein offenes Commitment ohne
     * jeden Beweis auf 0.
     */
    private fun validateReducerReachability(e: ProposalEntry) {
        // onAmount: RT_PUBLISHED hebt PREPARED IMMER nach PUBLISHED - ein
        // PREPARED mit rtPublishedU (oder umgekehrt) ist keine eigene Datei.
        if (e.phase == LedgerPhase.PREPARED)
            require(e.amounts.rtPublishedU == null) { "PREPARED with rtPublishedU (${e.proposalId})" }
        if (e.phase == LedgerPhase.PUBLISHED)
            require(e.amounts.rtPublishedU != null) { "PUBLISHED without rtPublishedU (${e.proposalId})" }
        // onExecutionResult setzt terminalSeen NUR zusammen mit TERMINAL,
        // und keine Phase faellt je wieder zurueck.
        if (e.terminalSeen)
            require(e.phase == LedgerPhase.TERMINAL) { "terminalSeen in phase ${e.phase} (${e.proposalId})" }
        // JEDER Widerspruchspfad des Reducers persistiert einen BEFUND an der
        // Zeile - contradicted ohne ihn ist Fremdinhalt. Neben dem
        // Reject-/Rueckzugs-Widerspruch (PHASE_VIOLATION) gibt es seit G3
        // (Codex-Adjudication bae885f1) den Nachweis-Widerspruch: bewiesene
        // Null gegen positive Liefermeldung -> IMPOSSIBLE_STATE_CONFLICT.
        if (e.contradicted)
            require(
                LedgerError.PHASE_VIOLATION in e.errors || LedgerError.IMPOSSIBLE_STATE_CONFLICT in e.errors
            ) { "contradicted without PHASE_VIOLATION/IMPOSSIBLE_STATE_CONFLICT (${e.proposalId})" }
        // Der schuldbefreiende Reject/Rueckzug (onQueueRejected/onWithdrawn):
        // beide setzen phase=TERMINAL, beide verlangen VORHER weder Annahme
        // noch Terminalereignis noch Pumpenkommando - und JEDES spaetere
        // Signal (Stufe, Annahme, ExecutionResult, positiver Nachweis)
        // markiert contradicted. Ein unwidersprochener Reject/Rueckzug kann
        // deshalb NUR so aussehen; alles andere wuerde ein offenes Commitment
        // ohne Beweis auf 0 setzen (commitmentU rechnet direkt aus diesen
        // Feldern).
        if (e.debtFreeingReject && !e.contradicted) {
            require(e.phase == LedgerPhase.TERMINAL) {
                "uncontradicted reject/withdrawal in phase ${e.phase} (${e.proposalId})"
            }
            require(!e.terminalSeen) { "uncontradicted reject/withdrawal with terminal result (${e.proposalId})" }
            require(e.amounts.pumpCommandU == null) {
                "uncontradicted reject/withdrawal with pump command (${e.proposalId})"
            }
            require(e.amounts.reportedDeliveredU == null) {
                "uncontradicted reject/withdrawal with reported delivery (${e.proposalId})"
            }
            // onDeliveryProven widerspricht bei JEDER positiven Menge -
            // stehen bleiben darf nur der exakte Nullnachweis.
            e.amounts.provenDeliveredU?.let { proven ->
                require(proven <= 0.0) { "uncontradicted reject/withdrawal with proven=$proven (${e.proposalId})" }
            }
        }
    }

    /**
     * STRIKTE Lade-Invarianten ab Schemaversion 2 (R4-02 c/d). Fuer Version-1-
     * Bestandsdateien bewusst NICHT erzwungen - heutige Dateien muessen
     * ladbar bleiben, auch wenn ein aelterer Schreiber hier abweicht.
     */
    private fun validateStrict(state: LedgerState, e: ProposalEntry) {
        // decisionTs ist die Wanduhrzeit der Entscheidung - 0 oder negativ
        // schreibt kein eigener Prozess.
        require(e.decisionTs > 0L) { "decisionTs ${e.decisionTs} not positive (${e.proposalId})" }
        e.identity?.let { id ->
            require(id.treatmentTimestamp > 0L) { "treatmentTimestamp ${id.treatmentTimestamp} not positive (${e.proposalId})" }
            // Die Bindung verlangte timestamp >= decisionTs; die pumpen-
            // bestaetigte Korrektur darf nur um den begrenzten Uhrenversatz
            // zurueckliegen (s. TREATMENT_BEFORE_DECISION_TOLERANCE_MS).
            require(id.treatmentTimestamp >= e.decisionTs - TREATMENT_BEFORE_DECISION_TOLERANCE_MS) {
                "treatmentTimestamp ${id.treatmentTimestamp} before decisionTs ${e.decisionTs} beyond tolerance (${e.proposalId})"
            }
        }
        // R4-02 (d): der failClosed-Latch entsteht im Reducer NUR zusammen
        // mit einem Fehlereintrag der Zeile UND einem aktiven globalen
        // Record derselben proposalId (die Quittung rechnet ihn zurueck).
        // Ein Latch ohne Befund waere eine Sperre ohne Begruendung - oder
        // umgekehrt ein manipulierter Zustand, der echte Befunde verdeckt.
        if (e.failClosed) {
            require(e.errors.isNotEmpty()) { "failClosed without entry errors (${e.proposalId})" }
            require(state.errors.any { it.active && it.proposalId == e.proposalId }) {
                "failClosed without active error record (${e.proposalId})"
            }
        }
    }

    private fun validateEntry(e: ProposalEntry) {
        // Gepinnte Policies STRIKT positiv: mit eps/step = 0 wuerden alle
        // Mengenvergleiche bzw. die Tick-Kanonisierung entarten (Division
        // durch 0 erst zur Laufzeit, mitten in einer Buchung).
        require(e.amountEpsU > 0.0) { "amountEpsU not strictly positive: ${e.amountEpsU} (${e.proposalId})" }
        require(e.bolusStepU > 0.0) { "bolusStepU not strictly positive: ${e.bolusStepU} (${e.proposalId})" }
        // Die Identitaet GEHOERT zur Zeile: der Reducer baut sie immer mit
        // der Id des Eintrags. Eine fremde Id hiesse, ein Nachweis einer
        // anderen Zeile wurde hierher kopiert.
        e.identity?.let {
            require(it.proposalId == e.proposalId) {
                "identity.proposalId ${it.proposalId} != entry ${e.proposalId}"
            }
        }
        // CONFIRMED_ZERO NUR mit persistiertem Nachweis: der Reducer setzt
        // den Wert ausschliesslich in onDeliveryProven, und der schreibt
        // IMMER provenDeliveredU (die Null-Lieferung ist dort der Beleg).
        // Eine Datei mit CONFIRMED_ZERO ohne proven-Feld - oder mit einem
        // positiven proven - kann keine eigene sein; sie wuerde ein offenes
        // Commitment ohne Beweis auf 0 setzen (closed/commitmentU rechnen
        // direkt aus dem Enum).
        if (e.delivery == DeliveryState.CONFIRMED_ZERO) {
            val proven = e.amounts.provenDeliveredU
            require(proven != null && LedgerRules.canonicalTicks(proven, e.bolusStepU) == 0L) {
                "CONFIRMED_ZERO without persisted zero proof (provenDeliveredU=$proven, ${e.proposalId})"
            }
        }
        // Mengenachse monoton fallend, soweit Stufen vorhanden sind - AUSSER
        // die Zeile traegt den Verstoss bereits als Befund (der Reducer
        // persistiert eine verletzte Kette MIT CONSTRAINT_CHAIN_INVALID und
        // failClosed; die sperrt ueber holdActuation, nicht ueber den Hold
        // des Laders). Ohne den Befund ist die Verletzung Fremdinhalt.
        val violation = LedgerRules.chainViolation(e.amounts, e.amountEpsU)
        require(violation == null || LedgerError.CONSTRAINT_CHAIN_INVALID in e.errors) {
            "amount axis not monotonic ($violation) without recorded CONSTRAINT_CHAIN_INVALID (${e.proposalId})"
        }
    }
}
