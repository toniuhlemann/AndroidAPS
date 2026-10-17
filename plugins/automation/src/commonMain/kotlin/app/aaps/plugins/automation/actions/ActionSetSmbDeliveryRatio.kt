package app.aaps.plugins.automation.actions

import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientDouble
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Fork (IOB-Action viewer / FCL boost bridges): set the FIXED smb_delivery_ratio per automation.
 *
 * In full loop mode (even target below 100) the effective ratio is max(fixed, interpolated), so the
 * fixed value is a floor above the variable curve: a boost bridge can raise the delivery speed for
 * the meal window, and a reset guard puts the base back. Writes only the preference the loop reads
 * next cycle. Stored as {"ratio": x} like in the 3.4 fork.
 */
class ActionSetSmbDeliveryRatio(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    /** Inside [MIN] .. [MAX], so the picker never opens empty. */
    var newRatio = DoubleKey.ApsAutoIsfSmbDeliveryRatio.defaultValue

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_smb_delivery_ratio
    override fun shortDescription(): String = rh.gs(AutomationStrings.automate_set_smb_delivery_ratio, newRatio)
    override fun elementType() = ElementType.AAPS

    override suspend fun doAction(): PumpEnactResult {
        val current = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
        // Compare with a tolerance, not for equality: the stored value can come back with a float
        // round trip error, and an exact compare re-fired the reset guard every cycle (3.4 fork).
        return if (abs(current - newRatio) >= TOLERANCE) {
            uel.log(app.aaps.core.data.ue.Action.ACCE_WEIGHT_SET, Sources.Automation, title + ": " + rh.gs(AutomationStrings.automate_set_smb_delivery_ratio, newRatio))
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, newRatio)
            pumpEnactResultProvider().success(true).comment(AutomationStrings.weight_new)
        } else {
            pumpEnactResultProvider().success(false).comment(AutomationStrings.weight_old)
        }
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("ratio", newRatio) }
        return buildJsonObject {
            put("type", this@ActionSetSmbDeliveryRatio::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        newRatio = jsonOf(data).lenientDouble("ratio").coerceIn(MIN, MAX)
        return this
    }

    override fun isValid(): Boolean = true

    companion object {

        /** The valid range of DoubleKey.ApsAutoIsfSmbDeliveryRatio; 0.01 steps as in the 3.4 fork. */
        const val MIN = 0.1
        const val MAX = 1.0
        const val STEP = 0.01
        const val TOLERANCE = 0.005
    }
}
