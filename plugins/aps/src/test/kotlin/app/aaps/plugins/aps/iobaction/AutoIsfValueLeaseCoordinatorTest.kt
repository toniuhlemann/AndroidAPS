package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * ValueLeaseCoordinator — R9/R10/R11-Pflichtfaelle: Gate-Latch (Re-enable belebt nie),
 * doppelte TTL (Uhr-Rollback verlaengert nie), Generation-Erkennung (auch wertgleich),
 * Post-Commit-Fremdaenderung (historisch APPLIED + revoked Publish), Writer-Invalidator,
 * eine Snapshot-Quelle, Overflow-Sicherheit. Alle Umwelt-Reader injiziert — kein Android.
 */
class AutoIsfValueLeaseCoordinatorTest {

    private lateinit var c: AutoIsfValueLeaseCoordinator
    private var base = 50
    private var gates = AutoIsfValueLeaseCoordinator.Gates(channelEnabled = true, iobthCapabilityEnabled = true, forcedValidateOnly = false)
    private var wall = 1_784_500_000_000L
    private var elapsed = 1_000_000L

    @BeforeEach fun setup() {
        c = AutoIsfValueLeaseCoordinator(mock(), mock())
        c.basePercentReader = { base }
        c.gatesReader = { gates }
        c.wallClock = { wall }
        c.elapsedClock = { elapsed }
    }

    private fun roomApplied(id: String = "lease-1", version: Long = 1) =
        AutoIsfValueLeaseCoordinator.RoomSetResult("APPLIED", null, false, """{"leaseCreated":true}""", id, version)

    private fun armSet(percent: Int = 80, ttlMin: Int = 60, room: AutoIsfValueLeaseCoordinator.RoomSetResult = roomApplied()) =
        c.executeArmedSet(percent, ttlMin) { room }

    // (1) OFF-Diff-Garantie: ohne Lease ist effective IMMER base, Zustand NONE.
    @Test fun offDiffNoLeaseMeansBase() {
        val s = c.snapshot()
        assertThat(s.iobThPercentEffective).isEqualTo(50)
        assertThat(s.overrideState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(c.isIobThLeaseActive()).isFalse()
    }

    // (2) ARMED-Happy-Path: Publish nach Commit, effective = Set-Wert, gleiche Quelle fuer Trigger.
    @Test fun armedSetPublishesActive() {
        val r = armSet(percent = 80)
        assertThat(r.currentLeaseState).isEqualTo(AutoIsfOverrideState.ACTIVE)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(80)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.ACTIVE)
        assertThat(c.isIobThLeaseActive()).isTrue()
    }

