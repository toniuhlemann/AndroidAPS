package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fork (IOB-Action patch 0041): trigger on the INSULIN COVERAGE - how much of the current insulin
 * need is already on board. One number instead of a ladder of glucose bands and delta tiers.
 *
 *   coverage % = 100 x max(bolus IOB, net IOB) / need       (need: see [CoverageNeed])
 *
 * max(bolus IOB, net IOB): withheld basal (negative basal IOB in protective phases) never raises the
 * coverage; positive basal IOB still counts. Without logged COB the need is under-stated, so the
 * coverage is over-stated - "coverage < 100 -> open" stays on the safe side.
 * Stored as {"coverage", "comparator"} like in the 3.4 fork.
 */
class TriggerCoverage(deps: TriggerDeps) : Trigger(deps) {

    /** Percent, 0 .. 1000, step 5. */
    var coverage = 100.0
    var comparator = Comparator(rh)

    constructor(deps: TriggerDeps, coverage: Double, compare: Comparator.Compare) : this(deps) {
        this.coverage = coverage
        comparator = Comparator(rh, compare)
    }

    constructor(deps: TriggerDeps, triggerCoverage: TriggerCoverage) : this(deps) {
        coverage = triggerCoverage.coverage
        comparator = Comparator(rh, triggerCoverage.comparator.value)
    }

    /** Live coverage in percent; null without profile or glucose. */
    private suspend fun currentCoverage(): Int? {
        val input = CoverageNeed.of(deps, "AutomationTriggerCoverage") ?: return null
        val gateIob = input.iob.iob - min(0.0, input.iob.basaliob)   // max(bolus IOB, net IOB)
        return (100.0 * gateIob / input.need).roundToInt()
    }

    override suspend fun shouldRun(): Boolean {
        val actual = currentCoverage()
        if (actual == null) {
            aapsLogger.debug(LTag.AUTOMATION, "Coverage not available: " + friendlyDescription())
            return false
        }
        if (comparator.value.check(actual.toDouble(), coverage)) {
            aapsLogger.debug(LTag.AUTOMATION, "Coverage $actual% ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "Coverage $actual% NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("coverage", coverage)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        coverage = d.lenientDouble("coverage")
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_coverage

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.coveragecompared, rh.gs(comparator.value.stringRes), coverage)

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerCoverage(deps, this)

    companion object {

        const val MIN = 0.0
        const val MAX = 1000.0
        const val STEP = 5.0
    }
}
