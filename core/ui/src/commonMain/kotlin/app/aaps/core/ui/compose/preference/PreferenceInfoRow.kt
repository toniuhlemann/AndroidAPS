package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.ui.compose.stringResource

/**
 * Renders a [PreferenceInfoDef]: a heading with its text like a preference row, or the text alone as a short
 * paragraph. Neither changes anything. On a screen with short summaries or live lines only
 * ([LocalPreferenceSummaryMode]) the text is cut to two lines and a tap shows all of it - an info row has no dialog,
 * so it is never hidden.
 *
 * A group heading ([PreferenceInfoDef.groupHeading]) is drawn in its own way, see [PreferenceGroupHeading].
 */
@Composable
fun PreferenceInfoRow(info: PreferenceInfoDef) {
    val title = info.title
    val short = LocalPreferenceSummaryMode.current != PreferenceSummaryMode.FULL
    val text = stringResource(info.text)
    if (title != null && info.groupHeading) {
        PreferenceGroupHeading(title = stringResource(title), text = text)
        return
    }
    if (title != null) {
        val titleText = stringResource(title)
        Preference(
            title = { Text(titleText) },
            summary = {
                if (short) ShortPreferenceSummary(text = text, title = titleText)
                else Text(text)
            }
        )
    } else {
        val theme = LocalPreferenceTheme.current
        val modifier = Modifier
            .fillMaxWidth()
            .padding(theme.padding)
        if (short) ShortPreferenceSummary(text = text, title = null, modifier = modifier)
        else Text(
            text = text,
            style = theme.summaryTextStyle,
            color = theme.summaryColor,
            modifier = modifier
        )
    }
}

/**
 * The heading of a group of screens inside one card: a line above it, the title larger and in the primary color, and
 * one line that says what the group is about. It is not cut and cannot be clicked. Marked as a heading, so a screen
 * reader can jump from group to group.
 */
@Composable
private fun PreferenceGroupHeading(title: String, text: String) {
    val theme = LocalPreferenceTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(theme.headerPaddingInsideCard)
        ) {
            Text(
                text = title,
                // One step larger than the title of a screen under it, so the two levels read as two levels.
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = theme.summaryCategoryTextStyle,
                    color = theme.summaryCategoryColor
                )
            }
        }
    }
}
