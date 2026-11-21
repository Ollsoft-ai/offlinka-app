package cz.offlinka.app.drive

import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.Flavour
import cz.offlinka.ui.model.MapRegion

/** Where a file sits on the drive. Order = preference when the same book is in several places. */
enum class DriveFolder(val label: String) {
    LIBRARY("Knihovna"),
    PICKED("."),
    PHONE("Telefon"),
}

/** A file found on a drive (no I/O here; [handle] is whatever the drive layer needs to open it). */
data class DriveFile(
    val name: String,
    val folder: DriveFolder,
    val size: Long,
    val handle: String,
    val driveIndex: Int = 0,
)

/**
 * What a ZIM file name says, Kiwix naming: `<project>_<lang>_<topic>[_<flavour>[-tag]][_<yyyy-mm>].zim`,
 * e.g. `wikipedia_cs_all_maxi-offlinka_2026-09.zim`, `maps_en_czech-republic_2026-06.zim`,
 * `offlinka_cs_prirucky.zim`.
 */
data class ZimName(
    val project: String,
    val language: String,
    val topic: String,
    val flavour: Flavour,
    val date: String,
) {
    /** Stable book id shared by every edition and flavour of a book: `wikipedia_cs_all`. */
    val key: String get() = listOf(project, language, topic).filter { it.isNotEmpty() }.joinToString("_")

    val kind: BookKind
        get() = when (project) {
            "wikipedia" -> if (topic == "medicine") BookKind.MEDICINE else BookKind.WIKIPEDIA
            "maps", "osm" -> BookKind.MAPS
            "offlinka" -> when (topic) {
                "prirucky", "guides" -> BookKind.GUIDES
                "hry", "games" -> BookKind.GAMES
                else -> BookKind.OTHER
            }
            "wikibooks" -> BookKind.WIKIBOOKS
            "wikiquote" -> BookKind.WIKIQUOTE
            "wikisource" -> BookKind.WIKISOURCE
            "wikiversity" -> BookKind.WIKIVERSITY
            "wiktionary" -> BookKind.WIKTIONARY
            "wikivoyage" -> BookKind.WIKIVOYAGE
            else -> BookKind.OTHER
        }

    val region: MapRegion?
        get() = if (kind != BookKind.MAPS) null else when (topic) {
            "czech-republic", "czechia", "cz" -> MapRegion.CZECHIA
            "slovakia", "sk" -> MapRegion.SLOVAKIA
            else -> MapRegion.OTHER
        }

    companion object {
        private val pattern = Regex(
            """^([a-z0-9]+)_([a-z]{2,3}(?:-[a-z]{2,4})?)_(.+?)(?:_(maxi|mini|nopic)(?:-[a-z0-9]+)?)?(?:_(\d{4}-\d{2}))?$""",
        )

        fun parse(fileName: String): ZimName {
            val base = fileName.substringAfterLast('/').removeSuffix(".zim").removeSuffix(".ZIM")
            val m = pattern.matchEntire(base.lowercase())
            if (m == null) {
                val key = base.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifEmpty { "book" }
                return ZimName(key, "", "", Flavour.OTHER, "")
            }
            val (project, lang, topic, flavour, date) = m.destructured
            return ZimName(
                project = project,
                language = lang,
                topic = topic,
                flavour = when (flavour) {
                    "maxi" -> Flavour.MAXI
                    "nopic" -> Flavour.NOPIC
                    "mini" -> Flavour.MINI
                    else -> Flavour.OTHER
                },
                date = date,
            )
        }
    }
}

/** A ZIM chosen to open, with the candidates it beat and its search index if one sits next to it. */
data class BookChoice(
    val key: String,
    val zim: DriveFile,
    val name: ZimName,
    val index: DriveFile?,
    /** Other files of the same book in preference order: tried when [zim] fails to open. */
    val fallbacks: List<DriveFile>,
)

object BookSelector {
    /** Two-letter language for the UI from the file name (`ces` → `cs`). */
    fun uiLanguage(code: String): String = when (code.substringBefore('-')) {
        "ces", "cze" -> "cs"
        "slk", "slo" -> "sk"
        "eng" -> "en"
        else -> code.substringBefore('-')
    }

