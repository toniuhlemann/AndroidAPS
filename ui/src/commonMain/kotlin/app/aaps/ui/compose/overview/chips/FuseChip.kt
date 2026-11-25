package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.graphs.fuseStateColor

/** The tiles of the dosing chain come first; the rest is protection and meal. */
private const val CHAIN_TILES = 6

/** Tiles in one row. */
private const val TILES_PER_ROW = 3

/** The small line under the value of a tile may take this many lines before it is cut. */
private const val SUB_LINES = 2

/** How far a state colour is darkened on a light background, so that the word stays readable. */
private const val LIGHT_THEME_DARKEN = 0.45f

/** Test tags, so a test finds the parts without knowing their texts. */
internal const val FUSE_CHIP_HEADER_TAG = "fuseChipHeader"
internal const val FUSE_CHIP_TILE_TAG = "fuseChipTile"

/**
 * Fork (Toni 15.11.2026): the FUSE chip - one full-width chip under the sensitivity chip.
 *
 * Closed it shows the state of the last FUSE cycle in the colour the BG points have in the main graph, and the pinned
 * values. A tap opens and closes the tiles: the dosing chain (disturbance, demand, share, SMB, limit, channel), then
 * protection and meal (guard, tail, meal). A tap on a tile pins it to the closed chip or takes it off again; a long
 * press anywhere on the chip opens the FUSE tab. Greyed out, with the age, when the last cycle is old. Display only.
 *
 * @see FuseChipClosedPreview
 * @see FuseChipOpenPreview
 * @see FuseChipStalePreview
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun FuseChip(
    state: FuseChipUiState,
    onToggleExpanded: () -> Unit,
    onTogglePin: (ChipTile) -> Unit,
    onOpenFuse: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.visible) return
    val haptic = LocalHapticFeedback.current
    val stateColor = if (state.stale || state.cycleState == null) MaterialTheme.colorScheme.outline else readable(fuseStateColor(state.cycleState))
    val stateWord = fuseChipStateText(state.cycleState)
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AapsSpacing.chipHeight)
                    .testTag(FUSE_CHIP_HEADER_TAG)
                    // The row is one button. A screen reader hears its texts in order - the name, the state or the
                    // age, then each pinned value - so it needs no description of its own, which would hide them.
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = stringResource(if (state.expanded) CoreUiStrings.fuse_chip_collapse else CoreUiStrings.fuse_chip_expand),
                        onClick = onToggleExpanded,
                        onLongClickLabel = stringResource(CoreUiStrings.fuse_chip_open_tab),
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onOpenFuse() }
                    )
                    .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    // Decorative: the texts beside it name the chip and carry its state.
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
                Text(
                    text = stringResource(CoreUiStrings.fuse_chip_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (state.stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = AapsSpacing.small)
                )
                Text(
                    // An old cycle names its age in place of a state that may no longer hold.
                    text = state.ageText.ifEmpty { stateWord },
                    style = MaterialTheme.typography.bodyMedium,
                    color = stateColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = AapsSpacing.medium)
                )
                // The pinned values keep to the end of the row, and go to a second line where the row is too narrow.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AapsSpacing.medium)
                ) {
                    state.pinnedTiles.forEach { tile -> PinnedValue(tile = tile, stale = state.stale) }
                }
                Icon(
                    imageVector = if (state.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AapsSpacing.extraLarge)
                )
            }
            if (state.expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                    modifier = Modifier.padding(start = AapsSpacing.medium, end = AapsSpacing.medium, bottom = AapsSpacing.medium)
                ) {
                    TileSection(
                        title = stringResource(CoreUiStrings.fuse_chip_section_chain),
                        tiles = state.tiles.take(CHAIN_TILES),
                        state = state,
                        onTogglePin = onTogglePin,
                        onOpenFuse = onOpenFuse
                    )
                    TileSection(
                        title = stringResource(CoreUiStrings.fuse_chip_section_safety),
                        tiles = state.tiles.drop(CHAIN_TILES),
                        state = state,
                        onTogglePin = onTogglePin,
                        onOpenFuse = onOpenFuse
                    )
                    Text(
                        text = state.pinCountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** One pinned value on the closed chip: its short name and its value. */
