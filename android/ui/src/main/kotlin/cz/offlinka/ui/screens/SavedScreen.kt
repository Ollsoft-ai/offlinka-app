package cz.offlinka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import cz.offlinka.ui.components.groupItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.BarIconButton
import cz.offlinka.ui.components.BookBadge
import cz.offlinka.ui.components.GroupCard
import cz.offlinka.ui.components.GroupDivider
import cz.offlinka.ui.components.MessageState
import cz.offlinka.ui.components.ScreenHeader
import cz.offlinka.ui.components.SectionLabel
import cz.offlinka.ui.components.Segmented
import cz.offlinka.ui.model.ArticleRef
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.bookTitle
import cz.offlinka.ui.model.family
import cz.offlinka.ui.model.icon
import cz.offlinka.ui.theme.BookFamily
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.bookTone
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

enum class SavedTab { SAVED, HISTORY }

data class SavedState(
    val tab: SavedTab = SavedTab.SAVED,
    val saved: List<ArticleRef> = emptyList(),
    val history: List<ArticleRef> = emptyList(),
    /** Books currently on the drive, by id; items of other books are shown as unavailable. */
    val books: Map<String, BookUi> = emptyMap(),
    /** "Now" for grouping history into today / yesterday (injected so screenshots are stable). */
    val now: Long = System.currentTimeMillis(),
    val timeZone: TimeZone = TimeZone.getDefault(),
)

@Composable
fun SavedScreen(
    state: SavedState,
    onTab: (SavedTab) -> Unit,
    onOpen: (ArticleRef) -> Unit,
    onRemoveSaved: (ArticleRef) -> Unit,
    onRemoveHistory: (ArticleRef) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val c = Offlinka.colors
    var confirmClear by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item(key = "header") {
                Column {
                    ScreenHeader(stringResource(R.string.saved_title))
                    Segmented(
                        listOf(
                            SavedTab.SAVED to stringResource(R.string.saved_tab_saved),
                            SavedTab.HISTORY to stringResource(R.string.saved_tab_history),
                        ),
                        state.tab,
                        onTab,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                    )
                }
            }
            val items = if (state.tab == SavedTab.SAVED) state.saved else state.history
            if (items.isEmpty()) {
                item(key = "empty") {
                    if (state.tab == SavedTab.SAVED) {
                        MessageState(OfflinkaIcons.Bookmark, stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body))
                    } else {
                        MessageState(OfflinkaIcons.History, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
                    }
                }
            } else if (state.tab == SavedTab.SAVED) {
                item(key = "saved-top") { Spacer(Modifier.height(10.dp)) }
                itemsIndexed(items, key = { _, it -> "s-${it.bookId}-${it.path}" }) { i, ref ->
                    ArticleRow(
                        ref, state.books[ref.bookId], onOpen,
                        Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp).groupItem(i == 0, i == items.lastIndex),
                    ) {
                        BarIconButton(
                            OfflinkaIcons.BookmarkFilled,
                            stringResource(R.string.saved_remove),
                            { onRemoveSaved(ref) },
                            tint = if (state.books.containsKey(ref.bookId)) c.accent else c.ink3,
                        )
                    }
                }
            } else {
                val days = items.groupBy { dayIndex(it.time, state.now, state.timeZone) }
                days.entries.forEachIndexed { index, (day, refs) ->
                    item(key = "day-$day") {
                        SectionLabel(dayLabel(day, refs.first().time, state.timeZone), Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp)) {
                            if (index == 0) {
                                Text(
                                    stringResource(R.string.history_clear),
                                    style = Offlinka.type.subhead,
                                    color = c.link,
                                    modifier = Modifier
                                        .clickable(role = Role.Button, onClick = { confirmClear = true })
                                        .heightIn(min = 48.dp)
                                        .padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(refs, key = { _, it -> "h-$day-${it.time}-${it.bookId}-${it.path}" }) { i, ref ->
                        ArticleRow(
                            ref, state.books[ref.bookId], onOpen,
                            Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp).groupItem(i == 0, i == refs.lastIndex),
                        ) {
                            Text(
                                timeOf(ref.time, state.timeZone),
                                style = Offlinka.type.label,
                                color = c.ink2,
                                modifier = Modifier.padding(end = 14.dp),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title), style = Offlinka.type.title) },
            text = { Text(stringResource(R.string.history_clear_confirm_body), style = Offlinka.type.body) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearHistory() }) {
                    Text(stringResource(R.string.history_clear), style = Offlinka.type.label, color = c.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel), style = Offlinka.type.label, color = c.ink) }
            },
            containerColor = c.surface,
        )
    }
}

/** 0 = today, 1 = yesterday, n = n days ago (calendar days in [tz]). */
fun dayIndex(time: Long, now: Long, tz: TimeZone): Long {
    fun day(t: Long): Long {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = t }
        return (t + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 86_400_000L
    }
    return (day(now) - day(time)).coerceAtLeast(0)
}

@Composable
private fun dayLabel(day: Long, time: Long, tz: TimeZone): String {
    val locale = currentLocale()
    return when (day) {
        0L -> stringResource(R.string.history_today)
        1L -> stringResource(R.string.history_yesterday)
        else -> {
            val pattern = if (locale.language == "en") "EEEE, MMMM d" else "EEEE d. MMMM"
            runCatching { SimpleDateFormat(pattern, locale).apply { timeZone = tz }.format(Date(time)) }
                .getOrElse { DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(time)) }
        }
    }
}

@Composable
private fun timeOf(time: Long, tz: TimeZone): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, currentLocale()).apply { timeZone = tz }.format(Date(time))

@Composable
private fun ArticleRow(
    ref: ArticleRef,
    book: BookUi?,
    onOpen: (ArticleRef) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val c = Offlinka.colors
    val available = book != null
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = available, role = Role.Button, onClick = { onOpen(ref) })
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ref.title,
                style = Offlinka.type.subhead.copy(fontSize = Offlinka.type.title.fontSize),
                color = if (available) c.ink else c.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val tone = bookTone(book?.kind?.family ?: BookFamily.Travel, c.isDark)
                BookBadge(book?.icon ?: OfflinkaIcons.Article, tone.background, tone.foreground, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (book != null) bookTitle(book) else stringResource(R.string.saved_unavailable),
                    style = Offlinka.type.body,
                    color = if (available) c.ink2 else c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
