package app.aaps.pump.medtrum.diagnosis

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * FORK DIAGNOSIS (Toni 20.11.2026): does the plunger follow the delivery the pump reports? All numbers are made up;
 * only the shape follows what the logs showed (about 20 steps per unit on a healthy patch).
 */
class PlungerWatchTest {

    private val minute = 60_000L
    private val start = 1_800_000_000_000L

    /** Feeds one sample per minute: the reservoir goes down by [perMinuteU], the plunger by [stepsPerU] for each unit. */
    private fun PlungerWatch.run(
        minutes: Int,
        fromMinute: Int,
        reservoir: Double,
        position: Int,
        perMinuteU: Double = 0.1,
        stepsPerU: Double = 20.0
    ): Triple<Double, Int, PlungerWatch.Status> {
        var r = reservoir
        var p = position.toDouble()
        var status = this.status
        for (i in 1..minutes) {
            r -= perMinuteU
            p -= perMinuteU * stepsPerU
            status = onSample(start + (fromMinute + i) * minute, Math.round(p).toInt(), r)
        }
        return Triple(r, Math.round(p).toInt(), status)
    }

    @Test
    fun `before the pump has reported one window of delivery there is no verdict`() {
        val watch = PlungerWatch()

        assertThat(watch.status.state).isEqualTo(PlungerWatch.State.UNKNOWN)
        assertThat(watch.onSample(start, 2000, 100.0).state).isEqualTo(PlungerWatch.State.UNKNOWN)
        // 0.6 U reported: still less than the window.
        val (_, _, status) = watch.run(minutes = 6, fromMinute = 0, reservoir = 100.0, position = 2000)

        assertThat(status.state).isEqualTo(PlungerWatch.State.UNKNOWN)
        assertThat(status.stepsPerUnit).isNull()
        assertThat(status.percentOfNormal).isNull()
        assertThat(status.position).isEqualTo(1988)
    }

