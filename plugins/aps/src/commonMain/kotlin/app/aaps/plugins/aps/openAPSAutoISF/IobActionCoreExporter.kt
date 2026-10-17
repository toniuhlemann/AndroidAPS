package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Fork (IOB-Action viewer): the live CORE snapshot, iobaction_core.json, every minute.
 *
 * The state export is written only in the determine-basal cycle of autoISF, so it is only as fresh
 * as the last loop run, and under FUSE it is never written at all. This writes the loop independent
 * live state (IOB/COB/running TBR/TT/device, profile basics, the SMBs of the last 6 h) straight
 * from the same calculators the widget reads, so the viewer stays current whatever the loop does.
 *
 * Write-only, guarded, never touches dosing. Same keys as the 3.4 fork.
 */
@SingleIn(AppScope::class)
@Inject
class IobActionCoreExporter(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val loop: Loop,
    private val effectiveAutoIsfSettings: EffectiveAutoIsfSettingsProvider,
    private val ch: ConcentrationHelper,
    private val sink: IobActionExportSink
) {

    suspend fun snapshot() {
        try {
            val now = dateUtil.now()
            val profile = profileFunction.getProfile() ?: return
            // The live loop BG and deltas (the glucose status the overview shows).
            val gs = runCatchingNotCancel { glucoseStatusProvider.glucoseStatusData }
            // The calculation the overview header uses, so the IOB matches it exactly.
            val headerIob = runCatchingNotCancel { iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile) }
            val cob = runCatchingNotCancel { iobCobCalculator.getCobInfo("IobAction core").displayCob }
            // The temp basal RUNNING now (what the pump delivers), not the loop's request.
            val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
            val tbrRate = tb?.convertedToAbsolute(now, profile)
            val tbrRemaining = tb?.plannedRemainingMinutes
            val activeTt = persistenceLayer.getTemporaryTargetActiveAt(now)
            val runningMode = runCatchingNotCancel { loop.runningMode() }
            val smbs = runCatchingNotCancel {
                persistenceLayer.getBolusesFromTimeToTime(now - SMB_WINDOW_MS, now, true)
                    .filter { it.isValid && it.type == BS.Type.SMB && it.amount > 0.0 }
            }
            val snap = effectiveAutoIsfSettings.snapshot()

            val json = buildJsonObject {
                putOpt("ts", now)
                putOpt("core", true)
                // Same keys as the state export, so the viewer uses one parser.
                put("aps", buildJsonObject {
                    putOpt("IOB", headerIob?.iob?.finiteOrNull())
                    putOpt("COB", cob?.finiteOrNull())
                    putOpt("rate", tbrRate?.finiteOrNull())
                    putOpt("duration", tbrRemaining)
                    putOpt("bg", gs?.glucose?.finiteOrNull())
                    // Lets the viewer gate auto-TT writes (closed/LGS = will dose) without the log.
                    runningMode?.let {
                        putOpt("loopMode", it.name)
                        putOpt("loopClosedOrLgs", it.isClosedLoopOrLgs())
                    }
                })
                gs?.let { g ->
                    put("glucose", buildJsonObject {
                        putOpt("delta", g.delta.finiteOrNull())
                        putOpt("shortAvgDelta", g.shortAvgDelta.finiteOrNull())
                        putOpt("longAvgDelta", g.longAvgDelta.finiteOrNull())
                    })
                }
                headerIob?.let { iob ->
                    put("iob", buildJsonObject {
                        putOpt("net", iob.iob.finiteOrNull())
                        putOpt("basal", iob.basaliob.finiteOrNull())
                        putOpt("bolus", (iob.iob - iob.basaliob).finiteOrNull())
                        putOpt("activity", iob.activity.finiteOrNull())
                    })
                }
                put("profile", buildJsonObject {
                    // Loop independent, so a profile switch shows at once even on an idle loop.
                    putOpt("target_bg", profile.getTargetMgdl().finiteOrNull())
                    putOpt("sens", profile.getProfileIsfMgdl().finiteOrNull())
                    putOpt("carb_ratio", profile.getIc().finiteOrNull())
                    putOpt("current_basal", profile.getBasal().finiteOrNull())
                    putOpt("name", profileFunction.getProfileName())
                    putOpt("name_remaining", profileFunction.getProfileNameWithRemainingTime())
                    // autoISF SETTINGS (not outputs). The legacy field keeps the BASE; the effective
                    // value and the lease state come next to it (same schema as the state export).
                    putOpt("iob_threshold_percent", preferences.get(IntKey.ApsAutoIsfIobThPercent))
                    putOpt("iob_threshold_percent_base", snap.iobThPercentBase)
                    putOpt("iob_threshold_percent_effective", snap.iobThPercentEffective)
                    putOpt("iobth_override_state", snap.overrideState.name)
                    putOpt("iobth_lease_id", snap.leaseId)
                    putOpt("iobth_lease_version", snap.leaseVersion)
                    putOpt("iobth_lease_expires_at", snap.expiresAtWallMs)
                    putOpt("bgAccel_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight))
                    putOpt("pp_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfPpWeight))
                    putOpt("bgBrake_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight))
                    putOpt("dura_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfDuraWeight))
                })
                // Each in its own guard: a pump without a reservoir value must not drop the whole file.
                runCatchingNotCancel {
                    val pump = activePlugin.activePump
                    put("device", buildJsonObject {
                        putOpt("reservoir", ch.fromPump(pump.reservoirLevel.value).finiteOrNull())
                        putOpt("battery", pump.batteryLevel.value)
                    })
                }
                activeTt?.let { tt ->
                    put("tt", buildJsonObject {
                        putOpt("target", tt.lowTarget.finiteOrNull())
                        putOpt("remainingMin", (tt.timestamp + tt.duration - now) / 60000L)
                    })
                }
                // The SMBs of the last 6 h from the treatments database (pump truth, raw ts + units).
                // The viewer sums the meal row from this list only; under FUSE the state export is
                // never written, so the core file must carry it. No factor stamps here: that stays
                // with the determine-basal hook, a heartbeat must never freeze them.
                // A failed read leaves the key out (as in the 3.4 fork), so the viewer never sees an
                // empty list that looks like "no SMBs".
                smbs?.let { list ->
                    put("smbHistory", buildJsonArray {
                        list.forEach { b -> add(buildJsonObject { putOpt("ts", b.timestamp); putOpt("units", b.amount) }) }
                    })
                }
            }
            sink.writeCore(json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "IobAction core export failed", e)
        }
    }

    /** runCatching that does not swallow coroutine cancellation. */
    private inline fun <T> runCatchingNotCancel(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {

        const val SMB_WINDOW_MS = 6 * 3600_000L
    }
}
