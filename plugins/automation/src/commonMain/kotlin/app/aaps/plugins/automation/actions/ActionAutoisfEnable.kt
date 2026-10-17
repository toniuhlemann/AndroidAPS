package app.aaps.plugins.automation.actions

import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.automation.AutomationStrings

/** autoISF 3.2.1 (Tobias/ga-zelle): switch the autoISF glucose ISF weights on. */
class ActionAutoisfEnable(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.enableautoisf
    override fun shortDescription(): String = rh.gs(AutomationStrings.enableautoisf)
    override fun elementType() = ElementType.AAPS

    override suspend fun doAction(): PumpEnactResult =
        if (!preferences.get(BooleanKey.ApsUseAutoIsfWeights)) {
            uel.log(app.aaps.core.data.ue.Action.AUTOISF_ENABLED, Sources.Automation, title)
            preferences.put(BooleanKey.ApsUseAutoIsfWeights, true)
            pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_enabled)
        } else {
            pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_alreadyenabled)
        }

    override fun isValid(): Boolean = true
    override fun hasDialog(): Boolean = false
}
