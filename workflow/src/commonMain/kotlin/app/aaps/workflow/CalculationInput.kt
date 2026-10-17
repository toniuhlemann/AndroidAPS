package app.aaps.workflow

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter

/**
 * The input of one calculation chain.
 *
 * Top level rather than nested in a runner: they are plain data holders read by [WorkflowChainData],
 * where each phase looks up its slot, so they belong beside it.
 */
class PrepareGraphData(
    val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
    val overviewData: OverviewData,
    val cache: OverviewDataCache,
    val signals: CalculationSignalsEmitter,
    val reason: String,
    val end: Long,
    val bgDataReload: Boolean,
    val limitDataToOldestAvailable: Boolean,
    val triggeredByNewBG: Boolean,
    val emitFinalProgress: Boolean,
    /**
     * Fork patch 0033: the graph phases (bucketed data, BG readings, IOB/autosens graph data) run in the
     * post phase, after the loop, instead of before it. Set for MAIN: only the BG load and IOB/COB feed
     * the loop, the graph phases only prepare the overview. HISTORY has no loop and keeps them here.
     */
    val graphsAfterLoop: Boolean = false
)

/** The input of the phase that runs after [PrepareGraphData]. */
class PostCalculationData(
    val overviewData: OverviewData,
    val cache: OverviewDataCache,
    val signals: CalculationSignalsEmitter,
    val runLoopAndWidgetPhase: Boolean
)
