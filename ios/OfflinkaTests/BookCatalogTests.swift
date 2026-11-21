import XCTest
@testable import Offlinka

final class BookCatalogTests: XCTestCase {
    func testKindsFromZimNames() {
        XCTAssertEqual(BookKind.of(name: "wikipedia_cs_all"), .wikipediaCs)
        XCTAssertEqual(BookKind.of(name: "wikipedia_sk_all"), .wikipediaSk)
        XCTAssertEqual(BookKind.of(name: "wikipedia_en_top1m"), .wikipediaEn)
        XCTAssertEqual(BookKind.of(name: "wikipedia_en_medicine"), .medicine)
        XCTAssertEqual(BookKind.of(name: "wikipedia_de_all"), .wikipediaOther)
        XCTAssertEqual(BookKind.of(name: "maps_en_czech-republic"), .mapsCz)
        XCTAssertEqual(BookKind.of(name: "maps_en_slovakia"), .mapsSk)
        XCTAssertEqual(BookKind.of(name: "maps_en_austria"), .maps)
        XCTAssertEqual(BookKind.of(name: "offlinka_cs_prirucky"), .guides)
        XCTAssertEqual(BookKind.of(name: "offlinka_mul_hry"), .games)
        XCTAssertEqual(BookKind.of(name: "wikibooks_cs_all"), .wikibooks)
        XCTAssertEqual(BookKind.of(name: "wikiquote_cs_all"), .wikiquote)
        XCTAssertEqual(BookKind.of(name: "wikisource_cs_all"), .wikisource)
        XCTAssertEqual(BookKind.of(name: "wikiversity_cs_all"), .wikiversity)
        XCTAssertEqual(BookKind.of(name: "wiktionary_cs_all"), .wiktionary)
        XCTAssertEqual(BookKind.of(name: "wikivoyage_en_europe"), .wikivoyage)
        XCTAssertEqual(BookKind.of(name: "gutenberg_cs_all"), .other)
        // A ZIM without a Name: the core falls back to the file stem.
        XCTAssertEqual(BookKind.of(name: "wikipedia_cs_all_maxi-offlinka_2026-09"), .wikipediaCs)
    }

    func testReaderStyleOnlyForWikimedia() {
        XCTAssertTrue(BookKind.wikipediaCs.usesReaderStyle)
        XCTAssertTrue(BookKind.wiktionary.usesReaderStyle)
        XCTAssertFalse(BookKind.mapsCz.usesReaderStyle)
        XCTAssertFalse(BookKind.games.usesReaderStyle)
        XCTAssertFalse(BookKind.guides.usesReaderStyle)
    }

    func testHomeOrderFollowsDesignForTheUILanguage() {
        let records = FakeContentSource.demo().books
        XCTAssertEqual(BookCatalog.sorted(records, uiLanguage: "cs").map(\.name),
                       ["wikipedia_cs_all", "maps_en_czech-republic", "offlinka_cs_prirucky", "wikipedia_sk_all", "offlinka_mul_hry"])
        XCTAssertEqual(BookCatalog.sorted(records, uiLanguage: "sk").map(\.name),
                       ["wikipedia_sk_all", "offlinka_cs_prirucky", "wikipedia_cs_all", "maps_en_czech-republic", "offlinka_mul_hry"])
        XCTAssertEqual(BookKind.mapsSk.homeOrder(uiLanguage: "sk"), 10)
        XCTAssertEqual(BookKind.wikipediaEn.homeOrder(uiLanguage: "en"), 0)
        let full = [BookKind.wikivoyage, .wiktionary, .medicine, .wikipediaEn, .guides, .games, .wikisource]
        XCTAssertEqual(full.sorted { $0.homeOrder(uiLanguage: "cs") < $1.homeOrder(uiLanguage: "cs") },
                       [.guides, .wikipediaEn, .medicine, .games, .wikivoyage, .wikisource, .wiktionary])
    }

