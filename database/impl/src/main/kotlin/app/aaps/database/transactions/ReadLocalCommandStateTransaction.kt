package app.aaps.database.transactions

import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandOwnership

/**
 * LocalCommandChannel — read-only Status-Transaktion: liefert die aktive Ownership (fuer
 * GET_SERVICE_STATUS.ownedTt inkl. CAS-Tokens nach Viewer-Neustart, R4 §1) und optional
 * das persistierte Outcome einer queryRequestId (GET_COMMAND_STATUS, R3-F5). Mutiert
 * nichts, verlaengert keine Retention.
 */
class ReadLocalCommandStateTransaction(
    private val queryRequestId: String? = null,
) : Transaction<ReadLocalCommandStateTransaction.Result>() {

    data class Result(
        val ownership: LocalCommandOwnership?,
        val queriedOutcome: LocalCommandOutcome?,
    )

    override fun run(): Result = Result(
        ownership = database.localCommandDao.activeOwnership(),
        queriedOutcome = queryRequestId?.let { database.localCommandDao.findOutcome(it) },
    )
}
