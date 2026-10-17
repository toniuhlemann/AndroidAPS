package app.aaps.plugins.automation

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientInt
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.actions.ActionAutoisfDisable
import app.aaps.plugins.automation.actions.ActionAutoisfEnable
import app.aaps.plugins.automation.actions.ActionFactory
import app.aaps.plugins.automation.actions.ActionResetProfilePercent
import app.aaps.plugins.automation.actions.ActionSetAcceWeight
import app.aaps.plugins.automation.actions.ActionSetAutomationState
import app.aaps.plugins.automation.actions.ActionSetDuraWeight
import app.aaps.plugins.automation.actions.ActionSetIobTH
import app.aaps.plugins.automation.actions.ActionSetPpWeight
import app.aaps.plugins.automation.actions.ActionSetSmbDeliveryRatio
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.ComparatorExists
import app.aaps.plugins.automation.triggers.TriggerAutoIsfLeaseActive
import app.aaps.plugins.automation.triggers.TriggerAutomationState
import app.aaps.plugins.automation.triggers.TriggerBgAcceWeight
import app.aaps.plugins.automation.triggers.TriggerBgRaw
import app.aaps.plugins.automation.triggers.TriggerBolusCoverage
import app.aaps.plugins.automation.triggers.TriggerCoverage
import app.aaps.plugins.automation.triggers.TriggerDeps
import app.aaps.plugins.automation.triggers.TriggerFactory
import app.aaps.plugins.automation.triggers.TriggerIobTH
import app.aaps.plugins.automation.triggers.TriggerSmbDeliveryRatio
import app.aaps.shared.tests.TestBase
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertIs

/**
 * The autoISF 3.2.x automation elements must read what 3.4 stored.
 *
 * v4 drops an action it does not know when it loads the list, and keeps it dropped. So a rule that
 * a user built on 3.4 must come back with the same type and the same values. The fixtures below are
 * written in the 3.4 form: triggers by simple class name, two of the actions by full class name.
 */
class AutoIsfAutomationJsonTest : TestBase() {

    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var pumpEnactResultProvider: () -> PumpEnactResult
    @Mock lateinit var automationState: AutomationStateInterface
    private val rh = generatedTextResolver("automation" to AutomationStringsValues::textOf)

    private val triggerDeps: TriggerDeps by lazy {
        TriggerDeps(
            aapsLogger, mock(), rh, profileFunction, mock(), preferences, mock(), mock(), mock(), mock(), mock(), dateUtil,
            automationState, mock()
        )
    }

    private val triggerFactory: TriggerFactory by lazy {
        TriggerFactory(triggerDeps, mock(), mock(), mock())
    }

    private val actionFactory: ActionFactory by lazy {
        ActionFactory(
            triggerDeps, aapsLogger, rh, pumpEnactResultProvider, mock(), dateUtil, mock(), mock(), mock(),
            profileFunction, mock(), mock(), mock(), mock(), mock(), preferences, mock(), mock(), mock(), mock(),
            mock(), mock(), mock(), mock(), automationState, mock()
        )
    }

    @BeforeEach fun prepare() {
        whenever(automationState.hasStateValues(STATE)).thenReturn(true)
        // InputBg asks for the units when it is built (TriggerBgRaw)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun iobThTriggerReads34Form() {
        val trigger = triggerFactory.instantiate(json("{\"type\":\"TriggerIobTH\",\"data\":{\"iobTH_percent\":55,\"comparator\":\"IS_GREATER\"}}"))

        assertIs<TriggerIobTH>(trigger)
        assertThat(trigger.iobTHpercent).isEqualTo(55)
        assertThat(trigger.comparator.value).isEqualTo(Comparator.Compare.IS_GREATER)
        val data = trigger.dataJSON()
        assertThat(data.lenientInt("iobTH_percent")).isEqualTo(55)
        assertThat(data.lenientString("comparator")).isEqualTo("IS_GREATER")
    }

    @Test fun acceWeightTriggerReads34Form() {
        val trigger = triggerFactory.instantiate(json("{\"type\":\"TriggerBgAcceWeight\",\"data\":{\"acce_weight\":0.35,\"comparator\":\"IS_EQUAL_OR_LESSER\"}}"))

        assertIs<TriggerBgAcceWeight>(trigger)
        assertThat(trigger.acceWeight).isEqualTo(0.35)
        assertThat(trigger.comparator.value).isEqualTo(Comparator.Compare.IS_EQUAL_OR_LESSER)
        assertThat(trigger.dataJSON().lenientDouble("acce_weight")).isEqualTo(0.35)
    }

