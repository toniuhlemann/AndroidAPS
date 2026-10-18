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
 * Short summaries: the rows of a screen show at most [SHORT_SUMMARY_MAX_LINES] lines under the title - the live line
 * of the row, or the start of its explanation - and the whole explanation after a tap. A screen asks for it with
 * `PreferenceSubScreenDef.shortSummaries`; the screen renderer provides this flag for the rows of that screen. Off
 * everywhere else, and then the rows look exactly as before.
 */
val LocalShortPreferenceSummaries = staticCompositionLocalOf { false }

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
        if (hasMore)
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = label,
                tint = color,
                modifier = Modifier
                    .padding(start = AapsSpacing.small)
                    .size(AapsSpacing.extraLarge)
            )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = title?.let { { Text(it) } },
            text = { Text(full, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(CoreUiStrings.ok)) } }
        )
    }
}
