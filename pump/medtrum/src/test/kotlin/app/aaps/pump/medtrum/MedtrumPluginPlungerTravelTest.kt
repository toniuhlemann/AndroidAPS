package app.aaps.pump.medtrum

import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.PlungerTravel
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.diagnosis.PlungerWatch
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock

/**
 * FORK DIAGNOSIS (Toni 20.11.2026): the loop asks the pump how far its plunger moves, to word its own warning. The
 * pump only answers with a number while the plunger moves too short. Made-up numbers.
 */
class MedtrumPluginPlungerTravelTest : MedtrumTestBase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var protectionCheck: ProtectionCheck
    @Mock lateinit var blePreCheck: BlePreCheck

    private lateinit var plugin: MedtrumPlugin

    @BeforeEach
    fun preparePlugin() {
        plugin = MedtrumPlugin(
            aapsLogger, rh, preferences, commandQueue, rxBus, context, dateUtil, medtrumPump, temporaryBasalStorage,
            pumpEnactResultProvider, protectionCheck, blePreCheck, notificationManager
        )
    }

    /** An active patch that reported two units of delivery while the plunger moved [steps]. */
    private fun twoUnitsWith(steps: Int) {
        medtrumPump.pumpState = MedtrumPumpState.ACTIVE
        medtrumPump.reservoir = 100.0
        medtrumPump.handlePlungerPosition(2000)
        now += 60_000L
        medtrumPump.reservoir = 98.0
        medtrumPump.handlePlungerPosition(2000 - steps)
    }

    @Test
    fun `the pump answers the loop as a plunger travel source`() {
        assertThat(plugin).isInstanceOf(PlungerTravel::class.java)
        // No patch, no verdict, no number
        assertThat(plugin.slowPlungerPercent).isNull()
    }

    @Test
    fun `a plunger that moves too short gives its share of the usual travel`() {
        twoUnitsWith(steps = 30)

        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.SLOW)
        assertThat(plugin.slowPlungerPercent).isEqualTo(medtrumPump.plungerStatus.percentOfNormal)
        assertThat(plugin.slowPlungerPercent!!).isIn(68..78)
    }

    @Test
    fun `a plunger that moves as usual gives no number`() {
        twoUnitsWith(steps = 41)

        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(plugin.slowPlungerPercent).isNull()
    }

    /** A plunger that stands is the alarm of the pump itself, not a hint for the loop. */
    @Test
    fun `a plunger that stands gives no number`() {
        twoUnitsWith(steps = 0)

        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.STALLED)
        assertThat(plugin.slowPlungerPercent).isNull()
    }
}
