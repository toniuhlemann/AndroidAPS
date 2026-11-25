package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.configuration.Config as AppConfig

/**
 * A preference screen as a page of its own: the top bar says where you are and how you got there, sub-screens are
 * rows that open the next page, values stand in groups with their name left and their value right, and the search of
 * a start page leads to the page of a value. A card of sections that unfold is the fallback where no page can be
 * opened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PreferencePageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val prefs: Preferences = mock()
    private val config: AppConfig = mock()
    private val profileUtil: ProfileUtil = mock()

    /** Every page that a row or a search hit asked for: the key of the screen and the value to bring into view. */
    private val opened = mutableListOf<Pair<String, String?>>()

    private fun render(navigator: Boolean = true, block: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(
            LocalPreferences provides prefs,
            LocalConfig provides config,
            LocalProfileUtil provides profileUtil
        ) {
            PreviewTheme {
                CompositionLocalProvider(
                    LocalPreferenceNavigator provides if (navigator) { key, highlight -> opened += key to highlight } else null
                ) { block() }
            }
        }
    }

    /** Existing keys with a name of their own, without the switch they depend on and without simple mode. */
    private val scale = object : DoublePreferenceKey by DoubleKey.OverviewInsulinButtonIncrement1 {
        override val key = "test_scale_breakfast"
        override val title: TextRef = TextRef.Literal("Scale breakfast")
        override val summary: TextRef? = TextRef.Literal("Multiplies the expected meal. Formerly: stable start")
        override val dependency: BooleanPreferenceKey? = null
        override val defaultedBySM = false
    }
    private val weight = object : DoublePreferenceKey by DoubleKey.OverviewInsulinButtonIncrement1 {
        override val key = "test_curve_weight"
        override val title: TextRef = TextRef.Literal("Curve weight")
        override val summary: TextRef? = null
        override val dependency: BooleanPreferenceKey? = null
        override val defaultedBySM = false
    }
    private val flag = object : BooleanPreferenceKey by BooleanKey.OverviewKeepScreenOn {
        override val key = "test_early_supply"
        override val title: TextRef = TextRef.Literal("Meal supply")
        override val summary: TextRef? = null
    }

    private fun page(key: String, title: String, summary: String? = null, vararg items: PreferenceItem) =
        PreferenceSubScreenDef(
            key = key,
            title = TextRef.Literal(title),
            items = items.toList(),
            summary = summary?.let { TextRef.Literal(it) },
            pages = true
        )

    private val amount = page(
        "amount", "Amount", "Curve and budget",
        PreferenceInfoDef(text = TextRef.Literal("How much a meal is expected to be"), title = TextRef.Literal("Curve")),
        weight,
        PreferenceInfoDef(text = TextRef.Literal(""), title = TextRef.Literal("Other")),
        flag
    )
    private val advanced = page("advanced", "Advanced", "Seldom needed", scale)
    private val meals = page(
        "meals", "Meals", "Amount and flow",
        PreferenceInfoDef(text = TextRef.Literal("All from the button to the end of the meal")),
        amount,
        PreferenceInfoDef(text = TextRef.Literal(""), title = TextRef.Literal("More settings")),
        advanced
    )
    private val start = page("start", "Controller", null, meals)

    // ---- the page -------------------------------------------------------------------------------------------------

    @Test
    fun aPageShowsItsTitleAndTheWayToIt() {
        render { PreferencePageScreen(screen = amount, parents = listOf(TextRef.Literal("Controller"), TextRef.Literal("Meals")), onBackClick = {}) }

        compose.onNodeWithText("Amount").assertIsDisplayed()
        compose.onNodeWithText("Controller › Meals").assertIsDisplayed()
    }

    @Test
    fun aPageWithoutParentsShowsNoWay() {
        render { PreferencePageScreen(screen = start, onBackClick = {}) }

        compose.onNodeWithText("Controller").assertIsDisplayed()
        compose.onNodeWithText("›", substring = true).assertDoesNotExist()
    }

    @Test
    fun backIsPassedOn() {
        var back = 0
        render { PreferencePageScreen(screen = amount, onBackClick = { back++ }) }

        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(back).isEqualTo(1)
    }

    @Test
    fun aSubScreenIsARowWithItsLineAndATapOpensItsPage() {
        render { PreferencePageScreen(screen = meals, onBackClick = {}) }

        compose.onNodeWithText("Curve and budget").assertIsDisplayed()
        compose.onNodeWithText("Seldom needed").assertIsDisplayed()
        compose.onNodeWithText("More settings").assertIsDisplayed()
        // The rows of the sub-screen are not on this page - that is the point of a page.
        compose.onNodeWithText("Curve weight").assertDoesNotExist()

        compose.onNodeWithText("Amount").performClick()
        assertThat(opened).containsExactly("amount" to null)
    }

    @Test
    fun withoutAWayToOpenAPageTheRowIsDrawnButCannotBeTapped() {
        render(navigator = false) { PreferencePageScreen(screen = meals, onBackClick = {}) }

        compose.onNodeWithText("Amount").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun theHeadingsOfAPageAreHeadingsAndItsValuesStandBelowThem() {
        render { PreferencePageScreen(screen = amount, onBackClick = {}) }

        compose.onNodeWithText("Curve").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("How much a meal is expected to be").assertIsDisplayed()
        compose.onNodeWithText("Other").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Curve weight").assertIsDisplayed()
        compose.onNodeWithText("Meal supply").assertIsDisplayed()
    }

    @Test
    fun theParagraphOfAPageIsShown() {
        render { PreferencePageScreen(screen = meals, onBackClick = {}) }

        compose.onNodeWithText("All from the button to the end of the meal").assertIsDisplayed()
    }

    // ---- groups ---------------------------------------------------------------------------------------------------

    @Test
    fun aTitledInfoRowOpensAGroupAndWhatStandsBeforeTheFirstIsAGroupWithoutHeading() {
        val groups = pageGroups(meals.items)

        assertThat(groups.map { (it.heading?.title as? TextRef.Literal)?.text }).containsExactly(null, "More settings").inOrder()
        assertThat(groups[0].items).hasSize(2)          // the paragraph and the first sub-screen
        assertThat(groups[1].items).containsExactly(advanced)
    }

    @Test
    fun anEmptyPageHasNoGroupAndAHeadingAloneIsAGroup() {
        assertThat(pageGroups(emptyList())).isEmpty()
        val alone = pageGroups(listOf(PreferenceInfoDef(text = TextRef.Literal("see the other page"), title = TextRef.Literal("Night"))))
        assertThat(alone).hasSize(1)
        assertThat(alone.single().items).isEmpty()
    }

    // ---- compact value rows ---------------------------------------------------------------------------------------

    @Test
    fun onAPageAValueIsOneRowAndATapOnItsNameOpensItsInput() {
        render { PreferencePageScreen(screen = advanced, onBackClick = {}) }

        // The whole row opens the input, not only the value at its end.
        compose.onNodeWithText("Scale breakfast").assertIsDisplayed().assertHasClickAction()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Scale breakfast").performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun aValueThatIsHiddenJustNowIsNoRowAndItsNeighboursStay() {
        // The switch it depends on is off (the mocked preferences answer false). The row draws nothing; the rows
        // around it are laid out as if it were not there, with no line left behind for it.
        val hidden = object : DoublePreferenceKey by DoubleKey.OverviewInsulinButtonIncrement1 {
            override val key = "test_hidden"
            override val title: TextRef = TextRef.Literal("Hidden value")
            override val summary: TextRef? = null
            override val dependency: BooleanPreferenceKey? = BooleanKey.OverviewKeepScreenOn
            override val defaultedBySM = false
        }
        render { PreferencePageScreen(screen = page("some", "Some", null, weight, hidden, flag), onBackClick = {}) }

        compose.onNodeWithText("Curve weight").assertIsDisplayed()
        compose.onNodeWithText("Meal supply").assertIsDisplayed()
        compose.onNodeWithText("Hidden value").assertDoesNotExist()
    }

    @Test
    fun outsideAPageAValueKeepsItsUsualRow() {
        // In a card that unfolds the name of a value is a text above its control, and only the control can be tapped.
        render { AdaptiveDoublePreferenceItem(doubleKey = scale) }

        compose.onNodeWithText("Scale breakfast").assertIsDisplayed().assertHasNoClickAction()
    }

    // ---- search ---------------------------------------------------------------------------------------------------

    @Test
    fun theSearchKnowsEveryPageAndEveryValueBelowThePageWithThePageATapOpens() {
        val entries = pageSearchEntries(start)

        assertThat(entries.map { (it.title as TextRef.Literal).text })
            .containsExactly("Meals", "Amount", "Curve weight", "Meal supply", "Advanced", "Scale breakfast").inOrder()
        assertThat(entries.map { it.screenKey }).containsExactly("meals", "amount", "amount", "amount", "advanced", "advanced").inOrder()
        // A hit that is a page opens that page; a value opens the page it stands on and is brought into view there.
        assertThat(entries.map { it.highlightKey }).containsExactly(null, null, weight.key, flag.key, null, scale.key).inOrder()
        // The way of a value names its own page; the way of a page ends above it.
        assertThat(entries[0].way).isEmpty()
        assertThat(entries[1].way.map { (it as TextRef.Literal).text }).containsExactly("Meals")
        assertThat(entries[2].way.map { (it as TextRef.Literal).text }).containsExactly("Meals", "Amount").inOrder()
    }

    @Test
    fun aSearchAlsoFindsAPageByItsLineAndATapOpensIt() {
        // A page can have no value that carries the searched word (think of a maintenance page), so the page
        // itself has to be a hit.
        render { PreferencePageScreen(screen = start, showSearch = true, onBackClick = {}) }

        compose.onNode(hasSetTextAction()).performTextInput("seldom")

        compose.onNodeWithText("Advanced").assertIsDisplayed().performClick()
        assertThat(opened).containsExactly("advanced" to null)
    }

    /** More groups than fit on the screen, and one value at the very end. */
    private val long = page(
        "long", "Long", null,
        *(1..30).map { PreferenceInfoDef(text = TextRef.Literal("Text $it"), title = TextRef.Literal("Group $it")) }.toTypedArray(),
        PreferenceInfoDef(text = TextRef.Literal(""), title = TextRef.Literal("Last")),
        scale
    )

    @Test
    fun theEndOfALongPageIsNotDrawnUntilItComesIntoView() {
        render { PreferencePageScreen(screen = long, onBackClick = {}) }

        compose.onNodeWithText("Group 1").assertIsDisplayed()
        compose.onNodeWithText("Scale breakfast").assertDoesNotExist()
    }

    @Test
    fun aValueFarDownThePageIsBroughtIntoViewWhenASearchLedToIt() {
        render {
            CompositionLocalProvider(LocalHighlightKey provides scale.key) { PreferencePageScreen(screen = long, onBackClick = {}) }
        }

        compose.onNodeWithText("Scale breakfast").assertIsDisplayed()
    }

    @Test
    fun aSearchShowsItsHitsInPlaceOfTheRowsAndATapOpensThePageOfTheValue() {
        render { PreferencePageScreen(screen = start, showSearch = true, onBackClick = {}) }

        compose.onNodeWithText("Meals").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("breakf")

        compose.onNodeWithText("Amount and flow").assertDoesNotExist()
        compose.onNodeWithText("Scale breakfast").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Meals › Advanced").assertIsDisplayed()
        compose.onNodeWithText("Scale breakfast").performClick()
        assertThat(opened).containsExactly("advanced" to scale.key)
    }

    @Test
    fun aValueIsAlsoFoundByAWordOfItsExplanation() {
        // A renamed value gives its old name in its explanation, so the old name still leads to it.
        render { PreferencePageScreen(screen = start, showSearch = true, onBackClick = {}) }

        compose.onNode(hasSetTextAction()).performTextInput("stable start")

        compose.onNodeWithText("Scale breakfast").assertIsDisplayed()
        compose.onNodeWithText("Curve weight").assertDoesNotExist()
    }

    @Test
    fun aSearchWithoutAHitSaysSo() {
        render { PreferencePageScreen(screen = start, showSearch = true, onBackClick = {}) }

        compose.onNode(hasSetTextAction()).performTextInput("zzzzqq")

        compose.onNodeWithText("No results found").assertIsDisplayed()
    }

    @Test
    fun aPageWithoutSearchHasNoSearchField() {
        render { PreferencePageScreen(screen = meals, onBackClick = {}) }

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    // ---- inside a card (the list of all settings) -----------------------------------------------------------------

    @Test
    fun inACardAScreenOfPagesShowsItsSubScreensAsRowsThatOpenTheirPage() {
        val state = PreferenceSectionState().also { it.toggle("start_main", SectionLevel.TOP_LEVEL) }
        render { LazyColumn { addPreferenceContent(start, {}, state) } }

        compose.onNodeWithText("Amount and flow").assertIsDisplayed()
        compose.onNodeWithText("Meals").performClick()
        assertThat(opened).containsExactly("meals" to null)
    }

    @Test
    fun inACardWithoutAWayToOpenAPageTheSubScreenUnfoldsAsBefore() {
        val state = PreferenceSectionState().also { it.toggle("meals_main", SectionLevel.TOP_LEVEL) }
        render(navigator = false) { LazyColumn { addPreferenceContent(meals, {}, state) } }

        compose.onNodeWithText("Curve weight").assertDoesNotExist()
        compose.onNodeWithText("Amount").performClick()
        compose.onNodeWithText("Curve weight").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }
}
