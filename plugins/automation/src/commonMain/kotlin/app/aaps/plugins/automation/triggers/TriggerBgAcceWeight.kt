package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** autoISF 3.2.1 (Tobias/ga-zelle): compare the bgAccel ISF weight. Stored as {"acce_weight", "comparator"}. */
class TriggerBgAcceWeight(deps: TriggerDeps) : Trigger(deps) {

    /** 0.0 .. 1.0, step 0.05. */
    var acceWeight = 0.0
    var comparator = Comparator(rh)

    constructor(deps: TriggerDeps, acceWeight: Double, compare: Comparator.Compare) : this(deps) {
        this.acceWeight = acceWeight
        comparator = Comparator(rh, compare)
    }

    constructor(deps: TriggerDeps, triggerBgAcceWeight: TriggerBgAcceWeight) : this(deps) {
        acceWeight = triggerBgAcceWeight.acceWeight
        comparator = Comparator(rh, triggerBgAcceWeight.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val actualWeight = deps.preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        if (comparator.value.check(actualWeight, acceWeight)) {
            aapsLogger.debug(LTag.AUTOMATION, "set bgAccel_ISF_weight ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "set bgAccel_ISF_weight NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("acce_weight", acceWeight)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        acceWeight = d.lenientDouble("acce_weight")
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_acce_weight

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.acceweightcompared, rh.gs(comparator.value.stringRes), acceWeight)

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerBgAcceWeight(deps, this)
}
