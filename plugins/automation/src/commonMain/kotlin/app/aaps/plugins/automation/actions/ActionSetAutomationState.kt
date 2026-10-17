package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * autoISF 3.2.x (Tobias/ga-zelle): set a named automation state to one of its allowed values.
 * Stored as {"inputStateName": .., "inputState": ..} like in 3.4.
 */
class ActionSetAutomationState(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val automationState: AutomationStateInterface
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    var stateName = ""
    var stateValue = ""

    /** The defined states, for the editor. */
    fun stateNames(): List<String> = automationState.getAllStates().map { it.first }.distinct()

    /** The allowed values of [name], for the editor. */
    fun stateValues(name: String): List<String> = if (name.isEmpty()) emptyList() else automationState.getStateValues(name)

    override fun friendlyName(): TextRef = AutomationStrings.set_state
    override fun shortDescription(): String = rh.gs(AutomationStrings.set_state_description, stateName, stateValue)
    override fun elementType() = ElementType.AAPS

    override fun isValid(): Boolean = stateName.isNotEmpty() && stateValue.isNotEmpty()

    override suspend fun doAction(): PumpEnactResult {
        automationState.setState(stateName, stateValue)
        return pumpEnactResultProvider().success(true).comment(CoreUiStrings.ok)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject {
            put("inputStateName", stateName)
            put("inputState", stateValue)
        }
        return buildJsonObject {
            put("type", this@ActionSetAutomationState::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        val o = jsonOf(data)
        stateName = o.lenientString("inputStateName", "")
        // As in 3.4: the value is only taken when the state is defined.
        stateValue = if (automationState.hasStateValues(stateName)) o.lenientString("inputState", "") else ""
        return this
    }
}
