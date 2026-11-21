package cz.offlinka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.BarIconButton
import cz.offlinka.ui.components.BookBadge
import cz.offlinka.ui.components.GroupCard
import cz.offlinka.ui.components.GroupDivider
import cz.offlinka.ui.components.InkCard
import cz.offlinka.ui.components.OutlineButton
import cz.offlinka.ui.components.PrimaryButton
import cz.offlinka.ui.components.ScreenHeader
import cz.offlinka.ui.components.SectionLabel
import cz.offlinka.ui.components.SheetOverlay
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.DriveUi
import cz.offlinka.ui.model.bookDescription
import cz.offlinka.ui.model.bookTitle
import cz.offlinka.ui.model.family
import cz.offlinka.ui.model.formatCount
import cz.offlinka.ui.model.formatEditionDate
import cz.offlinka.ui.model.formatFullDate
import cz.offlinka.ui.model.formatSize
import cz.offlinka.ui.model.icon
import cz.offlinka.ui.model.languageName
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.bookTone
import java.util.Locale

data class LibraryState(
    val books: List<BookUi>,
    /** Phone editions that a bigger edition replaces (shown greyed, for information). */
    val replaced: List<BookUi> = emptyList(),
    val drive: DriveUi? = null,
)

@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

private enum class Group(val title: Int) {
    WIKI(R.string.library_group_wiki),
    MAPS(R.string.library_group_maps),
    AID(R.string.library_group_aid),
    BOOKS(R.string.library_group_books),
    OTHER(R.string.library_group_other),
}

private fun BookUi.group(): Group = when (kind) {
    BookKind.WIKIPEDIA -> Group.WIKI
    BookKind.MAPS -> Group.MAPS
    BookKind.MEDICINE, BookKind.GUIDES -> Group.AID
    BookKind.WIKIBOOKS, BookKind.WIKIQUOTE, BookKind.WIKISOURCE, BookKind.WIKIVERSITY, BookKind.WIKTIONARY -> Group.BOOKS
    BookKind.WIKIVOYAGE, BookKind.GAMES, BookKind.OTHER -> Group.OTHER
}

