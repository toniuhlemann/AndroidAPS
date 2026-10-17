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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fork (IOB-Action patch 0048): BOLUS coverage - [TriggerCoverage] with only the BOLUS IOB on top.
 * Same need (see [CoverageNeed]), same 9 minute staleness.
 *
 * Why two coverage triggers: bolus IOB is the insulin that cannot be taken back, basal IOB is a
 * credit the loop can still offset with zero temps. max(bolus, net) coverage is right for CLOSING a
 * budget, but as an OPENING criterion it locks itself under a high temp: the temp stacks basal IOB
 * faster than glucose rises. So rules open on a bolus coverage below 100 and close on a high
 * coverage AND a bolus coverage of at least 100. Stored as {"coverage", "comparator"} like in the 3.4 fork.
 */
class TriggerBolusCoverage(deps: TriggerDeps) : Trigger(deps) {

    /** Percent, 0 .. 1000, step 5. */
    var coverage = 100.0
    var comparator = Comparator(rh)

    constructor(deps: TriggerDeps, coverage: Double, compare: Comparator.Compare) : this(deps) {
        this.coverage = coverage
        comparator = Comparator(rh, compare)
    }

    constructor(deps: TriggerDeps, triggerBolusCoverage: TriggerBolusCoverage) : this(deps) {
        coverage = triggerBolusCoverage.coverage
        comparator = Comparator(rh, triggerBolusCoverage.comparator.value)
    }

    /** Live bolus coverage in percent; null without profile or glucose. */
    private suspend fun currentBolusCoverage(): Int? {
        val input = CoverageNeed.of(deps, "AutomationTriggerBolusCoverage") ?: return null
        val bolusIob = max(0.0, input.iob.iob - input.iob.basaliob)   // net minus basal = bolus IOB
        return (100.0 * bolusIob / input.need).roundToInt()
    }

    override suspend fun shouldRun(): Boolean {
        val actual = currentBolusCoverage()
        if (actual == null) {
            aapsLogger.debug(LTag.AUTOMATION, "BolusCoverage not available: " + friendlyDescription())
            return false
        }
        if (comparator.value.check(actual.toDouble(), coverage)) {
            aapsLogger.debug(LTag.AUTOMATION, "BolusCoverage $actual% ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "BolusCoverage $actual% NOT ready for execution: " + friendlyDescription())
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

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_bolus_coverage

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.boluscoveragecompared, rh.gs(comparator.value.stringRes), coverage)

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerBolusCoverage(deps, this)
}
