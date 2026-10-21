package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.FuseChannelEnd
import app.aaps.core.interfaces.overview.graph.FuseChannelEpisode
import app.aaps.core.interfaces.overview.graph.FuseCycleInfo
import app.aaps.core.interfaces.overview.graph.FuseGraphData
import app.aaps.core.interfaces.overview.graph.FuseSmbSend
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs
import kotlin.math.round

/*
 * Fork (FUSE): the FUSE layers of the main graph (Toni 21.10.2026, after the viewer's main graph):
 *  - BG points in the colour of what FUSE did in that cycle; a low stays red,
 *  - the slow correction channel as a band with factor, delivered insulin and end reason,
 *  - SMB triangles in the IOB graph in the colour of their source, the sum per run, a ring on the BG points where a
 *    run starts,
 *  - a strip above the basal for protective zero and channel,
 *  - more values in the press-and-hold pill, and a legend that opens on demand.
 * Display only. The data comes from FuseGraphData; everything here is mapping and drawing on the UI side.
 */

/** Colours of the FUSE states, as in the viewer. */
internal fun fuseStateColor(state: FuseOverviewSource.CycleState): Color = when (state) {
    FuseOverviewSource.CycleState.FREE    -> Color(0xFF4CAF50)
    FuseOverviewSource.CycleState.GUARD   -> Color(0xFFFFC107)
    FuseOverviewSource.CycleState.BLOCKED -> Color(0xFFFF7043)
    FuseOverviewSource.CycleState.ZERO    -> Color(0xFFE040FB)
    FuseOverviewSource.CycleState.CHANNEL -> Color(0xFF26C6DA)
    FuseOverviewSource.CycleState.UNKNOWN -> Color(0xFF757575)
}

/** Colours of the SMB sources, as in the viewer. */
internal fun fuseSourceColor(source: FuseOverviewSource.SmbSource): Color = when (source) {
    FuseOverviewSource.SmbSource.EXPECTATION -> Color(0xFFFF9800)
    FuseOverviewSource.SmbSource.NORMAL      -> Color(0xFF4FC3F7)
    FuseOverviewSource.SmbSource.OTHER       -> Color(0xFFE0E0E0)
}

internal val FUSE_CHANNEL_COLOR = fuseStateColor(FuseOverviewSource.CycleState.CHANNEL)

/** Max distance between a reading or an SMB and its FUSE cycle, in minutes and in ms. */
private const val FUSE_MATCH_MIN = 3.0
private const val FUSE_MATCH_MS = 3 * 60_000L

/** A pause longer than this starts a new SMB run. */
internal const val FUSE_SMB_RUN_GAP_MS = 6 * 60_000L

/** The amount tolerance when an SMB is matched to the cycle that sent it (pump step and rounding). */
private const val FUSE_SMB_AMOUNT_EPS = 0.011

/** The FUSE data the main graph needs; `null` when the layers are off or there is no data. */
@Immutable
data class FuseMainLayers(
    val cycles: List<FuseCycleInfo>,
    val channelEpisodes: List<FuseChannelEpisode>,
    val smbSends: List<FuseSmbSend>
) {

    companion object {

        fun of(data: FuseGraphData): FuseMainLayers? =
            if (!data.mainLayers) null else FuseMainLayers(data.cycles, data.channelEpisodes, data.smbSends)
    }
}

// =========================================================================
// BG points
// =========================================================================

/** The state per cycle on the x axis (whole minutes), sorted, for the point provider. */
class FuseStateLookup internal constructor(private val xs: DoubleArray, private val states: Array<FuseOverviewSource.CycleState>) {

    /** The state of the cycle nearest to [x], at most [FUSE_MATCH_MIN] minutes away. */
    fun at(x: Double): FuseOverviewSource.CycleState? {
        if (xs.isEmpty()) return null
        var lo = 0
        var hi = xs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (xs[mid] < x) lo = mid + 1 else hi = mid
        }
        var best = -1
        for (i in intArrayOf(lo - 1, lo)) {
            if (i < 0 || i >= xs.size) continue
            if (best < 0 || abs(xs[i] - x) < abs(xs[best] - x)) best = i
        }
        return states[best].takeIf { abs(xs[best] - x) <= FUSE_MATCH_MIN }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FuseStateLookup && xs.contentEquals(other.xs) && states.contentEquals(other.states)

