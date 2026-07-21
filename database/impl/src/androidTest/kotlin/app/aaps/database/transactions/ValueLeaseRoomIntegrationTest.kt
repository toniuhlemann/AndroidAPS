package app.aaps.database.transactions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.database.AppDatabase
import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.di.DatabaseModule
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capability-Matrix A1 (R13-F5, Room-Haelfte): die Value-Lease-Transaktionen laufen hier
 * gegen ECHTES Room (in-memory AppDatabase inkl. Unique-Indizes + activeSlot-Guards) statt
 * gegen den Fake-DAO — inklusive echter Transaktions-Rollback-Semantik. Laeuft als
 * Library-Test-APK; die installierte AAPS-App wird nicht angefasst.
 */
@RunWith(AndroidJUnit4::class)
class ValueLeaseRoomIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var delegated: DelegatedAppDatabase
    private val t0 = 1_784_500_000_000L
    private val rid1 = "a".repeat(32)
    private val rid2 = "b".repeat(32)
    private val rid3 = "c".repeat(32)
    private val policy = "80".repeat(32)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        DatabaseModule().createValueLeaseGuards(db.openHelper.writableDatabase)
        delegated = DelegatedAppDatabase(mutableListOf(), db)
    }

    @After fun teardown() { db.close() }

    private fun <T> runTx(tx: Transaction<T>): T {
        tx.database = delegated
        var out: T? = null
        db.runInTransaction { out = tx.run() }
        return out!!
    }

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

    // R14-F4: klar benannte Einzeltests statt einer Sammel-Methode — die Protokoll-Zaehlung
    // entspricht damit echten @Test-Faellen. Jeder Test startet mit frischer In-Memory-DB.

    @Test fun voLeavesNoLeaseRowOnRealRoom() {
        val vo = runTx(set(rid3, validateOnly = true))
        Assert.assertEquals("VALIDATED", vo.outcome)
        Assert.assertNull(delegated.localCommandDao.activeValueLease("IOBTH"))
    }

    @Test fun setNoneAppliesAndReplaysByteEqual() {
        val r1 = runTx(set(rid1))
        Assert.assertEquals("APPLIED", r1.outcome)
        Assert.assertEquals(1L, r1.leaseVersion)
        val replay = runTx(set(rid1))
        Assert.assertTrue(replay.replayed)
        Assert.assertEquals(r1.resultJson, replay.resultJson)
    }

    @Test fun ownedReplaceInheritsBaseAndKeepsInvariants() {
        runTx(set(rid1))
        val r2 = runTx(set(rid2, percent = 90, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        Assert.assertEquals("APPLIED", r2.outcome)
        Assert.assertEquals(2L, r2.leaseVersion)
        Assert.assertEquals("""{"percent":50}""", r2.basePayloadUsed)
        Assert.assertEquals(rid2, delegated.localCommandDao.activeValueLease("IOBTH")!!.leaseId)
    }

    @Test fun terminalizeIsIdentityBoundOnRealRoom() {
        runTx(set(rid1))
        runTx(set(rid2, percent = 90, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        Assert.assertFalse(runTx(TerminalizeValueLeaseTransaction("IOBTH", "DISABLED", t0, rid1, 1)))
        Assert.assertEquals(rid2, delegated.localCommandDao.activeValueLease("IOBTH")!!.leaseId)
    }

    @Test fun clearWithCreationPolicyThenVersionsStayMonotone() {
        runTx(set(rid1))
        val cl = runTx(ExecuteValueLeaseCommandTransaction(
            cmd = ExecuteValueLeaseCommandTransaction.Cmd.CLEAR, capability = "IOBTH",
            requestId = "d".repeat(32), requestHash = "11".repeat(16), nowMs = t0, validateOnly = false,
            expectedLeaseId = rid1, expectedLeaseVersion = 1, expectedOwnerPolicyHash = policy,
        ))
        Assert.assertEquals("APPLIED", cl.outcome)
        Assert.assertNull(delegated.localCommandDao.activeValueLease("IOBTH"))
        Assert.assertEquals(2L, runTx(set("e".repeat(32))).leaseVersion)
    }

    @Test fun rejectPersistIsIdempotent() {
        runTx(set(rid1))
        Assert.assertFalse(runTx(PersistValueLeaseRejectTransaction("SET", "IOBTH", rid1, "11".repeat(16), "REJECTED_STATE_CONFLICT", t0)))
        Assert.assertTrue(runTx(PersistValueLeaseRejectTransaction("SET", "IOBTH", "f".repeat(32), "11".repeat(16), "REJECTED_STATE_CONFLICT", t0)))
    }

    // R14-F4 (seltenster Pfad, ECHT erzwungen): ein Insert-Constraint waehrend SET@OWNED auf
    // realem Room. Ein Test-Trigger wirft RAISE(ABORT) (= SQLiteConstraintException) exakt beim
    // Replace-Insert; erwartet: ValueLeaseConflictException nach aussen, die aktive Lease bleibt
    // VOLLSTAENDIG unveraendert (Room-Rollback = Mutations-Neutralitaet), das separate
    // idempotente Reject-Persist laeuft danach, und ein normaler Replace funktioniert weiter.
    @Test fun ownedReplaceInsertConflictIsMutationNeutralOnRealRoom() {
        runTx(set(rid1))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_force_insert_conflict BEFORE INSERT ON localCommandValueLease " +
                "WHEN NEW.setPayload LIKE '%\"percent\":91%' " +
                "BEGIN SELECT RAISE(ABORT, 'forced test conflict'); END",
        )
        var conflict = false
        try {
            runTx(set(rid2, percent = 91, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        } catch (_: ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException) {
            conflict = true
        }
        Assert.assertTrue(conflict)
        val active = delegated.localCommandDao.activeValueLease("IOBTH")!!
        Assert.assertEquals(rid1, active.leaseId)
        Assert.assertEquals(1L, active.leaseVersion)
        Assert.assertTrue(runTx(PersistValueLeaseRejectTransaction("SET", "IOBTH", rid2, "11".repeat(16), "REJECTED_STATE_CONFLICT", t0)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER test_force_insert_conflict")
        val r = runTx(set(rid3, percent = 92, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1))
        Assert.assertEquals("APPLIED", r.outcome)
    }
}
