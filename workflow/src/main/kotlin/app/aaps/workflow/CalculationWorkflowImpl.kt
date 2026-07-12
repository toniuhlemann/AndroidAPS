package app.aaps.workflow

import android.content.Context
import android.os.SystemClock
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.JOB
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.PASS
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.UPDATE_PREDICTIONS
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.core.utils.worker.then
import app.aaps.workflow.iob.IobCobOref1Worker
import app.aaps.workflow.iob.IobCobOrefWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalculationWorkflowImpl @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val dataWorkerStorage: DataWorkerStorage,
    private val activePlugin: ActivePlugin
) : CalculationWorkflow {

    init {
        // Verify definition
        var sumPercent = 0
        for (pass in CalculationWorkflow.ProgressData.entries) sumPercent += pass.percentOfTotal
        require(sumPercent == 100)
    }

    override fun stopCalculation(job: String, from: String) {
        aapsLogger.debug(LTag.WORKER, "Stopping calculation thread: $from")
        WorkManager.getInstance(context).cancelUniqueWork(job)
        // The old wait fetched the WorkInfo list ONCE and then busy-looped on the immutable
        // snapshot — if it caught state RUNNING the loop never terminated, permanently blocking
        // the single-threaded historyWorker (a second, independent "loop hangs until reboot"
        // path besides the 1-min recalc livelock). Re-fetch each iteration + bounded timeout;
        // any straggler is cancelled by ExistingWorkPolicy.REPLACE anyway and works on a clone.
        val timeout = SystemClock.elapsedRealtime() + 10_000
        var workStatus = WorkManager.getInstance(context).getWorkInfosForUniqueWork(job).get()
        while (workStatus.any { it.state == WorkInfo.State.RUNNING } && SystemClock.elapsedRealtime() < timeout) {
            SystemClock.sleep(100)
            workStatus = WorkManager.getInstance(context).getWorkInfosForUniqueWork(job).get()
        }
        aapsLogger.debug(LTag.WORKER, "Calculation thread stopped: $from")
    }

    override fun runCalculation(
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        cause: Event?
    ) {
        aapsLogger.debug(LTag.WORKER, "Starting calculation worker: $reason to ${dateUtil.dateAndTimeAndSecondsString(end)}")

        WorkManager.getInstance(context)
            .beginUniqueWork(
                job, ExistingWorkPolicy.REPLACE,
                if (bgDataReload) OneTimeWorkRequest.Builder(LoadBgDataWorker::class.java).setInputData(dataWorkerStorage.storeInputData(LoadBgDataWorker.LoadBgData(iobCobCalculator, end))).build()
                else OneTimeWorkRequest.Builder(DummyWorker::class.java).build()
            )
            // IOB-Action patch 0033 (2026-07-12): DOSING BEFORE DRAWING. The dosing-relevant
            // pipeline is only LoadBgData (creates the bucketed data) -> IobCobOref -> InvokeLoop;
            // everything else prepares overviewData for the UI. The graph workers used to run
            // BEFORE the loop (PrepareBasalData alone ~27s on 1-min data with DIA 9), so every
            // loop run waited ~40s on chart preparation — and when an SMB's treatment writes
            // cancelled+restarted this chain (see InvokeLoopWorker, patch 0032), the REPLACEMENT
            // run paid those ~40s again: effective delivery cadence stayed ~115-128s during
            // sustained delivery phases (log-verified 2026-07-12 12:54-13:07). With the loop
            // directly after IobCobOref the replacement reaches it in a few seconds instead.
            // Dependency audit: PrepareBucketedDataWorker only builds overviewData.bucketed-
            // GraphSeries (display; the bucketed data itself is created in LoadBgDataWorker);
            // UpdateIobCobSens + PrepareIobAutosensGraphData consume IobCobOref results and stay
            // after it; PreparePredictions/UpdateWidget consume the loop result and stay after.
            .then(
                if (activePlugin.activeSensitivity.isOref1)
                    OneTimeWorkRequest.Builder(IobCobOref1Worker::class.java)
                        .setInputData(dataWorkerStorage.storeInputData(IobCobOref1Worker.IobCobOref1WorkerData(iobCobCalculator, reason, end, job == MAIN_CALCULATION, cause)))
                        .build()
                else
                    OneTimeWorkRequest.Builder(IobCobOrefWorker::class.java)
                        .setInputData(dataWorkerStorage.storeInputData(IobCobOrefWorker.IobCobOrefWorkerData(iobCobCalculator, reason, end, job == MAIN_CALCULATION, cause)))
                        .build()
            )
            .then(
                runIf = job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(InvokeLoopWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(InvokeLoopWorker.InvokeLoopData(cause)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_BG.pass).build())
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBasalDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBasalDataWorker.PrepareBasalData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareTemporaryTargetDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTemporaryTargetDataWorker.PrepareTemporaryTargetData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareRunningModeDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareRunningModeDataWorker.PrepareRunningModeData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_TT.pass).build())
                    .build()
            )
            .then(OneTimeWorkRequest.Builder(UpdateIobCobSensWorker::class.java).build())
            .then(
                OneTimeWorkRequest.Builder(PrepareIobAutosensGraphDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareIobAutosensGraphDataWorker.PrepareIobAutosensData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                runIf = job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_IOB.pass).build())
                    .build()
            )
            .then(
                runIf = job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(UpdateWidgetWorker::class.java).build()
            )
            .then(
                runIf = job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    override fun runOnReceivedPredictions(
        overviewData: OverviewData
    ) {
        aapsLogger.debug(LTag.WORKER, "Starting updateReceivedPredictions worker")

        WorkManager.getInstance(context)
            .beginUniqueWork(
                UPDATE_PREDICTIONS, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, UPDATE_PREDICTIONS).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    override fun runOnEventTherapyEventChange(overviewData: OverviewData) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()

    }

    override fun runOnScaleChanged(iobCobCalculator: IobCobCalculator, overviewData: OverviewData) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }
}