    override fun hashCode(): Int = 31 * xs.contentHashCode() + states.contentHashCode()

    companion object {

        fun of(cycles: List<FuseCycleInfo>, minTimestamp: Long): FuseStateLookup {
            val sorted = cycles.sortedBy { it.timestamp }
            return FuseStateLookup(
                DoubleArray(sorted.size) { timestampToX(sorted[it].timestamp, minTimestamp) },
                Array(sorted.size) { sorted[it].state }
            )
        }
    }
}

/**
 * What the FUSE point provider needs per model: the FUSE state per cycle and the range of each drawn reading. Both
 * come through the model's extras, so the line and its provider stay the same objects when data changes.
 */
data class FuseBgPaint(val states: FuseStateLookup, val ranges: Map<Double, BgRange>)

internal val FUSE_BG_PAINT_KEY = ExtraStore.Key<FuseBgPaint>()

/**
 * The 1-minute readings in the colour of the FUSE state (Toni 21.10.2026). FUSE works on the 1-minute readings, and its
 * cycles match them one to one. A low keeps its red, and a reading without a FUSE cycle nearby keeps the usual range
 * colour.
 */
@Immutable
class FuseBgPointProvider(lowColor: Color, inRangeColor: Color, highColor: Color) : LineCartesianLayer.PointProvider {

    private fun filled(color: Color) = LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = CircleShape), size = 6.dp)

    private val rangePoints = mapOf(BgRange.LOW to filled(lowColor), BgRange.IN_RANGE to filled(inRangeColor), BgRange.HIGH to filled(highColor))

    private val statePoints = FuseOverviewSource.CycleState.entries.associateWith { filled(fuseStateColor(it)) }

    override fun getPoint(entry: LineCartesianLayerModel.Entry, extraStore: ExtraStore): LineCartesianLayer.Point? {
        val paint = extraStore.getOrNull(FUSE_BG_PAINT_KEY) ?: return rangePoints[BgRange.IN_RANGE]
        val range = paint.ranges[entry.x] ?: BgRange.IN_RANGE
        if (range == BgRange.LOW) return rangePoints[BgRange.LOW]
        val state = paint.states.at(entry.x) ?: return rangePoints[range]
        return statePoints[state]
    }

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = rangePoints.getValue(BgRange.IN_RANGE)
}

// =========================================================================
// SMB: source per delivered SMB, runs
// =========================================================================

/** A delivered SMB with the source of the cycle that sent it. */
internal data class FuseSmbWithSource(val timestamp: Long, val amount: Double, val source: FuseOverviewSource.SmbSource)

/** A run of SMBs without a pause longer than [FUSE_SMB_RUN_GAP_MS]: its sum and the source with the largest share. */
internal data class FuseSmbRun(val startTime: Long, val endTime: Long, val sumU: Double, val mainSource: FuseOverviewSource.SmbSource)

/**
 * Gives each delivered SMB the source of the cycle that sent it - greedy by the smallest time distance, each sent SMB
 * only once, first with a matching amount, then without (AAPS may have cut it). Without a matching cycle the source is
 * OTHER: a delivery without a FUSE request is not FUSE's work.
 */
internal fun fuseSmbSources(boluses: List<BolusGraphPoint>, sends: List<FuseSmbSend>): List<FuseSmbWithSource> {
    val open = sends.toMutableList()
    return boluses.filter { it.bolusType == BolusType.SMB && it.isValid && it.amount > 0.0 }.sortedBy { it.timestamp }.map { smb ->
        fun nearest(amountMustMatch: Boolean) = open.withIndex()
            .filter { (_, s) -> abs(s.timestamp - smb.timestamp) <= FUSE_MATCH_MS }
            .filter { (_, s) -> !amountMustMatch || abs(s.units - smb.amount) <= FUSE_SMB_AMOUNT_EPS }
            .minByOrNull { (_, s) -> abs(s.timestamp - smb.timestamp) }
        val match = nearest(true) ?: nearest(false)
        if (match != null) {
            open.removeAt(match.index)
            FuseSmbWithSource(smb.timestamp, smb.amount, match.value.source)
        } else FuseSmbWithSource(smb.timestamp, smb.amount, FuseOverviewSource.SmbSource.OTHER)
    }
}

