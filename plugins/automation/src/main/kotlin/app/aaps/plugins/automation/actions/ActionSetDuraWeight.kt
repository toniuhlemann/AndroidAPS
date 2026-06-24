package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import dagger.android.HasAndroidInjector
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.elements.InputWeightRanged
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.R
import org.json.JSONObject
import javax.inject.Inject

/**
 * Set the autoISF dura_ISF_weight per automation (e.g. gentler on a correction TT, stronger
 * on a meal TT). Mirrors [ActionSetAcceWeight]; dura ranges 0..3.0 (DoubleKey max) so it uses
 * [InputWeightRanged] (InputWeight is hard-capped at 1.0). Write-only to the preference the
 * loop already reads each cycle; no determine-basal change.
 */
class ActionSetDuraWeight(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var sp: SP

    override fun friendlyName(): Int = R.string.autoisf_dura_weight
    override fun shortDescription(): String = rh.gs(R.string.automate_set_dura_weight, new_weight.value)
    @DrawableRes override fun icon(): Int = R.drawable.ic_acce_weight

    var new_weight = InputWeightRanged(minVal = 0.0, maxVal = 3.0, stepVal = 0.1)

    override fun doAction(callback: Callback) {
        val currentDuraWeight: Double = sp.getDouble(R.string.dura_ISF_weight, 0.0)
        if (currentDuraWeight != new_weight.value) {
            uel.log(
                app.aaps.core.data.ue.Action.ACCE_WEIGHT_SET,
                Sources.Automation,
                title + ": " + rh.gs(R.string.automate_set_dura_weight, new_weight.value)
            )
            sp.putDouble(R.string.dura_ISF_weight, new_weight.value)
            callback.result(pumpEnactResultProvider.get().success(true).comment(R.string.weight_new)).run()
        } else {
            callback.result(pumpEnactResultProvider.get().success(false).comment(R.string.weight_old)).run()
        }
    }

    override fun hasDialog(): Boolean {
        return true
    }

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_dura_weight), "", new_weight))
            .build(root)
    }

    override fun toJSON(): String {
        val data = JSONObject()
            .put("weight", new_weight.value)
        return JSONObject()
            .put("type", this.javaClass.name)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        new_weight.value = JsonHelper.safeGetDouble(o, "weight")
        return this
    }
    override fun isValid(): Boolean = true
}
