package app.aaps.core.ui.compose.fuse

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource

/** Test tags of the withdraw question: the button that withdraws and the one that keeps the meal. */
const val FUSE_MEAL_WITHDRAW_YES_TAG = "fuse_meal_withdraw_yes"
const val FUSE_MEAL_WITHDRAW_NO_TAG = "fuse_meal_withdraw_no"

/**
 * THE QUESTION BEFORE A RUNNING MEAL IS WITHDRAWN (Toni 27.11.2026) - ONE dialog for the meal button on the main
 * screen and the one in the FUSE tab, like [FuseMealPromptDialog].
 *
 * Until now a press on the running meal only showed a bar with "Rueckgaengig" and withdrew the meal when nobody
 * reacted. A press by mistake that nobody notices then ended the meal: no meal account any more and the smaller
 * correction cap, in the middle of a rise. Now it is the other way round: ONLY the button "Zuruecknehmen" withdraws
 * the meal. "Mahlzeit behalten", the back key and a tap outside the dialog all keep it - no answer means no change.
 *
 * After "Zuruecknehmen" the withdrawal runs as before (noted, with the undo bar until the deadline).
 */
@Composable
fun FuseMealWithdrawConfirmDialog(
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
    // A choice acts exactly once (double tap protection, as in the meal question).
    var chosen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!chosen) { chosen = true; onKeep() } },
        title = { Text(stringResource(CoreUiStrings.fuse_meal_withdraw_confirm_title)) },
        text = { Text(stringResource(CoreUiStrings.fuse_meal_withdraw_confirm_text)) },
        confirmButton = {
            TextButton(
                onClick = { if (!chosen) { chosen = true; onConfirm() } },
                modifier = Modifier.testTag(FUSE_MEAL_WITHDRAW_YES_TAG),
            ) {
                Text(stringResource(CoreUiStrings.fuse_meal_withdraw_confirm_yes), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!chosen) { chosen = true; onKeep() } },
                modifier = Modifier.testTag(FUSE_MEAL_WITHDRAW_NO_TAG),
            ) {
                Text(stringResource(CoreUiStrings.fuse_meal_withdraw_confirm_no))
            }
        },
    )
}