    @Test fun automationStateTriggerReads34Form() {
        val trigger = triggerFactory.instantiate(json("{\"type\":\"TriggerAutomationState\",\"data\":{\"stateName\":\"$STATE\",\"stateValue\":\"on\"}}"))

        assertIs<TriggerAutomationState>(trigger)
        assertThat(trigger.stateName).isEqualTo(STATE)
        assertThat(trigger.stateValue).isEqualTo("on")
    }

    @Test fun automationStateTriggerDropsValueOfUnknownState() {
        // As in 3.4: the value is only kept while the state is still defined.
        val trigger = triggerFactory.instantiate(json("{\"type\":\"TriggerAutomationState\",\"data\":{\"stateName\":\"gone\",\"stateValue\":\"on\"}}"))

        assertIs<TriggerAutomationState>(trigger)
        assertThat(trigger.stateName).isEqualTo("gone")
        assertThat(trigger.stateValue).isEmpty()
    }

    @Test fun actionsStoredWithFullClassNameStillLoad() {
        val iobTH = actionFactory.instantiate(json("{\"type\":\"app.aaps.plugins.automation.actions.ActionSetIobTH\",\"data\":{\"percentage\":55}}"))
        val weight = actionFactory.instantiate(json("{\"type\":\"app.aaps.plugins.automation.actions.ActionSetAcceWeight\",\"data\":{\"weight\":0.35}}"))

        assertIs<ActionSetIobTH>(iobTH)
        assertThat(iobTH.newIobTH).isEqualTo(55)
        assertIs<ActionSetAcceWeight>(weight)
        assertThat(weight.newWeight).isEqualTo(0.35)
    }

    @Test fun actionsWithoutDataLoad() {
        assertIs<ActionAutoisfEnable>(actionFactory.instantiate(json("{\"type\":\"ActionAutoisfEnable\"}")))
        assertIs<ActionAutoisfDisable>(actionFactory.instantiate(json("{\"type\":\"ActionAutoisfDisable\"}")))
    }

    @Test fun automationStateActionReads34Form() {
        val action = actionFactory.instantiate(json("{\"type\":\"ActionSetAutomationState\",\"data\":{\"inputStateName\":\"$STATE\",\"inputState\":\"on\"}}"))

        assertIs<ActionSetAutomationState>(action)
        assertThat(action.stateName).isEqualTo(STATE)
        assertThat(action.stateValue).isEqualTo("on")
    }

    @Test fun actionsWriteThe34KeysBack() {
        // The stored keys must stay the 3.4 ones, so a phone that still runs 3.4 reads a synced rule.
        val iobTH = actionFactory.actionSetIobTH().also { it.newIobTH = 55 }
        val weight = actionFactory.actionSetAcceWeight().also { it.newWeight = 0.35 }
        val state = actionFactory.actionSetAutomationState().also { it.stateName = STATE; it.stateValue = "on" }

        val iobData = json(iobTH.toJSON())["data"] as JsonObject
        val weightData = json(weight.toJSON())["data"] as JsonObject
        val stateData = json(state.toJSON())["data"] as JsonObject

        assertThat(iobData.lenientInt("percentage")).isEqualTo(55)
        assertThat(weightData.lenientDouble("weight")).isEqualTo(0.35)
        assertThat(stateData.lenientString("inputStateName")).isEqualTo(STATE)
        assertThat(stateData.lenientString("inputState")).isEqualTo("on")

        // and they read back to the same values
        assertThat((actionFactory.instantiate(json(iobTH.toJSON())) as ActionSetIobTH).newIobTH).isEqualTo(55)
        assertThat((actionFactory.instantiate(json(weight.toJSON())) as ActionSetAcceWeight).newWeight).isEqualTo(0.35)
    }

    @Test fun everyAutoIsfElementIsOffered() {
        // The "choose" lists are where a user finds them. A type that is missing there cannot be added.
        val actions = actionFactory.allActions().map { it::class.simpleName }
        assertThat(actions).containsAtLeast(
            "ActionAutoisfEnable", "ActionAutoisfDisable", "ActionSetAcceWeight", "ActionSetIobTH", "ActionSetAutomationState",
            "ActionSetPpWeight", "ActionSetDuraWeight", "ActionSetSmbDeliveryRatio", "ActionResetProfilePercent"
        )
    }

    // ---- Toni's fork elements (IOB-Action viewer), in the 3.4 fork form ----

