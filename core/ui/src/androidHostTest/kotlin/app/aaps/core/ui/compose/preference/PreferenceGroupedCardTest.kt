package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.configuration.Config as AppConfig

/**
 * A long card in groups: heading rows in the card, under each heading its screens that open. One level of screens is
 * all the card draws, so the groups are rows and not screens. A screen that asks for it shows its own line under the
 * title; a screen that does not ask keeps its look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PreferenceGroupedCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val prefs: Preferences = mock()
    private val config: AppConfig = mock()
    private val profileUtil: ProfileUtil = mock()

    private fun render(block: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(
            LocalPreferences provides prefs,
            LocalConfig provides config,
            LocalProfileUtil provides profileUtil
        ) {
            PreviewTheme { block() }
        }
    }

    private val card = PreferenceSubScreenDef(
        key = "card",
        title = TextRef.Literal("Card"),
        items = listOf(
            PreferenceInfoDef(text = TextRef.Literal("All about meals"), title = TextRef.Literal("Meals"), groupHeading = true),
            PreferenceSubScreenDef(
                key = "amount",
                title = TextRef.Literal("Amount"),
                items = listOf(PreferenceInfoDef(text = TextRef.Literal("Inside amount"), title = TextRef.Literal("Curve"))),
                summary = TextRef.Literal("Curve and budget"),
                summaryInHeader = true
            ),
            PreferenceInfoDef(text = TextRef.Literal("All about corrections"), title = TextRef.Literal("Corrections"), groupHeading = true),
            PreferenceSubScreenDef(
                key = "smb",
                title = TextRef.Literal("Normal path"),
                items = listOf(PreferenceInfoDef(text = TextRef.Literal("Inside smb"), title = TextRef.Literal("Shares"))),
                summary = TextRef.Literal("A summary for another use")
            )
        )
    )

    private fun openCard(): PreferenceSectionState =
        PreferenceSectionState().also { it.toggle("card_main", SectionLevel.TOP_LEVEL) }

    @Test
    fun anOpenCardShowsItsGroupHeadingsAndTheScreensUnderThem() {
        val state = openCard()
        render { LazyColumn { addPreferenceContent(card, {}, state) } }

        compose.onNodeWithText("Meals").assertIsDisplayed()
        compose.onNodeWithText("All about meals").assertIsDisplayed()
        compose.onNodeWithText("Corrections").assertIsDisplayed()
        compose.onNodeWithText("All about corrections").assertIsDisplayed()
        compose.onNodeWithText("Amount").assertIsDisplayed()
        compose.onNodeWithText("Normal path").assertIsDisplayed()
    }

    @Test
    fun aScreenShowsItsOwnLineOnlyWhenItAsksForIt() {
        val state = openCard()
        render { LazyColumn { addPreferenceContent(card, {}, state) } }

        compose.onNodeWithText("Curve and budget").assertIsDisplayed()
        compose.onNodeWithText("A summary for another use").assertDoesNotExist()
    }

    @Test
    fun aScreenUnderAHeadingOpensAndShowsItsRows() {
        val state = openCard()
        render { LazyColumn { addPreferenceContent(card, {}, state) } }

        compose.onNodeWithText("Inside amount").assertDoesNotExist()
        compose.onNodeWithText("Amount").performClick()
        compose.onNodeWithText("Inside amount").assertIsDisplayed()
        // The own line belongs to the closed header; an open screen shows its rows instead.
        compose.onNodeWithText("Curve and budget").assertDoesNotExist()
    }
}
