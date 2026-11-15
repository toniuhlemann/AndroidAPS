package app.aaps.ui.compose.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.chips.FuseChip

/**
 * Fork (Toni 15.11.2026): the FUSE chip of the main screen, wired to its view model. It sits under the row with the BG
 * value and the chips, over the whole width, in all three layouts. Without FUSE as the active APS it draws nothing and
 * takes no room.
 *
 * A long press opens the FUSE tab: the plugin is found by its class name, as the plugin list does it.
 */
@Composable
internal fun FuseChipBlock(
    chipsViewModel: ChipsViewModel,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by chipsViewModel.fuseChipUiState.collectAsStateWithLifecycle()
    FuseChip(
        state = state,
        onToggleExpanded = chipsViewModel::toggleFuseChipExpanded,
        onTogglePin = chipsViewModel::toggleFuseChipPin,
        onOpenFuse = { onNavigate(NavigationRequest.Plugin(state.pluginClassName)) },
        modifier = modifier
    )
}
