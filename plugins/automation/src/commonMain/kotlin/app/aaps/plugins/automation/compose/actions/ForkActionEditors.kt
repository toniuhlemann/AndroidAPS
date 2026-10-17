package app.aaps.plugins.automation.compose.actions

import androidx.compose.runtime.Composable
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.actions.ActionSetDuraWeight
import app.aaps.plugins.automation.actions.ActionSetPpWeight
import app.aaps.plugins.automation.actions.ActionSetSmbDeliveryRatio

/*
 * Editors for Toni's fork actions (IOB-Action viewer). In their own file, so the upstream
 * ActionEditors.kt only carries one line per action.
 */

@Composable
fun ActionSetPpWeightEditor(a: ActionSetPpWeight, tick: Int = 0, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    NumberInputRow(
        labelRef = AutomationStrings.autoisf_pp_weight,
        value = a.newWeight,
        onValueChange = { a.newWeight = it; onChange() },
        valueRange = ActionSetPpWeight.MIN..ActionSetPpWeight.MAX,
        step = ActionSetPpWeight.STEP,
        decimalPlaces = 2
    )
}

@Composable
fun ActionSetDuraWeightEditor(a: ActionSetDuraWeight, tick: Int = 0, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    NumberInputRow(
        labelRef = AutomationStrings.autoisf_dura_weight,
        value = a.newWeight,
        onValueChange = { a.newWeight = it; onChange() },
        valueRange = ActionSetDuraWeight.MIN..ActionSetDuraWeight.MAX,
        step = ActionSetDuraWeight.STEP,
        decimalPlaces = 2
    )
}

@Composable
fun ActionSetSmbDeliveryRatioEditor(a: ActionSetSmbDeliveryRatio, tick: Int = 0, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    NumberInputRow(
        labelRef = AutomationStrings.autoisf_smb_delivery_ratio,
        value = a.newRatio,
        onValueChange = { a.newRatio = it; onChange() },
        valueRange = ActionSetSmbDeliveryRatio.MIN..ActionSetSmbDeliveryRatio.MAX,
        step = ActionSetSmbDeliveryRatio.STEP,
        decimalPlaces = 2
    )
}
