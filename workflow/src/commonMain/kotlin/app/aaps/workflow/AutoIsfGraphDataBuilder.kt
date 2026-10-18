package app.aaps.workflow

import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint

/**
 * Fork (autoISF): the series of the autoISF graphs (IOB_TH, FINAL, ACCE, BG, PP, DURA), as the fork built them in
 * PrepareIobAutosensGraphDataWorker from the stored values of the autoISF runs - only the drawing moved to the Compose
 * graphs. One point per run, at the time of the run.
 */
internal object AutoIsfGraphDataBuilder {

    /**
     * @param available autoISF is the active APS and this is not a client (the fork's `masterAutoIsf`); without it the
     *   series are not offered and nothing is read
     * @param values the stored values of the shown time range
     */
    fun build(available: Boolean, values: List<AIV>): AutoIsfGraphData {
        if (!available) return AutoIsfGraphData()
        val runs = values.filter { it.isValid }.sortedBy { it.timestamp }
        // A value that is not a number would break the axis of the whole graph
        fun series(value: (AIV) -> Double): List<GraphDataPoint> =
            runs.mapNotNull { run -> value(run).takeIf { it.isFinite() }?.let { GraphDataPoint(run.timestamp, it) } }
        return AutoIsfGraphData(
            available = true,
            iobTh = series { it.iobThEffective },
            finalIsf = series { it.finalIsf },
            acceIsf = series { it.acceIsf },
            bgIsf = series { it.bgIsf },
            ppIsf = series { it.ppIsf },
            duraIsf = series { it.duraIsf }
        )
    }
}
