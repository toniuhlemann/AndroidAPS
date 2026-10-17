package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import kotlin.math.abs

/*
 * Fork (FUSE): the two FUSE graphs below BG, F.DRV and F.GRD, and the meal markers - as the fork drew them
 * (`GraphData.addFuseDrive`, `addFuseGuard`, `addFuseMealMarkers`). The data comes from FuseGraphData, built in
 * the graph preparation from the process ring of the FUSE plugin.
 *
 * F.DRV: the disturbance r as bars (negative red, positive in the meal window yellow, else green, like the
 * deviations), the meal effect the account expects as a dashed line, the ramp edges as dotted reference lines.
 * F.GRD: guard margin (solid), tail margin (dashed), both again WITH the announced meal (yellow), the account state
 * as a strip at the top. Above 0 open, below 0 blocked. Both graphs are symmetric around 0, as in the fork.
 */

/** Colors of the fork (core/ui colors.xml there): guard green, tail purple, the account states. */
internal val FUSE_GUARD_COLOR = Color(0xFF66BB6A)
internal val FUSE_TAIL_COLOR = Color(0xFFCE93D8)
internal val FUSE_DRIVE_COLOR = Color(0xFFFFA726)

/** Color of the account state in the strip at the top of F.GRD, as in the fork. */
internal fun fuseStatusColor(status: FuseOverviewSource.AccountStatus): Color = when (status) {
    FuseOverviewSource.AccountStatus.AKTIV       -> Color(0xFF4CAF50)
    FuseOverviewSource.AccountStatus.WARTET      -> Color(0xFFFFEB3B)
    FuseOverviewSource.AccountStatus.PAUSIERT    -> Color(0xFFFF9800)
    FuseOverviewSource.AccountStatus.EINGEFROREN -> Color(0xFF29B6F6)
    FuseOverviewSource.AccountStatus.GESPERRT    -> Color(0xFFF44336)
    FuseOverviewSource.AccountStatus.OHNE_KONTO  -> Color(0xFFE040FB)
    FuseOverviewSource.AccountStatus.BEENDET     -> Color(0xFF9E9E9E)
}

/** The style of one FUSE line. */
internal enum class FuseLineStyle { EXPECTED_DRIVE, RAMP, GUARD, TAIL, GUARD_WITH_MEAL, TAIL_WITH_MEAL }

/** One FUSE line in chart coordinates (x = minutes from the start of the graph). */
internal class FuseLine(val style: FuseLineStyle, val points: List<Pair<Double, Double>>)

/** One segment of the account strip in chart coordinates. */
internal class FuseStripSegment(val startX: Double, val endX: Double, val status: FuseOverviewSource.AccountStatus)

/**
 * The FUSE part of one graph, in chart coordinates.
 *
 * @property bars the drive bars per type (F.DRV only), drawn like the deviations
 * @property lines the lines, each run of points its own line
 * @property markerXs the meal marker presses
 * @property strip the account state (F.GRD only)
 * @property minHalfRange the axis reaches at least this far to each side of 0 (fork: 1 mg/dl/min and 10 mg/dl)
 */
internal class ProcessedFuse(
    val bars: ProcessedDeviationLines?,
    val lines: List<FuseLine>,
    val markerXs: List<Double>,
    val strip: List<FuseStripSegment>,
    val minHalfRange: Double
) {

    /** The y values of bars and lines inside the visible window; all of them when the window holds none. */
    fun windowedY(visibleMinX: Double?, visibleMaxX: Double?): List<Double> {
        fun inWindow(x: Double) = visibleMinX == null || visibleMaxX == null || x in visibleMinX..visibleMaxX
        val all = buildList {
            bars?.let { b -> b.series.values.forEach { ys -> addAll(b.allX.zip(ys)) } }
            lines.forEach { addAll(it.points) }
        }
        return all.filter { inWindow(it.first) }.map { it.second }.ifEmpty { all.map { it.second } }
    }

    /** The symmetric axis around 0: at least [minHalfRange], else the largest value either way. */
    fun scale(values: List<Double>, tickCount: Int): NiceScale {
        val half = maxOf(minHalfRange, values.maxOfOrNull { abs(it) } ?: 0.0)
        return niceScaleAroundPivot(-half, half, 0.0, tickCount, minDeviation = minHalfRange)
    }
}

