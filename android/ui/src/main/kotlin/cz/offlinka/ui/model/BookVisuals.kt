package cz.offlinka.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import cz.offlinka.ui.R
import cz.offlinka.ui.theme.BookFamily
import cz.offlinka.ui.theme.OfflinkaIcons
import java.text.NumberFormat
import java.util.Locale

val BookKind.family: BookFamily
    get() = when (this) {
        BookKind.WIKIPEDIA -> BookFamily.Wiki
        BookKind.MEDICINE, BookKind.GUIDES -> BookFamily.Aid
        BookKind.MAPS -> BookFamily.Maps
        BookKind.GAMES -> BookFamily.Games
        BookKind.WIKIVOYAGE -> BookFamily.Travel
        BookKind.WIKIBOOKS, BookKind.WIKIQUOTE, BookKind.WIKISOURCE, BookKind.WIKIVERSITY,
        BookKind.WIKTIONARY, BookKind.OTHER -> BookFamily.Books
    }

val BookKind.icon: ImageVector
    @Composable get() = when (this) {
        BookKind.WIKIPEDIA -> OfflinkaIcons.BookWikipedia
        BookKind.MEDICINE -> OfflinkaIcons.BookMedicine
        BookKind.MAPS -> OfflinkaIcons.BookMaps
        BookKind.GUIDES -> OfflinkaIcons.BookGuides
        BookKind.GAMES -> OfflinkaIcons.BookGames
        BookKind.WIKIBOOKS -> OfflinkaIcons.BookWikibooks
        BookKind.WIKIQUOTE -> OfflinkaIcons.BookWikiquote
        BookKind.WIKISOURCE -> OfflinkaIcons.BookWikisource
        BookKind.WIKIVERSITY -> OfflinkaIcons.BookWikiversity
        BookKind.WIKTIONARY -> OfflinkaIcons.BookWiktionary
        BookKind.WIKIVOYAGE -> OfflinkaIcons.BookWikivoyage
        BookKind.OTHER -> OfflinkaIcons.Article
    }

/** Icon for a concrete book (English Wikipedia has its own). */
val BookUi.icon: ImageVector
    @Composable get() = if (kind == BookKind.WIKIPEDIA && language == "en") OfflinkaIcons.BookWikipediaEn else kind.icon

@Composable
@ReadOnlyComposable
fun bookTitle(book: BookUi): String = when (book.kind) {
    BookKind.WIKIPEDIA -> when (book.language) {
        "cs" -> stringResource(R.string.book_wikipedia_cs)
        "sk" -> stringResource(R.string.book_wikipedia_sk)
        "en" -> stringResource(R.string.book_wikipedia_en)
        else -> book.zimTitle
    }
    BookKind.MEDICINE -> stringResource(R.string.book_medicine)
    BookKind.MAPS -> when (book.region) {
        MapRegion.CZECHIA -> stringResource(R.string.book_maps_cz)
        MapRegion.SLOVAKIA -> stringResource(R.string.book_maps_sk)
        else -> book.zimTitle
    }
    BookKind.GUIDES -> stringResource(R.string.book_guides)
    BookKind.GAMES -> stringResource(R.string.book_games)
    BookKind.WIKIBOOKS -> stringResource(R.string.book_wikibooks)
    BookKind.WIKIQUOTE -> stringResource(R.string.book_wikiquote)
    BookKind.WIKISOURCE -> stringResource(R.string.book_wikisource)
    BookKind.WIKIVERSITY -> stringResource(R.string.book_wikiversity)
    BookKind.WIKTIONARY -> stringResource(R.string.book_wiktionary)
    BookKind.WIKIVOYAGE -> stringResource(R.string.book_wikivoyage)
    BookKind.OTHER -> book.zimTitle
}

@Composable
@ReadOnlyComposable
fun bookDescription(book: BookUi): String = when (book.kind) {
    BookKind.WIKIPEDIA -> when {
        book.flavour == Flavour.MINI -> stringResource(R.string.book_wikipedia_mini_desc)
        book.language == "en" -> stringResource(R.string.book_wikipedia_en_desc)
        else -> stringResource(R.string.book_wikipedia_full_desc)
    }
    BookKind.MEDICINE -> stringResource(R.string.book_medicine_desc)
    BookKind.MAPS -> stringResource(R.string.book_maps_desc)
    BookKind.GUIDES -> stringResource(R.string.book_guides_desc)
    BookKind.GAMES -> stringResource(R.string.book_games_desc)
    BookKind.WIKIBOOKS -> stringResource(R.string.book_wikibooks_desc)
    BookKind.WIKIQUOTE -> stringResource(R.string.book_wikiquote_desc)
    BookKind.WIKISOURCE -> stringResource(R.string.book_wikisource_desc)
    BookKind.WIKIVERSITY -> stringResource(R.string.book_wikiversity_desc)
    BookKind.WIKTIONARY -> stringResource(R.string.book_wiktionary_desc)
    BookKind.WIKIVOYAGE -> stringResource(R.string.book_wikivoyage_desc)
    BookKind.OTHER -> book.zimDescription.ifBlank { stringResource(R.string.book_other_desc) }
}

@Composable
@ReadOnlyComposable
fun languageName(code: String): String = when (code) {
    "cs" -> stringResource(R.string.lang_cs)
    "sk" -> stringResource(R.string.lang_sk)
    "en" -> stringResource(R.string.lang_en)
    "mul", "" -> stringResource(R.string.lang_mul)
    else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)).ifBlank { code }
}

/** "9,96 GB", "17,1 GB", "412 MB", "380 kB" in the given locale (decimal comma in cs/sk). */
fun formatSize(bytes: Long, locale: Locale): String {
    val nf = NumberFormat.getNumberInstance(locale)
    return when {
        bytes >= 1_000_000_000L -> {
            val gb = bytes / 1e9
            nf.maximumFractionDigits = if (gb < 10) 2 else 1
            nf.format(gb) + " GB"
        }
        bytes >= 1_000_000L -> { nf.maximumFractionDigits = 0; nf.format(bytes / 1e6) + " MB" }
        else -> { nf.maximumFractionDigits = 0; nf.format(maxOf(1.0, bytes / 1e3)) + " kB" }
    }
}

/** "2 104 553" with the locale's grouping. */
fun formatCount(n: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(n)

/** "2026-09" → "září 2026" / "September 2026". */
fun formatEditionDate(date: String, locale: Locale): String {
    val m = Regex("""^(\d{4})-(\d{2})""").find(date) ?: return date
    val (y, mo) = m.destructured
    return runCatching {
        java.time.YearMonth.of(y.toInt(), mo.toInt()).format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale))
    }.getOrDefault(date)
}

/** "2026-09-29" → "29. září 2026" / "September 29, 2026"; falls back to [formatEditionDate]. */
fun formatFullDate(date: String, locale: Locale): String = runCatching {
    java.time.LocalDate.parse(date.take(10)).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale))
}.getOrElse { formatEditionDate(date, locale) }
