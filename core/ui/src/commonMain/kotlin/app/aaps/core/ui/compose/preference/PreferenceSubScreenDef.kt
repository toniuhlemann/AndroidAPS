package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef

/**
 * Lightweight preference subscreen definition.
 * Can contain both PreferenceKeys and nested PreferenceSubScreenDefs for hierarchical structure.
 * Content is auto-generated from items using AdaptivePreferenceList.
 *
 * Titles are [TextRef], so the rendering code deals with one form only, the same as it does for
 * [PreferenceKey]. A second constructor takes plain resource ids, because ~174 call sites still build
 * these with `titleResId = R.string.x`; it wraps them in [TextRef.AndroidRes].
 *
 * Which one you get is decided by the argument NAME, and picking the wrong one is a type error rather
 * than anything readable: pass a [TextRef] (`title = CoreUiStrings.x`) to `title`, and a resource id
 * (`titleResId = R.string.x`) to `titleResId`. Multiplatform call sites want the first, since a
 * resource id means nothing off Android.
 *
 * @param key Unique key for this subscreen
 * @param title Screen title
 * @param items List of preference items (keys and/or nested subscreens)
 * @param summary Optional summary shown in the parent list
 * @param icon Optional Compose ImageVector icon shown next to the title
 * @param rowSummaries Optional live lines for the rows of this screen, see [PreferenceRowSummaries]
 * @param shortSummaries The rows show at most two lines under the title and the whole explanation after a tap, see
 *   [LocalShortPreferenceSummaries]
 */
data class PreferenceSubScreenDef(
    val key: String,
    /** Screen title, in the same form as [PreferenceKey.title]. */
    val title: TextRef,
    val items: List<PreferenceItem> = emptyList(),
    /** Optional summary, in the same form as [PreferenceKey.summary]. */
    val summary: TextRef? = null,
    val icon: ImageVector? = null,
    val rowSummaries: PreferenceRowSummaries? = null,
    val shortSummaries: Boolean = false
) : PreferenceItem {

    /**
     * Resource id form, for the many call sites that still name their strings with R.string.
     *
     * @param titleResId String resource id for the screen title
     * @param summaryResId Optional string resource id for the summary shown in the parent list
     */
    constructor(
        key: String,
        titleResId: Int,
        items: List<PreferenceItem> = emptyList(),
        summaryResId: Int? = null,
        icon: ImageVector? = null
    ) : this(key, TextRef.AndroidRes(titleResId), items, summaryResId?.let { TextRef.AndroidRes(it) }, icon)

    /** Titles of the contained items, used to build the summary line in the parent list. */
    fun effectiveSummaryItems(): List<TextRef> =
        items.mapNotNull { item ->
            when (item) {
                is PreferenceKey          -> item.title
                is PreferenceSubScreenDef -> item.title
                else                      -> null
            }
        }
}

/**
 * Live lines for the rows of one screen: preference key -> a short line shown under the title instead of the
 * key's summary. The summary stays the explanation in the edit dialog, so the row says what the value does NOW and
 * the dialog says what the setting is. Rows without a line keep their summary.
 *
 * It is called in composition, so an implementation can read the values its lines depend on and is recomposed
 * when one of them changes.
 */
fun interface PreferenceRowSummaries {

    @Composable
    fun lines(): Map<String, String>
}

/**
 * A row of plain text between the values of a screen: an explanation that is not a setting. It stores nothing and
 * cannot be clicked.
 *
 * @param text the text
 * @param title optional heading above the text; without one the text stands alone, like a short paragraph
 */
data class PreferenceInfoDef(
    val text: TextRef,
    val title: TextRef? = null
) : PreferenceItem