/** Builds the FUSE part of a graph of [type]; `null` for a graph that is neither F.DRV nor F.GRD. */
internal fun processFuse(data: FuseGraphData, type: SeriesType, minTimestamp: Long, minX: Double, maxX: Double): ProcessedFuse? {
    fun pts(points: List<GraphDataPoint>) = points.map { timestampToX(it.timestamp, minTimestamp) to it.value }.filter { it.first in minX..maxX }
    fun runs(style: FuseLineStyle, runs: List<List<GraphDataPoint>>) = runs.map { FuseLine(style, pts(it)) }.filter { it.points.isNotEmpty() }
    val markerXs = data.mealMarkers.map { timestampToX(it, minTimestamp) }.filter { it in minX..maxX }
    return when (type) {
        SeriesType.FUSE_DRIVE -> {
            // The axis always reaches 1.25 times the upper ramp edge, so the edge never leaves the picture (fork).
            val rampLines = listOfNotNull(data.rampLow, data.rampHigh)
                .filter { it.isFinite() }
                .map { level -> FuseLine(FuseLineStyle.RAMP, listOf(minX to level, maxX to level)) }
            ProcessedFuse(
                bars = deviationLines(data.drive, minTimestamp, minX, maxX),
                lines = runs(FuseLineStyle.EXPECTED_DRIVE, data.expectedDrive) + rampLines,
                markerXs = markerXs,
                strip = emptyList(),
                minHalfRange = maxOf(1.0, (data.rampHigh?.takeIf { it.isFinite() } ?: 0.0) * 1.25)
            )
        }

        SeriesType.FUSE_GUARD -> ProcessedFuse(
            bars = null,
            lines = listOf(FuseLine(FuseLineStyle.GUARD, pts(data.guard)), FuseLine(FuseLineStyle.TAIL, pts(data.tail)))
                .filter { it.points.isNotEmpty() } +
                runs(FuseLineStyle.GUARD_WITH_MEAL, data.guardWithMeal) +
                runs(FuseLineStyle.TAIL_WITH_MEAL, data.tailWithMeal),
            markerXs = markerXs,
            strip = data.accountStatus
                .map { FuseStripSegment(timestampToX(it.startTime, minTimestamp), timestampToX(it.endTime, minTimestamp), it.status) }
                .filter { it.endX >= minX && it.startX <= maxX },
            minHalfRange = 10.0
        )

        else                  -> null
    }
}

/**
 * Deviation-style bars: one series per type over ALL x positions, 0 where a point has another type. With the
 * square connector each point becomes a filled step. `null` when nothing is in range.
 */
internal fun deviationLines(points: List<DeviationDataPoint>, minTimestamp: Long, minX: Double, maxX: Double): ProcessedDeviationLines? {
    val filtered = points
        .map { Triple(timestampToX(it.timestamp, minTimestamp), it.value, it.deviationType) }
        .filter { it.first in minX..maxX }
        .sortedBy { it.first }
    if (filtered.isEmpty()) return null
    val allX = filtered.map { it.first }
    val typeValueMap = filtered.associate { it.first to (it.second to it.third) }
    // Per type y arrays: the real value where the type matches, 0.0 otherwise
    val perType = DeviationType.entries.associateWith { type ->
        allX.map { x ->
            val (value, pointType) = typeValueMap.getValue(x)
            if (pointType == type) value else 0.0
        }
    }
    // Only types that have at least one value
    return ProcessedDeviationLines(allX, perType.filter { (_, ys) -> ys.any { it != 0.0 } })
}

/** The line styles of the FUSE lines; [onSurface] for the expected drive (white in the dark theme, as in the fork). */
internal fun fuseLine(style: FuseLineStyle, onSurface: Color, mealColor: Color): LineCartesianLayer.Line = when (style) {
    FuseLineStyle.EXPECTED_DRIVE  -> dashedLine(onSurface, thickness = 2f, dash = 5f, gap = 3f)
    FuseLineStyle.RAMP            -> dashedLine(FUSE_GUARD_COLOR, thickness = 1f, dash = 1f, gap = 3f)
    FuseLineStyle.GUARD           -> LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(FUSE_GUARD_COLOR)), areaFill = null)
    FuseLineStyle.TAIL            -> dashedLine(FUSE_TAIL_COLOR, thickness = 1.5f, dash = 3f, gap = 3f)
    FuseLineStyle.GUARD_WITH_MEAL -> LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(mealColor)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
        areaFill = null
    )

    FuseLineStyle.TAIL_WITH_MEAL  -> dashedLine(mealColor, thickness = 1.5f, dash = 3f, gap = 3f)
}

