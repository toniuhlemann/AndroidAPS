package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.dialogs.ValueInputDialog
import app.aaps.core.ui.compose.formatSliderDisplayValue
import app.aaps.core.ui.compose.stringResource

/**
 * A value as one row on a page ([LocalCompactPreferenceRows]): its name and at most one line on the left, its value on
 * the right. A tap anywhere on the row opens the input of the value, with the explanation.
 *
 * There is no slider in this row. A page lists values, and three lines for each of them make it long again; the dialog
 * is the place to change one.
 *
 * @param head the name of the value and the line below it
 * @param dialogLabel the name again, as the title of the input and for a screen reader
 * @param dialogSummary the explanation shown with the input
 */
@Composable
internal fun CompactValueRow(
    head: @Composable () -> Unit,
    value: Double,
    onValueChange: (Double) -> Unit,
    valueRange: ClosedFloatingPointRange<Double>,
    step: Double,
    valueFormatRef: TextRef?,
    formatAsInt: Boolean,
    valueFormat: NumberFormat,
    unitLabel: TextRef?,
    asDuration: Boolean,
    dialogLabel: String,
    dialogSummary: String?,
    enabled: Boolean
) {
    val theme = LocalPreferenceTheme.current
    var showDialog by remember { mutableStateOf(false) }
    val displayText = formatSliderDisplayValue(
        value = value,
        unitLabel = unitLabel,
        valueFormatRef = valueFormatRef,
        formatAsInt = formatAsInt,
        valueFormat = valueFormat,
        asDuration = asDuration
    )
    // The value alone says nothing to a screen reader: a number and a unit, but of what? Name and value are read as one.
    val labelledValueText = stringResource(InterfacesStrings.confirmation_line, dialogLabel, displayText)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(role = Role.Button) { showDialog = true } else Modifier)
            .padding(theme.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) { head() }
        Spacer(modifier = Modifier.width(AapsSpacing.large))
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary.let { if (enabled) it else it.copy(alpha = theme.disabledOpacity) },
            modifier = Modifier.semantics { contentDescription = labelledValueText }
        )
    }

    if (showDialog && enabled) {
        ValueInputDialog(
            currentValue = value,
            valueRange = valueRange,
            step = step,
            label = dialogLabel,
            summary = dialogSummary,
            unitLabel = unitLabel,
            asDuration = asDuration,
            valueFormat = valueFormat,
            onValueConfirm = onValueChange,
            onDismiss = { showDialog = false }
        )
    }
}
