package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.ui.compose.stringResource

/**
 * Renders a [PreferenceInfoDef]: a heading with its text like a preference row, or the text alone as a short
 * paragraph. Neither is clickable - there is nothing to change.
 */
@Composable
fun PreferenceInfoRow(info: PreferenceInfoDef) {
    val title = info.title
    if (title != null) {
        Preference(
            title = { Text(stringResource(title)) },
            summary = { Text(stringResource(info.text)) }
        )
    } else {
        val theme = LocalPreferenceTheme.current
        Text(
            text = stringResource(info.text),
            style = theme.summaryTextStyle,
            color = theme.summaryColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(theme.padding)
        )
    }
}
