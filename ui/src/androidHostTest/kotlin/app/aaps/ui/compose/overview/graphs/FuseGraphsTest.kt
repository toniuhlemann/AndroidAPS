package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.FuseOverviewSource.AccountStatus
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.FuseStatusSegment
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Fork (FUSE): what F.DRV and F.GRD draw, and when the graph menu offers them. Synthetic numbers only. */
internal class FuseGraphsTest {

    private val minute = 60_000L
    private val start = 10_000_000L
    private val maxX = 360.0 // six hours, in minutes from start

    private fun at(min: Int) = start + min * minute

    @Test
    fun `the graph menu offers the FUSE series only while FUSE delivers them`() {
        assertThat(configurableSeries(fuseAvailable = true)).containsAtLeast(SeriesType.FUSE_DRIVE, SeriesType.FUSE_GUARD)
        assertThat(configurableSeries(fuseAvailable = false)).containsNoneOf(SeriesType.FUSE_DRIVE, SeriesType.FUSE_GUARD)
        assertThat(configurableSeries(fuseAvailable = false)).contains(SeriesType.COB)
    }

    @Test
    fun `F DRV draws the bars by type, the ramp edges over the whole width and reaches past the upper edge`() {
        val data = FuseGraphData(
            available = true,
            drive = listOf(DeviationDataPoint(at(5), 1.5, DeviationType.UAM), DeviationDataPoint(at(10), -0.5, DeviationType.NEGATIVE)),
            expectedDrive = listOf(listOf(GraphDataPoint(at(5), 1.0), GraphDataPoint(at(6), 1.1))),
            rampLow = 0.5,
            rampHigh = 2.0,
            mealMarkers = listOf(at(4), at(-30)) // the second is before the graph
        )
        val fuse = processFuse(data, SeriesType.FUSE_DRIVE, start, 0.0, maxX)!!

        assertThat(fuse.bars!!.series.keys).containsExactly(DeviationType.UAM, DeviationType.NEGATIVE)
        val ramps = fuse.lines.filter { it.style == FuseLineStyle.RAMP }
        assertThat(ramps.map { it.points.first().second }).containsExactly(0.5, 2.0)
        assertThat(ramps.all { it.points.first().first == 0.0 && it.points.last().first == maxX }).isTrue()
        assertThat(fuse.lines.count { it.style == FuseLineStyle.EXPECTED_DRIVE }).isEqualTo(1)
        assertThat(fuse.markerXs).containsExactly(4.0)
        // symmetric around 0 and at least 1.25 times the upper ramp edge (fork)
        val scale = fuse.scale(fuse.windowedY(null, null), SECONDARY_GRAPH_TICK_COUNT)
        assertThat(scale.max).isAtLeast(2.5)
        assertThat(scale.min).isEqualTo(-scale.max)
        assertThat(fuse.strip).isEmpty()
    }

    @Test
    fun `F GRD draws guard, tail and the lines with the meal, the strip, and at least 10 mg per dl each way`() {
        val data = FuseGraphData(
            available = true,
            guard = listOf(GraphDataPoint(at(1), 4.0), GraphDataPoint(at(2), -2.0)),
            tail = listOf(GraphDataPoint(at(1), 3.0)),
            guardWithMeal = listOf(listOf(GraphDataPoint(at(1), 1.0)), listOf(GraphDataPoint(at(20), 2.0))),
            accountStatus = listOf(
                FuseStatusSegment(at(1), at(3), AccountStatus.AKTIV),
                FuseStatusSegment(at(-60), at(-50), AccountStatus.GESPERRT) // before the graph: left out
            )
        )
        val fuse = processFuse(data, SeriesType.FUSE_GUARD, start, 0.0, maxX)!!

        assertThat(fuse.bars).isNull()
        assertThat(fuse.lines.map { it.style })
            .containsExactly(FuseLineStyle.GUARD, FuseLineStyle.TAIL, FuseLineStyle.GUARD_WITH_MEAL, FuseLineStyle.GUARD_WITH_MEAL).inOrder()
        assertThat(fuse.strip.map { it.status }).containsExactly(AccountStatus.AKTIV)
        val scale = fuse.scale(fuse.windowedY(null, null), SECONDARY_GRAPH_TICK_COUNT)
        assertThat(scale.max).isAtLeast(10.0)
        assertThat(scale.min).isEqualTo(-scale.max)
    }

    @Test
    fun `other graphs have no FUSE part`() {
        assertThat(processFuse(FuseGraphData(available = true), SeriesType.COB, start, 0.0, maxX)).isNull()
    }
}