    func testIndexPriorityGuidesFirstAndTop1mOnlyOnRequest() {
        XCTAssertEqual(BookCatalog.indexPriority(name: "offlinka_cs_prirucky", uiLanguage: "cs", requested: false), 0)
        XCTAssertEqual(BookCatalog.indexPriority(name: "wikipedia_cs_all", uiLanguage: "cs", requested: false), 1)
        XCTAssertEqual(BookCatalog.indexPriority(name: "wikipedia_sk_all", uiLanguage: "cs", requested: false), 2)
        XCTAssertEqual(BookCatalog.indexPriority(name: "wikipedia_sk_all", uiLanguage: "sk", requested: false), 1)
        XCTAssertEqual(BookCatalog.indexPriority(name: "wiktionary_cs_all", uiLanguage: "cs", requested: false), 3)
        XCTAssertEqual(BookCatalog.indexPriority(name: "maps_en_czech-republic", uiLanguage: "cs", requested: false), 4)
        XCTAssertNil(BookCatalog.indexPriority(name: "wikipedia_en_top1m", uiLanguage: "en", requested: false))
        XCTAssertEqual(BookCatalog.indexPriority(name: "wikipedia_en_top1m", uiLanguage: "en", requested: true), 10)
    }

    func testIndexFileNamesBindNameAndSize() {
        let name = BookCatalog.indexFileName(zimFileName: "wikipedia_cs_all_maxi-offlinka_2026-09.zim", size: 9_958_167_476)
        XCTAssertEqual(name, "wikipedia_cs_all_maxi-offlinka_2026-09-9958167476.offidx")
        XCTAssertEqual(BookCatalog.bookKey(ofIndexFile: name), "wikipedia_cs_all")
        XCTAssertEqual(BookCatalog.indexFileName(zimFileName: "něco divného (1).zim", size: 5), "n_co_divn_ho__1_-5.offidx")
        XCTAssertNil(BookCatalog.bookKey(ofIndexFile: "readme.txt"))
    }

    func testPrimaryWikipediaFollowsLanguage() {
        let books = FakeContentSource.demo().books
        XCTAssertEqual(BookCatalog.primaryWikipedia(in: books, languageCode: "sk")?.name, "wikipedia_sk_all")
        XCTAssertEqual(BookCatalog.primaryWikipedia(in: books, languageCode: "cs")?.name, "wikipedia_cs_all")
        // No English Wikipedia in the demo: falls back to Czech.
        XCTAssertEqual(BookCatalog.primaryWikipedia(in: books, languageCode: "en")?.name, "wikipedia_cs_all")
    }

    func testEditionIsNewestContentMonth() {
        XCTAssertEqual(BookCatalog.edition(of: FakeContentSource.demo().books), "2026-09")
        XCTAssertNil(BookCatalog.edition(of: []))
    }

    func testSizesUseThreeSignificantDigits() {
        L10n.apply(.cs)
        defer { L10n.apply(.system) }
        XCTAssertEqual(BookCatalog.gigabytes(9_958_167_476), "9,96")
        XCTAssertEqual(BookCatalog.gigabytes(3_501_486_592), "3,5")
        XCTAssertEqual(BookCatalog.gigabytes(17_134_155_148), "17,1")
        XCTAssertEqual(BookCatalog.gigabytes(539_516_304), "0,54")
        XCTAssertEqual(BookCatalog.gigabytes(32_400_000_000, significantDigits: 2), "32")
    }

    func testLanguageTagsOnWikipediasAndEnglishOnlyBooks() {
        XCTAssertEqual(BookCatalog.languageTag(kind: .wikipediaCs), "CZ")
        XCTAssertEqual(BookCatalog.languageTag(kind: .wikipediaSk), "SK")
        XCTAssertEqual(BookCatalog.languageTag(kind: .medicine), "EN")
        XCTAssertEqual(BookCatalog.languageTag(kind: .wikivoyage), "EN")
        XCTAssertNil(BookCatalog.languageTag(kind: .guides))
    }

    func testContentDates() {
        L10n.apply(.cs)
        defer { L10n.apply(.system) }
        XCTAssertEqual(ContentDate.monthYear("2026-09-29"), "září 2026")
        XCTAssertEqual(ContentDate.monthYear("2026-11"), "listopad 2026")
        XCTAssertEqual(ContentDate.monthYear("bad"), "bad")
    }

    func testEveryKindHasStringsAndAnIcon() {
        for kind in BookKind.allCases {
            XCTAssertFalse(kind.descriptionKey(isMini: false).rawValue.isEmpty)
            XCTAssertFalse(kind.icon.rawValue.isEmpty)
            XCTAssertTrue(["wiki", "maps", "aid", "books", "games", "travel"].contains(kind.familyName))
        }
    }
}
