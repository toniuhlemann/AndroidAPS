package app.aaps.database.transactions

import androidx.sqlite.SQLiteException
import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.LocalCommandDao
import app.aaps.database.daos.TemporaryTargetDao
import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandOwnership
import app.aaps.database.entities.LocalCommandValueLease
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.embedments.InterfaceIDs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ExecuteLocalTtCommandTransaction — Zustandsmaschinen-Suite (R2-B2/B3, R3-F3, R4-Matrix,
 * R5-Pilot-Bau). Stateful In-Memory-Fakes fuer beide DAOs: die Sequenzen (Replay nach
 * Mutation, Echo-Fortschreibung, Latch-Ketten) brauchen echten Zustand, keine Stub-Werte.
 * Die ATOMIZITAET selbst ist Rooms Job und wird in ValueLeaseRoomIntegrationTest (jvmTest,
 * echte Datenbank) geprueft; HIER wird die komplette Entscheidungslogik bewiesen.
 */
class ExecuteLocalTtCommandTransactionTest {

    private val t0 = 1_784_500_000_000L
    private val policyHash = "ab".repeat(32)
    private val rid1 = "1".repeat(32)
    private val rid2 = "2".repeat(32)
    private val hash1 = "aa".repeat(32)

    /**
     * What the bundled driver throws for a broken unique index. The message is overridden because in
     * unit tests the exception is the android.jar stub, whose constructor drops the message.
     */
    class ConstraintFailure : SQLiteException("constraint") {

        override val message: String get() = "Error code: 2067, message: UNIQUE constraint failed: localCommandValueLease.capability"
    }

    // ---- Stateful Fakes (auch von ExecuteValueLeaseCommandTransactionTest genutzt) ----
    class FakeLocalDao : LocalCommandDao {

        val outcomes = mutableListOf<LocalCommandOutcome>()
        val ownerships = mutableListOf<LocalCommandOwnership>()
        private var nextId = 1L
        override suspend fun insertOutcome(outcome: LocalCommandOutcome): Long {
            outcome.id = nextId++; outcomes += outcome; return outcome.id
        }

        override suspend fun findOutcome(requestId: String) = outcomes.firstOrNull { it.requestId == requestId }
        override suspend fun countAppliedSince(sinceMs: Long) =
            outcomes.count { it.createdAt >= sinceMs && it.outcome == "APPLIED" && !it.validateOnly }

        override suspend fun pruneOutcomes(nowMs: Long) {
            outcomes.removeAll { it.retainUntil < nowMs }
        }

        override suspend fun insertOwnership(ownership: LocalCommandOwnership): Long {
            ownership.id = nextId++; ownerships += ownership; return ownership.id
        }

        override suspend fun activeOwnership() = ownerships.filter { it.terminatedAt == null }.maxByOrNull { it.id }
        override suspend fun updateOwnership(ownership: LocalCommandOwnership) {
            ownerships.replaceAll { if (it.id == ownership.id) ownership else it }
        }

        // Capability-Wert-Leases (A1) — stateful analog zu den Ownership-Fakes.
        val valueLeases = mutableListOf<LocalCommandValueLease>()
        var throwOnInsertLease = false          // R12-F6: simulierter Unique-Constraint
        override suspend fun insertValueLease(lease: LocalCommandValueLease): Long {
            if (throwOnInsertLease) throw ConstraintFailure()
            lease.id = nextId++; valueLeases += lease; return lease.id
        }

        override suspend fun activeValueLease(capability: String) =
            valueLeases.firstOrNull { it.capability == capability && it.activeSlot != null }

        override suspend fun maxLeaseVersion(capability: String) =
            valueLeases.filter { it.capability == capability }.maxOfOrNull { it.leaseVersion } ?: 0L

        override suspend fun updateValueLease(lease: LocalCommandValueLease) {
            valueLeases.replaceAll { if (it.id == lease.id) lease else it }
        }
    }

    private lateinit var database: DelegatedAppDatabase
    private lateinit var localDao: FakeLocalDao
    private lateinit var ttDao: TemporaryTargetDao
    private var activeTt: TemporaryTarget? = null
    private val insertedTts = mutableListOf<TemporaryTarget>()
    private val updatedTts = mutableListOf<TemporaryTarget>()
    private var nextTtId = 100L

    @BeforeEach
    fun setup() {
        localDao = FakeLocalDao()
        ttDao = mock()
        database = mock()
        whenever(database.localCommandDao).thenReturn(localDao)
        whenever(database.temporaryTargetDao).thenReturn(ttDao)
        runBlocking {
            whenever(ttDao.getTemporaryTargetActiveAtLegacy(any())).doAnswer { activeTt }
            whenever(ttDao.insertNewEntry(any())).doAnswer { inv ->
                val tt = inv.arguments[0] as TemporaryTarget
                insertedTts += tt; nextTtId++
            }
            whenever(ttDao.updateExistingEntry(any())).doAnswer { inv ->
                updatedTts += inv.arguments[0] as TemporaryTarget; 1L
            }
        }
        activeTt = null; insertedTts.clear(); updatedTts.clear()
    }

