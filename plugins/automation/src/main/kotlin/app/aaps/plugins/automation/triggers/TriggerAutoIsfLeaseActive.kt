package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.ComparatorExists
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional
import javax.inject.Inject

/**
 * Capability-Matrix A1 (R9-F4): LOCAL_IOBTH_LEASE_ACTIVE — read-only Sicht auf die lokale
 * IOBTH-Lease aus DERSELBEN Provider-Quelle wie APS/Export/Status (R11: nie eine zweite
 * Wahrheit). Verwendung (A2-Config-Umbau): OEFFNER-Waechter zusaetzlich an NOT_EXISTS
 * binden; SCHUTZ-Absenker bekommen die Sperre AUSDRUECKLICH NICHT (Schutz gewinnt immer —
 * sein Write beendet die Lease dann via Generation als FOREIGN_MODIFIED, fail-safe).
 * In A1 (OFF/Forced-VO) existiert nie eine Lease → EXISTS ist konstant false.
 */
class TriggerAutoIsfLeaseActive(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var effectiveAutoIsfSettingsProvider: EffectiveAutoIsfSettingsProvider

    var comparator = ComparatorExists(rh)

    constructor(injector: HasAndroidInjector, compare: ComparatorExists.Compare) : this(injector) {
        comparator = ComparatorExists(rh, compare)
    }

    constructor(injector: HasAndroidInjector, other: TriggerAutoIsfLeaseActive) : this(injector) {
        comparator = ComparatorExists(rh, other.comparator.value)
    }

    override fun shouldRun(): Boolean {
        val active = effectiveAutoIsfSettingsProvider.isIobThLeaseActive()
        val ready = (active && comparator.value == ComparatorExists.Compare.EXISTS) ||
            (!active && comparator.value == ComparatorExists.Compare.NOT_EXISTS)
        aapsLogger.debug(LTag.AUTOMATION, (if (ready) "Ready" else "NOT ready") + " for execution: " + friendlyDescription())
        return ready
    }

    override fun dataJSON(): JSONObject = JSONObject().put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        comparator.value = ComparatorExists.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!)
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_iobth_lease

    override fun friendlyDescription(): String =
        rh.gs(R.string.iobthleasecompared, rh.gs(comparator.value.stringRes))

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_iobth)

    override fun duplicate(): Trigger = TriggerAutoIsfLeaseActive(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_iobth_lease, this))
            .add(comparator)
            .build(root)
    }
}
