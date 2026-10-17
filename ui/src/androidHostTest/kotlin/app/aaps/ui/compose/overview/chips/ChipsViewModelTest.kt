package app.aaps.ui.compose.overview.chips

import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.CobGraphData
import app.aaps.core.interfaces.overview.graph.IobGraphData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.ui.CoreUiStrings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChipsViewModelTest {

    @Mock private lateinit var cache: OverviewDataCache
    @Mock private lateinit var iobCobCalculator: IobCobCalculator
    @Mock private lateinit var loop: Loop
    @Mock private lateinit var config: Config
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var sensitivityOverview: SensitivityOverview
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var dateUtil: DateUtil

    private lateinit var sut: ChipsViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // The stateIn/shareIn coroutines are deferred by StandardTestDispatcher, but the cold combine chains
        // are built at construction, so the cache graph flows they reference must be non-null.
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(cache.iobGraphFlow).thenReturn(MutableStateFlow(IobGraphData(emptyList(), emptyList())))
        whenever(cache.cobGraphFlow).thenReturn(MutableStateFlow(CobGraphData(emptyList(), emptyList())))
        whenever(cache.predictionsFlow).thenReturn(MutableStateFlow(emptyList()))
        sut = ChipsViewModel(cache, iobCobCalculator, loop, config, persistenceLayer, sensitivityOverview, rh, decimalFormatter, rxBus, activePlugin, dateUtil)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState exposes empty initial values`() {
        assertThat(sut.iobUiState.value.iobTotal).isEqualTo(0.0)
        assertThat(sut.iobUiState.value.text).isEmpty()
        assertThat(sut.cobUiState.value.text).isEmpty()
        assertThat(sut.cobUiState.value.carbsReq).isEqualTo(0)
        assertThat(sut.sensitivityUiState.value.ratio).isEqualTo(1.0)
        assertThat(sut.sensitivityUiState.value.isEnabled).isTrue()
        assertThat(sut.sensitivityUiState.value.hasData).isFalse()
    }

    // ---- Fork (Toni): the insulinReq chip ---------------------------------------------------------------------

    private fun stubTexts() {
        whenever(rh.gs(eq(CoreUiStrings.insulin_req_chip), any())).thenAnswer { "Req " + it.arguments[1] }
        whenever(rh.gs(eq(InterfacesStrings.format_insulin_units), any())).thenAnswer { "${it.arguments[1]} U" }
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(config.APS).thenReturn(true)
    }

    private fun fuseActive(req: FuseOverviewSource.InsulinReq?) {
        val fuse = mock<APS>(extraInterfaces = arrayOf(FuseOverviewSource::class))
        whenever((fuse as FuseOverviewSource).fuseInsulinReq()).thenReturn(req)
        whenever(activePlugin.activeAPS).thenReturn(fuse)
    }

    @Test
    fun `fork - FUSE shows its own insulinReq, fresh`() {
        stubTexts()
        fuseActive(FuseOverviewSource.InsulinReq(timestamp = NOW - 60_000L, units = 0.35, grenze = "guard", block = "NONE"))

        val state = sut.buildInsulinReqUiState()

        assertThat(state.visible).isTrue()
        assertThat(state.text).isEqualTo("Req 0.35 U")
        assertThat(state.stale).isFalse()
    }

    /** FuseRtBuilder writes a not calculated insulinReq as 0.0 into the RT - the chip must show a dash, never 0. */
    @Test
    fun `fork - a not calculated FUSE insulinReq is a dash, not 0`() {
        stubTexts()
        fuseActive(FuseOverviewSource.InsulinReq(timestamp = NOW - 60_000L, units = null, grenze = "guard", block = "LOW"))

        val state = sut.buildInsulinReqUiState()

        assertThat(state.text).isEqualTo("Req " + ChipsViewModel.NOT_CALCULATED)
        assertThat(state.text).doesNotContain("0")
    }

    @Test
    fun `fork - an old run greys the chip out`() {
        stubTexts()
        fuseActive(FuseOverviewSource.InsulinReq(timestamp = NOW - ChipsViewModel.INSULIN_REQ_STALE_MS - 1, units = 0.2, grenze = "guard", block = "NONE"))

        assertThat(sut.buildInsulinReqUiState().stale).isTrue()
    }

    @Test
    fun `fork - FUSE before its first cycle shows a dash, greyed out`() {
        stubTexts()
        fuseActive(null)

        val state = sut.buildInsulinReqUiState()

        assertThat(state.text).isEqualTo("Req " + ChipsViewModel.NOT_CALCULATED)
        assertThat(state.stale).isTrue()
    }

    @Test
    fun `fork - another algorithm shows RT insulinReq of the last run`() {
        stubTexts()
        val apsResult = mock<APSResult>()
        whenever(apsResult.rawData()).thenReturn(RT(algorithm = APSResult.Algorithm.SMB, runningDynamicIsf = false, timestamp = NOW, insulinReq = 0.8))
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply { request = apsResult; lastAPSRun = NOW - 60_000L })

        val state = sut.buildInsulinReqUiState()

        assertThat(state.text).isEqualTo("Req 0.8 U")
        assertThat(state.stale).isFalse()
    }

    @Test
    fun `fork - a client shows no insulinReq chip`() {
        stubTexts()
        whenever(config.APS).thenReturn(false)

        assertThat(sut.buildInsulinReqUiState().visible).isFalse()
    }

    private companion object {

        const val NOW = 1_800_000_000_000L
    }
}
