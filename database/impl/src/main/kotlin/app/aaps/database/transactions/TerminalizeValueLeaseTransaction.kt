package app.aaps.database.transactions

/**
 * Capability-Matrix A1 — nachlaufende Lease-Terminalisierung (R10-F2 §5: rein metadatisch,
 * nie therapeutisch — der RAM-Widerruf des Coordinators ist laengst wirksam). Aufrufer:
 * Service beim Konsumieren von pendingRoomTerminal, beim Prozessstart (PROCESS_RESTART)
 * und bei Gate-/TTL-Latches. No-op wenn keine aktive Lease existiert.
 */
class TerminalizeValueLeaseTransaction(
    private val capability: String,
    private val reason: String,
    private val nowMs: Long,
) : Transaction<Boolean>() {

    override fun run(): Boolean {
        val dao = database.localCommandDao
        val active = dao.activeValueLease(capability) ?: return false
        active.activeSlot = null
        active.terminatedAt = nowMs
        active.terminalReason = reason
        dao.updateValueLease(active)
        return true
    }
}
