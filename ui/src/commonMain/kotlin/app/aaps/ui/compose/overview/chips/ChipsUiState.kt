package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.IsfStep

/**
 * What the overview chips show.
 *
 * Kept in their own file rather than beside `ChipsViewModel`: these are plain values with no Android
 * in them, and every composable and preview that reads them can then be shared code.
 */
@Immutable
data class IobUiState(
    val text: String = "",
    val iobTotal: Double = 0.0
)

/**
 * Fork (Toni): insulinReq of the last loop run, the third chip beside IOB and COB. Not shown with FUSE as the active
 * APS: there the demand is a tile of the FUSE chip ([FuseChipUiState]).
 *
 * @property visible false where no loop runs here (a client) and with FUSE: the chip is left out
 * @property text "Req 0.35 U", or "Req -" when the run did not calculate one - never 0 for "not calculated"
 * @property stale the last run is old, the chip is greyed out
 * @property details what a tap shows
 */
@Immutable
data class InsulinReqUiState(
    val visible: Boolean = false,
    val text: String = "",
    val stale: Boolean = true,
    val details: String = ""
)

@Immutable
data class CobUiState(
    val text: String = "",
    val carbsReq: Int = 0,
    val cobValue: Double = 0.0
)

@Immutable
data class SensitivityUiState(
    val asText: String = "",
    val isfFrom: String = "",
    val isfTo: String = "",
    val isfStep: IsfStep = IsfStep.SAME,
    val dialogText: String = "",
    val ratio: Double = 1.0,
    val isEnabled: Boolean = true,
    val hasData: Boolean = false
)

/** What the value of a FUSE chip tile says, for its colour: nothing special, free, held by the guard, or blocked. */
enum class FuseChipTone { NEUTRAL, FREE, GUARD, BLOCKED }

/**
 * Fork (Toni 15.11.2026): one tile of the FUSE chip, with its texts ready to draw.
 *
 * @property label the name on the tile ("Bedarf")
 * @property value the big value ("0,80 U"), a dash when the cycle has none
 * @property sub the small line under the value ("Rampe 50 %"), empty when there is none
 * @property pinLabel the name on the closed chip when the tile is pinned - shorter where the tile name is long
 * @property pinValue the value on the closed chip, without the parts that only fit on the tile
 */
@Immutable
data class FuseChipTileUi(
    val tile: FuseOverviewSource.ChipTile,
    val label: String,
    val value: String,
    val sub: String = "",
    val pinLabel: String = label,
    val pinValue: String = value,
    val tone: FuseChipTone = FuseChipTone.NEUTRAL
)

/**
 * Fork (Toni 15.11.2026): the FUSE chip under the sensitivity chip. Closed it shows the state of the last FUSE cycle
 * and the pinned values; open it shows all tiles in the order of the dosing chain. Display only.
 *
 * @property visible false without FUSE as the active APS, and on a client: the chip is left out
 * @property cycleState what FUSE did in the last cycle; `null` = no cycle in this process yet
 * @property stale the last cycle is old (or there is none): the chip is greyed out
 * @property ageText "vor 14 min" when the last cycle is old, else empty
 * @property expanded the tiles are shown
 * @property tiles all tiles, in the order they are shown
 * @property pinned the tiles on the closed chip, in the order they were pinned
 * @property pinCountText "3 von 4 angeheftet"
 * @property pluginClassName the class name of the FUSE plugin, for the jump into its tab
 */
@Immutable
data class FuseChipUiState(
    val visible: Boolean = false,
    val cycleState: FuseOverviewSource.CycleState? = null,
    val stale: Boolean = true,
    val ageText: String = "",
    val expanded: Boolean = false,
    val tiles: List<FuseChipTileUi> = emptyList(),
    val pinned: List<FuseOverviewSource.ChipTile> = emptyList(),
    val pinCountText: String = "",
    val pluginClassName: String = ""
) {

    /** The pinned tiles with their texts, in the order they were pinned. */
    val pinnedTiles: List<FuseChipTileUi> get() = pinned.mapNotNull { pin -> tiles.firstOrNull { it.tile == pin } }
}
