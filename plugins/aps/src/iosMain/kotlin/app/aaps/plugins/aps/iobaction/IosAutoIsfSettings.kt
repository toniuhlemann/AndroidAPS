package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfValueLeaseInvalidator
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/** iOS has no local command channel: the effective autoISF settings are the base ones. */
// Two interfaces from one object, so @ContributesBinding is repeated with an explicit bound type.
@ContributesBinding(AppScope::class, binding = binding<EffectiveAutoIsfSettingsProvider>())
@ContributesBinding(AppScope::class, binding = binding<AutoIsfValueLeaseInvalidator>())
@SingleIn(AppScope::class)
@Inject
class IosAutoIsfSettings(preferences: Preferences) : AutoIsfSettingsWithoutChannel(preferences)
