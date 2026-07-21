package app.aaps.database.transactions

/**
 * Capability-Matrix A1 — nachlaufende Lease-Terminalisierung (R10-F2 §5 + R12-F1:
 * IDENTITAETSGEBUNDEN). Rein metadatisch, nie therapeutisch — der RAM-Widerruf des
 * Coordinators ist laengst wirksam.
 *
 * CAS-Regel (R12-F1): terminalisiert NUR, wenn die aktive Zeile exakt (leaseId,
 * leaseVersion) entspricht — ein Nachfolger kann nie getroffen werden. Identitaetsfreie
 * Aufrufe (leaseId=null) sind AUSSCHLIESSLICH fuer PROCESS_RESTART erlaubt (Boot: RAM
 * leer, jede aktive Zeile stammt sicher aus dem Vorprozess).
 */
class TerminalizeValueLeaseTransaction(
    private val capability: String,
    private val reason: String,
    private val nowMs: Long,
    private val leaseId: String? = null,
    private val leaseVersion: Long? = null,
) : Transaction<Boolean>() {

    init {
        require(leaseId != null || reason == "PROCESS_RESTART") {
            "identity-free terminalization is reserved for PROCESS_RESTART"
        }
    }

    override fun run(): Boolean {
        val dao = database.localCommandDao
        val active = dao.activeValueLease(capability) ?: return false
        if (leaseId != null && (active.leaseId != leaseId || active.leaseVersion != leaseVersion)) return false
        active.activeSlot = null
        active.terminatedAt = nowMs
        active.terminalReason = reason
        dao.updateValueLease(active)
        return true
    }
}
