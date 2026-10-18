package app.aaps.plugins.automation.states

import app.aaps.core.interfaces.automation.AutomationStateInterface

/** One state as the tab shows it: its name, the current value ("" when none is set) and the allowed values. */
internal data class AutomationStateUi(
    val name: String,
    val current: String,
    val values: List<String>
)

/** All states, sorted by name, each with its allowed values in the order the user entered them. */
internal fun readAutomationStates(automationState: AutomationStateInterface): List<AutomationStateUi> =
    automationState.getAllStates()
        .map { (name, current) -> AutomationStateUi(name, current, automationState.getStateValues(name)) }
        .sortedBy { it.name.lowercase() }

/** The result of one change to the list of allowed values while the dialog is open. */
internal sealed interface ValuesEdit {

    data class Changed(val values: List<String>) : ValuesEdit
    data object MissingValue : ValuesEdit
    data object ValueExists : ValuesEdit
    data object LastValue : ValuesEdit
}

/** Adds [text] to [values]. Trimmed like the service trims; empty text and a value already in the list are refused. */
internal fun addValue(values: List<String>, text: String): ValuesEdit {
    val value = text.trim()
    return when {
        value.isEmpty()  -> ValuesEdit.MissingValue
        value in values  -> ValuesEdit.ValueExists
        else             -> ValuesEdit.Changed(values + value)
    }
}

/** Removes the value at [index]. The last value stays, as in the fork: a state needs at least one. */
internal fun removeValue(values: List<String>, index: Int): ValuesEdit =
    if (values.size <= 1) ValuesEdit.LastValue
    else ValuesEdit.Changed(values.filterIndexed { i, _ -> i != index })

/**
 * Stores the allowed values of [name], as the fork's dialog did on OK.
 *
 * A state that had no value when the dialog opened (a new state, or one whose value was cleared) starts
 * with the first value. A state that had a value keeps it while it is still allowed; the service clears it
 * when it is not, and then the state stays without a value until one is picked.
 */
internal fun saveValues(automationState: AutomationStateInterface, name: String, values: List<String>, hadValue: Boolean) {
    automationState.setStateValues(name, values)
    if (!hadValue) automationState.setState(name, values.first())
}
