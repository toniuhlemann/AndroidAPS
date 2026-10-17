package app.aaps.workflow

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.FuseStatusSegment
import app.aaps.core.interfaces.overview.graph.GraphDataPoint

/**
 * Fork (FUSE): the series of the two FUSE graphs and the meal markers, as the fork built them in
 * PrepareIobAutosensGraphDataWorker - only the drawing moved to the Compose graphs.
 *
 * F.DRV: the disturbance r as 5 minute bars (the mean in each 5 minute slot, drawn at the newest point of the slot;
 * negative red, positive in the meal window yellow, else green), the meal effect the account expects (one line per
 * run of points), the ramp edges as reference lines. F.GRD: the guard margin, the tail margin, both again WITH the
 * announced meal (one line per run), and the account state as a strip at the top.
 *
 * The source is the process ring of the FUSE plugin, so after an app start the graphs begin empty and fill in the
 * 1 minute rhythm. Values stay in mg/dl and mg/dl/min, as in the fork.
 */
internal object FuseGraphDataBuilder {

    /** Width of a drive bar. */
    private const val BAR_MS = 5 * 60_000L

    /** A gap longer than this starts a new line or a new strip segment. */
    private const val GAP_MS = 3 * 60_000L

    /** A strip segment lasts one cycle past its last point. */
    private const val CYCLE_MS = 60_000L

    fun build(source: FuseOverviewSource?, fromTime: Long, endTime: Long): FuseGraphData {
        source ?: return FuseGraphData()
        val points = source.fuseGraphPoints(fromTime, endTime).sortedBy { it.timestamp }
        // The points come sorted, so groupBy keeps the slots in time order (no sorted map in common code).
        val drive = points.filter { it.driveMgdlPerMin != null }
            .groupBy { it.timestamp / BAR_MS }
            .values
            .mapNotNull { slot ->
                val r = slot.mapNotNull { it.driveMgdlPerMin }.average()
                if (!r.isFinite()) null
                else DeviationDataPoint(
                    timestamp = slot.maxOf { it.timestamp },
                    value = r,
                    deviationType = when {
                        r < 0.0                    -> DeviationType.NEGATIVE
                        slot.any { it.mealWindow } -> DeviationType.UAM
                        else                       -> DeviationType.POSITIVE
                    }
                )
            }
        val (rampLow, rampHigh) = source.fuseRampLevels()
        return FuseGraphData(
            available = true,
            drive = drive,
            expectedDrive = runs(points) { it.expectedMealDriveMgdlPerMin },
            rampLow = rampLow,
            rampHigh = rampHigh,
            guard = points.mapNotNull { p -> p.guardMarginMgdl?.takeIf { it.isFinite() }?.let { GraphDataPoint(p.timestamp, it) } },
            tail = points.mapNotNull { p -> p.tailMarginMgdl?.takeIf { it.isFinite() }?.let { GraphDataPoint(p.timestamp, it) } },
            guardWithMeal = runs(points) { it.guardWithMealMarginMgdl },
            tailWithMeal = runs(points) { it.tailWithMealMarginMgdl },
            accountStatus = statusStrip(points),
            mealMarkers = source.fuseMealMarkerTimes(fromTime, endTime).sorted()
        )
    }

    /**
     * Lines that exist only in the account window: one line per connected run of points. One single line would join
     * the meals across the time between them.
     */
    internal fun runs(points: List<FuseOverviewSource.Point>, value: (FuseOverviewSource.Point) -> Double?): List<List<GraphDataPoint>> {
        val all = ArrayList<MutableList<GraphDataPoint>>()
        var lastTs = Long.MIN_VALUE
        for (p in points) {
            val v = value(p)?.takeIf { it.isFinite() } ?: continue
            if (all.isEmpty() || p.timestamp - lastTs > GAP_MS) all.add(ArrayList())
            all.last().add(GraphDataPoint(p.timestamp, v))
            lastTs = p.timestamp
        }
        return all
    }

    /** Following cycles with the same account state form one segment; a gap or a change starts the next. */
    internal fun statusStrip(points: List<FuseOverviewSource.Point>): List<FuseStatusSegment> {
        val strip = ArrayList<FuseStatusSegment>()
        var status: FuseOverviewSource.AccountStatus? = null
        var start = 0L
        var end = 0L
        fun close() {
            status?.let { strip.add(FuseStatusSegment(start, end, it)) }
        }
        for (p in points) {
            if (p.accountStatus != status || p.timestamp - end > GAP_MS) {
                close()
                status = p.accountStatus
                start = p.timestamp
            }
            end = p.timestamp + CYCLE_MS
        }
        close()
        return strip
    }
}
