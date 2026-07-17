package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class InvokeLoopWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop
    @Inject lateinit var commandQueue: CommandQueue

    class InvokeLoopData(
        val cause: Event?
    )

    /*
     This method is triggered once autosens calculation has completed, so the LoopPlugin
     has current data to work with.

     A loop run needs a FRESH, NOT-YET-LOOPED BG - regardless of which event caused this
     calculation. Historically only EventNewBG-caused calculations could invoke the loop, but
     on a 1-min CGM with a slow-confirming pump (Medtrum: enact + confirmation ~45-70s) the
     SMB's own treatment writes (bolus + TBR records) fire EventNewHistoryData, whose recalc
     CANCELS the already running EventNewBG calculation of the NEXT reading
     (IobCobCalculatorPlugin.newHistoryData -> stopCalculation(MAIN_CALCULATION)). The
     replacement calculation then returned early here ("no calculation needed") -> every
     second loop run was swallowed -> SMBs only every ~2 min despite SMBInterval=1
     (log-verified 2026-07-10 21:39-21:53: BG :37 -> its calc :42 -> confirmation recalc
     kills it :48 -> "no calculation needed"; during non-delivery phases the loop ran every
     60s). The two remaining gates keep every safety property: actualBg() returns null for
     data older than 9 min (no loop on a stale/dead sensor), and lastBgTriggeredRun
     guarantees at most ONE loop run per BG value (recalcs without a fresh reading still
     end in "already looped with that value").
    */
    override suspend fun doWorkAndLog(): Result {

        // Input integrity check only - the cause itself no longer gates the loop (see above).
        dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as InvokeLoopData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val glucoseValue = iobCobCalculator.ads.actualBg()
            // 0055 v4 (Codex round 3, blocker 2): sensor stale → just return. Do NOT clear pending
            // here: a concurrent worker may have marked a fresh BG between our actualBg()==null and
            // reading pendingRetryBgTs(), and we'd erase it. A stale marker is harmless — the next
            // successful claim's clearThrough(claimedTs) cleans it up.
            ?: return Result.success(workDataOf("Result" to "bg outdated"))
        // Fast-path (NOT authoritative — re-checked atomically at the claim below).
        if (glucoseValue.timestamp <= loop.lastBgTriggeredRun) {
            loop.clearPendingRetryThrough(loop.lastBgTriggeredRun)
            return Result.success(workDataOf("Result" to "already looped with that value"))
        }
        // PUMP-BUSY-PEEK (2026-07-12): lastBgTriggeredRun was consumed BEFORE invoke(); if invoke
        // then hit the pump-busy path (queue occupied by the previous cycle's SMB enact + sync,
        // LoopPlugin waits up to 2 min then RETURNS without dosing), the BG counted as looped and
        // no later recalculation could retry it — the cycle was lost. Now: while the queue is
        // busy we DON'T consume the BG and return; the next calculation (treatment writes fire
        // one within seconds during delivery) retries with the BG still eligible. In quiet phases
        // the queue is virtually never busy, so behaviour there is unchanged.
        if (commandQueue.size() > 0 || commandQueue.performing() != null) {
            // 0055: RECORD the skipped BG (monotonic) so QueueWorker can prompt a retry the instant
            // the queue drains — instead of waiting for the next natural trigger (~45s onset
            // latency). This is the ONLY place a retry is marked, so it provably follows a real skip.
            loop.markPendingRetry(glucoseValue.timestamp)
            return Result.success(workDataOf("Result" to "pump busy - retry when queue idle"))
        }
        // 0055 v4 (Codex round 3, blocker 1): ATOMIC check-then-claim of lastBgTriggeredRun.
        // ExistingWorkPolicy.REPLACE is NOT mutual exclusion — cancellation isn't instant (the fork's
        // own stopCalculation() waits up to 10s for a running worker), so two InvokeLoopWorkers can
        // briefly overlap and both pass the fast-path check for the same BG. Guard the claim under
        // the loop monitor so exactly one wins; the loser reports already-looped and never doses.
        val claimed = synchronized(loop) {
            if (glucoseValue.timestamp <= loop.lastBgTriggeredRun) false
            else { loop.lastBgTriggeredRun = glucoseValue.timestamp; true }
        }
        if (!claimed) {
            loop.clearPendingRetryThrough(loop.lastBgTriggeredRun)
            return Result.success(workDataOf("Result" to "already looped with that value (raced)"))
        }
        loop.clearPendingRetryThrough(glucoseValue.timestamp)   // 0055: claim satisfies retry up through this BG
        loop.invoke("Calculation for $glucoseValue", true)
        return Result.success()
    }
}