@Composable
private fun PinnedValue(tile: FuseChipTileUi, stale: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = tile.pinLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = tile.pinValue,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = toneColor(tile.tone, stale),
            maxLines = 1,
            modifier = Modifier.padding(start = AapsSpacing.small)
        )
    }
}

/** A title and its tiles, three in a row. Tiles of one row are equally high. */
@Composable
private fun TileSection(
    title: String,
    tiles: List<FuseChipTileUi>,
    state: FuseChipUiState,
    onTogglePin: (ChipTile) -> Unit,
    onOpenFuse: () -> Unit
) {
    if (tiles.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    tiles.chunked(TILES_PER_ROW).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            row.forEach { tile ->
                Tile(
                    tile = tile,
                    pinned = tile.tile in state.pinned,
                    stale = state.stale,
                    onClick = { onTogglePin(tile.tile) },
                    onLongClick = onOpenFuse,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
            // A row that is not full keeps the width of its tiles.
            repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * One tile: name, value, and a small line under it. A tap pins it to the closed chip or takes it off. A long press
 * opens the FUSE tab, as it does on the row above: the whole chip answers a long press the same way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    tile: FuseChipTileUi,
    pinned: Boolean,
    stale: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val pinnedText = stringResource(CoreUiStrings.fuse_chip_pinned)
    val muted = if (stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(AapsSpacing.chipCornerRadius)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .testTag(FUSE_CHIP_TILE_TAG + tile.tile.name)
            // Clipped first, so the press shows inside the rounded tile.
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(if (pinned) CoreUiStrings.fuse_chip_unpin else CoreUiStrings.fuse_chip_pin),
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
                onLongClickLabel = stringResource(CoreUiStrings.fuse_chip_open_tab),
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }
            )
            .semantics { if (pinned) stateDescription = pinnedText }
    ) {
        Column(modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tile.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (pinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        // Decorative: the tile says "pinned" as its state.
                        contentDescription = null,
                        tint = if (stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AapsSpacing.large)
                    )
                }
            }
            Text(
                text = tile.value,
                style = MaterialTheme.typography.titleSmall,
                color = toneColor(tile.tone, stale),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tile.sub.isNotEmpty()) {
                Text(
                    text = tile.sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    // Two lines: the meal tile carries state, minutes and scaler, and one line cut the scaler off
                    // (Toni 25.11.2026). The tiles of a row stay equally high.
                    maxLines = SUB_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The state of the last cycle in one short word; without a cycle yet it says so. */
@Composable
private fun fuseChipStateText(state: CycleState?): String = stringResource(
    when (state) {
        CycleState.FREE    -> CoreUiStrings.fuse_chip_state_free
        CycleState.GUARD   -> CoreUiStrings.fuse_chip_state_guard
        CycleState.BLOCKED -> CoreUiStrings.fuse_chip_state_blocked
        CycleState.ZERO    -> CoreUiStrings.fuse_chip_state_zero
        CycleState.CHANNEL -> CoreUiStrings.fuse_chip_state_channel
        CycleState.UNKNOWN -> CoreUiStrings.fuse_chip_state_unknown
        null               -> CoreUiStrings.fuse_chip_no_cycle
    }
)

/** The colour of a value: the state colours of the main graph for free, guard and blocked; an old cycle is muted. */
@Composable
private fun toneColor(tone: FuseChipTone, stale: Boolean): Color = when {
    stale                        -> MaterialTheme.colorScheme.outline
    tone == FuseChipTone.FREE    -> readable(fuseStateColor(CycleState.FREE))
    tone == FuseChipTone.GUARD   -> readable(fuseStateColor(CycleState.GUARD))
    tone == FuseChipTone.BLOCKED -> readable(fuseStateColor(CycleState.BLOCKED))
    else                         -> MaterialTheme.colorScheme.onSurface
}

/**
 * The state colours are made for points on the dark graph. As a word on a light background amber and green are too
 * pale to read, so there they are darkened; on a dark background they stay as they are.
 */
@Composable
private fun readable(color: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) lerp(color, Color.Black, LIGHT_THEME_DARKEN) else color