    private fun tt(id: Long, ts: Long = t0 - 600_000, target: Double = 90.0, durationMs: Long = 1_800_000, version: Int = 0) =
        TemporaryTarget(
            id = id, version = version, timestamp = ts, reason = TemporaryTarget.Reason.CORRECTION,
            lowTarget = target, highTarget = target, duration = durationMs, interfaceIDs_backing = InterfaceIDs(),
        )

    private fun ownFor(tt: TemporaryTarget, requestId: String = rid1) = LocalCommandOwnership(
        requestId = requestId, ttDbId = tt.id, ttEntityVersion = tt.version, ttTimestamp = tt.timestamp,
        lowTarget = tt.lowTarget, highTarget = tt.highTarget, durationMs = tt.duration,
        reasonKey = "CORRECTION", ownerPolicyHash = policyHash, createdAt = tt.timestamp,
    )

    private fun setNone(requestId: String = rid1, hash: String = hash1, validateOnly: Boolean = false, now: Long = t0) =
        ExecuteLocalTtCommandTransaction(
            cmd = ExecuteLocalTtCommandTransaction.Cmd.SET, requestId = requestId, requestHash = hash,
            nowMs = now, validateOnly = validateOnly, targetMgdl = 90, durationMin = 20, reasonKey = "CORRECTION",
            ttReason = TemporaryTarget.Reason.CORRECTION, currentPolicyHash = policyHash, expectedState = "NONE",
            rateCapPerHour = 30,
        ).also { it.database = database }

    private fun setOwned(owner: String, ttId: Long, ver: Int, requestId: String = rid2, now: Long = t0) =
        ExecuteLocalTtCommandTransaction(
            cmd = ExecuteLocalTtCommandTransaction.Cmd.SET, requestId = requestId, requestHash = hash1,
            nowMs = now, validateOnly = false, targetMgdl = 76, durationMin = 15, reasonKey = "MEAL",
            ttReason = TemporaryTarget.Reason.MEAL, currentPolicyHash = policyHash, expectedState = "OWNED",
            expectedOwnerRequestId = owner, expectedTtDbId = ttId, expectedTtEntityVersion = ver,
            rateCapPerHour = 30,
        ).also { it.database = database }

    private fun cancel(owner: String, ttId: Long, ver: Int, ownerHash: String = policyHash, requestId: String = rid2, now: Long = t0) =
        ExecuteLocalTtCommandTransaction(
            cmd = ExecuteLocalTtCommandTransaction.Cmd.CANCEL, requestId = requestId, requestHash = hash1,
            nowMs = now, validateOnly = false,
            expectedOwnerRequestId = owner, expectedTtDbId = ttId, expectedTtEntityVersion = ver,
            expectedOwnerPolicyHash = ownerHash, rateCapPerHour = 30,
        ).also { it.database = database }

    // (1) SET-NONE auf leerem Zustand: APPLIED + Ownership + Outcome
    @Test fun setNoneOnEmptyApplies() = runTest {
        val r = setNone().run()
        assertThat(r.outcome).isEqualTo("APPLIED")
        assertThat(insertedTts).hasSize(1)
        assertThat(insertedTts[0].lowTarget).isEqualTo(90.0)
        assertThat(localDao.activeOwnership()!!.requestId).isEqualTo(rid1)
        assertThat(localDao.findOutcome(rid1)!!.outcome).isEqualTo("APPLIED")
    }

