package app.aaps.plugins.automation.states

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AutomationStatesModelTest {

    private val service = AutomationStateService(StatePreferences().preferences)

    @Test fun `states are listed by name with their values in the entered order`() {
        service.setStateValues("sleeping", listOf("true", "false"))
        service.setStateValues("Exercise", listOf("on", "off"))
        service.setState("Exercise", "off")

        assertThat(readAutomationStates(service)).containsExactly(
            AutomationStateUi("Exercise", "off", listOf("on", "off")),
            AutomationStateUi("sleeping", "", listOf("true", "false"))
        ).inOrder()
    }

    @Test fun `a value is added trimmed`() {
        assertThat(addValue(listOf("on"), "  off ")).isEqualTo(ValuesEdit.Changed(listOf("on", "off")))
    }

    @Test fun `an empty value is refused`() {
        assertThat(addValue(listOf("on"), "   ")).isEqualTo(ValuesEdit.MissingValue)
    }

    @Test fun `a value already in the list is refused`() {
        assertThat(addValue(listOf("on", "off"), "off ")).isEqualTo(ValuesEdit.ValueExists)
    }

    @Test fun `a value is removed by its place`() {
        assertThat(removeValue(listOf("on", "off", "auto"), 1)).isEqualTo(ValuesEdit.Changed(listOf("on", "auto")))
    }

    @Test fun `the last value stays`() {
        assertThat(removeValue(listOf("on"), 0)).isEqualTo(ValuesEdit.LastValue)
    }

    @Test fun `a new state starts with its first value`() {
        saveValues(service, "Sleeping", listOf("false", "true"), hadValue = false)

        assertThat(service.getState("Sleeping")).isEqualTo("false")
        assertThat(service.getStateValues("Sleeping")).containsExactly("false", "true").inOrder()
    }

    @Test fun `a state keeps its value while it is still allowed`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "false")

        saveValues(service, "Sleeping", listOf("true", "false", "nap"), hadValue = true)

        assertThat(service.getState("Sleeping")).isEqualTo("false")
    }

    @Test fun `a state whose value was removed has no value until one is picked`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "false")

        saveValues(service, "Sleeping", listOf("true"), hadValue = true)

        assertThat(service.getState("Sleeping")).isEmpty()
    }
}
