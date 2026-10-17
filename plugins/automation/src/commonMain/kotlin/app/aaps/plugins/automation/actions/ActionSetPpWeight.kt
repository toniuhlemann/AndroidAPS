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

/**
 * Fork (IOB-Action viewer): set the autoISF pp_ISF_weight per automation (for example gentler on a
 * correction TT, stronger on a meal TT). Mirrors [ActionSetAcceWeight]. Writes only the preference
 * the loop reads each cycle. Stored as {"weight": x} like in the 3.4 fork.
 */
class ActionSetPpWeight(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    var newWeight = 0.0

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_pp_weight
    override fun shortDescription(): String = rh.gs(AutomationStrings.automate_set_pp_weight, newWeight)
    override fun elementType() = ElementType.AAPS

    override suspend fun doAction(): PumpEnactResult {
        val current = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
        return if (current != newWeight) {
            uel.log(app.aaps.core.data.ue.Action.ACCE_WEIGHT_SET, Sources.Automation, title + ": " + rh.gs(AutomationStrings.automate_set_pp_weight, newWeight))
            preferences.put(DoubleKey.ApsAutoIsfPpWeight, newWeight)
            pumpEnactResultProvider().success(true).comment(AutomationStrings.weight_new)
        } else {
            pumpEnactResultProvider().success(false).comment(AutomationStrings.weight_old)
        }
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("weight", newWeight) }
        return buildJsonObject {
            put("type", this@ActionSetPpWeight::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        newWeight = jsonOf(data).lenientDouble("weight")
        return this
    }

    override fun isValid(): Boolean = true

    companion object {

        /** As the picker of the 3.4 fork: up to 0.2, although the key declares 0.15. Preferences do not clamp. */
        const val MIN = 0.0
        const val MAX = 0.2
        const val STEP = 0.01
    }
}
