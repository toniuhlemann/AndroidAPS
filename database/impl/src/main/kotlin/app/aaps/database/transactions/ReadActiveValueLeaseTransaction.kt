package app.aaps.database.transactions

/**
 * Liest die aktive Wert-Lease einer Capability, ohne sie zu veraendern.
 *
 * Grund (Re-Review B7, 25.07.): nach einem Prozesstod ist die RAM-Lease weg, die Room-Zeile
 * traegt die Basis aber noch. Ohne diesen Read wurde beim Neustart nur terminalisiert und der
 * ueberlagerte Wert blieb DAUERHAFT stehen — bei einem Automation-State wie MEAL_ACTIVE heisst
 * das, dass ein OOM-Kill mitten in einer Lease die Automationen dauerhaft umsteuert.
 *
 * Rein lesend; die Entscheidung, ob zurueckgeschrieben wird, faellt der Koordinator (nur wenn
 * noch UNSER Wert steht).
 */
class ReadActiveValueLeaseTransaction(
    private val capability: String,
) : Transaction<ReadActiveValueLeaseTransaction.Result>() {

    /** [found] statt eines nullbaren Ergebnisses: Transaction<T> verlangt T : Any. */
    data class Result(
        val found: Boolean,
        val leaseId: String = "",
        val leaseVersion: Long = 0L,
        val basePayload: String = "",
        val setPayload: String = "",
    )

    override fun run(): Result {
        val active = database.localCommandDao.activeValueLease(capability) ?: return Result(false)
        return Result(true, active.leaseId, active.leaseVersion, active.basePayload, active.setPayload)
    }
}
