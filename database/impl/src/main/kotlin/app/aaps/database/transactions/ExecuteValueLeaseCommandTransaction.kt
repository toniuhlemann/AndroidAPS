package app.aaps.database.transactions

import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandValueLease

/**
 * Capability-Matrix A1 — DIE Value-Lease-Transaktion (Spec v1.1 §3 + R9-F6 + R10-F2/F4 +
 * R11 + R12-F5/F6): Idempotenz, CAS, Lease-Mutation und terminales Outcome inkl.
 * resultJson in EINEM Commit. Room-Commit = historischer Linearization Point.
 *
 * Grundregeln:
 *  - resultJson ist HISTORISCH und unveraenderlich (R11-P1); Replay liefert es exakt.
 *  - VO persistiert genau EIN Outcome, NIE eine Lease (leaseCreated=false).
 *  - leaseVersion = addExact(max+1) UEBER Terminalisierungen hinweg.
 *  - R12-F5: SET@OWNED ERBT basePayload/baseGeneration/gateGeneration der ersetzten Lease
 *    (ein Replacement beginnt NIE still eine neue Basis-/Gate-Kohorte); die tatsaechlich
 *    persistierten Werte werden im Result zurueckgegeben — der RAM-Publish nutzt exakt sie.
 *  - R12-F6: ein Insert-Constraint (Parallel-SET) laesst die Transaktion NICHT teilmutiert
 *    committen: die REPLACED-Terminalisierung wird kompensiert (Zeile restauriert) und
 *    [ValueLeaseConflictException] geworfen — der Aufrufer persistiert das terminale
 *    REJECTED_STATE_CONFLICT anschliessend in einer separaten idempotenten Transaktion.
 */
