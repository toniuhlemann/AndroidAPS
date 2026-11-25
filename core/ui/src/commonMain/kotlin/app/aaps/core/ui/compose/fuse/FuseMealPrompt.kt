package app.aaps.core.ui.compose.fuse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.QuickAddButtons
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/** Test tags of the meal dialog: the two input rows, the line below them and the button "Mahlzeit". */
const val FUSE_MEAL_CARBS_TAG = "fuse_meal_carbs"
const val FUSE_MEAL_ABSORPTION_TAG = "fuse_meal_absorption"
const val FUSE_MEAL_INPUT_LINE_TAG = "fuse_meal_input_line"
const val FUSE_MEAL_BUTTON_TAG = "fuse_meal_button"

/**
 * The question at the FUSE meal button (fork: FuseMarkerDialog) - ONE dialog for the button on the main screen
 * and the one in the FUSE tab. Two versions would be two safety levels for the same button.
 *
 * With an expectation account: "Mahlzeit", "Snack" (Toni 24.10.2026: the same account with the smaller snack scale)
 * or "Nur Marker - 0 U", one below the other, each with the amount that goes out at once at most. Without an account
 * only "Nur Marker - 0 U". The text says in one sentence that "Nur Marker" gives nothing at the press and FUSE only
 * reacts when glucose rises. A measured low, manual insulin of the last hour and an evidence window that runs out are
 * named in the text. A choice acts exactly once (double tap protection, as in the fork). Whether to ask at all is
 * decided by FUSE ([FuseOverviewSource.fuseMarkerPrompt]), not here.
 *
 * Meal input (Toni 25.11.2026): two optional numbers for the carbs and the absorption time of the meal, with the
 * controls of the carbs dialog (a number field with -/+, the quick add buttons of the carbs dialog, the time in half
 * hours). They apply to "Mahlzeit" only. With them the button and the budget show what FUSE books for this meal
 * ([FuseOverviewSource.MealInputOffer]); the line below names the budget and the meal window. 0 leaves everything as
 * it was. The rows keep every value inside its range; when FUSE still cannot book the input (no carb ratio in the
 * profile), "Mahlzeit" cannot be chosen and the line says so.
 */
