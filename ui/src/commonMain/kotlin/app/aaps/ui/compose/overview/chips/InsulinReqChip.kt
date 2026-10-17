package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource

/**
 * Fork (Toni): insulinReq of the last loop run beside IOB and COB. Greyed out when the run is old; a tap shows
 * where it comes from (for FUSE the lines "Grenze" and "Block").
 */
@Composable
internal fun InsulinReqChip(
    state: InsulinReqUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    // Same compact height as the IOB and COB chips, see IobChip.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        val chipDescription = stringResource(CoreUiStrings.insulin_req_title)
        Surface(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
            shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
            color = Color.Transparent,
            modifier = modifier
                .heightIn(min = AapsSpacing.chipHeight)
                .semantics { contentDescription = chipDescription }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
            ) {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    // Greyed out with the theme's muted color, not a made up alpha.
                    color = if (state.stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
