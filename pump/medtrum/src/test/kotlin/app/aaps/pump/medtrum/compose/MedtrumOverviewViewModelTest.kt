package app.aaps.pump.medtrum.compose

import android.content.Context
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventQueueChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpInfoGroup
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.diagnosis.PlungerWatch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.EnumSet
import app.aaps.core.ui.R as CoreUiR

/**
 * Unit test for [MedtrumOverviewViewModel]'s initial [PumpOverviewUiState] assembly.
 *
 * The VM owns its OWN CoroutineScope (Dispatchers.Default + SupervisorJob) rather than using
 * viewModelScope, and `uiState` is a `combine(...).stateIn(scope, WhileSubscribed(...), buildInitialState())`.
 * Reading `uiState.value` returns `buildInitialState()` WITHOUT running the combine (no subscriber),
 * so we only need the flow getters (read while building the combine argument array) plus everything
 * `buildInitialState()`/`buildUiState()` touch. No Dispatchers.setMain is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MedtrumOverviewViewModelTest {

    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var ch: ConcentrationHelper
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var uel: UserEntryLogger
    @Mock private lateinit var context: Context

    private val medtrumPlugin: MedtrumPlugin = mock()
    private val medtrumPump: MedtrumPump = mock()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        // The uiState field-initializer calls combine(...) over these non-null StateFlow getters,
        // so they must be stubbed or the getter reads NPE. bolusAmountDeliveredFlow.value is also
        // read directly by buildInitialState().
        whenever(medtrumPump.connectionStateFlow).thenReturn(MutableStateFlow(ConnectionState.DISCONNECTED))
        whenever(medtrumPump.pumpStateFlow).thenReturn(MutableStateFlow(MedtrumPumpState.NONE))
        whenever(medtrumPump.lastBasalTypeFlow).thenReturn(MutableStateFlow(BasalType.NONE))
        whenever(medtrumPump.lastBasalRateFlow).thenReturn(MutableStateFlow(0.0))
        whenever(medtrumPump.reservoirFlow).thenReturn(MutableStateFlow(0.0))
        whenever(medtrumPump.batteryVoltage_BFlow).thenReturn(MutableStateFlow(0.0))
        whenever(medtrumPump.bolusAmountDeliveredFlow).thenReturn(MutableStateFlow(0.0))
        whenever(medtrumPump.lastBolusTimeFlow).thenReturn(MutableStateFlow<Long?>(null))
        whenever(medtrumPump.lastBolusAmountFlow).thenReturn(MutableStateFlow<Double?>(null))
        whenever(medtrumPump.lastConnectionFlow).thenReturn(MutableStateFlow(0L))

        // Property getters read directly by buildInitialState() (default mock values are safe for the rest)
        whenever(medtrumPump.lastBasalType).thenReturn(BasalType.NONE)
        whenever(medtrumPump.swVersion).thenReturn("")   // read into an info row; null -> NPE
        // EnumSet is not auto-stubbed to empty by Mockito (returns null) -> must stub or activeAlarms.map NPEs
        whenever(medtrumPump.activeAlarms).thenReturn(EnumSet.noneOf(AlarmState::class.java))

        // PumpCommunicationStatus init subscribes to these two flows at construction
        whenever(rxBus.toFlow(EventPumpStatusChanged::class)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventQueueChanged::class)).thenReturn(emptyFlow())

        // Formatting collaborator (base basal rate row is always built)
        whenever(ch.basalRateString(any(), any(), any())).thenReturn("0.00 U/h")

        // Every info-row / action / banner label the build path touches (unstubbed rh.gs -> null -> NPE in PumpInfoRow)
        // Common rows (core:ui builder)
        whenever(rh.gs(CoreUiR.string.tempbasal_label)).thenReturn("Temp basal")
        whenever(rh.gs(CoreUiStrings.tempbasal_label)).thenReturn("Temp basal")
        whenever(rh.gs(CoreUiR.string.extended_bolus_label)).thenReturn("Extended bolus")
        whenever(rh.gs(CoreUiStrings.extended_bolus_label)).thenReturn("Extended bolus")
        whenever(rh.gs(CoreUiR.string.serial_number)).thenReturn("Serial number")
        whenever(rh.gs(CoreUiStrings.serial_number)).thenReturn("Serial number")
        // Medtrum-specific rows
        whenever(rh.gs(R.string.pump_state_label)).thenReturn("Pump state")
        whenever(rh.gs(CoreUiR.string.base_basal_rate_label)).thenReturn("Base basal")
        whenever(rh.gs(CoreUiStrings.base_basal_rate_label)).thenReturn("Base basal")
        whenever(rh.gs(R.string.pump_type_label)).thenReturn("Pump type")
        whenever(rh.gs(R.string.patch_no_label)).thenReturn("Patch no")
        whenever(rh.gs(R.string.expiry_not_enabled)).thenReturn("")   // empty -> expiry row skipped
        // pumpState.label values used across the two tests
        whenever(rh.gs(R.string.alarm_none)).thenReturn("None")
        whenever(rh.gs(R.string.status_active)).thenReturn("Active")
        // Banner
        whenever(rh.gs(R.string.patch_not_active)).thenReturn("Patch not activated")
        // Actions
        whenever(rh.gs(CoreUiR.string.refresh)).thenReturn("Refresh")
        whenever(rh.gs(CoreUiStrings.refresh)).thenReturn("Refresh")
        whenever(rh.gs(R.string.reset_alarms_label)).thenReturn("Reset alarms")
        whenever(rh.gs(R.string.change_patch_label)).thenReturn("Change patch")
        whenever(rh.gs(CoreUiR.string.pump_unpair)).thenReturn("Unpair")
        whenever(rh.gs(CoreUiStrings.pump_unpair)).thenReturn("Unpair")
        // Fork diagnosis block (Toni 20.11.2026): read at build time, so the mock must answer
        whenever(medtrumPump.plungerStatus).thenReturn(PlungerWatch.Status())
        whenever(medtrumPump.unknownStateCode).thenReturn(null)   // a mock answers 0 for Int?, the pump answers null
        whenever(rh.gs(CoreUiR.string.fork_diag_title)).thenReturn("Fork-Diagnose (Custom)")
        whenever(rh.gs(CoreUiR.string.fork_diag_plunger)).thenReturn("Kolben")
        whenever(rh.gs(CoreUiR.string.fork_diag_plunger_unknown)).thenReturn("noch keine Aussage")
        whenever(rh.gs(CoreUiR.string.fork_diag_alarm_bits)).thenReturn("Alarmbits der Pumpe (roh)")
        whenever(rh.gs(CoreUiR.string.fork_diag_alarm_bits_none)).thenReturn("keine")
        whenever(rh.gs(CoreUiR.string.fork_diag_unknown_state)).thenReturn("Unbekannter Statuscode")
        whenever(rh.gs(CoreUiR.string.fork_diag_plunger_moving)).thenReturn("läuft")
        whenever(rh.gs(CoreUiR.string.fork_diag_plunger_slow)).thenReturn("läuft zu kurz")
        whenever(rh.gs(CoreUiR.string.fork_diag_plunger_travel)).thenReturn("Kolbenweg je IE")
        whenever(rh.gs(eq(CoreUiR.string.fork_diag_plunger_travel_value), anyOrNull(), anyOrNull())).thenAnswer { "${it.arguments[1]} Schritte (${it.arguments[2]} %)" }
        whenever(rh.gs(CoreUiR.string.fork_diag_alarm_record)).thenReturn("Letzter Alarm-Eintrag")
        whenever(rh.gs(eq(CoreUiR.string.fork_diag_alarm_record_value), anyOrNull(), anyOrNull())).thenAnswer { "${it.arguments[1]} · Bit ${it.arguments[2]}" }
        whenever(rh.gs(eq(CoreUiR.string.fork_diag_plunger_stalled), anyOrNull())).thenAnswer { "STEHT seit ${it.arguments[1]}" }
        whenever(rh.gs(eq(CoreUiR.string.fork_diag_alarm_bits_value), anyOrNull(), anyOrNull())).thenAnswer { "${it.arguments[1]} · Bit ${it.arguments[2]}" }
        whenever(rh.gs(eq(CoreUiR.string.fork_diag_unknown_state_value), anyOrNull())).thenAnswer { "Code ${it.arguments[1]}" }
    }

    private fun createViewModel() = MedtrumOverviewViewModel(
        aapsLogger, rh, profileFunction, commandQueue, rxBus, dateUtil,
        medtrumPlugin, medtrumPump, ch, preferences, uel, context
    )

    @Test
    fun notActive_showsPatchNotActiveBanner_disablesRefresh_andNoUnpair() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.NONE)   // NONE -> "patch not active" banner, not pump-active
        whenever(medtrumPump.pumpSN).thenReturn(0L)                          // not paired -> no Unpair

        val state = createViewModel().uiState.value

        assertThat(state.infoRows).isNotEmpty()
        assertThat(state.statusBanner).isNotNull()
        assertThat(state.statusBanner?.level).isEqualTo(StatusLevel.WARNING)
        assertThat(state.statusBanner?.text).isEqualTo("Patch not activated")
        // NONE is not (EJECTED < state < STOPPED) -> refresh disabled
        assertThat(state.primaryActions.first { it.label == "Refresh" }.enabled).isFalse()
        assertThat(state.managementActions.map { it.label }).contains("Change patch")
        assertThat(state.managementActions.map { it.label }).doesNotContain("Unpair")
    }

    @Test
    fun activeAndPaired_noBanner_enablesRefresh_andOffersUnpair() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)  // EJECTED < ACTIVE < STOPPED -> pump active, no banner
        whenever(medtrumPump.pumpSN).thenReturn(12345L)                      // paired -> Unpair offered

        val state = createViewModel().uiState.value

        assertThat(state.statusBanner).isNull()
        // disconnected + pump-active -> refresh enabled
        assertThat(state.primaryActions.first { it.label == "Refresh" }.enabled).isTrue()
        assertThat(state.managementActions.map { it.label }).contains("Unpair")
    }

    // ---- Fork diagnosis (Toni 20.11.2026) ----------------------------------------------------------------------

    /** Our own reading stands as ONE block under the rows of the pump, and its first row names it. */
    @Test
    fun forkDiagnosis_isOneBlockUnderThePumpRows_andNamesItself() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)
        whenever(medtrumPump.pumpSN).thenReturn(12345L)

        val rows = createViewModel().uiState.value.infoRows

        assertThat(rows.filterIsInstance<PumpInfoGroup>()).hasSize(1)
        val block = rows.last() as PumpInfoGroup
        // Without a verdict there are no numbers yet, so no row for them
        assertThat(block.list.map { it.label }).containsExactly("Fork-Diagnose (Custom)", "Kolben", "Alarmbits der Pumpe (roh)").inOrder()
        assertThat(block.list[0].value).isEmpty()
        assertThat(block.list[1].value).isEqualTo("noch keine Aussage")
        assertThat(block.list[2].value).isEqualTo("keine")
        // Nothing of it stands among the rows the pump reports itself
        assertThat(rows.filterIsInstance<PumpInfoRow>().map { it.label }).containsNoneOf("Fork-Diagnose (Custom)", "Kolben", "Alarmbits der Pumpe (roh)")
    }

    @Test
    fun forkDiagnosis_aMovingPlungerShowsItsStepsPerUnit() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)
        whenever(medtrumPump.pumpSN).thenReturn(12345L)
        whenever(medtrumPump.plungerStatus).thenReturn(PlungerWatch.Status(PlungerWatch.State.MOVING, 20.4, null, 1500))
        whenever(medtrumPump.rawAlarmFlags).thenReturn(4)

        val block = createViewModel().uiState.value.infoRows.last() as PumpInfoGroup

        // The state and its numbers in two short rows, so that nothing wraps
        assertThat(block.list.map { it.label }).containsExactly("Fork-Diagnose (Custom)", "Kolben", "Kolbenweg je IE", "Alarmbits der Pumpe (roh)").inOrder()
        assertThat(block.list[1].value).isEqualTo("läuft")
        assertThat(block.list[1].level).isEqualTo(StatusLevel.NORMAL)
        assertThat(block.list[2].value).isEqualTo("20 Schritte (100 %)")
        // A bit the driver reads itself is shown, but it is no warning of ours
        assertThat(block.list[3].value).isEqualTo("4 · Bit 2")
        assertThat(block.list[3].level).isEqualTo(StatusLevel.UNSPECIFIED)
    }

    /** A standing plunger is critical; a bit or a status code nobody has a name for is a warning. */
    @Test
    fun forkDiagnosis_aStandingPlungerIsCritical_andUnknownSignalsAreWarnings() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)
        whenever(medtrumPump.pumpSN).thenReturn(12345L)
        whenever(medtrumPump.plungerStatus).thenReturn(PlungerWatch.Status(PlungerWatch.State.STALLED, 0.0, 1_800_000_000_000L, 1000))
        whenever(medtrumPump.rawAlarmFlags).thenReturn(4 + 128)
        whenever(medtrumPump.unknownStateCode).thenReturn(104)
        whenever(dateUtil.timeString(1_800_000_000_000L)).thenReturn("03:04")

        val block = createViewModel().uiState.value.infoRows.last() as PumpInfoGroup

        assertThat(block.list.map { it.label })
            .containsExactly("Fork-Diagnose (Custom)", "Kolben", "Kolbenweg je IE", "Alarmbits der Pumpe (roh)", "Unbekannter Statuscode").inOrder()
        assertThat(block.list[1].value).isEqualTo("STEHT seit 03:04")
        assertThat(block.list[1].level).isEqualTo(StatusLevel.CRITICAL)
        assertThat(block.list[2].value).isEqualTo("0 Schritte (0 %)")
        assertThat(block.list[3].value).isEqualTo("132 · Bit 2, 7")
        assertThat(block.list[3].level).isEqualTo(StatusLevel.WARNING)
        assertThat(block.list[4].value).isEqualTo("Code 104")
        assertThat(block.list[4].level).isEqualTo(StatusLevel.WARNING)
    }

    /** On the phone a value of about 30 characters and a label of about 25 fit their columns; longer ones wrap. */
    @Test
    fun forkDiagnosis_noRowIsLongerThanItsColumn() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)
        whenever(medtrumPump.pumpSN).thenReturn(12345L)
        whenever(dateUtil.timeString(1_800_000_000_000L)).thenReturn("03:04")
        whenever(dateUtil.dateAndTimeString(1_800_000_000_000L)).thenReturn("01.02.26 03:04")
        whenever(medtrumPump.rawAlarmFlags).thenReturn(4 + 1024)
        whenever(medtrumPump.unknownStateCode).thenReturn(104)
        whenever(medtrumPump.lastAlarmRecord).thenReturn(MedtrumPump.AlarmRecord(1_800_000_000_000L, 10, 7))

        for (status in listOf(
            PlungerWatch.Status(),
            PlungerWatch.Status(PlungerWatch.State.MOVING, 123.4, null, 1500),
            PlungerWatch.Status(PlungerWatch.State.SLOW, 16.4, null, 1500, slowSince = 1_800_000_000_000L),
            PlungerWatch.Status(PlungerWatch.State.STALLED, -17.6, 1_800_000_000_000L, 1500)
        )) {
            whenever(medtrumPump.plungerStatus).thenReturn(status)
            val block = createViewModel().uiState.value.infoRows.last() as PumpInfoGroup
            for (row in block.list) {
                assertThat(row.label.length).isAtMost(25)
                assertThat(row.value.length).isAtMost(30)
            }
        }
    }

    /** The hint below the alarm: a plunger that moves too short is a warning, with its share of the usual travel. */
    @Test
    fun forkDiagnosis_aSlowPlungerIsAWarning_withItsShareOfTheUsualTravel() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)
        whenever(medtrumPump.pumpSN).thenReturn(12345L)
        whenever(medtrumPump.plungerStatus).thenReturn(PlungerWatch.Status(PlungerWatch.State.SLOW, PlungerWatch.NORMAL_STEPS_PER_U * 0.8, null, 1500, slowSince = 1_800_000_000_000L))

        val block = createViewModel().uiState.value.infoRows.last() as PumpInfoGroup

        assertThat(block.list[1].value).isEqualTo("läuft zu kurz")
        assertThat(block.list[1].level).isEqualTo(StatusLevel.WARNING)
        assertThat(block.list[2].value).isEqualTo("16 Schritte (80 %)")
    }

    /** The last alarm record of the pump history stands in the block; a code nobody has a name for is a warning. */
    @Test
    fun forkDiagnosis_theLastAlarmRecordOfThePumpIsShown() {
        whenever(medtrumPump.connectionState).thenReturn(ConnectionState.DISCONNECTED)
        whenever(medtrumPump.pumpState).thenReturn(MedtrumPumpState.ACTIVE)
        whenever(medtrumPump.pumpSN).thenReturn(12345L)
        whenever(dateUtil.dateAndTimeString(1_800_000_000_000L)).thenReturn("01.02.26 03:04")

        whenever(medtrumPump.lastAlarmRecord).thenReturn(MedtrumPump.AlarmRecord(1_800_000_000_000L, 10, 7))
        val known = createViewModel().uiState.value.infoRows.last() as PumpInfoGroup
        assertThat(known.list.map { it.label }).containsExactly("Fork-Diagnose (Custom)", "Kolben", "Alarmbits der Pumpe (roh)", "Letzter Alarm-Eintrag").inOrder()
        assertThat(known.list[3].value).isEqualTo("01.02.26 03:04 · Bit 10")
        assertThat(known.list[3].level).isEqualTo(StatusLevel.UNSPECIFIED)

        whenever(medtrumPump.lastAlarmRecord).thenReturn(MedtrumPump.AlarmRecord(1_800_000_000_000L, 7, 7))
        val unknown = createViewModel().uiState.value.infoRows.last() as PumpInfoGroup
        assertThat(unknown.list[3].value).isEqualTo("01.02.26 03:04 · Bit 7")
        assertThat(unknown.list[3].level).isEqualTo(StatusLevel.WARNING)
    }
}
