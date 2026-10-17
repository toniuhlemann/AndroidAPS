package app.aaps.database.transactions

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.LocalCommandValueLease
import app.aaps.database.entities.TemporaryTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The local command transactions against a REAL database (fork: ValueLeaseRoomIntegrationTest, there
 * an instrumented test on the phone). Here on the desktop target: the same bundled SQLite and the same
 * common code as on the phone, run through AppRepository exactly as the channel service runs them -
 * one IMMEDIATE transaction per command, with Room's real rollback, real unique indexes and the real
 * activeSlot guard triggers.
 *
 * This is also the place that proves the v4 constraint detection: the bundled driver reports a broken
 * constraint as a plain SQLiteException, and [isConstraintViolation] has to recognise its message.
 */
class ValueLeaseRoomIntegrationTest {

    private val directory = createTempDirectory("aaps-lease-test")
    private val repository = JvmAppDatabaseBuilder().provideAppRepository("$directory/aaps.db")
    private val database get() = repository.database

    private val t0 = 1_784_500_000_000L
    private val rid1 = "a".repeat(32)
    private val rid2 = "b".repeat(32)
    private val rid3 = "c".repeat(32)
    private val policy = "80".repeat(32)

    @AfterTest
    fun tearDown() {
        database.close()
        repository.close()
        directory.toFile().deleteRecursively()
    }

    private suspend fun <T : Any> runTx(tx: Transaction<T>): T = repository.runTransactionForResultSuspend(tx)

    private fun set(
        requestId: String, validateOnly: Boolean = false, percent: Int = 80,
        expectedState: String = "NONE", leaseId: String? = null, leaseVersion: Long? = null,
    ) = ExecuteValueLeaseCommandTransaction(
        cmd = ExecuteValueLeaseCommandTransaction.Cmd.SET, capability = "IOBTH",
        requestId = requestId, requestHash = "11".repeat(16), nowMs = t0, validateOnly = validateOnly,
        setPayload = """{"percent":$percent}""", ttlMin = 60, expiresAtWallMs = t0 + 3_600_000,
        basePayload = """{"percent":50}""", baseGeneration = 1, gateGeneration = 0,
        currentPolicyHash = policy, expectedState = expectedState,
        expectedLeaseId = leaseId, expectedLeaseVersion = leaseVersion,
    )

