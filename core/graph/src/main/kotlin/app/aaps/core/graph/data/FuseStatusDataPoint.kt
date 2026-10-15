package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint

/**
 * Ein Abschnitt des FUSE-Kontozustands: ein Streifen am oberen Rand des Guard-Untergraphen von [startTime] bis
 * [endTime], in der Farbe des Zustands. Ohne Y-Wert - der Zustand ist eine Zeitspanne, kein Messwert.
 */
class FuseStatusDataPoint(
    private val startTime: Long,
    private val endTime: Long,
    private val farbe: Int,
    override val label: String,
) : DataPointWithLabelInterface {

    override fun getX(): Double = startTime.toDouble()
    override fun getY(): Double = 0.0
    override fun setY(y: Double) {}

    override val duration get() = endTime - startTime
    override val shape = Shape.STATUS_STRIP
    override val size = 8f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = farbe
}
