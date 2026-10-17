package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import app.aaps.core.ui.compose.AapsSpacing

/**
 * IOB, COB and - fork (Toni) - insulinReq of the last loop run, when [insulinReqUiState] is visible.
 *
 * @see IobCobChipsRowPreview
 * @see IobCobChipsRowCarbsReqPreview
 */
@Composable
fun IobCobChipsRow(
    iobUiState: IobUiState,
    cobUiState: CobUiState,
    onIobChipClick: () -> Unit,
    modifier: Modifier = Modifier,
    insulinReqUiState: InsulinReqUiState = InsulinReqUiState(),
    onInsulinReqChipClick: () -> Unit = {}
) {
    val spacingDp = AapsSpacing.small
    val withReq = insulinReqUiState.visible
    SubcomposeLayout(
        modifier = modifier.fillMaxWidth()
    ) { constraints ->
        val spacingPx = spacingDp.roundToPx()
        val gaps = if (withReq) 2 else 1
        val isWidthBounded = constraints.hasBoundedWidth
        val availableWidth = if (isWidthBounded) (constraints.maxWidth - gaps * spacingPx).coerceAtLeast(0) else 0

        // First pass: measure intrinsic widths with icons
        val withIcons = subcompose("withIcons") {
            IobChip(state = iobUiState, onClick = onIobChipClick, showIcon = true)
            CobChip(state = cobUiState, showIcon = true)
            if (withReq) InsulinReqChip(state = insulinReqUiState, onClick = onInsulinReqChipClick)
        }
        val intrinsicsWithIcons = withIcons.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val totalWithIcons = intrinsicsWithIcons.sum()

        // If chips with icons don't fit, hide icons to free up space. Not while carbs are required:
        // the blinking COB icon is the cue, and the "16req" text is what made the row too narrow in
        // the first place, so the cue would vanish exactly when it is needed. The COB text scrolls
        // instead (see CobChip).
        val showIcons = cobUiState.carbsReq > 0 || !isWidthBounded || totalWithIcons <= availableWidth

        val measurables = if (showIcons) {
            withIcons
        } else {
            subcompose("withoutIcons") {
                IobChip(state = iobUiState, onClick = onIobChipClick, showIcon = false)
                CobChip(state = cobUiState, showIcon = false)
                if (withReq) InsulinReqChip(state = insulinReqUiState, onClick = onInsulinReqChipClick)
            }
        }

        // Max intrinsic width = the width each chip needs to render on a single line.
        // (min intrinsic only guarantees the longest word fits, which wraps IOB.)
        val naturalWidths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }

        val placeables = if (isWidthBounded) {
            // IOB hugs its content but never grabs more than half the row when space is tight.
            val iobWidth = naturalWidths[0].coerceAtMost(availableWidth / 2)
            // insulinReq hugs its short text too, within what IOB left.
            val reqWidth = if (withReq) naturalWidths[2].coerceAtMost((availableWidth - iobWidth).coerceAtLeast(0) / 2) else 0
            // COB fills the rest of the row up to the edge so the chips span the full width;
            // its text marquee-scrolls when it can't fit (see CobChip).
            val cobWidth = (availableWidth - iobWidth - reqWidth).coerceAtLeast(0)
            listOfNotNull(
                measurables[0].measure(constraints.copy(minWidth = iobWidth, maxWidth = iobWidth)),
                measurables[1].measure(constraints.copy(minWidth = cobWidth, maxWidth = cobWidth)),
                if (withReq) measurables[2].measure(constraints.copy(minWidth = reqWidth, maxWidth = reqWidth)) else null
            )
        } else {
            measurables.mapIndexed { i, measurable ->
                measurable.measure(constraints.copy(minWidth = naturalWidths[i], maxWidth = naturalWidths[i]))
            }
        }

        val height = if (placeables.isEmpty()) 0 else placeables.maxOf { it.height }
        val layoutWidth = if (isWidthBounded) constraints.maxWidth else placeables.sumOf { it.width } + (if (placeables.size > 1) (placeables.size - 1) * spacingPx else 0)
        layout(layoutWidth, height) {
            var x = 0
            placeables.forEachIndexed { i, placeable ->
                placeable.place(x, 0)
                x += placeable.width + if (i < placeables.lastIndex) spacingPx else 0
            }
        }
    }
}
