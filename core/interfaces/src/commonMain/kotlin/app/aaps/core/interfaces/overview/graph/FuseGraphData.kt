package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.overview.FuseOverviewSource

/**
 * Fork (FUSE): the two FUSE graphs (F.DRV, F.GRD) and the meal markers, built from the process ring of the FUSE
 * plugin. Values in mg/dl and mg/dl/min, as in the fork. [available] is false without FUSE as the active APS: the
 * two series are then not offered in the graph menu.
 *
 * @property drive the disturbance r as 5 minute bars; the type colors them like the deviations (NEGATIVE red,
 *   UAM = positive in the meal window yellow, POSITIVE green)
 * @property expectedDrive the meal effect the account expects, one line per run of points
 * @property rampLow lower ramp edge [mg/dl/min], a reference line in F.DRV
 * @property rampHigh upper ramp edge [mg/dl/min], a reference line in F.DRV; the axis always reaches 1.25 times it
 * @property guard guard margin: above 0 open, below 0 blocked; one line per run of points, so a gap in the data stays
 *   a gap (Toni 21.10.2026: one line joined the cycles across a CGM gap)
 * @property tail tail margin, same meaning, one line per run
 * @property guardWithMeal guard margin WITH the announced meal, one line per run
 * @property tailWithMeal tail margin WITH the announced meal, one line per run
 * @property accountStatus the account state as segments, drawn as a strip at the top of F.GRD
 * @property mealMarkers times of the meal marker presses, vertical lines in the BG graph and in both FUSE graphs
 * @property mainLayers the FUSE layers of the main graph are on and there is data for them (Toni 21.10.2026)
 * @property cycles one entry per cycle: state, binding limit, insulinReq - BG point colours and the press-and-hold pill
 * @property channelEpisodes the slow correction channel, one entry per run, drawn as bands in the BG graph
 * @property smbSends the cycles that handed an SMB to AAPS, with the source - colours the SMB triangles
 */
data class FuseGraphData(
    val available: Boolean = false,
    val drive: List<DeviationDataPoint> = emptyList(),
    val expectedDrive: List<List<GraphDataPoint>> = emptyList(),
    val rampLow: Double? = null,
    val rampHigh: Double? = null,
    val guard: List<List<GraphDataPoint>> = emptyList(),
    val tail: List<List<GraphDataPoint>> = emptyList(),
    val guardWithMeal: List<List<GraphDataPoint>> = emptyList(),
    val tailWithMeal: List<List<GraphDataPoint>> = emptyList(),
    val accountStatus: List<FuseStatusSegment> = emptyList(),
    val mealMarkers: List<Long> = emptyList(),
    val mainLayers: Boolean = false,
    val cycles: List<FuseCycleInfo> = emptyList(),
    val channelEpisodes: List<FuseChannelEpisode> = emptyList(),
    val smbSends: List<FuseSmbSend> = emptyList()
)

/** One run of cycles with the same account state, [startTime] until [endTime]. */
data class FuseStatusSegment(
    val startTime: Long,
    val endTime: Long,
    val status: FuseOverviewSource.AccountStatus
)

/** One FUSE cycle for the main graph. [channelFactor] is set while a channel TBR is wanted, pending or ending. */
data class FuseCycleInfo(
    val timestamp: Long,
    val state: FuseOverviewSource.CycleState,
    val bindingLimit: String? = null,
    val insulinReqU: Double? = null,
    val channelFactor: Double? = null
)

/**
 * One run of the slow correction channel, [startTime] until [endTime] (the last cycle of the run).
 *
 * @property factor the highest active factor in the run
 * @property deliveredU the extra insulin the pump delivered during this band [U] - not the whole episode: the channel
 *   books from the start of its episode, and a band that starts later shows only its own part
 * @property end why the run ended; `null` while it runs or when the data stops without a reason
 * @property running the run is still going on (it is the newest data)
 */
data class FuseChannelEpisode(
    val startTime: Long,
    val endTime: Long,
    val factor: Double?,
    val deliveredU: Double?,
    val end: FuseChannelEnd?,
    val running: Boolean
)

/** Why a run of the slow correction channel ended. */
enum class FuseChannelEnd { FALLING, BELOW_THRESHOLD, NO_DEMAND, MAX_IOB, SAFETY_ZERO, DESCENT_RISK, LOW, SIGNAL, REBOUND_DEADBAND, NIGHT_DEADBAND, OTHER_STOP, RESTART }

/** A cycle that handed [units] of SMB to AAPS, and where the SMB came from. */
data class FuseSmbSend(
    val timestamp: Long,
    val units: Double,
    val source: FuseOverviewSource.SmbSource
)
