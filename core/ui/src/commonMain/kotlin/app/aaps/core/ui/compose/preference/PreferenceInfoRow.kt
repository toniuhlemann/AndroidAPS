package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.ui.compose.stringResource

/**
 * Renders a [PreferenceInfoDef]: a heading with its text like a preference row, or the text alone as a short
 * paragraph. Neither changes anything. On a screen with short summaries ([LocalShortPreferenceSummaries]) the text
 * is cut to two lines and a tap shows all of it.
 */
@Composable
fun PreferenceInfoRow(info: PreferenceInfoDef) {
    val title = info.title
    val short = LocalShortPreferenceSummaries.current
    val text = stringResource(info.text)
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
