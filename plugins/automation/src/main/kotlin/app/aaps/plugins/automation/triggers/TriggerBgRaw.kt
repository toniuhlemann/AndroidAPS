package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputBg
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional
import kotlin.math.roundToInt

/**
 * IOB-Action patch 0045 (2026-07-12): TriggerBg on the RAW glucose value (last persisted GV)
 * instead of the loop's smoothed GlucoseStatus. TriggerCoverage deliberately computes on RAW
 * (leads the smoothed value by ~2-3min at turns with EMA alpha 0.3 on 1-min data), so BG edge
 * conditions guarding coverage rules (e.g. the below-target low-zone at 98) must run on the
 * SAME clock — with TriggerBg the falling edge lagged: raw already below target ("under-covered"
 * via the need floor) while the smoothed BG still gated the 40% budget open for 2-3 minutes.
 *
 * Staleness: a raw value older than 9min (the loop's own actualBg staleness convention) counts
 * as NOT AVAILABLE — conditions go false (or true for the IS_NOT_AVAILABLE comparator), exactly
 * like TriggerBg behaves when GlucoseStatus expires.
 */
class TriggerBgRaw(injector: HasAndroidInjector) : Trigger(injector) {

    var bg = InputBg(profileFunction)
    var comparator = Comparator(rh)

    constructor(injector: HasAndroidInjector, value: Double, units: GlucoseUnit, compare: Comparator.Compare) : this(injector) {
        bg = InputBg(profileFunction, value, units)
        comparator = Comparator(rh, compare)
    }

    constructor(injector: HasAndroidInjector, triggerBgRaw: TriggerBgRaw) : this(injector) {
        bg = InputBg(profileFunction, triggerBgRaw.bg.value, triggerBgRaw.bg.units)
        comparator = Comparator(rh, triggerBgRaw.comparator.value)
    }

    fun setUnits(units: GlucoseUnit): TriggerBgRaw {
        bg.units = units
        return this
    }

    fun setValue(value: Double): TriggerBgRaw {
        bg.value = value
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerBgRaw {
        this.comparator.value = comparator
        return this
    }

    private fun currentRawBg(): Double? =
        persistenceLayer.getLastGlucoseValue()
            ?.takeIf { dateUtil.now() - it.timestamp <= T.mins(9).msecs() }
            ?.value

    override fun shouldRun(): Boolean {
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

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("bg", bg.value)
            .put("comparator", comparator.value.toString())
            .put("units", bg.units.asText)

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        bg.setUnits(GlucoseUnit.fromText(JsonHelper.safeGetString(d, "units", GlucoseUnit.MGDL.asText)))
        bg.value = JsonHelper.safeGetDouble(d, "bg")
        comparator.setValue(Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!))
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_raw_bg

    override fun friendlyDescription(): String {
        return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE)
            rh.gs(R.string.rawbgisnotavailable)
        else
            rh.gs(if (bg.units == GlucoseUnit.MGDL) R.string.rawbgcomparedmgdl else R.string.rawbgcomparedmmol, rh.gs(comparator.value.stringRes), bg.value, bg.units)
    }

    override fun icon(): Optional<Int> = Optional.of(app.aaps.core.objects.R.drawable.ic_cp_bgcheck)

    override fun duplicate(): Trigger = TriggerBgRaw(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_raw_bg, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.glucose_u, bg.units), "", bg))
            .build(root)
    }
}
