package cz.offlinka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.BarIconButton
import cz.offlinka.ui.components.BookBadge
import cz.offlinka.ui.components.Hairline
import cz.offlinka.ui.components.MessageState
import cz.offlinka.ui.components.PaperBar
import cz.offlinka.ui.components.SectionLabel
import cz.offlinka.ui.components.TextLink
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.SearchGroup
import cz.offlinka.ui.model.SearchHit
import cz.offlinka.ui.model.bookTitle
import cz.offlinka.ui.model.family
import cz.offlinka.ui.model.icon
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.bookTone
import java.text.Normalizer

data class SearchState(
    val query: String = "",
    /** Search only in this book; null = every book on the drive. */
    val scope: BookUi? = null,
    val groups: List<SearchGroup> = emptyList(),
    val recent: List<String> = emptyList(),
    val searching: Boolean = false,
    /** How many hits a group shows before "show more" when searching everywhere. */
    val perGroup: Int = 4,
    /** Nothing matched the whole query, so these results are for its longest word. */
    val retriedWord: String? = null,
    /** Accent-free search index being built on the phone, percent. */
    val indexProgress: Int? = null,
) {
    val showRecent: Boolean get() = query.isBlank()
    val noResults: Boolean get() = query.isNotBlank() && !searching && groups.all { it.hits.isEmpty() }
}

