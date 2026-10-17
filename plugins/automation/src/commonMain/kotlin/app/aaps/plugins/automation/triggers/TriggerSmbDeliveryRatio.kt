package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.actions.ActionSetSmbDeliveryRatio
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/**
 * Fork (IOB-Action viewer): compare the FIXED smb_delivery_ratio preference. The counterpart of
 * [ActionSetSmbDeliveryRatio], so a self-healing base guard can be built: "ratio > base AND no boost
 * TT -> set base". Stored as {"ratio", "comparator"} like in the 3.4 fork.
 */
class TriggerSmbDeliveryRatio(deps: TriggerDeps) : Trigger(deps) {

    /** Inside the valid range, so the picker never opens empty. */
    var ratio = DoubleKey.ApsAutoIsfSmbDeliveryRatio.defaultValue
    var comparator = Comparator(rh)

    constructor(deps: TriggerDeps, ratio: Double, compare: Comparator.Compare) : this(deps) {
        this.ratio = ratio
        comparator = Comparator(rh, compare)
    }

    constructor(deps: TriggerDeps, triggerSmbDeliveryRatio: TriggerSmbDeliveryRatio) : this(deps) {
        ratio = triggerSmbDeliveryRatio.ratio
        comparator = Comparator(rh, triggerSmbDeliveryRatio.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        // Rounded to 2 decimals: the stored value can come back with a float round trip error, and
        // without rounding "IS_GREATER base" stayed true by a tiny rest forever (3.4 fork).
        val actual = (deps.preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio) * 100.0).roundToLong() / 100.0
        if (comparator.value.check(actual, ratio)) {
            aapsLogger.debug(LTag.AUTOMATION, "smb_delivery_ratio ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "smb_delivery_ratio NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("ratio", ratio)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        ratio = d.lenientDouble("ratio").coerceIn(ActionSetSmbDeliveryRatio.MIN, ActionSetSmbDeliveryRatio.MAX)
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_smb_delivery_ratio

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.smbdeliveryratiocompared, rh.gs(comparator.value.stringRes), ratio)

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerSmbDeliveryRatio(deps, this)
}
