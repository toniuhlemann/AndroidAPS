package app.aaps.core.ui.compose.fuse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource

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
 */
@Composable
fun FuseMealPromptDialog(
    facts: FuseOverviewSource.MarkerPromptFacts,
    onExpectationOnly: () -> Unit,
    onNoPrime: () -> Unit,
    onCancel: () -> Unit,
    /** "Snack"; `null` = not offered by the caller. */
    onSnack: (() -> Unit)? = null,
) {
    var chosen by remember { mutableStateOf(false) }
    val maxU = facts.expectationOnlyMaxU
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
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Text(text)
                // The amount on the button is what goes out at once at most - the initial portion, never more than
                // the budget.
                if (maxU != null) Button(onClick = { once(onExpectationOnly) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CoreUiStrings.fuse_meal_confirm_expectation_only, facts.expectationOnlyInitialU ?: maxU))
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
