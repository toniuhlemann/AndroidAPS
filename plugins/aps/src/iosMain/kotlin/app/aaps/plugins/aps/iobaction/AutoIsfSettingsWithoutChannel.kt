package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.AutoIsfValueLeaseInvalidator
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * The effective autoISF settings on iOS, which has no local command channel (the channel is an
 * Android service, see AutoIsfValueLeaseCoordinator in androidMain).
 *
 * Without the channel no lease can exist, so this is exactly the channel-off state of the fork:
 * effective == base, no lease is ever active, and invalidation before a write is the safe no-op
 * (true).
 */
// Two interfaces from one object, so @ContributesBinding is repeated with an explicit bound type.
@ContributesBinding(AppScope::class, binding = binding<EffectiveAutoIsfSettingsProvider>())
@ContributesBinding(AppScope::class, binding = binding<AutoIsfValueLeaseInvalidator>())
@SingleIn(AppScope::class)
@Inject
class AutoIsfSettingsWithoutChannel(
    private val preferences: Preferences
) : EffectiveAutoIsfSettingsProvider, AutoIsfValueLeaseInvalidator {

    override fun snapshot(): EffectiveAutoIsfSettingsProvider.Snapshot {
        val base = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        return EffectiveAutoIsfSettingsProvider.Snapshot(
            iobThPercentBase = base,
            iobThPercentEffective = base,
            overrideState = AutoIsfOverrideState.NONE,
            leaseId = null,
            leaseVersion = null,
            expiresAtWallMs = null
        )
    }

    override fun isIobThLeaseActive(): Boolean = false

    override fun invalidateBeforeExternalWrite(capability: AutoIsfCapability, reason: String): Boolean = true
}
