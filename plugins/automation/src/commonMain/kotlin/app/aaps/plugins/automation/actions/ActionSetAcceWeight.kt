package app.aaps.plugins.automation.actions

import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientDouble
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** autoISF 3.2.1 (Tobias/ga-zelle): set the bgAccel ISF weight. Stored as {"weight": x} like in 3.4. */
class ActionSetAcceWeight(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    /** 0.0 .. 1.0, step 0.05, as the number picker of 3.4. */
    var newWeight = 0.0

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_acce_weight
    override fun shortDescription(): String = rh.gs(AutomationStrings.automate_set_acce_weight, newWeight)
    override fun elementType() = ElementType.AAPS

    override suspend fun doAction(): PumpEnactResult {
        val current = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        return if (current != newWeight) {
            uel.log(app.aaps.core.data.ue.Action.ACCE_WEIGHT_SET, Sources.Automation, title + ": " + rh.gs(AutomationStrings.automate_set_acce_weight, newWeight))
            preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, newWeight)
            pumpEnactResultProvider().success(true).comment(AutomationStrings.weight_new)
        } else {
            pumpEnactResultProvider().success(false).comment(AutomationStrings.weight_old)
        }
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("weight", newWeight) }
        return buildJsonObject {
            put("type", this@ActionSetAcceWeight::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        newWeight = jsonOf(data).lenientDouble("weight")
        return this
    }

    override fun isValid(): Boolean = true

    companion object {

        const val MIN = 0.0
        const val MAX = 1.0
        const val STEP = 0.05
    }
}
