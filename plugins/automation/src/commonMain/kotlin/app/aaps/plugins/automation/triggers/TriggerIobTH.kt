package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientInt
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** autoISF 3.2.1 (Tobias/ga-zelle): compare the iobTH percent setting. Stored as {"iobTH_percent", "comparator"}. */
class TriggerIobTH(deps: TriggerDeps) : Trigger(deps) {

    /** 10 .. 100 %, step 5. */
    var iobTHpercent = 100
    var comparator = Comparator(rh)

    constructor(deps: TriggerDeps, iobTHpercent: Int, compare: Comparator.Compare) : this(deps) {
        this.iobTHpercent = iobTHpercent
        comparator = Comparator(rh, compare)
    }

    constructor(deps: TriggerDeps, triggerIobTH: TriggerIobTH) : this(deps) {
        iobTHpercent = triggerIobTH.iobTHpercent
        comparator = Comparator(rh, triggerIobTH.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val actualPercent = deps.preferences.get(IntKey.ApsAutoIsfIobThPercent)
        if (comparator.value.check(actualPercent, iobTHpercent)) {
            aapsLogger.debug(LTag.AUTOMATION, "set iob_threshold_percent ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "set iob_threshold_percent NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("iobTH_percent", iobTHpercent)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        iobTHpercent = d.lenientInt("iobTH_percent")
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_iobTH_percent

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.iobTHpercentcompared, rh.gs(comparator.value.stringRes), iobTHpercent)

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerIobTH(deps, this)

    companion object {

        const val MIN = 10
        const val MAX = 100
        const val STEP = 5
    }
}
