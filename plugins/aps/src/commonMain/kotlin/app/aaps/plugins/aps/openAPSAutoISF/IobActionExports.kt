package app.aaps.plugins.aps.openAPSAutoISF

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Fork (IOB-Action viewer): the minute heartbeat of the viewer exports. `MainApp` calls it on the same
 * 60 s tick that refreshes the widget, so the files stay fresh whichever APS runs (FUSE included) and
 * whether or not the loop ran. The state export is not here: it belongs to the autoISF cycle.
 */
@SingleIn(AppScope::class)
@Inject
class IobActionExports(
    private val core: IobActionCoreExporter,
    private val config: IobActionConfigExporter
) {

    /** Core first: it is the live state. Each exporter guards itself. */
    suspend fun heartbeat() {
        core.snapshot()
        config.snapshot()
    }
}
