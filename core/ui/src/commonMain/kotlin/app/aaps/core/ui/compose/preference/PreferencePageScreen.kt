package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.masterEditingEnabled
import app.aaps.core.ui.compose.stringResource

/** A search starts with this many characters; one letter would match nearly every value. */
private const val SEARCH_MIN_CHARS = 2

/** At most this many hits are listed. More than that is not a search result, it is the menu again. */
private const val SEARCH_MAX_HITS = 40

/**
 * A preference screen as a page of its own ([PreferenceSubScreenDef.pages]).
 *
 * A card of sections that unfold is one long list: the title of the card scrolls away, and the next section starts in
 * the middle of the screen. A page shows one thing. Its sub-screens are rows that open their own page, its values
 * stand in groups under the headings of its info rows, and the top bar always says where you are and how you got
 * there. Back leads to the page before, at the place it was left.
 *
 * The caller provides the preference theme and, for a page with sub-screens, [LocalPreferenceNavigator]. A value named
 * by [LocalHighlightKey] is scrolled into view when the page opens; that is how a search hit leads to its value.
 *
 * @param screen the screen to show
 * @param parents titles of the screens above this one, the outermost first - shown as the way to this page
 * @param showSearch a search field above the rows; it looks through every page and every value below [screen]
 * @param visibilityContext context for the visibility rules of the values
 * @param onShowMessage shows a short message, for the values that need one
 * @param onBackClick back
 */
