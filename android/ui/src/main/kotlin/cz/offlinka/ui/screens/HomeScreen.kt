package cz.offlinka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.BookBadge
import cz.offlinka.ui.components.HeroBox
import cz.offlinka.ui.components.InkCard
import cz.offlinka.ui.components.OfflinkaLogo
import cz.offlinka.ui.components.Peak
import cz.offlinka.ui.components.Ridge
import cz.offlinka.ui.components.Tag
import cz.offlinka.ui.model.ArticleRef
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.QuickLink
import cz.offlinka.ui.model.bookDescription
import cz.offlinka.ui.model.bookTitle
import cz.offlinka.ui.model.family
import cz.offlinka.ui.model.icon
import cz.offlinka.ui.theme.BookFamily
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.bookTone

data class HomeState(
    val books: List<BookUi>,
    val continueReading: ArticleRef? = null,
    val continueBook: BookUi? = null,
    val hasGuides: Boolean = true,
    val hasRandom: Boolean = true,
    /** Accent-free search index being built on the phone, percent (a calm line, never blocking). */
    val indexProgress: Int? = null,
)

@Composable
fun HomeScreen(
    state: HomeState,
    onSearch: () -> Unit,
    onOpenBook: (BookUi) -> Unit,
    onQuickLink: (QuickLink) -> Unit,
    onContinue: (ArticleRef) -> Unit,
    modifier: Modifier = Modifier,
    onRetryBook: (BookUi) -> Unit = {},
) {
    val c = Offlinka.colors
    var failed by remember { mutableStateOf<BookUi?>(null) }
    val open: (BookUi) -> Unit = { b ->
        when (b.state) {
            BookState.OPEN -> onOpenBook(b)
            BookState.FAILED -> failed = b
            BookState.OPENING -> Unit
        }
    }
    failed?.let { b -> RetryBookDialog(b, onRetry = { failed = null; onRetryBook(b) }, onDismiss = { failed = null }) }
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        HeroBox(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                Column(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 720.dp)
                        .padding(horizontal = 16.dp)
                        .padding(top = 18.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OfflinkaLogo(Modifier.height(30.dp), onDark = true)
                        Spacer(Modifier.weight(1f))
                        OfflineBadge()
                    }
                    Spacer(Modifier.height(30.dp))
                    Text(
                        stringResource(R.string.home_title),
                        style = Offlinka.type.display,
                        color = c.onHero,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(18.dp))
                    SearchLauncher(onSearch)
                }
                QuickLinks(state, onQuickLink)
                Ridge()
            }
        }

        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp),
        ) {
            val cont = state.continueReading
            if (cont != null) {
                Spacer(Modifier.height(10.dp))
                ContinueCard(cont, state.continueBook, onContinue)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Peak()
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.home_books_title),
                    style = Offlinka.type.title,
                    color = c.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(
                    pluralStringResource(R.plurals.library_books_count, state.books.size, state.books.size),
                    style = Offlinka.type.label,
                    color = c.ink2,
                )
            }
            BookGrid(state.books, open)
            state.indexProgress?.let { p ->
                Text(
                    stringResource(R.string.search_index_building, p),
                    style = Offlinka.type.label,
                    color = c.ink2,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun OfflineBadge() {
    val c = Offlinka.colors
    Row(
        Modifier
            .border(1.5.dp, c.heroLine, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(c.ok))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.status_offline), style = Offlinka.type.label, color = c.onHero2)
    }
}

/** Looks like a search field; opens the search screen. */
@Composable
private fun SearchLauncher(onSearch: () -> Unit) {
    val c = Offlinka.colors
    InkCard(
        onClick = onSearch,
        onClickLabel = stringResource(R.string.cd_search),
        shadow = Offlinka.elevation.raised,
        border = c.outline,
        borderWidth = 2.dp,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Offlinka.size.searchField).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(OfflinkaIcons.Search, null, tint = c.ink2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.home_search_placeholder),
                style = Offlinka.type.searchInput.copy(fontWeight = FontWeight.Normal),
                color = c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickLinks(state: HomeState, onQuickLink: (QuickLink) -> Unit) {
    val links = buildList {
        if (state.hasGuides) {
            add(QuickLink.FIRST_AID)
            add(QuickLink.PREPARE)
        }
        if (state.hasRandom) add(QuickLink.RANDOM)
    }
    if (links.isEmpty()) {
        Spacer(Modifier.height(18.dp))
        return
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 18.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        links.forEach { link ->
            when (link) {
                QuickLink.FIRST_AID -> QuickChip(stringResource(R.string.home_quick_first_aid), OfflinkaIcons.FirstAid) { onQuickLink(link) }
                QuickLink.PREPARE -> QuickChip(stringResource(R.string.home_quick_prepare), OfflinkaIcons.Checklist) { onQuickLink(link) }
                QuickLink.RANDOM -> QuickChip(stringResource(R.string.home_quick_random), OfflinkaIcons.Random) { onQuickLink(link) }
            }
        }
    }
}

/** Quick chip on the forest hero: 40 dp, 15 sp, tinted fill and a 3:1 outline. */
@Composable
private fun QuickChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = Offlinka.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .clip(shape)
            .background(c.heroDeep.copy(alpha = 0.35f))
            .border(1.5.dp, c.onHero2.copy(alpha = 0.5f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.onHero2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Offlinka.type.label.copy(fontSize = 15.sp), color = c.onHero)
    }
}

