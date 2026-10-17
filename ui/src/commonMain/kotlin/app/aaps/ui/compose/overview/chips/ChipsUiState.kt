package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable
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
 * Fork (Toni): insulinReq of the last loop run, the third chip beside IOB and COB.
 *
 * @property visible false where no loop runs here (a client): the chip is left out
 * @property text "Req 0.35 U", or "Req -" when the run did not calculate one - never 0 for "not calculated"
 * @property stale the last run is old, the chip is greyed out
 * @property details what a tap shows (for FUSE: the lines "Grenze" and "Block" of the FUSE tab's section "Menge")
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
