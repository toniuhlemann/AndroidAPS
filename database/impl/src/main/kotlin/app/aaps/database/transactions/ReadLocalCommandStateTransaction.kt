package app.aaps.database.transactions

import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandOwnership

/**
 * LocalCommandChannel — Status-Transaktion: liefert die aktive Ownership (fuer
 * GET_SERVICE_STATUS.ownedTt inkl. CAS-Tokens nach Viewer-Neustart, R4 §1) und optional
 * das persistierte Outcome einer queryRequestId (GET_COMMAND_STATUS, R3-F5).
 *
 * R6-F4: VOR dem Export laeuft dieselbe Reconciliation-Regel wie in der Mutation
 * (ExecuteLocalTtCommandTransaction.reconcileOwnership): abgelaufene, fremd ersetzte oder
 * inhaltlich geaenderte Ownership wird NIE als OWNED gemeldet (sie wird dabei atomar
 * terminalisiert, reine NS-ID-Echos werden fortgeschrieben — so bekommt der Viewer im
 * Preflight immer FRISCHE Tokens statt eines vermeidbaren STATE_CONFLICT). Es werden
 * ausschliesslich Ownership-Zeilen geschrieben, niemals TTs; Retention unveraendert.
 */
class ReadLocalCommandStateTransaction(
    private val nowMs: Long,
    private val queryRequestId: String? = null,
) : Transaction<ReadLocalCommandStateTransaction.Result>() {

    data class Result(
        val ownership: LocalCommandOwnership?,
        val queriedOutcome: LocalCommandOutcome?,
    )

    override fun run(): Result {
        val (_, ownership) = ExecuteLocalTtCommandTransaction.reconcileOwnership(database, nowMs)
        return Result(
            ownership = ownership,
            queriedOutcome = queryRequestId?.let { database.localCommandDao.findOutcome(it) },
        )
    }
}