@Composable
fun FuseMealPromptDialog(
    facts: FuseOverviewSource.MarkerPromptFacts,
    /** "Mahlzeit" with the meal input of the dialog: the carbs [g] and the absorption time [min]; 0 = not given. */
    onExpectationOnly: (carbsG: Int, absorptionMin: Int) -> Unit,
    onNoPrime: () -> Unit,
    onCancel: () -> Unit,
    /** "Snack"; `null` = not offered by the caller. */
    onSnack: (() -> Unit)? = null,
) {
    var chosen by remember { mutableStateOf(false) }
    var carbsG by remember { mutableStateOf(0) }
    var absorptionH by remember { mutableStateOf(0.0) }
    val input = facts.mealInput?.takeIf { facts.expectationOnlyMaxU != null }
    val typed = if (input == null) FuseMealInput.NONE else FuseMealInput(carbsG, (absorptionH * 60.0).roundToInt())
    // What "Mahlzeit" books with the given values. `null` with a given value = FUSE cannot book it.
    val offer = remember(input, typed) {
        if (input == null || typed.isEmpty) null else input.offer(typed.carbsG, typed.absorptionMin)
    }
    val inputGiven = input != null && !typed.isEmpty
    val inputValid = !inputGiven || offer != null
    // What the press hands on: the given values when they count, else nothing.
    val chosenInput = typed.takeIf { offer != null } ?: FuseMealInput.NONE
    val maxU = offer?.budgetU ?: facts.expectationOnlyMaxU
    val initialU = offer?.initialU ?: facts.expectationOnlyInitialU
    val snackMaxU = facts.snackMaxU?.takeIf { maxU != null && onSnack != null }
    val text = buildString {
        append(
            if (maxU != null) stringResource(CoreUiStrings.fuse_meal_confirm_expectation_only_text, maxU)
            else stringResource(CoreUiStrings.fuse_meal_confirm_no_account)
        )
        snackMaxU?.let { append(" ").append(stringResource(CoreUiStrings.fuse_meal_confirm_snack_text, it)) }
        append("\n\n").append(stringResource(CoreUiStrings.fuse_meal_confirm_no_prime_text))
        if (facts.measuredLow) append("\n\n").append(stringResource(CoreUiStrings.fuse_meal_confirm_low))
        facts.foreignBolusU?.takeIf { it > 0.0 }?.let { append("\n\n").append(stringResource(CoreUiStrings.fuse_meal_confirm_foreign, it)) }
        facts.episodeRestMin?.let { append("\n\n").append(stringResource(CoreUiStrings.fuse_meal_confirm_episode, it)) }
    }

    fun once(action: () -> Unit) {
        if (chosen) return
        chosen = true
        action()
    }
    AlertDialog(
        // Only the buttons close it: a tap beside it must not count as an answer.
        onDismissRequest = { },
        title = { Text(stringResource(CoreUiStrings.fuse_meal_confirm_title)) },
        text = {
            // The focus of the DIALOG window: the fields live there. The one outside it belongs to the screen below.
            val focusManager = LocalFocusManager.current
            Column(
                // It scrolls: with the keyboard open the buttons must stay in reach.
                modifier = Modifier
                    .clearFocusOnTap(focusManager)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
            ) {
                Text(text)
                if (input != null) {
                    // The carbs as in the carbs dialog: a number field with -/+ and the same quick add buttons.
                    NumberInputRow(
                        labelRef = CoreUiStrings.fuse_meal_input_carbs,
                        value = carbsG.toDouble(),
                        onValueChange = { carbsG = it.roundToInt() },
                        valueRange = 0.0..input.maxCarbsG.toDouble(),
                        step = 1.0,
                        valueFormat = NumberFormat.INTEGER,
                        unitLabel = CoreUiStrings.shortgramm,
                        modifier = Modifier.testTag(FUSE_MEAL_CARBS_TAG)
                    )
                    QuickAddButtons(
                        increment1 = input.carbsIncrements.getOrElse(0) { 0 },
                        increment2 = input.carbsIncrements.getOrElse(1) { 0 },
                        increment3 = input.carbsIncrements.getOrElse(2) { 0 },
                        onAddCarbs = { carbsG = min(input.maxCarbsG, carbsG + it) }
                    )
                    // The absorption time in half hours, as the duration in the carbs dialog.
                    NumberInputRow(
                        labelRef = CoreUiStrings.fuse_meal_input_absorption,
                        value = absorptionH,
                        onValueChange = { absorptionH = it },
                        valueRange = 0.0..input.maxAbsorptionMin / 60.0,
                        step = ABSORPTION_STEP_H,
                        decimalPlaces = 1,
                        unitLabel = InterfacesStrings.shorthour,
                        modifier = Modifier.testTag(FUSE_MEAL_ABSORPTION_TAG)
                    )
                    // One line that is always there and only changes its text: the hint, what the input books, or
                    // why FUSE cannot book it. A line that appears is not announced by a screen reader; one that
                    // changes is. The region is set only for the error, so the other texts stay quiet.
                    Text(
                        text = when {
                            !inputValid   -> stringResource(CoreUiStrings.fuse_meal_input_invalid)
                            offer != null -> stringResource(CoreUiStrings.fuse_meal_input_effect, offer.budgetU, offer.windowMin)
                            else          -> stringResource(CoreUiStrings.fuse_meal_input_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (inputValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag(FUSE_MEAL_INPUT_LINE_TAG)
                            .semantics { if (!inputValid) liveRegion = LiveRegionMode.Assertive }
                    )
                }
                // The amount on the button is what goes out at once at most - the initial portion, never more than
                // the budget. With a meal input it is the amount for this meal.
                if (maxU != null) Button(
                    onClick = {
                        // A number still being typed is taken over first, as when its field loses focus. If that changes
                        // the input, this press does nothing: a press counts only for what the dialog showed.
                        focusManager.clearFocus()
                        val now = if (input == null) FuseMealInput.NONE else FuseMealInput(carbsG, (absorptionH * 60.0).roundToInt())
                        if (now == typed) once { onExpectationOnly(chosenInput.carbsG, chosenInput.absorptionMin) }
                    },
                    enabled = inputValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FUSE_MEAL_BUTTON_TAG)
                ) {
                    Text(stringResource(CoreUiStrings.fuse_meal_confirm_expectation_only, initialU ?: maxU))
                }
                if (snackMaxU != null && onSnack != null) OutlinedButton(onClick = { once(onSnack) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CoreUiStrings.fuse_meal_confirm_snack, facts.snackInitialU ?: snackMaxU))
                }
                OutlinedButton(onClick = { once(onNoPrime) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CoreUiStrings.fuse_meal_confirm_no_prime))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { once(onCancel) }) { Text(stringResource(CoreUiStrings.cancel)) }
        },
    )
}

/** The step of the absorption time in the dialog [h]: half hours, as a food app gives it. */
private const val ABSORPTION_STEP_H = 0.5

/** The meal input of the dialog: the carbs [g] and the absorption time [min]; 0 = not given. */
data class FuseMealInput(val carbsG: Int, val absorptionMin: Int) {

    val isEmpty: Boolean get() = carbsG == 0 && absorptionMin == 0

    companion object {

        val NONE = FuseMealInput(0, 0)
    }
}

/** What the FUSE meal button shows, the same on the main screen and in the FUSE tab. */
enum class FuseMealButtonState {

    /** No meal: a press announces one. */
    IDLE,

    /** A marker runs (the first 90 min): a press withdraws it, with an undo deadline. */
    ACTIVE,

    /** A marker runs, but no expectation account applies: FUSE doses as without a marker. */
    ACTIVE_NO_ACCOUNT,

    /** The window of the running account is still open, the marker is over: a press starts a NEW meal. */
    NEW_MEAL;

    companion object {

        /** The state of [source] at [now]; the order matters - a running marker says more than an open window. */
        fun of(source: FuseOverviewSource, now: Long): FuseMealButtonState = when {
            source.fuseMarkerArmed(now) && source.fuseMarkerOhneKonto(now) -> ACTIVE_NO_ACCOUNT
            source.fuseMarkerArmed(now)                                    -> ACTIVE
            source.fuseMahlzeitFensterLaeuft(now)                          -> NEW_MEAL
            else                                                           -> IDLE
        }
    }
}
