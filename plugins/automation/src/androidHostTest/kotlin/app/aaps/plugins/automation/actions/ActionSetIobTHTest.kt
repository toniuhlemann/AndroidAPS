package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Fork (capability matrix A1): ActionSetIobTH ends a channel lease BEFORE it writes the base, and
 * writes nothing when that fails ("protection wins, provably").
 */
class ActionSetIobTHTest : ActionsTestBase() {

    private lateinit var sut: ActionSetIobTH

    @BeforeEach fun setUp() {
        sut = actionFactory.actionSetIobTH().also { it.newIobTH = 55 }
        whenever(preferences.get(IntKey.ApsAutoIsfIobThPercent)).thenReturn(100)
    }

    @Test fun failedInvalidationWritesNothing() = runTest {
        whenever(leaseInvalidator.invalidateBeforeExternalWrite(AutoIsfCapability.IOBTH, "ActionSetIobTH")).thenReturn(false)

        val result = sut.doAction()

        assertThat(result.success).isFalse()
        verify(preferences, never()).put(any<IntPreferenceKey>(), anyInt())
        verifyNoInteractions(uel)
    }

    @Test fun writesAfterInvalidation() = runTest {
        whenever(leaseInvalidator.invalidateBeforeExternalWrite(AutoIsfCapability.IOBTH, "ActionSetIobTH")).thenReturn(true)

        val result = sut.doAction()

        assertThat(result.success).isTrue()
        verify(preferences).put(IntKey.ApsAutoIsfIobThPercent, 55)
    }

    @Test fun sameValueIsNoWrite() = runTest {
        whenever(leaseInvalidator.invalidateBeforeExternalWrite(AutoIsfCapability.IOBTH, "ActionSetIobTH")).thenReturn(true)
        sut.newIobTH = 100

        val result = sut.doAction()

        assertThat(result.success).isFalse()
        verify(preferences, never()).put(any<IntPreferenceKey>(), anyInt())
    }
}
