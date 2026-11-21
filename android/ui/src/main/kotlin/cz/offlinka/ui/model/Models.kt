package cz.offlinka.ui.model

import androidx.compose.runtime.Immutable

/** What a book on the drive is, decided from its ZIM name (`wikipedia_cs_all`, `maps_en_slovakia`, …). */
enum class BookKind {
    WIKIPEDIA, MEDICINE, MAPS, GUIDES, GAMES, WIKIBOOKS, WIKIQUOTE, WIKISOURCE, WIKIVERSITY, WIKTIONARY, WIKIVOYAGE, OTHER;

    /** Books whose pages are MediaWiki output and get our reader stylesheet. */
    val isWiki: Boolean get() = this != MAPS && this != GUIDES && this != GAMES && this != OTHER
}

/** Map regions we know a nice name for. */
enum class MapRegion { CZECHIA, SLOVAKIA, OTHER }

enum class Flavour { MAXI, NOPIC, MINI, OTHER }

/** Whether a book of the drive is ready to read. */
enum class BookState { OPEN, OPENING, FAILED }

@Immutable
data class BookUi(
    /** Stable id used in URLs and saved items: the ZIM name, e.g. `wikipedia_cs_all`. */
    val id: String,
    val kind: BookKind,
    /** Two-letter language (`cs`, `sk`, `en`) or `mul`. */
    val language: String,
    val region: MapRegion? = null,
    /** Title and description from the ZIM metadata, used for books we have no own text for. */
    val zimTitle: String,
    val zimDescription: String = "",
    val flavour: Flavour = Flavour.OTHER,
    val topic: String = "all",
    val sizeBytes: Long = 0,
    /** `2026-09` style. */
    val date: String = "",
    /** Full date from the ZIM metadata, `2026-09-29`, when known. */
    val zimDate: String = "",
    val articleCount: Long = 0,
    val mediaCount: Long = 0,
    val fileName: String = "",
    val folder: String = "",
    val hasIndex: Boolean = false,
    val driveName: String? = null,
    val publisher: String = "",
    val creator: String = "",
    val state: BookState = BookState.OPEN,
)

@Immutable
data class SearchHit(
    val bookId: String,
    val title: String,
    val path: String,
    val snippet: String? = null,
)

@Immutable
data class SearchGroup(val book: BookUi, val hits: List<SearchHit>)

@Immutable
data class ArticleRef(
    val bookId: String,
    val path: String,
    val title: String,
    /** Epoch millis of saving / visiting. */
    val time: Long,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val tag: String) { SYSTEM(""), CS("cs"), SK("sk"), EN("en") }

@Immutable
data class DriveUi(
    /** The drive's tree URI, to forget it. */
    val id: String = "",
    val name: String,
    val bookCount: Int,
    val connected: Boolean,
    /** Edition of the drive content as year + month, `2026-11`, when the drive says. */
    val edition: String? = null,
    val totalBytes: Long = 0,
)

/** Quick links on the home screen into the guides book and Wikipedia. */
enum class QuickLink { FIRST_AID, PREPARE, RANDOM }

/** A section heading of the open article (for the "Obsah" sheet). */
@Immutable
data class TocEntry(val id: String, val text: String, val level: Int)
