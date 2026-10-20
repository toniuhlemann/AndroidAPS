package app.aaps.ui.compose.quickLaunch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.fuse.FuseMealButtonState
import app.aaps.core.ui.compose.icons.IcBolus
import app.aaps.core.ui.compose.icons.IcCarbs
import app.aaps.core.ui.compose.icons.IcTtActivity
import app.aaps.core.ui.compose.icons.IcTtEatingSoon
import app.aaps.core.ui.compose.icons.IcTtHypo
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.stringResource

@Immutable
data class ResolvedQuickLaunchItem(
    val action: QuickLaunchAction,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val description: String? = null,
    /** Fork (FUSE): the state of the meal button, for its color; null for every other action. */
    val fuseMealState: FuseMealButtonState? = null
)

/**
 * Floating pill-shaped toolbar for quick actions.
 * Displayed as an overlay at the bottom of the overview screen.
 * When items exceed available width, extra items go into an overflow dropdown menu.
 * Long-press any icon to see a tooltip with label and description.
 */
@Composable
fun QuickLaunchToolbar(
    items: List<ResolvedQuickLaunchItem>,
    onActionClick: (QuickLaunchAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 6.dp,
        modifier = modifier.padding(horizontal = 16.dp)
    ) {
        BoxWithConstraints(Modifier.height(48.dp)) {
            val itemSizePx = with(LocalDensity.current) { 48.dp.toPx() }
            val contentPaddingPx = with(LocalDensity.current) { 8.dp.toPx() }
            val maxItems = ((constraints.maxWidth - contentPaddingPx) / itemSizePx).toInt()
                .coerceAtLeast(1)

            val needsOverflow = items.size > maxItems
            val visibleItems = if (needsOverflow) items.take((maxItems - 1).coerceAtLeast(0)) else items
            val overflowItems = if (needsOverflow) items.drop((maxItems - 1).coerceAtLeast(0)) else emptyList()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                visibleItems.forEach { item ->
                    ToolbarIconButton(item, onActionClick)
                }
                if (needsOverflow) {
                    OverflowMenuButton(overflowItems, onActionClick)
                }
            }
        }
    }
}

@Composable
internal fun resolveItemColor(item: ResolvedQuickLaunchItem): androidx.compose.ui.graphics.Color {
    val action = item.action
    // Fork (FUSE): the state must be readable at the button - "did I press already?" is the question that leads to a
    // double press. A running marker in the warning color, without an account in the error color.
    item.fuseMealState?.let { state ->
        return when (state) {
            FuseMealButtonState.ACTIVE            -> AapsTheme.generalColors.inProgress
            FuseMealButtonState.ACTIVE_NO_ACCOUNT -> MaterialTheme.colorScheme.error
            FuseMealButtonState.IDLE,
            FuseMealButtonState.NEW_MEAL          -> ElementType.CARBS.color()
        }
    }
    if (action is QuickLaunchAction.QuickWizardAction) return when (item.icon) {
        IcBolus -> ElementType.INSULIN.color()
        IcCarbs -> ElementType.CARBS.color()
        else    -> ElementType.QUICK_WIZARD.color()
    }
    if (action is QuickLaunchAction.TempTargetPreset) {
        val colors = AapsTheme.generalColors
        return when (item.icon) {
            IcTtActivity   -> colors.ttActivity
            IcTtEatingSoon -> colors.ttEatingSoon
            IcTtHypo       -> colors.ttHypoglycemia
            else            -> colors.ttCustom
        }
    }
    return action.elementType?.color() ?: MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarIconButton(
    item: ResolvedQuickLaunchItem,
    onActionClick: (QuickLaunchAction) -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip {
                if (item.description != null) {
                    Column {
                        Text(item.label, style = MaterialTheme.typography.labelMedium)
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(item.label)
                }
            }
        },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = { onActionClick(item.action) },
            enabled = item.enabled,
            modifier = Modifier
                .size(48.dp)
        ) {
            val tint = resolveItemColor(item)
            Box(Modifier.size(24.dp)) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
                FuseMealMarkView(fuseMealMark(item.fuseMealState), tint, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/**
 * Fork (FUSE): a mark at the meal button. The toolbar shows no text, so the "◉" of the label ("◉ Mahlzeit") was
 * only in the long-press tooltip, and yellow (meal running) next to orange (no meal) is hard to tell apart.
 */
internal enum class FuseMealMark { NONE, DOT, RING }

internal fun fuseMealMark(state: FuseMealButtonState?): FuseMealMark = when (state) {
    // The marker runs: a press withdraws the meal.
    FuseMealButtonState.ACTIVE,
    FuseMealButtonState.ACTIVE_NO_ACCOUNT -> FuseMealMark.DOT
    // The window of the meal is still open: a press starts a new meal.
    FuseMealButtonState.NEW_MEAL          -> FuseMealMark.RING
    FuseMealButtonState.IDLE, null        -> FuseMealMark.NONE
}

/** The mark sits at the top right corner, half outside the icon; an edge in the toolbar color keeps it apart. */
@Composable
private fun FuseMealMarkView(mark: FuseMealMark, color: Color, modifier: Modifier) {
    if (mark == FuseMealMark.NONE) return
    val toolbar = MaterialTheme.colorScheme.surfaceContainerHigh
    val placed = modifier.offset(x = 4.dp, y = (-4).dp).size(10.dp)
    when (mark) {
        FuseMealMark.DOT  -> Box(placed.background(color, CircleShape).border(2.dp, toolbar, CircleShape))
        FuseMealMark.RING -> Box(placed.background(toolbar, CircleShape).border(2.dp, color, CircleShape))
        FuseMealMark.NONE -> Unit
    }
}

@Composable
private fun OverflowMenuButton(
    overflowItems: List<ResolvedQuickLaunchItem>,
    onActionClick: (QuickLaunchAction) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(CoreUiStrings.more_options),
                modifier = Modifier.size(24.dp)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            overflowItems.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label)
                            if (item.description != null) {
                                Text(
                                    item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = resolveItemColor(item),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    enabled = item.enabled,
                    onClick = {
                        showMenu = false
                        onActionClick(item.action)
                    }
                )
            }
        }
    }
}