    @Test
    fun `a plunger that follows the delivery is moving, with its steps per unit`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)

        // Before the long window is full the short one gives the steps.
        val (r1, p1, early) = watch.run(minutes = 10, fromMinute = 0, reservoir = 100.0, position = 2000)
        assertThat(early.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(early.stepsPerUnit!!).isWithin(2.0).of(20.0)

        val (_, _, status) = watch.run(minutes = 20, fromMinute = 10, reservoir = r1, position = p1)

        assertThat(status.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(status.stepsPerUnit!!).isWithin(2.0).of(20.0)
        assertThat(status.percentOfNormal!!).isAtLeast(90)
        assertThat(status.stalledSince).isNull()
        assertThat(status.slowSince).isNull()
    }

    /** A little less travel than usual is within what healthy patches show. */
    @Test
    fun `a plunger that travels a little less than usual is still moving`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)

        val (_, _, status) = watch.run(minutes = 30, fromMinute = 0, reservoir = 100.0, position = 2000, stepsPerU = 18.5)

        assertThat(status.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(status.stepsPerUnit!!).isWithin(1.0).of(18.5)
    }

    /**
     * The hint below the alarm: the plunger moves, but clearly less far than the reported delivery asks for. Then
     * less insulin left the reservoir than the pump counts.
     */
    @Test
    fun `a plunger that travels clearly less than usual is slow, with its share of the usual travel`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (r1, p1, healthy) = watch.run(minutes = 20, fromMinute = 0, reservoir = 100.0, position = 2000)
        assertThat(healthy.state).isEqualTo(PlungerWatch.State.MOVING)

        // From minute 20 on the plunger moves 14 steps for each unit instead of 20.
        val (r2, p2, slow) = watch.run(minutes = 20, fromMinute = 20, reservoir = r1, position = p1, stepsPerU = 14.0)

        assertThat(slow.state).isEqualTo(PlungerWatch.State.SLOW)
        assertThat(slow.stepsPerUnit!!).isWithin(1.0).of(14.0)
        assertThat(slow.stepsPerUnit!!).isLessThan(PlungerWatch.HINT_STEPS_PER_U)
        assertThat(slow.percentOfNormal!!).isIn(62..74)
        assertThat(slow.stalledSince).isNull()
        // The window that first showed it started before the slow travel began, and not before the patch did.
        assertThat(slow.slowSince!!).isAtLeast(start)
        assertThat(slow.slowSince!!).isAtMost(start + 20 * minute)

        // It stays slow, with the same start, for as long as the plunger moves like that.
        val (_, _, later) = watch.run(minutes = 30, fromMinute = 40, reservoir = r2, position = p2, stepsPerU = 14.0)
        assertThat(later.state).isEqualTo(PlungerWatch.State.SLOW)
        assertThat(later.slowSince).isEqualTo(slow.slowSince)
    }

    @Test
    fun `a slow plunger that moves as usual again is moving again`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (r1, p1, slow) = watch.run(minutes = 30, fromMinute = 0, reservoir = 100.0, position = 2000, stepsPerU = 14.0)
        assertThat(slow.state).isEqualTo(PlungerWatch.State.SLOW)

        val (_, _, status) = watch.run(minutes = 25, fromMinute = 30, reservoir = r1, position = p1)

        assertThat(status.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(status.slowSince).isNull()
    }

    /** The alarm wins over the hint: a slow plunger that stops is stalled. */
    @Test
    fun `a slow plunger that stops is stalled`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (r1, p1, slow) = watch.run(minutes = 30, fromMinute = 0, reservoir = 100.0, position = 2000, stepsPerU = 14.0)
        assertThat(slow.state).isEqualTo(PlungerWatch.State.SLOW)

        val (_, _, status) = watch.run(minutes = 15, fromMinute = 30, reservoir = r1, position = p1, stepsPerU = 0.0)

        assertThat(status.state).isEqualTo(PlungerWatch.State.STALLED)
        assertThat(status.slowSince).isNull()
        assertThat(status.stalledSince).isNotNull()
    }

    /**
     * The case the watch is for: the pump goes on counting doses, the plunger stands. One window of reported
     * delivery later the watch says so, and it names the time the standing began.
     */
    @Test
    fun `a plunger that stands while the pump reports delivery is stalled`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (reservoir, position, healthy) = watch.run(minutes = 20, fromMinute = 0, reservoir = 100.0, position = 2000)
        assertThat(healthy.state).isEqualTo(PlungerWatch.State.MOVING)

        // From minute 20 on the plunger stands. After 0.3 U reported it is not yet certain: the hint comes first ...
        val (r2, _, early) = watch.run(minutes = 3, fromMinute = 20, reservoir = reservoir, position = position, stepsPerU = 0.0)
        assertThat(early.state).isEqualTo(PlungerWatch.State.SLOW)
        // ... a few minutes later it is.
        val (r3, _, stalled) = watch.run(minutes = 7, fromMinute = 23, reservoir = r2, position = position, stepsPerU = 0.0)

        assertThat(stalled.state).isEqualTo(PlungerWatch.State.STALLED)
        assertThat(stalled.stepsPerUnit!!).isLessThan(PlungerWatch.MIN_STEPS_PER_U)
        assertThat(stalled.percentOfNormal!!).isLessThan(50)
        // The standing began around minute 20; the window that first showed it started shortly before or at it.
        assertThat(stalled.stalledSince!!).isAtLeast(start + 15 * minute)
        assertThat(stalled.stalledSince!!).isAtMost(start + 24 * minute)

        // It stays stalled, with the same start, for as long as the plunger stands.
        val (_, _, later) = watch.run(minutes = 60, fromMinute = 30, reservoir = r3, position = position, stepsPerU = 0.0)
        assertThat(later.state).isEqualTo(PlungerWatch.State.STALLED)
        assertThat(later.stalledSince).isEqualTo(stalled.stalledSince)
    }

    /** After a blocked patch the logs showed the plunger creeping back: that is no delivery either. */
    @Test
    fun `a plunger that moves backwards is stalled`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (reservoir, position, _) = watch.run(minutes = 20, fromMinute = 0, reservoir = 100.0, position = 2000)

        val (_, _, status) = watch.run(minutes = 15, fromMinute = 20, reservoir = reservoir, position = position, stepsPerU = -5.0)

        assertThat(status.state).isEqualTo(PlungerWatch.State.STALLED)
        assertThat(status.stepsPerUnit!!).isLessThan(0.0)
        assertThat(status.percentOfNormal).isEqualTo(0)
    }

    @Test
    fun `a plunger that moves again is moving again`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (r1, p1, _) = watch.run(minutes = 20, fromMinute = 0, reservoir = 100.0, position = 2000)
        val (r2, _, stalled) = watch.run(minutes = 15, fromMinute = 20, reservoir = r1, position = p1, stepsPerU = 0.0)
        assertThat(stalled.state).isEqualTo(PlungerWatch.State.STALLED)

        val (_, _, status) = watch.run(minutes = 20, fromMinute = 35, reservoir = r2, position = p1)

        assertThat(status.state).isEqualTo(PlungerWatch.State.MOVING)
        assertThat(status.stalledSince).isNull()
    }

    @Test
    fun `a new patch starts the series again`() {
        val watch = PlungerWatch()
        watch.onSample(start, 600, 30.0)
        val (r1, p1, _) = watch.run(minutes = 20, fromMinute = 0, reservoir = 30.0, position = 600)
        val (_, _, stalled) = watch.run(minutes = 15, fromMinute = 20, reservoir = r1, position = p1, stepsPerU = 0.0)
        assertThat(stalled.state).isEqualTo(PlungerWatch.State.STALLED)

        // A full reservoir and a plunger far back: a new patch that nobody announced.
        val fresh = watch.onSample(start + 40 * minute, 3000, 150.0)
        assertThat(fresh.state).isEqualTo(PlungerWatch.State.UNKNOWN)
        assertThat(fresh.stalledSince).isNull()

        // And an announced one.
        watch.run(minutes = 20, fromMinute = 40, reservoir = 150.0, position = 3000)
        watch.reset()
        assertThat(watch.status).isEqualTo(PlungerWatch.Status())
    }

    @Test
    fun `the same reading again and a reservoir that is no number change nothing`() {
        val watch = PlungerWatch()
        watch.onSample(start, 2000, 100.0)
        val (reservoir, position, before) = watch.run(minutes = 20, fromMinute = 0, reservoir = 100.0, position = 2000)

        // No delivery for hours (a zero basal): the same reading every minute.
        var status = before
        for (i in 1..300) status = watch.onSample(start + (20 + i) * minute, position, reservoir)
        assertThat(status).isEqualTo(before)

        assertThat(watch.onSample(start + 400 * minute, position, Double.NaN)).isEqualTo(before)
        assertThat(watch.onSample(start + 401 * minute, position, -1.0)).isEqualTo(before)
    }

    /** At the end of a reservoir the plunger is at its stop while the pump still counts a few units: the same alarm. */
    @Test
    fun `a plunger at the end of the reservoir is stalled`() {
        val watch = PlungerWatch()
        watch.onSample(start, 200, 10.0)
        val (reservoir, position, _) = watch.run(minutes = 40, fromMinute = 0, reservoir = 10.0, position = 200)

        val (_, _, status) = watch.run(minutes = 20, fromMinute = 40, reservoir = reservoir, position = position, perMinuteU = 0.05, stepsPerU = 0.0)

        assertThat(status.state).isEqualTo(PlungerWatch.State.STALLED)
    }

    @Test
    fun `the share of the usual travel stays between nothing and all`() {
        fun percent(steps: Double?) = PlungerWatch.Status(PlungerWatch.State.MOVING, steps).percentOfNormal

        assertThat(percent(null)).isNull()
        assertThat(percent(PlungerWatch.NORMAL_STEPS_PER_U)).isEqualTo(100)
        assertThat(percent(PlungerWatch.NORMAL_STEPS_PER_U / 2)).isEqualTo(50)
        // More travel than usual (the first units of a patch) is not more than all, and a plunger that creeps back is nothing.
        assertThat(percent(PlungerWatch.NORMAL_STEPS_PER_U * 1.3)).isEqualTo(100)
        assertThat(percent(-3.0)).isEqualTo(0)
    }
}
