package app.aaps.plugins.aps.openAPSAutoISF

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonObject

/**
 * iOS has no IOB-Action companion viewer, so there is nobody to export to: the exports are absent on
 * this platform rather than written to a place nothing reads.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class IosIobActionExportSink : IobActionExportSink {

    override fun writeState(state: JsonObject, smbs: List<IobActionExportSink.SmbIn>, factors: IobActionExportSink.Factors) = Unit
    override fun writeCore(core: JsonObject) = Unit
    override fun writeConfig(config: JsonObject) = Unit
    override fun loopCycleWritten(cycleTs: Long) = Unit
}
