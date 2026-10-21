package app.aaps.workflow

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.FuseChannelEnd
import app.aaps.core.interfaces.overview.graph.FuseChannelEpisode
import app.aaps.core.interfaces.overview.graph.FuseCycleInfo
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.FuseSmbSend
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

    /** A gap longer than this ends a run of the slow correction channel. */
    private const val CHANNEL_GAP_MS = 5 * 60_000L

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
        // Main graph layers (Toni 21.10.2026): one linear pass each over at most 1500 ring points. This runs in the
        // calculation chain after the loop, so it must stay this cheap - the drawing data is built in the graphs.
        val layers = source.fuseMainGraphLayers()
        val cycles = if (layers) cycles(points) else emptyList()
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
            mealMarkers = source.fuseMealMarkerTimes(fromTime, endTime).sorted(),
            mainLayers = cycles.isNotEmpty(),
            cycles = cycles,
            channelEpisodes = if (layers) channelEpisodes(points) else emptyList(),
            smbSends = if (layers) smbSends(points) else emptyList()
        )
    }

    /** One entry per cycle that carries a state. */
    internal fun cycles(points: List<FuseOverviewSource.Point>): List<FuseCycleInfo> = points.mapNotNull { p ->
        val state = p.cycleState ?: return@mapNotNull null
        FuseCycleInfo(
            timestamp = p.timestamp,
            state = state,
            bindingLimit = p.bindingLimit,
            insulinReqU = p.insulinReqU?.takeIf { it.isFinite() },
            channelFactor = p.channel?.takeIf { it.active }?.factor
        )
    }

    /** The cycles that handed an SMB to AAPS. */
    internal fun smbSends(points: List<FuseOverviewSource.Point>): List<FuseSmbSend> = points.mapNotNull { p ->
        val units = p.smbU?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
        FuseSmbSend(p.timestamp, units, p.smbSource ?: FuseOverviewSource.SmbSource.OTHER)
    }

    /**
     * Following channel cycles form one run; a gap longer than [CHANNEL_GAP_MS] starts the next. The end reason is in
     * the first cycle in which the channel no longer wants (phase ENDING), else in the cycle after the run. A cycle
     * without the channel block right after a run is a restart: FUSE writes the block while anything of it runs.
     */
    internal fun channelEpisodes(points: List<FuseOverviewSource.Point>): List<FuseChannelEpisode> {
        val episodes = ArrayList<FuseChannelEpisode>()
        var i = 0
        while (i < points.size) {
            if (points[i].channel?.active != true) {
                i++
                continue
            }
            var j = i
            while (j + 1 < points.size && points[j + 1].channel?.active == true && points[j + 1].timestamp - points[j].timestamp <= CHANNEL_GAP_MS) j++
            val last = points[j]
            val stop = (i..j).map { points[it] }.firstOrNull { it.channel?.phase == "ENDING" }
            val next = points.getOrNull(j + 1)
            val end = when {
                stop != null                                    -> channelEnd(stop.channel?.reason, stop.channel?.detail)
                next == null                                    -> null
                next.timestamp - last.timestamp > CHANNEL_GAP_MS -> null
                next.channel == null                            -> FuseChannelEnd.RESTART
                else                                            -> channelEnd(next.channel?.reason, next.channel?.detail)
            }
            var factor: Double? = null
            var delivered: Double? = null
            for (k in i..j) {
                points[k].channel?.factor?.let { f -> factor = maxOf(factor ?: f, f) }
                points[k].channel?.deliveredU?.takeIf { it.isFinite() }?.let { d -> delivered = maxOf(delivered ?: d, d) }
            }
            episodes.add(
                FuseChannelEpisode(
                    startTime = points[i].timestamp,
                    endTime = last.timestamp,
                    factor = factor,
                    deliveredU = delivered,
                    end = end,
                    running = stop == null && next == null
                )
            )
            i = j + 1
        }
        return episodes
    }

    /** The end reason from the channel's own reason and detail (`slowChannel.reason`, `.detail`). */
    internal fun channelEnd(reason: String?, detail: String?): FuseChannelEnd = when (reason) {
        "STOP_FALLING" -> FuseChannelEnd.FALLING
        "STOP_BELOW"   -> FuseChannelEnd.BELOW_THRESHOLD
        "NO_DEMAND"    -> FuseChannelEnd.NO_DEMAND
        "MAX_IOB"      -> FuseChannelEnd.MAX_IOB
        "HARD_STOP"    -> when (detail) {
            "SAFETY_ZERO"      -> FuseChannelEnd.SAFETY_ZERO
            "DESCENT_RISK"     -> FuseChannelEnd.DESCENT_RISK
            "MEASURED_LOW"     -> FuseChannelEnd.LOW
            "SIGNAL_NOT_READY" -> FuseChannelEnd.SIGNAL
            "REBOUND_DEADBAND" -> FuseChannelEnd.REBOUND_DEADBAND
            "NIGHT_DEADBAND"   -> FuseChannelEnd.NIGHT_DEADBAND
            else               -> FuseChannelEnd.OTHER_STOP
        }

        else           -> FuseChannelEnd.OTHER_STOP
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
