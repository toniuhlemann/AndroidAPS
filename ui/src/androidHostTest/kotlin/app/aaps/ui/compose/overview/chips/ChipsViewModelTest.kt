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
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipLayout
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.CobGraphData
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.IobGraphData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.ui.CoreUiStrings
import app.aaps.shared.tests.generatedTextResolver
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Locale

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
        whenever(cache.fuseGraphFlow).thenReturn(MutableStateFlow(FuseGraphData()))
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
        assertThat(sut.fuseChipUiState.value.visible).isFalse()
    }

    // ---- Fork (Toni): the insulinReq chip ---------------------------------------------------------------------

    private fun stubTexts() {
        whenever(rh.gs(eq(CoreUiStrings.insulin_req_chip), any())).thenAnswer { "Req " + it.arguments[1] }
        whenever(rh.gs(eq(InterfacesStrings.format_insulin_units), any())).thenAnswer { "${it.arguments[1]} U" }
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(config.APS).thenReturn(true)
    }

    /** FUSE as the active APS: a mock that is an APS and the FUSE source at once, as the plugin is. */
    private fun fuseActive(chip: FuseOverviewSource.Chip? = null, layout: ChipLayout = ChipLayout()): FuseOverviewSource {
        val fuse = mock<APS>(extraInterfaces = arrayOf(FuseOverviewSource::class))
        val source = fuse as FuseOverviewSource
        whenever(source.fuseChip()).thenReturn(chip)
        whenever(source.fuseChipLayout()).thenReturn(layout)
        whenever(activePlugin.activeAPS).thenReturn(fuse)
        return source
    }

    private fun lastRun(insulinReq: Double?, ageMs: Long = 60_000L) {
        val apsResult = mock<APSResult>()
        whenever(apsResult.rawData()).thenReturn(RT(algorithm = APSResult.Algorithm.SMB, runningDynamicIsf = false, timestamp = NOW, insulinReq = insulinReq))
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply { request = apsResult; lastAPSRun = NOW - ageMs })
    }

    @Test
    fun `fork - an algorithm shows RT insulinReq of the last run`() {
        stubTexts()
        lastRun(insulinReq = 0.8)

        val state = sut.buildInsulinReqUiState()

        assertThat(state.visible).isTrue()
        assertThat(state.text).isEqualTo("Req 0.8 U")
        assertThat(state.stale).isFalse()
    }

    @Test
    fun `fork - a not calculated insulinReq is a dash, not 0`() {
        stubTexts()
        lastRun(insulinReq = null)

        val state = sut.buildInsulinReqUiState()

        assertThat(state.text).isEqualTo("Req " + ChipsViewModel.NOT_CALCULATED)
        assertThat(state.text).doesNotContain("0")
    }

    @Test
    fun `fork - an old run greys the chip out`() {
        stubTexts()
        lastRun(insulinReq = 0.2, ageMs = ChipsViewModel.INSULIN_REQ_STALE_MS + 1)

        assertThat(sut.buildInsulinReqUiState().stale).isTrue()
    }

    @Test
    fun `fork - before the first run the chip shows a dash, greyed out`() {
        stubTexts()

        val state = sut.buildInsulinReqUiState()

        assertThat(state.visible).isTrue()
        assertThat(state.text).isEqualTo("Req " + ChipsViewModel.NOT_CALCULATED)
        assertThat(state.stale).isTrue()
    }

    @Test
    fun `fork - a client shows no insulinReq chip`() {
        stubTexts()
        whenever(config.APS).thenReturn(false)

        assertThat(sut.buildInsulinReqUiState().visible).isFalse()
    }

    /** With FUSE the demand is a tile of the FUSE chip: the chip beside IOB and COB is gone (Toni 15.11.2026). */
    @Test
    fun `fork - with FUSE the insulinReq chip is left out`() {
        stubTexts()
        fuseActive()
        lastRun(insulinReq = 0.8)

        assertThat(sut.buildInsulinReqUiState().visible).isFalse()
    }

    // ---- Fork (Toni 15.11.2026): the FUSE chip ----------------------------------------------------------------

    /** A view model with the real texts, for the FUSE chip: its tiles are made of many strings. */
    private fun fuseSut(): ChipsViewModel {
        whenever(decimalFormatter.to0Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.0f", it.arguments[0] as Double) }
        whenever(decimalFormatter.to1Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.1f", it.arguments[0] as Double) }
        whenever(decimalFormatter.to2Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.2f", it.arguments[0] as Double) }
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(config.APS).thenReturn(true)
        return ChipsViewModel(
            cache, iobCobCalculator, loop, config, persistenceLayer, sensitivityOverview, generatedTextResolver(), decimalFormatter, rxBus, activePlugin, dateUtil
        )
    }

    private fun chip(ageMs: Long = 60_000L, cycleState: CycleState = CycleState.FREE) = FuseOverviewSource.Chip(
        timestamp = NOW - ageMs,
        cycleState = cycleState,
        driveMgdlPerMin = 1.2,
        expectedMealDriveMgdlPerMin = null,
        insulinReqU = 0.8,
        smbRatio = 0.25,
        ratioMode = FuseOverviewSource.RatioMode.RAMP,
        rampPercent = 50,
        smb = null,
        smbWantedU = null,
        lastSmb = null,
        limit = null,
        roomU = 2.3,
        channel = null,
        guardMarginMgdl = 18.0,
        guardWithMealMarginMgdl = null,
        tailMarginMgdl = 25.0,
        tailWithMealMarginMgdl = null,
        meal = null
    )

    @Test
    fun `FUSE chip - another algorithm has no FUSE chip`() {
        val vm = fuseSut()

        assertThat(vm.buildFuseChipUiState().visible).isFalse()
    }

    @Test
    fun `FUSE chip - a client has no FUSE chip`() {
        val vm = fuseSut()
        fuseActive(chip())
        whenever(config.APS).thenReturn(false)

        assertThat(vm.buildFuseChipUiState().visible).isFalse()
    }

    @Test
    fun `FUSE chip - a fresh cycle shows its state, the tiles and the pinned values from the start`() {
        val vm = fuseSut()
        fuseActive(chip(cycleState = CycleState.GUARD))

        val state = vm.buildFuseChipUiState()

        assertThat(state.visible).isTrue()
        assertThat(state.cycleState).isEqualTo(CycleState.GUARD)
        assertThat(state.stale).isFalse()
        assertThat(state.ageText).isEmpty()
        assertThat(state.expanded).isFalse()
        assertThat(state.tiles.map { it.tile }).containsExactlyElementsIn(ChipTile.entries).inOrder()
        // Pinned from the start: demand, guard and meal - in that order on the closed chip.
        assertThat(state.pinned).containsExactly(ChipTile.DEMAND, ChipTile.GUARD, ChipTile.MEAL).inOrder()
        assertThat(state.pinnedTiles.map { it.pinLabel to it.pinValue })
            .containsExactly("Bedarf" to "0,80 U", "Guard" to "+18", "Konto" to FuseChipTexts.NONE).inOrder()
        assertThat(state.pinCountText).isEqualTo("3 von 4 angeheftet")
        assertThat(state.pluginClassName).isNotEmpty()
    }

    @Test
    fun `FUSE chip - before the first cycle it is greyed out without a state and without an age`() {
        val vm = fuseSut()
        fuseActive(chip = null)

        val state = vm.buildFuseChipUiState()

        assertThat(state.visible).isTrue()
        assertThat(state.cycleState).isNull()
        assertThat(state.stale).isTrue()
        assertThat(state.ageText).isEmpty()
        assertThat(state.tiles.map { it.value }.toSet()).containsExactly(FuseChipTexts.NONE)
    }

    @Test
    fun `FUSE chip - an old cycle is greyed out and names its age`() {
        val vm = fuseSut()
        fuseActive(chip(ageMs = 14 * 60_000L + 20_000L))

        val state = vm.buildFuseChipUiState()

        assertThat(state.stale).isTrue()
        assertThat(state.ageText).isEqualTo("vor 14 min")
        // The values of the old cycle stay readable: greyed out, not hidden.
        assertThat(state.tiles.single { it.tile == ChipTile.DEMAND }.value).isEqualTo("0,80 U")
    }

    @Test
    fun `FUSE chip - a cycle at the edge of old is still the current one`() {
        val vm = fuseSut()
        fuseActive(chip(ageMs = ChipsViewModel.FUSE_CHIP_STALE_MS))

        assertThat(vm.buildFuseChipUiState().stale).isFalse()
    }

    @Test
    fun `FUSE chip - a tap opens and closes it and stores the choice`() {
        val vm = fuseSut()
        val closed = fuseActive(chip(), ChipLayout(pinned = listOf(ChipTile.SMB), expanded = false))
        vm.toggleFuseChipExpanded()
        verify(closed).fuseStoreChipLayout(ChipLayout(pinned = listOf(ChipTile.SMB), expanded = true))

        val open = fuseActive(chip(), ChipLayout(pinned = listOf(ChipTile.SMB), expanded = true))
        assertThat(vm.buildFuseChipUiState().expanded).isTrue()
        vm.toggleFuseChipExpanded()
        verify(open).fuseStoreChipLayout(ChipLayout(pinned = listOf(ChipTile.SMB), expanded = false))
    }

    @Test
    fun `FUSE chip - a tap on a tile pins it at the end, a second tap takes it off`() {
        val vm = fuseSut()
        val source = fuseActive(chip(), ChipLayout(pinned = listOf(ChipTile.DEMAND, ChipTile.GUARD), expanded = true))

        vm.toggleFuseChipPin(ChipTile.CHANNEL)
        verify(source).fuseStoreChipLayout(ChipLayout(pinned = listOf(ChipTile.DEMAND, ChipTile.GUARD, ChipTile.CHANNEL), expanded = true))

        vm.toggleFuseChipPin(ChipTile.DEMAND)
        verify(source).fuseStoreChipLayout(ChipLayout(pinned = listOf(ChipTile.GUARD), expanded = true))
    }

    @Test
    fun `FUSE chip - with four pinned a fifth is not taken, but one can come off`() {
        val vm = fuseSut()
        val full = listOf(ChipTile.DEMAND, ChipTile.GUARD, ChipTile.MEAL, ChipTile.SMB)
        val source = fuseActive(chip(), ChipLayout(pinned = full, expanded = true))
        assertThat(full).hasSize(ChipLayout.MAX_PINS)

        vm.toggleFuseChipPin(ChipTile.TAIL)
        verify(source, never()).fuseStoreChipLayout(any())

        vm.toggleFuseChipPin(ChipTile.MEAL)
        verify(source).fuseStoreChipLayout(ChipLayout(pinned = listOf(ChipTile.DEMAND, ChipTile.GUARD, ChipTile.SMB), expanded = true))
    }

    @Test
    fun `FUSE chip - without FUSE a tap stores nothing`() {
        val vm = fuseSut()

        vm.toggleFuseChipExpanded()
        vm.toggleFuseChipPin(ChipTile.SMB)

        assertThat(vm.buildFuseChipUiState().visible).isFalse()
    }

    private companion object {

        const val NOW = 1_800_000_000_000L
    }
}
