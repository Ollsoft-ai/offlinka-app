package cz.offlinka.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import cz.offlinka.ui.R
import cz.offlinka.ui.components.BarIconButton
import cz.offlinka.ui.components.BookBadge
import cz.offlinka.ui.components.Hairline
import cz.offlinka.ui.components.OutlineButton
import cz.offlinka.ui.components.PaperBar
import cz.offlinka.ui.components.Segmented
import cz.offlinka.ui.components.SheetOverlay
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.model.bookTitle
import cz.offlinka.ui.model.family
import cz.offlinka.ui.model.icon
import cz.offlinka.ui.theme.BookFamily
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.bookTone
import cz.offlinka.ui.theme.hardShadow

data class FindState(val query: String = "", val current: Int = 0, val total: Int = 0, val done: Boolean = false)

data class ReaderState(
    val title: String = "",
    val book: BookUi? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val saved: Boolean = false,
    /** 0..100 while a page loads, null when idle. */
    val progress: Int? = null,
    val find: FindState? = null,
    val textZoom: Int = 100,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val showTextPanel: Boolean = false,
    /** An external (internet) link the user tapped; shown in a dialog. */
    val externalLink: String? = null,
    /** Books like maps and games are apps, not articles: nothing to save, no text size, no find. */
    val isApp: Boolean = false,
    /** The article's own heading scrolled away: show its title in the bar instead of the book. */
    val showTitle: Boolean = false,
)

object TextZoom {
    const val MIN = 80
    const val MAX = 200
    const val STEP = 5
}