    // (3) R10-Test 1: Gate AUS latcht irreversibel — schnelles Wieder-AN belebt NIE.
    @Test fun gateOffLatchesForever() {
        armSet()
        gates = gates.copy(iobthCapabilityEnabled = false)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(50)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.DISABLED)
        gates = gates.copy(iobthCapabilityEnabled = true)          // Re-enable VOR jedem Cleanup
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.DISABLED)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(50)
        assertThat(c.consumePendingRoomTerminal()).isEqualTo("DISABLED")
    }

    // (4) R9: ForcedVO AN wirkt als Kill-Switch mit eigenem Terminalgrund und latcht ebenso.
    @Test fun forcedVoLatchesWithOwnReason() {
        armSet()
        gates = gates.copy(forcedValidateOnly = true)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.VO_FORCED)
        gates = gates.copy(forcedValidateOnly = false)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.VO_FORCED)
    }

    // (5) TTL: now == deadline ist abgelaufen; Wall-Rollback verlaengert nie (elapsed monoton).
    @Test fun ttlExpiryIsMonotone() {
        armSet(ttlMin = 60)
        elapsed += 60L * 60_000                                     // exakt die Frist (now == deadline)
        wall -= 3L * 60_000                                         // leichter Rueckstell-Drift (unter der
                                                                    // Anomalie-Toleranz) — verlaengert NIE
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.EXPIRED)
        elapsed -= 60L * 60_000                                     // haette es je gegeben: Latch bleibt
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.EXPIRED)
    }

    // (6) Deutlicher Wall-Ruecksprung vor Erstellzeit → CLOCK_ANOMALY, dauerhaft.
    @Test fun clockAnomalyLatches() {
        armSet()
        wall -= 10L * 60_000
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.CLOCK_ANOMALY)
        wall += 20L * 60_000
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.CLOCK_ANOMALY)
    }

    // (7) R9-F5: WERTGLEICHER Fremd-Write wird ueber die Generation erkannt.
    @Test fun sameValueForeignWriteDetectedViaGeneration() {
        armSet()
        c.onExternalBaseWrite()                                     // SP-Listener-Fangnetz, Wert unveraendert
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.FOREIGN_MODIFIED)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(50)
    }

    // (8) Basis-WERT-Aenderung ohne Listener (Fallback-Vergleich) → FOREIGN_MODIFIED.
    @Test fun baseValueChangeDetectedWithoutListener() {
        armSet()
        base = 40
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.FOREIGN_MODIFIED)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(40)
    }

    // (9) R10-Test 2: Fremd-Write ZWISCHEN Commit und Publish → historisch APPLIED,
    //     aber revoked publiziert; naechster Snapshot = Basis; Room-Terminal vorgemerkt.
    @Test fun postCommitForeignPublishesRevoked() {
        val r = c.executeArmedSet(80, 60) { base = 40; roomApplied() }   // txn simuliert Fremd-Write
        assertThat(r.room.outcome).isEqualTo("APPLIED")                  // Historie unveraendert
        assertThat(r.currentLeaseState).isEqualTo(AutoIsfOverrideState.FOREIGN_MODIFIED)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(40)
        assertThat(c.consumePendingRoomTerminal()).isEqualTo("FOREIGN_MODIFIED")
    }

    // (10) Writer-Invalidator: aktive Lease wird VOR dem naechsten Snapshot widerrufen; true.
    @Test fun invalidatorRevokesBeforeNextSnapshot() {
        armSet()
        assertThat(c.invalidateBeforeExternalWrite(AutoIsfCapability.IOBTH, "ActionSetIobTH")).isTrue()
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.FOREIGN_MODIFIED)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(50)
        // Ohne Lease: sicherer No-op, weiterhin true.
        assertThat(c.invalidateBeforeExternalWrite(AutoIsfCapability.IOBTH, "again")).isTrue()
    }

    // (11) Replay publiziert NIE (historisches Resultat, Gegenwart aus dem Snapshot).
    @Test fun replayDoesNotPublish() {
        val replay = roomApplied().copy(replayed = true)
        val r = armSet(room = replay)
        assertThat(r.currentLeaseState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.NONE)
    }

    // (12) CLEAR: erfolgreiches APPLIED publiziert NONE.
    @Test fun clearPublishesNone() {
        armSet()
        val r = c.executeArmedClear { roomApplied() }
        assertThat(r.currentLeaseState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(c.snapshot().iobThPercentEffective).isEqualTo(50)
    }

    // (13) Reject in der txn publiziert nichts.
    @Test fun rejectedSetDoesNotPublish() {
        val r = armSet(room = AutoIsfValueLeaseCoordinator.RoomSetResult("REJECTED", "REJECTED_POLICY", false, null, null, null))
        assertThat(r.currentLeaseState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(c.snapshot().overrideState).isEqualTo(AutoIsfOverrideState.NONE)
    }

    // (14) R10-F3: Overflow-sichere TTL-Arithmetik — addExact an der Deadline-Grenze
    //     wirft statt zu wrappen (Int-ttl*60000 selbst kann Long nie ueberlaufen).
    @Test fun ttlArithmeticIsOverflowSafe() {
        wall = Long.MAX_VALUE - 1_000L
        assertThrows(ArithmeticException::class.java) {
            c.executeArmedSet(80, 60) { roomApplied() }
        }
    }
}