private fun dashedLine(color: Color, thickness: Float, dash: Float, gap: Float) = LineCartesianLayer.Line(
    fill = LineCartesianLayer.LineFill.single(Fill(color)),
    stroke = LineCartesianLayer.LineStroke.Dashed(thickness = thickness.dp, dashLength = dash.dp, gapLength = gap.dp),
    areaFill = null
)

/** Canvas x of chart value [x] (the same mapping as [NowLine]); `null` outside the visible area. */
private fun CartesianDrawingContext.canvasXOf(x: Double): Float? {
    val xStep = ranges.xStep
    if (xStep == 0.0) return null
    val canvasX = layerBounds.left + layerDimensions.startPadding + layerDimensions.xSpacing * ((x - ranges.minX) / xStep).toFloat() - scroll
    return canvasX.takeIf { it in layerBounds.left..layerBounds.right }
}

/**
 * Fork (FUSE): the meal marker presses as vertical lines over the full height - in the BG graph and in both FUSE
 * graphs, so one marker names the same moment in all three. In the BG graph a plate [icon] at the top says WHAT, the
 * line says WHEN (Toni, as in the viewer).
 */
class FuseMealMarkerLines(
    private val markerXs: List<Double>,
    private val color: Color,
    private val strokeWidthPx: Float,
    private val icon: Painter? = null,
    private val iconSizePx: Float = 0f
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            for (x in markerXs) {
                val canvasX = canvasXOf(x) ?: continue
                with(mutableDrawScope) {
                    drawLine(
                        color = this@FuseMealMarkerLines.color,
                        start = Offset(canvasX, layerBounds.top),
                        end = Offset(canvasX, layerBounds.bottom),
                        strokeWidth = strokeWidthPx
                    )
                    val painter = icon ?: return@with
                    translate(left = canvasX - iconSizePx / 2f, top = layerBounds.top) {
                        with(painter) { draw(Size(iconSizePx, iconSizePx), colorFilter = ColorFilter.tint(this@FuseMealMarkerLines.color)) }
                    }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is FuseMealMarkerLines && markerXs == other.markerXs && color == other.color && strokeWidthPx == other.strokeWidthPx &&
            icon == other.icon && iconSizePx == other.iconSizePx

    override fun hashCode(): Int {
        var result = markerXs.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + strokeWidthPx.hashCode()
        result = 31 * result + (icon?.hashCode() ?: 0)
        return result
    }
}

/** Fork (FUSE): the account state as a strip at the top of F.GRD - one colored band per run of cycles. */
internal class FuseAccountStrip(
    private val segments: List<FuseStripSegment>,
    private val heightPx: Float
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return
            fun rawX(x: Double) = layerBounds.left + layerDimensions.startPadding + layerDimensions.xSpacing * ((x - ranges.minX) / xStep).toFloat() - scroll
            for (segment in segments) {
                val left = rawX(segment.startX).coerceAtLeast(layerBounds.left)
                val right = rawX(segment.endX).coerceAtMost(layerBounds.right)
                if (right <= left) continue
                with(mutableDrawScope) {
                    drawRect(color = fuseStatusColor(segment.status), topLeft = Offset(left, layerBounds.top), size = Size(right - left, heightPx))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FuseAccountStrip && segments.size == other.segments.size &&
            segments.zip(other.segments).all { (a, b) -> a.startX == b.startX && a.endX == b.endX && a.status == b.status } &&
            heightPx == other.heightPx

    override fun hashCode(): Int = segments.fold(heightPx.hashCode()) { acc, s -> 31 * acc + s.startX.hashCode() + s.status.hashCode() }
}

/** The decorations of a FUSE graph: marker lines and, on F.GRD, the account strip. */
@Composable
internal fun rememberFuseDecorations(processed: ProcessedFuse?, markerColor: Color): List<Decoration> {
    val density = LocalDensity.current.density
    return remember(processed, markerColor, density) {
        if (processed == null) emptyList()
        else listOfNotNull(
            processed.markerXs.takeIf { it.isNotEmpty() }?.let { FuseMealMarkerLines(it, markerColor, strokeWidthPx = 2f * density) },
            processed.strip.takeIf { it.isNotEmpty() }?.let { FuseAccountStrip(it, heightPx = 4f * density) }
        )
    }
}
