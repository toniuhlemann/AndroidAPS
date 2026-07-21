package app.aaps.database.transactions

import app.aaps.database.entities.LocalCommandOutcome

/**
 * Capability-Matrix A1 (R12-F6): persistiert das terminale REJECTED-Outcome eines
 * Value-Kommandos in einer SEPARATEN Transaktion, nachdem die Mutations-Transaktion
 * wegen eines Insert-Constraints mutationsneutral beendet wurde. Idempotent: existiert
 * bereits ein Outcome fuer die requestId, wird nichts geschrieben (Replay-Wahrheit
 * gehoert der ersten Persistenz).
 */
class PersistValueLeaseRejectTransaction(
    private val cmdName: String,
    private val capability: String,
    private val requestId: String,
    private val requestHash: String,
    private val errorCode: String,
    private val nowMs: Long,
) : Transaction<Boolean>() {

    override fun run(): Boolean {
        val dao = database.localCommandDao
        if (dao.findOutcome(requestId) != null) return false
        dao.insertOutcome(LocalCommandOutcome(
            requestId = requestId, requestHash = requestHash, cmd = "${cmdName}_$capability",
            outcome = "REJECTED", errorCode = errorCode, appliedAt = null,
            validateOnly = false, createdAt = nowMs, retainUntil = nowMs + 24 * 3_600_000L,
            resultJson = null,
        ))
        return true
    }
}
