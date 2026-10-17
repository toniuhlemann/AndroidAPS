package app.aaps.plugins.automation.compose.triggers

import androidx.compose.runtime.Composable
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.plugins.automation.actions.ActionSetSmbDeliveryRatio
import app.aaps.plugins.automation.compose.elements.ComparatorExistsEditor
import app.aaps.plugins.automation.compose.elements.CompareRow
import app.aaps.plugins.automation.elements.InputBg
import app.aaps.plugins.automation.triggers.TriggerAutoIsfLeaseActive
import app.aaps.plugins.automation.triggers.TriggerBgRaw
import app.aaps.plugins.automation.triggers.TriggerBolusCoverage
import app.aaps.plugins.automation.triggers.TriggerCoverage
import app.aaps.plugins.automation.triggers.TriggerSmbDeliveryRatio

/*
 * Editors for Toni's fork triggers (IOB-Action viewer). In their own file, so the upstream
 * TriggerEditors.kt only carries one line per trigger.
 */

@Composable
fun TriggerSmbDeliveryRatioEditor(t: TriggerSmbDeliveryRatio, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    CompareRow(
        comparator = t.comparator.value,
        onComparatorChange = { t.comparator.value = it; onChange() },
        label = ""
    ) {
        NumberInputRow(
            labelRef = null,
            value = t.ratio,
            onValueChange = { t.ratio = it; onChange() },
            valueRange = ActionSetSmbDeliveryRatio.MIN..ActionSetSmbDeliveryRatio.MAX,
            step = ActionSetSmbDeliveryRatio.STEP,
            decimalPlaces = 2,
            compact = true
        )
    }
}

@Composable
fun TriggerAutoIsfLeaseActiveEditor(t: TriggerAutoIsfLeaseActive, onChange: () -> Unit) {
    ComparatorExistsEditor(t.comparator.value, onValueChange = { t.comparator.value = it; onChange() })
}

@Composable
fun TriggerBgRawEditor(t: TriggerBgRaw, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    val isMmol = t.bg.units == GlucoseUnit.MMOL
    CompareRow(
        comparator = t.comparator.value,
        onComparatorChange = { t.comparator.value = it; onChange() },
        label = ""
    ) {
        NumberInputRow(
            labelRef = null,
            value = t.bg.value,
            onValueChange = { t.bg.value = it; onChange() },
            valueRange = if (isMmol) InputBg.MMOL_MIN..InputBg.MMOL_MAX else InputBg.MGDL_MIN..InputBg.MGDL_MAX,
            step = if (isMmol) 0.1 else 1.0,
            decimalPlaces = if (isMmol) 1 else 0,
            unitLabel = if (isMmol) CoreUiStrings.units_mmol else CoreUiStrings.units_mgdl,
            compact = true
        )
    }
}

@Composable
fun TriggerCoverageEditor(t: TriggerCoverage, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    CompareRow(
        comparator = t.comparator.value,
        onComparatorChange = { t.comparator.value = it; onChange() },
        label = ""
    ) {
        CoveragePercentInput(t.coverage) { t.coverage = it; onChange() }
    }
}

@Composable
fun TriggerBolusCoverageEditor(t: TriggerBolusCoverage, onChange: () -> Unit, tick: Int = 0) {
    @Suppress("UNUSED_EXPRESSION") tick
    CompareRow(
        comparator = t.comparator.value,
        onComparatorChange = { t.comparator.value = it; onChange() },
        label = ""
    ) {
        CoveragePercentInput(t.coverage) { t.coverage = it; onChange() }
    }
}

@Composable
private fun CoveragePercentInput(value: Double, onValueChange: (Double) -> Unit) {
    NumberInputRow(
        labelRef = null,
        value = value,
        onValueChange = onValueChange,
        valueRange = TriggerCoverage.MIN..TriggerCoverage.MAX,
        step = TriggerCoverage.STEP,
        unitLabel = CoreUiStrings.units_percent,
        formatAsInt = true,
        compact = true
    )
}
