package app.aaps.workflow

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.FuseOverviewSource.AccountStatus
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChannelState
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.FuseChannelEnd
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Fork (FUSE): the series of F.DRV and F.GRD, as the fork built them in `PrepareIobAutosensGraphDataWorker`.
 * Synthetic numbers only.
 */
class FuseGraphDataBuilderTest {

    private val minute = 60_000L
    private val t0 = 1_000L * 5 * minute // on a 5 minute slot boundary

    private fun point(min: Int, drive: Double? = null, mealWindow: Boolean = false, expected: Double? = null, guard: Double? = null, status: AccountStatus? = null) =
        FuseOverviewSource.Point(
            timestamp = t0 + min * minute,
            driveMgdlPerMin = drive,
            fastDriveMgdlPerMin = null,
            guardMarginMgdl = guard,
            mealWindow = mealWindow,
            expectedMealDriveMgdlPerMin = expected,
            accountStatus = status
        )

    private fun source(points: List<FuseOverviewSource.Point>, markers: List<Long> = emptyList()): FuseOverviewSource =
        mock<FuseOverviewSource>().also {
            whenever(it.fuseGraphPoints(any(), any())).thenReturn(points)
            whenever(it.fuseRampLevels()).thenReturn(0.5 to 2.0)
            whenever(it.fuseMealMarkerTimes(any(), any())).thenReturn(markers)
        }

    @Test
    fun `without FUSE as the active APS nothing is offered`() {
        assertThat(FuseGraphDataBuilder.build(null, 0L, Long.MAX_VALUE).available).isFalse()
    }

    @Test
    fun `the drive is one bar per 5 minutes - the mean, at the newest point, colored by sign and meal window`() {
        val points = listOf(
            point(0, drive = 1.0), point(1, drive = 3.0),                    // slot 1: mean 2, no meal window
            point(5, drive = 1.0, mealWindow = true), point(6, drive = 2.0), // slot 2: in the meal window
            point(10, drive = -1.0, mealWindow = true)                       // slot 3: negative stays red
        )
        val data = FuseGraphDataBuilder.build(source(points), 0L, Long.MAX_VALUE)

        assertThat(data.available).isTrue()
        assertThat(data.drive.map { it.timestamp }).containsExactly(t0 + minute, t0 + 6 * minute, t0 + 10 * minute).inOrder()
        assertThat(data.drive.map { it.value }).containsExactly(2.0, 1.5, -1.0).inOrder()
        assertThat(data.drive.map { it.deviationType }).containsExactly(DeviationType.POSITIVE, DeviationType.UAM, DeviationType.NEGATIVE).inOrder()
        assertThat(data.rampLow).isEqualTo(0.5)
        assertThat(data.rampHigh).isEqualTo(2.0)
    }

    @Test
    fun `account lines break at a gap - one line would join two meals`() {
        val points = listOf(point(0, expected = 1.0), point(1, expected = 1.2), point(10, expected = 0.8))
        val data = FuseGraphDataBuilder.build(source(points), 0L, Long.MAX_VALUE)

        assertThat(data.expectedDrive).hasSize(2)
        assertThat(data.expectedDrive[0].map { it.value }).containsExactly(1.0, 1.2).inOrder()
        assertThat(data.expectedDrive[1].map { it.value }).containsExactly(0.8)
    }

    @Test
    fun `the account strip joins cycles of the same state and lasts one cycle past the last`() {
        val points = listOf(
            point(0, status = AccountStatus.AKTIV), point(1, status = AccountStatus.AKTIV),
            point(2, status = AccountStatus.WARTET),
            point(3, status = null)
        )
        val strip = FuseGraphDataBuilder.build(source(points), 0L, Long.MAX_VALUE).accountStatus

        assertThat(strip.map { it.status }).containsExactly(AccountStatus.AKTIV, AccountStatus.WARTET).inOrder()
        assertThat(strip[0].startTime).isEqualTo(t0)
        assertThat(strip[0].endTime).isEqualTo(t0 + 2 * minute)
        assertThat(strip[1].endTime).isEqualTo(t0 + 3 * minute)
    }

    @Test
    fun `meal markers come sorted and the guard keeps every point`() {
        val points = listOf(point(0, guard = 12.0), point(1, guard = -3.0))
        val data = FuseGraphDataBuilder.build(source(points, markers = listOf(t0 + 9 * minute, t0 + 2 * minute)), 0L, Long.MAX_VALUE)

        assertThat(data.mealMarkers).containsExactly(t0 + 2 * minute, t0 + 9 * minute).inOrder()
        assertThat(data.guard.map { it.value }).containsExactly(12.0, -3.0).inOrder()
    }

    // ---- Main graph layers (Toni 21.10.2026) ------------------------------------------------------------------------

    private fun cycle(
        min: Int,
        state: CycleState = CycleState.FREE,
        smbU: Double? = null,
        source: SmbSource? = null,
        channel: ChannelState? = null,
        binding: String? = "guardFloor=70.0",
        demand: Double? = 0.4
    ) = point(min).copy(cycleState = state, smbU = smbU, smbSource = source, channel = channel, bindingLimit = binding, insulinReqU = demand)

    private fun running(rate: Double = 2.0, extra: Double = 1.5, delivered: Double = 0.1) =
        ChannelState(phase = "RUNNING", reason = "CONTINUE", rateUPerH = rate, extraUPerH = extra, deliveredU = delivered)

