package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.objects.workflow.MetroWorkerCreator
import app.aaps.core.ui.compose.icons.IcXDrip
import app.aaps.core.utils.receivers.DataInbox
import app.aaps.core.utils.receivers.Inbox
import app.aaps.plugins.source.compose.BgSourceComposeContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(400)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<XDripSource>())
@Inject
class XdripSourcePlugin(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    notificationManager: NotificationManager
) : AbstractBgSourceWithSensorInsertLogPlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { plugin ->
            BgSourceComposeContent(
                title = rh.gs(R.string.source_xdrip)
            )
        }
        .icon(IcXDrip)
        .pluginName(TextRef.AndroidRes(R.string.source_xdrip))
        .preferencesVisibleInSimpleMode(false)
        .description(TextRef.AndroidRes(R.string.description_source_xdrip)),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    notificationManager = notificationManager
), BgSource, XDripSource {

    override var sensorBatteryLevel = -1

    // cannot be inner class because of needed injection

    @AssistedInject
    class XdripSourceWorker(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        aapsLogger: AAPSLogger,
        fabricPrivacy: FabricPrivacy,
        private val xdripSourcePlugin: XdripSourcePlugin,
        private val persistenceLayer: PersistenceLayer,
        private val preferences: Preferences,
        private val dateUtil: DateUtil,
        private val dataInbox: DataInbox,
        private val profileUtil: ProfileUtil
    ) : LoggingWorker(context, params, Dispatchers.IO, aapsLogger, fabricPrivacy) {

        /**
         * Metro builds this worker. The parameter names must match [MetroWorkerCreator],
         * because Metro matches assisted parameters by name and not only by type.
         */
        @AssistedFactory
        fun interface Factory : MetroWorkerCreator {

            override fun create(context: Context, params: WorkerParameters): XdripSourceWorker
        }

        fun getSensorStartTime(bundle: Bundle): Long? {
            val now = dateUtil.now()
            var sensorStartTime: Long? = if (preferences.get(BooleanKey.BgSourceCreateSensorChange)) {
                bundle.getLong(Intents.EXTRA_SENSOR_STARTED_AT, 0)
            } else {
                null
            }
            // check start time validity
            sensorStartTime?.let {
                if (abs(it - now) > T.months(1).msecs() || it > now) sensorStartTime = null
            }
            return sensorStartTime
        }

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            // Drain first, unconditionally: drain() clears DataInbox's pending-work gate, so every
            // enqueued worker MUST reach it. If we returned early (plugin disabled) before draining,
            // the gate would stay set and silently block all future enqueues for this slot until
            // the process restarts. Bundles drained while disabled are intentionally discarded.
            val bundles = dataInbox.drain(XdripInbox)
            if (!xdripSourcePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            if (bundles.isEmpty()) return Result.success(workDataOf("Result" to "no data"))

            var hadFailure = false
            for ((index, bundle) in bundles.withIndex()) {
                try {
                    processBundle(bundle)
                } catch (e: CancellationException) {
                    // WorkManager stopped this run (e.g. chain overload / run-attempt limit). The
                    // coroutine contract requires CancellationException to propagate, otherwise the
                    // loop keeps fighting a cancelled Job and spams failures for every remaining
                    // bundle. drain() already removed the whole batch, so re-queue this bundle plus
                    // everything not yet processed before propagating — otherwise those readings are
                    // lost. requeue/enqueue are non-suspending, so they complete despite cancellation.
                    dataInbox.requeue(XdripInbox, bundles.subList(index, bundles.size))
                    throw e
                } catch (e: Exception) {
                    // processBundle early-returns on malformed-bundle conditions; anything that
                    // reaches the catch is a real exception (typically a DB write). Surface as
                    // failure so WorkInfo reflects the truth.
                    aapsLogger.error(LTag.BGSOURCE, "Failed processing xDrip bundle", e)
                    hadFailure = true
                }
            }
            return if (hadFailure) Result.failure(workDataOf("Error" to "one or more bundles failed")) else Result.success()
        }

        private suspend fun processBundle(bundle: Bundle) {
            aapsLogger.debug(LTag.BGSOURCE, "Received xDrip data: $bundle")
            val glucoseValues = mutableListOf<GV>()
            // autoISF 3.2.1 (Tobias/ga-zelle): Libre calibration (offset/slope) and first order
            // exponential smoothing. For a Libre or G7 reading without raw value the sensor value is
            // calibrated into `raw`, smoothed into `value`, and the uncalibrated value is kept in `noise`.
            // Values are no longer rounded. Other sources pass through as before, apart from the rounding.
            var extraBgEstimate = bundle.getDouble(Intents.EXTRA_BG_ESTIMATE, 0.0)
            var extraRaw = bundle.getDouble(Intents.EXTRA_RAW, 0.0)
            val offset = preferences.get(DoubleKey.FslCalOffset)
            val slope = preferences.get(DoubleKey.FslCalSlope)
            val factor = preferences.get(DoubleKey.FslSmoothAlpha)
            val lastSmooth = preferences.get(DoubleNonKey.FslLastSmooth)
            val lastTimeRaw = preferences.get(LongNonKey.FslSmoothLastTimeRaw)
            val thisTimeRaw = bundle.getLong(Intents.EXTRA_TIMESTAMP, 0)
            val elapsedMinutes = (thisTimeRaw - lastTimeRaw) / 60000.0
            var smooth = extraBgEstimate
            if (preferences.get(BooleanKey.FslCalibrationTrigger)) {
                preferences.put(LongNonKey.FslCalibrationStart, dateUtil.now())
                preferences.put(BooleanKey.FslCalibrationTrigger, false)
                preferences.put(BooleanKey.FslCalibrationEnd, false)
            }
            val calibrationDuration = preferences.get(IntNonKey.FslCalibrationDuration)
            val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongNonKey.FslCalibrationStart)) / 60000
            val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
            if (calibrationStopsSMB) {
                aapsLogger.debug(LTag.BGSOURCE, "Sensor calibrating for another ${calibrationMinutes}m")
            }
            val sourceCGM = bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: ""
            if (extraRaw == 0.0 && (sourceCGM == "Libre2" || sourceCGM == "Libre2 Native" || sourceCGM == "Libre3" || sourceCGM == "G7")) {
                extraRaw = extraBgEstimate
                extraBgEstimate = max(40.0, extraRaw * slope + offset * (if (profileUtil.units == GlucoseUnit.MMOL) Constants.MMOLL_TO_MGDL else 1.0))
                val maxGap = 20
                val cgmDelta = if (sourceCGM == "G7") 5.0 else 1.0
                // no smoothing right after a calibration start and for long gaps (alpha up to 1)
                val effectiveAlpha = if (calibrationDuration - calibrationMinutes < 2 && !preferences.get(BooleanKey.FslCalibrationEnd)) 1.0
                else min(1.0, factor + (1.0 - factor) * ((max(0.0, elapsedMinutes - cgmDelta) / (maxGap - cgmDelta)).pow(2.0)))
                if (lastSmooth > 0.0) {
                    // exponential smoothing, see https://en.wikipedia.org/wiki/Exponential_smoothing
                    smooth = lastSmooth + effectiveAlpha * (extraBgEstimate - lastSmooth)
                }
                preferences.put(DoubleNonKey.FslLastRaw, extraBgEstimate)
                preferences.put(DoubleNonKey.FslLastSmooth, smooth)
                preferences.put(LongNonKey.FslSmoothLastTimeRaw, thisTimeRaw)
                var calibrationMsg = "Calibration json: {\"calibration_offset\":$offset,\"calibration_slope\":$slope,\"smoothFactor\":$factor,\"effectiveAlpha\":$effectiveAlpha"
                calibrationMsg += ",\"calibrationStart\":${preferences.get(LongNonKey.FslCalibrationStart)},\"calibrationIgnore\":${preferences.get(BooleanKey.FslCalibrationEnd)}"
                calibrationMsg += "}"
                aapsLogger.debug(LTag.BGSOURCE, calibrationMsg)
                // Fork (Toni's smoothing): the causal 1-min UKF "Q1" replaces the EMA as the LOOP value.
                // Its input is the calibrated series BEFORE the EMA (= stored GV.raw + the current
                // extraBgEstimate) - never GV.value and never the uncalibrated sensor value. The EMA above
                // keeps running warm, so the switch is an instant rollback; any error falls back silently
                // to the EMA (the value stays `smooth`). GV.raw itself stays the unsmoothed value.
                if (preferences.get(BooleanKey.FslUkfQ1Enabled)) {
                    val ema = smooth
                    try {
                        // Reset bounds: after a sensor change, and after a calibration (the stored raw
                        // points still carry the OLD slope/offset), Q1 must not filter two measuring regimes
                        // as one series. The viewer reference model bounds the window the same way.
                        val sensorStart = maxOf(
                            getSensorStartTime(bundle) ?: 0L,
                            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp ?: 0L,
                        )
                        val calibrationStart = preferences.get(LongNonKey.FslCalibrationStart).takeIf { it > 0L } ?: 0L
                        val resetBoundary = maxOf(sensorStart, calibrationStart)
                        val windowStart = thisTimeRaw - UkfQ1.WINDOW_SAMPLES * 60_000L
                        val from = maxOf(windowStart, resetBoundary)
                        val history = persistenceLayer
                            .getBgReadingsDataFromTimeToTime(from, thisTimeRaw - 1, true)
                            .mapNotNull { gv -> gv.raw?.takeIf { it > 39.0 }?.let { UkfQ1.Point(gv.timestamp, it) } }
                        // A STATE, not an event: which bound limits the window holds for as long as it does.
                        val boundedBy = when {
                            from == windowStart             -> "window"
                            sensorStart >= calibrationStart -> "sensorChange"
                            else                            -> "calibrationStart"
                        }
                        val q1 = UkfQ1.leadingEdge(history + UkfQ1.Point(thisTimeRaw, extraBgEstimate))
                        if (q1 != null) smooth = q1.glucose
                        val status = "{\"applied\":${q1 != null},\"ts\":$thisTimeRaw," +
                            "\"loopValue\":$smooth,\"ema\":$ema," +
                            (q1?.let {
                                "\"q1\":${it.glucose},\"rate\":${it.ratePerMin}," +
                                    "\"learnedR\":${it.learnedR},\"outlier\":${it.outlier},"
                            } ?: "\"fallback\":\"emaKept\",") +
                            "\"inputCount\":${history.size + 1},\"boundedBy\":\"$boundedBy\"," +
                            "\"windowFromTs\":$from}"
                        aapsLogger.debug(LTag.BGSOURCE, "UkfQ1 json: $status")
                        // The same status into the preferences, so the state export can pass it on and the
                        // viewer shows a silent fallback to the EMA without anyone reading the log.
                        preferences.put(StringNonKey.FslUkfQ1Status, status)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        smooth = ema
                        val status = "{\"applied\":false,\"ts\":$thisTimeRaw," +
                            "\"loopValue\":$smooth,\"ema\":$smooth,\"fallback\":\"error\"}"
                        aapsLogger.error(LTag.BGSOURCE, "UkfQ1 json: $status", e)
                        preferences.put(StringNonKey.FslUkfQ1Status, status)
                    }
                }
            }
            glucoseValues += GV(
                timestamp = thisTimeRaw,
                value = smooth,
                raw = extraBgEstimate,
                // piggy back: the uncalibrated sensor value (autoISF 3.2.1)
                noise = extraRaw,
                trendArrow = TrendArrow.fromString(bundle.getString(Intents.EXTRA_BG_SLOPE_NAME)),
                sourceSensor = SourceSensor.fromString(bundle.getString(Intents.XDRIP_DATA_SOURCE) ?: "")
            )
            val newSensorStartTime = getSensorStartTime(bundle)
            // Retrieve last stored sensorStartTime from the database
            val lastTherapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            val lastStoredSensorStartTime = lastTherapyEvent?.timestamp
            // Decide whether to update sensorStartTime or keep the last stored one
            val finalSensorStartTime = when {
                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    abs(newSensorStartTime - lastStoredSensorStartTime) <= 300_000 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is within 5 minutes range, skipping update.")
                    null
                }

                lastStoredSensorStartTime != null && newSensorStartTime != null &&
                    newSensorStartTime < lastStoredSensorStartTime                 -> {
                    aapsLogger.debug(LTag.BGSOURCE, "Sensor start time is older than last stored time, skipping update.")
                    null
                }

                else                                                               -> newSensorStartTime
            }
            // Always update glucoseValues, but use the decided sensorStartTime
            if (glucoseValues[0].timestamp > 0 && glucoseValues[0].value > 0.0) {
                persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), finalSensorStartTime)
            } else {
                aapsLogger.warn(LTag.BGSOURCE, "Skipping xDrip bundle: missing glucoseValue")
                return
            }
            xdripSourcePlugin.sensorBatteryLevel = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY, -1)
        }
    }
}

object XdripInbox : Inbox<Bundle>("xdrip-bg", XdripSourcePlugin.XdripSourceWorker::class.java)
