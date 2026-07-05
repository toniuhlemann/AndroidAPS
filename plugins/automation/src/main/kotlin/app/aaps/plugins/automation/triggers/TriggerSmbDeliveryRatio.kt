package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import java.util.Optional
import dagger.android.HasAndroidInjector
import app.aaps.plugins.automation.R
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputWeightRanged
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import app.aaps.core.utils.JsonHelper
import org.json.JSONObject
import javax.inject.Inject

/**
 * Trigger on the FIXED smb_delivery_ratio preference — the counterpart to
 * [app.aaps.plugins.automation.actions.ActionSetSmbDeliveryRatio], so a self-healing base guard
 * can exist in the idempotent iobTH-band pattern: "ratio > base AND no boost TT → set base".
 * Without this trigger a stuck ratio pulse could only be caught by time/state conditions.
 */
class TriggerSmbDeliveryRatio(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var sp: SP

    var ratio = InputWeightRanged(minVal = 0.1, maxVal = 1.0, stepVal = 0.01)
    var comparator = Comparator(rh)

    constructor(injector: HasAndroidInjector, triggerSmbDeliveryRatio: TriggerSmbDeliveryRatio) : this(injector) {
        this.ratio = InputWeightRanged(triggerSmbDeliveryRatio.ratio.value, minVal = 0.1, maxVal = 1.0, stepVal = 0.01)
        comparator = Comparator(rh, triggerSmbDeliveryRatio.comparator.value)
    }

    fun setValue(ratio: Double): TriggerSmbDeliveryRatio {
        this.ratio.value = ratio
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerSmbDeliveryRatio {
        this.comparator.value = comparator
        return this
    }

    override fun shouldRun(): Boolean {
        val actual = sp.getDouble(R.string.key_openapsama_smb_delivery_ratio, 0.5)
        if (comparator.value.check(actual, ratio.value)) {
            aapsLogger.debug(LTag.AUTOMATION, "smb_delivery_ratio ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "smb_delivery_ratio NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("ratio", ratio.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        ratio.value = JsonHelper.safeGetDouble(d, "ratio")
        comparator.value = Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!)
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_smb_delivery_ratio

    override fun friendlyDescription(): String =
        rh.gs(R.string.smbdeliveryratiocompared, rh.gs(comparator.value.stringRes), ratio.value)

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_acce_weight)

    override fun duplicate(): Trigger = TriggerSmbDeliveryRatio(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_smb_delivery_ratio, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_smb_delivery_ratio) + ": ", "", ratio))
            .build(root)
    }
}
