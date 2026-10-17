package app.aaps.plugins.automation.actions

import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientInt
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** autoISF 3.2.1 (Tobias/ga-zelle): set the iobTH percent. Stored as {"percentage": n} like in 3.4. */
class ActionSetIobTH(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    /** 10 .. 100 %, step 5, as the number picker of 3.4. */
    var newIobTH = 100

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_iobTH_percent
    override fun shortDescription(): String = rh.gs(AutomationStrings.automate_set_iobTH_percent, newIobTH)
    override fun elementType() = ElementType.AAPS

    override suspend fun doAction(): PumpEnactResult {
        val current = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        return if (current != newIobTH) {
            uel.log(
                app.aaps.core.data.ue.Action.IOB_TH_SET,
                Sources.Automation,
                title + ": " + rh.gs(AutomationStrings.automate_set_iobTH_percent, newIobTH),
                ValueWithUnit.Percent(newIobTH)
            )
            preferences.put(IntKey.ApsAutoIsfIobThPercent, newIobTH)
            pumpEnactResultProvider().success(true).comment(AutomationStrings.weight_new)
        } else {
            pumpEnactResultProvider().success(false).comment(AutomationStrings.weight_old)
        }
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("percentage", newIobTH) }
        return buildJsonObject {
            put("type", this@ActionSetIobTH::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        newIobTH = jsonOf(data).lenientInt("percentage")
        return this
    }

    override fun isValid(): Boolean = true

    companion object {

        const val MIN = 10
        const val MAX = 100
        const val STEP = 5
    }
}