@Composable
fun ReaderScreen(
    state: ReaderState,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggleSave: () -> Unit,
    onSearch: () -> Unit,
    onOpenFind: () -> Unit,
    onFindQuery: (String) -> Unit,
    onFindNext: (forward: Boolean) -> Unit,
    onCloseFind: () -> Unit,
    onToggleTextPanel: () -> Unit,
    onTextZoom: (Int) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onMainPage: () -> Unit,
    onBookDetails: () -> Unit,
    onHistory: () -> Unit,
    onDismissExternal: () -> Unit,
    onCopyLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable () -> Unit,
) {
    val c = Offlinka.colors
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            val find = state.find
            if (find != null) {
                FindBar(find, onFindQuery, onFindNext, onCloseFind)
            } else {
                ReaderTopBar(state, onHome, onToggleSave, onSearch, onBookDetails, onHistory)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content()
                val p = state.progress
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = c.primary,
                        trackColor = c.bg.copy(alpha = 0f),
                        drawStopIndicator = {},
                    )
                }
            }
            ReaderBottomBar(state, onBack, onForward, onOpenFind, onToggleTextPanel, onMainPage)
        }
        if (state.showTextPanel && !state.isApp) {
            SheetOverlay(onDismiss = onToggleTextPanel, dismissLabel = stringResource(R.string.action_close)) {
                TextPanel(state, onTextZoom, onTheme)
            }
        }
        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 72.dp),
        )
    }
    val link = state.externalLink
    if (link != null) {
        ExternalLinkDialog(link, onDismissExternal, onCopyLink)
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderState,
    onHome: () -> Unit,
    onToggleSave: () -> Unit,
    onSearch: () -> Unit,
    onBookDetails: () -> Unit,
    onHistory: () -> Unit,
) {
    val c = Offlinka.colors
    var menu by remember { mutableStateOf(false) }
    PaperBar {
        BarIconButton(OfflinkaIcons.Home, stringResource(R.string.nav_home), onHome, tint = c.ink)
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = state.book != null, role = Role.Button, onClick = onBookDetails)
                .heightIn(min = 48.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val book = state.book
            if (state.showTitle && state.title.isNotBlank()) {
                Text(
                    state.title,
                    style = Offlinka.type.subhead,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            } else {
                val tone = bookTone(book?.kind?.family ?: BookFamily.Wiki, c.isDark)
                BookBadge(book?.icon ?: OfflinkaIcons.Article, tone.background, tone.foreground, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (book != null) bookTitle(book) else stringResource(R.string.reader_loading),
                    style = Offlinka.type.label,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        if (!state.isApp) {
            val savedDesc = stringResource(if (state.saved) R.string.cd_saved_on else R.string.cd_saved_off)
            BarIconButton(
                if (state.saved) OfflinkaIcons.BookmarkFilled else OfflinkaIcons.Bookmark,
                stringResource(if (state.saved) R.string.reader_unsave else R.string.reader_save),
                onToggleSave,
                tint = if (state.saved) c.accent else c.ink,
                modifier = Modifier.semantics { stateDescription = savedDesc },
            )
        }
        Box {
            BarIconButton(OfflinkaIcons.More, stringResource(R.string.action_more), { menu = true }, tint = c.ink)
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                MenuItem(stringResource(R.string.cd_search), OfflinkaIcons.Search) { menu = false; onSearch() }
                MenuItem(stringResource(R.string.reader_history), OfflinkaIcons.History) { menu = false; onHistory() }
                MenuItem(stringResource(R.string.library_details), OfflinkaIcons.Info) { menu = false; onBookDetails() }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = Offlinka.colors
    DropdownMenuItem(
        text = { Text(text, style = Offlinka.type.label, color = c.ink) },
        leadingIcon = { Icon(icon, null, tint = c.ink2) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
private fun FindBar(
    find: FindState,
    onFindQuery: (String) -> Unit,
    onFindNext: (Boolean) -> Unit,
    onCloseFind: () -> Unit,
) {
    val c = Offlinka.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PaperBar {
        BarIconButton(OfflinkaIcons.Close, stringResource(R.string.action_close), onCloseFind, tint = c.ink)
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface)
                .border(2.dp, c.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = find.query,
                onValueChange = onFindQuery,
                singleLine = true,
                textStyle = Offlinka.type.searchInput.copy(color = c.ink),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onFindNext(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (find.query.isEmpty()) {
                            Text(stringResource(R.string.reader_find_placeholder), style = Offlinka.type.searchInput, color = c.ink3)
                        }
                        inner()
                    }
                },
            )
            if (find.query.isNotEmpty() && find.done) {
                Text(
                    if (find.total == 0) stringResource(R.string.reader_find_none)
                    else stringResource(R.string.reader_find_count, find.current, find.total),
                    style = Offlinka.type.label,
                    color = c.ink2,
                )
            }
        }
        BarIconButton(OfflinkaIcons.ChevronUp, stringResource(R.string.reader_find_prev), { onFindNext(false) }, tint = c.ink, enabled = find.total > 0)
        BarIconButton(OfflinkaIcons.ChevronDown, stringResource(R.string.reader_find_next), { onFindNext(true) }, tint = c.ink, enabled = find.total > 0)
    }
}

@Composable
private fun TextPanel(state: ReaderState, onTextZoom: (Int) -> Unit, onTheme: (ThemeMode) -> Unit) {
    val c = Offlinka.colors
    Text(
        stringResource(R.string.reader_text_size),
        style = Offlinka.type.headline,
        color = c.ink,
        modifier = Modifier.padding(bottom = 18.dp).semantics { heading() },
    )
    val smaller = stringResource(R.string.reader_text_smaller)
    val larger = stringResource(R.string.reader_text_larger)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = state.textZoom > TextZoom.MIN, role = Role.Button, onClickLabel = smaller) {
                    onTextZoom((state.textZoom - 10).coerceAtLeast(TextZoom.MIN))
                }
                .semantics { contentDescription = smaller },
            contentAlignment = Alignment.Center,
        ) { Text("A", style = Offlinka.type.subhead, color = c.ink) }
        ZoomSlider(state.textZoom, onTextZoom, Modifier.weight(1f))
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = state.textZoom < TextZoom.MAX, role = Role.Button, onClickLabel = larger) {
                    onTextZoom((state.textZoom + 10).coerceAtMost(TextZoom.MAX))
                }
                .semantics { contentDescription = larger },
            contentAlignment = Alignment.Center,
        ) { Text("A", style = Offlinka.type.display.copy(fontSize = 30.sp), color = c.ink) }
    }
    Text(
        stringResource(R.string.settings_text_size_value, state.textZoom),
        style = Offlinka.type.subhead,
        color = c.ink2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(22.dp))
    Text(stringResource(R.string.settings_theme), style = Offlinka.type.subhead, color = c.ink2)
    Spacer(Modifier.height(10.dp))
    Segmented(
        listOf(
            ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
            ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
            ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        ),
        state.theme,
        onTheme,
    )
    Spacer(Modifier.height(8.dp))
}