@Composable
fun LibraryScreen(
    state: LibraryState,
    onOpen: (BookUi) -> Unit,
    onDetails: (BookUi) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (BookUi) -> Unit = {},
) {
    val c = Offlinka.colors
    val locale = currentLocale()
    var failed by remember { mutableStateOf<BookUi?>(null) }
    failed?.let { b -> RetryBookDialog(b, onRetry = { failed = null; onRetry(b) }, onDismiss = { failed = null }) }
    val open: (BookUi) -> Unit = { b ->
        when (b.state) {
            BookState.OPEN -> onOpen(b)
            BookState.FAILED -> failed = b
            BookState.OPENING -> Unit
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(stringResource(R.string.library_title), subtitle = stringResource(R.string.library_subtitle))
        Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp)) {
            state.drive?.let {
                Spacer(Modifier.height(18.dp))
                DriveCard(it, state.books.size, locale)
            }
            val groups = state.books.groupBy { it.group() }
            Group.entries.forEach { g ->
                val books = groups[g] ?: return@forEach
                SectionLabel(stringResource(g.title))
                GroupCard {
                    books.forEachIndexed { i, book ->
                        if (i > 0) GroupDivider()
                        LibraryRow(book, locale, onOpen = { open(book) }, onDetails = { onDetails(book) })
                    }
                }
            }
            if (state.replaced.isNotEmpty()) {
                SectionLabel(stringResource(R.string.library_group_phone))
                Text(
                    stringResource(R.string.library_group_phone_note),
                    style = Offlinka.type.bodySmall,
                    color = c.ink2,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                GroupCard(background = c.bgSunken) {
                    state.replaced.forEachIndexed { i, book ->
                        if (i > 0) GroupDivider()
                        LibraryRow(book, locale, onOpen = null, onDetails = { onDetails(book) }, replaced = true)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DriveCard(drive: DriveUi, books: Int, locale: Locale) {
    val c = Offlinka.colors
    InkCard(
        modifier = Modifier.fillMaxWidth(),
        background = c.heroDeep,
        border = c.outline,
        shadow = Offlinka.elevation.card,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.5.dp, c.heroLine, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(OfflinkaIcons.Drive, null, tint = c.onHero, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(drive.name, style = Offlinka.type.title, color = c.onHero2)
                drive.edition?.let {
                    Text(stringResource(R.string.library_drive_edition, formatEditionDate(it, locale)), style = Offlinka.type.body, color = c.onHero2)
                }
                val count = pluralStringResource(R.plurals.library_books_count, books, books)
                Text(
                    if (drive.totalBytes > 0) "$count, ${formatSize(drive.totalBytes, locale)}" else count,
                    style = Offlinka.type.body,
                    color = c.onHero2,
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(
    book: BookUi,
    locale: Locale,
    onOpen: (() -> Unit)?,
    onDetails: () -> Unit,
    replaced: Boolean = false,
) {
    val c = Offlinka.colors
    val tone = bookTone(book.kind.family, c.isDark)
    val title = bookTitle(book)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (book.state == BookState.OPEN) 1f else 0.62f)
            .clickable(
                role = Role.Button,
                onClickLabel = if (onOpen != null) stringResource(R.string.cd_open_book, title) else stringResource(R.string.library_details),
                onClick = onOpen ?: onDetails,
            )
            .padding(start = 14.dp, top = 16.dp, bottom = 16.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookBadge(book.icon, tone.background, tone.foreground, size = Offlinka.size.bookIconBoxSmall)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Offlinka.type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    replaced -> stringResource(R.string.library_replaced)
                    book.state == BookState.OPENING -> stringResource(R.string.library_opening)
                    book.state == BookState.FAILED -> stringResource(R.string.library_open_failed)
                    else -> bookDescription(book)
                },
                style = Offlinka.type.bodySmall,
                color = c.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(meta(book, locale), style = Offlinka.type.caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BarIconButton(OfflinkaIcons.Info, stringResource(R.string.library_details), onDetails, tint = c.ink2)
    }
}

/** "září 2026, čeština, 9,96 GB": what changes between editions first. */
@Composable
private fun meta(book: BookUi, locale: Locale): String = buildList {
    if (book.date.isNotBlank()) add(formatEditionDate(book.date, locale))
    if (book.kind != BookKind.MAPS && book.language != "mul") add(languageName(book.language))
    if (book.sizeBytes > 0) add(formatSize(book.sizeBytes, locale))
}.joinToString(", ")

data class BookDetailsState(
    val book: BookUi,
    val illustration: ImageBitmap? = null,
    val replaced: Boolean = false,
    /** "Pro technickou podporu" unfolded (screenshots). */
    val supportOpen: Boolean = false,
    /** A heavy book (English top1m) whose accent-free search the user may start here, with a warning. */
    val canBuildIndex: Boolean = false,
    /** Its index is being built right now, percent. */
    val indexProgress: Int? = null,
)

/** Book details as a sheet over whatever is below (library or reader). */
@Composable
fun BookDetailsSheet(
    state: BookDetailsState,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onBuildIndex: () -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        SheetOverlay(onDismiss = onDismiss, dismissLabel = stringResource(R.string.action_close)) {
            BookDetailsContent(state, onOpen, onBuildIndex)
        }
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    val c = Offlinka.colors
    GroupDivider()
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = Offlinka.type.body, color = c.ink2, modifier = Modifier.weight(0.45f))
        Text(
            v,
            style = if (v.length > 24) Offlinka.type.bodySmall else Offlinka.type.subhead,
            color = c.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun ColumnScope.BookDetailsContent(state: BookDetailsState, onOpen: () -> Unit, onBuildIndex: () -> Unit) {
    val c = Offlinka.colors
    var supportOpen by rememberSaveable { mutableStateOf(state.supportOpen) }
    val book = state.book
    val locale = currentLocale()
    val tone = bookTone(book.kind.family, c.isDark)
    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookBadge(book.icon, tone.background, tone.foreground, size = 56.dp, illustration = state.illustration)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(bookTitle(book), style = Offlinka.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(
                    if (state.replaced) stringResource(R.string.library_replaced) else bookDescription(book),
                    style = Offlinka.type.body,
                    color = c.ink2,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val rows = buildList {
            if (book.articleCount > 0 && book.kind != BookKind.MAPS) {
                add(stringResource(R.string.library_detail_articles) to formatCount(book.articleCount, locale))
            }
            if (book.mediaCount > 0) add(stringResource(R.string.library_detail_media) to formatCount(book.mediaCount, locale))
            if (book.sizeBytes > 0) add(stringResource(R.string.library_detail_size) to formatSize(book.sizeBytes, locale))
            val date = book.zimDate.ifBlank { book.date }
            if (date.isNotBlank()) add(stringResource(R.string.library_detail_date) to formatFullDate(date, locale))
            add(stringResource(R.string.library_detail_language) to languageName(book.language))
        }
        rows.forEach { (k, v) -> DetailRow(k, v) }
        if (!book.hasIndex && (state.canBuildIndex || state.indexProgress != null)) {
            GroupDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Text(stringResource(R.string.library_index_offer_title), style = Offlinka.type.subhead, color = c.ink)
                Spacer(Modifier.height(4.dp))
                val progress = state.indexProgress
                if (progress != null) {
                    Text(stringResource(R.string.search_index_building, progress), style = Offlinka.type.body, color = c.ink2)
                } else {
                    Text(stringResource(R.string.library_index_offer_body), style = Offlinka.type.body, color = c.ink2)
                    Spacer(Modifier.height(12.dp))
                    OutlineButton(stringResource(R.string.library_index_offer_button), onBuildIndex, Modifier.fillMaxWidth())
                }
            }
        }
        // Things only support needs, folded away.
        val support = buildList {
            if (book.kind.isWiki || book.kind == BookKind.GUIDES) {
                add(
                    stringResource(R.string.library_detail_search) to stringResource(
                        if (book.hasIndex) R.string.library_search_indexed else R.string.library_search_basic,
                    ),
                )
            }
            if (book.folder.isNotBlank()) add(stringResource(R.string.library_detail_folder) to book.folder)
            if (book.fileName.isNotBlank()) add(stringResource(R.string.library_detail_file) to book.fileName)
        }
        if (support.isNotEmpty()) {
            GroupDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { supportOpen = !supportOpen }
                    .heightIn(min = 50.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.library_support), style = Offlinka.type.body, color = c.ink2, modifier = Modifier.weight(1f))
                Icon(if (supportOpen) OfflinkaIcons.ChevronUp else OfflinkaIcons.ChevronDown, null, tint = c.ink2)
            }
            if (supportOpen) support.forEach { (k, v) -> DetailRow(k, v) }
        }
    }
    if (!state.replaced) {
        Spacer(Modifier.height(18.dp))
        PrimaryButton(stringResource(R.string.library_open_main), onOpen, Modifier.fillMaxWidth())
    }
}
