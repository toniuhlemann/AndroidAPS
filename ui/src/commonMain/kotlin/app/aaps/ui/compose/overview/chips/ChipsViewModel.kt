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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
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
                    cobText += " ${constraintsProcessed.carbsReq}${rh.gs(CoreUiStrings.required)}"
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
     * Fork (Toni): insulinReq of the last loop run. FUSE gives its own value ([FuseOverviewSource.fuseInsulinReq]):
     * its RT carries a not calculated insulinReq as 0.0, which would read as "no insulin needed". Every other
     * algorithm gives RT.insulinReq. Fresh after every loop run (the predictions are published right after it on the
     * master), greyed out by the ticker once the run is old.
     */
    val insulinReqUiState: StateFlow<InsulinReqUiState> = combine(iobCobTicker, cache.predictionsFlow) { _, _ ->
        buildInsulinReqUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InsulinReqUiState()
    )

    internal fun buildInsulinReqUiState(): InsulinReqUiState {
        // A client runs no loop: no own value to show.
        if (!config.APS) return InsulinReqUiState()
        val now = dateUtil.now()
        val fuse = activePlugin.activeAPS as? FuseOverviewSource
        if (fuse != null) {
            val req = fuse.fuseInsulinReq() ?: return InsulinReqUiState(visible = true, text = chipText(null))
            return InsulinReqUiState(
                visible = true,
                text = chipText(req.units),
                stale = now - req.timestamp > INSULIN_REQ_STALE_MS,
                details = listOf(
                    rh.gs(CoreUiStrings.insulin_req_line, rh.gs(CoreUiStrings.insulin_req_title), valueText(req.units)),
                    rh.gs(CoreUiStrings.insulin_req_line, rh.gs(CoreUiStrings.insulin_req_limit), req.grenze),
                    rh.gs(CoreUiStrings.insulin_req_line, rh.gs(CoreUiStrings.insulin_req_block), req.block),
                    rh.gs(CoreUiStrings.insulin_req_time, dateUtil.timeString(req.timestamp))
                ).joinToString("\n")
            )
        }
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

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val total = bolusIob.iob + basalIob.basaliob
            val message =
                rh.gs(CoreUiStrings.bolus_iob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, bolusIob.iob) + "\n" +
                    rh.gs(CoreUiStrings.treatments_wizard_basaliob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, basalIob.basaliob) + "\n" +
                    rh.gs(CoreUiStrings.iob) + ": " + rh.gs(InterfacesStrings.format_insulin_units, total)
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

        /** The chip's value when the run calculated none - a dash, never 0 (Toni). */
        const val NOT_CALCULATED = "–"
    }
}