/** The text-size slider shared by the reader sheet and settings: continuous, snapping to 5 %. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoomSlider(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Offlinka.colors
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(((it / TextZoom.STEP).roundToInt() * TextZoom.STEP).coerceIn(TextZoom.MIN, TextZoom.MAX)) },
        valueRange = TextZoom.MIN.toFloat()..TextZoom.MAX.toFloat(),
        thumb = {
            Box(
                Modifier
                    .size(28.dp)
                    .hardShadow(Offlinka.elevation.card.copy(x = 2.dp, y = 3.dp), RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface)
                    .border(2.dp, c.outline, RoundedCornerShape(14.dp)),
            )
        },
        track = { st ->
            val range = st.valueRange.endInclusive - st.valueRange.start
            val fraction = if (range > 0f) (st.value - st.valueRange.start) / range else 0f
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val y = size.height / 2
                val w = 5.dp.toPx()
                drawLine(c.line, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                drawLine(c.accent, Offset(0f, y), Offset(size.width * fraction, y), w, StrokeCap.Round)
            }
        },
        modifier = modifier.semantics { stateDescription = "$value %" },
    )
}

@Composable
private fun ExternalLinkDialog(link: String, onDismiss: () -> Unit, onCopyLink: (String) -> Unit) {
    val c = Offlinka.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .hardShadow(Offlinka.elevation.raised, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .border(1.5.dp, c.outline, RoundedCornerShape(16.dp))
                .padding(22.dp),
        ) {
            Icon(OfflinkaIcons.NoInternet, null, tint = c.warnInk, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.reader_external_title), style = Offlinka.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reader_external_body), style = Offlinka.type.body, color = c.ink2)
            Spacer(Modifier.height(14.dp))
            Text(
                link,
                style = Offlinka.type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = c.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surfaceMuted)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton(stringResource(R.string.reader_copy_link), { onCopyLink(link) }, Modifier.weight(1f), icon = OfflinkaIcons.Copy, shadow = true)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.action_close), style = Offlinka.type.subhead, color = c.ink) }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onOpenFind: () -> Unit,
    onToggleTextPanel: () -> Unit,
    onMainPage: () -> Unit,
) {
    val c = Offlinka.colors
    Column(Modifier.fillMaxWidth().background(c.surface).windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        Hairline(color = c.line)
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarIconButton(OfflinkaIcons.Back, stringResource(R.string.action_back), onBack, tint = c.ink, enabled = state.canGoBack)
            BarIconButton(OfflinkaIcons.Forward, stringResource(R.string.action_forward), onForward, tint = c.ink, enabled = state.canGoForward)
            BarIconButton(OfflinkaIcons.FindInPage, stringResource(R.string.reader_find), onOpenFind, tint = c.ink, enabled = !state.isApp)
            BarIconButton(OfflinkaIcons.TextSize, stringResource(R.string.reader_text_size), onToggleTextPanel, tint = c.ink, enabled = !state.isApp)
            BarIconButton(OfflinkaIcons.BookWikipedia, stringResource(R.string.reader_main_page), onMainPage, tint = c.ink)
        }
    }
}
