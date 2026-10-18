package app.aaps.plugins.automation.states

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.di.APS
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.plugins.automation.AutomationStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * The "Automation states" tab of the 3.4 fork, rebuilt in Compose on [AutomationStateService]: the list of
 * states with their current value, set a value by hand, add and delete states, edit the allowed values.
 *
 * Same class name as the fork's plugin on purpose: the Config Builder stores the on/off switch under the
 * class name, so a user who had the tab switched on keeps it on.
 *
 * In the `@APS` bucket, so only a build that runs the loop has it - automations run only there, and the
 * states are not synced to a client. Order 252 as in the fork, after where Automation (250) used to be.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@APS
@IntKey(252)
@SingleIn(AppScope::class)
@Inject
class AutomationStatePlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    notificationManager: NotificationManager,
    automationState: AutomationStateInterface,
    preferences: Preferences,
    rxBus: RxBus
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .composeContent { AutomationStatesContent(automationState, preferences, rxBus, rh) }
        .icon(IcAutomation)
        .pluginName(AutomationStrings.automation_states)
        .description(AutomationStrings.description_automation_states),
    aapsLogger, rh, notificationManager
)
