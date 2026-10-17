package app.aaps.plugins.automation

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
import app.aaps.plugins.automation.actions.ActionSetAcceWeight
import app.aaps.plugins.automation.actions.ActionSetAutomationState
import app.aaps.plugins.automation.actions.ActionSetIobTH
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.triggers.TriggerAutomationState
import app.aaps.plugins.automation.triggers.TriggerBgAcceWeight
import app.aaps.plugins.automation.triggers.TriggerDeps
import app.aaps.plugins.automation.triggers.TriggerFactory
import app.aaps.plugins.automation.triggers.TriggerIobTH
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
            automationState
        )
    }

    private val triggerFactory: TriggerFactory by lazy {
        TriggerFactory(triggerDeps, mock(), mock(), mock())
    }

    private val actionFactory: ActionFactory by lazy {
        ActionFactory(
            triggerDeps, aapsLogger, rh, pumpEnactResultProvider, mock(), dateUtil, mock(), mock(), mock(),
            profileFunction, mock(), mock(), mock(), mock(), mock(), preferences, mock(), mock(), mock(), mock(),
            mock(), mock(), mock(), mock(), automationState
        )
    }

    @BeforeEach fun prepare() {
        whenever(automationState.hasStateValues(STATE)).thenReturn(true)
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
            "ActionAutoisfEnable", "ActionAutoisfDisable", "ActionSetAcceWeight", "ActionSetIobTH", "ActionSetAutomationState"
        )
    }

    companion object {

        private const val STATE = "Sport"
    }
}
