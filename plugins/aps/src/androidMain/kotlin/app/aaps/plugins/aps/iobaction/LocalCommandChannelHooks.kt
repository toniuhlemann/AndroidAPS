package app.aaps.plugins.aps.iobaction

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.keys.IntKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The parts of the local command channel that the fork ran from MainApp (fork patches of the channel).
 *
 *  - [start]: two SharedPreferences safety nets. Every write to an iobTH, SMB ratio or weight BASE
 *    preference bumps that capability's base generation (R9-F5), so a running lease notices a write
 *    from the settings screen or anybody else. And every write to the channel namespace makes the
 *    coordinator look at the gates at once (R12-F2), before the next snapshot.
 *  - [heartbeat]: the AUTOSTATE watch on the 60 s heartbeat (Re-Review B1/B2). It used to run inside
 *    the APS run, where it tied the dosing thread to the lock of the AutoState coordinator, which the
 *    binder thread holds over a whole Room transaction. Here it runs on its own, whether the loop
 *    calculates or not, and an error only hits itself.
 *
 * The app preferences are the SharedPreferences file `<package>_preferences` (SPImpl in :shared:impl),
 * which is what Android also returns as the default shared preferences.
 */
@SingleIn(AppScope::class)
@Inject
class LocalCommandChannelHooks(
    private val context: Context,
    private val valueLeaseCoordinator: AutoIsfValueLeaseCoordinator,
    private val autoStateCoordinator: AutoStateLeaseCoordinator,
) {

    private val weightKeys: Set<String> = ValueOverlayPolicy.WEIGHT_KEYS.values.map { it.key }.toSet()

    // SharedPreferences keeps its listeners only weakly - these fields are what keeps them alive.
    private var baseListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var gateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun start() {
        if (baseListener != null) return
        baseListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                key == IntKey.ApsAutoIsfIobThPercent.key  -> valueLeaseCoordinator.onExternalBaseWrite()
                key == ValueOverlayPolicy.RATIO_KEY.key   -> valueLeaseCoordinator.onExternalBaseWrite(AutoIsfCapability.SMBRATIO)
                key != null && key in weightKeys          -> valueLeaseCoordinator.onExternalBaseWrite(AutoIsfCapability.WEIGHTS)
            }
        }
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(baseListener)
        gateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> valueLeaseCoordinator.onGateWrite() }
        context.getSharedPreferences(LocalCommandChannelPrefs.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(gateListener)
    }

    fun heartbeat() {
        runCatching { autoStateCoordinator.enforce(System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
    }
}