class ExecuteValueLeaseCommandTransaction(
    private val cmd: Cmd,
    private val capability: String,
    private val requestId: String,
    private val requestHash: String,
    private val nowMs: Long,
    private val validateOnly: Boolean,
    // SET
    private val setPayload: String? = null,
    private val ttlMin: Int? = null,
    private val expiresAtWallMs: Long? = null,
    private val basePayload: String? = null,         // Basis-Capture des Coordinators (nur NONE)
    private val baseGeneration: Long? = null,
    private val gateGeneration: Long? = null,
    private val currentPolicyHash: String? = null,
    private val expectedState: String? = null,       // NONE | OWNED
    private val expectedLeaseId: String? = null,
    private val expectedLeaseVersion: Long? = null,
    private val expectedOwnerPolicyHash: String? = null,   // nur CLEAR
    private val policyErrorCode: String? = null,
) : Transaction<ExecuteValueLeaseCommandTransaction.Result>() {

    enum class Cmd { SET, CLEAR }

    /** R12-F6: signalisiert einen Insert-Constraint NACH vollstaendiger Kompensation —
     *  die Room-Transaktion committet mutationsneutral bzw. rollt zurueck. */
    class ValueLeaseConflictException : RuntimeException("value lease insert conflict")

    data class Result(
        val outcome: String,
        val errorCode: String? = null,
        val replayed: Boolean = false,
        val resultJson: String? = null,
        val leaseId: String? = null,
        val leaseVersion: Long? = null,
        // R12-F5: tatsaechlich persistierte Herkunftswerte (OWNED: geerbt)
        val basePayloadUsed: String? = null,
        val baseGenerationUsed: Long? = null,
        val gateGenerationUsed: Long? = null,
    )

    override fun run(): Result {
        val dao = database.localCommandDao
        // (1) Idempotenz VOR allem: Replay liefert das persistierte historische Resultat.
        dao.findOutcome(requestId)?.let { existing ->
            return if (existing.requestHash == requestHash) Result(
                outcome = existing.outcome, errorCode = existing.errorCode, replayed = true,
                resultJson = existing.resultJson,
            ) else Result(outcome = "REJECTED", errorCode = "REJECTED_REQUEST_ID_REUSE")
        }
        dao.pruneOutcomes(nowMs)

        // (2) Vorentschiedener Policy-Fehler: terminal.
        if (policyErrorCode != null) return reject(policyErrorCode)

        // (3) CAS gegen die aktive Lease dieser Capability.
        val active = dao.activeValueLease(capability)
        when (cmd) {
            Cmd.SET -> when (expectedState) {
                "NONE" -> if (active != null) return reject("REJECTED_STATE_CONFLICT")
                "OWNED" -> {
                    val a = active ?: return reject("REJECTED_STATE_CONFLICT")
                    if (a.leaseId != expectedLeaseId || a.leaseVersion != expectedLeaseVersion)
                        return reject("REJECTED_STATE_CONFLICT")
                }
                else -> return reject("REJECTED_MALFORMED")
            }
            Cmd.CLEAR -> {
                val a = active ?: return reject("REJECTED_STATE_CONFLICT")
                if (a.leaseId != expectedLeaseId || a.leaseVersion != expectedLeaseVersion)
                    return reject("REJECTED_STATE_CONFLICT")
                if (a.ownerPolicyHash != expectedOwnerPolicyHash) return reject("REJECTED_POLICY_VERSION")
            }
        }

        // (4) validateOnly: EIN idempotentes VALIDATED-Outcome, KEINE Lease, kein Override.
        if (validateOnly) {
            val rj = when (cmd) {
                Cmd.SET -> """{"leaseCreated":false,"runtimeOverrideCreated":false,"wouldSetPayload":${setPayload},"validatedTtlMin":${ttlMin}}"""
                Cmd.CLEAR -> """{"leaseCreated":false,"runtimeOverrideCreated":false,"wouldClear":true}"""
            }
            persistOutcome("VALIDATED", rj)
            return Result(outcome = "VALIDATED", resultJson = rj)
        }

        // (5) Mutation.
        return when (cmd) {
            Cmd.SET -> {
                // R12-F5: bei Replacement erben — die Kohorte des ERSTEN SET bleibt bestehen.
                val usedBasePayload = if (expectedState == "OWNED") active!!.basePayload else basePayload!!
                val usedBaseGen = if (expectedState == "OWNED") active!!.baseGeneration else baseGeneration!!
                val usedGateGen = if (expectedState == "OWNED") active!!.gateGeneration else gateGeneration!!
                val replacedSnapshotForRestore = active?.let {
                    Triple(it.activeSlot, it.terminatedAt, it.terminalReason)
                }
                if (expectedState == "OWNED") terminalize(active!!, "REPLACED")
                val newVersion = Math.addExact(dao.maxLeaseVersion(capability), 1L)
                val lease = LocalCommandValueLease(
                    capability = capability, leaseId = requestId, leaseVersion = newVersion,
                    ownerPolicyHash = currentPolicyHash!!, basePayload = usedBasePayload,
                    baseGeneration = usedBaseGen, setPayload = setPayload!!,
                    gateGeneration = usedGateGen, createdAt = nowMs, expiresAtWallMs = expiresAtWallMs!!,
                )
                try {
                    dao.insertValueLease(lease)
                } catch (_: android.database.sqlite.SQLiteConstraintException) {
                    // R12-F6: mutationsneutral bleiben — REPLACED kompensieren, dann Marker.
                    if (expectedState == "OWNED" && active != null && replacedSnapshotForRestore != null) {
                        active.activeSlot = replacedSnapshotForRestore.first
                        active.terminatedAt = replacedSnapshotForRestore.second
                        active.terminalReason = replacedSnapshotForRestore.third
                        dao.updateValueLease(active)
                    }
                    throw ValueLeaseConflictException()
                }
                val rj = """{"leaseCreated":true,"leaseId":"$requestId","leaseVersion":$newVersion,"setPayload":$setPayload,"basePayload":$usedBasePayload,"expiresAtWallMs":$expiresAtWallMs}"""
                persistOutcome("APPLIED", rj)
                Result(
                    outcome = "APPLIED", resultJson = rj, leaseId = requestId, leaseVersion = newVersion,
                    basePayloadUsed = usedBasePayload, baseGenerationUsed = usedBaseGen, gateGenerationUsed = usedGateGen,
                )
            }
            Cmd.CLEAR -> {
                terminalize(active!!, "CLEARED")
                val rj = """{"leaseCreated":false,"cleared":true,"leaseId":"${active.leaseId}","leaseVersion":${active.leaseVersion}}"""
                persistOutcome("APPLIED", rj)
                Result(outcome = "APPLIED", resultJson = rj, leaseId = active.leaseId, leaseVersion = active.leaseVersion)
            }
        }
    }

    private fun terminalize(lease: LocalCommandValueLease, reason: String) {
        lease.activeSlot = null
        lease.terminatedAt = nowMs
        lease.terminalReason = reason
        database.localCommandDao.updateValueLease(lease)
    }

    private fun reject(code: String): Result {
        persistOutcome("REJECTED", null, code)
        return Result(outcome = "REJECTED", errorCode = code)
    }

    private fun persistOutcome(outcome: String, resultJson: String?, errorCode: String? = null) {
        database.localCommandDao.insertOutcome(LocalCommandOutcome(
            requestId = requestId, requestHash = requestHash, cmd = "${cmd.name}_$capability",
            outcome = outcome, errorCode = errorCode, appliedAt = if (outcome == "APPLIED") nowMs else null,
            validateOnly = validateOnly, createdAt = nowMs, retainUntil = nowMs + 24 * 3_600_000L,
            resultJson = resultJson,
        ))
    }
}
