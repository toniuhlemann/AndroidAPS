package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.AutoIsfValueLeaseInvalidator
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * The effective autoISF settings on a target without the local command channel - iOS and the desktop. The
 * channel is an Android service (see AutoIsfValueLeaseCoordinator in androidMain), which binds both interfaces
 * itself; each other target binds a subclass of this one.
 *
 * Without the channel no lease can exist, so this is exactly the channel-off state of the fork:
 * effective == base, no lease is ever active, and invalidation before a write is the safe no-op
 * (true).
 */
open class AutoIsfSettingsWithoutChannel(
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
