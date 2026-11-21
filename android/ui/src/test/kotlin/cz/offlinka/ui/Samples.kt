package cz.offlinka.ui

import cz.offlinka.ui.model.ArticleRef
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.DriveUi
import cz.offlinka.ui.model.Flavour
import cz.offlinka.ui.model.MapRegion
import cz.offlinka.ui.model.SearchGroup
import cz.offlinka.ui.model.SearchHit

/** The books of drive edition 2026-11.3, as the app sees them. */
object Samples {
    private fun book(
        id: String, kind: BookKind, lang: String, size: Long, date: String, articles: Long,
        file: String, flavour: Flavour = Flavour.MAXI, region: MapRegion? = null, title: String = id,
    ) = BookUi(
        id = id, kind = kind, language = lang, region = region, zimTitle = title, flavour = flavour,
        sizeBytes = size, date = date, articleCount = articles, fileName = file, folder = "Knihovna",
        hasIndex = true, driveName = "OFFLINKA", publisher = "Kiwix",
        zimDescription = if (kind == BookKind.WIKIPEDIA && lang == "cs") "Česká Wikipedie, všechny články s obrázky" else "",
    )

    val csWiki = book("wikipedia_cs_all", BookKind.WIKIPEDIA, "cs", 9_958_167_476, "2026-09", 975_525, "wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        .copy(zimDate = "2026-09-29", mediaCount = 1_072_582)
    val skWiki = book("wikipedia_sk_all", BookKind.WIKIPEDIA, "sk", 3_501_486_592, "2026-08", 251_402, "wikipedia_sk_all_maxi-offlinka_2026-08.zim")
    val enWiki = book("wikipedia_en_top1m", BookKind.WIKIPEDIA, "en", 17_134_155_148, "2026-04", 1_000_000, "wikipedia_en_top1m_nopic_2026-04.zim", Flavour.NOPIC)
    val medicine = book("wikipedia_en_medicine", BookKind.MEDICINE, "en", 1_047_708_415, "2026-10", 72_311, "wikipedia_en_medicine_nopic_2026-10.zim", Flavour.NOPIC)
    val mapCz = book("maps_en_czech-republic", BookKind.MAPS, "en", 1_065_265_251, "2026-06", 1, "maps_en_czech-republic_2026-06.zim", Flavour.OTHER, MapRegion.CZECHIA)
    val mapSk = book("maps_en_slovakia", BookKind.MAPS, "en", 539_516_304, "2026-06", 1, "maps_en_slovakia_2026-06.zim", Flavour.OTHER, MapRegion.SLOVAKIA)
    val guides = book("offlinka_cs_prirucky", BookKind.GUIDES, "cs", 393_612, "", 6, "offlinka_cs_prirucky.zim", Flavour.OTHER)
    val games = book("offlinka_mul_hry", BookKind.GAMES, "mul", 1_025_390, "", 3, "offlinka_mul_hry.zim", Flavour.OTHER)
    val wikibooks = book("wikibooks_cs_all", BookKind.WIKIBOOKS, "cs", 44_663_460, "2026-10", 1_204, "wikibooks_cs_all_maxi-offlinka_2026-10.zim")
    val wikiquote = book("wikiquote_cs_all", BookKind.WIKIQUOTE, "cs", 92_036_014, "2026-07", 3_911, "wikiquote_cs_all_maxi-offlinka_2026-07.zim")
    val wikisource = book("wikisource_cs_all", BookKind.WIKISOURCE, "cs", 512_000_000, "2026-08", 21_533, "wikisource_cs_all_maxi_2026-08.zim")
    val wikiversity = book("wikiversity_cs_all", BookKind.WIKIVERSITY, "cs", 61_000_000, "2026-08", 2_870, "wikiversity_cs_all_maxi_2026-08.zim")
    val wiktionary = book("wiktionary_cs_all", BookKind.WIKTIONARY, "cs", 1_311_000_000, "2026-07", 220_118, "wiktionary_cs_all_nopic_2026-07.zim", Flavour.NOPIC)
    val wikivoyage = book("wikivoyage_en_europe", BookKind.WIKIVOYAGE, "en", 980_000_000, "2026-09", 9_412, "wikivoyage_en_europe_maxi_2026-09.zim")

    val books = listOf(csWiki, skWiki, enWiki, medicine, mapCz, mapSk, guides, games, wikibooks, wikiquote, wikisource, wikiversity, wiktionary, wikivoyage)

    val phoneEditions = listOf(
        csWiki.copy(flavour = Flavour.MINI, fileName = "wikipedia_cs_all_mini-offlinka_2026-09.zim", folder = "Telefon", sizeBytes = 911_871_253),
        skWiki.copy(flavour = Flavour.MINI, fileName = "wikipedia_sk_all_mini-offlinka_2026-08.zim", folder = "Telefon", sizeBytes = 577_291_868),
    )

    /** 2026-11-21 10:00 UTC */
    const val NOW = 1_795_255_200_000L
    private const val HOUR = 3_600_000L

    val saved = listOf(
        ArticleRef("wikipedia_cs_all", "Sněžka", "Sněžka", NOW - 2 * HOUR),
        ArticleRef("offlinka_cs_prirucky", "prvni-pomoc.html", "První pomoc", NOW - 26 * HOUR),
        ArticleRef("wikipedia_cs_all", "Jan_Amos_Komenský", "Jan Amos Komenský", NOW - 80 * HOUR),
        ArticleRef("wikipedia_sk_all", "Vysoké_Tatry", "Vysoké Tatry", NOW - 200 * HOUR),
    )

    val history = listOf(
        ArticleRef("wikipedia_cs_all", "Český_Krumlov", "Český Krumlov", NOW - 1 * HOUR),
        ArticleRef("wikipedia_cs_all", "Vltava", "Vltava", NOW - 2 * HOUR),
        ArticleRef("offlinka_cs_prirucky", "vypadek-proudu.html", "Výpadek proudu", NOW - 25 * HOUR),
        ArticleRef("wikipedia_en_medicine", "Burn", "Burn", NOW - 27 * HOUR),
        ArticleRef("wikipedia_cs_all", "Sněžka", "Sněžka", NOW - 75 * HOUR),
        ArticleRef("wikipedia_old_book", "Pryč", "Článek z jiné flashky", NOW - 76 * HOUR),
    )

    val searchGroups = listOf(
        SearchGroup(
            csWiki,
            listOf(
                SearchHit(csWiki.id, "Český Krumlov", "Český_Krumlov"),
                SearchHit(csWiki.id, "Český Krumlov (zámek)", "Zámek_Český_Krumlov"),
                SearchHit(csWiki.id, "Okres Český Krumlov", "Okres_Český_Krumlov"),
                SearchHit(csWiki.id, "Krumlovský zámek", "Zámek_Český_Krumlov", "Zámek Český Krumlov"),
                SearchHit(csWiki.id, "Český Krumlov (železniční stanice)", "Český_Krumlov_(železniční_stanice)"),
                SearchHit(csWiki.id, "Česká Kanada", "Česká_Kanada"),
            ),
        ),
        SearchGroup(skWiki, listOf(SearchHit(skWiki.id, "Český Krumlov", "Český_Krumlov"))),
        SearchGroup(wikivoyage, listOf(SearchHit(wikivoyage.id, "Cesky Krumlov", "Cesky_Krumlov"))),
    )

    val drives = listOf(DriveUi("OFFLINKA", 14, true, edition = "2026-11", totalBytes = 34_299_000_000))
}
