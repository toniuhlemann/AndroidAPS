package app.aaps.plugins.automation.triggers

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcBgCheck
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientString
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputBg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Fork (IOB-Action patch 0045): [TriggerBg] on the last STORED glucose value instead of the loop's
 * smoothed glucose status. [TriggerCoverage] computes on this value too, so the glucose edge of a
 * coverage rule runs on the same clock (with [TriggerBg] the falling edge lagged behind).
 *
 * Staleness: a value older than 9 minutes (the loop's own actual-BG convention) counts as NOT
 * AVAILABLE, exactly like [TriggerBg] when the glucose status expires.
 * Stored as {"bg", "comparator", "units"} like [TriggerBg] and the 3.4 fork.
 */
class TriggerBgRaw(deps: TriggerDeps) : Trigger(deps) {

    var bg = InputBg(profileFunction)
    var comparator = Comparator(rh)

    constructor(deps: TriggerDeps, value: Double, units: GlucoseUnit, compare: Comparator.Compare) : this(deps) {
        bg = InputBg(profileFunction, value, units)
        comparator = Comparator(rh, compare)
    }

    constructor(deps: TriggerDeps, triggerBgRaw: TriggerBgRaw) : this(deps) {
        bg = InputBg(profileFunction, triggerBgRaw.bg.value, triggerBgRaw.bg.units)
        comparator = Comparator(rh, triggerBgRaw.comparator.value)
    }

    private suspend fun currentRawBg(): Double? =
        persistenceLayer.getLastGlucoseValue()
            ?.takeIf { dateUtil.now() - it.timestamp <= T.mins(9).msecs() }
            ?.value

    override suspend fun shouldRun(): Boolean {
        val rawBg = currentRawBg()
        if (rawBg == null && comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        if (rawBg == null) {
            aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
            return false
        }
        if (comparator.value.check(rawBg.roundToInt(), profileUtil.convertToMgdl(bg.value, bg.units).roundToInt())) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("bg", bg.value)
            put("comparator", comparator.value.toString())
            put("units", bg.units.asText)
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        bg.setUnits(GlucoseUnit.fromText(d.lenientString("units", GlucoseUnit.MGDL.asText)))
        bg.value = d.lenientDouble("bg")
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_raw_bg

    override fun friendlyDescription(): String {
        return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE)
            rh.gs(AutomationStrings.rawbgisnotavailable)
        else
            rh.gs(if (bg.units == GlucoseUnit.MGDL) AutomationStrings.rawbgcomparedmgdl else AutomationStrings.rawbgcomparedmmol, rh.gs(comparator.value.stringRes), bg.value, bg.units)
    }

    override fun composeIcon() = IcBgCheck
    override fun elementType() = ElementType.BG_CHECK

    override fun duplicate(): Trigger = TriggerBgRaw(deps, this)
}
