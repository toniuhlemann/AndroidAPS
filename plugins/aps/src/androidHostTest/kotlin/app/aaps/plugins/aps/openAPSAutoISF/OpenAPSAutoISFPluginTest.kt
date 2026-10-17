package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.plugins.aps.iobaction.LocalCommandChannelSettings
import app.aaps.plugins.aps.iobaction.ValueOverlayReader
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class OpenAPSAutoISFPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalAutoISF
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var profiler: Profiler
    @Mock lateinit var automationStateService: AutomationStateInterface
    @Mock lateinit var effectiveAutoIsfSettings: EffectiveAutoIsfSettingsProvider
    @Mock lateinit var localCommandChannelSettings: LocalCommandChannelSettings
    @Mock lateinit var iobActionExport: IobActionExportSink
    private lateinit var openAPSAutoISFPlugin: OpenAPSAutoISFPlugin

    @BeforeEach fun prepare() {
        openAPSAutoISFPlugin = OpenAPSAutoISFPlugin(
            aapsLogger, rxBus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
            bgQualityCheck, notificationManager, determineBasalSMB, profiler,
            GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator, preferences), { apsResultProvider() }, ch, automationStateService,
            effectiveAutoIsfSettings, iobActionExport, localCommandChannelSettings
        )
        // Neutral provider (no lease): effective == base, as with the channel off.
        whenever(effectiveAutoIsfSettings.snapshot()).thenReturn(
            EffectiveAutoIsfSettingsProvider.Snapshot(100, 100, AutoIsfOverrideState.NONE, null, null, null)
        )
    }

    @Test
    fun `an engineering dev build leaves the choice to the user`() {
        whenever(config.isEngineeringMode()).thenReturn(true)
        whenever(config.isDev()).thenReturn(true)
        assertThat(openAPSAutoISFPlugin.enforcedState()).isNull()
    }

    @Test
    fun `fork - any other build leaves the choice to the user too`() {
        // Upstream forces autoISF off outside an engineering dev build. This fork runs autoISF 3.2.1
        // as its APS, so the lock is removed (see the plugin description).
        whenever(config.isEngineeringMode()).thenReturn(false)
        assertThat(openAPSAutoISFPlugin.enforcedState()).isNull()
    }

    @Suppress("KotlinConstantConditions")
    @Test
    fun activityMonitor() = runTest {
        //`when`(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)).thenReturn(0)
        // TODO without being able to provide tests data for phone_moved most tests are useless
        //`when`(PhoneMovementDetector.phoneMoved()).thenReturn(false)
        whenever(preferences.get(DoubleKey.ActivityScaleFactor)).thenReturn(0.5)
        whenever(preferences.get(DoubleKey.InactivityScaleFactor)).thenReturn(1.5)
        whenever(preferences.get(BooleanKey.ActivityMonitorOvernight)).thenReturn(false)
        whenever(preferences.get(IntKey.ActivityMonitorIdleStart)).thenReturn(22)
        whenever(preferences.get(IntKey.ActivityMonitorIdleEnd)).thenReturn(6)
        whenever(preferences.get(BooleanKey.ActivityMonitorDetection)).thenReturn(false)

        assertThat(openAPSAutoISFPlugin.activityMonitor(true, 80.0, 90.0, 2)).isEqualTo(1.0) // not selected in preferences

        whenever(preferences.get(BooleanKey.ActivityMonitorDetection)).thenReturn(true)
        assertThat(openAPSAutoISFPlugin.activityMonitor(true, 80.0, 90.0, 2)).isEqualTo(1.0) // Temp Target
        assertThat(openAPSAutoISFPlugin.activityMonitor(false, 80.0, 90.0, 2)).isEqualTo(1.0) // bg < target
        assertThat(openAPSAutoISFPlugin.activityMonitor(false, 99.0, 90.0, 2)).isEqualTo(1.0) // bg > target
        //whenever(PhoneMovementDetector.phoneMoved()).thenReturn(true)
        assertThat(openAPSAutoISFPlugin.activityMonitor(false, 99.0, 90.0, 2)).isEqualTo(1.0) // sleeping hours
        whenever(preferences.get(IntKey.ActivityMonitorIdleStart)).thenReturn(3)
        // assertThat(openAPSAutoISFPlugin.activityMonitor(false, 99.0, 90.0, 2)).isEqualTo(1.3) // inactivity; disable phoneMoved first for this to work !!
        //whenever(StepService.getRecentStepCount5Min()).thenReturn(500)
        // assertThat(openAPSAutoISFPlugin.activityMonitor(false, 99.0, 90.0, 2)).isEqualTo(0.85) // activity
    }

    @Test
    fun withinISFLimitsTest() {
        val autoIsfMin = 0.7
        val autoIsfMax = 1.2
        var sens = 1.1  // from Autosens
        val originSens = ""
        var ttSet = false
        var exerciseModeActive = false
        var resistanceModeActive = false
        val targetBg = 120.0
        val normalTarget = Constants.NORMAL_TARGET_MGDL
        var stepActivityDetected = false
        val stepInactivityDetected = false
        whenever(preferences.get(BooleanKey.ActivityMonitorDetection)).thenReturn(false)

        assertThat(openAPSAutoISFPlugin.withinISFlimits(1.7, autoIsfMin, autoIsfMax, sens, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)).isEqualTo(1.2) // upper limit
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)).isEqualTo(0.7) // lower limit
        sens = 1.5  // from Autosens
        assertThat(openAPSAutoISFPlugin.withinISFlimits(1.7, autoIsfMin, autoIsfMax, sens, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)).isEqualTo(1.5) // autosens 1.5 wins
        sens = 0.5  // from Autosens
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)).isEqualTo(0.5) // autosens 0.5 wins
        exerciseModeActive = true
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)).isEqualTo(0.35) // exercise mode
        stepActivityDetected = true
        assertThat(openAPSAutoISFPlugin.withinISFlimits(0.5, autoIsfMin, autoIsfMax, sens, exerciseModeActive, resistanceModeActive, stepActivityDetected, stepInactivityDetected)).isEqualTo(0.35) // Activity mode
    }

    @Test
    fun determine_varSMBRatioTest() {
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)).thenReturn(0.55)
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)).thenReturn(0.4)
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)).thenReturn(0.6)
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)).thenReturn(20.0)
        //whenever(preferences.get(DoubleKey.ApsAutoIsfSmbMaxRangeExtension)).thenReturn(1.0)

        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(100, 90.0, "fullLoop")).isEqualTo(0.55)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "fullLoop")).isEqualTo(0.6)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(100, 90.0, "enforced")).isEqualTo(0.5)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(80, 90.0, "enforced")).isEqualTo(0.4)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced")).isEqualTo(0.6)
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)).thenReturn(0.0)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced")).isEqualTo(0.55)
    }

    @Test
    fun interpolateTest() {
        whenever(preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)).thenReturn(10.0)
        whenever(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)).thenReturn(1.0)
        assertThat(openAPSAutoISFPlugin.interpolate(45.0)).isEqualTo(-5.0)
        assertThat(openAPSAutoISFPlugin.interpolate(55.0)).isEqualTo(-5.0)
        assertThat(openAPSAutoISFPlugin.interpolate(100.0)).isEqualTo(0.0)
        assertThat(openAPSAutoISFPlugin.interpolate(130.0)).isEqualTo(0.25)
        assertThat(openAPSAutoISFPlugin.interpolate(230.0)).isEqualTo(0.7)
    }

    // Fork (capability matrix A1): the value overlays of a run. The fork kept the run snapshot in a
    // ThreadLocal; here it is passed as a value, and without it (any caller outside a run) the base counts.
    private fun leaseSnapshot(ratio: Double? = null, weights: Map<String, Double> = emptyMap()) =
        EffectiveAutoIsfSettingsProvider.Snapshot(
            100, 100, AutoIsfOverrideState.NONE, null, null, null,
            smbRatioEffective = ratio,
            smbRatioState = if (ratio != null) AutoIsfOverrideState.ACTIVE else AutoIsfOverrideState.NONE,
            weightOverrides = weights,
            weightsState = if (weights.isEmpty()) AutoIsfOverrideState.NONE else AutoIsfOverrideState.ACTIVE,
        )

    @Test
    fun `fork - the fixed SMB ratio comes from the run overlay and from the base outside a run`() {
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)).thenReturn(0.55)
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)).thenReturn(0.4)
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)).thenReturn(0.6)
        // bg range 0: the fixed ratio decides
        whenever(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)).thenReturn(0.0)

        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced")).isEqualTo(0.55)
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced", leaseSnapshot(ratio = 0.3))).isEqualTo(0.3)
        // a run without a ratio lease uses the base
        assertThat(openAPSAutoISFPlugin.determine_varSMBratio(180, 90.0, "enforced", leaseSnapshot())).isEqualTo(0.55)
    }

    @Test
    fun `fork - the range weights come from the run overlay and from the base outside a run`() {
        whenever(preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)).thenReturn(10.0)
        whenever(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)).thenReturn(1.0)
        val lease = leaseSnapshot(weights = mapOf(ValueOverlayReader.F_HIGH to 2.0, ValueOverlayReader.F_LOW to 5.0))

        assertThat(openAPSAutoISFPlugin.interpolate(130.0, lease)).isEqualTo(0.5)   // 0.25 * 2
        assertThat(openAPSAutoISFPlugin.interpolate(45.0, lease)).isEqualTo(-2.5)   // -0.5 * 5
        assertThat(openAPSAutoISFPlugin.interpolate(130.0)).isEqualTo(0.25)
        assertThat(openAPSAutoISFPlugin.interpolate(45.0)).isEqualTo(-5.0)
    }

    @Test
    fun loop_smbTest() {
        val profile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = 10.0,
            max_daily_basal = 0.5,
            max_basal = 0.0,
            min_bg = 90.0,
            max_bg = 90.0,
            target_bg = 90.0,
            carb_ratio = 10.0,
            sens = 100.0,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget),
            activity_detection = preferences.get(BooleanKey.ActivityMonitorDetection),
            recent_steps_5_minutes  = 5,
            recent_steps_10_minutes = 10,
            recent_steps_15_minutes = 15,
            recent_steps_30_minutes = 30,
            recent_steps_60_minutes = 60,
            phone_moved = false,
            time_since_start = 120,
            now = 15,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = false,
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = false,
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = true,
            enableSMB_with_temptarget = true,
            allowSMB_with_high_temptarget = false,
            enableSMB_always = true,
            enableSMB_after_carbs = true,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = 0.1,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate),
            temptargetSet = true,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = "mg/dl",
            variable_sens = 111.1,
            autoISF_version = "3.2.0",
            enable_autoISF = false,
            autoISF_max = 1.5,
            autoISF_min = 0.7,
            bgAccel_ISF_weight = 0.0,
            bgBrake_ISF_weight = 0.0,
            pp_ISF_weight = 0.0,
            lower_ISFrange_weight = 0.0,
            higher_ISFrange_weight = 0.0,
            dura_ISF_weight = 0.0,
            smb_delivery_ratio = 0.5,
            smb_delivery_ratio_min = 0.6,
            smb_delivery_ratio_max = 1.0,
            smb_delivery_ratio_bg_range = 0.0,
            smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff_always = true,
            iob_threshold_percent = 100,
            profile_percentage = 100
        )
        // Fork 0059: loop_smb returns LoopSmbDecision(mode, reason) - the mode as before, the reason names the branch.
        var d = openAPSAutoISFPlugin.loop_smb(false, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("AAPS")
        assertThat(d.reason).isEqualTo("microbolus-disabled")
        whenever(preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget)).thenReturn(true)
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("fullLoop")
        assertThat(d.reason).isEqualTo("none")
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, true, 10.1)
        assertThat(d.mode).isEqualTo("iobTH")
        assertThat(d.reason).isEqualTo("iobTH")
        profile.target_bg = 122.0
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("enforced")
        assertThat(d.reason).isEqualTo("none")
        profile.target_bg = 91.8    //5.1
        profile.out_units = "mmol/L"
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("blocked")
        assertThat(d.reason).isEqualTo("odd-target")
        profile.target_bg = 149.4   //8.3
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("blocked")
        assertThat(d.reason).isEqualTo("odd-target")
        profile.target_bg = 147.6   //8.2
        assertThat(openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1).mode).isEqualTo("enforced")
        profile.target_bg = 145.8   //8.1
        assertThat(openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1).reason).isEqualTo("odd-target")
        profile.target_bg = 144.0   //8.0
        assertThat(openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1).mode).isEqualTo("enforced")
        // max_iob = 0 -> blocked / max-iob-zero
        profile.target_bg = 144.0
        profile.out_units = "mg/dl"
        d = openAPSAutoISFPlugin.loop_smb(true, profile.copy(max_iob = 0.0), 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("blocked")
        assertThat(d.reason).isEqualTo("max-iob-zero")
        whenever(preferences.get(BooleanKey.ApsAutoIsfSmbOnEvenTarget)).thenReturn(false)
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("AAPS")
        assertThat(d.reason).isEqualTo("even-odd-off")
        // A running calibration window -> blocked / calibration. A start in the future makes the
        // remaining minutes positive whatever the (construction time) duration setting is.
        whenever(preferences.get(LongNonKey.FslCalibrationStart)).thenReturn(now + 600_000L)
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        d = openAPSAutoISFPlugin.loop_smb(true, profile, 11.0, false, 11.1)
        assertThat(d.mode).isEqualTo("blocked")
        assertThat(d.reason).isEqualTo("calibration")
    }

    @Test
    fun autoISFTest() = kotlinx.coroutines.runBlocking {
        // TODO get profile
        val profile = profileFunction.getProfile(now) ?: return@runBlocking

        val oapsProfile = OapsProfileAutoIsf(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = 10.0,
            max_daily_basal = 0.5,
            max_basal = 0.0,
            min_bg = 91.0,
            max_bg = 91.0,
            target_bg = 91.0,
            carb_ratio = 10.0,
            sens = 100.0,
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens), // was false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget),
            activity_detection = preferences.get(BooleanKey.ActivityMonitorDetection),
            recent_steps_5_minutes  = 5,
            recent_steps_10_minutes = 10,
            recent_steps_15_minutes = 15,
            recent_steps_30_minutes = 30,
            recent_steps_60_minutes = 60,
            phone_moved = false,
            time_since_start = 120,
            now = 15,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = false,
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = false,
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = true,
            enableSMB_with_temptarget = true,
            allowSMB_with_high_temptarget = false,
            enableSMB_always = true,
            enableSMB_after_carbs = true,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = 0.1,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate),
            temptargetSet = true,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = "mg/dl",
            variable_sens = 47.11,
            autoISF_version = "3.2.0",
            enable_autoISF = false,
            autoISF_max = 1.5,
            autoISF_min = 0.7,
            bgAccel_ISF_weight = 0.0,
            bgBrake_ISF_weight = 0.0,
            pp_ISF_weight = 0.0,
            lower_ISFrange_weight = 0.0,
            higher_ISFrange_weight = 0.0,
            dura_ISF_weight = 0.0,
            smb_delivery_ratio = 0.5,
            smb_delivery_ratio_min = 0.6,
            smb_delivery_ratio_max = 1.0,
            smb_delivery_ratio_bg_range = 0.0,
            smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff_always = true,
            iob_threshold_percent = 100,
            profile_percentage = 100
        )
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11)                             // inactive
        whenever(oapsProfile.enable_autoISF).thenReturn(true)
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData as GlucoseStatusAutoIsf
        whenever(glucoseStatus.corrSqu).thenReturn(0.4711)
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11)                             // bad parabola
        whenever(preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)).thenReturn(true)
        whenever(preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget)).thenReturn(160.0)
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11 * 2.0)                       // exercise mode w/o AutoISF
        whenever(glucoseStatus.corrSqu).thenReturn(0.95)
        whenever(glucoseStatus.glucose).thenReturn(90.0)
        whenever(glucoseStatus.a0).thenReturn(90.3)
        whenever(glucoseStatus.a1).thenReturn(2.0)
        whenever(glucoseStatus.a2).thenReturn(3.0)
        whenever(glucoseStatus.bgAcceleration).thenReturn(2.0 * glucoseStatus.a2)
        whenever(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)).thenReturn(2.0)
        whenever(preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)).thenReturn(true)
        whenever(preferences.get(UnitDoubleKey.ApsAutoIsfHalfBasalExerciseTarget)).thenReturn(160.0)
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11 * 2.0)                       // exercise mode w/o AutoISF
        whenever(glucoseStatus.corrSqu).thenReturn(0.95)
        whenever(glucoseStatus.glucose).thenReturn(90.0)
        whenever(glucoseStatus.a0).thenReturn(90.3)
        whenever(glucoseStatus.a1).thenReturn(2.0)
        whenever(glucoseStatus.a2).thenReturn(3.0)
        whenever(glucoseStatus.bgAcceleration).thenReturn(2.0 * glucoseStatus.a2)
        whenever(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)).thenReturn(2.0)
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11 * 2.0 * 2.0)                 // acce_ISF + exercise mode
        whenever(preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)).thenReturn(false)
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11 * 2.0)                       // acce_ISF w/o exercise mode
        whenever(preferences.get(DoubleKey.ApsAutoIsfLowBgWeight)).thenReturn(2.0)
        assertThat(openAPSAutoISFPlugin.autoISF(profile)).isEqualTo(47.11 * 1.0)                       // bg_ISF strengthened by acce_ISF

    }
}