@Composable
fun SearchScreen(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHit: (SearchHit) -> Unit,
    onRecent: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onScope: (BookUi?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    onClearRecent: () -> Unit = {},
    onCancelIndex: () -> Unit = {},
) {
    val c = Offlinka.colors
    Column(modifier.fillMaxSize().background(c.bg)) {
        SearchBar(state, onQueryChange, onSubmit, onBack, autoFocus)
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (state.searching) {
                LinearProgressIndicator(Modifier.fillMaxSize(), color = c.primary, trackColor = c.bg)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            val scope = state.scope
            state.indexProgress?.let { p ->
                item(key = "index") {
                    InfoNote(stringResource(R.string.search_index_building, p), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        BarIconButton(OfflinkaIcons.Close, stringResource(R.string.action_cancel), onCancelIndex, tint = c.ink2)
                    }
                }
            }
            if (scope != null) {
                item(key = "scope") { ScopeChip(scope) { onScope(null) } }
            }
            state.retriedWord?.let { w ->
                if (!state.showRecent && state.groups.any { it.hits.isNotEmpty() }) {
                    item(key = "retried") {
                        Text(
                            stringResource(R.string.search_retry_word, w),
                            style = Offlinka.type.bodySmall,
                            color = c.ink2,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                        )
                    }
                }
            }
            when {
                state.showRecent -> {
                    if (state.recent.isNotEmpty()) {
                        item(key = "recent-title") {
                            SectionLabel(stringResource(R.string.search_recent_title), Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    stringResource(R.string.action_clear),
                                    style = Offlinka.type.subhead,
                                    color = c.link,
                                    modifier = Modifier
                                        .clickable(role = Role.Button, onClick = onClearRecent)
                                        .heightIn(min = 48.dp)
                                        .padding(top = 12.dp),
                                )
                            }
                        }
                        items(state.recent, key = { "recent-$it" }) { q ->
                            RecentRow(q, onClick = { onRecent(q) }, onRemove = { onRemoveRecent(q) })
                        }
                    }
                    item(key = "tip") {
                        InfoNote(stringResource(R.string.search_tip), Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
                    }
                }
                state.noResults -> item(key = "none") {
                    MessageState(
                        OfflinkaIcons.Search,
                        stringResource(R.string.search_no_results_title),
                        stringResource(R.string.search_no_results_body),
                    )
                }
                else -> state.groups.filter { it.hits.isNotEmpty() }.forEach { group ->
                    val limited = scope == null && state.groups.size > 1
                    val shown = if (limited) group.hits.take(state.perGroup) else group.hits
                    item(key = "g-${group.book.id}") { GroupHeader(group.book) }
                    items(shown, key = { "h-${group.book.id}-${it.path}-${it.title}" }) { hit ->
                        HitRow(hit, state.query, onClick = { onHit(hit) }, onFill = { onQueryChange(hit.title) })
                    }
                    if (limited && group.hits.size > shown.size) {
                        item(key = "more-${group.book.id}") {
                            TextLink(stringResource(R.string.search_show_more), { onScope(group.book) }, Modifier.padding(start = 58.dp, top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean,
) {
    val c = Offlinka.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    PaperBar {
        BarIconButton(OfflinkaIcons.Back, stringResource(R.string.action_back), onBack, tint = c.ink)
        Row(
            Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface)
                .border(2.dp, c.outline, RoundedCornerShape(8.dp))
                .padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val placeholder = state.scope?.let { bookTitle(it) } ?: stringResource(R.string.search_placeholder)
            BasicTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = Offlinka.type.searchInput.copy(color = c.ink),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text(
                                placeholder,
                                style = Offlinka.type.searchInput.copy(fontWeight = FontWeight.Normal),
                                color = c.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (state.query.isNotEmpty()) {
                BarIconButton(OfflinkaIcons.Close, stringResource(R.string.search_clear_query), { onQueryChange("") }, tint = c.ink2)
            }
        }
    }
}

@Composable
private fun ScopeChip(book: BookUi, onClear: () -> Unit) {
    val c = Offlinka.colors
    val tone = bookTone(book.kind.family, c.isDark)
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.surface)
            .border(1.5.dp, c.outline, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_close), onClick = onClear)
            .heightIn(min = 44.dp)
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookBadge(book.icon, tone.background, tone.foreground, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(bookTitle(book), style = Offlinka.type.label, color = c.ink)
        Spacer(Modifier.width(8.dp))
        Icon(OfflinkaIcons.Close, null, tint = c.ink2, modifier = Modifier.size(18.dp))
    }
}

/** Info note: soft fill, info icon, body text, optional trailing action. */
@Composable
private fun InfoNote(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val c = Offlinka.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.surfaceMuted)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OfflinkaIcons.Info, null, tint = c.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Offlinka.type.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun GroupHeader(book: BookUi) {
    val c = Offlinka.colors
    val tone = bookTone(book.kind.family, c.isDark)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookBadge(book.icon, tone.background, tone.foreground, size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(bookTitle(book), style = Offlinka.type.label, color = c.ink2, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun HitRow(hit: SearchHit, query: String, onClick: () -> Unit, onFill: () -> Unit) {
    val c = Offlinka.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(start = 58.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                highlight(hit.title, query, c.ink, c.ink2),
                style = Offlinka.type.body.copy(fontSize = Offlinka.type.title.fontSize),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val snippet = hit.snippet
            if (!snippet.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(OfflinkaIcons.Redirect, null, tint = c.ink3, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(snippet, style = Offlinka.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        BarIconButton(OfflinkaIcons.FillQuery, stringResource(R.string.search_fill), onFill, tint = c.ink2)
    }
    Hairline(color = c.line)
}

@Composable
private fun RecentRow(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val c = Offlinka.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OfflinkaIcons.Clock, null, tint = c.ink3, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(query, style = Offlinka.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BarIconButton(OfflinkaIcons.Close, stringResource(R.string.search_remove_recent), onRemove, tint = c.ink3)
    }
    Hairline(color = c.line)
}

/** Lowercase without diacritics, one output char per input char (so indexes map back). */
private fun fold(ch: Char): Char =
    Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: ch

/**
 * The title words the query words start (diacritics and case ignored) in bold ink, the rest in regular
 * ink2: "Okres **Český Krumlov**" for "cesky krumlov". A title nothing matches stays all bold.
 */
fun highlight(title: String, query: String, strong: Color, weak: Color): AnnotatedString {
    val folded = String(CharArray(title.length) { fold(title[it]) })
    val words = query.trim().split(Regex("\\s+")).map { w -> String(CharArray(w.length) { fold(w[it]) }) }.filter { it.isNotEmpty() }
    val marked = BooleanArray(title.length)
    for (w in words) {
        var from = 0
        while (true) {
            val i = folded.indexOf(w, from)
            if (i < 0) break
            if (i == 0 || !folded[i - 1].isLetterOrDigit()) {
                var end = i + w.length
                while (end < title.length && title[end].isLetterOrDigit()) end++
                for (k in i until end) marked[k] = true
                break
            }
            from = i + 1
        }
    }
    if (marked.none { it }) {
        return buildAnnotatedString { withStyle(SpanStyle(color = strong, fontWeight = FontWeight.Bold)) { append(title) } }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < title.length) {
            val on = marked[i]
            var j = i
            while (j < title.length && marked[j] == on) j++
            withStyle(if (on) SpanStyle(color = strong, fontWeight = FontWeight.Bold) else SpanStyle(color = weak, fontWeight = FontWeight.Normal)) {
                append(title.substring(i, j))
            }
            i = j
        }
    }
}
