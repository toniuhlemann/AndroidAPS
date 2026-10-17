package app.aaps.implementation.queue.commands

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import kotlin.concurrent.Volatile
import kotlin.time.Clock

class CommandSMBBolus(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val bolusProgressData: BolusProgressData,
    override val pumpEnactResultProvider: () -> PumpEnactResult,
    private val detailedBolusInfo: DetailedBolusInfo,
    private val bolusGeneration: Long,
) : Command() {

    override val commandType: Command.CommandType = Command.CommandType.SMB_BOLUS

    /**
     * Fork (FUSE KI-171): set as the first thing [execute] does. Before that the command was only waiting
     * in the queue, so a [cancel] then provably never reached the pump. After it the executor may still
     * cancel the command (its driver threw) - then the outcome is unknown and nothing is marked.
     */
    @Volatile private var started = false

    override suspend fun execute(): PumpEnactResult {
        started = true
        val r: PumpEnactResult
        val lastBolusTime = persistenceLayer.getNewestBolus()?.timestamp ?: 0L
        aapsLogger.debug(LTag.PUMPQUEUE, "Last bolus: $lastBolusTime ${dateUtil.dateAndTimeAndSecondsString(lastBolusTime)}")
        // Fork: shared tolerance, see Constants.SMB_INTERVAL_TOLERANCE_SECONDS
        if (lastBolusTime != 0L && lastBolusTime + T.mins(preferences.get(IntKey.ApsMaxSmbFrequency).toLong()).msecs() - T.secs(Constants.SMB_INTERVAL_TOLERANCE_SECONDS).msecs() > dateUtil.now()) {
            aapsLogger.debug(LTag.APS, "SMB requested but still in ${preferences.get(IntKey.ApsMaxSmbFrequency)} min interval")
            // Fork (FUSE KI-171): this branch and the "too old" one return before deliverTreatment, so the pump
            // was provably never called. The pump branch is never marked, whatever its result.
            r = pumpEnactResultProvider().enacted(false).success(false).comment("SMB requested but still in ${preferences.get(IntKey.ApsMaxSmbFrequency)} min interval")
                .notSentToPump(true)
        } else if (detailedBolusInfo.deliverAtTheLatest != 0L && detailedBolusInfo.deliverAtTheLatest + T.mins(1).msecs() > Clock.System.now().toEpochMilliseconds()) {
            r = activePlugin.activePump.deliverTreatment(detailedBolusInfo)
        } else {
            r = pumpEnactResultProvider().enacted(false).success(false).comment("SMB request too old")
                .notSentToPump(true)
            aapsLogger.debug(LTag.PUMPQUEUE, "SMB bolus canceled. deliverAt: " + dateUtil.dateAndTimeString(detailedBolusInfo.deliverAtTheLatest))
        }
        aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
        // Generation-scoped: never wipe a newer bolus that was enqueued behind this SMB (see BolusProgressData.clear).
        bolusProgressData.clear(bolusGeneration)
        return r
    }

    override fun status(): String = rh.gs(CoreUiStrings.smb_bolus_u, detailedBolusInfo.insulin)

    override fun log(): String = "SMB BOLUS ${rh.gs(InterfacesStrings.format_insulin_units, detailedBolusInfo.insulin)}"

    override fun cancel(comment: TextRef, success: Boolean, cancelled: Boolean) {
        // Fork (FUSE KI-171): the queue drops only commands that are still waiting (removeAll, drain), and
        // those never reached the pump. A command that has started is cancelled only by the executor after its
        // driver threw - the pump may have delivered, so that one is not marked.
        if (started) super.cancel(comment, success, cancelled)
        else completion.complete(pumpEnactResultProvider().success(success).cancelled(cancelled).comment(comment).notSentToPump(true))
        bolusProgressData.clear(bolusGeneration)
    }
}
