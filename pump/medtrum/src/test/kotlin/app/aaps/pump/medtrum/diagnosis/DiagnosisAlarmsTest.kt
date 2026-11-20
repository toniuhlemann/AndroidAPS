package app.aaps.pump.medtrum.diagnosis

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** FORK DIAGNOSIS (Toni 20.11.2026): when the user is told, and which alarm bits are news. Made-up numbers. */
class DiagnosisAlarmsTest {

    private val minute = 60_000L
    private val start = 1_800_000_000_000L
    private val stalled = PlungerWatch.Status(PlungerWatch.State.STALLED, 1.0, start, 1000)
    private val moving = PlungerWatch.Status(PlungerWatch.State.MOVING, 20.0, null, 1000)
    private val slow = PlungerWatch.Status(PlungerWatch.State.SLOW, 15.0, null, 1000, slowSince = start)

    @Test
    fun `a standing plunger alarms at once and again every quarter of an hour`() {
        val alarm = PlungerAlarm()

        assertThat(alarm.onStatus(stalled, start)).isEqualTo(PlungerAlarm.Action.ALARM)
        // Every status message while it stands: no new alarm ...
        assertThat(alarm.onStatus(stalled.copy(position = 1001), start + minute)).isEqualTo(PlungerAlarm.Action.NONE)
        assertThat(alarm.onStatus(stalled, start + 14 * minute)).isEqualTo(PlungerAlarm.Action.NONE)
        // ... until the reminder is due.
        assertThat(alarm.onStatus(stalled, start + 15 * minute)).isEqualTo(PlungerAlarm.Action.ALARM)
        assertThat(alarm.onStatus(stalled, start + 16 * minute)).isEqualTo(PlungerAlarm.Action.NONE)
    }

    @Test
    fun `a plunger that moves again takes the alarm away, and without an alarm nothing happens`() {
        val alarm = PlungerAlarm()

        assertThat(alarm.onStatus(moving, start)).isEqualTo(PlungerAlarm.Action.NONE)
        assertThat(alarm.onStatus(PlungerWatch.Status(), start)).isEqualTo(PlungerAlarm.Action.NONE)

        alarm.onStatus(stalled, start)
        assertThat(alarm.onStatus(moving, start + minute)).isEqualTo(PlungerAlarm.Action.DISMISS)
        assertThat(alarm.onStatus(moving, start + 2 * minute)).isEqualTo(PlungerAlarm.Action.NONE)
        // A new patch (no verdict yet) takes it away as well, and a new stall alarms at once.
        alarm.onStatus(stalled, start + 3 * minute)
        assertThat(alarm.onStatus(PlungerWatch.Status(), start + 4 * minute)).isEqualTo(PlungerAlarm.Action.DISMISS)
        assertThat(alarm.onStatus(stalled, start + 5 * minute)).isEqualTo(PlungerAlarm.Action.ALARM)
    }

    /** The hint is said once and then left alone: a hint the user has put away stays away. */
    @Test
    fun `a slow plunger is hinted at once, and not again while it stays slow`() {
        val hint = PlungerHint()

        assertThat(hint.onStatus(moving)).isEqualTo(PlungerHint.Action.NONE)
        assertThat(hint.onStatus(slow)).isEqualTo(PlungerHint.Action.HINT)
        assertThat(hint.onStatus(slow.copy(position = 990, stepsPerUnit = 14.0))).isEqualTo(PlungerHint.Action.NONE)
        assertThat(hint.onStatus(slow.copy(position = 980))).isEqualTo(PlungerHint.Action.NONE)
    }

    @Test
    fun `a plunger that is no longer slow takes the hint away, whatever comes instead`() {
        val hint = PlungerHint()

        hint.onStatus(slow)
        assertThat(hint.onStatus(moving)).isEqualTo(PlungerHint.Action.DISMISS)
        assertThat(hint.onStatus(moving)).isEqualTo(PlungerHint.Action.NONE)
        // A plunger that stands: the alarm speaks, the hint goes. Slow again afterwards is a new hint.
        hint.onStatus(slow)
        assertThat(hint.onStatus(stalled)).isEqualTo(PlungerHint.Action.DISMISS)
        assertThat(hint.onStatus(slow)).isEqualTo(PlungerHint.Action.HINT)
        // A new patch (no verdict yet) takes it away as well.
        assertThat(hint.onStatus(PlungerWatch.Status())).isEqualTo(PlungerHint.Action.DISMISS)
    }

    /** The alarm and the hint do not get in each other's way: one status feeds both. */
    @Test
    fun `the alarm does not speak for a slow plunger, and the hint not for a standing one`() {
        val alarm = PlungerAlarm()
        val hint = PlungerHint()

        assertThat(alarm.onStatus(slow, start)).isEqualTo(PlungerAlarm.Action.NONE)
        assertThat(hint.onStatus(stalled)).isEqualTo(PlungerHint.Action.NONE)
    }

    @Test
    fun `the alarm bits are read as numbers, lowest first`() {
        assertThat(PumpAlarmBits.bits(0)).isEmpty()
        assertThat(PumpAlarmBits.bits(4)).containsExactly(2)
        assertThat(PumpAlarmBits.bits(1028)).containsExactly(2, 10).inOrder()
        assertThat(PumpAlarmBits.bits(16388)).containsExactly(2, 14).inOrder()
        assertThat(PumpAlarmBits.bits(0xFFFF)).hasSize(16)
    }

    /** Bits 1 to 3 belong to the driver; 10 and 14 came in the logs with a state the driver shows anyway. */
    @Test
    fun `only a bit nobody has a name for is news`() {
        assertThat(PumpAlarmBits.unknown(0)).isEmpty()
        assertThat(PumpAlarmBits.unknown(2 + 4 + 8)).isEmpty()
        assertThat(PumpAlarmBits.unknown(1024)).isEmpty()
        assertThat(PumpAlarmBits.unknown(16388)).isEmpty()
        assertThat(PumpAlarmBits.unknown(128)).containsExactly(7)
        assertThat(PumpAlarmBits.unknown(4 + 128 + 512 + 1024)).containsExactly(7, 9).inOrder()
        // The same for one bit by its number, as the alarm record of the pump history carries it.
        assertThat(PumpAlarmBits.isKnown(2)).isTrue()
        assertThat(PumpAlarmBits.isKnown(10)).isTrue()
        assertThat(PumpAlarmBits.isKnown(7)).isFalse()
        assertThat(PumpAlarmBits.isKnown(99)).isFalse()
    }
}
