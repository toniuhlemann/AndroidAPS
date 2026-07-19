package app.aaps.plugins.automationstate.services

import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/** R7 Punkt 5: getStateSnapshot-Tests — missing / definiert-ohne-Wert / true / false /
 *  Writer-Interaktion. Snapshot liest Definition+Wert unter EINEM Lock (R6 F3). */
@ExtendWith(MockitoExtension::class)
class AutomationStateServiceTest {

    @Mock lateinit var sp: SP
    private lateinit var service: AutomationStateService

    @BeforeEach fun setup() {
        whenever(sp.getString(any<String>(), any())).thenReturn("{}")
        service = AutomationStateService(sp)
    }

    @Test fun missingStateIsUnknown() {
        val snap = service.getStateSnapshot("DYNMEAL_SHADOW")
        assertThat(snap.known).isFalse()
        assertThat(snap.value).isNull()
    }

    @Test fun definedWithoutValueIsUnknown() {
        service.setStateValues("DYNMEAL_SHADOW", listOf("true", "false"))
        val snap = service.getStateSnapshot("DYNMEAL_SHADOW")
        assertThat(snap.known).isFalse()
        assertThat(snap.value).isNull()
    }

    @Test fun trueAndFalseValuesAreKnown() {
        service.setStateValues("DYNMEAL_SHADOW", listOf("true", "false"))
        service.setState("DYNMEAL_SHADOW", "true")
        assertThat(service.getStateSnapshot("DYNMEAL_SHADOW"))
            .isEqualTo(app.aaps.core.interfaces.automation.AutomationStateSnapshot(true, "true"))
        service.setState("DYNMEAL_SHADOW", "false")
        assertThat(service.getStateSnapshot("DYNMEAL_SHADOW").value).isEqualTo("false")
    }

    @Test fun invalidValueIsRejectedAndSnapshotUnchanged() {
        service.setStateValues("DYNMEAL_SHADOW", listOf("true", "false"))
        service.setState("DYNMEAL_SHADOW", "true")
        val threw = runCatching { service.setState("DYNMEAL_SHADOW", "banana") }.isFailure
        assertThat(threw).isTrue()
        assertThat(service.getStateSnapshot("DYNMEAL_SHADOW").value).isEqualTo("true")
    }

    @Test fun writersInteractConsistentlyWithSnapshot() {
        service.setStateValues("MEAL_ACTIVE", listOf("true", "false"))
        service.setState("MEAL_ACTIVE", "true")
        // Definition ohne den aktuellen Wert neu setzen -> Wert wird geraeumt -> unknown
        service.setStateValues("MEAL_ACTIVE", listOf("yes", "no"))
        assertThat(service.getStateSnapshot("MEAL_ACTIVE").known).isFalse()
        // deleteState raeumt Definition UND Wert
        service.setStateValues("MEAL_ACTIVE", listOf("true", "false"))
        service.setState("MEAL_ACTIVE", "false")
        service.deleteState("MEAL_ACTIVE")
        val snap = service.getStateSnapshot("MEAL_ACTIVE")
        assertThat(snap.known).isFalse()
        assertThat(snap.value).isNull()
        // clearStates raeumt Werte, Definitionen bleiben -> definiert-ohne-Wert
        service.setStateValues("X", listOf("true", "false"))
        service.setState("X", "true")
        service.clearStates()
        assertThat(service.getStateSnapshot("X").known).isFalse()
    }
}
