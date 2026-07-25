package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Die generischen Wert-Leases (SMBRATIO / WEIGHTS) im selben Koordinator wie iobTH.
 * Schwerpunkt: Teil-Nutzlast, Float-Rundtrip der Basis, und dass sich die Capabilities
 * gegenseitig nicht anfassen.
 */
class ValueLeaseOverlayTest {

    private lateinit var c: AutoIsfValueLeaseCoordinator
    private var base = 50
    private var ratio = 0.2
    private val weights = mutableMapOf(
        ValueOverlayPolicy.F_ACCE to 0.23000000417232513,   // echter Float-Rundtrip aus den Prefs
        ValueOverlayPolicy.F_DURA to 0.800000011920929,
        ValueOverlayPolicy.F_PP to 0.029999999329447746,
    )
    private var gates = AutoIsfValueLeaseCoordinator.Gates(
        channelEnabled = true,
        capabilityEnabled = AutoIsfCapability.entries.associateWith { true },
        forcedValidateOnly = false,
    )
    private var wall = 1_784_500_000_000L
    private var elapsed = 1_000_000L

    @BeforeEach fun setup() {
        c = AutoIsfValueLeaseCoordinator(mock(), mock())
        c.basePercentReader = { base }
        c.ratioReader = { ratio }
        c.weightReader = { f -> weights[f] ?: 0.0 }
        c.gatesReader = { gates }
        c.wallClock = { wall }
        c.elapsedClock = { elapsed }
    }

    private fun applied(id: String = "v-1", version: Long = 1) =
        AutoIsfValueLeaseCoordinator.RoomSetResult("APPLIED", null, false, null, id, version)

    private fun setRatio(v: Double, ttl: Int = 60) = c.executeArmedValueSet(
        AutoIsfCapability.SMBRATIO, mapOf(ValueOverlayPolicy.F_RATIO to ValueOverlayPolicy.scaled(v)), ttl,
    ) { applied() }

    private fun setWeights(vararg pairs: Pair<String, Double>, ttl: Int = 60) = c.executeArmedValueSet(
        AutoIsfCapability.WEIGHTS, pairs.associate { it.first to ValueOverlayPolicy.scaled(it.second) }, ttl,
    ) { applied("w-1", 1) }