@Composable
private fun ContinueCard(ref: ArticleRef, book: BookUi?, onContinue: (ArticleRef) -> Unit) {
    val c = Offlinka.colors
    val tone = bookTone(book?.kind?.family ?: BookFamily.Wiki, c.isDark)
    InkCard(
        onClick = { onContinue(ref) },
        modifier = Modifier.fillMaxWidth(),
        shadow = null,
        border = c.line,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookBadge(book?.icon ?: OfflinkaIcons.Article, tone.background, tone.foreground, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_continue), style = Offlinka.type.label, color = c.ink2)
                Text(
                    ref.title,
                    style = Offlinka.type.subhead.copy(fontSize = Offlinka.type.title.fontSize),
                    color = c.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(OfflinkaIcons.ChevronRight, null, tint = c.ink2)
        }
    }
}

/** Czech Wikipedia (the first book) full width, the rest in a grid. */
@Composable
private fun BookGrid(books: List<BookUi>, onOpenBook: (BookUi) -> Unit) {
    if (books.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 600.dp -> 3
            maxWidth >= 320.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val first = books.first()
            val wide = first.kind == BookKind.WIKIPEDIA
            if (wide) WideBookTile(first, onOpenBook)
            (if (wide) books.drop(1) else books).chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { book -> BookTile(book, Modifier.weight(1f).fillMaxHeight(), onOpenBook) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** "CZ", "SK", "EN" on the Wikipedia tiles, and "EN" on the other English books (Medicína, Wikivoyage). */
private fun langTag(book: BookUi): String? = when {
    book.kind == BookKind.WIKIPEDIA -> if (book.language == "cs") "CZ" else book.language.uppercase()
    book.language == "en" && book.kind != BookKind.MAPS && book.kind != BookKind.GAMES -> "EN"
    else -> null
}

/** Opening / failed books: dimmed, with a short state line instead of the description. */
@Composable
private fun stateLine(book: BookUi): String? = when (book.state) {
    BookState.OPENING -> stringResource(R.string.library_opening)
    BookState.FAILED -> stringResource(R.string.library_open_failed)
    BookState.OPEN -> null
}

@Composable
private fun WideBookTile(book: BookUi, onOpenBook: (BookUi) -> Unit) {
    val c = Offlinka.colors
    val tone = bookTone(book.kind.family, c.isDark)
    val title = bookTitle(book)
    InkCard(
        onClick = { onOpenBook(book) },
        onClickLabel = stringResource(R.string.cd_open_book, title),
        modifier = Modifier.fillMaxWidth().alpha(if (book.state == BookState.OPEN) 1f else 0.62f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookBadge(book.icon, tone.background, tone.foreground)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Offlinka.type.title.copy(fontSize = 21.sp, lineHeight = 26.sp), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stateLine(book) ?: bookDescription(book), style = Offlinka.type.body, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            langTag(book)?.let {
                Spacer(Modifier.width(8.dp))
                Tag(it)
            }
        }
    }
}

@Composable
private fun BookTile(book: BookUi, modifier: Modifier, onOpenBook: (BookUi) -> Unit) {
    val c = Offlinka.colors
    val tone = bookTone(book.kind.family, c.isDark)
    val title = bookTitle(book)
    InkCard(
        onClick = { onOpenBook(book) },
        onClickLabel = stringResource(R.string.cd_open_book, title),
        modifier = modifier.alpha(if (book.state == BookState.OPEN) 1f else 0.62f),
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            BookBadge(book.icon, tone.background, tone.foreground)
            Spacer(Modifier.weight(1f))
            langTag(book)?.let { Tag(it) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = Offlinka.type.title, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(stateLine(book) ?: bookDescription(book), style = Offlinka.type.bodySmall, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A book that could not be opened: the calm error and "Zkusit znovu". */
@Composable
internal fun RetryBookDialog(book: BookUi, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val c = Offlinka.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(bookTitle(book), style = Offlinka.type.title) },
        text = { Text(stringResource(R.string.error_body), style = Offlinka.type.body) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry), style = Offlinka.type.label, color = c.link) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), style = Offlinka.type.label, color = c.ink) }
        },
        containerColor = c.surface,
    )
}
