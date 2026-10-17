package app.aaps.plugins.automation.actions

import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fork (IOB-Action viewer): set the profile percentage back to 100 % PERMANENTLY (duration 0).
 *
 * [ActionProfileSwitchPercent] cannot do this: its precondition (profile percent == 100) blocks it
 * while a percentage is active, and its isValid() forbids duration 0. So a timed boost switch could
 * only end lazily, and no guard automation could clean it up. This action has no precondition (the
 * guard's own trigger decides when it fires; firing at 100 % only re-asserts the base profile).
 * It writes through the same profile switch path as the stock action.
 */
class ActionResetProfilePercent(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val profileFunction: ProfileFunction
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.automate_reset_profile_percent
    override fun shortDescription(): String = rh.gs(AutomationStrings.automate_reset_profile_percent)

    override fun composeIcon() = IcProfile
    override fun elementType() = ElementType.PROFILE_MANAGEMENT

    override suspend fun doAction(): PumpEnactResult {
        val switched = profileFunction.createProfileSwitch(
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            action = app.aaps.core.data.ue.Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = title + ": " + rh.gs(AutomationStrings.automate_reset_profile_percent),
            listValues = listOf(
                ValueWithUnit.Percent(100),
                ValueWithUnit.Minute(0)
            )
        ) != null
        return if (switched) {
            pumpEnactResultProvider().success(true).comment(CoreUiStrings.ok)
        } else {
            val reason = profileFunction.profileSwitchRefusal(0, 100, 0) ?: rh.gs(CoreUiStrings.invalid_profile)
            aapsLogger.error(LTag.AUTOMATION, "Final profile not valid: $reason")
            pumpEnactResultProvider().success(false).comment(reason)
        }
    }

    override fun toJSON(): String =
        buildJsonObject {
            put("type", this@ActionResetProfilePercent::class.simpleName)
            put("data", buildJsonObject { })
        }.toString()

    override fun fromJSON(data: String): Action = this

    override fun isValid(): Boolean = true
}
