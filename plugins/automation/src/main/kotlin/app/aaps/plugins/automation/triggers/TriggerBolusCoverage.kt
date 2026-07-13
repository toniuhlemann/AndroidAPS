package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.data.time.T
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
import kotlin.math.roundToInt

/**
 * IOB-Action patch 0048 (2026-07-13): BOLUS coverage — TriggerCoverage's sibling with the
 * numerator restricted to BOLUS-IOB. Same need denominator (RAW bg, PROFILE target/ISF/IC,
 * 0.2U floor), same 9-min staleness.
 *
 * Why two coverage triggers (live case 13.07 22:14, raw 166 rising +1.8/min): bolus-IOB is the
 * IRREVOCABLE insulin, basal-IOB a refundable credit the loop can offset with future zero-temps.
 * The max(bolus,net) coverage is right for CLOSING the budget (hidden credit counts), but as an
 * OPENING criterion it self-locks under TBR correction: the high temp stacks basal-IOB at
 * ~0.04U/min, the opening threshold recedes ~2.6 mg/dl/min — faster than BG rises. Division of
 * labour: LOW rules open on BolusCoverage < 100 ("firm insulin missing"), HIGH rules close on
 * max()-Coverage >= ~200 AND BolusCoverage >= 100 (arbitration: opening wins while irrevocable
 * insulin is missing).
 */
class TriggerBolusCoverage(injector: HasAndroidInjector) : Trigger(injector) {

    var coverage: InputDouble = InputDouble(100.0, 0.0, 1000.0, 5.0, DecimalFormat("0"))
    var comparator: Comparator = Comparator(rh)

    private constructor(injector: HasAndroidInjector, triggerBolusCoverage: TriggerBolusCoverage) : this(injector) {
        coverage = InputDouble(triggerBolusCoverage.coverage)
        comparator = Comparator(rh, triggerBolusCoverage.comparator.value)
    }

    fun setValue(value: Double): TriggerBolusCoverage {
        coverage.value = value
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerBolusCoverage {
        this.comparator.value = comparator
        return this
    }

    /** Live bolus coverage % = bolusIOB / need; null if no profile or no fresh bg. */
    private fun currentBolusCoverage(): Int? {
        val profile = profileFunction.getProfile() ?: return null
        val rawBg = persistenceLayer.getLastGlucoseValue()
            ?.takeIf { dateUtil.now() - it.timestamp <= T.mins(9).msecs() }?.value
            ?: glucoseStatusProvider.glucoseStatusData?.glucose
            ?: return null
        val isf = profile.getProfileIsfMgdl()
        val ic = profile.getIc()
        if (isf <= 0.0 || ic <= 0.0) return null
        val target = profile.getTargetMgdl()
        val cob = iobCobCalculator.getCobInfo("AutomationTriggerBolusCoverage").displayCob ?: 0.0
        val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now(), profile)
        val bolusIob = max(0.0, iob.iob - iob.basaliob)   // net minus basal component = bolus-IOB
        val need = max(0.2, (rawBg - target) / isf + cob / ic)
        return (100.0 * bolusIob / need).roundToInt()
    }

    override fun shouldRun(): Boolean {
        val actual = currentBolusCoverage()
        if (actual == null) {
            aapsLogger.debug(LTag.AUTOMATION, "BolusCoverage not available: " + friendlyDescription())
            return false
        }
        if (comparator.value.check(actual.toDouble(), coverage.value)) {
            aapsLogger.debug(LTag.AUTOMATION, "BolusCoverage $actual% ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "BolusCoverage $actual% NOT ready for execution: " + friendlyDescription())
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

    override fun friendlyName(): Int = R.string.autoisf_bolus_coverage

    override fun friendlyDescription(): String =
        rh.gs(R.string.boluscoveragecompared, rh.gs(comparator.value.stringRes), coverage.value)

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_iobth)

    override fun duplicate(): Trigger = TriggerBolusCoverage(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_bolus_coverage, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_bolus_coverage) + ": ", "%", coverage))
            .build(root)
    }
}
