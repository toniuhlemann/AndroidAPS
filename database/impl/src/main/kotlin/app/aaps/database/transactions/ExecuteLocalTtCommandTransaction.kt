package app.aaps.database.transactions

import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandOwnership
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.interfaces.end

/**
 * LocalCommandChannel — DIE eine Room-Transaktion (Spec v1.2 B1/B2/B3, R3-F3, R5-Pilot-Bau):
 * Idempotenz-Recheck, Ownership-/CAS-Pruefung, TT-Mutation, Ownership-Fortschreibung und
 * terminales Outcome in EINEM Commit. Der Service-Mutex ordnet nur die eigenen Requests;
 * die Sicherheit gegen fremde Schreiber (manuell, NS-Sync) haengt an DIESER Transaktion
 * (TOCTOU-frei: alle Pruefungen lesen innerhalb desselben Commits).
 *
 * Grundregeln (nicht verhandelbar):
 *  - Ein fremdes aktives TT wird NIE ersetzt oder beendet (REJECTED_NOT_OWNED) —
 *    manuelle TTs sind strukturell unantastbar.
 *  - CAS: verspaetete, einzeln gueltige Requests koennen einen neueren eigenen Zustand
 *    nie ueberschreiben (REJECTED_STATE_CONFLICT).
 *  - NS-ID-Echo-Ausnahme: identischer Therapieinhalt bei anderer Entity-Version wird als
 *    Version-Fortschreibung uebernommen; jede INHALTLICHE Fremdaenderung terminiert die
 *    Ownership. Zielgleichheit begruendet nie Ownership.
 *  - validateOnly mutiert nichts, persistiert aber ein terminales VALIDATED-Outcome.
 */
