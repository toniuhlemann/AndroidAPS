package app.aaps.database.transactions

import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandValueLease

/**
 * Capability-Matrix A1 — DIE Value-Lease-Transaktion (Spec v1.1 §3 + R9-F6 + R10-F2/F4 +
 * R11): Idempotenz, CAS, Lease-Mutation und terminales Outcome inkl. resultJson in EINEM
 * Commit. Der Room-Commit ist der historische Linearization Point — das RAM-Publish
 * (Coordinator) folgt DANACH; diese Transaktion weiss davon nichts.
 *
 * Grundregeln:
 *  - resultJson ist HISTORISCH und unveraenderlich (R11-P1): Replay liefert exakt diesen
 *    String zurueck, unabhaengig vom spaeteren Lease-Zustand.
 *  - VO persistiert genau EIN idempotentes Outcome, aber NIE eine Lease
 *    (leaseCreated=false, runtimeOverrideCreated=false — R10-F5).
 *  - leaseVersion = addExact(max+1) je Capability UEBER Terminalisierungen hinweg;
 *    ein Unique-Konflikt (Parallel-SET) wird deterministisch zu REJECTED_STATE_CONFLICT
 *    (R11-Empfehlung), nie zur generischen DB-Exception.
 *  - CLEAR prueft den ownerPolicyHash der LEASE-ERSTELLUNG (R11/F5).
 */
class ExecuteValueLeaseCommandTransaction(
    private val cmd: Cmd,
    private val capability: String,
    private val requestId: String,
    private val requestHash: String,
    private val nowMs: Long,
    private val validateOnly: Boolean,
    // SET
    private val setPayload: String? = null,          // kanonisch, z.B. {"percent":80}
    private val ttlMin: Int? = null,
    private val expiresAtWallMs: Long? = null,
    private val basePayload: String? = null,         // Basis-Capture des Coordinators
    private val baseGeneration: Long? = null,
    private val gateGeneration: Long? = null,
    private val currentPolicyHash: String? = null,
    private val expectedState: String? = null,       // NONE | OWNED
    private val expectedLeaseId: String? = null,
    private val expectedLeaseVersion: Long? = null,
    private val expectedOwnerPolicyHash: String? = null,   // nur CLEAR
    /** Vorentschiedener Policy-Fehler (Matrix/Hash): wird terminal persistiert. */
    private val policyErrorCode: String? = null,
) : Transaction<ExecuteValueLeaseCommandTransaction.Result>() {

    enum class Cmd { SET, CLEAR }

    data class Result(
        val outcome: String,               // APPLIED | VALIDATED | REJECTED
        val errorCode: String? = null,
        val replayed: Boolean = false,
        val resultJson: String? = null,    // historisches commandResult (R11-P1)
        val leaseId: String? = null,
        val leaseVersion: Long? = null,
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
                if (expectedState == "OWNED") terminalize(active!!, "REPLACED")
                val newVersion = Math.addExact(dao.maxLeaseVersion(capability), 1L)
                val lease = LocalCommandValueLease(
                    capability = capability, leaseId = requestId, leaseVersion = newVersion,
                    ownerPolicyHash = currentPolicyHash!!, basePayload = basePayload!!,
                    baseGeneration = baseGeneration!!, setPayload = setPayload!!,
                    gateGeneration = gateGeneration!!, createdAt = nowMs, expiresAtWallMs = expiresAtWallMs!!,
                )
                try {
                    dao.insertValueLease(lease)
                } catch (_: android.database.sqlite.SQLiteConstraintException) {
                    // R11: Parallel-SET verliert deterministisch — nie eine rohe DB-Exception.
                    return reject("REJECTED_STATE_CONFLICT")
                }
                val rj = """{"leaseCreated":true,"leaseId":"$requestId","leaseVersion":$newVersion,"setPayload":$setPayload,"basePayload":$basePayload,"expiresAtWallMs":$expiresAtWallMs}"""
                persistOutcome("APPLIED", rj)
                Result(outcome = "APPLIED", resultJson = rj, leaseId = requestId, leaseVersion = newVersion)
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
