package app.aaps.plugins.automation.compose.actions

import androidx.compose.runtime.Composable
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.actions.ActionSetAcceWeight
import app.aaps.plugins.automation.actions.ActionSetAutomationState
import app.aaps.plugins.automation.actions.ActionSetIobTH
import app.aaps.plugins.automation.compose.elements.AutomationDropdown

/*
 * Editors for the autoISF 3.2.x automation actions (Tobias/ga-zelle). In their own file, so the
 * upstream ActionEditors.kt only carries one line per action.
 */

@Composable
fun ActionSetAcceWeightEditor(a: ActionSetAcceWeight, tick: Int = 0, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    NumberInputRow(
        labelRef = AutomationStrings.autoisf_acce_weight,
        value = a.newWeight,
        onValueChange = { a.newWeight = it; onChange() },
        valueRange = ActionSetAcceWeight.MIN..ActionSetAcceWeight.MAX,
        step = ActionSetAcceWeight.STEP,
        decimalPlaces = 2
    )
}

@Composable
fun ActionSetIobTHEditor(a: ActionSetIobTH, tick: Int = 0, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    NumberInputRow(
        labelRef = AutomationStrings.autoisf_iobTH_percent,
        value = a.newIobTH.toDouble(),
        onValueChange = { a.newIobTH = it.toInt(); onChange() },
        valueRange = ActionSetIobTH.MIN.toDouble()..ActionSetIobTH.MAX.toDouble(),
        step = ActionSetIobTH.STEP.toDouble(),
        unitLabel = CoreUiStrings.units_percent,
        formatAsInt = true
    )
}

@Composable
fun ActionSetAutomationStateEditor(a: ActionSetAutomationState, tick: Int = 0, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    AutomationDropdown(
        value = a.stateName,
        options = a.stateNames(),
        onValueChange = {
            if (it != a.stateName) a.stateValue = ""
            a.stateName = it
            onChange()
        },
        label = stringResource(AutomationStrings.state_name_label)
    )
    AutomationDropdown(
        value = a.stateValue,
        options = a.stateValues(a.stateName),
        onValueChange = { a.stateValue = it; onChange() },
        label = stringResource(AutomationStrings.state_value_label),
        enabled = a.stateName.isNotEmpty()
    )
}
