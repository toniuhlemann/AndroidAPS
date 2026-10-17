package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.ComparatorExists
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fork (capability matrix A1): "local iobTH lease active" - a read-only view of the local iobTH
 * lease, from the SAME provider as the APS, the exports and the channel status (never a second
 * truth). Use: opening guards also bind to NOT_EXISTS; protective lowering rules deliberately do
 * NOT get this lock (protection always wins - their write ends the lease, fail-safe).
 * While no lease exists, EXISTS is always false. Stored as {"comparator"} like in the 3.4 fork.
 */
class TriggerAutoIsfLeaseActive(deps: TriggerDeps) : Trigger(deps) {

    var comparator = ComparatorExists(rh)

    constructor(deps: TriggerDeps, compare: ComparatorExists.Compare) : this(deps) {
        comparator = ComparatorExists(rh, compare)
    }

    constructor(deps: TriggerDeps, other: TriggerAutoIsfLeaseActive) : this(deps) {
        comparator = ComparatorExists(rh, other.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val active = deps.effectiveAutoIsfSettingsProvider.isIobThLeaseActive()
        val ready = (active && comparator.value == ComparatorExists.Compare.EXISTS) ||
            (!active && comparator.value == ComparatorExists.Compare.NOT_EXISTS)
        aapsLogger.debug(LTag.AUTOMATION, (if (ready) "Ready" else "NOT ready") + " for execution: " + friendlyDescription())
        return ready
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        comparator.value = ComparatorExists.Compare.valueOf(d.lenientStringOrNull("comparator")!!)
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_iobth_lease

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.iobthleasecompared, rh.gs(comparator.value.stringRes))

    override fun elementType() = ElementType.AAPS

    override fun duplicate(): Trigger = TriggerAutoIsfLeaseActive(deps, this)
}
