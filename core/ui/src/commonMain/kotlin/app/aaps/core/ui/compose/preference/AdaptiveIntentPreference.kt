/*
 * Adaptive Intent Preferences for Jetpack Compose
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
import androidx.compose.ui.platform.LocalUriHandler
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.stringResource

/**
 * Composable intent preference for use inside card sections.
 *
 * @param title Optional title override. If null, uses intentKey.title
 * @param summary Optional summary override. If null, uses intentKey.summary
 * @param visibilityContext Optional context for evaluating runtime visibility/enabled conditions
 */
@Composable
fun AdaptiveIntentPreferenceItem(
    intentKey: IntentPreferenceKey,
    title: TextRef? = null,
    summary: TextRef? = null,
    onClick: () -> Unit,
    visibilityContext: VisibilityContext? = null
) {
    val effectiveTitle = title ?: intentKey.title
    val effectiveSummary = summary ?: intentKey.summary

    val visibility = calculateIntentPreferenceVisibility(
        intentKey = intentKey,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    // Show confirmation dialog when confirmationMessage is set on the key
    val confirmation = intentKey.confirmationMessage
    var showConfirmation by remember { mutableStateOf(false) }

    if (showConfirmation && confirmation != null) {
        OkCancelDialog(
            title = stringResource(effectiveTitle),
            message = stringResource(confirmation),
            onConfirm = {
                onClick()
                showConfirmation = false
            },
            onDismiss = { showConfirmation = false }
        )
    }

    val effectiveOnClick = if (confirmation != null) {
        { showConfirmation = true }
    } else {
        onClick
    }

    IntentRow(
        title = effectiveTitle,
        summary = effectiveSummary,
        enabled = visibility.enabled,
        onClick = if (visibility.enabled) effectiveOnClick else null
    )
}

/**
 * Composable URL preference for use inside card sections.
 *
 * @param title Optional title override. If null, uses intentKey.title
 */
@Composable
fun AdaptiveUrlPreferenceItem(
    intentKey: IntentPreferenceKey,
    title: TextRef? = null,
    url: String,
    visibilityContext: VisibilityContext? = null
) {
    val effectiveTitle = title ?: intentKey.title

    val visibility = calculateIntentPreferenceVisibility(
        intentKey = intentKey,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    val uriHandler = LocalUriHandler.current
    Preference(
        title = { Text(stringResource(effectiveTitle)) },
        summary = { Text(url) },
        enabled = visibility.enabled,
        onClick = if (visibility.enabled) {
            { uriHandler.openUri(url) }
        } else null
    )
}

/**
 * Composable preference that navigates to an inline Compose screen.
 * Used for IntentPreferenceKey with composeScreen attached via withCompose().
 */
@Composable
fun AdaptiveComposeScreenPreferenceItem(
    intentKey: IntentPreferenceKey,
    composeScreen: ComposeScreenContent,
    onNavigate: (ComposeScreenContent) -> Unit,
    title: TextRef? = null,
    summary: TextRef? = null,
    visibilityContext: VisibilityContext? = null
) {
    val effectiveTitle = title ?: intentKey.title
    val effectiveSummary = summary ?: intentKey.summary

    val visibility = calculateIntentPreferenceVisibility(
        intentKey = intentKey,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    IntentRow(
        title = effectiveTitle,
        summary = effectiveSummary,
        enabled = visibility.enabled,
        onClick = if (visibility.enabled) {
            { onNavigate(composeScreen) }
        } else null
    )
}

/**
 * A row that opens something rather than holding a value. On a [PreferenceSummaryMode.SHORT] screen its summary is
 * cut to two lines; on a [PreferenceSummaryMode.LIVE_ONLY] screen it shows behind an info sign next to the title,
 * because a tap on the row opens its target.
 */
@Composable
private fun IntentRow(title: TextRef, summary: TextRef?, enabled: Boolean, onClick: (() -> Unit)?) {
    val mode = LocalPreferenceSummaryMode.current
    val titleText = stringResource(title)
    val summaryText = summary?.let { stringResource(it) }
    Preference(
        title = {
            if (mode == PreferenceSummaryMode.LIVE_ONLY && summaryText != null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(titleText, modifier = Modifier.weight(1f, fill = false))
                    PreferenceExplanationButton(title = titleText, explanation = summaryText)
                }
            else Text(titleText)
        },
        summary = summaryText?.takeIf { mode != PreferenceSummaryMode.LIVE_ONLY }?.let { text ->
            {
                if (mode == PreferenceSummaryMode.SHORT) ShortPreferenceSummary(text = text, title = titleText)
                else Text(text)
            }
        },
        enabled = enabled,
        onClick = onClick
    )
}
