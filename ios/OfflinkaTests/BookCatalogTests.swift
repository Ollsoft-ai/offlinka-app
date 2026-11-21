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

    func testHomeOrderFollowsSpec() {
        let books = FakeContentSource.demo().books
        XCTAssertEqual(books.map(\.name), ["wikipedia_cs_all", "wikipedia_sk_all", "maps_en_czech-republic", "offlinka_cs_prirucky", "offlinka_mul_hry"])
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

    func testEveryKindHasStringsAndAnIcon() {
        for kind in BookKind.allCases {
            XCTAssertFalse(kind.descriptionKey(isMini: false).rawValue.isEmpty)
            XCTAssertFalse(kind.icon.rawValue.isEmpty)
            XCTAssertTrue(["wiki", "maps", "aid", "books", "games", "travel"].contains(kind.familyName))
        }
    }
}