    @Test fun `ohne Lease gilt die Basis - null statt Basiswert`() {
        val s = c.snapshot()
        assertThat(s.smbRatioEffective).isNull()
        assertThat(s.smbRatioState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(s.weightOverrides).isEmpty()
        assertThat(s.weightsState).isEqualTo(AutoIsfOverrideState.NONE)
    }

    @Test fun `Ratio-Lease wirkt im Snapshot`() {
        assertThat(setRatio(0.3).currentLeaseState).isEqualTo(AutoIsfOverrideState.ACTIVE)
        val s = c.snapshot()
        assertThat(s.smbRatioEffective!!).isWithin(1e-9).of(0.3)
        assertThat(s.smbRatioState).isEqualTo(AutoIsfOverrideState.ACTIVE)
        // iobTH bleibt unberuehrt
        assertThat(s.iobThPercentEffective).isEqualTo(50)
        assertThat(s.overrideState).isEqualTo(AutoIsfOverrideState.NONE)
    }

    @Test fun `Teil-Nutzlast - nur bgAccel wird ueberlagert`() {
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        val s = c.snapshot()
        assertThat(s.weightOverrides.keys).containsExactly(ValueOverlayPolicy.F_ACCE)
        assertThat(s.weightOverrides[ValueOverlayPolicy.F_ACCE]!!).isWithin(1e-9).of(0.4)
        // dura und pp tauchen NICHT auf -> der Leser nimmt dort weiter die Preference
        assertThat(s.weightOverrides).doesNotContainKey(ValueOverlayPolicy.F_DURA)
    }

    @Test fun `mehrere Gewichte in EINEM Kommando sind atomar`() {
        setWeights(
            ValueOverlayPolicy.F_ACCE to 0.4,
            ValueOverlayPolicy.F_DURA to 1.2,
        )
        val s = c.snapshot()
        assertThat(s.weightOverrides.keys).containsExactly(ValueOverlayPolicy.F_ACCE, ValueOverlayPolicy.F_DURA)
    }

    @Test fun `Float-Rundtrip der Basis loest KEIN FOREIGN_MODIFIED aus`() {
        // Die Basis steht auf 0.23000000417232513; ohne Toleranzvergleich wuerde die Lease
        // sofort als fremdveraendert gelten und dauerhaft latchen.
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        assertThat(c.snapshot().weightsState).isEqualTo(AutoIsfOverrideState.ACTIVE)
        // minimal andere Float-Repraesentation derselben Zahl
        weights[ValueOverlayPolicy.F_ACCE] = 0.23000000417232515
        assertThat(c.snapshot().weightsState).isEqualTo(AutoIsfOverrideState.ACTIVE)
    }

    @Test fun `echte Basis-Aenderung ist FOREIGN_MODIFIED und latcht`() {
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        weights[ValueOverlayPolicy.F_ACCE] = 0.30
        assertThat(c.snapshot().weightsState).isEqualTo(AutoIsfOverrideState.FOREIGN_MODIFIED)
        assertThat(c.snapshot().weightOverrides).isEmpty()
        // zuruecksetzen belebt NICHT
        weights[ValueOverlayPolicy.F_ACCE] = 0.23000000417232513
        assertThat(c.snapshot().weightsState).isEqualTo(AutoIsfOverrideState.FOREIGN_MODIFIED)
    }

    @Test fun `TTL laeuft ab - doppelte Frist`() {
        setRatio(0.3, ttl = 30)
        wall += 30 * 60_000
        assertThat(c.snapshot().smbRatioState).isEqualTo(AutoIsfOverrideState.EXPIRED)
        assertThat(c.snapshot().smbRatioEffective).isNull()
    }

    @Test fun `eigener Capability-Schalter aus - nur diese Lease stirbt`() {
        setRatio(0.3)
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        gates = gates.withCapability(AutoIsfCapability.SMBRATIO, false)
        val s = c.snapshot()
        assertThat(s.smbRatioState).isEqualTo(AutoIsfOverrideState.DISABLED)
        assertThat(s.weightsState).isEqualTo(AutoIsfOverrideState.ACTIVE)
    }

    @Test fun `Master-Schalter aus trifft beide`() {
        setRatio(0.3)
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        gates = gates.copy(channelEnabled = false)
        val s = c.snapshot()
        assertThat(s.smbRatioState).isEqualTo(AutoIsfOverrideState.DISABLED)
        assertThat(s.weightsState).isEqualTo(AutoIsfOverrideState.DISABLED)
    }

    @Test fun `unsicheres Gate verhindert die Transaktion ueberhaupt`() {
        gates = gates.withCapability(AutoIsfCapability.WEIGHTS, false)
        var called = false
        val r = c.executeArmedValueSet(AutoIsfCapability.WEIGHTS, mapOf(ValueOverlayPolicy.F_ACCE to 400L), 60) {
            called = true; applied()
        }
        assertThat(called).isFalse()
        assertThat(r.room.outcome).isEqualTo("REJECTED")
    }

    @Test fun `CLEAR gibt die Lease frei`() {
        setRatio(0.3)
        val r = c.executeArmedValueClear(AutoIsfCapability.SMBRATIO) { applied() }
        assertThat(r.currentLeaseState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(c.snapshot().smbRatioEffective).isNull()
    }

    @Test fun `Terminalisierung ist identitaetsgebunden und nennt die Capability`() {
        setRatio(0.3, ttl = 30)
        wall += 30 * 60_000
        c.snapshot()
        val pt = c.peekPendingTerminal()!!
        assertThat(pt.capability).isEqualTo("SMBRATIO")
        assertThat(pt.leaseId).isEqualTo("v-1")
        assertThat(pt.reason).isEqualTo(AutoIsfOverrideState.EXPIRED.name)
    }

    // Selbstreview 25.07.: der frueher hier stehende read-modify-write auf der Slot-Map konnte
    // einen Widerruf verlieren, der lockfrei aus dem LESE-Pfad kam. Folge waere eine abgelaufene
    // Lease gewesen, die wieder aktiv erscheint — Verletzung der Latch-Invariante.
    @Test fun `ein Kommando auf Capability B loescht keinen Widerruf von Capability A`() {
        setRatio(0.3, ttl = 30)
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        // Ratio laeuft ab und wird beim Lesen gelatcht
        wall += 30 * 60_000
        assertThat(c.snapshot().smbRatioState).isEqualTo(AutoIsfOverrideState.EXPIRED)
        // jetzt ein neues WEIGHTS-Kommando: es darf den Ratio-Widerruf NICHT aufheben
        c.executeArmedValueSet(
            AutoIsfCapability.WEIGHTS, mapOf(ValueOverlayPolicy.F_DURA to 1200L), 60,
        ) { applied("w-2", 2) }
        val s = c.snapshot()
        assertThat(s.smbRatioState).isEqualTo(AutoIsfOverrideState.EXPIRED)
        assertThat(s.smbRatioEffective).isNull()
        assertThat(s.weightsState).isEqualTo(AutoIsfOverrideState.ACTIVE)
    }

    @Test fun `CLEAR auf B laesst eine aktive Lease auf A stehen`() {
        setRatio(0.3)
        setWeights(ValueOverlayPolicy.F_ACCE to 0.4)
        c.executeArmedValueClear(AutoIsfCapability.WEIGHTS) { applied("w-1", 1) }
        val s = c.snapshot()
        assertThat(s.weightsState).isEqualTo(AutoIsfOverrideState.NONE)
        assertThat(s.smbRatioState).isEqualTo(AutoIsfOverrideState.ACTIVE)
        assertThat(s.smbRatioEffective!!).isWithin(1e-9).of(0.3)
    }
}