    @Test fun forkWeightAndRatioActionsReadFullClassNames() {
        val pp = actionFactory.instantiate(json("{\"type\":\"app.aaps.plugins.automation.actions.ActionSetPpWeight\",\"data\":{\"weight\":0.07}}"))
        val dura = actionFactory.instantiate(json("{\"type\":\"app.aaps.plugins.automation.actions.ActionSetDuraWeight\",\"data\":{\"weight\":1.3}}"))
        val ratio = actionFactory.instantiate(json("{\"type\":\"app.aaps.plugins.automation.actions.ActionSetSmbDeliveryRatio\",\"data\":{\"ratio\":0.45}}"))

        assertIs<ActionSetPpWeight>(pp)
        assertThat(pp.newWeight).isEqualTo(0.07)
        assertIs<ActionSetDuraWeight>(dura)
        assertThat(dura.newWeight).isEqualTo(1.3)
        assertIs<ActionSetSmbDeliveryRatio>(ratio)
        assertThat(ratio.newRatio).isEqualTo(0.45)
        assertThat((json(ratio.toJSON())["data"] as JsonObject).lenientDouble("ratio")).isEqualTo(0.45)
    }

    @Test fun smbRatioOutsideTheRangeIsClampedOnRead() {
        val ratio = actionFactory.instantiate(json("{\"type\":\"ActionSetSmbDeliveryRatio\",\"data\":{\"ratio\":0.05}}"))
        val trigger = triggerFactory.instantiate(json("{\"type\":\"TriggerSmbDeliveryRatio\",\"data\":{\"ratio\":1.5,\"comparator\":\"IS_GREATER\"}}"))

        assertThat((ratio as ActionSetSmbDeliveryRatio).newRatio).isEqualTo(ActionSetSmbDeliveryRatio.MIN)
        assertThat((trigger as TriggerSmbDeliveryRatio).ratio).isEqualTo(ActionSetSmbDeliveryRatio.MAX)
    }

    @Test fun resetProfilePercentLoads() {
        assertIs<ActionResetProfilePercent>(actionFactory.instantiate(json("{\"type\":\"ActionResetProfilePercent\",\"data\":{}}")))
    }

    @Test fun forkTriggersRead34Form() {
        val ratio = triggerFactory.instantiate(json("{\"type\":\"TriggerSmbDeliveryRatio\",\"data\":{\"ratio\":0.45,\"comparator\":\"IS_GREATER\"}}"))
        // org.json wrote whole numbers as integers
        val coverage = triggerFactory.instantiate(json("{\"type\":\"TriggerCoverage\",\"data\":{\"coverage\":125,\"comparator\":\"IS_LESSER\"}}"))
        val bolusCoverage = triggerFactory.instantiate(json("{\"type\":\"TriggerBolusCoverage\",\"data\":{\"coverage\":90,\"comparator\":\"IS_EQUAL_OR_GREATER\"}}"))
        val lease = triggerFactory.instantiate(json("{\"type\":\"TriggerAutoIsfLeaseActive\",\"data\":{\"comparator\":\"NOT_EXISTS\"}}"))
        val rawBg = triggerFactory.instantiate(json("{\"type\":\"TriggerBgRaw\",\"data\":{\"bg\":123,\"comparator\":\"IS_LESSER\",\"units\":\"mg/dl\"}}"))

        assertIs<TriggerSmbDeliveryRatio>(ratio)
        assertThat(ratio.ratio).isEqualTo(0.45)
        assertThat(ratio.comparator.value).isEqualTo(Comparator.Compare.IS_GREATER)
        assertIs<TriggerCoverage>(coverage)
        assertThat(coverage.coverage).isEqualTo(125.0)
        assertThat(coverage.comparator.value).isEqualTo(Comparator.Compare.IS_LESSER)
        assertIs<TriggerBolusCoverage>(bolusCoverage)
        assertThat(bolusCoverage.coverage).isEqualTo(90.0)
        assertIs<TriggerAutoIsfLeaseActive>(lease)
        assertThat(lease.comparator.value).isEqualTo(ComparatorExists.Compare.NOT_EXISTS)
        assertIs<TriggerBgRaw>(rawBg)
        assertThat(rawBg.bg.value).isEqualTo(123.0)
        assertThat(rawBg.bg.units).isEqualTo(GlucoseUnit.MGDL)
        assertThat(rawBg.comparator.value).isEqualTo(Comparator.Compare.IS_LESSER)
    }

    @Test fun forkTriggersWriteThe34KeysBack() {
        val coverage = triggerFactory.instantiate(json("{\"type\":\"TriggerCoverage\",\"data\":{\"coverage\":125,\"comparator\":\"IS_LESSER\"}}"))
        val data = coverage.dataJSON()

        assertThat(data.lenientDouble("coverage")).isEqualTo(125.0)
        assertThat(data.lenientString("comparator")).isEqualTo("IS_LESSER")
    }

    companion object {

        private const val STATE = "Sport"
    }
}
