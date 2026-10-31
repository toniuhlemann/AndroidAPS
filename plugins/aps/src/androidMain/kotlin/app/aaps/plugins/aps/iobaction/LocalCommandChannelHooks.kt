package app.aaps.plugins.aps.iobaction

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The parts of the local command channel that the fork ran from MainApp (fork patches of the channel).
 *
 *  - [start]: two safety nets. Every write to an iobTH, SMB ratio or weight BASE preference bumps
 *    that capability's base generation (R9-F5), so a running lease notices a write from the settings
 *    screen or anybody else. And every write to the channel namespace makes the coordinator look at
 *    the gates at once (R12-F2), before the next snapshot.
 *  - [heartbeat]: the AUTOSTATE watch on the 60 s heartbeat (Re-Review B1/B2). It used to run inside
 *    the APS run, where it tied the dosing thread to the lock of the AutoState coordinator, which the
 *    binder thread holds over a whole Room transaction. Here it runs on its own, whether the loop
 *    calculates or not, and an error only hits itself.
 *
 * Since AAPS 4.0.0 the app preferences are in an encrypted store and the old SharedPreferences file
 * stays frozen, so the base settings are watched through [Preferences.observe]. A listener on that file
 * would stay registered and silently never fire again. Only the channel's own namespace is still a
 * SharedPreferences file.
 */
@SingleIn(AppScope::class)
@Inject
class LocalCommandChannelHooks(
    private val context: Context,
    private val preferences: Preferences,
    private val valueLeaseCoordinator: AutoIsfValueLeaseCoordinator,
    private val autoStateCoordinator: AutoStateLeaseCoordinator,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    // SharedPreferences keeps its listeners only weakly - this field is what keeps it alive.
    private var gateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Runs [action] on every change of [flow]. The first value of a StateFlow is the current one, not a write. */
    private fun onWrite(flow: Flow<*>, action: () -> Unit) {
        flow.drop(1).onEach { action() }.launchIn(appScope)
    }

    fun start() {
        if (gateListener != null) return
        onWrite(preferences.observe(IntKey.ApsAutoIsfIobThPercent)) { valueLeaseCoordinator.onExternalBaseWrite() }
        onWrite(preferences.observe(ValueOverlayPolicy.RATIO_KEY)) { valueLeaseCoordinator.onExternalBaseWrite(AutoIsfCapability.SMBRATIO) }
        ValueOverlayPolicy.WEIGHT_KEYS.values.forEach { key ->
            onWrite(preferences.observe(key)) { valueLeaseCoordinator.onExternalBaseWrite(AutoIsfCapability.WEIGHTS) }
        }
        gateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> valueLeaseCoordinator.onGateWrite() }
        context.getSharedPreferences(LocalCommandChannelPrefs.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(gateListener)
    }

    fun heartbeat() {
        runCatching { autoStateCoordinator.enforce(System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
    }
}
