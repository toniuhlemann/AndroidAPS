package app.aaps.plugins.automation.compose.triggers

import androidx.compose.runtime.Composable
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.compose.elements.AutomationDropdown
import app.aaps.plugins.automation.compose.elements.CompareRow
import app.aaps.plugins.automation.triggers.TriggerAutomationState
import app.aaps.plugins.automation.triggers.TriggerBgAcceWeight
import app.aaps.plugins.automation.triggers.TriggerIobTH

/*
 * Editors for the autoISF 3.2.x automation triggers (Tobias/ga-zelle). In their own file, so the
 * upstream TriggerEditors.kt only carries one line per trigger.
 */

@Composable
fun TriggerIobTHEditor(t: TriggerIobTH, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    CompareRow(
        comparator = t.comparator.value,
        onComparatorChange = { t.comparator.value = it; onChange() },
        label = ""
    ) {
        NumberInputRow(
            labelRef = null,
            value = t.iobTHpercent.toDouble(),
            onValueChange = { t.iobTHpercent = it.toInt(); onChange() },
            valueRange = TriggerIobTH.MIN.toDouble()..TriggerIobTH.MAX.toDouble(),
            step = TriggerIobTH.STEP.toDouble(),
            unitLabel = CoreUiStrings.units_percent,
            formatAsInt = true,
            compact = true
        )
    }
}

@Composable
fun TriggerBgAcceWeightEditor(t: TriggerBgAcceWeight, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    CompareRow(
        comparator = t.comparator.value,
        onComparatorChange = { t.comparator.value = it; onChange() },
        label = ""
    ) {
        NumberInputRow(
            labelRef = null,
            value = t.acceWeight,
            onValueChange = { t.acceWeight = it; onChange() },
            valueRange = 0.0..1.0,
            step = 0.05,
            decimalPlaces = 2,
            compact = true
        )
    }
}

@Composable
fun TriggerAutomationStateEditor(t: TriggerAutomationState, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    AutomationDropdown(
        value = t.stateName,
        options = t.stateNames(),
        onValueChange = {
            if (it != t.stateName) t.stateValue = ""
            t.stateName = it
            onChange()
        },
        label = stringResource(AutomationStrings.state_name_label)
    )
    AutomationDropdown(
        value = t.stateValue,
        options = t.stateValues(t.stateName),
        onValueChange = { t.stateValue = it; onChange() },
        label = stringResource(AutomationStrings.state_value_label),
        enabled = t.stateName.isNotEmpty()
    )
}
