package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource

/**
 * How much of its explanation a row shows under its title. A screen picks it with
 * `PreferenceSubScreenDef.shortSummaries` or `PreferenceSubScreenDef.liveLinesOnly`; the screen renderer provides it for
 * the rows of that screen. [FULL] everywhere else, and then the rows look exactly as before.
 */
enum class PreferenceSummaryMode {

    /** The whole explanation under the title. */
    FULL,

    /** At most [SHORT_SUMMARY_MAX_LINES] lines - the live line of the row, or the start of its explanation - and all of it after a tap. */
    SHORT,

    /**
     * Only the live line, if the row has one. The explanation shows where the value is entered (the input dialog). A
     * switch has no dialog, so it shows an info sign that opens the explanation.
     */
    LIVE_ONLY
}

val LocalPreferenceSummaryMode = staticCompositionLocalOf { PreferenceSummaryMode.FULL }

/** Lines a short summary shows before it is cut with "…". */
const val SHORT_SUMMARY_MAX_LINES = 2

/**
 * The summary of a row in a short-summary screen: [text] cut to [SHORT_SUMMARY_MAX_LINES] lines. When there is more
 * to read - a cut [text], or an [explanation] that is not the shown text - an info sign follows, and a tap opens the
 * explanation (or the whole text) in a dialog with [title]. The dialog scrolls and shows the text as it is (no HTML).
 */
@Composable
fun ShortPreferenceSummary(
    text: String,
    title: String?,
    modifier: Modifier = Modifier,
    explanation: String? = null,
    style: TextStyle = LocalPreferenceTheme.current.summaryTextStyle,
    color: Color = LocalPreferenceTheme.current.summaryColor
) {
    var cut by remember(text) { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val full = explanation?.takeIf { it.isNotBlank() && it != text } ?: text
    val hasMore = cut || full != text
    val label = stringResource(CoreUiStrings.preference_show_explanation)
    Row(
        modifier = if (hasMore) modifier.clickable(onClickLabel = label) { open = true } else modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = SHORT_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
            modifier = Modifier.weight(1f, fill = false)
        )
        if (hasMore) InfoSign(label, color)
    }
    if (open) ExplanationDialog(title, full) { open = false }
}

/**
 * Only the info sign: a tap opens [explanation] in a dialog with [title]. For a row that shows no text of its own but
 * has an explanation and no dialog to show it in (a switch on a [PreferenceSummaryMode.LIVE_ONLY] screen).
 */
@Composable
fun PreferenceExplanationButton(
    title: String?,
    explanation: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPreferenceTheme.current.summaryColor
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(CoreUiStrings.preference_show_explanation)
    Row(modifier = modifier.clickable(onClickLabel = label) { open = true }) { InfoSign(label, color) }
    if (open) ExplanationDialog(title, explanation) { open = false }
}

@Composable
private fun InfoSign(label: String, color: Color) =
    Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = label,
        tint = color,
        modifier = Modifier
            .padding(start = AapsSpacing.small)
            .size(AapsSpacing.extraLarge)
    )

@Composable
private fun ExplanationDialog(title: String?, text: String, onDismiss: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.ok)) } }
    )
