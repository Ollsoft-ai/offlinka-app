import XCTest
@testable import Offlinka

/// „Vyzkoušejte hned“ offers the same pages as Android, and only pages the books really have.
final class TryNowTests: XCTestCase {
    func testCzechUIOffersSnezkaTheCzechMapFirstAidAndA2048Game() {
        let items = TryNow.items(content: FakeContentSource.demo(), uiLanguage: "cs")
        XCTAssertEqual(items.map(\.kind), [.article, .map, .firstAid, .game])
        XCTAssertEqual(items[0].label, "Sněžka")
        XCTAssertEqual(items[0].bookId, FakeContentSource.demoWikipediaId)
        XCTAssertEqual(items[1].bookId, FakeContentSource.demoMapsId)
        XCTAssertEqual(items[2].path, "prvni-pomoc.html")
        XCTAssertEqual(items[3].path, "2048/index.html")
    }

    func testSlovakUIOffersVysokeTatryFromTheSlovakWikipedia() {
        let items = TryNow.items(content: FakeContentSource.demo(), uiLanguage: "sk")
        XCTAssertEqual(items.first?.label, "Vysoké Tatry")
        XCTAssertEqual(items.first?.bookId, FakeContentSource.demoWikipediaSkId)
        // No Slovak map in the demo: the Czech one stands in.
        XCTAssertEqual(items.first { $0.kind == .map }?.bookId, FakeContentSource.demoMapsId)
    }

    func testNoLinkToABookOrPageThatIsNotThere() {
        let books = FakeContentSource.demoBooks().filter { $0.record.name != "offlinka_mul_hry" }
        let items = TryNow.items(content: FakeContentSource(books: books), uiLanguage: "en")
        XCTAssertFalse(items.contains { $0.kind == .game })
        // English UI without an English Wikipedia: the Czech one and its article.
        XCTAssertEqual(items.first?.label, "Sněžka")
    }
}
