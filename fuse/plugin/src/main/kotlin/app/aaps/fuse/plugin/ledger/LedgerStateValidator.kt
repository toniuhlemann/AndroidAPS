package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.DeliveryState
import app.aaps.fuse.core.ledger.LedgerError
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

    fun validate(state: LedgerState) {
        for (e in state.entries.values) validateEntry(e)
        state.lastSnapshotOrder?.let { order ->
            require(order.sourceEpochId.isNotBlank()) { "snapshot order with blank sourceEpochId" }
            require(order.calculatorGeneration >= 0L) { "negative calculatorGeneration ${order.calculatorGeneration}" }
            require(order.calculatedAt >= 0L) { "negative calculatedAt ${order.calculatedAt}" }
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
