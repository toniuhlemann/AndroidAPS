package app.aaps.ui.compose.overview.graphs

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric test for [FuseLegendDialog] (Toni 25.11.2026): the legend of the FUSE layers starts with a switch for the
 * colour of the BG points - by FUSE state, or plainly by range - and explains the view that is on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FuseLegendDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<Boolean>()

    private fun show(pointsByState: Boolean) = compose.setContent {
        MaterialTheme {
            FuseLegendDialog(pointsByState = pointsByState, onPointsByState = { chosen += it }, onDismiss = {})
        }
    }

    @Test
    fun withTheStateColoursOnTheLegendNamesTheFuseStates() {
        show(pointsByState = true)

        compose.onNodeWithTag(FUSE_LEGEND_SWITCH_TAG).assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("Schutz-Null").assertIsDisplayed()
        compose.onNodeWithText("Langsamer Kanal").assertIsDisplayed()
        compose.onNodeWithText("im Zielbereich").assertDoesNotExist()
    }

    @Test
    fun withTheStateColoursOffTheLegendNamesTheThreeRanges() {
        show(pointsByState = false)

        compose.onNodeWithTag(FUSE_LEGEND_SWITCH_TAG).assertIsOff()
        compose.onNodeWithText("im Zielbereich").assertIsDisplayed()
        compose.onNodeWithText("darüber").assertIsDisplayed()
        compose.onNodeWithText("darunter").assertIsDisplayed()
        // The state rows are gone: nothing on the graph has these colours now.
        compose.onNodeWithText("Schutz-Null").assertDoesNotExist()
    }

    @Test
    fun aTapOnTheSwitchRowAsksForTheOtherView() {
        show(pointsByState = true)

        compose.onNodeWithTag(FUSE_LEGEND_SWITCH_TAG).performClick()

        assertThat(chosen).containsExactly(false)
    }

    /** The legend names the place of the main switch as the settings menu has it since the pages. */
    @Test
    fun theLegendNamesThePlaceOfTheMainSwitchAsTheMenuHasIt() {
        show(pointsByState = true)

        compose.onNodeWithText("Anzeige und Diagnose, Anzeige und Beobachter", substring = true).assertExists()
    }
}