    // (2)+(3) Idempotenz: Replay liefert Original ohne 2. Mutation; anderer Hash → REUSE
    @Test fun replayAndReuse() = runTest {
        setNone().run()
        val replay = setNone().run()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.outcome).isEqualTo("APPLIED")
        assertThat(insertedTts).hasSize(1)                       // KEINE zweite Mutation
        val reuse = setNone(hash = "bb".repeat(32)).run()
        assertThat(reuse.errorCode).isEqualTo("REJECTED_REQUEST_ID_REUSE")
        assertThat(localDao.findOutcome(rid1)!!.requestHash).isEqualTo(hash1)   // Original unangetastet
    }

    // (4) Fremdes aktives TT: SET-NONE fasst es NIE an
    @Test fun foreignTtIsUntouchable() = runTest {
        activeTt = tt(id = 500)                                  // fremd: keine Ownership dazu
        val r = setNone().run()
        assertThat(r.errorCode).isEqualTo("REJECTED_NOT_OWNED")
        assertThat(insertedTts).isEmpty(); assertThat(updatedTts).isEmpty()
    }

    // (5) SET-NONE waehrend eigenes TT aktiv → STATE_CONFLICT
    @Test fun setNoneWhileOwnedConflicts() = runTest {
        val own = tt(id = 100); activeTt = own; localDao.insertOwnership(ownFor(own))
        val r = setNone(requestId = rid2).run()
        assertThat(r.errorCode).isEqualTo("REJECTED_STATE_CONFLICT")
    }

    // (6) SET-OWNED Replace happy path: alt beendet + REPLACED, neu + neue Ownership
    @Test fun setOwnedReplaceHappyPath() = runTest {
        val own = tt(id = 100); activeTt = own; localDao.insertOwnership(ownFor(own))
        val r = setOwned(owner = rid1, ttId = 100, ver = 0).run()
        assertThat(r.outcome).isEqualTo("APPLIED")
        assertThat(updatedTts).hasSize(1)                        // altes TT beendet
        assertThat(insertedTts).hasSize(1)
        assertThat(localDao.ownerships.first { it.requestId == rid1 }.terminalReason).isEqualTo("REPLACED")
        assertThat(localDao.activeOwnership()!!.requestId).isEqualTo(rid2)
    }

    // (7) Verspaeteter Request mit stale Tokens → STATE_CONFLICT (R3-F3-Ueberholschutz)
    @Test fun staleTokensConflict() = runTest {
        val own = tt(id = 100, version = 2); activeTt = own
        localDao.insertOwnership(ownFor(own))
        val r = setOwned(owner = rid1, ttId = 100, ver = 0).run()   // Token aus alter Zeit
        assertThat(r.errorCode).isEqualTo("REJECTED_STATE_CONFLICT")
        assertThat(updatedTts).isEmpty()
    }

    // (8) Inhaltliche Fremdaenderung → Ownership FOREIGN_MODIFIED, TT gilt als fremd
    @Test fun foreignContentModificationInvalidatesOwnership() = runTest {
        val own = tt(id = 100); activeTt = own
        localDao.insertOwnership(ownFor(own))
        activeTt = tt(id = 100, target = 120.0, version = 1)     // jemand hat das Ziel geaendert
        val r = setOwned(owner = rid1, ttId = 100, ver = 0).run()
        assertThat(r.errorCode).isEqualTo("REJECTED_NOT_OWNED")
        assertThat(localDao.ownerships.first().terminalReason).isEqualTo("FOREIGN_MODIFIED")
    }

    // (9) NS-ID-Echo: Inhalt identisch, Version +1 → Fingerprint fortgeschrieben; alte Tokens
    //     ehrlich STATE_CONFLICT (Viewer holt frische Tokens via Status)
    @Test fun nsEchoCarriesVersionForward() = runTest {
        val own = tt(id = 100); activeTt = own
        localDao.insertOwnership(ownFor(own))
        activeTt = tt(id = 100, version = 1)                     // nur Version bumpt (NS-Id-Echo)
        val r = setOwned(owner = rid1, ttId = 100, ver = 0).run()
        assertThat(r.errorCode).isEqualTo("REJECTED_STATE_CONFLICT")   // alter Token
        val o = localDao.activeOwnership()!!
        assertThat(o.terminatedAt).isNull()                      // Ownership LEBT
        assertThat(o.ttEntityVersion).isEqualTo(1)               // Version fortgeschrieben
        // mit frischem Token klappt das Replace:
        val r2 = setOwned(owner = rid1, ttId = 100, ver = 1, requestId = "3".repeat(32)).run()
        assertThat(r2.outcome).isEqualTo("APPLIED")
    }

    // (10)-(12) CANCEL: happy / falscher Owner-Policy-Hash / fremdes TT
    @Test fun cancelPaths() = runTest {
        val own = tt(id = 100); activeTt = own; localDao.insertOwnership(ownFor(own))
        assertThat(cancel(rid1, 100, 0, ownerHash = "ff".repeat(32)).run().errorCode).isEqualTo("REJECTED_POLICY_VERSION")
        val ok = cancel(rid1, 100, 0, requestId = "4".repeat(32)).run()
        assertThat(ok.outcome).isEqualTo("APPLIED")
        assertThat(updatedTts).hasSize(1)
        assertThat(localDao.ownerships.first().terminalReason).isEqualTo("CANCELLED")
        // fremdes TT: cancel prallt ab
        activeTt = tt(id = 999)
        assertThat(cancel(rid1, 999, 0, requestId = "5".repeat(32)).run().errorCode).isEqualTo("REJECTED_NOT_OWNED")
    }

    // (13) validateOnly: terminales VALIDATED ohne jede Mutation; Replay liefert VALIDATED
    @Test fun validateOnlyIsTerminalWithoutMutation() = runTest {
        val r = setNone(validateOnly = true).run()
        assertThat(r.outcome).isEqualTo("VALIDATED")
        assertThat(insertedTts).isEmpty()
        assertThat(localDao.activeOwnership()).isNull()          // KEIN Ownership-Datensatz (R4 §C3)
        val replay = setNone(validateOnly = true).run()
        assertThat(replay.replayed).isTrue(); assertThat(replay.outcome).isEqualTo("VALIDATED")
    }

    // (14) Rate-Limit: persistente APPLIED-Basis; Replays zaehlen nicht
    @Test fun rateLimitFromPersistentAppliedCount() = runTest {
        repeat(30) { i ->
            localDao.insertOutcome(
                LocalCommandOutcome(
                    requestId = "%032x".format(i + 100), requestHash = hash1, cmd = "SET", outcome = "APPLIED",
                    validateOnly = false, createdAt = t0 - 60_000, retainUntil = t0 + 86_400_000, appliedAt = t0 - 60_000,
                )
            )
        }
        assertThat(setNone().run().errorCode).isEqualTo("REJECTED_RATE_LIMITED")
        assertThat(insertedTts).isEmpty()
    }

    // (15) TT_GONE: Ownership ohne aktives TT wird atomar terminalisiert, frischer NONE-SET gilt
    @Test fun ownershipWithoutTtIsTerminalizedAndNoneSucceeds() = runTest {
        localDao.insertOwnership(ownFor(tt(id = 100)))           // Ownership, aber kein aktives TT
        val r = setNone().run()
        assertThat(r.outcome).isEqualTo("APPLIED")
        assertThat(localDao.ownerships.first().terminalReason).isEqualTo("TT_GONE")
        assertThat(localDao.activeOwnership()!!.requestId).isEqualTo(rid1)
    }

    // (16) R6-F5: Reason-only-Fremdaenderung (gleiche ID/Zeiten/Ziele) ist eine INHALTLICHE
    //      Aenderung — Ownership verfaellt, TT gilt als fremd
    @Test fun reasonOnlyForeignChangeInvalidatesOwnership() = runTest {
        val own = tt(id = 100); activeTt = own
        localDao.insertOwnership(ownFor(own))
        activeTt = tt(id = 100, version = 1).also { it.reason = TemporaryTarget.Reason.MEAL }   // nur Reason geaendert
        val r = setOwned(owner = rid1, ttId = 100, ver = 0).run()
        assertThat(r.errorCode).isEqualTo("REJECTED_NOT_OWNED")
        assertThat(localDao.ownerships.first().terminalReason).isEqualTo("FOREIGN_MODIFIED")
        assertThat(updatedTts).isEmpty()
    }

    // ---- R6-F4: Status-Transaktion nutzt DIESELBE Reconciliation wie die Mutation ----

    private fun readState(now: Long = t0) =
        ReadLocalCommandStateTransaction(nowMs = now).also { it.database = database }

    // (17) Abgelaufenes/verschwundenes eigenes TT wird NIE als OWNED exportiert
    @Test fun statusNeverReportsExpiredOwnership() = runTest {
        localDao.insertOwnership(ownFor(tt(id = 100)))           // Ownership, aber kein aktives TT (abgelaufen)
        val r = readState().run()
        assertThat(r.ownership).isNull()
        assertThat(localDao.ownerships.first().terminalReason).isEqualTo("TT_GONE")
    }

    // (18) NS-ID-Echo im Status: Preflight bekommt FRISCHE Tokens (kein vermeidbarer
    //      STATE_CONFLICT an der TT-Grenze mehr)
    @Test fun statusCarriesEchoVersionForward() = runTest {
        val own = tt(id = 100); activeTt = own
        localDao.insertOwnership(ownFor(own))
        activeTt = tt(id = 100, version = 3)                     // Inhalt identisch, NS-Echo bumpt Version
        val r = readState().run()
        assertThat(r.ownership).isNotNull()
        assertThat(r.ownership!!.ttEntityVersion).isEqualTo(3)   // fortgeschrieben, nicht stale
        assertThat(r.ownership!!.terminatedAt).isNull()
    }

    // (19) Fremd ersetztes TT (auch bei GLEICHEM Ziel): Status meldet NONE, nie OWNED
    @Test fun statusNeverReportsForeignReplacedTt() = runTest {
        val own = tt(id = 100); activeTt = own
        localDao.insertOwnership(ownFor(own))
        activeTt = tt(id = 999)                                  // manuelles TT, gleicher Inhalt, andere ID
        val r = readState().run()
        assertThat(r.ownership).isNull()
        assertThat(localDao.ownerships.first().terminalReason).isEqualTo("TT_GONE")
    }
}
