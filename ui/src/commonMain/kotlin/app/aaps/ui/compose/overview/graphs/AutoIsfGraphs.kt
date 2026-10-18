package app.aaps.ui.compose.overview.graphs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill

/*
 * Fork (autoISF): the autoISF graphs of the fork (`GraphData.addIobTh`, `addAcceIsf` ... `addFinalIsf`) in the Compose
 * graphs. The data comes from AutoIsfGraphData, built in the workflow from the stored values of the autoISF runs.
 *
 * The five factors are drawn around 1 (the pivot lands in the middle of the axis, like SENSITIVITY around 100 %),
 * iobTH in U from 0. Colors as in the fork, the lighter ones on a dark background.
 */

/** A factor of 1 changes nothing: the middle of the factor axis. */
internal const val AUTO_ISF_FACTOR_PIVOT = 1.0

/** The factor axis reaches at least this far to each side of 1, so a flat line does not fill the whole graph. */
internal const val AUTO_ISF_FACTOR_MIN_DEVIATION = 0.25

/** The fork's colors of the six autoISF series (`iobThColor`, `finalIsfColor`, `acceIsfColor` ...). */
data class AutoIsfColors(
    val iobTh: Color,
    val finalIsf: Color,
    val acceIsf: Color,
    val bgIsf: Color,
    val ppIsf: Color,
    val duraIsf: Color
) {

    companion object {

        val LIGHT = AutoIsfColors(
            iobTh = Color(0xFF00FFFF), finalIsf = Color(0xFF00FFFF), acceIsf = Color(0xFF0044FF),
            bgIsf = Color(0xFFFF0000), ppIsf = Color(0xFF00BB00), duraIsf = Color(0xFFFF8800)
        )
        val DARK = AutoIsfColors(
            iobTh = Color(0xFFBBFFFF), finalIsf = Color(0xFF00FFFF), acceIsf = Color(0xFF0066FF),
            bgIsf = Color(0xFFFF0000), ppIsf = Color(0xFF00FF00), duraIsf = Color(0xFFFF8800)
        )
    }
}

/** The line of an autoISF series: iobTH dashed, the final factor thicker than the others, as in the fork. */
internal fun autoIsfLine(type: SeriesType, color: Color): LineCartesianLayer.Line = when (type) {
    SeriesType.IOB_TH  -> LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 2.dp, dashLength = 4.dp, gapLength = 3.dp),
        areaFill = null
    )

    SeriesType.FIN_ISF -> LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 3.dp),
        areaFill = null
    )

    else               -> LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        areaFill = null
    )
}