    private fun layersSource(points: List<FuseOverviewSource.Point>, on: Boolean = true): FuseOverviewSource =
        source(points).also { whenever(it.fuseMainGraphLayers()).thenReturn(on) }

    @Test
    fun `with the layers off the main graph gets nothing`() {
        val data = FuseGraphDataBuilder.build(layersSource(listOf(cycle(0), cycle(1, smbU = 0.2)), on = false), 0L, Long.MAX_VALUE)

        assertThat(data.mainLayers).isFalse()
        assertThat(data.cycles).isEmpty()
        assertThat(data.smbSends).isEmpty()
        assertThat(data.channelEpisodes).isEmpty()
    }

    @Test
    fun `each cycle carries its state, limit and demand, the factor only while the channel is active`() {
        val points = listOf(cycle(0, CycleState.GUARD), cycle(1, CycleState.CHANNEL, channel = running()), point(2))
        val data = FuseGraphDataBuilder.build(layersSource(points), 0L, Long.MAX_VALUE)

        assertThat(data.mainLayers).isTrue()
        assertThat(data.cycles.map { it.state }).containsExactly(CycleState.GUARD, CycleState.CHANNEL).inOrder()
        assertThat(data.cycles[0].bindingLimit).isEqualTo("guardFloor=70.0")
        assertThat(data.cycles[0].insulinReqU).isEqualTo(0.4)
        assertThat(data.cycles[0].channelFactor).isNull()
        assertThat(data.cycles[1].channelFactor).isWithin(1e-9).of(4.0)
    }

    @Test
    fun `only cycles that handed an SMB to AAPS are sends, without a source they count as other`() {
        val points = listOf(cycle(0, smbU = 0.3, source = SmbSource.EXPECTATION), cycle(1, smbU = 0.0), cycle(2, smbU = 0.1))
        val sends = FuseGraphDataBuilder.build(layersSource(points), 0L, Long.MAX_VALUE).smbSends

        assertThat(sends.map { it.units }).containsExactly(0.3, 0.1).inOrder()
        assertThat(sends.map { it.source }).containsExactly(SmbSource.EXPECTATION, SmbSource.OTHER).inOrder()
    }

    @Test
    fun `a channel run ends at its ENDING cycle and carries the highest factor and the delivered insulin`() {
        val points = listOf(
            cycle(0, channel = ChannelState(phase = "NONE", reason = "WAIT_ENTRY")),
            cycle(1, CycleState.CHANNEL, channel = ChannelState(phase = "PENDING", reason = "START", rateUPerH = 1.0, extraUPerH = 0.5, deliveredU = 0.0)),
            cycle(2, CycleState.CHANNEL, channel = running(delivered = 0.2)),
            cycle(3, channel = ChannelState(phase = "ENDING", reason = "STOP_FALLING", deliveredU = 0.3)),
            cycle(4, channel = ChannelState(phase = "NONE", reason = "HARD_STOP", detail = "SAFETY_ZERO"))
        )
        val run = FuseGraphDataBuilder.build(layersSource(points), 0L, Long.MAX_VALUE).channelEpisodes.single()

        assertThat(run.startTime).isEqualTo(t0 + minute)
        assertThat(run.endTime).isEqualTo(t0 + 3 * minute)
        assertThat(run.factor!!).isWithin(1e-9).of(4.0)
        assertThat(run.deliveredU).isEqualTo(0.3)
        assertThat(run.end).isEqualTo(FuseChannelEnd.FALLING)
        assertThat(run.running).isFalse()
    }

    @Test
    fun `without ENDING the next cycle names the end, a cycle without the block is a restart`() {
        val zero = FuseGraphDataBuilder.channelEpisodes(
            listOf(cycle(0, channel = running()), cycle(1, channel = ChannelState(phase = "NONE", reason = "HARD_STOP", detail = "NIGHT_DEADBAND")))
        )
        assertThat(zero.single().end).isEqualTo(FuseChannelEnd.NIGHT_DEADBAND)

        val restart = FuseGraphDataBuilder.channelEpisodes(listOf(cycle(0, channel = running()), cycle(1)))
        assertThat(restart.single().end).isEqualTo(FuseChannelEnd.RESTART)
    }

    @Test
    fun `the newest run still runs, a gap splits runs and leaves the end open`() {
        val runs = FuseGraphDataBuilder.channelEpisodes(listOf(cycle(0, channel = running()), cycle(10, channel = running()), cycle(11, channel = running())))

        assertThat(runs).hasSize(2)
        assertThat(runs[0].end).isNull()
        assertThat(runs[0].running).isFalse()
        assertThat(runs[1].running).isTrue()
        assertThat(runs[1].end).isNull()
    }

    @Test
    fun `hard stop details map to their end reasons`() {
        assertThat(FuseGraphDataBuilder.channelEnd("HARD_STOP", "SAFETY_ZERO")).isEqualTo(FuseChannelEnd.SAFETY_ZERO)
        assertThat(FuseGraphDataBuilder.channelEnd("HARD_STOP", "MEASURED_LOW")).isEqualTo(FuseChannelEnd.LOW)
        assertThat(FuseGraphDataBuilder.channelEnd("HARD_STOP", "LEDGER_HOLD")).isEqualTo(FuseChannelEnd.OTHER_STOP)
        assertThat(FuseGraphDataBuilder.channelEnd("STOP_BELOW", null)).isEqualTo(FuseChannelEnd.BELOW_THRESHOLD)
        assertThat(FuseGraphDataBuilder.channelEnd(null, null)).isEqualTo(FuseChannelEnd.OTHER_STOP)
    }
}
