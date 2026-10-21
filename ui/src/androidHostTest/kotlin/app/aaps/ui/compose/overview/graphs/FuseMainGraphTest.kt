package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.FuseCycleInfo
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.FuseSmbSend
import app.aaps.core.interfaces.utils.DecimalFormatter
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Locale

/** Fork (FUSE): the layers of the main graph (Toni 21.10.2026). Synthetic numbers only. */
internal class FuseMainGraphTest {

    private val minute = 60_000L
    private val start = 10_000_000L

    private fun at(min: Int, sec: Int = 0) = start + min * minute + sec * 1_000L

    private fun smb(min: Int, sec: Int, amount: Double) = BolusGraphPoint(at(min, sec), amount, BolusType.SMB, true, "")

    @Test
    fun `the layers exist only when the data says so`() {
        assertThat(FuseMainLayers.of(FuseGraphData(available = true))).isNull()
        val cycles = listOf(FuseCycleInfo(at(0), CycleState.GUARD))
        assertThat(FuseMainLayers.of(FuseGraphData(available = true, mainLayers = true, cycles = cycles))!!.cycles).isEqualTo(cycles)
    }

    @Test
    fun `a BG point takes the state of the nearest cycle, at most three minutes away`() {
        val lookup = FuseStateLookup.of(listOf(FuseCycleInfo(at(10), CycleState.CHANNEL), FuseCycleInfo(at(0), CycleState.ZERO)), start)

        assertThat(lookup.at(0.0)).isEqualTo(CycleState.ZERO)
        assertThat(lookup.at(2.0)).isEqualTo(CycleState.ZERO)
        assertThat(lookup.at(8.0)).isEqualTo(CycleState.CHANNEL)
        assertThat(lookup.at(5.0)).isNull()
        assertThat(lookup.at(20.0)).isNull()
        assertThat(FuseStateLookup.of(emptyList(), start).at(0.0)).isNull()
        // Equal by content: Vico compares the extras to decide whether the model changed.
        assertThat(FuseStateLookup.of(listOf(FuseCycleInfo(at(0), CycleState.ZERO)), start))
            .isEqualTo(FuseStateLookup.of(listOf(FuseCycleInfo(at(0), CycleState.ZERO)), start))
    }

    @Test
    fun `each delivered SMB takes the source of the cycle that sent it, each send only once`() {
        val sends = listOf(
            FuseSmbSend(at(0), 0.30, SmbSource.EXPECTATION),
            FuseSmbSend(at(1), 0.10, SmbSource.NORMAL),
            FuseSmbSend(at(2), 0.20, SmbSource.OTHER)
        )
        val boluses = listOf(
            smb(0, 8, 0.30),
            smb(1, 9, 0.10),
            // AAPS cut it: the amount does not match, the time does
            smb(2, 7, 0.15),
            // no FUSE request nearby
            smb(30, 0, 0.50),
            // not an SMB
            BolusGraphPoint(at(31), 2.0, BolusType.NORMAL, true, "")
        )
        val sources = fuseSmbSources(boluses, sends)

        assertThat(sources.map { it.source }).containsExactly(SmbSource.EXPECTATION, SmbSource.NORMAL, SmbSource.OTHER, SmbSource.OTHER).inOrder()
        // Two deliveries, one send: only one gets its source
        val twice = fuseSmbSources(listOf(smb(0, 5, 0.3), smb(0, 30, 0.3)), listOf(FuseSmbSend(at(0), 0.3, SmbSource.EXPECTATION)))
        assertThat(twice.count { it.source == SmbSource.EXPECTATION }).isEqualTo(1)
    }

    @Test
    fun `a pause longer than six minutes starts a new run, the main source is the largest share`() {
        val runs = fuseSmbRuns(
            listOf(
                FuseSmbWithSource(at(0), 0.20, SmbSource.NORMAL),
                FuseSmbWithSource(at(3), 0.50, SmbSource.EXPECTATION),
                FuseSmbWithSource(at(9), 0.10, SmbSource.EXPECTATION),
                FuseSmbWithSource(at(16), 0.40, SmbSource.NORMAL)
            )
        )

        assertThat(runs).hasSize(2)
        assertThat(runs[0].sumU).isWithin(1e-9).of(0.80)
        assertThat(runs[0].startTime).isEqualTo(at(0))
        assertThat(runs[0].endTime).isEqualTo(at(9))
        assertThat(runs[0].mainSource).isEqualTo(SmbSource.EXPECTATION)
        assertThat(runs[1].mainSource).isEqualTo(SmbSource.NORMAL)
        assertThat(fuseSmbRuns(emptyList())).isEmpty()
    }

