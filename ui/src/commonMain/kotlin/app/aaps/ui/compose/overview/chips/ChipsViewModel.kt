package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Stable
@AssistedInject
class ChipsViewModel(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val sensitivityOverview: SensitivityOverview,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val rxBus: RxBus,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val iobCobTicker = flow {
        while (true) {
            emit(Unit)
            delay(150_000L) // 2.5 minutes
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = bolusIob.iob + basalIob.basaliob
        IobUiState(
            text = rh.gs(InterfacesStrings.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(CoreUiStrings.value_unavailable_short)
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText = rh.gs(CoreUiStrings.value_with_unit, cobText, rh.gs(CoreUiStrings.value_with_unit, constraintsProcessed.carbsReq.toString(), rh.gs(CoreUiStrings.required)))
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    // The IOB graph is published before the loop runs in the same calculation chain, so on its
    // own it would show the previous loop's ratio and variable ISF. Predictions are published
    // right after the loop ran on the master, and right after a device status came in on a
    // client, so they carry the fresh values on both sides.
    val sensitivityUiState: StateFlow<SensitivityUiState> = combine(iobCobTicker, cache.iobGraphFlow, cache.predictionsFlow) { _, _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
            isfStep = data.isfStep,
            dialogText = data.lines.joinToString("\n"),
            ratio = data.ratio,
            isEnabled = data.isEnabled,
            hasData = data.hasData
        )
    }

    /**
     * Fork (Toni): insulinReq of the last loop run, from RT.insulinReq. Fresh after every loop run (the predictions are
     * published right after it on the master), greyed out by the ticker once the run is old.
     *
     * Not with FUSE as the active APS (Toni 15.11.2026): there the demand is a tile of the FUSE chip
     * ([fuseChipUiState]), read from the FUSE decision itself - the RT of FUSE carries a not calculated insulinReq as
     * 0.0, which would read as "no insulin needed".
     */
    val insulinReqUiState: StateFlow<InsulinReqUiState> = combine(iobCobTicker, cache.predictionsFlow) { _, _ ->
        buildInsulinReqUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InsulinReqUiState()
    )

    internal fun buildInsulinReqUiState(): InsulinReqUiState {
        // A client runs no loop: no own value to show. FUSE shows its demand in its own chip.
        if (!config.APS || activePlugin.activeAPS is FuseOverviewSource) return InsulinReqUiState()
        val now = dateUtil.now()
        val lastRun = loop.lastRun ?: return InsulinReqUiState(visible = true, text = chipText(null))
        val units = (lastRun.request?.rawData() as? RT)?.insulinReq
        return InsulinReqUiState(
            visible = true,
            text = chipText(units),
            stale = now - lastRun.lastAPSRun > INSULIN_REQ_STALE_MS,
            details = listOf(
                rh.gs(CoreUiStrings.insulin_req_line, rh.gs(CoreUiStrings.insulin_req_title), valueText(units)),
                rh.gs(CoreUiStrings.insulin_req_time, dateUtil.timeString(lastRun.lastAPSRun))
            ).joinToString("\n")
        )
    }

    private fun valueText(units: Double?): String =
        units?.let { rh.gs(InterfacesStrings.format_insulin_units, it) } ?: rh.gs(CoreUiStrings.insulin_req_not_calculated)

    private fun chipText(units: Double?): String =
        rh.gs(CoreUiStrings.insulin_req_chip, units?.let { rh.gs(InterfacesStrings.format_insulin_units, it) } ?: NOT_CALCULATED)

    fun showInsulinReqInfo() {
        val state = buildInsulinReqUiState()
        if (!state.visible) return
        rxBus.send(EventShowDialog.Ok(title = rh.gs(CoreUiStrings.insulin_req_title), message = state.details))
    }

    // ---- Fork (Toni 15.11.2026): the FUSE chip ----------------------------------------------------------------

    private val fuseChipTexts = FuseChipTexts(rh, decimalFormatter)

    /** Counts up when the user opens, closes or pins: the chip is then built again from the stored layout. */
    private val fuseChipLayoutChanges = MutableStateFlow(0)

    /**
     * The FUSE chip under the sensitivity chip: the state of the last FUSE cycle, the pinned values, and the nine tiles.
     * Fresh after every loop run - the predictions and the FUSE graph data are published right after it -, and built
     * again by the ticker, which greys the chip out once the cycle is old. Display only.
     */
    val fuseChipUiState: StateFlow<FuseChipUiState> =
        combine(iobCobTicker, cache.predictionsFlow, cache.fuseGraphFlow, fuseChipLayoutChanges) { _, _, _, _ ->
            buildFuseChipUiState()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FuseChipUiState()
        )

    /** FUSE as the active APS on a device that runs the loop; `null` on a client and with another algorithm. */
    private fun fuseSource(): FuseOverviewSource? = if (config.APS) activePlugin.activeAPS as? FuseOverviewSource else null

    internal fun buildFuseChipUiState(): FuseChipUiState {
        val fuse = fuseSource() ?: return FuseChipUiState()
        val layout = fuse.fuseChipLayout()
        val chip = fuse.fuseChip()
        val now = dateUtil.now()
        val ageMs = chip?.let { (now - it.timestamp).coerceAtLeast(0L) }
        val stale = ageMs == null || ageMs > FUSE_CHIP_STALE_MS
        return FuseChipUiState(
            visible = true,
            cycleState = chip?.cycleState,
            stale = stale,
            ageText = if (ageMs != null && stale) rh.gs(CoreUiStrings.fuse_chip_age, (ageMs / 60_000L).toInt()) else "",
            expanded = layout.expanded,
            tiles = fuseChipTexts.tiles(chip, now),
            pinned = layout.pinned,
            pinCountText = rh.gs(CoreUiStrings.fuse_chip_pin_count, layout.pinned.size, FuseOverviewSource.ChipLayout.MAX_PINS),
            pluginClassName = fuse::class.simpleName.orEmpty()
        )
    }

    /** Opens or closes the tiles of the FUSE chip. The choice is stored. */
    fun toggleFuseChipExpanded() {
        val fuse = fuseSource() ?: return
        val layout = fuse.fuseChipLayout()
        fuse.fuseStoreChipLayout(layout.copy(expanded = !layout.expanded))
        fuseChipLayoutChanges.update { it + 1 }
    }

    /**
     * Pins a tile to the closed chip, or takes it off again. The choice is stored. With the most allowed already
     * pinned another tile is not taken: one has to come off first.
     */
    fun toggleFuseChipPin(tile: FuseOverviewSource.ChipTile) {
        val fuse = fuseSource() ?: return
        val layout = fuse.fuseChipLayout()
        val pinned = when {
            tile in layout.pinned                                        -> layout.pinned - tile
            layout.pinned.size >= FuseOverviewSource.ChipLayout.MAX_PINS -> return
            else                                                         -> layout.pinned + tile
        }
        fuse.fuseStoreChipLayout(layout.copy(pinned = pinned))
        fuseChipLayoutChanges.update { it + 1 }
    }

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val total = bolusIob.iob + basalIob.basaliob
            val message = listOf(
                CoreUiStrings.bolus_iob_label to bolusIob.iob,
                CoreUiStrings.treatments_wizard_basaliob_label to basalIob.basaliob,
                CoreUiStrings.iob to total
            ).joinToString("\n") { (label, insulin) ->
                rh.gs(InterfacesStrings.confirmation_line, rh.gs(label), rh.gs(InterfacesStrings.format_insulin_units, insulin))
            }
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }

    companion object {

        /** A loop run older than this is no longer the current one: the insulinReq chip is greyed out. */
        const val INSULIN_REQ_STALE_MS = 10L * 60_000L

        /** A FUSE cycle older than this is no longer the current one: the FUSE chip is greyed out and names its age. */
        const val FUSE_CHIP_STALE_MS = INSULIN_REQ_STALE_MS

        /** The chip's value when the run calculated none - a dash, never 0 (Toni). */
        const val NOT_CALCULATED = "–"
    }
}
