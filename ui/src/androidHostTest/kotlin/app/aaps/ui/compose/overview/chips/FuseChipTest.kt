package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric test for [FuseChip] (Toni 15.11.2026): what the closed and the open chip show, and what a tap, a long
 * press and a tap on a tile do. The tile texts are made up, so that each one can be found.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FuseChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val tiles = ChipTile.entries.map { tile ->
        FuseChipTileUi(tile = tile, label = "L-${tile.name}", value = "V-${tile.name}", sub = "S-${tile.name}", pinLabel = "P-${tile.name}", pinValue = "PV-${tile.name}")
    }

    private var toggled = 0
    private var opened = 0
    private val pinTaps = mutableListOf<ChipTile>()

    private fun state(
        expanded: Boolean = false,
        pinned: List<ChipTile> = listOf(ChipTile.DEMAND, ChipTile.GUARD),
        stale: Boolean = false,
        ageText: String = "",
        visible: Boolean = true
    ) = FuseChipUiState(
        visible = visible,
        cycleState = CycleState.FREE,
        stale = stale,
        ageText = ageText,
        expanded = expanded,
        tiles = tiles,
        pinned = pinned,
        pinCountText = "PINS"
    )

    private fun setChip(state: FuseChipUiState) {
        compose.setContent {
            MaterialTheme {
                FuseChip(
                    state = state,
                    onToggleExpanded = { toggled++ },
                    onTogglePin = { pinTaps += it },
                    onOpenFuse = { opened++ }
                )
            }
        }
    }

    @Test
    fun theClosedChipShowsTheStateAndThePinnedValuesButNoTiles() {
        setChip(state(expanded = false))

        compose.onNodeWithText("FUSE").assertIsDisplayed()
        compose.onNodeWithText("frei").assertIsDisplayed()
        compose.onNodeWithText("PV-DEMAND").assertIsDisplayed()
        compose.onNodeWithText("PV-GUARD").assertIsDisplayed()
        // Not pinned: not on the closed chip. And no tile is drawn.
        compose.onAllNodesWithText("PV-MEAL").assertCountEquals(0)
        compose.onAllNodesWithText("V-SMB").assertCountEquals(0)
        compose.onAllNodesWithText("PINS").assertCountEquals(0)
    }

    @Test
    fun theOpenChipShowsAllNineTiles() {
        setChip(state(expanded = true))

        ChipTile.entries.forEach { tile ->
            compose.onNodeWithTag(FUSE_CHIP_TILE_TAG + tile.name).assertExists()
            compose.onNodeWithText("V-${tile.name}").assertExists()
            compose.onNodeWithText("S-${tile.name}").assertExists()
        }
        compose.onNodeWithText("PINS").assertExists()
    }

    @Test
    fun aTapOpensOrClosesAndALongPressOpensTheFuseTab() {
        setChip(state(expanded = false))

        compose.onNodeWithTag(FUSE_CHIP_HEADER_TAG).performClick()
        assertThat(toggled).isEqualTo(1)
        assertThat(opened).isEqualTo(0)

        compose.onNodeWithTag(FUSE_CHIP_HEADER_TAG).performTouchInput { longClick() }
        assertThat(opened).isEqualTo(1)
        // A long press is no tap: the chip does not also open or close.
        assertThat(toggled).isEqualTo(1)
        assertThat(pinTaps).isEmpty()
    }

    @Test
    fun aTapOnATileAsksToPinThatTile() {
        setChip(state(expanded = true))

        compose.onNodeWithTag(FUSE_CHIP_TILE_TAG + ChipTile.SMB.name).performClick()
        compose.onNodeWithTag(FUSE_CHIP_TILE_TAG + ChipTile.DEMAND.name).performClick()

        assertThat(pinTaps).containsExactly(ChipTile.SMB, ChipTile.DEMAND).inOrder()
        assertThat(toggled).isEqualTo(0)
    }

    /** The whole chip answers a long press the same way: on a tile it opens the FUSE tab too, and pins nothing. */
    @Test
    fun aLongPressOnATileOpensTheFuseTabAndPinsNothing() {
        setChip(state(expanded = true))

        compose.onNodeWithTag(FUSE_CHIP_TILE_TAG + ChipTile.GUARD.name).performTouchInput { longClick() }

        assertThat(opened).isEqualTo(1)
        assertThat(pinTaps).isEmpty()
        assertThat(toggled).isEqualTo(0)
    }

    @Test
    fun anOldCycleShowsItsAgeInPlaceOfTheState() {
        setChip(state(stale = true, ageText = "vor 14 min"))

        compose.onNodeWithText("vor 14 min").assertIsDisplayed()
        compose.onAllNodesWithText("frei").assertCountEquals(0)
        // The pinned values stay readable.
        compose.onNodeWithText("PV-DEMAND").assertIsDisplayed()
    }

    @Test
    fun withoutFuseNothingIsDrawn() {
        setChip(state(visible = false))

        compose.onAllNodesWithTag(FUSE_CHIP_HEADER_TAG).assertCountEquals(0)
        compose.onAllNodesWithText("FUSE").assertCountEquals(0)
    }
}
