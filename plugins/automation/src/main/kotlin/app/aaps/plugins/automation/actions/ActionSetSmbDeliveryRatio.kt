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
 * IOB Action viewer / FCL boost bridges: set the FIXED smb_delivery_ratio per Automation.
 * In fullLoop mode (even target < 100) the effective ratio is max(fixed, interpolated), so the
 * fixed value acts as a floor that BYPASSES the variable curve's max — a boost bridge can pulse
 * the delivery speed (e.g. 0.5) for the meal window and a reset guard restores the base (0.18).
 * Write-only preference (same safety class as the acce/pp/dura weight actions): the loop reads
 * it next cycle, no dosing code is touched.
 */
class ActionSetSmbDeliveryRatio(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var sp: SP

    override fun friendlyName(): Int = R.string.autoisf_smb_delivery_ratio
    override fun shortDescription(): String = rh.gs(R.string.automate_set_smb_delivery_ratio, new_ratio.value)
    @DrawableRes override fun icon(): Int = R.drawable.ic_acce_weight

    // Pref valid range 0.1–1.0 (DoubleKey.ApsAutoIsfSmbDeliveryRatio); 0.01 steps so the
    // 0.18 base value is reachable by the reset guard.
    var new_ratio = InputWeightRanged(minVal = 0.1, maxVal = 1.0, stepVal = 0.01)

    override fun doAction(callback: Callback) {
        val current: Double = sp.getDouble(R.string.key_openapsama_smb_delivery_ratio, 0.5)
        if (current != new_ratio.value) {
            uel.log(
                app.aaps.core.data.ue.Action.ACCE_WEIGHT_SET,
                Sources.Automation,
                title + ": " + rh.gs(R.string.automate_set_smb_delivery_ratio, new_ratio.value)
            )
            sp.putDouble(R.string.key_openapsama_smb_delivery_ratio, new_ratio.value)
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
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_smb_delivery_ratio), "", new_ratio))
            .build(root)
    }

    override fun toJSON(): String {
        val data = JSONObject()
            .put("ratio", new_ratio.value)
        return JSONObject()
            .put("type", this.javaClass.name)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        new_ratio.value = JsonHelper.safeGetDouble(o, "ratio")
        return this
    }

    override fun isValid(): Boolean = true
}
