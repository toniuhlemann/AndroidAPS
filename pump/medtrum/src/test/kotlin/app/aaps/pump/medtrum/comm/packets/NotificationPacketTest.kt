package app.aaps.pump.medtrum.comm.packets

import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.pump.medtrum.MedtrumTestBase
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.diagnosis.PlungerWatch
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NotificationPacketTest : MedtrumTestBase() {

    val medtrumTimeUtil = MedtrumTimeUtil()

    /** Test base behavior of the Notification packet  */

    private val packetInjector = MetroMemberInjector {
        if (it is NotificationPacket) {
                it.aapsLogger = aapsLogger
                it.medtrumPump = medtrumPump
                it.medtrumTimeUtil = medtrumTimeUtil
        }
        true
    }

    @Test fun handleNotificationGivenStatusAndDataThenStateSaved() {
        // Inputs
        val state: Byte = 1

        // Call
        NotificationPacket(packetInjector).handleNotification(byteArrayOf(state))

        // Expected values
        assertThat(MedtrumPumpState.fromByte(state)).isEqualTo(medtrumPump.pumpState)
    }

    @Test fun handleNotificationGivenBasalDataThenDataSaved() {
        // Inputs
        val data = byteArrayOf(32, 40, 64, 6, 25, 0, 14, 0, 84, -93, -83, 17, 17, 64, 0, -104, 14, 0, 16)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.lastBasalType).isEqualTo(BasalType.ABSOLUTE_TEMP)
        assertThat(medtrumPump.lastBasalRate).isWithin(0.01).of(0.85)
        assertThat(medtrumPump.lastBasalSequence).isEqualTo(25)
        assertThat(medtrumPump.lastBasalPatchId).isEqualTo(14)
        assertThat(medtrumPump.lastBasalStartTime).isEqualTo(1685126612000)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(186.80)
    }

    @Test fun handleNotificationGivenSequenceAndOtherDataThenDataSaved() {
        // Inputs
        val data = byteArrayOf(32, 0, 17, -89, 0, 14, 0, 0, 0, 0, 0, 0)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.currentSequenceNumber).isEqualTo(167)
    }

    @Test fun handleNotificationGivenBolusInProgressThenDataSaved() {
        // Inputs
        val data = byteArrayOf(32, 34, 16, 0, 3, 0, -58, 12, 0, 0, 0, 0, 0)
        bolusProgressData.start(insulin = 1.0, isSMB = false)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.bolusDone).isFalse()
        assertThat(bolusProgressData.state.value?.delivered?.cU ?: 0.0).isWithin(0.01).of(0.15)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(163.5)
    }

    @Test fun handleNotificationGivenBolusFinishedThenDataSaved() {
        // Inputs
        val data = byteArrayOf(32, 34, 17, -128, 33, 0, -89, 12, -80, 0, 14, 0, 0, 0, 0, 0, 0)
        bolusProgressData.start(insulin = 1.0, isSMB = false)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.bolusDone).isTrue()
        assertThat(bolusProgressData.state.value?.delivered?.cU ?: 0.0).isWithin(0.01).of(1.65)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(161.95)
    }

    @Test fun handleNotificationGivenFieldMaskButMessageTooShortThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(67, 41, 67, -1, 122, 95, 18, 0, 73, 1, 19, 0, 1, 0, 20, 0, 0, 0, 0, 16)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.suspendTime).isEqualTo(0)
        assertThat(medtrumPump.lastBasalStartTime).isEqualTo(0)
        assertThat(medtrumPump.currentSequenceNumber).isEqualTo(0)
    }

    // Testcase from real erroneous message
    @Test fun handleNotificationGivenErroneousMessageThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(32, 34, 17, -128, 4, -2, 20, -1, -89, 5, 6, 0, 18, 64, 35, 0, -54)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.suspendTime).isEqualTo(0)
        assertThat(medtrumPump.lastBasalStartTime).isEqualTo(0)
        assertThat(medtrumPump.currentSequenceNumber).isEqualTo(0)
    }

    @Test fun handleNotificationGivenBolusOutOfRangeThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(32, 34, 17, -128, -128, -128, -89, 12, -80, 0, 14, 0, 0, 0, 0, 0, 0)
        bolusProgressData.start(insulin = 1.0, isSMB = false)
        // Set valid patchID (as in a started pump session)
        medtrumPump.patchId = 14

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.bolusDone).isTrue()
        assertThat(bolusProgressData.state.value?.delivered?.cU ?: 0.0).isWithin(0.01).of(0.0)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(0.0)
    }

    @Test fun handleNotificationGiveWrongPatchIDInBasalDataThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(32, 40, 64, 6, 25, 0, 14, 0, 84, -93, -83, 17, 17, 64, 0, -104, 15, 0, 16)
        // Set a valid patchID (as in a started pump session)
        medtrumPump.patchId = 15

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.lastBasalType).isEqualTo(BasalType.NONE)
        assertThat(medtrumPump.lastBasalRate).isWithin(0.01).of(0.0)
        assertThat(medtrumPump.lastBasalSequence).isEqualTo(0)
        assertThat(medtrumPump.lastBasalPatchId).isEqualTo(0)
        assertThat(medtrumPump.lastBasalStartTime).isEqualTo(0)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(0.0)
    }

    @Test fun handleNotificationGiveBasalOutOfRangeInBasalDataThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(32, 40, 64, 6, 25, 0, 14, 0, 84, -93, -83, 17, 127, 127, -128, -104, 14, 0, 16)
        // Set a valid patchID (as in a started pump session)
        medtrumPump.patchId = 14

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.lastBasalType).isEqualTo(BasalType.NONE)
        assertThat(medtrumPump.lastBasalRate).isWithin(0.01).of(0.0)
        assertThat(medtrumPump.lastBasalSequence).isEqualTo(0)
        assertThat(medtrumPump.lastBasalPatchId).isEqualTo(0)
        assertThat(medtrumPump.lastBasalStartTime).isEqualTo(0)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(0.0)
    }

    @Test fun handleNotificationGivenReservoirOutOfRangeThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(32, 34, 16, 0, 3, 0, -128, -128, 0, 0, 0, 0, 0)
        bolusProgressData.start(insulin = 1.0, isSMB = false)

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.bolusDone).isTrue()
        assertThat(bolusProgressData.state.value?.delivered?.cU ?: 0.0).isWithin(0.01).of(0.0)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(0.0)
    }

    @Test fun handleNotificationGivenWrongPatchIDThenNothingSaved() {
        // Inputs
        val data = byteArrayOf(32, 34, 17, -128, 33, 0, -89, 12, -80, 0, 15, 0, 0, 0, 0, 0, 0)
        bolusProgressData.start(insulin = 1.0, isSMB = false)
        // Set valid patchID (as in a started pump session)
        medtrumPump.patchId = 14

        // Call
        NotificationPacket(packetInjector).handleNotification(data)

        // Expected values
        assertThat(medtrumPump.bolusDone).isTrue()
        assertThat(bolusProgressData.state.value?.delivered?.cU ?: 0.0).isWithin(0.01).of(0.0)
        assertThat(medtrumPump.reservoir).isWithin(0.01).of(0.0)
    }

    // ---- Fork diagnosis (Toni 20.11.2026): values the patch sends and the upstream driver does not use ----------

    /** A status message with the fields reservoir (0x20) and plunger position (0x800). Made-up values. */
    private fun plungerMessage(state: Byte, reservoirSteps: Int, position: Int) = byteArrayOf(
        state, 0x20, 0x08,
        (reservoirSteps and 0xFF).toByte(), (reservoirSteps shr 8).toByte(),
        (position and 0xFF).toByte(), (position shr 8).toByte()
    )

    @Test fun handleNotificationGivenPlungerPositionThenForkDiagnosisFollowsIt() {
        // An active patch (state 32) with 100 U counted and the plunger at step 2000
        NotificationPacket(packetInjector).handleNotification(plungerMessage(32, 2000, 2000))

        assertThat(medtrumPump.reservoir).isWithin(0.001).of(100.0)
        assertThat(medtrumPump.plungerStatus.position).isEqualTo(2000)
        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.UNKNOWN)

        // A minute later the pump has counted one unit, and the plunger went 20 steps: it moves
        now += 60_000L
        NotificationPacket(packetInjector).handleNotification(plungerMessage(32, 1980, 1980))
        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(medtrumPump.plungerStatus.stepsPerUnit!!).isWithin(0.01).of(20.0)

        // Another unit counted, the plunger stands: no insulin leaves the reservoir
        now += 60_000L
        NotificationPacket(packetInjector).handleNotification(plungerMessage(32, 1960, 1980))
        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.STALLED)

        // In a fault state of the pump the verdict stays ...
        NotificationPacket(packetInjector).handleNotification(plungerMessage(100, 1960, 1980))
        assertThat(medtrumPump.pumpState).isEqualTo(MedtrumPumpState.PATCH_FAULT2)
        assertThat(medtrumPump.plungerStatus.state).isEqualTo(PlungerWatch.State.STALLED)

        // ... and a patch that is being primed starts the series again
        NotificationPacket(packetInjector).handleNotification(plungerMessage(3, 4000, 4000))
        assertThat(medtrumPump.plungerStatus).isEqualTo(PlungerWatch.Status())
    }

    @Test fun handleNotificationGivenAlarmFlagsThenAllBitsKept() {
        // Alarm field (0x200): flags 1028 = bit 2 (low reservoir, read by the driver) and bit 10, parameter 0
        NotificationPacket(packetInjector).handleNotification(byteArrayOf(32, 0x00, 0x02, 0x04, 0x04, 0, 0))

        assertThat(medtrumPump.rawAlarmFlags).isEqualTo(1028)
        // The driver reads its own bits as before
        assertThat(medtrumPump.activeAlarms).contains(AlarmState.PUMP_LOW_RESERVOIR)

        // No alarm any more
        NotificationPacket(packetInjector).handleNotification(byteArrayOf(32, 0x00, 0x02, 0, 0, 0, 0))
        assertThat(medtrumPump.rawAlarmFlags).isEqualTo(0)
        assertThat(medtrumPump.activeAlarms).isEmpty()
    }

    @Test fun handleNotificationGivenUnknownStateCodeThenCodeKeptAndFieldsRead() {
        NotificationPacket(packetInjector).handleNotification(byteArrayOf(32))
        assertThat(medtrumPump.unknownStateCode).isNull()

        // State code 104 has no name in the driver; the message also carries the reservoir (0x20): 50 U
        NotificationPacket(packetInjector).handleNotification(byteArrayOf(104, 0x20, 0x00, (1000 and 0xFF).toByte(), (1000 shr 8).toByte()))

        assertThat(medtrumPump.unknownStateCode).isEqualTo(104)
        // The state stays as it was, and the fields are still read
        assertThat(medtrumPump.pumpState).isEqualTo(MedtrumPumpState.ACTIVE)
        assertThat(medtrumPump.reservoir).isWithin(0.001).of(50.0)

        // A code above 127 arrives as a negative byte and is shown as it is
        NotificationPacket(packetInjector).handleNotification(byteArrayOf(200.toByte()))
        assertThat(medtrumPump.unknownStateCode).isEqualTo(200)
    }
}
