package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Fork (autoISF): when the graph menu offers the autoISF series and how their axes are built. Synthetic numbers only. */
internal class AutoIsfGraphsTest {

    @Test
    fun `the six autoISF series are offered only while autoISF runs`() {
        assertThat(configurableSeries(fuseAvailable = false, autoIsfAvailable = true)).containsAtLeastElementsIn(AutoIsfGraphData.SERIES)
        assertThat(configurableSeries(fuseAvailable = false, autoIsfAvailable = false)).containsNoneIn(AutoIsfGraphData.SERIES)
        // independent of FUSE
        assertThat(configurableSeries(fuseAvailable = true, autoIsfAvailable = false)).contains(SeriesType.FUSE_DRIVE)
        assertThat(configurableSeries(fuseAvailable = false, autoIsfAvailable = true)).doesNotContain(SeriesType.FUSE_DRIVE)
    }

    @Test
    fun `each autoISF series reads its own points`() {
        val data = AutoIsfGraphData(
            available = true,
            iobTh = listOf(GraphDataPoint(1L, 2.0)),
            finalIsf = listOf(GraphDataPoint(1L, 1.1)),
            acceIsf = listOf(GraphDataPoint(1L, 1.2)),
            bgIsf = listOf(GraphDataPoint(1L, 1.3)),
            ppIsf = listOf(GraphDataPoint(1L, 1.4)),
            duraIsf = listOf(GraphDataPoint(1L, 1.5))
        )
        assertThat(AutoIsfGraphData.SERIES.map { data.points(it).single().value }).containsExactly(2.0, 1.1, 1.2, 1.3, 1.4, 1.5)
        assertThat(data.points(SeriesType.COB)).isEmpty()
    }

    @Test
    fun `the factors are drawn around 1 and iobTH from 0`() {
        for (factor in AutoIsfGraphData.FACTOR_SERIES) assertThat(seriesPivot(factor)).isEqualTo(AUTO_ISF_FACTOR_PIVOT)
        assertThat(seriesPivot(SeriesType.IOB_TH)).isNull()
        assertThat(SeriesType.IOB_TH in ZERO_FLOOR_SERIES_TYPES).isTrue()
        // unchanged for the upstream pivot series
        assertThat(seriesPivot(SeriesType.SENSITIVITY)).isEqualTo(100.0)
        assertThat(seriesPivot(SeriesType.DEV_SLOPE)).isEqualTo(0.0)
        assertThat(seriesPivot(SeriesType.COB)).isNull()
    }

    @Test
    fun `a flat factor gets a calm axis around 1`() {
        val scale = niceScaleAroundPivot(1.0, 1.0, AUTO_ISF_FACTOR_PIVOT, SENS_PIVOT_TICK_COUNT, AUTO_ISF_FACTOR_MIN_DEVIATION)
        assertThat(scale.min).isWithin(1e-9).of(1.0 - AUTO_ISF_FACTOR_MIN_DEVIATION)
        assertThat(scale.max).isWithin(1e-9).of(1.0 + AUTO_ISF_FACTOR_MIN_DEVIATION)
    }
}
