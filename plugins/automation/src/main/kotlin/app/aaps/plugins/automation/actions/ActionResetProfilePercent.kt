package app.aaps.plugins.automation.actions

import androidx.annotation.DrawableRes
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.automation.R
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

/**
 * Reset the profile percentage to 100% PERMANENTLY (duration 0).
 *
 * [ActionProfileSwitchPercent] cannot do this: its hardcoded precondition
 * (TriggerProfilePercent == 100) blocks it whenever a percentage is ACTIVE, and its
 * isValid() forbids duration 0. So an expired timed boost switch (e.g. 130% for a meal
 * boost window) can only revert LAZILY (3-5 min observed) and no guard automation could
 * actively clean it up. This action is the counterpart: no precondition (the guard's own
 * trigger, e.g. "profile percent == 130 AND no boost TT", controls when it fires — and
 * firing at 100% would merely re-assert the base profile), fixed 100%, permanent.
 * Write-side only via the same createProfileSwitch path the stock action uses.
 */
class ActionResetProfilePercent(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var profileFunction: ProfileFunction

    override fun friendlyName(): Int = R.string.automate_reset_profile_percent
    override fun shortDescription(): String = rh.gs(R.string.automate_reset_profile_percent)

    @DrawableRes override fun icon(): Int = app.aaps.core.ui.R.drawable.ic_actions_profileswitch_24dp

    override fun doAction(callback: Callback) {
        if (profileFunction.createProfileSwitch(
                durationInMinutes = 0,
                percentage = 100,
                timeShiftInHours = 0,
                action = app.aaps.core.data.ue.Action.PROFILE_SWITCH,
                source = Sources.Automation,
                note = title + ": " + rh.gs(R.string.automate_reset_profile_percent),
                listValues = listOf(
                    ValueWithUnit.Percent(100),
                    ValueWithUnit.Minute(0)
                )
            )
        ) {
            callback.result(pumpEnactResultProvider.get().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
        } else {
            aapsLogger.error(LTag.AUTOMATION, "Final profile not valid")
            callback.result(pumpEnactResultProvider.get().success(false).comment(app.aaps.core.ui.R.string.ok)).run()
        }
    }

    override fun toJSON(): String =
        JSONObject().put("type", this.javaClass.simpleName).put("data", JSONObject()).toString()

    override fun fromJSON(data: String): Action = this

    override fun isValid(): Boolean = true
}
