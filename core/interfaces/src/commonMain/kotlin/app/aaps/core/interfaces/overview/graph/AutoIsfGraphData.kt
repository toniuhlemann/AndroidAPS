package app.aaps.core.interfaces.overview.graph

/**
 * Fork (autoISF): the values of each autoISF run for the graph menu, as in the fork (IOB_TH, FINAL, ACCE, BG, PP,
 * DURA). Read from the stored autoISF values of the shown time range. [available] is false while autoISF is not the
 * active APS, and on a client: the six series are then not offered in the graph menu.
 *
 * The five factors are 1 when they change nothing; above 1 the ISF gets stronger (more insulin), below 1 weaker.
 *
 * @property iobTh the effective iobTH of each run [U]
 * @property finalIsf the final autoISF factor
 * @property acceIsf the factor from the glucose acceleration
 * @property bgIsf the factor from the glucose level
 * @property ppIsf the factor from the rise after a meal
 * @property duraIsf the factor from the duration of a high plateau
 */
data class AutoIsfGraphData(
    val available: Boolean = false,
    val iobTh: List<GraphDataPoint> = emptyList(),
    val finalIsf: List<GraphDataPoint> = emptyList(),
    val acceIsf: List<GraphDataPoint> = emptyList(),
    val bgIsf: List<GraphDataPoint> = emptyList(),
    val ppIsf: List<GraphDataPoint> = emptyList(),
    val duraIsf: List<GraphDataPoint> = emptyList()
) {

    /** The points of [type]; empty for a series that is not an autoISF one. */
    fun points(type: SeriesType): List<GraphDataPoint> = when (type) {
        SeriesType.IOB_TH  -> iobTh
        SeriesType.FIN_ISF -> finalIsf
        SeriesType.ACC_ISF -> acceIsf
        SeriesType.BG_ISF  -> bgIsf
        SeriesType.PP_ISF  -> ppIsf
        SeriesType.DUR_ISF -> duraIsf
        else               -> emptyList()
    }

    companion object {

        /** The five factors, drawn around 1. */
        val FACTOR_SERIES: Set<SeriesType> = setOf(SeriesType.FIN_ISF, SeriesType.ACC_ISF, SeriesType.BG_ISF, SeriesType.PP_ISF, SeriesType.DUR_ISF)

        /** All six autoISF series of the graph menu. */
        val SERIES: Set<SeriesType> = FACTOR_SERIES + SeriesType.IOB_TH
    }
}
