package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.core.utils.JsonHelper.safeGetDouble
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDouble
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.Optional
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * IOB-Action patch 0041 (2026-07-12): trigger on the physiologic INSULIN COVERAGE — how much of
 * the current insulin NEED is already on board. One coverage number replaces the whole BG-band +
 * delta-tier ladder (a band is a proxy for "how much is missing"; coverage answers it directly and
 * scales itself with ISF / time-of-day / meal size).
 *
 *   need    = max(0.2U, (RAW_BG − PROFILE_target) / PROFILE_ISF  +  COB / PROFILE_IC)
 *   coverage% = 100 × max(bolusIOB, netIOB) / need
 *
 * Deliberate inputs (Toni 2026-07-12):
 *  - RAW bg (persistence last GV), not the smoothed loop value → leads the band-lag at turns.
 *  - PROFILE target, never the active TT → the trigger SETS iobTH/TTs; reading the active TT would
 *    be circular (a brake-TT would shrink the need, raise coverage, close the budget → deadlock).
 *  - PROFILE ISF/IC, not the per-cycle autoISF-modulated ISF → a stable denominator (otherwise an
 *    acce-cap moment would halve the ISF and flap coverage every cycle). Bernie's principle.
 *  - max(bolusIOB, netIOB) → withheld basal (negative basal-IOB in protective phases) never inflates
 *    coverage; positive basal-IOB still counts. Same asymmetry as the 0040 iobTH gate.
 *
 * FCL note: with no logged COB the need is UNDER-stated (denominator misses the absorbing meal), so
 * coverage is OVER-stated — meaning "coverage < 100 → open the budget" is conservatively safe: if
 * even the optimistic number says under-covered, insulin is genuinely missing.
 */
class TriggerCoverage(injector: HasAndroidInjector) : Trigger(injector) {

    // Coverage in percent; the same 0.2U need floor as the console line keeps this finite.
    var coverage: InputDouble = InputDouble(100.0, 0.0, 1000.0, 5.0, DecimalFormat("0"))
    var comparator: Comparator = Comparator(rh)

    private constructor(injector: HasAndroidInjector, triggerCoverage: TriggerCoverage) : this(injector) {
        coverage = InputDouble(triggerCoverage.coverage)
        comparator = Comparator(rh, triggerCoverage.comparator.value)
    }

    fun setValue(value: Double): TriggerCoverage {
        coverage.value = value
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerCoverage {
        this.comparator.value = comparator
        return this
    }

    /** Live coverage % from RAW bg + PROFILE isf/ic/target + max(bolusIOB, netIOB); null if no profile/bg. */
    private fun currentCoverage(): Int? {
        val profile = profileFunction.getProfile() ?: return null
        val rawBg = persistenceLayer.getLastGlucoseValue()?.value
            ?: glucoseStatusProvider.glucoseStatusData?.glucose
            ?: return null
        val isf = profile.getProfileIsfMgdl()
        val ic = profile.getIc()
        if (isf <= 0.0 || ic <= 0.0) return null
        val target = profile.getTargetMgdl()
        val cob = iobCobCalculator.getCobInfo("AutomationTriggerCoverage").displayCob ?: 0.0
        val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now(), profile)
        val gateIob = iob.iob - min(0.0, iob.basaliob)   // max(bolusIOB, netIOB)
        val need = max(0.2, (rawBg - target) / isf + cob / ic)
        return (100.0 * gateIob / need).roundToInt()
    }

    override fun shouldRun(): Boolean {
        val actual = currentCoverage()
        if (actual == null) {
            aapsLogger.debug(LTag.AUTOMATION, "Coverage not available: " + friendlyDescription())
            return false
        }
        if (comparator.value.check(actual.toDouble(), coverage.value)) {
            aapsLogger.debug(LTag.AUTOMATION, "Coverage $actual% ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "Coverage $actual% NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("coverage", coverage.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        coverage.setValue(safeGetDouble(d, "coverage"))
        comparator.setValue(Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!))
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_coverage

    override fun friendlyDescription(): String =
        rh.gs(R.string.coveragecompared, rh.gs(comparator.value.stringRes), coverage.value)

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_iobth)

    override fun duplicate(): Trigger = TriggerCoverage(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_coverage, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_coverage) + ": ", "%", coverage))
            .build(root)
    }
}
