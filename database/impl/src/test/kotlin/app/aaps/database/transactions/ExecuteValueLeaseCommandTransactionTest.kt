package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.TemporaryTargetDao
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ExecuteValueLeaseCommandTransaction — A1-Zustandsmaschine (Spec v1.1 §3 + R10/R11):
 * VO-Ehrlichkeit (Outcome ja, Lease nie), historisches resultJson (Replay exakt),
 * CAS/Version-Monotonie, CLEAR mit Erstellungs-Policy. Nutzt den stateful FakeLocalDao
 * der TT-Suite (gemeinsames Package).
 */
class ExecuteValueLeaseCommandTransactionTest {

    private val t0 = 1_784_500_000_000L
    private val rid1 = "a".repeat(32)
    private val rid2 = "b".repeat(32)
    private val rid3 = "c".repeat(32)
    private val hash1 = "11".repeat(16)
    private val policy = "80".repeat(32)

    private lateinit var database: DelegatedAppDatabase
    private lateinit var dao: ExecuteLocalTtCommandTransactionTest.FakeLocalDao

    @BeforeEach fun setup() {
        dao = ExecuteLocalTtCommandTransactionTest.FakeLocalDao()
        database = mock()
        val ttDao: TemporaryTargetDao = mock()
        whenever(database.localCommandDao).thenReturn(dao)
        whenever(database.temporaryTargetDao).thenReturn(ttDao)
    }

    private fun set(
        requestId: String = rid1, validateOnly: Boolean = false, percent: Int = 80,
        expectedState: String = "NONE", leaseId: String? = null, leaseVersion: Long? = null,
        policyError: String? = null, requestHash: String = hash1,
        captureBasePercent: Int = 50, captureBaseGen: Long = 1, captureGateGen: Long = 0,
    ) = ExecuteValueLeaseCommandTransaction(
        cmd = ExecuteValueLeaseCommandTransaction.Cmd.SET, capability = "IOBTH",
        requestId = requestId, requestHash = requestHash, nowMs = t0, validateOnly = validateOnly,
        setPayload = """{"percent":$percent}""", ttlMin = 60, expiresAtWallMs = t0 + 3_600_000,
        basePayload = """{"percent":$captureBasePercent}""", baseGeneration = captureBaseGen, gateGeneration = captureGateGen,
        currentPolicyHash = policy, expectedState = expectedState,
        expectedLeaseId = leaseId, expectedLeaseVersion = leaseVersion,
        policyErrorCode = policyError,
    ).also { it.database = database }

    private fun clear(requestId: String, leaseId: String, leaseVersion: Long, ownerHash: String = policy) =
        ExecuteValueLeaseCommandTransaction(
            cmd = ExecuteValueLeaseCommandTransaction.Cmd.CLEAR, capability = "IOBTH",
            requestId = requestId, requestHash = hash1, nowMs = t0, validateOnly = false,
            expectedLeaseId = leaseId, expectedLeaseVersion = leaseVersion, expectedOwnerPolicyHash = ownerHash,
        ).also { it.database = database }