class ExecuteLocalTtCommandTransaction(
    private val cmd: Cmd,
    private val requestId: String,
    private val requestHash: String,
    private val nowMs: Long,
    private val validateOnly: Boolean,
    // SET
    private val targetMgdl: Int? = null,
    private val durationMin: Int? = null,
    private val reasonKey: String? = null,          // Protokoll-ReasonKey (Name)
    private val ttReason: TemporaryTarget.Reason? = null,
    private val currentPolicyHash: String? = null,
    private val expectedState: String? = null,      // NONE | OWNED
    // CAS-Tokens (SET-OWNED + CANCEL)
    private val expectedOwnerRequestId: String? = null,
    private val expectedTtDbId: Long? = null,
    private val expectedTtEntityVersion: Int? = null,
    private val expectedOwnerPolicyHash: String? = null,   // nur CANCEL
    /** Vom Domain-Glue vorentschiedener Policy-Fehler (Matrix/Hash, statisch — kein Race):
     *  wird HIER terminal persistiert, damit Retries das Original sehen (R2-B4). */
    private val policyErrorCode: String? = null,
    private val rateCapPerHour: Int,
) : Transaction<ExecuteLocalTtCommandTransaction.Result>() {

    enum class Cmd { SET, CANCEL }

    data class Result(
        val outcome: String,               // APPLIED | VALIDATED | REJECTED
        val errorCode: String? = null,
        val replayed: Boolean = false,
        val appliedAt: Long? = null,
        val ttDbId: Long? = null,
        val ttEntityVersion: Int? = null,
        val endedTt: TemporaryTarget? = null,     // fuer Event-/UserEntry-Pfade des Aufrufers
        val insertedTt: TemporaryTarget? = null,
    )

    override fun run(): Result {
        val dao = database.localCommandDao
        // (1) Idempotenz VOR allem anderen (R3-F4): gleiche ID+Hash → Original; anderer Hash → Reuse.
        dao.findOutcome(requestId)?.let { existing ->
            return if (existing.requestHash == requestHash) Result(
                outcome = existing.outcome, errorCode = existing.errorCode, replayed = true,
                appliedAt = existing.appliedAt, ttDbId = existing.ttDbId, ttEntityVersion = existing.ttEntityVersion,
            ) else Result(outcome = "REJECTED", errorCode = "REJECTED_REQUEST_ID_REUSE")
        }
        dao.pruneOutcomes(nowMs)

        // (1b) Fachlicher Policy-Fehler (Matrix/Hash): terminal persistieren.
        if (policyErrorCode != null) return reject(policyErrorCode)

        // (2) Rate-Limit (persistent, Replays zaehlen nicht — R2-B8/R1-F8).
        if (!validateOnly && dao.countAppliedSince(nowMs - 3_600_000L) >= rateCapPerHour)
            return reject("REJECTED_RATE_LIMITED")

        // (3) Aktives TT + Ownership lesen und abgleichen — im selben Commit (TOCTOU-frei).
        val activeTt = database.temporaryTargetDao.getTemporaryTargetActiveAtLegacy(nowMs)
        var ownership = dao.activeOwnership()
        if (ownership != null) {
            val o = ownership
            when {
                activeTt == null || activeTt.id != o.ttDbId -> {
                    // Eigenes TT weg/ersetzt → Ownership terminal; das aktive TT (falls vorhanden) ist fremd.
                    o.terminatedAt = nowMs; o.terminalReason = "TT_GONE"; dao.updateOwnership(o); ownership = null
                }
                !contentMatches(activeTt, o) -> {
                    // INHALTLICHE Fremdaenderung (auch NS): Ownership verfaellt (R2-B2).
                    o.terminatedAt = nowMs; o.terminalReason = "FOREIGN_MODIFIED"; dao.updateOwnership(o); ownership = null
                }
                activeTt.version != o.ttEntityVersion -> {
                    // NS-ID-Echo-Ausnahme (R2-B3): Inhalt identisch, nur Version fortgeschrieben
                    // (z.B. nachgetragene NS-Id) → Fingerprint atomar aktualisieren.
                    o.ttEntityVersion = activeTt.version; dao.updateOwnership(o)
                }
            }
        }

        // (4) CAS-/Ownership-Regeln je Kommando.
        when (cmd) {
            Cmd.SET -> when (expectedState) {
                "NONE" -> {
                    if (ownership != null) return reject("REJECTED_STATE_CONFLICT")     // eigenes TT existiert noch
                    if (activeTt != null) return reject("REJECTED_NOT_OWNED")           // fremdes TT ist unantastbar
                }
                "OWNED" -> {
                    val o = ownership ?: return reject(if (activeTt != null) "REJECTED_NOT_OWNED" else "REJECTED_STATE_CONFLICT")
                    if (o.requestId != expectedOwnerRequestId || o.ttDbId != expectedTtDbId || o.ttEntityVersion != expectedTtEntityVersion)
                        return reject("REJECTED_STATE_CONFLICT")
                }
                else -> return reject("REJECTED_MALFORMED")
            }
            Cmd.CANCEL -> {
                val o = ownership ?: return reject(if (activeTt != null) "REJECTED_NOT_OWNED" else "REJECTED_STATE_CONFLICT")
                if (o.requestId != expectedOwnerRequestId || o.ttDbId != expectedTtDbId || o.ttEntityVersion != expectedTtEntityVersion)
                    return reject("REJECTED_STATE_CONFLICT")
                if (o.ownerPolicyHash != expectedOwnerPolicyHash) return reject("REJECTED_POLICY_VERSION")
            }
        }

        // (5) validateOnly: terminales VALIDATED-Outcome, KEINE Mutation, KEIN Ownership-Datensatz.
        if (validateOnly) {
            persistOutcome("VALIDATED", null, null, null)
            return Result(outcome = "VALIDATED", appliedAt = nowMs)
        }

        // (6) Mutation.
        return when (cmd) {
            Cmd.SET -> {
                var ended: TemporaryTarget? = null
                if (expectedState == "OWNED") {
                    val current = activeTt!!                            // durch (4) garantiert eigenes TT
                    current.end = nowMs
                    database.temporaryTargetDao.updateExistingEntry(current)
                    ended = current
                    ownership!!.let { it.terminatedAt = nowMs; it.terminalReason = "REPLACED"; dao.updateOwnership(it) }
                }
                val newTt = TemporaryTarget(
                    timestamp = nowMs,
                    reason = ttReason!!,
                    lowTarget = targetMgdl!!.toDouble(),
                    highTarget = targetMgdl.toDouble(),
                    duration = durationMin!! * 60_000L,
                )
                val newId = database.temporaryTargetDao.insertNewEntry(newTt)
                dao.insertOwnership(LocalCommandOwnership(
                    requestId = requestId, ttDbId = newId, ttEntityVersion = newTt.version,
                    ttTimestamp = newTt.timestamp, lowTarget = newTt.lowTarget, highTarget = newTt.highTarget,
                    durationMs = newTt.duration, reasonKey = reasonKey!!, ownerPolicyHash = currentPolicyHash!!,
                    createdAt = nowMs,
                ))
                persistOutcome("APPLIED", nowMs, newId, newTt.version)
                Result(outcome = "APPLIED", appliedAt = nowMs, ttDbId = newId, ttEntityVersion = newTt.version, endedTt = ended, insertedTt = newTt)
            }
            Cmd.CANCEL -> {
                val current = activeTt!!                                // durch (4) garantiert eigenes TT
                current.end = nowMs
                database.temporaryTargetDao.updateExistingEntry(current)
                ownership!!.let { it.terminatedAt = nowMs; it.terminalReason = "CANCELLED"; dao.updateOwnership(it) }
                persistOutcome("APPLIED", nowMs, current.id, current.version)
                Result(outcome = "APPLIED", appliedAt = nowMs, ttDbId = current.id, ttEntityVersion = current.version, endedTt = current)
            }
        }
    }

    private fun contentMatches(tt: TemporaryTarget, o: LocalCommandOwnership): Boolean =
        tt.isValid && tt.timestamp == o.ttTimestamp && tt.lowTarget == o.lowTarget &&
            tt.highTarget == o.highTarget && tt.duration == o.durationMs

    private fun reject(code: String): Result {
        // Fachliche Ablehnungen sind TERMINAL fuer diese requestId (R2-B4: kein Fallback,
        // kein spaeteres Umdeuten) → persistieren, damit Retries das Original sehen.
        persistOutcome("REJECTED", null, null, null, code)
        return Result(outcome = "REJECTED", errorCode = code)
    }

    private fun persistOutcome(outcome: String, appliedAt: Long?, ttDbId: Long?, ttVersion: Int?, errorCode: String? = null) {
        database.localCommandDao.insertOutcome(LocalCommandOutcome(
            requestId = requestId, requestHash = requestHash, cmd = cmd.name, outcome = outcome,
            errorCode = errorCode, appliedAt = appliedAt, ttDbId = ttDbId, ttEntityVersion = ttVersion,
            validateOnly = validateOnly, createdAt = nowMs, retainUntil = nowMs + 24 * 3_600_000L,
        ))
    }
}
