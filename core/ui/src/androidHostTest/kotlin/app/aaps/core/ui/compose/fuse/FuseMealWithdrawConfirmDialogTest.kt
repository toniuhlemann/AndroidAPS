package app.aaps.core.ui.compose.fuse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric test for [FuseMealWithdrawConfirmDialog] (Toni 27.11.2026): a running meal is only withdrawn after an
 * active "Zuruecknehmen". No answer - also after a long time - keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FuseMealWithdrawConfirmDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmed = 0
    private var kept = 0

    private fun show() = compose.setContent {
        MaterialTheme {
            FuseMealWithdrawConfirmDialog(onConfirm = { confirmed++ }, onKeep = { kept++ })
        }
    }

    @Test
    fun `without an answer nothing happens, also after a long time`() {
        show()
        // Longer than the old undo deadline: the question does not run out into a withdrawal.
        compose.mainClock.advanceTimeBy(60_000L)
        compose.waitForIdle()
        assertThat(confirmed).isEqualTo(0)
        assertThat(kept).isEqualTo(0)
    }

    @Test
    fun `only the button Zuruecknehmen withdraws the meal, and only once`() {
        show()
        compose.onNodeWithTag(FUSE_MEAL_WITHDRAW_YES_TAG).performClick()
        compose.onNodeWithTag(FUSE_MEAL_WITHDRAW_YES_TAG).performClick()
        assertThat(confirmed).isEqualTo(1)
        assertThat(kept).isEqualTo(0)
    }

    @Test
    fun `Mahlzeit behalten keeps the meal`() {
        show()
        compose.onNodeWithTag(FUSE_MEAL_WITHDRAW_NO_TAG).performClick()
        compose.onNodeWithTag(FUSE_MEAL_WITHDRAW_YES_TAG).performClick()
        assertThat(kept).isEqualTo(1)
        assertThat(confirmed).isEqualTo(0)
    }
}