    private suspend fun execSql(sql: String) =
        database.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }

    private suspend fun triggerNames(): List<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private fun lease(version: Long, activeSlot: Int? = 1) = LocalCommandValueLease(
        capability = "IOBTH", leaseId = "x$version", leaseVersion = version, ownerPolicyHash = policy,
        basePayload = """{"percent":50}""", baseGeneration = 1, setPayload = """{"percent":60}""",
        gateGeneration = 0, createdAt = t0, expiresAtWallMs = t0 + 3_600_000, activeSlot = activeSlot,
    )

    @Test
    fun `validate only leaves no lease row`() = runTest {
        val vo = runTx(set(rid3, validateOnly = true))
        assertThat(vo.outcome).isEqualTo("VALIDATED")
        assertThat(database.localCommandDao.activeValueLease("IOBTH")).isNull()
    }

    @Test
    fun `set on none applies and the replay is byte equal`() = runTest {
        val r1 = runTx(set(rid1))
        assertThat(r1.outcome).isEqualTo("APPLIED")
        assertThat(r1.leaseVersion).isEqualTo(1L)
        val replay = runTx(set(rid1))
        assertThat(replay.replayed).isTrue()
        assertThat(replay.resultJson).isEqualTo(r1.resultJson)
    }

    @Test
    fun `owned replace inherits the base and keeps the invariants`() = runTest {
        runTx(set(rid1))
        val r2 = runTx(set(rid2, percent = 90, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        assertThat(r2.outcome).isEqualTo("APPLIED")
        assertThat(r2.leaseVersion).isEqualTo(2L)
        assertThat(r2.basePayloadUsed).isEqualTo("""{"percent":50}""")
        assertThat(database.localCommandDao.activeValueLease("IOBTH")!!.leaseId).isEqualTo(rid2)
    }

    @Test
    fun `terminalize is bound to the identity`() = runTest {
        runTx(set(rid1))
        runTx(set(rid2, percent = 90, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        assertThat(runTx(TerminalizeValueLeaseTransaction("IOBTH", "DISABLED", t0, rid1, 1))).isFalse()
        assertThat(database.localCommandDao.activeValueLease("IOBTH")!!.leaseId).isEqualTo(rid2)
    }

    @Test
    fun `clear needs the creation policy and the versions stay monotone`() = runTest {
        runTx(set(rid1))
        val cl = runTx(
            ExecuteValueLeaseCommandTransaction(
                cmd = ExecuteValueLeaseCommandTransaction.Cmd.CLEAR, capability = "IOBTH",
                requestId = "d".repeat(32), requestHash = "11".repeat(16), nowMs = t0, validateOnly = false,
                expectedLeaseId = rid1, expectedLeaseVersion = 1, expectedOwnerPolicyHash = policy,
            )
        )
        assertThat(cl.outcome).isEqualTo("APPLIED")
        assertThat(database.localCommandDao.activeValueLease("IOBTH")).isNull()
        assertThat(runTx(set("e".repeat(32))).leaseVersion).isEqualTo(2L)
    }

    @Test
    fun `persisting a reject is idempotent`() = runTest {
        runTx(set(rid1))
        assertThat(runTx(PersistValueLeaseRejectTransaction("SET", "IOBTH", rid1, "11".repeat(16), "REJECTED_STATE_CONFLICT", t0))).isFalse()
        assertThat(runTx(PersistValueLeaseRejectTransaction("SET", "IOBTH", "f".repeat(32), "11".repeat(16), "REJECTED_STATE_CONFLICT", t0))).isTrue()
    }

    // R14-F4 (the rarest path, really forced): an insert constraint during SET@OWNED. A test trigger
    // aborts exactly the replace insert. Expected: ValueLeaseConflictException outside (so the bundled
    // driver's error WAS recognised as a constraint), the active lease completely unchanged (rollback =
    // no partial mutation), the separate idempotent reject persist works, and a normal replace still does.
    @Test
    fun `an insert conflict on owned replace changes nothing`() = runTest {
        runTx(set(rid1))
        execSql(
            "CREATE TRIGGER test_force_insert_conflict BEFORE INSERT ON localCommandValueLease " +
                "WHEN NEW.setPayload LIKE '%\"percent\":91%' " +
                "BEGIN SELECT RAISE(ABORT, 'forced test conflict'); END"
        )
        assertFailsWith<ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException> {
            runTx(set(rid2, percent = 91, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        }
        val active = database.localCommandDao.activeValueLease("IOBTH")!!
        assertThat(active.leaseId).isEqualTo(rid1)
        assertThat(active.leaseVersion).isEqualTo(1L)
        assertThat(active.terminalReason).isNull()
        assertThat(database.localCommandDao.findOutcome(rid2)).isNull()
        assertThat(runTx(PersistValueLeaseRejectTransaction("SET", "IOBTH", rid2, "11".repeat(16), "REJECTED_STATE_CONFLICT", t0))).isTrue()
        execSql("DROP TRIGGER test_force_insert_conflict")
        val r = runTx(set(rid3, percent = 92, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        assertThat(r.outcome).isEqualTo("APPLIED")
    }

    @Test
    fun `the activeSlot guards exist and refuse a second kind of active`() = runTest {
        assertThat(triggerNames()).containsAtLeast("lcvl_activeSlot_insert_guard", "lcvl_activeSlot_update_guard")
        val e = assertFailsWith<SQLiteException> { database.localCommandDao.insertValueLease(lease(version = 1, activeSlot = 2)) }
        assertThat(e.isConstraintViolation()).isTrue()
        assertThat(database.localCommandDao.activeValueLease("IOBTH")).isNull()
    }

    @Test
    fun `a second active lease is a recognised constraint`() = runTest {
        database.localCommandDao.insertValueLease(lease(version = 1))
        val e = assertFailsWith<SQLiteException> { database.localCommandDao.insertValueLease(lease(version = 2)) }
        assertThat(e.isConstraintViolation()).isTrue()
    }

    @Test
    fun `an error that is no constraint is not taken for one`() = runTest {
        val e = assertFailsWith<SQLiteException> { execSql("SELECT * FROM tableThatDoesNotExist") }
        assertThat(e.isConstraintViolation()).isFalse()
    }

    // ---- the TT path on the real database ----

    private fun setTt(requestId: String, now: Long, expectedState: String = "NONE", owner: String? = null, ttId: Long? = null, ver: Int? = null) =
        ExecuteLocalTtCommandTransaction(
            cmd = ExecuteLocalTtCommandTransaction.Cmd.SET, requestId = requestId, requestHash = "aa".repeat(32),
            nowMs = now, validateOnly = false, targetMgdl = 90, durationMin = 20, reasonKey = "CORRECTION",
            ttReason = TemporaryTarget.Reason.CORRECTION, currentPolicyHash = policy, expectedState = expectedState,
            expectedOwnerRequestId = owner, expectedTtDbId = ttId, expectedTtEntityVersion = ver, rateCapPerHour = 30,
        )

    @Test
    fun `set then cancel on the real database`() = runTest {
        val set = runTx(setTt(rid1, now = t0))
        assertThat(set.outcome).isEqualTo("APPLIED")
        val active = database.temporaryTargetDao.getTemporaryTargetActiveAtLegacy(t0 + 60_000)!!
        assertThat(active.id).isEqualTo(set.ttDbId)
        assertThat(active.lowTarget).isEqualTo(90.0)

        val cancel = runTx(
            ExecuteLocalTtCommandTransaction(
                cmd = ExecuteLocalTtCommandTransaction.Cmd.CANCEL, requestId = rid2, requestHash = "aa".repeat(32),
                nowMs = t0 + 60_000, validateOnly = false, expectedOwnerRequestId = rid1, expectedTtDbId = set.ttDbId,
                expectedTtEntityVersion = set.ttEntityVersion, expectedOwnerPolicyHash = policy, rateCapPerHour = 30,
            )
        )
        assertThat(cancel.outcome).isEqualTo("APPLIED")
        assertThat(database.temporaryTargetDao.getTemporaryTargetActiveAtLegacy(t0 + 60_000)).isNull()
        assertThat(database.localCommandDao.activeOwnership()).isNull()
    }

    @Test
    fun `a foreign temp target on the real database is never touched`() = runTest {
        val foreign = TemporaryTarget(timestamp = t0 - 60_000, reason = TemporaryTarget.Reason.CUSTOM, lowTarget = 110.0, highTarget = 110.0, duration = 3_600_000)
        val foreignId = database.temporaryTargetDao.insert(foreign)

        val r = runTx(setTt(rid1, now = t0))

        assertThat(r.errorCode).isEqualTo("REJECTED_NOT_OWNED")
        val active = database.temporaryTargetDao.getTemporaryTargetActiveAtLegacy(t0)!!
        assertThat(active.id).isEqualTo(foreignId)
        assertThat(active.duration).isEqualTo(3_600_000)
    }
}