    // (1) R10-F5/R11: VO persistiert Outcome+resultJson, aber NIE eine Lease; Replay exakt gleich.
    @Test fun validateOnlyIsHonest() {
        val r = set(validateOnly = true).run()
        assertThat(r.outcome).isEqualTo("VALIDATED")
        assertThat(r.resultJson).contains("\"leaseCreated\":false")
        assertThat(r.resultJson).contains("\"runtimeOverrideCreated\":false")
        assertThat(dao.valueLeases).isEmpty()
        val replay = set(validateOnly = true).run()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.resultJson).isEqualTo(r.resultJson)
    }

    // (2) SET@NONE APPLIED: Lease v1, historisches resultJson, Replay liefert es exakt.
    @Test fun setNoneAppliesAndReplaysHistorically() {
        val r = set().run()
        assertThat(r.outcome).isEqualTo("APPLIED")
        assertThat(r.leaseVersion).isEqualTo(1L)
        assertThat(dao.activeValueLease("IOBTH")!!.leaseId).isEqualTo(rid1)
        val replay = set().run()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.resultJson).isEqualTo(r.resultJson)
        assertThat(dao.valueLeases).hasSize(1)                       // keine zweite Mutation
        // gleiche ID, anderer Hash → REUSE
        assertThat(set(requestHash = "22".repeat(16)).run().errorCode).isEqualTo("REJECTED_REQUEST_ID_REUSE")
    }

    // (3) SET@NONE waehrend aktiver Lease → STATE_CONFLICT (stale Client).
    @Test fun setNoneConflictsWhenActive() {
        set().run()
        assertThat(set(requestId = rid2).run().errorCode).isEqualTo("REJECTED_STATE_CONFLICT")
    }

    // (4) SET@OWNED Replace: alte Lease REPLACED, Version MONOTON (auch ueber CLEAR hinweg).
    @Test fun ownedReplaceKeepsVersionMonotone() {
        set().run()                                                   // v1
        val r2 = set(requestId = rid2, percent = 90, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1).run()
        assertThat(r2.outcome).isEqualTo("APPLIED")
        assertThat(r2.leaseVersion).isEqualTo(2L)
        assertThat(dao.valueLeases.first { it.leaseVersion == 1L }.terminalReason).isEqualTo("REPLACED")
        clear(rid3, rid2, 2).run()                                    // beendet v2
        val r3 = set(requestId = "d".repeat(32)).run()                // NONE nach CLEAR
        assertThat(r3.leaseVersion).isEqualTo(3L)                     // NIE wiederverwendet (R10-F4)
    }

    // (5) OWNED mit stale Tokens → STATE_CONFLICT, nichts mutiert.
    @Test fun staleOwnedTokensConflict() {
        set().run()
        val r = set(requestId = rid2, expectedState = "OWNED", leaseId = rid1, leaseVersion = 99).run()
        assertThat(r.errorCode).isEqualTo("REJECTED_STATE_CONFLICT")
        assertThat(dao.activeValueLease("IOBTH")!!.leaseVersion).isEqualTo(1L)
    }

    // (6) CLEAR: happy path + falscher Erstellungs-Hash → POLICY_VERSION (R11/F5-Regel:
    //     der Hash der ERSTELLUNG zaehlt — ein Policy-Upgrade aendert ihn nicht nachtraeglich).
    @Test fun clearPathsHonorCreationPolicy() {
        set().run()
        assertThat(clear(rid2, rid1, 1, ownerHash = "ff".repeat(32)).run().errorCode).isEqualTo("REJECTED_POLICY_VERSION")
        val ok = clear(rid3, rid1, 1).run()
        assertThat(ok.outcome).isEqualTo("APPLIED")
        assertThat(dao.activeValueLease("IOBTH")).isNull()
        assertThat(dao.valueLeases.first().terminalReason).isEqualTo("CLEARED")
        assertThat(ok.resultJson).contains("\"cleared\":true")
    }

    // (7) Vorentschiedener Policy-Fehler: terminal persistiert (Retry sieht das Original).
    @Test fun policyErrorIsTerminal() {
        val r = set(policyError = "REJECTED_POLICY").run()
        assertThat(r.errorCode).isEqualTo("REJECTED_POLICY")
        assertThat(dao.valueLeases).isEmpty()
        val replay = set(policyError = "REJECTED_POLICY").run()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.errorCode).isEqualTo("REJECTED_POLICY")
    }

    // (8) R12-F5: Replacement ERBT die Erstbasis/-generationen — ein absichtlich
    //     abweichender zweiter Capture darf NIE eine neue Kohorte beginnen.
    @Test fun ownedReplaceInheritsFirstBase() {
        set(captureBasePercent = 50, captureBaseGen = 1, captureGateGen = 2).run()
        val r2 = set(
            requestId = rid2, percent = 90, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1,
            captureBasePercent = 47, captureBaseGen = 9, captureGateGen = 7,   // absichtlich anders
        ).run()
        assertThat(r2.outcome).isEqualTo("APPLIED")
        val lease = dao.activeValueLease("IOBTH")!!
        assertThat(lease.basePayload).isEqualTo("""{"percent":50}""")   // v1-Basis geerbt
        assertThat(lease.baseGeneration).isEqualTo(1L)
        assertThat(lease.gateGeneration).isEqualTo(2L)
        // ... und exakt diese Werte gehen an den Publish zurueck:
        assertThat(r2.basePayloadUsed).isEqualTo("""{"percent":50}""")
        assertThat(r2.baseGenerationUsed).isEqualTo(1L)
        assertThat(r2.gateGenerationUsed).isEqualTo(2L)
    }

    // (9) R12-F6: Insert-Constraint beim Replacement bleibt MUTATIONSNEUTRAL — die alte
    //     Lease wird restauriert, der Marker fliegt, kein REJECTED-Outcome in DIESER Txn.
    @Test fun insertConflictOnReplaceIsMutationNeutral() {
        set().run()
        dao.throwOnInsertLease = true
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException::class.java
            ) {
                set(requestId = rid2, expectedState = "OWNED", leaseId = rid1, leaseVersion = 1).run()
            }
        } finally { dao.throwOnInsertLease = false }
        val active = dao.activeValueLease("IOBTH")!!
        assertThat(active.leaseId).isEqualTo(rid1)                     // alte Lease UNANGETASTET aktiv
        assertThat(active.terminalReason).isNull()
        assertThat(dao.findOutcome(rid2)).isNull()                     // Reject kommt separat (Service)
    }

    // (10) R12-F1: identitaetsgebundene Terminalisierung trifft NIE einen Nachfolger.
    @Test fun terminalizeIsIdentityBound() {
        set().run()                                                    // Lease A (rid1, v1)
        clear(rid2, rid1, 1).run()                                     // A beendet
        set(requestId = rid3).run()                                    // Lease B (rid3, v2) — Nachfolger
        // Auftrag fuer A (stale) → no-op, B bleibt aktiv:
        val staleHit = TerminalizeValueLeaseTransaction("IOBTH", "DISABLED", t0, rid1, 1)
            .also { it.database = database }.run()
        assertThat(staleHit).isFalse()
        assertThat(dao.activeValueLease("IOBTH")!!.leaseId).isEqualTo(rid3)
        // Auftrag fuer B (korrekt) → trifft:
        val hit = TerminalizeValueLeaseTransaction("IOBTH", "DISABLED", t0, rid3, 2)
            .also { it.database = database }.run()
        assertThat(hit).isTrue()
        assertThat(dao.activeValueLease("IOBTH")).isNull()
    }
}