/** The SMB runs; a pause longer than [FUSE_SMB_RUN_GAP_MS] starts the next. */
internal fun fuseSmbRuns(smbs: List<FuseSmbWithSource>): List<FuseSmbRun> {
    val runs = ArrayList<FuseSmbRun>()
    var current = ArrayList<FuseSmbWithSource>()
    fun close() {
        if (current.isEmpty()) return
        val shares = current.groupBy { it.source }.mapValues { (_, list) -> list.sumOf { it.amount } }
        runs.add(FuseSmbRun(current.first().timestamp, current.last().timestamp, current.sumOf { it.amount }, shares.maxByOrNull { it.value }!!.key))
        current = ArrayList()
    }
    for (smb in smbs.sortedBy { it.timestamp }) {
        if (current.isNotEmpty() && smb.timestamp - current.last().timestamp > FUSE_SMB_RUN_GAP_MS) close()
        current.add(smb)
    }
    close()
    return runs
}

/** One group of SMB triangles: one size class (0 small, 1 medium, 2 large) and one source. */
internal data class FuseSmbGroup(val size: Int, val source: FuseOverviewSource.SmbSource, val xs: List<Double>)

/**
 * The SMB triangles of the IOB graph, split by size like the plain graph (thirds of the visible range) and by
 * source. Only SMBs inside [minX]..[maxX].
 */
internal fun fuseSmbGroups(smbs: List<FuseSmbWithSource>, minTimestamp: Long, minX: Double, maxX: Double): List<FuseSmbGroup> {
    val visible = smbs.filter { timestampToX(it.timestamp, minTimestamp) in minX..maxX }
    if (visible.isEmpty()) return emptyList()
    val minAmount = visible.minOf { it.amount }
    val range = visible.maxOf { it.amount } - minAmount
    fun size(amount: Double): Int = when {
        visible.size <= 1 || range <= 0.0         -> 1
        amount < minAmount + range / 3.0          -> 0
        amount < minAmount + 2.0 * range / 3.0    -> 1
        else                                      -> 2
    }
    return visible.groupBy { size(it.amount) to it.source }
        .map { (key, list) -> FuseSmbGroup(key.first, key.second, list.map { timestampToX(it.timestamp, minTimestamp) }) }
        .sortedWith(compareBy({ it.size }, { it.source.ordinal }))
}

/** The triangle line of one SMB group - the shapes and sizes of the plain IOB graph, the colour of the source. */
internal fun fuseSmbLine(group: FuseSmbGroup): LineCartesianLayer.Line = LineCartesianLayer.Line(
    fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
    areaFill = null,
    pointProvider = LineCartesianLayer.PointProvider.single(
        LineCartesianLayer.Point(
            component = ShapeComponent(fill = Fill(fuseSourceColor(group.source)), shape = TriangleShape),
            size = when (group.size) {
                0    -> 10.dp
                1    -> 16.dp
                else -> 22.dp
            }
        )
    )
)

// =========================================================================
// Decorations
// =========================================================================

/** Canvas x of chart value [x], with the same mapping as [NowLine]; may lie outside the visible area. */
private fun CartesianDrawingContext.rawCanvasX(x: Double): Float? {
    val xStep = ranges.xStep
    if (xStep == 0.0) return null
    return layerBounds.left + layerDimensions.startPadding + layerDimensions.xSpacing * ((x - ranges.minX) / xStep).toFloat() - scroll
}

/** Canvas y of [value] on the start axis. */
private fun CartesianDrawingContext.canvasYOf(value: Double): Float {
    val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
    return layerBounds.bottom - ((value - yRange.minY) / yRange.length).toFloat() * layerBounds.height
}

/** One band of the slow correction channel, in chart coordinates, with its two label lines. */
internal data class FuseBand(val startX: Double, val endX: Double, val label: String, val end: String?)

