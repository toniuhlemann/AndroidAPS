package app.aaps.core.ui.compose.fuse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.stringResource
import kotlin.math.roundToInt

/** Test tags of the meal dialog: the two input fields, the line below them and the button "Mahlzeit". */
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
 * Meal input (Toni 25.11.2026): two optional number fields for the carbs and the absorption time of the meal. They
 * apply to "Mahlzeit" only. With them the button and the budget show what FUSE books for this meal
 * ([FuseOverviewSource.MealInputOffer]); the line below the fields names the budget and the meal window. Empty fields
 * leave everything as it was. While a field holds a value that is not valid, "Mahlzeit" cannot be chosen.
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
    var carbsText by remember { mutableStateOf("") }
    var absorptionText by remember { mutableStateOf("") }
    val input = facts.mealInput?.takeIf { facts.expectationOnlyMaxU != null }
    val typed = if (input == null) FuseMealInput.NONE else FuseMealInput.parse(carbsText, absorptionText)
    // What "Mahlzeit" books with the typed values. `null` with a typed value = the value is not valid.
    val offer = remember(input, typed) {
        if (input == null || typed == null || typed.isEmpty) null else input.offer(typed.carbsG, typed.absorptionMin)
    }
    val inputGiven = input != null && (typed == null || !typed.isEmpty)
    val inputValid = !inputGiven || offer != null
    // What the press hands on: the typed values when they count, else nothing.
    val chosenInput = typed?.takeIf { offer != null } ?: FuseMealInput.NONE
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
    val focusManager = LocalFocusManager.current
    AlertDialog(
        // Only the buttons close it: a tap beside it must not count as an answer.
        onDismissRequest = { },
        title = { Text(stringResource(CoreUiStrings.fuse_meal_confirm_title)) },
        text = {
            Column(
                // It scrolls: with the keyboard open the buttons must stay in reach.
                modifier = Modifier
                    .clearFocusOnTap(focusManager)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
            ) {
                Text(text)
                if (input != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                        OutlinedTextField(
                            value = carbsText,
                            onValueChange = { carbsText = it },
                            label = { Text(stringResource(CoreUiStrings.fuse_meal_input_carbs)) },
                            suffix = { Text(stringResource(CoreUiStrings.units_grams)) },
                            singleLine = true,
                            isError = !inputValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier
                                .weight(1f)
                                .testTag(FUSE_MEAL_CARBS_TAG)
                        )
                        OutlinedTextField(
                            value = absorptionText,
                            onValueChange = { absorptionText = it },
                            label = { Text(stringResource(CoreUiStrings.fuse_meal_input_absorption)) },
                            suffix = { Text(stringResource(CoreUiStrings.units_hours)) },
                            singleLine = true,
                            isError = !inputValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            modifier = Modifier
                                .weight(1f)
                                .testTag(FUSE_MEAL_ABSORPTION_TAG)
                        )
                    }
                    // One line that is always there and only changes its text: the hint, what the input books, or
                    // why it does not count. A line that appears is not announced by a screen reader; one that
                    // changes is. The region is set only for the error, so the other texts stay quiet while typing.
                    Text(
                        text = when {
                            !inputValid   -> stringResource(CoreUiStrings.fuse_meal_input_invalid, input.maxCarbsG, input.maxAbsorptionMin / 60)
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
                    onClick = { once { onExpectationOnly(chosenInput.carbsG, chosenInput.absorptionMin) } },
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

/**
 * The meal input as typed in the dialog: the carbs [g] and the absorption time [min]; 0 = the field is empty.
 */
data class FuseMealInput(val carbsG: Int, val absorptionMin: Int) {

    val isEmpty: Boolean get() = carbsG == 0 && absorptionMin == 0

    companion object {

        val NONE = FuseMealInput(0, 0)

        /**
         * Reads the two fields: [carbsText] in grams, [absorptionText] in hours (a comma or a point for the decimals).
         * An empty field is 0 = not given. `null` = a field holds something that is not a number above zero. The
         * limits are checked by FUSE ([FuseOverviewSource.MealInputOffer.offer]), not here.
         */
        fun parse(carbsText: String, absorptionText: String): FuseMealInput? {
            val carbsG = number(carbsText)?.let { it.roundToInt() } ?: return null
            val absorptionMin = number(absorptionText)?.let { (it * 60.0).roundToInt() } ?: return null
            // A value that rounds to 0 was typed, but would count as "not given": it is not a usable number.
            if ((carbsText.isNotBlank() && carbsG == 0) || (absorptionText.isNotBlank() && absorptionMin == 0)) return null
            return FuseMealInput(carbsG, absorptionMin)
        }

        /** The number in [text]; an empty text is 0. `null` = no number, not finite, negative or far too large. */
        private fun number(text: String): Double? {
            if (text.isBlank()) return 0.0
            val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() && it > 0.0 && it <= MAX_TYPED }
        }

        /** Above this a typed number is not read at all (it would not fit into the whole numbers used here). */
        private const val MAX_TYPED = 100_000.0
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
