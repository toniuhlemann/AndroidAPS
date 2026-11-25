package app.aaps.core.ui.compose.fuse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.aaps.core.interfaces.overview.FuseOverviewSource
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric test for the meal input of [FuseMealPromptDialog] (Toni 25.11.2026): the carbs and the absorption time of
 * the meal, with the controls of the carbs dialog. 0 leaves the dialog as it was. Given values show what "Mahlzeit"
 * books for this meal and go out with the press. Made-up numbers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FuseMealPromptDialogTest {

    @get:Rule
    val compose = createComposeRule()

    /** What "Mahlzeit" was pressed with, and what the dialog asked FUSE about. */
    private val chosen = mutableListOf<Pair<Int, Int>>()
    private val asked = mutableListOf<Pair<Int, Int>>()

    /**
     * A stand-in for FUSE: 10 g of carbs per unit as the budget (9 U without carbs), half of it at once - a quarter
     * above 3 h -, the window is the absorption time (3 h without one). 7 g cannot be booked (as without a carb ratio).
     */
    private val input = FuseOverviewSource.MealInputOffer(maxCarbsG = 300, maxAbsorptionMin = 300, carbsIncrements = listOf(5, 10, 20)) { carbsG, absorptionMin ->
        asked += carbsG to absorptionMin
        if (carbsG == 7) null
        else {
            val budget = if (carbsG > 0) carbsG / 10.0 else 9.0
            FuseOverviewSource.MealOffer(
                budgetU = budget,
                initialU = budget * (if (absorptionMin > 180) 0.25 else 0.5),
                windowMin = if (absorptionMin > 0) absorptionMin else 180,
            )
        }
    }

    private fun show(withInput: Boolean = true) = compose.setContent {
        MaterialTheme {
            FuseMealPromptDialog(
                facts = FuseOverviewSource.MarkerPromptFacts(
                    measuredLow = false, expectationOnlyMaxU = 9.0, expectationOnlyInitialU = 4.5,
                    mealInput = input.takeIf { withInput },
                ),
                onExpectationOnly = { carbsG, absorptionMin -> chosen += carbsG to absorptionMin },
                onNoPrime = {},
                onCancel = {},
            )
        }
    }

    /** The text field inside one of the two input rows. */
    private fun field(tag: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    private fun pressMeal() = compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).performScrollTo().performClick()

    @Test
    fun withoutMealInputTheDialogHasNoRows() {
        show(withInput = false)

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertTextContains("Mahlzeit · 4", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(0 to 0)
    }

    @Test
    fun zeroLeavesEverythingAsItWas() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).assertExists()
        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).assertExists()
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("0 = wie bisher", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsEnabled().assertTextContains("Mahlzeit · 4", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(0 to 0)
        // Nothing was given, so FUSE was not asked for an offer.
        assertThat(asked).isEmpty()
    }

    @Test
    fun theCarbsSetTheBudgetAndTheAmountOnTheButton() {
        show()

        field(FUSE_MEAL_CARBS_TAG).performTextInput("60")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Budget 6", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Fenster 180 min", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsEnabled().assertTextContains("Mahlzeit · 3", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(60 to 0)
    }

    @Test
    fun theQuickAddButtonsOfTheCarbsDialogAddCarbs() {
        show()

        compose.onNodeWithText("+20").performScrollTo().performClick()
        compose.onNodeWithText("+20").performScrollTo().performClick()
        compose.onNodeWithText("+5").performScrollTo().performClick()

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Budget 4", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(45 to 0)
    }

    @Test
    fun theAbsorptionTimeIsGivenInHoursAndGoesOutInMinutes() {
        show()

        field(FUSE_MEAL_ABSORPTION_TAG).performTextInput("4")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Fenster 240 min", substring = true)
        // A long absorption time: less goes out at once, the budget stays.
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Budget 9", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertTextContains("Mahlzeit · 2", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(0 to 240)
    }

    @Test
    fun bothValuesGoOutTogetherAndHalfHoursCount() {
        show()

        field(FUSE_MEAL_CARBS_TAG).performTextInput("48")
        field(FUSE_MEAL_ABSORPTION_TAG).performTextInput("3,5")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Fenster 210 min", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(48 to 210)
    }

    /**
     * A number above the range stays in the field as an error and is not taken. The press takes it over first, at the
     * end of the range - and does nothing, because the dialog did not show that input yet. The next press counts.
     */
    @Test
    fun aNumberAboveTheRangeIsTakenOverAtItsEndBeforeAPressCounts() {
        show()

        field(FUSE_MEAL_CARBS_TAG).performTextInput("500")
        pressMeal()

        assertThat(chosen).isEmpty()
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Budget 30", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(300 to 0)
    }

    @Test
    fun anInputThatFuseCannotBookBlocksMahlzeitAndTheLineSaysSo() {
        show()

        field(FUSE_MEAL_CARBS_TAG).performTextInput("7")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("kann FUSE nicht rechnen", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsNotEnabled()
        pressMeal()

        assertThat(chosen).isEmpty()
    }
}
