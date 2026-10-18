package app.aaps.plugins.automation.states

import app.aaps.core.interfaces.automation.AutomationStateSnapshot
import app.aaps.core.keys.StringNonKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AutomationStateServiceTest {

    private val prefs = StatePreferences()
    private val service = AutomationStateService(prefs.preferences)

    /** What a settings import does: the stored JSON is replaced, and nobody tells the service. */
    private fun importStates(values: String, current: String) {
        prefs.stored[StringNonKey.AutomationStateValues.key] = values
        prefs.stored[StringNonKey.AutomationCurrentStates.key] = current
    }

    @Test fun `a missing state is unknown`() {
        assertThat(service.getStateSnapshot("Sleeping")).isEqualTo(AutomationStateSnapshot(known = false, value = null))
    }

    @Test fun `a defined state without a value is unknown`() {
        service.setStateValues("Sleeping", listOf("true", "false"))

        assertThat(service.getStateSnapshot("Sleeping")).isEqualTo(AutomationStateSnapshot(known = false, value = null))
        assertThat(service.getAllStates()).containsExactly("Sleeping" to "")
    }

    @Test fun `a set value is known and stored`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "true")

        assertThat(service.getStateSnapshot("Sleeping")).isEqualTo(AutomationStateSnapshot(known = true, value = "true"))
        assertThat(service.inState("Sleeping", "true")).isTrue()
        assertThat(prefs.stored[StringNonKey.AutomationCurrentStates.key]).isEqualTo("""{"Sleeping":"true"}""")
    }

    @Test fun `a value that is not allowed is refused and the state keeps its value`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "true")

        assertThat(runCatching { service.setState("Sleeping", "maybe") }.isFailure).isTrue()
        assertThat(service.getState("Sleeping")).isEqualTo("true")
    }

    @Test fun `new allowed values without the current value clear it`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "true")

        service.setStateValues("Sleeping", listOf("yes", "no"))

        assertThat(service.getState("Sleeping")).isEmpty()
        assertThat(service.getStateValues("Sleeping")).containsExactly("yes", "no").inOrder()
    }

    @Test fun `delete removes the state and its values`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "true")

        service.deleteState("Sleeping")

        assertThat(service.hasStateValues("Sleeping")).isFalse()
        assertThat(service.getAllStates()).isEmpty()
        assertThat(service.getState("Sleeping")).isEmpty()
    }

    /** An import no longer restarts the app, so the states it brings must count at once. */
    @Test fun `states written by an import are seen at once`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "false")

        importStates(values = """{"Exercise":["on","off"]}""", current = """{"Exercise":"on"}""")

        assertThat(service.getAllStates()).containsExactly("Exercise" to "on")
        assertThat(service.inState("Exercise", "on")).isTrue()
        assertThat(service.hasStateValues("Sleeping")).isFalse()
    }

    /** The old copy in memory used to be written back over the imported states by the next change. */
    @Test fun `a change after an import keeps the imported states`() {
        service.setStateValues("Sleeping", listOf("true", "false"))
        service.setState("Sleeping", "false")
        importStates(values = """{"Exercise":["on","off"]}""", current = """{"Exercise":"on"}""")

        service.setStateValues("Meal", listOf("yes", "no"))
        service.setState("Meal", "yes")

        assertThat(service.getAllStates()).containsExactly("Exercise" to "on", "Meal" to "yes")
    }

    @Test fun `broken stored text reads as no states`() {
        importStates(values = "not json", current = "not json")

        assertThat(service.getAllStates()).isEmpty()
        assertThat(service.inState("Sleeping", "true")).isFalse()
    }
}
