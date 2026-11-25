package app.aaps.core.ui.compose.fuse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Robolectric test for the meal input of [FuseMealPromptDialog] (Toni 25.11.2026): two optional fields for the carbs
 * and the absorption time of the meal. Empty fields leave the dialog as it was. Typed values show what "Mahlzeit" books
 * for this meal and go out with the press. A value that does not count blocks "Mahlzeit". Made-up numbers.
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
     * above 3 h -, the window is the absorption time (3 h without one). Above the limits there is no offer.
     */
    private val input = FuseOverviewSource.MealInputOffer(maxCarbsG = 300, maxAbsorptionMin = 600) { carbsG, absorptionMin ->
        asked += carbsG to absorptionMin
        if (carbsG > 300 || absorptionMin > 600) null
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

    private fun pressMeal() = compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).performScrollTo().performClick()

    @Test
    fun withoutMealInputTheDialogHasNoFields() {
        show(withInput = false)

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertTextContains("Mahlzeit · 4", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(0 to 0)
    }

    @Test
    fun emptyFieldsLeaveEverythingAsItWas() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).assertExists()
        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).assertExists()
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Leer = wie bisher", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsEnabled().assertTextContains("Mahlzeit · 4", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(0 to 0)
        // Nothing was typed, so FUSE was not asked for an offer.
        assertThat(asked).isEmpty()
    }

    @Test
    fun theCarbsSetTheBudgetAndTheAmountOnTheButton() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).performTextInput("60")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Budget 6", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Fenster 180 min", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsEnabled().assertTextContains("Mahlzeit · 3", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(60 to 0)
    }

    @Test
    fun theAbsorptionTimeIsTypedInHoursAndGoesOutInMinutes() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).performTextInput("4")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Fenster 240 min", substring = true)
        // A long absorption time: less goes out at once, the budget stays.
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Budget 9", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertTextContains("Mahlzeit · 2", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(0 to 240)
    }

    @Test
    fun bothValuesGoOutTogetherAndACommaCountsAsDecimalPoint() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).performTextInput("48")
        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).performTextInput("3,5")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Fenster 210 min", substring = true)
        pressMeal()

        assertThat(chosen).containsExactly(48 to 210)
    }

    @Test
    fun aValueAboveItsLimitBlocksMahlzeitAndTheLineSaysWhy() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_CARBS_TAG).performTextInput("500")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Eingabe gilt nicht", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("300 g", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("10 h", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsNotEnabled()
        pressMeal()

        assertThat(chosen).isEmpty()
    }

    @Test
    fun somethingThatIsNoNumberBlocksMahlzeitToo() {
        show()

        compose.onNodeWithTag(FUSE_MEAL_ABSORPTION_TAG).performTextInput("lang")

        compose.onNodeWithTag(FUSE_MEAL_INPUT_LINE_TAG).assertTextContains("Eingabe gilt nicht", substring = true)
        compose.onNodeWithTag(FUSE_MEAL_BUTTON_TAG).assertIsNotEnabled()
        // It is not a number, so there was nothing to ask FUSE about.
        assertThat(asked).isEmpty()
    }

    @Test
    fun theFieldsAreReadAsGramsAndHours() {
        assertThat(FuseMealInput.parse("", "")).isEqualTo(FuseMealInput.NONE)
        assertThat(FuseMealInput.parse("  ", " ")).isEqualTo(FuseMealInput.NONE)
        assertThat(FuseMealInput.parse("60", "")).isEqualTo(FuseMealInput(60, 0))
        assertThat(FuseMealInput.parse(" 60 ", "3,5")).isEqualTo(FuseMealInput(60, 210))
        assertThat(FuseMealInput.parse("60.4", "0.5")).isEqualTo(FuseMealInput(60, 30))
        assertThat(FuseMealInput.parse("", "2")).isEqualTo(FuseMealInput(0, 120))
        assertThat(FuseMealInput.NONE.isEmpty).isTrue()
        assertThat(FuseMealInput(0, 120).isEmpty).isFalse()
    }

    @Test
    fun aFieldThatHoldsNoNumberAboveZeroIsNotRead() {
        for (bad in listOf("0", "-5", "abc", "1e9", "0.4", "NaN", "Infinity")) {
            assertThat(FuseMealInput.parse(bad, "")).isNull()
        }
        for (bad in listOf("0", "-1", "h", "0,001", "1e9")) {
            assertThat(FuseMealInput.parse("", bad)).isNull()
        }
    }
}
