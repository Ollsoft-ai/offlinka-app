import XCTest
@testable import Offlinka

@MainActor
final class SearchModelTests: XCTestCase {
    private func makeModel(scope: String? = nil) -> (SearchModel, FakeContentSource) {
        let source = FakeContentSource.demo()
        return (SearchModel(source: source, scopeBookId: scope, debounce: .zero), source)
    }

    func testEmptyQueryIsIdle() async {
        let (model, source) = makeModel()
        model.query = "   "
        await model.settle()
        XCTAssertEqual(model.phase, .idle)
        XCTAssertTrue(model.groups.isEmpty)
        XCTAssertEqual(source.callCount, 0)
    }

    func testDiacriticsInsensitiveAndGroupedByBook() async {
        let (model, _) = makeModel()
        model.query = "praha"
        await model.settle()
        XCTAssertEqual(model.phase, .results)
        // Czech Wikipedia first (core ranking: same match class, Czech Wikipedia before others).
        XCTAssertEqual(model.groups.map(\.book.id), [FakeContentSource.demoWikipediaId, FakeContentSource.demoWikipediaSkId])
        XCTAssertEqual(model.groups.first?.hits.first?.title, "Praha")

        model.query = "cesky krumlov"
        await model.settle()
        XCTAssertEqual(model.topHit?.path, "Český_Krumlov")
    }

    func testRedirectHitOpensTarget() async {
        let (model, _) = makeModel()
        model.query = "komensky"
        await model.settle()
        let paths = model.groups.flatMap(\.hits).map(\.path)
        XCTAssertEqual(Set(paths), ["Jan_Amos_Komenský"])
    }

    func testNoResults() async {
        let (model, _) = makeModel()
        model.query = "qqqqzzzz"
        await model.settle()
        XCTAssertEqual(model.phase, .empty)
        XCTAssertTrue(model.groups.isEmpty)
    }

    func testScopedSearchStaysInOneBook() async {
        let (model, _) = makeModel(scope: FakeContentSource.demoWikipediaSkId)
        model.query = "praha"
        await model.settle()
        XCTAssertEqual(model.groups.map(\.book.id), [FakeContentSource.demoWikipediaSkId])
    }

    func testLatestQueryWins() async {
        let (model, _) = makeModel()
        model.query = "pra"
        model.query = "sne"
        await model.settle()
        XCTAssertEqual(model.topHit?.title, "Sněžka")
    }

    func testDriveFailureGivesNoResultsInsteadOfCrashing() async {
        let (model, source) = makeModel()
        source.simulatesUnpluggedDrive = true
        model.query = "praha"
        await model.settle()
        XCTAssertEqual(model.phase, .empty)
    }

    func testExpandLoadsMoreFromThatBook() async throws {
        let (model, _) = makeModel()
        model.query = "p"
        await model.settle()
        let group = try XCTUnwrap(model.groups.first)
        await model.expand(group)
        XCTAssertEqual(model.groups.first?.isExpanded, true)
    }

    func testGroupingDropsUnknownBooksAndDuplicates() {
        let books = FakeContentSource.demo().books
        let hits = [
            SearchHit(bookId: FakeContentSource.demoWikipediaId, title: "Praha", path: "Praha", snippet: nil),
            SearchHit(bookId: "nonexistent", title: "X", path: "X", snippet: nil),
            SearchHit(bookId: FakeContentSource.demoWikipediaSkId, title: "Praha", path: "Praha", snippet: nil),
            SearchHit(bookId: FakeContentSource.demoWikipediaId, title: "Praha (město)", path: "Praha", snippet: nil),
        ]
        let groups = SearchModel.group(hits, books: books)
        XCTAssertEqual(groups.map(\.book.id), [FakeContentSource.demoWikipediaId, FakeContentSource.demoWikipediaSkId])
        XCTAssertEqual(groups.first?.hits.count, 1)
    }
}
