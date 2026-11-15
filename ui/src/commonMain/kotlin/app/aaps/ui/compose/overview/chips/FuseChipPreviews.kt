package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState

/** Example values only: none of them comes from a real day. */
private val previewTiles = listOf(
    FuseChipTileUi(ChipTile.DRIVE, label = "Störung r", value = "+1,20 /min", sub = "erwartet +0,90", pinLabel = "r", pinValue = "+1,20"),
    FuseChipTileUi(ChipTile.DEMAND, label = "Bedarf", value = "0,80 U"),
    FuseChipTileUi(ChipTile.RATIO, label = "Anteil", value = "0,25", sub = "Rampe 50 %"),
    FuseChipTileUi(ChipTile.SMB, label = "SMB", value = "0,20 U", sub = "Mahlzeitkonto"),
    FuseChipTileUi(ChipTile.LIMIT, label = "Grenze", value = "frei", sub = "Platz 2,30 U", tone = FuseChipTone.FREE),
    FuseChipTileUi(ChipTile.CHANNEL, label = "Kanal", value = "aus"),
    FuseChipTileUi(ChipTile.GUARD, label = "Guard", value = "+18 mg/dl", sub = "mit Mahlzeit +42", pinValue = "+18"),
    FuseChipTileUi(ChipTile.TAIL, label = "Schwanz", value = "+25 mg/dl", pinValue = "+25"),
    FuseChipTileUi(ChipTile.MEAL, label = "Mahlzeit", value = "3,20 U Rest", sub = "aktiv · 85 min · ×1,4", pinLabel = "Konto", pinValue = "3,20 U")
)

private val previewState = FuseChipUiState(
    visible = true,
    cycleState = CycleState.FREE,
    stale = false,
    tiles = previewTiles,
    pinned = listOf(ChipTile.DEMAND, ChipTile.GUARD, ChipTile.MEAL),
    pinCountText = "3 von 4 angeheftet"
)

@Preview(showBackground = true)
@Composable
internal fun FuseChipClosedPreview() {
    MaterialTheme {
        FuseChip(state = previewState, onToggleExpanded = {}, onTogglePin = {}, onOpenFuse = {})
    }
}

@Preview(showBackground = true)
@Composable
internal fun FuseChipOpenPreview() {
    MaterialTheme {
        FuseChip(state = previewState.copy(expanded = true), onToggleExpanded = {}, onTogglePin = {}, onOpenFuse = {})
    }
}

@Preview(showBackground = true)
@Composable
internal fun FuseChipStalePreview() {
    MaterialTheme {
        FuseChip(
            state = previewState.copy(stale = true, ageText = "vor 14 min", expanded = true),
            onToggleExpanded = {}, onTogglePin = {}, onOpenFuse = {}
        )
    }
}
