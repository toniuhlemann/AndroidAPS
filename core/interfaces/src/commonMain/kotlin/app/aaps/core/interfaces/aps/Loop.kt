package app.aaps.core.interfaces.aps

import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.PumpEnactResult
import kotlin.concurrent.Volatile
import kotlin.time.Clock

interface Loop {

    /**
     * Check if plugin is currently enabled
     * @return true if enabled
     */
    fun isEnabled(): Boolean

    /**
     * Collected values from last APS run
     */
    class LastRun {

        var request: APSResult? = null
        var constraintsProcessed: APSResult? = null
        var tbrSetByPump: PumpEnactResult? = null
        var smbSetByPump: PumpEnactResult? = null
        var source: String? = null
        var lastAPSRun = Clock.System.now().toEpochMilliseconds()
        var lastTBREnact: Long = 0
        var lastSMBEnact: Long = 0
        var lastTBRRequest: Long = 0
        var lastSMBRequest: Long = 0
        var lastOpenModeAccept: Long = 0

        /**
         * Fork (FUSE KI-171): SMB requests that were rejected WITHOUT reaching the pump driver
         * (PumpEnactResult.notSentToPump). Such a rejection can arrive minutes after the request (the queue
         * waits for Bluetooth), when this LastRun already describes a newer run - so each entry carries the
         * identity of its own request instead of relying on the current one.
         *
         * Bounded (newest last) and replaced as a whole: written from the queue, read by the APS plugin in
         * its next run.
         */
        @Volatile var smbNotSent: List<SmbNotSent> = emptyList()
        private val smbNotSentLock = AapsLock()

        fun recordSmbNotSent(entry: SmbNotSent) {
            smbNotSentLock.withLock { smbNotSent = (smbNotSent + entry).takeLast(SMB_NOT_SENT_MAX) }
        }

        companion object {

            const val SMB_NOT_SENT_MAX = 32
        }
    }

    /**
     * Fork (FUSE KI-171): one SMB request that provably never reached the pump.
     *
     * @param request identity key - `APSResult.rawData()` of the rejected request, compared by REFERENCE by
     *   the APS plugin that produced it.
     * @param requestedU the amount AAPS tried to deliver (after constraints), in insulin units.
     */
    class SmbNotSent(
        val request: Any?,
        val requestedU: Double,
        val atMs: Long,
        val reason: String,
    )

    /**
     * Last APS run result
     */
    var lastRun: LastRun?

    /**
     * Variable to store reasons of disabled loop
     */
    var closedLoopEnabled: Constraint<Boolean>?

    /**
     * Current running mode (queries persistence — must be called from a coroutine).
     */
    suspend fun runningMode(): RM.Mode

    /**
     * Current running mode (queries persistence — must be called from a coroutine).
     */
    suspend fun runningModeRecord(): RM

    /**
     * Allowed next (following) modes according to current running mode
     */
    suspend fun allowedNextModes(): List<RM.Mode>

    /**
     * Handle RunningMode change
     * @param newRM New running mode if specified
     * @param durationInMinutes Duration for new mode in minutes
     * @return true if change is successful
     */
    suspend fun handleRunningModeChange(newRM: RM.Mode, action: Action, source: Sources, listValues: List<ValueWithUnit> = emptyList(), durationInMinutes: Int = 0, profile: Profile): Boolean

    /**
     * Periodic check that the pump really gives no insulin while the pump is disconnected or a super
     * bolus runs. Sends the zero temp basal again when it is missing or ends soon, and raises an alarm
     * when it stays missing. Never changes the running mode. Does nothing on a device that does not
     * drive the pump.
     */
    suspend fun verifyZeroDelivery()

    /**
     * Timestamp of last loop run triggered by new BG
     */
    var lastBgTriggeredRun: Long

    /**
     * Invoke new loop run
     *
     * @param initiator Identifies who triggered the run
     * @param allowNotification Allow notification to be sent (false in open loop mode)
     * @param tempBasalFallback true if called from failed SMB
     */
    suspend fun invoke(initiator: String, allowNotification: Boolean, tempBasalFallback: Boolean = false)

    /**
     * Open loop mode trigger: enact the last calculated suggestion.
     *
     * Refused while the running mode pauses the loop, and when the suggestion is older than an old BG.
     * A suggestion can wait on the watch long after it was sent.
     *
     * @return why it was refused, as text for the user, or null when it was not refused
     */
    suspend fun acceptChangeRequest(): String?

    /**
     * Returns minutes to end of suspended loop
     */
    suspend fun minutesToEndOfSuspend(): Int

    fun disableCarbSuggestions(durationMinutes: Int)

    /**
     * Schedule building of device status before sending to NS
     *
     * @param reason Initiator
     */
    fun scheduleBuildAndStoreDeviceStatus(reason: String)
}