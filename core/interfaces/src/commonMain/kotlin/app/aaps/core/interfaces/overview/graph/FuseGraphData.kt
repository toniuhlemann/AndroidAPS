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
 * @property guard guard margin: above 0 open, below 0 blocked
 * @property tail tail margin, same meaning
 * @property guardWithMeal guard margin WITH the announced meal, one line per run
 * @property tailWithMeal tail margin WITH the announced meal, one line per run
 * @property accountStatus the account state as segments, drawn as a strip at the top of F.GRD
 * @property mealMarkers times of the meal marker presses, vertical lines in the BG graph and in both FUSE graphs
 */
data class FuseGraphData(
    val available: Boolean = false,
    val drive: List<DeviationDataPoint> = emptyList(),
    val expectedDrive: List<List<GraphDataPoint>> = emptyList(),
    val rampLow: Double? = null,
    val rampHigh: Double? = null,
    val guard: List<GraphDataPoint> = emptyList(),
    val tail: List<GraphDataPoint> = emptyList(),
    val guardWithMeal: List<List<GraphDataPoint>> = emptyList(),
    val tailWithMeal: List<List<GraphDataPoint>> = emptyList(),
    val accountStatus: List<FuseStatusSegment> = emptyList(),
    val mealMarkers: List<Long> = emptyList()
)

/** One run of cycles with the same account state, [startTime] until [endTime]. */
data class FuseStatusSegment(
    val startTime: Long,
    val endTime: Long,
    val status: FuseOverviewSource.AccountStatus
)