    @Test
    fun `triangles split by size like the plain graph and by source, only inside the window`() {
        val groups = fuseSmbGroups(
            listOf(
                FuseSmbWithSource(at(0), 0.1, SmbSource.NORMAL),
                FuseSmbWithSource(at(1), 0.5, SmbSource.EXPECTATION),
                FuseSmbWithSource(at(2), 1.0, SmbSource.EXPECTATION),
                FuseSmbWithSource(at(3), 1.0, SmbSource.NORMAL),
                FuseSmbWithSource(at(500), 3.0, SmbSource.NORMAL) // outside the window, does not stretch the sizes
            ),
            start, 0.0, 360.0
        )

        assertThat(groups.map { it.size to it.source }).containsExactly(
            0 to SmbSource.NORMAL, 1 to SmbSource.EXPECTATION, 2 to SmbSource.EXPECTATION, 2 to SmbSource.NORMAL
        ).inOrder()
        assertThat(groups.last().xs).containsExactly(3.0)
        // One single SMB is medium, like the plain graph
        assertThat(fuseSmbGroups(listOf(FuseSmbWithSource(at(0), 0.2, SmbSource.OTHER)), start, 0.0, 360.0).single().size).isEqualTo(1)
    }

    @Test
    fun `the basal strip shows only protective zero and channel, one cycle past the last`() {
        val segments = fuseBasalSegments(
            listOf(
                FuseCycleInfo(at(0), CycleState.ZERO), FuseCycleInfo(at(1), CycleState.ZERO),
                FuseCycleInfo(at(2), CycleState.GUARD),
                FuseCycleInfo(at(3), CycleState.CHANNEL),
                FuseCycleInfo(at(20), CycleState.CHANNEL) // after a gap: a new segment
            ),
            start
        )

        assertThat(segments.map { it.first to it.second }).containsExactly(0.0 to 2.0, 3.0 to 4.0, 20.0 to 21.0).inOrder()
        assertThat(segments.map { it.third }).containsExactly(
            fuseStateColor(CycleState.ZERO), fuseStateColor(CycleState.CHANNEL), fuseStateColor(CycleState.CHANNEL)
        ).inOrder()
    }

    @Test
    fun `the factor uses as few decimals as it needs`() {
        val formatter = mock<DecimalFormatter>()
        whenever(formatter.to0Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.0f", it.arguments[0] as Double) }
        whenever(formatter.to1Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.1f", it.arguments[0] as Double) }
        whenever(formatter.to2Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.2f", it.arguments[0] as Double) }

        assertThat(fuseFactorText(4.000000000000001, formatter)).isEqualTo("4")
        assertThat(fuseFactorText(1.5, formatter)).isEqualTo("1,5")
        assertThat(fuseFactorText(1.75, formatter)).isEqualTo("1,75")
    }

    @Test
    fun `the press-and-hold data carries the FUSE cycle and the source of the SMBs`() {
        val data = inspectDataAt(
            timestamp = at(10),
            bgReadings = emptyList(),
            iob = emptyList(),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = listOf(smb(10, 20, 0.3)),
            carbs = emptyList(),
            fuseCycles = listOf(FuseCycleInfo(at(9), CycleState.GUARD, "guardFloor=70.0", 0.4)),
            fuseSends = listOf(FuseSmbSend(at(10), 0.3, SmbSource.EXPECTATION))
        )

        assertThat(data.fuse?.state).isEqualTo(CycleState.GUARD)
        assertThat(data.smbSources[at(10, 20)]).isEqualTo(SmbSource.EXPECTATION)
        // Without FUSE nothing changes
        val plain = inspectDataAt(at(10), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(smb(10, 20, 0.3)), emptyList())
        assertThat(plain.fuse).isNull()
        assertThat(plain.smbSources).isEmpty()
    }
}
