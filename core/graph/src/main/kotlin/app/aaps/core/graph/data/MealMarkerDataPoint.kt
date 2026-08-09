package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint

/**
 * Der FUSE-Essensbeginn als Symbol am oberen Graphenrand.
 *
 * Y ist bewusst der obere Rand des sichtbaren Bereichs und traegt KEINE
 * Aussage - [Shape.MEAL_MARKER] zeichnet ohnehin am Rand. Der Wert steht nur
 * hier, damit die Reihe die Skalierung des Graphen nicht veraendert.
 */
class MealMarkerDataPoint(
    private val time: Long,
    private var yValue: Double,
    override val label: String = "🍽",
) : DataPointWithLabelInterface {

    override fun getX(): Double = time.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {
        yValue = y
    }

    override val duration = 0L
    override val shape = Shape.MEAL_MARKER
    override val size = 2f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.WHITE
}
