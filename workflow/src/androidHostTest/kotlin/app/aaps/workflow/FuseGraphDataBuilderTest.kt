package app.aaps.workflow

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.FuseOverviewSource.AccountStatus
import app.aaps.core.interfaces.overview.graph.DeviationType
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
}
