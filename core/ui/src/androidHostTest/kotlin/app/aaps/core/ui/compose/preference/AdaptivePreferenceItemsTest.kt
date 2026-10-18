package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.configuration.Config as AppConfig

/**
 * Robolectric render tests for the Adaptive*PreferenceItem composables (key-driven rows), mirroring
 * their previews. Locals are provided outside [PreviewTheme] (whose own ProvidePreferenceTheme reads
 * LocalPreferences before providing it — fine in IDE inspection mode, needs an outer provider in tests).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdaptivePreferenceItemsTest {

    @get:Rule
    val compose = createComposeRule()

    private val prefs: Preferences = mock()
    private val config: AppConfig = mock()

    private fun render(block: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalPreferences provides prefs, LocalConfig provides config) {
            PreviewTheme { block() }
        }
    }

    @Test
    fun switchItemRenders() {
        render { AdaptiveSwitchPreferenceItem(booleanKey = BooleanKey.OverviewKeepScreenOn) }
        compose.onRoot().assertIsDisplayed()
    }

    @Test
    fun stringItemRenders() {
        render { AdaptiveStringPreferenceItem(stringKey = StringKey.GeneralInstanceName) }
        compose.onRoot().assertIsDisplayed()
    }

    @Test
    fun intItemRenders() {
        render { AdaptiveIntPreferenceItem(intKey = IntKey.OverviewCarbsButtonIncrement1) }
        compose.onRoot().assertIsDisplayed()
    }

    @Test
    fun doubleItemRenders() {
        render { AdaptiveDoublePreferenceItem(doubleKey = DoubleKey.OverviewInsulinButtonIncrement1) }
        compose.onRoot().assertIsDisplayed()
    }

    @Test
    fun listIntItemRenders() {
        render {
            AdaptiveListIntPreferenceItem(
                intKey = IntKey.OverviewCarbsButtonIncrement1,
                entries = listOf("5g", "10g", "15g", "20g"),
                entryValues = listOf(5, 10, 15, 20)
            )
        }
        compose.onRoot().assertIsDisplayed()
    }

    @Test
    fun passwordItemRenders() {
        render {
            AdaptivePasswordPreferenceItem(
                stringKey = StringKey.ProtectionSettingsPassword,
                hashPassword = { it },
                onShowMessage = {}
            )
        }
        compose.onRoot().assertIsDisplayed()
    }

    // Fork (FUSE): a live line on the row instead of the summary, and plain info rows between the values

    @Test
    fun aRowSummaryIsShownOnTheRow() {
        render { AdaptiveDoublePreferenceItem(doubleKey = DoubleKey.OverviewInsulinButtonIncrement1, rowSummary = "what it does now") }
        compose.onNodeWithText("what it does now").assertIsDisplayed()
    }

    @Test
    fun aRowSummaryIsShownOnASwitchRow() {
        render { AdaptiveSwitchPreferenceItem(booleanKey = BooleanKey.OverviewKeepScreenOn, rowSummary = "on since today") }
        compose.onNodeWithText("on since today").assertIsDisplayed()
    }

    @Test
    fun anInfoRowShowsItsTextAndCannotBeClicked() {
        render { PreferenceInfoRow(PreferenceInfoDef(text = TextRef.Literal("The text"), title = TextRef.Literal("The heading"))) }
        compose.onNodeWithText("The heading").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("The text").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun aShortSummaryCutsALongTextAndATapShowsAllOfIt() {
        val long = List(40) { "word$it" }.joinToString(" ")
        render { ShortPreferenceSummary(text = long, title = "Heading") }

        compose.onNodeWithText(long).assertIsDisplayed().performClick()
        // Now the cut row and the dialog both hold the text
        compose.onAllNodesWithText(long).assertCountEquals(2)
        compose.onNodeWithText("Heading").assertIsDisplayed()
    }

    @Test
    fun aShortLiveLineOpensTheExplanation() {
        render { ShortPreferenceSummary(text = "what it does now", title = "Heading", explanation = "The whole explanation") }

        compose.onNodeWithText("The whole explanation").assertDoesNotExist()
        compose.onNodeWithText("what it does now").performClick()
        compose.onNodeWithText("The whole explanation").assertIsDisplayed()
    }

    @Test
    fun aShortTextThatFitsIsNotClickable() {
        render { ShortPreferenceSummary(text = "fits", title = "Heading") }

        compose.onNodeWithText("fits").assertHasNoClickAction()
    }

    @Test
    fun anInfoRowOnAShortSummaryScreenIsCut() {
        val long = List(40) { "word$it" }.joinToString(" ")
        render {
            CompositionLocalProvider(LocalShortPreferenceSummaries provides true) {
                PreferenceInfoRow(PreferenceInfoDef(text = TextRef.Literal(long), title = TextRef.Literal("The heading")))
            }
        }

        compose.onNodeWithText(long).performClick()
        compose.onAllNodesWithText(long).assertCountEquals(2)
    }

    @Test
    fun anInfoRowWithoutHeadingIsAParagraph() {
        render { PreferenceInfoRow(PreferenceInfoDef(text = TextRef.Literal("Only a paragraph"))) }
        compose.onNodeWithText("Only a paragraph").assertIsDisplayed().assertHasNoClickAction()
    }
}