/**
 * The slow correction channel in the BG graph: a translucent band per run over the full height, the factor and the
 * delivered insulin on top, below it how the run ended. Labels of neighbouring runs alternate in height.
 */
internal class FuseChannelBands(
    private val bands: List<FuseBand>,
    private val textMeasurer: TextMeasurer,
    private val labelStyle: TextStyle,
    private val endStyle: TextStyle,
    private val labelBackground: Color,
    private val density: Float
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            bands.forEachIndexed { index, band ->
                val x0 = rawCanvasX(band.startX) ?: return
                // A run lasts one cycle past its last point.
                val x1 = rawCanvasX(band.endX + 1.0) ?: return
                val left = x0.coerceAtLeast(layerBounds.left)
                val right = x1.coerceAtMost(layerBounds.right)
                if (right <= left) return@forEachIndexed
                with(mutableDrawScope) {
                    drawRect(FUSE_CHANNEL_COLOR.copy(alpha = 0.16f), topLeft = Offset(left, layerBounds.top), size = Size(right - left, layerBounds.height))
                    val top = layerBounds.top + (2f + (index % 2) * 26f) * density
                    val first = textMeasurer.measure(band.label, labelStyle)
                    val second = band.end?.let { textMeasurer.measure(it, endStyle) }
                    val width = maxOf(first.size.width, second?.size?.width ?: 0) + 6f * density
                    val height = first.size.height + (second?.size?.height ?: 0) + 2f * density
                    val center = ((x0 + x1) / 2f).coerceIn(layerBounds.left + width / 2f, maxOf(layerBounds.left + width / 2f, layerBounds.right - width / 2f))
                    drawRect(labelBackground, topLeft = Offset(center - width / 2f, top), size = Size(width, height))
                    drawText(first, topLeft = Offset(center - first.size.width / 2f, top + 1f * density))
                    second?.let { drawText(it, topLeft = Offset(center - it.size.width / 2f, top + 1f * density + first.size.height)) }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FuseChannelBands && bands == other.bands && labelStyle == other.labelStyle && endStyle == other.endStyle &&
            labelBackground == other.labelBackground && density == other.density

    override fun hashCode(): Int = 31 * bands.hashCode() + labelStyle.hashCode()
}

/** White rings on the BG points where an SMB run starts: [rings] holds x and the BG value there. */
internal class FuseSmbRunRings(
    private val rings: List<Pair<Double, Double>>,
    private val radiusPx: Float,
    private val strokePx: Float,
    private val color: Color
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            for ((x, value) in rings) {
                val canvasX = rawCanvasX(x) ?: continue
                if (canvasX < layerBounds.left || canvasX > layerBounds.right) continue
                val canvasY = canvasYOf(value)
                if (canvasY < layerBounds.top || canvasY > layerBounds.bottom) continue
                with(mutableDrawScope) {
                    drawCircle(this@FuseSmbRunRings.color, radius = radiusPx, center = Offset(canvasX, canvasY), style = Stroke(width = strokePx))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FuseSmbRunRings && rings == other.rings && radiusPx == other.radiusPx && strokePx == other.strokePx && color == other.color

    override fun hashCode(): Int = 31 * rings.hashCode() + color.hashCode()
}

/** The sum of each SMB run above its triangles in the IOB graph: x of the run's middle, the text and its colour. */
internal class FuseSmbRunSums(
    private val sums: List<Triple<Double, String, Color>>,
    private val textMeasurer: TextMeasurer,
    private val style: TextStyle,
    private val liftPx: Float
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            // Larger sums first; a sum that would cover one already drawn is left out.
            val placed = ArrayList<Pair<Float, Float>>()
            val baseline = canvasYOf(0.0)
            for ((x, text, color) in sums) {
                val canvasX = rawCanvasX(x) ?: continue
                if (canvasX < layerBounds.left || canvasX > layerBounds.right) continue
                val layout = textMeasurer.measure(text, style.copy(color = color))
                val half = layout.size.width / 2f + 2f
                if (placed.any { (px, ph) -> abs(px - canvasX) < ph + half }) continue
                placed.add(canvasX to half)
                with(mutableDrawScope) {
                    drawText(layout, topLeft = Offset(canvasX - layout.size.width / 2f, baseline - liftPx - layout.size.height))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FuseSmbRunSums && sums == other.sums && style == other.style && liftPx == other.liftPx

    override fun hashCode(): Int = 31 * sums.hashCode() + style.hashCode()
}

/** A strip at the top of the IOB graph, above the basal: protective zero and channel in their colours. */
internal class FuseBasalStrip(
    private val segments: List<Triple<Double, Double, Color>>,
    private val heightPx: Float
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            for ((startX, endX, color) in segments) {
                val left = (rawCanvasX(startX) ?: continue).coerceAtLeast(layerBounds.left)
                val right = (rawCanvasX(endX) ?: continue).coerceAtMost(layerBounds.right)
                if (right <= left) continue
                with(mutableDrawScope) {
                    drawRect(color, topLeft = Offset(left, layerBounds.top), size = Size(right - left, heightPx))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FuseBasalStrip && segments == other.segments && heightPx == other.heightPx

    override fun hashCode(): Int = 31 * segments.hashCode() + heightPx.hashCode()
}

/** Runs of protective zero and channel for [FuseBasalStrip]; a run lasts one cycle past its last point. */
internal fun fuseBasalSegments(cycles: List<FuseCycleInfo>, minTimestamp: Long): List<Triple<Double, Double, Color>> {
    val out = ArrayList<Triple<Double, Double, Color>>()
    var state: FuseOverviewSource.CycleState? = null
    var start = 0L
    var end = 0L
    fun close() {
        state?.let { out.add(Triple(timestampToX(start, minTimestamp), timestampToX(end, minTimestamp), fuseStateColor(it))) }
    }
    for (c in cycles.sortedBy { it.timestamp }) {
        val s = c.state.takeIf { it == FuseOverviewSource.CycleState.ZERO || it == FuseOverviewSource.CycleState.CHANNEL }
        if (s != state || c.timestamp - end > FUSE_MATCH_MS) {
            close()
            state = s
            start = c.timestamp
        }
        end = c.timestamp + 60_000L
    }
    close()
    return out
}

// =========================================================================
// Texts
// =========================================================================

/** The active factor with as few decimals as it needs: 4, 1.5, 1.75 - never "4.00". */
internal fun fuseFactorText(factor: Double, formatter: DecimalFormatter): String = when {
    abs(factor - round(factor)) < 0.005               -> formatter.to0Decimal(factor)
    abs(factor * 10.0 - round(factor * 10.0)) < 0.05 -> formatter.to1Decimal(factor)
    else                                              -> formatter.to2Decimal(factor)
}

@Composable
internal fun fuseStateText(state: FuseOverviewSource.CycleState): String = stringResource(
    when (state) {
        FuseOverviewSource.CycleState.FREE    -> CoreUiStrings.fuse_state_free
        FuseOverviewSource.CycleState.GUARD   -> CoreUiStrings.fuse_state_guard
        FuseOverviewSource.CycleState.BLOCKED -> CoreUiStrings.fuse_state_blocked
        FuseOverviewSource.CycleState.ZERO    -> CoreUiStrings.fuse_state_zero
        FuseOverviewSource.CycleState.CHANNEL -> CoreUiStrings.fuse_state_channel
        FuseOverviewSource.CycleState.UNKNOWN -> CoreUiStrings.fuse_state_unknown
    }
)

@Composable
internal fun fuseSourceText(source: FuseOverviewSource.SmbSource): String = stringResource(
    when (source) {
        FuseOverviewSource.SmbSource.EXPECTATION -> CoreUiStrings.fuse_source_expectation
        FuseOverviewSource.SmbSource.NORMAL      -> CoreUiStrings.fuse_source_normal
        FuseOverviewSource.SmbSource.OTHER       -> CoreUiStrings.fuse_source_other
    }
)

@Composable
internal fun fuseChannelEndText(end: FuseChannelEnd): String = stringResource(
    when (end) {
        FuseChannelEnd.FALLING          -> CoreUiStrings.fuse_channel_end_falling
        FuseChannelEnd.BELOW_THRESHOLD  -> CoreUiStrings.fuse_channel_end_below
        FuseChannelEnd.NO_DEMAND        -> CoreUiStrings.fuse_channel_end_no_demand
        FuseChannelEnd.MAX_IOB          -> CoreUiStrings.fuse_channel_end_max_iob
        FuseChannelEnd.SAFETY_ZERO      -> CoreUiStrings.fuse_channel_end_safety_zero
        FuseChannelEnd.DESCENT_RISK     -> CoreUiStrings.fuse_channel_end_descent
        FuseChannelEnd.LOW              -> CoreUiStrings.fuse_channel_end_low
        FuseChannelEnd.SIGNAL           -> CoreUiStrings.fuse_channel_end_signal
        FuseChannelEnd.REBOUND_DEADBAND -> CoreUiStrings.fuse_channel_end_rebound
        FuseChannelEnd.NIGHT_DEADBAND   -> CoreUiStrings.fuse_channel_end_night
        FuseChannelEnd.OTHER_STOP       -> CoreUiStrings.fuse_channel_end_other
        FuseChannelEnd.RESTART          -> CoreUiStrings.fuse_channel_end_restart
    }
)

/**
 * The binding limit as FUSE writes it (`guardFloor=70.0|...`, `tailHeadroom=-0.1`, `liveness:demandRatioCap`) in
 * Toni's words; an unknown limit shows its own name.
 */
@Composable
internal fun fuseBindingText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.startsWith("signal epoch already consumed")) return stringResource(CoreUiStrings.fuse_channel_end_restart)
    if (raw.startsWith("drive not estimable")) return stringResource(CoreUiStrings.fuse_binding_signal)
    val core = raw.substringBefore('|').substringBefore('=').trim()
    return when (core) {
        "guardFloor"                                                -> stringResource(CoreUiStrings.fuse_binding_guard)
        "SAFETY_HOLD"                                               -> stringResource(CoreUiStrings.fuse_binding_safety_hold)
        "tailHeadroom"                                              -> stringResource(CoreUiStrings.fuse_binding_tail)
        "markerAuth"                                                -> stringResource(CoreUiStrings.fuse_binding_marker)
        "demandRatioCap", "liveness:demandRatioCap"                 -> stringResource(CoreUiStrings.fuse_binding_demand_ratio)
        "smbRatio", "liveness:smbRatio"                             -> stringResource(CoreUiStrings.fuse_binding_smb_ratio)
        "correctionExposureLimit", "liveness:correctionExposureLimit" -> stringResource(CoreUiStrings.fuse_binding_exposure)
        "measuredDescentRisk"                                       -> stringResource(CoreUiStrings.fuse_channel_end_descent)
        "reboundDeadband"                                           -> stringResource(CoreUiStrings.fuse_channel_end_rebound)
        "nightDeadband"                                             -> stringResource(CoreUiStrings.fuse_channel_end_night)
        else                                                        -> core
    }
}

// =========================================================================
// Legend
// =========================================================================

/** The small "Legend" button on the BG graph; the legend itself opens as a dialog and takes no room. */
@Composable
internal fun FuseLegendButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(CoreUiStrings.fuse_legend),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
internal fun FuseLegendDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.ok)) } },
        title = { Text(stringResource(CoreUiStrings.fuse_legend_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(CoreUiStrings.fuse_legend_points), style = MaterialTheme.typography.bodySmall)
                FuseOverviewSource.CycleState.entries.forEach { state -> FuseLegendRow(fuseStateColor(state), fuseStateText(state), triangle = false) }
                Text(stringResource(CoreUiStrings.fuse_legend_smb), style = MaterialTheme.typography.bodySmall)
                FuseOverviewSource.SmbSource.entries.forEach { source -> FuseLegendRow(fuseSourceColor(source), fuseSourceText(source), triangle = true) }
                Text(stringResource(CoreUiStrings.fuse_legend_channel), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(CoreUiStrings.fuse_legend_basal), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(CoreUiStrings.fuse_legend_hold), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun FuseLegendRow(color: Color, text: String, triangle: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = Modifier.size(12.dp)) {
            if (triangle) {
                val path = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color)
            } else drawCircle(color)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
