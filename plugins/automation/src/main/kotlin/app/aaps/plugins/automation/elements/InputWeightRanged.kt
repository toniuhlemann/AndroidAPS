package app.aaps.plugins.automation.elements

import android.view.Gravity
import android.widget.LinearLayout
import app.aaps.core.ui.elements.NumberPicker
import java.text.DecimalFormat

/**
 * Like [InputWeight] but with a configurable range/step, so it can serve pp_ISF_weight
 * (0–0.15, fine step) and dura_ISF_weight (0–3.0) — InputWeight is hard-capped at 1.0/0.05.
 */
class InputWeightRanged(
    initial: Double = 0.0,
    private val minVal: Double = 0.0,
    private val maxVal: Double = 1.0,
    private val stepVal: Double = 0.05,
) : Element {

    var value = initial

    override fun addToLayout(root: LinearLayout) {
        root.addView(
            NumberPicker(root.context, null).also {
                it.setParams(value, minVal, maxVal, stepVal, DecimalFormat("0.00"), true, root.findViewById(app.aaps.core.ui.R.id.ok))
                it.setOnValueChangedListener { v: Double -> value = v }
                it.gravity = Gravity.CENTER_HORIZONTAL
            })
    }
}
