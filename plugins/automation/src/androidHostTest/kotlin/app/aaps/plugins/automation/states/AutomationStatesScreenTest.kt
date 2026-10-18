package app.aaps.plugins.automation.states

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.plugins.automation.AutomationStringIds
import app.aaps.plugins.automation.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.ui.R as CoreUiR

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AutomationStatesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Saved(val name: String, val values: List<String>, val hadValue: Boolean)

    private val setValues = mutableListOf<Pair<String, String>>()
    private val saved = mutableListOf<Saved>()
    private val deleted = mutableListOf<String>()

    @Before
    fun setUp() {
        // What MainApp does at startup: a TextRef.Named is resolved through this registry.
        TextRefIdRegistry.register("automation") { name -> AutomationStringIds.idOf(name) }
    }

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private val ok get() = text(CoreUiR.string.ok)

    private fun show(states: List<AutomationStateUi>) {
        compose.setContent {
            MaterialTheme {
                AutomationStatesScreen(
                    states = states,
                    onSetValue = { name, value -> setValues += name to value },
                    onSaveValues = { name, values, hadValue -> saved += Saved(name, values, hadValue) },
                    onDeleteState = { deleted += it }
                )
            }
        }
    }

    private val sleeping = AutomationStateUi("Sleeping", "false", listOf("true", "false"))
    private val exercise = AutomationStateUi("Exercise", "", listOf("on"))

    @Test
    fun everyStateShowsItsValuesAndTheCurrentOne() {
        show(listOf(exercise, sleeping))

        compose.onNodeWithText("Sleeping").assertIsDisplayed()
        compose.onNodeWithText("false").assertIsSelected()
        compose.onNodeWithText("true").assertIsNotSelected()
        compose.onNodeWithText("Exercise").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.automation_state_no_value)).assertIsDisplayed()
    }

    @Test
    fun aTapOnAValueSetsIt() {
        show(listOf(sleeping))

        compose.onNodeWithText("true").performClick()

        assertThat(setValues).containsExactly("Sleeping" to "true")
    }

    @Test
    fun withoutStatesTheScreenSaysHowToAddOne() {
        show(emptyList())

        compose.onNodeWithText(text(R.string.automation_states_empty_desc)).assertIsDisplayed()
    }

    @Test
    fun aNewStateIsSavedWithItsValues() {
        show(emptyList())

        compose.onNodeWithContentDescription(text(R.string.automation_state_add)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(" Sleeping ")
        compose.onNodeWithText(ok).performClick()

        compose.onNodeWithText(text(R.string.automation_state_values_title, "Sleeping")).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("true")
        compose.onNodeWithContentDescription(text(R.string.automation_state_add_value)).performClick()
        // A value typed but not added yet is taken along by OK
        compose.onNode(hasSetTextAction()).performTextInput("false")
        compose.onNodeWithText(ok).performClick()

        assertThat(saved).containsExactly(Saved("Sleeping", listOf("true", "false"), hadValue = false))
    }

    @Test
    fun aStateNeedsAName() {
        show(emptyList())

        compose.onNodeWithContentDescription(text(R.string.automation_state_add)).performClick()
        compose.onNodeWithText(ok).performClick()

        compose.onNodeWithText(text(R.string.automation_state_missing_name)).assertIsDisplayed()
    }

    @Test
    fun theLastValueCannotBeDeleted() {
        show(listOf(exercise))

        compose.onNodeWithContentDescription(text(R.string.automation_state_edit_values, "Exercise")).performClick()
        compose.onNodeWithContentDescription(text(R.string.automation_state_delete_value, "on")).performClick()

        compose.onNodeWithText(text(R.string.automation_state_last_value)).assertIsDisplayed()
        compose.onNodeWithText(ok).performClick()
        assertThat(saved).containsExactly(Saved("Exercise", listOf("on"), hadValue = false))
    }

    @Test
    fun aValueCannotBeAddedTwice() {
        show(listOf(sleeping))

        compose.onNodeWithContentDescription(text(R.string.automation_state_edit_values, "Sleeping")).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("true")
        compose.onNodeWithContentDescription(text(R.string.automation_state_add_value)).performClick()

        compose.onNodeWithText(text(R.string.automation_state_value_exists)).assertIsDisplayed()
    }

    @Test
    fun aStateIsDeletedOnlyAfterAsking() {
        show(listOf(sleeping))

        compose.onNodeWithContentDescription(text(R.string.automation_state_edit_values, "Sleeping")).performClick()
        compose.onNodeWithText(text(R.string.automation_state_delete)).performClick()
        assertThat(deleted).isEmpty()

        compose.onNodeWithText(text(R.string.automation_state_delete_confirmation, "Sleeping")).assertIsDisplayed()
        compose.onNodeWithText(ok).performClick()

        assertThat(deleted).containsExactly("Sleeping")
        assertThat(saved).isEmpty()
    }

    @Test
    fun cancelStoresNothing() {
        show(listOf(sleeping))

        compose.onNodeWithContentDescription(text(R.string.automation_state_edit_values, "Sleeping")).performClick()
        compose.onNodeWithContentDescription(text(R.string.automation_state_delete_value, "true")).performClick()
        compose.onNodeWithText(text(CoreUiR.string.cancel)).performClick()

        assertThat(saved).isEmpty()
        assertThat(deleted).isEmpty()
    }
}
