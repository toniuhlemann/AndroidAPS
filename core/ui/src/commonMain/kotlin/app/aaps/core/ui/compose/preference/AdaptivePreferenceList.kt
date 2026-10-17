/*
 * Adaptive Preference List for Jetpack Compose
 * Provides generic rendering for lists of PreferenceItems
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.Composable
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.VisibilityContext

/**
 * Renders a list of preference items (keys, subscreens, custom items).
 * This is the enhanced version that handles all PreferenceItem types.
 *
 * Supported types:
 * - PreferenceKey: Rendered with AdaptivePreferenceItem
 * - PreferenceSubScreenDef: Rendered as navigation item
 * - PreferenceInfoDef: Rendered as plain text
 *
 * @param items List of PreferenceItems to render
 * @param visibilityContext Optional context for evaluating runtime visibility conditions
 * @param onNavigateToSubScreen Callback for navigating to subscreens
 * @param rowSummaries Live lines by preference key, shown on the row instead of the summary (see [PreferenceRowSummaries])
 */
@Composable
fun AdaptivePreferenceList(
    items: List<PreferenceItem>,
    onShowMessage: (String) -> Unit = { },
    visibilityContext: VisibilityContext? = null,
    onNavigateToSubScreen: ((PreferenceSubScreenDef) -> Unit)? = null,
    rowSummaries: Map<String, String> = emptyMap()
) {
    items.forEach { item ->
        when (item) {
            is PreferenceKey          -> {
                // AdaptivePreferenceItem handles all visibility checks
                // (simpleMode, mode flags, dependencies, engineeringMode, runtime visibility)
                AdaptivePreferenceItem(
                    key = item,
                    onShowMessage = onShowMessage,
                    visibilityContext = visibilityContext,
                    rowSummary = rowSummaries[item.key]
                )
            }

            is PreferenceInfoDef      -> PreferenceInfoRow(item)

            is PreferenceSubScreenDef -> {
                // Subscreens are handled by PreferenceContentExtensions as nested collapsible sections
                // Not rendered here in the flat list
            }
        }
    }
}