@Composable
fun PreferencePageScreen(
    screen: PreferenceSubScreenDef,
    parents: List<TextRef> = emptyList(),
    showSearch: Boolean = false,
    visibilityContext: VisibilityContext? = null,
    onShowMessage: (String) -> Unit = {},
    onBackClick: () -> Unit
) {
    val navigator = LocalPreferenceNavigator.current
    val highlightKey = LocalHighlightKey.current
    var query by rememberSaveable(screen.key) { mutableStateOf("") }
    val rowSummaries = screen.rowSummaries?.lines().orEmpty()
    val way = wayLine(parents)
    val groups = remember(screen) { pageGroups(screen.items) }
    val searching = showSearch && query.trim().length >= SEARCH_MIN_CHARS
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // The rows of a page are drawn as they come into view, so a value far down does not exist yet when the page
    // opens. Scroll its group in first; the row then brings itself into view and shows its mark. This is done once:
    // coming back to the page keeps the place where it was left.
    var highlightShown by rememberSaveable(highlightKey) { mutableStateOf(false) }
    LaunchedEffect(highlightKey) {
        val index = groups.indexOfFirst { group -> group.items.any { it is PreferenceKey && it.key == highlightKey } }
        if (highlightKey != null && index >= 0 && !highlightShown) {
            listState.scrollToItem(rowsBeforeGroups(showSearch) + index)
            highlightShown = true
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = {
                    Column {
                        if (way.isNotEmpty()) {
                            Text(
                                text = way,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = stringResource(screen.title),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiStrings.back)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        CompositionLocalProvider(
            LocalVisibilityContext provides (visibilityContext ?: LocalVisibilityContext.current),
            LocalPreferenceSummaryMode provides screen.summaryMode,
            LocalCompactPreferenceRows provides true
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScrollIndicators(listState)
                    // A tap beside the search field ends the typing and closes the keyboard.
                    .clearFocusOnTap(focusManager),
                state = listState
            ) {
                item(key = "banner") { MasterOfflineBanner(editingEnabled = masterEditingEnabled()) }
                if (showSearch) {
                    item(key = "search") { PageSearchField(query = query, onQueryChange = { query = it }) }
                }
                if (searching) {
                    item(key = "hits") { PageSearchHits(screen = screen, query = query.trim(), navigator = navigator) }
                } else {
                    groups.forEachIndexed { index, group ->
                        item(key = "group_$index") {
                            PageGroup(
                                group = group,
                                rowSummaries = rowSummaries,
                                onShowMessage = onShowMessage,
                                navigator = navigator
                            )
                        }
                    }
                }
                item(key = "end") { Spacer(modifier = Modifier.size(AapsSpacing.xxLarge)) }
            }
        }
    }
}

/**
 * The titles of the pages on the way to a page as one line: "FUSE › Meals". Empty where there is no page above.
 * The sign between two steps comes from a text template, so a translation can choose it and its direction.
 */
@Composable
private fun wayLine(way: List<TextRef>): String {
    var line = ""
    way.forEach { step ->
        val title = stringResource(step)
        line = if (line.isEmpty()) title else stringResource(CoreUiStrings.preference_page_way, line, title)
    }
    return line
}

/** The list items of a page that stand before its first group: the banner and, where there is one, the search field. */
private fun rowsBeforeGroups(showSearch: Boolean): Int = if (showSearch) 2 else 1

/**
 * The rows of a page between two headings. A titled info row opens a group and is its heading; everything before the
 * first heading is a group without one.
 */
internal class PreferencePageGroup(val heading: PreferenceInfoDef?, val items: List<PreferenceItem>)

/** Cuts the items of a page into its groups, in their order. An empty page has no group. */
internal fun pageGroups(items: List<PreferenceItem>): List<PreferencePageGroup> {
    val groups = mutableListOf<PreferencePageGroup>()
    var heading: PreferenceInfoDef? = null
    var rows = mutableListOf<PreferenceItem>()
    fun close() {
        if (heading != null || rows.isNotEmpty()) groups += PreferencePageGroup(heading, rows.toList())
        heading = null
        rows = mutableListOf()
    }
    for (item in items) {
        if (item is PreferenceInfoDef && item.title != null) {
            close()
            heading = item
        } else {
            rows += item
        }
    }
    close()
    return groups
}

/** One group: its heading with the line that goes with it, a paragraph where there is one, and its rows in a card. */
@Composable
private fun PageGroup(
    group: PreferencePageGroup,
    rowSummaries: Map<String, String>,
    onShowMessage: (String) -> Unit,
    navigator: ((String, String?) -> Unit)?
) {
    val theme = LocalPreferenceTheme.current
    val paragraphs = group.items.filterIsInstance<PreferenceInfoDef>()
    val rows = group.items.filterNot { it is PreferenceInfoDef }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(theme.cardPadding)
    ) {
        group.heading?.let { heading ->
            val title = heading.title?.let { stringResource(it) }.orEmpty()
            val text = stringResource(heading.text)
            Column(modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
                if (text.isNotBlank()) ShortPreferenceSummary(text = text, title = title)
            }
        }
        paragraphs.forEach { paragraph ->
            val text = stringResource(paragraph.text)
            if (text.isNotBlank()) {
                ShortPreferenceSummary(
                    text = text,
                    title = null,
                    modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
                )
            }
        }
        if (rows.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = theme.cardElevation),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                DividedRows {
                    rows.forEach { item ->
                        when (item) {
                            // A screen that hides together with one of its values is not offered here either.
                            is PreferenceSubScreenDef ->
                                if (shouldShowSubScreenInline(subScreen = item, visibilityContext = LocalVisibilityContext.current)) {
                                    PreferenceNavigationRow(
                                        screen = item,
                                        onClick = navigator?.let { open -> { open(item.key, null) } }
                                    )
                                }

                            is PreferenceKey          -> HighlightablePreference(preferenceKey = item.key) {
                                AdaptivePreferenceItem(
                                    key = item,
                                    onShowMessage = onShowMessage,
                                    visibilityContext = LocalVisibilityContext.current,
                                    rowSummary = rowSummaries[item.key]
                                )
                            }

                            else                      -> Unit
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rows one below the other, with a thin line between two of them. A row that draws nothing - a value that is hidden
 * just now - gets no line, so no double line and no line at the edge of the card is left behind.
 */
@Composable
private fun DividedRows(content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.outlineVariant
    // Where the lines go: the top edge of every row that has a row above it. Set while the rows are measured and
    // read only while drawing.
    var lines by remember { mutableStateOf(emptyList<Int>()) }
    Layout(
        content = content,
        modifier = Modifier.drawWithContent {
            drawContent()
            val stroke = DividerDefaults.Thickness.toPx()
            lines.forEach { y ->
                drawLine(color = color, start = Offset(0f, y.toFloat()), end = Offset(size.width, y.toFloat()), strokeWidth = stroke)
            }
        }
    ) { measurables, constraints ->
        val rows = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val tops = mutableListOf<Int>()
        var height = 0
        rows.forEach { row ->
            if (row.height > 0 && height > 0) tops += height
            height += row.height
        }
        lines = tops
        val width = rows.maxOfOrNull { it.width } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var top = 0
            rows.forEach { row ->
                row.placeRelative(x = 0, y = top)
                top += row.height
            }
        }
    }
}

/**
 * A screen as one row that opens it: its icon where it has one, its title, the line that says what is in it, and an
 * arrow. Without a way to open it the row is drawn but cannot be tapped.
 */
@Composable
internal fun PreferenceNavigationRow(screen: PreferenceSubScreenDef, onClick: (() -> Unit)?) {
    val theme = LocalPreferenceTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(theme.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        screen.icon?.let { icon ->
            Box(
                modifier = Modifier
                    .size(AapsSpacing.xxLarge + AapsSpacing.extraLarge)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(AapsSpacing.large)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(modifier = Modifier.width(AapsSpacing.extraLarge))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(screen.title), style = theme.titleTextStyle, color = theme.titleColor)
            screen.summary?.let { summary ->
                Text(text = stringResource(summary), style = theme.summaryTextStyle, color = theme.summaryColor)
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageSearchField(query: String, onQueryChange: (String) -> Unit) {
    val theme = LocalPreferenceTheme.current
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(theme.cardPadding),
        singleLine = true,
        // The hits follow every letter. The search key only puts the keyboard away, so that they can be seen.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        placeholder = { Text(stringResource(CoreUiStrings.search)) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(imageVector = Icons.Filled.Clear, contentDescription = stringResource(CoreUiStrings.clear))
                }
            }
        }
    )
}

/**
 * One thing the search of a page can find: a page below it or a value.
 *
 * @param title the name of the hit
 * @param text the line of a page or the explanation of a value; the search reads it too
 * @param screenKey the page that a tap opens
 * @param highlightKey the value to bring into view on that page; null where the hit is the page itself
 * @param way titles of the pages from the searched page down to the place of the hit, the outermost first
 */
internal class PreferencePageSearchEntry(
    val title: TextRef,
    val text: TextRef?,
    val screenKey: String,
    val highlightKey: String?,
    val way: List<TextRef>
)

/**
 * Everything the search of [screen] can find, at any depth: every page below it and every value, each with the page
 * that a tap opens. A page stands before its values. A value named twice is listed once.
 */
internal fun pageSearchEntries(screen: PreferenceSubScreenDef): List<PreferencePageSearchEntry> {
    val entries = mutableListOf<PreferencePageSearchEntry>()
    val seen = mutableSetOf<String>()
    fun walk(current: PreferenceSubScreenDef, way: List<TextRef>) {
        for (item in current.items) {
            when (item) {
                is PreferenceKey          ->
                    if (seen.add(item.key)) entries += PreferencePageSearchEntry(item.title, item.summary, current.key, item.key, way)

                is PreferenceSubScreenDef -> {
                    entries += PreferencePageSearchEntry(item.title, item.summary, item.key, null, way)
                    walk(item, way + item.title)
                }

                else                      -> Unit
            }
        }
    }
    walk(screen, emptyList())
    return entries
}

/**
 * The pages and values that match the search: the words are looked for in the name and in the line or explanation,
 * so a value is also found by an old name that its explanation still gives. A tap opens the page; for a value it
 * opens the page the value stands on and brings the value into view.
 */
@Composable
private fun PageSearchHits(screen: PreferenceSubScreenDef, query: String, navigator: ((String, String?) -> Unit)?) {
    val theme = LocalPreferenceTheme.current
    val entries = remember(screen) { pageSearchEntries(screen) }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val hits = entries.filter { entry ->
        val name = stringResource(entry.title).lowercase()
        val text = entry.text?.let { stringResource(it) }.orEmpty().lowercase()
        words.all { name.contains(it) || text.contains(it) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(theme.cardPadding)
    ) {
        if (hits.isEmpty()) {
            Text(
                text = stringResource(CoreUiStrings.no_search_results),
                style = theme.summaryTextStyle,
                color = theme.summaryColor,
                modifier = Modifier.padding(theme.padding)
            )
            return@Column
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = theme.cardElevation),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            DividedRows {
                hits.take(SEARCH_MAX_HITS).forEach { hit ->
                    val way = wayLine(hit.way)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (navigator != null) Modifier.clickable(role = Role.Button) { navigator(hit.screenKey, hit.highlightKey) }
                                else Modifier
                            )
                            .padding(theme.padding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(hit.title), style = theme.titleTextStyle, color = theme.titleColor)
                            if (way.isNotEmpty()) Text(text = way, style = theme.summaryTextStyle, color = theme.summaryColor)
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
