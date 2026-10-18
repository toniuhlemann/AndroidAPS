/*
 * Adaptive Switch Preference for Jetpack Compose
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.keys.interfaces.BooleanKeyWithChangeGuard
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.stringResource

/**
 * Composable switch preference for use inside card sections.
 *
 * @param title Optional title override. If null, uses booleanKey.title
 * @param summary Optional summary override. If null, uses booleanKey.summary
 * @param visibilityContext Optional context for evaluating runtime visibility/enabled conditions
 *
 * @see AdaptiveSwitchPreferencePreview
 */
@Composable
fun AdaptiveSwitchPreferenceItem(
    booleanKey: BooleanPreferenceKey,
    title: TextRef? = null,
    summary: TextRef? = null,
    summaryOn: TextRef? = null,
    summaryOff: TextRef? = null,
    visibilityContext: VisibilityContext? = null,
    /** Optional live line instead of the summary. A switch has no dialog, so the summary is not shown then. */
    rowSummary: String? = null
) {
    val effectiveTitle = title ?: booleanKey.title
    val effectiveSummary = summary ?: booleanKey.summary

    val visibility = calculatePreferenceVisibility(
        preferenceKey = booleanKey,
        engineeringModeOnly = booleanKey.engineeringModeOnly,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    val state = rememberPreferenceBooleanState(booleanKey)
    val changeGuard = (booleanKey as? BooleanKeyWithChangeGuard)?.guard

    var guardMessage by remember { mutableStateOf<String?>(null) }

    val mode = LocalPreferenceSummaryMode.current
    val summary: @Composable (() -> Unit)? = when {
        rowSummary != null                      -> {
            {
                if (mode != PreferenceSummaryMode.FULL) ShortPreferenceSummary(text = rowSummary, title = stringResource(effectiveTitle), explanation = effectiveSummary?.let { stringResource(it) })
                else Text(rowSummary)
            }
        }

        summaryOn != null && summaryOff != null -> {
            {
                val status = stringResource(if (state.value) summaryOn else summaryOff)
                if (mode == PreferenceSummaryMode.LIVE_ONLY) ShortPreferenceSummary(text = status, title = stringResource(effectiveTitle), explanation = effectiveSummary?.let { stringResource(it) })
                else Text(status)
            }
        }

        // LIVE_ONLY: no text on the row, the info sign next to the title opens the explanation
        mode == PreferenceSummaryMode.LIVE_ONLY -> null

        effectiveSummary != null                -> {
            {
                if (mode == PreferenceSummaryMode.SHORT) ShortPreferenceSummary(text = stringResource(effectiveSummary), title = stringResource(effectiveTitle))
                else Text(stringResource(effectiveSummary))
            }
        }

        else                                    -> null
    }
    // A switch has no dialog: a row without text keeps its explanation behind an info sign
    val explanationOnly = effectiveSummary.takeIf { mode == PreferenceSummaryMode.LIVE_ONLY && summary == null }
    val titleSlot: @Composable () -> Unit = {
        if (explanationOnly != null) TitleWithExplanation(effectiveTitle, booleanKey, explanationOnly)
        else PreferenceTitleWithSyncBadge(effectiveTitle, booleanKey)
    }

    if (changeGuard != null) {
        SwitchPreference(
            value = state.value,
            onValueChange = { newValue ->
                val message = changeGuard(newValue)
                if (message == null) {
                    state.value = newValue
                } else {
                    guardMessage = message
                }
            },
            title = titleSlot,
            summary = summary,
            enabled = visibility.enabled
        )
    } else {
        SwitchPreference(
            state = state,
            title = titleSlot,
            summary = summary,
            enabled = visibility.enabled
        )
    }

    // Show guard rejection dialog
    guardMessage?.let { message ->
        OkDialog(
            title = stringResource(CoreUiStrings.error),
            message = message,
            onDismiss = { guardMessage = null }
        )
    }
}

/** The title of a switch with the info sign that opens its [explanation] (no text under the title). */
@Composable
private fun TitleWithExplanation(title: TextRef, key: BooleanPreferenceKey, explanation: TextRef) {
    val titleText = stringResource(title)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextWithSyncBadge(text = titleText, key = key, modifier = Modifier.weight(1f, fill = false))
        PreferenceExplanationButton(title = titleText, explanation = stringResource(explanation))
    }
}
