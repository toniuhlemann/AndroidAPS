package app.aaps.plugins.aps.openAPSAutoISF

import androidx.collection.LongSparseArray
import androidx.collection.forEach
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.data.model.SC
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.iobaction.LocalCommandChannelSettings
import app.aaps.plugins.aps.iobaction.ValueOverlayReader
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSSMB.PhoneMovementDetector
import app.aaps.plugins.aps.openAPSSMB.StepService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

@SingleIn(AppScope::class)
@Inject
open class OpenAPSAutoISFPlugin(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    override val rh: TextResolver,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val bgQualityCheck: BgQualityCheck,
    notificationManager: NotificationManager,
    private val determineBasalAutoISF: DetermineBasalAutoISF,
    private val profiler: Profiler,
    private val glucoseStatusCalculatorAutoIsf: GlucoseStatusCalculatorAutoIsf,
    private val apsResultProvider: () -> APSResult,
    private val ch: ConcentrationHelper,
    private val automationStateService: AutomationStateInterface,
    // Fork (IOB-Action viewer): the effective autoISF settings (one snapshot per run) and the exports
    private val effectiveAutoIsfSettings: EffectiveAutoIsfSettingsProvider,
    private val iobActionExport: IobActionExportSink,
    private val localCommandChannelSettings: LocalCommandChannelSettings
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            app.aaps.plugins.aps.compose.OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.openaps_auto_isf)
        .preferencesVisibleInSimpleMode(false)
        // AAPSCLIENT: visible so a client can select the master's APS.
        .showInList { config.APS || config.AAPSCLIENT }
        // Fork: autoISF 3.2.1 is selectable in this build. Upstream forces it off outside an engineering
        // dev build; Tobias' builds unlock it by forcing engineering mode on for everything. Here only the
        // autoISF lock is removed, the other engineering-only parts stay locked.
        .description(ApsStrings.description_auto_isf),
    ownPreferences = ApsIntentKey.entries,
    aapsLogger, rh, preferences, notificationManager
), APS, PluginConstraints {

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AUTO_ISF
    override var lastAPSResult: APSResult? = null
    private var consoleError = mutableListOf<String>()
    private var consoleLog = mutableListOf<String>()
    val autoIsfVersion = "3.2.0"
    val autoIsfWeights; get() = preferences.get(BooleanKey.ApsUseAutoIsfWeights)
    private val autoISF_max; get() = preferences.get(DoubleKey.ApsAutoIsfMax)
    private val autoISF_min; get() = preferences.get(DoubleKey.ApsAutoIsfMin)

    /*
     * Fork (capability matrix A1, value leases): the autoISF weights and the fixed SMB delivery ratio
     * are read through the overlay of THIS run, the base preference stays untouched.
     *
     * The fork kept the run snapshot in a ThreadLocal, for two reasons: a lease that ends in the
     * middle of a run must not let one run calculate with two values, and callers OUTSIDE a run must
     * see the base - calculateVariableIsf() serves overview, NS upload, widgets and automations, and
     * its result goes into autoIsfCache, which feeds COB and deviations for up to a day. A lease value
     * there would outlive the lease. invoke() is suspend here and can change threads, so a
     * ThreadLocal would be silently wrong. Instead the snapshot is passed as a value: invoke() takes
     * ONE snapshot and hands it on, every other caller passes null and gets the base.
     */
    private fun weight(overlay: EffectiveAutoIsfSettingsProvider.Snapshot?, field: String, key: DoubleKey): Double =
        ValueOverlayReader.weight(overlay, field, preferences.get(key))

    private fun bgAccelIsfWeight(o: EffectiveAutoIsfSettingsProvider.Snapshot?) = weight(o, ValueOverlayReader.F_ACCE, DoubleKey.ApsAutoIsfBgAccelWeight)
    private fun bgBrakeIsfWeight(o: EffectiveAutoIsfSettingsProvider.Snapshot?) = weight(o, ValueOverlayReader.F_BRAKE, DoubleKey.ApsAutoIsfBgBrakeWeight)
    private fun ppIsfWeight(o: EffectiveAutoIsfSettingsProvider.Snapshot?) = weight(o, ValueOverlayReader.F_PP, DoubleKey.ApsAutoIsfPpWeight)
    private fun lowerIsfRangeWeight(o: EffectiveAutoIsfSettingsProvider.Snapshot?) = weight(o, ValueOverlayReader.F_LOW, DoubleKey.ApsAutoIsfLowBgWeight)
    private fun higherIsfRangeWeight(o: EffectiveAutoIsfSettingsProvider.Snapshot?) = weight(o, ValueOverlayReader.F_HIGH, DoubleKey.ApsAutoIsfHighBgWeight)
    private fun duraIsfWeight(o: EffectiveAutoIsfSettingsProvider.Snapshot?) = weight(o, ValueOverlayReader.F_DURA, DoubleKey.ApsAutoIsfDuraWeight)
    private fun smbDeliveryRatio(o: EffectiveAutoIsfSettingsProvider.Snapshot?) =
        ValueOverlayReader.ratio(o, preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio))

    private val smb_delivery_ratio_min; get() = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)
    private val smb_delivery_ratio_max; get() = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)
    private val smb_delivery_ratio_bg_range
        get() = if (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) < 10.0) preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) * Constants.MMOLL_TO_MGDL else preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)
    val smbMaxRangeExtension; get() = preferences.get(DoubleKey.ApsAutoIsfSmbMaxRangeExtension)
    private val enableSMB_EvenOn_OddOff_always; get() = preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget) // for profile target
    /** The BASE iobTH setting. The run uses the effective value of its settings snapshot. */
    val iobThresholdPercent; get() = preferences.get(IntKey.ApsAutoIsfIobThPercent)
    private val exerciseMode; get() = SMBDefaults.exercise_mode
    private val highTemptargetRaisesSensitivity; get() = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)
    val mgdlHalfBasalExerciseTarget; get() = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget))
    val normalTarget = Constants.NORMAL_TARGET_MGDL
    val calibrationDuration = preferences.get(IntNonKey.FslCalibrationDuration)
    private val minutesClass; get() = if (preferences.get(IntKey.ApsMaxSmbFrequency) == 1) 6L else 30L  // ga-zelle: later get correct 1 min CGM flag from glucoseStatus ? ... or from apsResults?
    // create array for key AutoISF results with defaults
    var autoIsfValues = AIV(
        timestamp = 0L,
        acceIsf = 1.0,
        bgIsf =  1.0,
        ppIsf = 1.0,
        driftIsf = 1.0,
        duraIsf = 1.0,
        finalIsf = 1.0,
        iobThEffective = 0.0
    )
    // Activity detection (steps)
    private val recentSteps5Minutes ; get() = StepService.getRecentStepCount5Min()
    private val recentSteps10Minutes; get() = StepService.getRecentStepCount10Min()
    private val recentSteps15Minutes; get() = StepService.getRecentStepCount15Min()
    private val recentSteps30Minutes; get() = StepService.getRecentStepCount30Min()
    private val recentSteps60Minutes; get() = StepService.getRecentStepCount60Min()
    private val phone_moved; get() = PhoneMovementDetector.phoneMoved()

    override suspend fun onStart() {
        super.onStart()
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(minutesClass).msecs() + glucose.toLong()
            if (variableSens > 0) isfCacheLock.withLock { autoIsfCache.put(key, variableSens) }
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }

    override fun usingDynamicIsf() = true //: Boolean = preferences.get(BooleanKey.ApsUseAutoIsf)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as ProfileSealed.EPS).value.originalPercentage / 100.0
        val sensitivity = runBlocking { calculateVariableIsf(start) }
        if (sensitivity.second == null && caller == "OpenAPSSMBPlugin")
            notificationManager.post(
                NotificationId.DYN_ISF_FALLBACK,
                ApsStrings.fallback_to_isf_no_tdd.withArgs(sensitivity.first),
                level = NotificationLevel.INFO,
                date = start,
                validTo = dateUtil.now() + T.mins(1).msecs())
        else
            notificationManager.dismiss(NotificationId.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() ${sensitivity.first} ${sensitivity.second} ${dateUtil.dateAndTimeAndSecondsString(start)} $caller", start)
        return sensitivity.second?.let { it * multiplier }
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        isfCacheLock.withLock {
            autoIsfCache.forEach { key, value ->
                if (key in start..timestamp) {
                    count++
                    sum += value
                }
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun getSensitivityOverviewString(): String? = null // placeholder for Auto ISF Detailed information for overview

    // autoISF 3.2.1 activity monitor: the phone step counter needs this permission. Asked only while the
    // monitor is switched on. Without it no steps arrive and the monitor stays off (StepService.stepsAvailable).
    override fun requiredPermissions(): List<PermissionGroup> =
        if (preferences.get(BooleanKey.ActivityMonitorDetection))
            listOf(PermissionGroup(listOf("android.permission.ACTIVITY_RECOGNITION"), ApsStrings.activity_monitor_settings_title, ApsStrings.need_step_count_permission))
        else emptyList()

    private val autoIsfCache = LongSparseArray<Double>()

    /** Guards [autoIsfCache]. Was `synchronized(autoIsfCache)`, which is JVM only. */
    private val isfCacheLock = AapsLock()

    private suspend fun calculateVariableIsf(timestamp: Long): Pair<String, Double?> {
        val profile = profileFunction.getProfile(timestamp) ?: return Pair("OFF", null)
        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to minutesClass min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(minutesClass).msecs() + glucose.toLong()
        // Fork 0044: this path serves getIsfMgdl() for ANY caller (overview, NS upload, widgets,
        // automations). Its console lines must not land in the live AUTO-ISF console of the loop run.
        val sensitivity = autoISF(profile, mutableListOf())
        if (sensitivity > 0) {
            // can default to 0, e.g. for the first 2-3 loops in a virgin setup
            aapsLogger.debug("calculateVariableIsf CALC ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
            isfCacheLock.withLock {
                autoIsfCache.put(key, sensitivity)
                if (autoIsfCache.size() > 1000) autoIsfCache.clear()
            }
        }
        // this return is mandatory, otherwise it messed up the AutoISF algo.
        return Pair("OFF", null)
    }

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        // Fork (capability matrix A1): ONE effective settings snapshot per APS run, a pure RAM read.
        // Without a lease the effective value is the base preference, so the run is unchanged.
        val autoIsfSettingsSnapshot = effectiveAutoIsfSettings.snapshot()
        val effectiveIobThPercent = autoIsfSettingsSnapshot.iobThPercentEffective
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(CoreUiStrings.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(CoreUiStrings.no_profile_set))
            return@withContext
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_disabled))
            return@withContext
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
            return@withContext
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, InterfacesStrings.profile_dia, hardLimits.diaRange())) return@withContext
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                InterfacesStrings.profile_carbs_ratio_value,
                hardLimits.icRange()
            )
        ) return@withContext
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSAutoISFPlugin"), InterfacesStrings.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return@withContext
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), CoreUiStrings.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return@withContext
        if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), CoreUiStrings.current_basal_value, 0.01, hardLimits.maxBasal())) return@withContext

        // End of check, start gathering data

        val autoIsfMode = usingDynamicIsf()  // preferences.get(BooleanKey.ApsUseAutoIsf)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, HardLimits.LIMIT_MIN_BG)
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, HardLimits.LIMIT_MAX_BG)
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, CoreUiStrings.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG)
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, CoreUiStrings.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG)
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }
        // for key AutoISF results assign defaults
        autoIsfValues = AIV(
            timestamp = now,
            acceIsf = 1.0,
            bgIsf =  1.0,
            ppIsf = 1.0,
            driftIsf = 1.0,
            duraIsf = 1.0,
            finalIsf = 1.0,
            iobThEffective = 0.0
        )

        var autosensResult = AutosensResult()
        var variableSensitivity = profile.getProfileIsfMgdl()
        val sens = profile.getIsfMgdl("OpenAPSAutoISFPlugin")

        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                return@withContext
            }
            autosensResult = autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens), mgdlHalfBasalExerciseTarget, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        val iobData = iobArray[0]
        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()

        aapsLogger.debug(LTag.APS, "invoke found step counts 5m:$recentSteps5Minutes, 10m:$recentSteps10Minutes, 15m:$recentSteps15Minutes, 30m:$recentSteps30Minutes, 60m:$recentSteps60Minutes")
        consoleError.clear()
        consoleLog.clear()
        val hourOfDay = MidnightUtils.secondsFromMidnight() / 3600
        val hour = max(1, hourOfDay)
        val activityRatio = activityMonitor(isTempTarget, glucoseStatus.glucose, targetBg, hour)
        val activityLog = if (consoleLog.size==0) "Activity Monitor skipped" else consoleLog[0]
        consoleLog.clear()
        var stepActivityDetected = false
        var stepInactivityDetected = false
        if (activityRatio < 1) { stepActivityDetected = true
        } else if (activityRatio>1)   { stepInactivityDetected = true}
        preferences.put(BooleanNonKey.ActivityMonitorStepsActive, stepActivityDetected)
        preferences.put(BooleanNonKey.ActivityMonitorStepsInactive, stepInactivityDetected)
        if (autoIsfMode) {
            variableSensitivity = autoISF(profile, overlay = autoIsfSettingsSnapshot)
        }
        val lastAppStart = preferences.get(LongNonKey.AppStart)
        val elapsedTimeSinceLastStart = (dateUtil.now() - lastAppStart).milliseconds.inWholeMinutes
        val oapsProfile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = sens,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = exerciseMode || highTemptargetRaisesSensitivity, //was false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget),
            // mod activity mode
            activity_detection = preferences.get(BooleanKey.ActivityMonitorDetection) && StepService.stepsAvailable(),
            recent_steps_5_minutes  = recentSteps5Minutes,
            recent_steps_10_minutes = recentSteps10Minutes,
            recent_steps_15_minutes = recentSteps15Minutes,
            recent_steps_30_minutes = recentSteps30Minutes,
            recent_steps_60_minutes = recentSteps60Minutes,
            phone_moved = phone_moved,
            time_since_start = elapsedTimeSinceLastStart,
            now = hourOfDay,
            // end mod
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate),
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = variableSensitivity,
            autoISF_version = autoIsfVersion,
            enable_autoISF = autoIsfWeights,
            autoISF_max = autoISF_max,
            autoISF_min = autoISF_min,
            bgAccel_ISF_weight = bgAccelIsfWeight(autoIsfSettingsSnapshot),
            bgBrake_ISF_weight = bgBrakeIsfWeight(autoIsfSettingsSnapshot),
            pp_ISF_weight = ppIsfWeight(autoIsfSettingsSnapshot),
            lower_ISFrange_weight = lowerIsfRangeWeight(autoIsfSettingsSnapshot),
            higher_ISFrange_weight = higherIsfRangeWeight(autoIsfSettingsSnapshot),
            dura_ISF_weight = duraIsfWeight(autoIsfSettingsSnapshot),
            smb_delivery_ratio = smbDeliveryRatio(autoIsfSettingsSnapshot),
            smb_delivery_ratio_min = smb_delivery_ratio_min,
            smb_delivery_ratio_max = smb_delivery_ratio_max,
            smb_delivery_ratio_bg_range = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange),   //smb_delivery_ratio_bg_range was always in mg/dL
            smb_max_range_extension = smbMaxRangeExtension,
            enableSMB_EvenOn_OddOff_always = enableSMB_EvenOn_OddOff_always,
            iob_threshold_percent = effectiveIobThPercent,
            profile_percentage = profile_percentage
        )

        // Refuse to run the algorithm with degenerate ISF inputs — division by these would produce NaN/Infinity in
        // the result. carb_ratio feeds csf (sens/carb_ratio) and autosensResult.ratio becomes the non-autoISF
        // sensitivityRatio (sens = profile.sens/sensitivityRatio); a non-finite/≤0 value of any cascades to a NaN
        // carbsReq (round() crash). Mirrors the OpenAPSSMBPlugin guard.
        val invalidInputs = !oapsProfile.sens.isFinite() || oapsProfile.sens <= 0.0 ||
            !oapsProfile.carb_ratio.isFinite() || oapsProfile.carb_ratio <= 0.0 ||
            !autosensResult.ratio.isFinite() || autosensResult.ratio <= 0.0 ||
            (autoIsfMode && (!oapsProfile.variable_sens.isFinite() || oapsProfile.variable_sens <= 0.0))
        if (invalidInputs) {
            val msg = "OpenAPS AutoISF aborting: invalid ISF inputs " +
                "autoIsfMode=$autoIsfMode sens=${oapsProfile.sens} carb_ratio=${oapsProfile.carb_ratio} " +
                "autosensRatio=${autosensResult.ratio} variable_sens=${oapsProfile.variable_sens}"
            aapsLogger.error(LTag.APS, msg)
            rxBus.send(EventResetOpenAPSGui(msg))
            return@withContext
        }
        var sensitivityRatio = 1.0
        // TODO eliminate
        val target_bg = (minBg + maxBg) / 2
        val exerciseModeActive = highTemptargetRaisesSensitivity && isTempTarget && target_bg > normalTarget
        val resistanceModeActive = oapsProfile.low_temptarget_lowers_sensitivity && isTempTarget && target_bg < normalTarget
        if ( exerciseModeActive || resistanceModeActive || stepActivityDetected || stepInactivityDetected ) {
            if (exerciseModeActive || resistanceModeActive) {
                // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
                // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
                //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
                val resistanceMax = min(1.5, preferences.get(DoubleKey.AutosensMax))  // additional safety limit
                val c = (mgdlHalfBasalExerciseTarget - normalTarget)
                if (c * (c + target_bg - normalTarget) <= 0.0) {
                    sensitivityRatio = resistanceMax
                } else {
                    sensitivityRatio = c / (c + target_bg - normalTarget)
                    // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                    sensitivityRatio = min(sensitivityRatio, resistanceMax)
                    sensitivityRatio = round(sensitivityRatio, 2)
                    //exerciseRatio = sensitivityRatio
                }
            } else {
                sensitivityRatio = activityRatio
            }
        }
        var iobTH_reduction_ratio = 1.0
        var use_iobTH = false
        if (effectiveIobThPercent != 100) {
            iobTH_reduction_ratio = profile_percentage / 100.0 * sensitivityRatio
            use_iobTH = true
        }
        val iobTHtolerance = 130.0
        val iobTHvirtual = effectiveIobThPercent * iobTHtolerance / 10000.0 * oapsProfile.max_iob * iobTH_reduction_ratio
        // Fork 0040: the SMB budget gate compares against max(bolus IOB, net IOB) instead of net IOB.
        // Negative basal IOB (withheld basal from zero temps) builds up exactly in PROTECTIVE phases and
        // freed bolus headroom right when the ceiling should be tightest. Asymmetric on purpose: positive
        // basal IOB (high temps) still uses budget, withheld basal can no longer create budget.
        // Predictions and eventualBG keep using net IOB - only the budget gate changes.
        val gateIob = iobData.iob - min(0.0, iobData.basaliob)   // = max(bolus IOB, net IOB)
        // Fork 0059: the mode drives dosing as before, the reason goes to the export only.
        val loopSmbDecision = loop_smb(microBolusAllowed, oapsProfile, gateIob, use_iobTH, iobTHvirtual / iobTHtolerance * 100.0)
        val loopWantedSmb = loopSmbDecision.mode
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        val smbRatio = determine_varSMBratio(glucoseStatus.glucose.toInt(), target_bg, loopWantedSmb, autoIsfSettingsSnapshot)

        // Fork 0041/0048: the COVERAGE line in the AUTO-ISF console - the insulin coverage the coverage
        // automations gate on. Raw bg (leads the smoothed value at turns), PROFILE target/ISF/IC (not the
        // TT or the autoISF ISF - a stable denominator) and max(bolus IOB, net IOB) as in the 0040 gate;
        // bolus coverage next to it. Diagnostic only, never read back into dosing.
        try {
            val covRaw = persistenceLayer.getLastGlucoseValue()?.value ?: glucoseStatus.glucose
            val covIsf = profile.getProfileIsfMgdl()
            val covIc = profile.getIc()
            val covTarget = profile.getTargetMgdl()
            val covNeed = max(0.2, (covRaw - covTarget) / covIsf + mealData.mealCOB / covIc)
            val covBolusIob = max(0.0, iobData.iob - iobData.basaliob)
            consoleError.add(
                "Coverage ${(100.0 * gateIob / covNeed).roundToLong()}% (raw ${covRaw.roundToLong()}, profTgt ${covTarget.roundToLong()}, " +
                    "need ${round(covNeed, 2)}U, iobGate ${round(gateIob, 2)}U [max bol/net]) | " +
                    "BolusCov ${(100.0 * covBolusIob / covNeed).roundToLong()}% (bolus ${round(covBolusIob, 2)}U)"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AutoISF <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobCobCalculator.iobArrayToString(iobArray)}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "AutoIsfMode:        $autoIsfMode")
        //aapsLogger.debug(LTag.APS, "AutoISF extras:     ${Json.encodeToString(OapsProfile.serializer(), oapsProfile)}")

        determineBasalAutoISF.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            autoIsfMode = autoIsfMode,
            loop_wanted_smb = loopWantedSmb,
            profile_percentage = profile_percentage,
            smb_ratio = smbRatio,
            smb_max_range_extension = smbMaxRangeExtension,
            iob_threshold_percent = effectiveIobThPercent,
            activity_consoleLog = activityLog,
            auto_isf_consoleError = consoleError,
            auto_isf_consoleLog = consoleLog
        ).also {
            val determineBasalResult = apsResultProvider().with(it)
            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileAutoIsf = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }
        autoIsfValues.timestamp = now
        //aapsLogger.debug(LTag.APS, "autoIsfValues to write contains: $autoIsfValues")
        runCatching { persistenceLayer.insertOrUpdateAutoIsfValues(autoIsfValues) }
            .onFailure { aapsLogger.error(LTag.APS, "AutoIsfValues not stored", it) }
        // Fork (IOB-Action viewer): the state export of this cycle and the signal to the viewer.
        // Write-only and guarded: a failing export never touches the loop.
        exportState(now, profile, glucoseStatus, oapsProfile, iobData, mealData, profile_percentage, smbRatio, loopSmbDecision, autoIsfSettingsSnapshot)
        iobActionExport.loopCycleWritten(now)
        //val autoIsfRecords = persistenceLayer.getAutoIsfValuesFromTime(now-100000L)
        //aapsLogger.debug(LTag.APS, "autoIsfValues records read contain: $autoIsfRecords")
        rxBus.send(EventOpenAPSUpdateGui())
    }

    /**
     * Fork (IOB-Action viewer): iobaction_state.json of this determine-basal cycle - the full loop and
     * autoISF state, so the viewer does not have to parse the log. Same keys as the 3.4 fork. Written
     * only, guarded: an export problem is logged and never reaches the loop.
     */
    private suspend fun exportState(
        now: Long,
        profile: EffectiveProfile,
        glucoseStatus: GlucoseStatus,
        oapsProfile: OapsProfileAutoIsf,
        iobData: IobTotal,
        mealData: MealData,
        profilePercentage: Int,
        smbRatio: Double,
        loopSmbDecision: LoopSmbDecision,
        snapshot: EffectiveAutoIsfSettingsProvider.Snapshot
    ) {
        try {
            val activeTt = persistenceLayer.getTemporaryTargetActiveAt(now)
            val gsAisf = glucoseStatus as? GlucoseStatusAutoIsf
            // The calculation the overview header uses (iobArray[0] is the SMB decision array and
            // reports another basal IOB). Falls back to iobData below.
            val headerIob = notCancelled { iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile) }
            val dbSmbs = notCancelled {
                persistenceLayer.getBolusesFromTimeToTime(now - T.hours(6).msecs(), now, true)
                    .filter { it.type == BS.Type.SMB && it.isValid && it.amount > 0.0 }
                    .map { IobActionExportSink.SmbIn(it.timestamp, it.amount) }
            } ?: emptyList()
            // Recent manual bolus and rescue carbs straight from the database, so the viewer's meal mode
            // and rebound hold need no log. SMBs are excluded.
            val halfHourAgo = now - T.mins(30).msecs()
            val bolusWindowMs = T.mins(15).msecs()
            val normalBoluses = notCancelled {
                persistenceLayer.getBolusesFromTimeToTime(halfHourAgo - bolusWindowMs, now, false)
                    .filter { it.type == BS.Type.NORMAL && it.isValid && it.amount > 0.0 }
            }
            // RESCUE = carbs WITHOUT a manual bolus within +/- 15 min. In HCL, carbs without a manual
            // bolus are eaten because of a low or too much IOB; a real meal carries a manual bolus.
            val rescueCarbs = normalBoluses?.let { boluses ->
                notCancelled {
                    persistenceLayer.getCarbsFromTimeToTimeExpanded(halfHourAgo, now, false)
                        .filter { c -> c.isValid && c.amount > 0.0 && boluses.none { abs(it.timestamp - c.timestamp) <= bolusWindowMs } }
                }
            }
            val mealSnapshot = notCancelled { automationStateService.getStateSnapshot("MEAL_ACTIVE") }
            val profileName = notCancelled { profileFunction.getProfileName() }
            val profileNameRemaining = notCancelled { profileFunction.getProfileNameWithRemainingTime() }
            val pump = activePlugin.activePump

            val state = buildJsonObject {
                putOpt("ts", now)
                put("aps", lastAPSResult?.json() ?: JsonObject(emptyMap()))
                put("autoIsf", buildJsonObject {
                    // The factors come from the fit and can be non-finite on a flat window: left out then.
                    putOpt("acce", autoIsfValues.acceIsf.finiteOrNull())
                    putOpt("bg", autoIsfValues.bgIsf.finiteOrNull())
                    putOpt("pp", autoIsfValues.ppIsf.finiteOrNull())
                    putOpt("dura", autoIsfValues.duraIsf.finiteOrNull())
                    putOpt("final", autoIsfValues.finalIsf.finiteOrNull())
                    putOpt("iobThEffectiveU", autoIsfValues.iobThEffective.finiteOrNull())
                    // 0059: the exact branch of the SMB gate (smb-disabled alone is ambiguous).
                    putOpt("smbGateMode", loopSmbDecision.mode)
                    putOpt("smbGateReason", loopSmbDecision.reason)
                })
                // The pump bolus increment and the BG time stamp this run used. Absent when unknown.
                putOpt("bolusIncrementU", oapsProfile.bolus_increment.takeIf { it.isFinite() && it > 0.0 })
                putOpt("apsGlucoseTs", glucoseStatus.date.takeIf { it > 0L })
                put("smb", buildJsonObject {
                    putOpt("ratio", smbRatio)                       // effective SMB delivery ratio of this cycle
                    putOpt("ratio_fixed", smbDeliveryRatio(snapshot))
                    putOpt("ratio_min", smb_delivery_ratio_min)
                    putOpt("ratio_max", smb_delivery_ratio_max)
                    putOpt("ratio_bg_range", smb_delivery_ratio_bg_range)
                })
                // The SMB decision of this cycle (wanted vs delivered, state, which limit shaped it).
                determineBasalAutoISF.lastSmbDecision?.let { d ->
                    put("smbDecision", buildJsonObject {
                        putOpt("wantedU", d.wantedU.finiteOrNull())
                        putOpt("deliveredU", d.deliveredU.finiteOrNull())
                        putOpt("maxBolusU", d.maxBolusU?.finiteOrNull())
                        putOpt("state", d.state)
                        putOpt("limitReason", d.limitReason)
                        putOpt("capIobU", d.capIobU?.finiteOrNull())
                        putOpt("headroomU", d.headroomU?.finiteOrNull())
                        putOpt("headroomAfterU", d.headroomAfterU?.finiteOrNull())
                    })
                }
                // Shadow telemetry: two fit horizons, the factor chain, the pp candidate. Export only.
                put("shadow", buildJsonObject {
                    AutoIsfShadow.fit?.let { f ->
                        put("fit", buildJsonObject {
                            putOpt("acceShort", f.acceShort.finiteOrNull())
                            putOpt("winShortMin", f.winShortMin.finiteOrNull())
                            putOpt("acceMid", f.acceMid?.finiteOrNull())
                            putOpt("winMidMin", f.winMidMin?.finiteOrNull())
                            putOpt("acceBest", f.acceBest.finiteOrNull())
                            putOpt("winBestMin", f.winBestMin.finiteOrNull())
                            putOpt("rSquBest", f.rSquBest.finiteOrNull())
                            putOpt("rmseBest", f.rmseBest?.finiteOrNull())
                            putOpt("nPoints", f.nPoints)
                            putOpt("signAgreeShortMid", f.signAgreeShortMid)
                            putOpt("use1MinuteRaw", f.use1MinuteRaw)
                            putOpt("cycleTs", f.capturedAtMs)
                        })
                    }
                    AutoIsfShadow.factors?.let { fc ->
                        put("factors", buildJsonObject {
                            putOpt("strongestPreBrake", fc.strongestPreBrake.finiteOrNull())
                            putOpt("acceBrake", fc.acceBrake?.finiteOrNull())
                            putOpt("liftAfterBrake", fc.liftAfterBrake.finiteOrNull())
                            putOpt("finalAfterClamp", fc.finalAfterClamp.finiteOrNull())
                            putOpt("ppLive", fc.ppLive.finiteOrNull())
                            putOpt("ppCandidate", fc.ppCandidate?.finiteOrNull())
                            putOpt("persistentDelta", fc.persistentDelta?.finiteOrNull())
                            putOpt("cycleTs", fc.capturedAtMs)
                        })
                    }
                })
                // The fork's truth about the MEAL_ACTIVE automation state, so the viewer can see a split brain.
                mealSnapshot?.let { snap ->
                    val known = snap.known && snap.value in listOf("true", "false")
                    put("automation", buildJsonObject {
                        putOpt("mealActiveKnown", known)
                        putOpt("mealActive", known && snap.value == "true")
                    })
                }
                put("profile", buildJsonObject {
                    putOpt("max_iob", oapsProfile.max_iob)
                    putOpt("sens", oapsProfile.sens)
                    putOpt("target_bg", oapsProfile.target_bg)
                    putOpt("min_bg", oapsProfile.min_bg)
                    putOpt("max_bg", oapsProfile.max_bg)
                    putOpt("carb_ratio", oapsProfile.carb_ratio)
                    putOpt("profile_percentage", profilePercentage)
                    // The legacy field carries the BASE in both files (viewer compatibility); the
                    // effective value, the state and the lease come separately.
                    putOpt("iob_threshold_percent", snapshot.iobThPercentBase)
                    putOpt("iob_threshold_percent_base", snapshot.iobThPercentBase)
                    putOpt("iob_threshold_percent_effective", snapshot.iobThPercentEffective)
                    putOpt("iobth_override_state", snapshot.overrideState.name)
                    putOpt("iobth_lease_id", snapshot.leaseId)
                    putOpt("iobth_lease_version", snapshot.leaseVersion)
                    putOpt("iobth_lease_expires_at", snapshot.expiresAtWallMs)
                    putOpt("name", profileName)
                    putOpt("name_remaining", profileNameRemaining)
                    putOpt("enable_autoISF", autoIsfWeights)
                    putOpt("current_basal", oapsProfile.current_basal)
                    putOpt("bgAccel_ISF_weight", bgAccelIsfWeight(snapshot))
                    putOpt("pp_ISF_weight", ppIsfWeight(snapshot))
                    putOpt("bgBrake_ISF_weight", bgBrakeIsfWeight(snapshot))
                    putOpt("dura_ISF_weight", duraIsfWeight(snapshot))
                })
                put("iob", buildJsonObject {
                    val net = headerIob?.iob ?: iobData.iob
                    val basal = headerIob?.basaliob ?: iobData.basaliob
                    putOpt("net", net)
                    putOpt("basal", basal)
                    putOpt("bolus", net - basal)
                    putOpt("activity", headerIob?.activity ?: iobData.activity)
                })
                put("device", buildJsonObject {
                    putOpt("reservoir", notCancelled { ch.fromPump(pump.reservoirLevel.value) }?.finiteOrNull())
                    putOpt("battery", pump.batteryLevel.value)
                })
                putOpt("carbsReq", lastAPSResult?.carbsReq)
                normalBoluses?.firstOrNull { it.timestamp >= halfHourAgo }?.let { manual ->
                    putOpt("manualBolusU", manual.amount)
                    putOpt("manualBolusAgeMin", ((now - manual.timestamp) / 60000L).toInt())
                }
                if (!rescueCarbs.isNullOrEmpty()) {
                    putOpt("rescueCarbsTakenG", rescueCarbs.sumOf { it.amount })
                    putOpt("rescueCarbsAgeMin", ((now - rescueCarbs.maxOf { it.timestamp }) / 60000L).toInt())
                }
                gsAisf?.let { gs ->
                    put("glucose", buildJsonObject {
                        putOpt("bgAcceleration", gs.bgAcceleration.finiteOrNull())
                        putOpt("corrSqu", gs.corrSqu.finiteOrNull())
                        putOpt("delta", gs.delta.finiteOrNull())
                        putOpt("shortAvgDelta", gs.shortAvgDelta.finiteOrNull())
                        putOpt("longAvgDelta", gs.longAvgDelta.finiteOrNull())
                        putOpt("duraISFminutes", gs.duraISFminutes.finiteOrNull())
                        putOpt("duraISFaverage", gs.duraISFaverage.finiteOrNull())
                    })
                }
                put("meal", buildJsonObject {
                    putOpt("mealCOB", mealData.mealCOB.finiteOrNull())
                    putOpt("carbs", mealData.carbs)
                    putOpt("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation.finiteOrNull())
                    // 999 is the AAPS sentinel for "no valid 1 h history"; it switches the UAM prediction
                    // off. Made visible, so it is clear WHY the loop doses more carefully after a restart.
                    putOpt("slopeFromMinDeviation", mealData.slopeFromMinDeviation.finiteOrNull())
                })
                // Which filter made the loop value, so the viewer sees it without the log. Passed through as
                // written; without the key no Q1 cycle has run.
                notCancelled {
                    preferences.get(StringNonKey.FslUkfQ1Status).takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject }
                }?.let { put("filter", it) }
                activeTt?.let { tt ->
                    put("tt", buildJsonObject {
                        putOpt("target", tt.lowTarget)
                        putOpt("remainingMin", (tt.timestamp + tt.duration - now) / 60000L)
                    })
                }
            }
            iobActionExport.writeState(
                state, dbSmbs,
                IobActionExportSink.Factors(
                    acce = autoIsfValues.acceIsf.finiteOrNull(),
                    bg = autoIsfValues.bgIsf.finiteOrNull(),
                    pp = autoIsfValues.ppIsf.finiteOrNull(),
                    dura = autoIsfValues.duraIsf.finiteOrNull(),
                    final = autoIsfValues.finalIsf.finiteOrNull(),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "IobAction state export failed", e)
        }
    }

    /** The value, or null when it throws; coroutine cancellation is passed on. */
    private inline fun <T> notCancelled(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorAutoIsf.getGlucoseStatusData(allowOldData)

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(ApsStrings.limiting_iob, maxIobPref, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(ApsStrings.limiting_iob, hardLimits.maxIobSMB(), rh.gs(ApsStrings.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(ApsStrings.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(CoreUiStrings.limitingbasalratio, maxBasal, rh.gs(ApsStrings.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(CoreUiStrings.limitingbasalratio, maxFromBasalMultiplier, rh.gs(ApsStrings.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(CoreUiStrings.limitingbasalratio, maxFromDaily, rh.gs(ApsStrings.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(ApsStrings.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(ApsStrings.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseAutosens)
        if (!enabled) value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        return value
    }

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        // Pass NaN AND ±Infinity through untouched - Math.round saturates at Long.MAX_VALUE, which would
        // turn an infinite value into a normal looking number and hide it from the invalidInputs check.
        if (!value.isFinite()) return value
        val scale = 10.0.pow(digits.toDouble())
        return (value * scale).roundToLong() / scale
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    fun convert_bg_to_units(value: Double, profile: OapsProfileAutoIsf): Double =
        if (profile.out_units == "mmol/L") value * Constants.MGDL_TO_MMOLL else value

    suspend fun activityMonitor(isTempTarget: Boolean, bg: Double, target_bg: Double, now: Int): Double {
        if (preferences.get(BooleanKey.ActivityMonitorShowStepsFromSmartphone) && StepService.stepsAvailable()) {
            val nowMillis = dateUtil.now()
            val stepsCount = SC(
                duration = 0,
                timestamp = nowMillis,
                steps5min = recentSteps5Minutes,
                steps10min = recentSteps5Minutes + recentSteps10Minutes,
                steps15min = recentSteps5Minutes + recentSteps10Minutes + recentSteps15Minutes,
                steps30min = recentSteps30Minutes,
                steps60min = recentSteps60Minutes,
                steps180min = StepService.getRecentStepCount180Min(),
                device = "Smartphone"
            )
            runCatching { persistenceLayer.insertOrUpdateStepsCounts(listOf(stepsCount)) }
                .onFailure { aapsLogger.error(LTag.APS, "Steps count not stored", it) }
        }

        val phoneMoved = PhoneMovementDetector.phoneMoved()
        val lastAppStart = preferences.get(LongNonKey.AppStart)
        //val elapsedTimeSinceLastStart = (dateUtil.now() - lastAppStart) / 60000
        val time_since_start = (dateUtil.now() - lastAppStart).milliseconds.inWholeMinutes
        // Fork (v4 port): without step data (no sensor or no permission) the monitor is off, never "inactive".
        val activityDetection = preferences.get(BooleanKey.ActivityMonitorDetection) && StepService.stepsAvailable()
        val activity_scale_factor = preferences.get(DoubleKey.ActivityScaleFactor)              // profile.activity_scale_factor;
        val inactivity_scale_factor = preferences.get(DoubleKey.InactivityScaleFactor)          // profile.inactivity_scale_factor;
        var activityRatio = 1.0
        val ignore_inactivity_overnight = preferences.get(BooleanKey.ActivityMonitorOvernight)  // profile.ignore_inactivity_overnight;
        val inactivity_idle_start =  preferences.get(IntKey.ActivityMonitorIdleStart)           // profile.inactivity_idle_start;
        val inactivity_idle_end = preferences.get(IntKey.ActivityMonitorIdleEnd)                // profile.inactivity_idle_end;

        val existSleepState = automationStateService.hasStateValues("Sleeping")
        val useSleepState = automationStateService.inState("Sleeping", "True")
        aapsLogger.debug(LTag.APS, "State json for Sleep mode: {\"Sleeping\":\"${automationStateService.getState("Sleeping")}\"}")
        // really still sleeping?
        if (existSleepState && useSleepState && (recentSteps5Minutes+recentSteps10Minutes+recentSteps15Minutes < recentSteps30Minutes) ) {  // && now>=inactivity_idle_end) {
            automationStateService.setState("query_got_up", "query_it")
        }
        aapsLogger.debug(LTag.APS, "State json for got up query: {\"query_got_up\":\"${automationStateService.getState("query_got_up")}\"}")

        if ( !activityDetection ) {
            consoleLog.add("Activity monitor disabled in settings")
        } else if ( isTempTarget ) {
            consoleLog.add("Activity monitor disabled: tempTarget")
        } else if ( !phoneMoved ) {
            consoleLog.add("Activity monitor disabled: Phone seems not to be carried for the last 15m")
        } else {
            if ( time_since_start < 60 && recentSteps60Minutes <= 200 ) {
                consoleLog.add("Activity monitor initialising for ${60 - time_since_start} more minutes: inactivity detection disabled")
            } else if ( useSleepState && recentSteps60Minutes <= 200) {
                consoleLog.add("Activity monitor disabled inactivity detection: sleeping state")
            } else if ( (( inactivity_idle_start>inactivity_idle_end && ( now>=inactivity_idle_start || now<inactivity_idle_end ) )  // includes midnight
                || ( now>=inactivity_idle_start && now<inactivity_idle_end)  )                                                       // excludes midnight
                && recentSteps60Minutes <= 200 && ignore_inactivity_overnight && !existSleepState) {
                consoleLog.add("Activity monitor disabled inactivity detection: sleeping hours")
            } else if ( recentSteps5Minutes > 300 || recentSteps10Minutes > 300  || recentSteps15Minutes > 300  || recentSteps30Minutes > 1500 || recentSteps60Minutes > 2500 ) {
                activityRatio = 1 - 0.3 * activity_scale_factor
                consoleLog.add("Activity monitor detected activity, sensitivity ratio: $activityRatio")
            } else if ( recentSteps5Minutes > 200 || recentSteps10Minutes > 200  || recentSteps15Minutes > 200
                || recentSteps30Minutes > 500 || recentSteps60Minutes > 800 ) {
                activityRatio = 1 - 0.15 * activity_scale_factor
                consoleLog.add("Activity monitor detected partial activity, sensitivity ratio: $activityRatio")
            } else if ( bg < target_bg && recentSteps60Minutes <= 200 ) {
                consoleLog.add("Activity monitor disabled inactivity detection: bg < target")
            } else if ( recentSteps60Minutes < 50 ) {
                activityRatio = 1 + 0.2 * inactivity_scale_factor
                consoleLog.add("Activity monitor detected inactivity, sensitivity ratio: $activityRatio")
            } else if ( recentSteps60Minutes <= 200 ) {
                activityRatio = 1 + 0.1 * inactivity_scale_factor
                consoleLog.add("Activity monitor detected partial inactivity, sensitivity ratio: $activityRatio")
            } else {
                consoleLog.add("Activity monitor detected neutral state")  //, sensitivity ratio unchanged: $activityRatio")
            }
        }
        preferences.put(DoubleNonKey.ActivityMonitorRatio, activityRatio)
        var activityMsg = "Activity Monitor json: {\"activity_scale_factor\":$activity_scale_factor,\"inactivity_scale_factor\":$inactivity_scale_factor"
        activityMsg += ",\"recentSteps5Minutes\":$recentSteps5Minutes,\"recentSteps10Minutes\":$recentSteps10Minutes,\"recentSteps15Minutes\":$recentSteps15Minutes"
        activityMsg += ",\"recentSteps30Minutes\":$recentSteps30Minutes,\"recentSteps60Minutes\":$recentSteps60Minutes"
        activityMsg += ",\"phone_moved\":$phoneMoved,\"time_since_start\":$time_since_start,\"activity_detection\":$activityDetection"
        activityMsg += ",\"ignore_inactivity_overnight\":$ignore_inactivity_overnight,\"inactivity_idle_start\":$inactivity_idle_start,\"inactivity_idle_end\":$inactivity_idle_end}"
        aapsLogger.debug(LTag.APS, activityMsg)
        return activityRatio
    }

    // Fork 0044: callers outside the loop pass their own list, so the live console only gets the
    // loop's own factor block. The returned value is the same either way.
    // Fork (A1): [overlay] is the snapshot of the APS run, null outside a run (base weights).
    suspend fun autoISF(profile: Profile, console: MutableList<String> = consoleError, overlay: EffectiveAutoIsfSettingsProvider.Snapshot? = null): Double {
        val bgAccel_ISF_weight = bgAccelIsfWeight(overlay)
        val bgBrake_ISF_weight = bgBrakeIsfWeight(overlay)
        val pp_ISF_weight = ppIsfWeight(overlay)
        val dura_ISF_weight = duraIsfWeight(overlay)
        val sens = profile.getProfileIsfMgdl()
        val glucose_status = glucoseStatusCalculatorAutoIsf.getGlucoseStatusData(allowOldData = false)

        val high_temptarget_raises_sensitivity = exerciseMode || highTemptargetRaisesSensitivity
        var target_bg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            target_bg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }
        val activityRatio = preferences.get(DoubleNonKey.ActivityMonitorRatio)
        val stepActivityDetected = preferences.get(BooleanNonKey.ActivityMonitorStepsActive)
        val stepInactivityDetected = preferences.get(BooleanNonKey.ActivityMonitorStepsInactive)
        var sensitivityRatio = 1.0
        val exerciseModeActive = high_temptarget_raises_sensitivity && isTempTarget && target_bg > normalTarget
        val resistanceModeActive = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens)  && isTempTarget && target_bg < normalTarget
        if ( exerciseModeActive || resistanceModeActive || stepActivityDetected || stepInactivityDetected ) {
            if ( exerciseModeActive || resistanceModeActive ) {
                // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
                // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
                //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
                val resistanceMax = min(1.5, preferences.get(DoubleKey.AutosensMax))  // additional safety limit
                val c = (mgdlHalfBasalExerciseTarget - normalTarget)
                if (c * (c + target_bg - normalTarget) <= 0.0) {
                    sensitivityRatio = resistanceMax
                    // console.add("Sensitivity decrease for temp target of $target_bg limited by Autosens_max; ")

                } else {
                    sensitivityRatio = c / (c + target_bg - normalTarget)
                    // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                }
                sensitivityRatio = min(sensitivityRatio, resistanceMax)
                sensitivityRatio = round(sensitivityRatio, 2)

            } else if ( stepActivityDetected ) {
                sensitivityRatio = activityRatio
            } else if ( stepInactivityDetected ) {
                sensitivityRatio = activityRatio
            }
        } else {
            var autosensResult = AutosensResult()

            if (constraintsChecker.isAutosensModeEnabled().value()) {
                iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")?.also {
                    autosensResult = it.autosensResult
                }
            } else autosensResult.sensResult = "autosens disabled"
            sensitivityRatio = autosensResult.ratio
        }
        var skipWeights = false
        val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongNonKey.FslCalibrationStart)) / 60000
        val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
        if (calibrationStopsSMB) {
            console.add("AutoISF weights disabled while calibrating")
            skipWeights = true
        } else if (!usingDynamicIsf() || !autoIsfWeights || glucose_status == null) {
            console.add("AutoISF weights disabled in Preferences")
            skipWeights = true
        }
        if (skipWeights) {
            console.add("----------------------------------")
            console.add("end AutoISF")
            console.add("----------------------------------")
            return round(sens / sensitivityRatio, 1)
        }
        val autosensResult = AutosensResult()

        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSAutoISFPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                return sens
            }
            autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"

        val dura05: Double = glucose_status!!.duraISFminutes
        val avg05: Double = glucose_status.duraISFaverage
        val maxISFReduction: Double = autoISF_max
        var sens_modified = false
        var pp_ISF = 1.0
        var acce_ISF = 1.0
        var acce_weight = 1.0
        val bg_off = target_bg + 10.0 - glucose_status.glucose                      // move from central BG=100 to target+10 as virtual BG'=100

        // calculate acce_ISF from bg acceleration and adapt ISF accordingly
        val fit_corr: Double = glucose_status.corrSqu
        val bg_acce: Double = glucose_status.bgAcceleration
        //console.add("Parabola fit results were acceleration:${round(bg_acce, 2)}, correlation:$fit_corr, duration:${glucose_status.parabolaMinutes}m")
        if (glucose_status.a2 != 0.0 && fit_corr >= 0.9) {
            var minmax_delta: Double = -glucose_status.a1 / 2 / glucose_status.a2 * 5      // back from 5min block to 1 min
            val minmax_value: Double = round(glucose_status.a0 - minmax_delta * minmax_delta / 25 * glucose_status.a2, 1)
            minmax_delta = round(minmax_delta, 1)
            if (minmax_delta > 0 && bg_acce < 0) {
                console.add("Parabolic fit extrapolates a maximum of ${convert_bg(minmax_value)} in about $minmax_delta minutes")
            } else if (minmax_delta > 0 && bg_acce > 0.0) {

                console.add("Parabolic fit extrapolates a minimum of ${convert_bg(minmax_value)} in about $minmax_delta minutes")
                if (minmax_delta <= 30 && minmax_value < target_bg) {   // start braking
                    acce_weight = -bgBrake_ISF_weight
                    console.add("extrapolation below target soon: use bgBrake_ISF_weight instead")
                }
            }
        }
        if (fit_corr < 0.9) {
            console.add("acce_ISF adaptation by-passed as correlation ${round(fit_corr, 3)} is too low")
        } else {
            val fit_share = 10 * (fit_corr - 0.9)                            // 0 at correlation 0.9, 1 at 1.00
            var cap_weight = 1.0                                             // full contribution above target
            if (acce_weight == 1.0 && glucose_status.glucose < target_bg) {  // below target acce goes towards target
                if (bg_acce > 0) {
                    if (bg_acce > 1) {
                        cap_weight = 0.5
                    }            // halve the effect below target
                    acce_weight = bgBrake_ISF_weight
                } else if (bg_acce < 0) {
                    acce_weight = bgAccel_ISF_weight
                }
            } else if (acce_weight == 1.0) {                                 // above target acce goes away from target
                if (bg_acce < 0.0) {
                    acce_weight = bgBrake_ISF_weight
                } else if (bg_acce > 0.0) {
                    acce_weight = bgAccel_ISF_weight
                }
            }
            acce_ISF = 1.0 + bg_acce * cap_weight * acce_weight * fit_share
            console.add("acce_ISF adaptation is ${round(acce_ISF, 2)}")
            if (acce_ISF != 1.0) {
                sens_modified = true
            }
        }
        autoIsfValues.acceIsf = acce_ISF

        val bg_ISF = 1 + interpolate(100 - bg_off, overlay)
        console.add("bg_ISF adaptation is ${round(bg_ISF, 2)}")
        autoIsfValues.bgIsf = bg_ISF
        var liftISF: Double
        val final_ISF: Double
        if (bg_ISF < 1.0) {
            liftISF = min(bg_ISF, acce_ISF)
            if (acce_ISF > 1.0) {
                liftISF = bg_ISF * acce_ISF                                 // bg_ISF could become > 1 now
                console.add("bg_ISF adaptation lifted to ${round(liftISF, 2)} as bg accelerates already")
            }
            final_ISF = withinISFlimits(liftISF, autoISF_min, maxISFReduction, sensitivityRatio, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected, console)
            // Fork shadow (export only, the live loop call only): the factor chain below target.
            if (console === consoleError) AutoIsfShadow.factors = AutoIsfShadow.FactorShadow(
                strongestPreBrake = min(bg_ISF, acce_ISF), acceBrake = acce_ISF.takeIf { it < 1.0 },
                liftAfterBrake = liftISF, finalAfterClamp = final_ISF,
                ppLive = 1.0, ppCandidate = null, persistentDelta = null,
            )
            return min(720.0, round(sens / final_ISF, 1))         // observe ISF maximum of 720(?)
        } else if (bg_ISF > 1.0) {
            sens_modified = true
        }

        val bg_delta = glucose_status.delta
        val deltaType = "pp"
        when {
            bg_off > 0.0                     -> {
                console.add("${deltaType}_ISF adaptation by-passed as average glucose < $target_bg+10")
            }

            glucose_status.shortAvgDelta < 0 -> {
                console.add("${deltaType}_ISF adaptation by-passed as no rise or too short lived")
            }

            else                             -> {
                pp_ISF = 1.0 + max(0.0, bg_delta * pp_ISF_weight)
                console.add("pp_ISF adaptation is ${round(pp_ISF, 2)}")
                if (pp_ISF != 1.0) {
                    sens_modified = true
                }

            }
        }
        autoIsfValues.ppIsf = pp_ISF

        // Fork shadow - pp persistence CANDIDATE (does NOT dose): computed fresh, no stored peak. Only
        // valid while delta, short and long average delta are all positive and the pp zone is active
        // (bg above target + 10, as live). With delta <= 0 the live value applies (candidate null).
        val ppCandidate: Double? = if (console === consoleError &&
            bg_off <= 0.0 && bg_delta > 0.0 &&
            glucose_status.shortAvgDelta > 0.0 && glucose_status.longAvgDelta > 0.0
        ) {
            val persistentDelta = max(bg_delta, min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))
            1.0 + max(0.0, persistentDelta * pp_ISF_weight)
        } else null

        var dura_ISF = 1.0
        val weightISF: Double = dura_ISF_weight
        when {
            dura05 < 10.0      -> {
                console.add("dura_ISF by-passed; bg is only $dura05 m at level $avg05")
            }

            avg05 <= target_bg -> {
                console.add("dura_ISF by-passed; avg. glucose $avg05 below target $target_bg")
            }

            else               -> {
                // fight the resistance at high levels
                val dura05Weight = dura05 / 60
                val avg05Weight = weightISF / target_bg
                dura_ISF += dura05Weight * avg05Weight * (avg05 - target_bg)
                sens_modified = true
                console.add("dura_ISF adaptation is ${round(dura_ISF, 2)} because ISF ${round(sens, 1)} did not do it for ${round(dura05, 1)}m")
            }
        }
        autoIsfValues.duraIsf = dura_ISF

        if (sens_modified) {
            val strongestPreBrake = max(dura_ISF, max(bg_ISF, max(acce_ISF, pp_ISF)))
            liftISF = strongestPreBrake
            if (acce_ISF < 1.0) {
                console.add("strongest autoISF factor ${round(liftISF, 2)} weakened to ${round(liftISF * acce_ISF, 2)} as bg decelerates already")
                liftISF = liftISF * acce_ISF
            }
            final_ISF = withinISFlimits(liftISF, autoISF_min, maxISFReduction, sensitivityRatio, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected, console)
            // Fork shadow - the factor chain (the live loop call only; export only, doses unchanged).
            if (console === consoleError) AutoIsfShadow.factors = AutoIsfShadow.FactorShadow(
                strongestPreBrake = strongestPreBrake, acceBrake = acce_ISF.takeIf { it < 1.0 },
                liftAfterBrake = liftISF, finalAfterClamp = final_ISF,
                ppLive = pp_ISF, ppCandidate = ppCandidate,
                persistentDelta = ppCandidate?.let { max(bg_delta, min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)) },
            )
            return round(sens / final_ISF, 1)
        }
        console.add("----------------------------------")
        console.add("end AutoISF")
        console.add("----------------------------------")
        // Fork shadow - a neutral cycle (no factor active), so the export never shows an old chain.
        if (console === consoleError) AutoIsfShadow.factors = AutoIsfShadow.FactorShadow(
            strongestPreBrake = 1.0, acceBrake = null, liftAfterBrake = 1.0,
            finalAfterClamp = 1.0, ppLive = pp_ISF, ppCandidate = ppCandidate, persistentDelta = null,
        )
        return round(sens / sensitivityRatio, 1)     // nothing changed
    }

    // Fork (A1): [overlay] as in autoISF - the range weights of the run, null = base.
    fun interpolate(xdata: Double, overlay: EffectiveAutoIsfSettingsProvider.Snapshot? = null): Double {   // interpolate ISF behaviour based on polygons defining nonlinear functions defined by value pairs for ...
        //  ...             <----------------------  glucose  ---------------------->
        val polyX = arrayOf(50.0, 60.0, 80.0, 90.0, 100.0, 110.0, 150.0, 180.0, 200.0)
        val polyY = arrayOf(-0.5, -0.5, -0.3, -0.2, 0.0, 0.0, 0.5, 0.7, 0.7)
        val polymax: Int = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        var stepT = polyX[polymax]
        var sValold = polyY[polymax]

        var newVal = 1.0
        var lowVal = 1.0
        val topVal: Double
        val lowX: Double
        val topX: Double
        val myX: Double
        var lowLabl = step

        if (step > xdata) {
            // extrapolate backwards
            stepT = polyX[1]
            sValold = polyY[1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else if (stepT < xdata) {
            // extrapolate forwards
            step = polyX[polymax - 1]
            sVal = polyY[polymax - 1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else {
            // interpolate
            for (i: Int in 0..polymax) {
                step = polyX[i]
                sVal = polyY[i]
                if (step == xdata) {
                    newVal = sVal
                    break
                } else if (step > xdata) {
                    topVal = sVal
                    lowX = lowLabl
                    myX = xdata
                    topX = step
                    newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
                    break
                }
                lowVal = sVal
                lowLabl = step
            }
        }
        newVal = if (xdata > 100) {
            newVal * higherIsfRangeWeight(overlay)
        } else {
            newVal * lowerIsfRangeWeight(overlay)
        }
        return newVal
    }

    fun withinISFlimits(
        liftISF: Double, minISFReduction: Double, maxISFReduction: Double, sensitivityRatio: Double,
        exerciseModeActive: Boolean, resistanceModeActive: Boolean, stepActivityDetected:Boolean, stepInactivityDetected: Boolean,
        console: MutableList<String> = consoleError
    ): Double {
        var liftISFlimited: Double = liftISF
        if (liftISF < minISFReduction) {
            console.add("weakest autoISF factor ${round(liftISF, 2)} limited by autoISF_min $minISFReduction")
            liftISFlimited = minISFReduction
        } else if (liftISF > maxISFReduction) {
            console.add("strongest autoISF factor ${round(liftISF, 2)} limited by autoISF_max $maxISFReduction")
            liftISFlimited = maxISFReduction
        }
        val finalISF: Double
        var originSens = ""
        when {
            exerciseModeActive          -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of TT modification
                originSens = "including exercise mode impact"
            }
            resistanceModeActive        -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of TT modification
                originSens = "including resistance mode impact"
            }
            stepActivityDetected        -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of activity detection
                originSens  = "including activity detection impact"
            }
            stepInactivityDetected      -> {
                finalISF = liftISFlimited * sensitivityRatio                //# on top of inactivity detection
                originSens  = "including inactivity detection impact"
            }
            liftISFlimited >= 1         -> {                                // can we evr get here?
                finalISF = max(liftISFlimited, sensitivityRatio)
                if (liftISFlimited < sensitivityRatio) {
                    originSens = "from low TT modifier"
                }
            }
            else                        -> {
                finalISF = min(liftISFlimited, sensitivityRatio)            // low TT lowers sensitivity dominates
            }
        }
        console.add("final ISF factor is ${round(finalISF, 2)} " + originSens)
        console.add("----------------------------------")
        console.add("end AutoISF")
        console.add("----------------------------------")
        autoIsfValues.finalIsf = finalISF
        return finalISF
    }

    /**
     * Fork 0059: the exact branch of the SMB gate, for the export. The modes and every branch
     * condition are UNCHANGED (dosing identical); [reason] only names the branch.
     */
    data class LoopSmbDecision(val mode: String, val reason: String)

    fun loop_smb(microBolusAllowed: Boolean, profile: OapsProfileAutoIsf, iob_data_iob: Double, useIobTh: Boolean, iobThEffective: Double): LoopSmbDecision {
        // Fork: the same truth as the calculation - profile.iob_threshold_percent IS the effective value
        // of this run (no second preference read in the same cycle).
        val iobThUser = profile.iob_threshold_percent
        if (useIobTh) {
            val iobThPercent: Double
            if ( profile.max_iob<0.001 ) {
                iobThPercent = 0.0
                consoleLog.add("User setting iobTH disabled in LGS mode")
            } else {
                iobThPercent = round(iobThEffective/profile.max_iob*100.0, 0)
            }
            if (iobThPercent == iobThUser.toDouble()) {
                consoleLog.add("User setting iobTH=$iobThUser% not modulated")
            } else if (iobThPercent > 0.0) {
                consoleLog.add("User setting iobTH=$iobThUser% modulated to ${iobThPercent.toInt()}% or ${round(iobThEffective, 2)}U")
                consoleLog.add("  due to profile %, exercise mode or similar")
            }
        } else {
            consoleLog.add("User setting iobTH=100% disables iobTH method")
        }
        autoIsfValues.iobThEffective = if (useIobTh) iobThEffective else profile.max_iob

        if (!microBolusAllowed) {
            return LoopSmbDecision("AAPS", "microbolus-disabled")         // see message in enable_smb
        }

        if (preferences.get(BooleanKey.FslCalibrationTrigger)) {
            preferences.put(LongNonKey.FslCalibrationStart, dateUtil.now())
            preferences.put(BooleanKey.FslCalibrationTrigger, false)
            preferences.put(BooleanKey.FslCalibrationEnd, false)
        }
        val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongNonKey.FslCalibrationStart)) / 60000
        val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
        var CalibrationMsg = "Calibration json: {\"calibrationStart\":${preferences.get(LongNonKey.FslCalibrationStart)},\"calibrationIgnore\":${preferences.get(BooleanKey.FslCalibrationEnd)}"
        CalibrationMsg += "}"
        aapsLogger.debug(LTag.APS, CalibrationMsg)
        if (calibrationStopsSMB) {
            consoleLog.add("SMB disabled while calibrating for another ${calibrationMinutes}m")
            return LoopSmbDecision("blocked", "calibration")
        } else if (enableSMB_EvenOn_OddOff_always) {
            //TODO: cleaner conversion back to original mmol/L if applicable
            var target = convert_bg_to_units(profile.target_bg, profile)
            // val msgType: String
            val evenTarget: Boolean
            val msgUnits: String
            val msgTail: String
            if (profile.out_units == "mmol/L") {
                evenTarget = round(target * 10.0, 0).toInt() % 2 == 0
                target = round(target, 1)
                msgUnits = "has"
                msgTail = "decimal"
            } else {
                evenTarget = round(target, 0).toInt() % 2 == 0
                target = round(target, 0)
                msgUnits = "is"
                msgTail = "number"
            }
            val msgEven: String = if (evenTarget) "even" else "odd"

            if (!evenTarget) {
                consoleLog.add("SMB disabled; current target $target $msgUnits $msgEven $msgTail")
                consoleLog.add("Loop allows minimal power")
                return LoopSmbDecision("blocked", "odd-target")
            } else if (profile.max_iob == 0.0) {
                consoleLog.add("SMB disabled because of max_iob=0")
                return LoopSmbDecision("blocked", "max-iob-zero")
            } else if (useIobTh && iobThEffective < iob_data_iob) {
                consoleLog.add("SMB disabled by Full Loop logic: iob $iob_data_iob is above effective iobTH $iobThEffective")
                consoleLog.add("Loop power level temporarily capped")
                return LoopSmbDecision("iobTH", "iobTH")
            } else {
                consoleLog.add("SMB enabled; current target $target $msgUnits $msgEven $msgTail")
                return if (profile.target_bg < 100) {     // indirect assessment; later set it in GUI
                    consoleLog.add("Loop allows maximum power")
                    LoopSmbDecision("fullLoop", "none")             // even number
                } else {
                    consoleLog.add("Loop allows medium power")
                    LoopSmbDecision("enforced", "none")             // even number
                }
            }
        }
        consoleLog.add("Loop allows AAPS power level")
        return LoopSmbDecision("AAPS", "even-odd-off")                     // leave it to standard AAPS
    }

    // Fork (A1): [overlay] is the snapshot of the APS run, null outside a run (base ratio).
    fun determine_varSMBratio(bg: Int, target_bg: Double, loop_wanted_smb: String, overlay: EffectiveAutoIsfSettingsProvider.Snapshot? = null): Double {   // let SMB delivery ratio increase from min to max depending on how much bg exceeds target
        val fix_SMB: Double = smbDeliveryRatio(overlay)
        val lower_SMB = min(smb_delivery_ratio_min, smb_delivery_ratio_max)
        val higher_SMB = max(smb_delivery_ratio_min, smb_delivery_ratio_max)
        val higher_bg = target_bg + smb_delivery_ratio_bg_range
        var new_SMB: Double = fix_SMB
        if (smb_delivery_ratio_bg_range > 0) {
            new_SMB = lower_SMB + (higher_SMB - lower_SMB) * (bg - target_bg) / smb_delivery_ratio_bg_range
            new_SMB = max(lower_SMB, min(higher_SMB, new_SMB))   // cap if outside target_bg--higher_bg
        }
        if (loop_wanted_smb == "fullLoop") {                                // go for max impact
            consoleLog.add("SMB delivery ratio set to ${round(max(fix_SMB, new_SMB), 2)} as max of fixed and interpolated values")
            return max(fix_SMB, new_SMB)
        }

        if (smb_delivery_ratio_bg_range == 0.0) {                     // deactivated in SMB extended menu
            consoleLog.add("SMB delivery ratio set to fixed value ${round(fix_SMB, 2)}")
            return fix_SMB
        }
        if (bg <= target_bg) {
            consoleLog.add("SMB delivery ratio limited by minimum value ${round(lower_SMB, 2)}")
            return lower_SMB
        }
        if (bg >= higher_bg) {
            consoleLog.add("SMB delivery ratio limited by maximum value ${round(higher_SMB, 2)}")
            return higher_SMB
        }
        consoleLog.add("SMB delivery ratio set to interpolated value ${round(new_SMB, 2)}")
        return new_SMB
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "openapsautoisf_settings",
        title = ApsStrings.openaps_auto_isf,
        items = listOf(
            DoubleKey.ApsMaxBasal,
            DoubleKey.ApsSmbMaxIob,
            BooleanKey.ApsUseAutosens,
            BooleanKey.ApsSensitivityRaisesTarget,
            BooleanKey.ApsResistanceLowersTarget,
            BooleanKey.ApsAutoIsfHighTtRaisesSens,
            BooleanKey.ApsAutoIsfLowTtLowersSens,
            UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget,
            BooleanKey.ApsUseSmb,
            BooleanKey.ApsUseSmbWithHighTt,
            BooleanKey.ApsUseSmbAlways,
            BooleanKey.ApsUseSmbWithCob,
            BooleanKey.ApsUseSmbWithLowTt,
            BooleanKey.ApsUseSmbAfterCarbs,
            BooleanKey.ApsUseUam,
            IntKey.ApsMaxSmbFrequency,
            IntKey.ApsMaxMinutesOfBasalToLimitSmb,
            IntKey.ApsUamMaxMinutesOfBasalToLimitSmb,
            IntKey.ApsCarbsRequestThreshold,
            PreferenceSubScreenDef(
                key = "absorption_smb_advanced",
                title = CoreUiStrings.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier
                )
            ),
            PreferenceSubScreenDef(
                key = "auto_isf_settings",
                title = ApsStrings.autoISF_settings_title,
                items = listOf(
                    BooleanKey.ApsUseAutoIsfWeights,
                    DoubleKey.ApsAutoIsfMin,
                    DoubleKey.ApsAutoIsfMax,
                    DoubleKey.ApsAutoIsfBgAccelWeight,
                    DoubleKey.ApsAutoIsfBgBrakeWeight,
                    DoubleKey.ApsAutoIsfLowBgWeight,
                    DoubleKey.ApsAutoIsfHighBgWeight,
                    DoubleKey.ApsAutoIsfPpWeight,
                    DoubleKey.ApsAutoIsfDuraWeight,
                    IntKey.ApsAutoIsfIobThPercent
                )
            ),
            PreferenceSubScreenDef(
                key = "smb_delivery_settings",
                title = ApsStrings.smb_delivery_settings_title,
                items = listOf(
                    DoubleKey.ApsAutoIsfSmbDeliveryRatio,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioMin,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioMax,
                    DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange,
                    DoubleKey.ApsAutoIsfSmbMaxRangeExtension,
                    BooleanKey.ApsAutoIsfSmbOnEvenTarget
                )
            ),
            // autoISF 3.2.1 (Tobias/ga-zelle)
            PreferenceSubScreenDef(
                key = "activity_monitor_settings",
                title = ApsStrings.activity_monitor_settings_title,
                items = listOf(
                    BooleanKey.ActivityMonitorDetection,
                    DoubleKey.ActivityScaleFactor,
                    DoubleKey.InactivityScaleFactor,
                    BooleanKey.ActivityMonitorOvernight,
                    IntKey.ActivityMonitorIdleStart,
                    IntKey.ActivityMonitorIdleEnd,
                    BooleanKey.ActivityMonitorShowStepsFromSmartphone
                )
            ),
            PreferenceSubScreenDef(
                key = "libre_special_settings",
                title = ApsStrings.libre_special_settings_title,
                items = listOf(
                    BooleanKey.FslCalibrationTrigger,
                    BooleanKey.FslCalibrationEnd,
                    DoubleKey.FslCalOffset,
                    DoubleKey.FslCalSlope,
                    DoubleKey.FslSmoothAlpha,
                    BooleanKey.FslUkfQ1Enabled
                )
            )
        ) + listOfNotNull(
            // Fork: the local command channel of the IOB-Action viewer. Only where it exists (Android).
            localCommandChannelSettings.screen()?.let { ApsIntentKey.LocalCommandChannel.withCompose(it) }
        ),
        icon = pluginDescription.icon
    )
}