    private fun flavourRank(f: Flavour) = when (f) {
        Flavour.MAXI -> 0
        Flavour.OTHER -> 1
        Flavour.NOPIC -> 1
        Flavour.MINI -> 2
    }

    /**
     * Picks one file per book: the big edition (maxi in Knihovna) wins over the phone one (mini in Telefon),
     * then the newer date, then the first drive. Index files (`<zim basename>.offidx`) are matched in the same
     * folder of the same drive. The result is in home-screen order for [uiLanguage].
     */
    fun select(files: List<DriveFile>, uiLanguage: String = "cs"): List<BookChoice> {
        val zims = files.filter { it.name.endsWith(".zim", ignoreCase = true) }
        val indexes = files.filter { it.name.endsWith(".offidx", ignoreCase = true) }
            .associateBy { Triple(it.driveIndex, it.folder, it.name.substringBeforeLast('.').lowercase()) }
        return zims
            .map { it to ZimName.parse(it.name) }
            .groupBy { it.second.key }
            .map { (key, group) ->
                val sorted = group.sortedWith(
                    compareBy<Pair<DriveFile, ZimName>> { flavourRank(it.second.flavour) }
                        .thenBy { it.first.folder.ordinal }
                        .thenByDescending { it.second.date }
                        .thenBy { it.first.driveIndex }
                        .thenBy { it.first.name },
                )
                val (best, name) = sorted.first()
                BookChoice(
                    key = key,
                    zim = best,
                    name = name,
                    index = indexFor(best, indexes),
                    fallbacks = sorted.drop(1).map { it.first },
                )
            }
            .sortedWith(compareBy<BookChoice> { homeOrder(it.name, uiLanguage) }.thenBy { it.key })
    }

    fun indexFor(zim: DriveFile, indexes: Map<Triple<Int, DriveFolder, String>, DriveFile>): DriveFile? =
        indexes[Triple(zim.driveIndex, zim.folder, zim.name.substringBeforeLast('.').lowercase())]

    /**
     * Home screen order (design README "Tile order"): the UI-language Wikipedia, Mapa Česka, Nouzové
     * příručky, the other Wikipedia, English Wikipedia, Medicína, Mapa Slovenska, Hry, Wikivoyage,
     * Wikiknihy, Wikizdroje, Wikicitáty, Wikislovník, Wikiverzita, then unknown books.
     */
    fun homeOrder(n: ZimName, ui: String = "cs"): Int = when (n.kind) {
        BookKind.WIKIPEDIA -> when (uiLanguage(n.language)) {
            ui -> 0
            "cs" -> 30
            "sk" -> 31
            "en" -> 40
            else -> 45
        }
        BookKind.MAPS -> when (n.region) {
            MapRegion.CZECHIA -> 10
            MapRegion.SLOVAKIA -> 60
            else -> 65
        }
        BookKind.GUIDES -> 20
        BookKind.MEDICINE -> 50
        BookKind.GAMES -> 70
        BookKind.WIKIVOYAGE -> 80
        BookKind.WIKIBOOKS -> 90
        BookKind.WIKISOURCE -> 100
        BookKind.WIKIQUOTE -> 110
        BookKind.WIKTIONARY -> 120
        BookKind.WIKIVERSITY -> 130
        BookKind.OTHER -> 200
    }

    /**
     * Order of the automatic on-device search indexes, most useful first. Null = never automatic: English
     * top1m (6–11 min, ~460 MB RAM to build) is built only when asked for in its details.
     */
    fun indexPriority(n: ZimName, ui: String): Int? = when {
        onDemandIndex(n) -> null
        n.kind == BookKind.WIKIPEDIA -> if (uiLanguage(n.language) == ui) 0 else 1
        n.kind == BookKind.GUIDES -> 2
        n.kind == BookKind.MAPS || n.kind == BookKind.GAMES || n.kind == BookKind.OTHER -> 4
        else -> 3
    }

    /** Too heavy to index unasked (English top1m). */
    fun onDemandIndex(n: ZimName): Boolean = n.kind == BookKind.WIKIPEDIA && n.topic == "top1m"
}
