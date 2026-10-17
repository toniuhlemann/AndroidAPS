package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * autoISF 3.2.x (Tobias/ga-zelle): true while a named automation state has the given value.
 * Stored as {"stateName", "stateValue"} like in 3.4.
 */
class TriggerAutomationState(deps: TriggerDeps) : Trigger(deps) {

    var stateName = ""
    var stateValue = ""

    private val automationState get() = deps.automationState

    constructor(deps: TriggerDeps, stateName: String, stateValue: String) : this(deps) {
        this.stateName = stateName
        this.stateValue = stateValue
    }

    /** The defined states, for the editor. */
    fun stateNames(): List<String> = automationState.getAllStates().map { it.first }.distinct()

    /** The allowed values of [name], for the editor. */
    fun stateValues(name: String): List<String> = if (name.isEmpty()) emptyList() else automationState.getStateValues(name)

    override suspend fun shouldRun(): Boolean {
        if (automationState.inState(stateName, stateValue)) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("stateName", stateName)
            put("stateValue", stateValue)
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        stateName = d.lenientString("stateName", "")
        // As in 3.4: the value is only taken when the state is defined.
        stateValue = if (automationState.hasStateValues(stateName)) d.lenientString("stateValue", "") else ""
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.check_state_name

    override fun friendlyDescription(): String = rh.gs(AutomationStrings.check_state_description, stateName, stateValue)

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerAutomationState(deps, stateName, stateValue)
}
