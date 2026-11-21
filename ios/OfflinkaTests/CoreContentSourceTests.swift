import XCTest
@testable import Offlinka

/// The real Rust core through the generated bindings, on real ZIMs.
///
/// Needs the small drive ZIMs in `ios/OfflinkaTests/Fixtures/` (git-ignored; copy them from
/// `/srv/offlinka/drive/release/<edition>/files/Knihovna/`: `offlinka_cs_prirucky.zim`,
/// `offlinka_mul_hry.zim`), or a folder with them in the `OFFLINKA_FIXTURES` environment
/// variable. Skipped when they are missing.
final class CoreContentSourceTests: XCTestCase {
    private var source: CoreContentSource!

    override func setUpWithError() throws {
        let fromEnvironment = ProcessInfo.processInfo.environment["OFFLINKA_FIXTURES"].map { URL(fileURLWithPath: $0, isDirectory: true) }
        let fixtures = fromEnvironment ?? Bundle(for: Self.self).url(forResource: "Fixtures", withExtension: nil)
        let guides = fixtures?.appendingPathComponent("offlinka_cs_prirucky.zim")
        try XCTSkipUnless(guides.map { FileManager.default.fileExists(atPath: $0.path) } ?? false,
                          "copy offlinka_cs_prirucky.zim into ios/OfflinkaTests/Fixtures to run the core tests")
        let scan = try DriveScanner().scan(root: try XCTUnwrap(fixtures))
        source = try CoreContentSource(scan: scan)
    }

    func testOpensBookWithMetadata() throws {
        let guides = try XCTUnwrap(source.book(named: "offlinka_cs_prirucky"))
        XCTAssertEqual(guides.id, "offlinka_cs_prirucky")
        XCTAssertEqual(guides.title, "Nouzové příručky")
        XCTAssertEqual(guides.language, "ces")
        XCTAssertEqual(guides.mainPath, "index.html")
        XCTAssertGreaterThan(guides.articleCount, 0)
        XCTAssertGreaterThan(guides.sizeBytes, 0)
        XCTAssertFalse(source.coreVersion.isEmpty)
    }

    func testReadsArticle() throws {
        let page = try source.resource(bookId: "offlinka_cs_prirucky", path: BookCatalog.firstAidPath)
        XCTAssertEqual(page.path, BookCatalog.firstAidPath)
        XCTAssertTrue(page.mimeType.hasPrefix("text/html"))
        XCTAssertTrue(String(decoding: page.data, as: UTF8.self).contains("První pomoc"))
        XCTAssertNoThrow(try source.resource(bookId: "offlinka_cs_prirucky", path: BookCatalog.prepareAtHomePath))
    }

    func testRangeMatchesWholeResource() throws {
        let whole = try source.resource(bookId: "offlinka_cs_prirucky", path: "pdf/prvni-pomoc.pdf").data
        let length = try source.contentLength(bookId: "offlinka_cs_prirucky", path: "pdf/prvni-pomoc.pdf")
        XCTAssertEqual(Int(length), whole.count)
        let part = try source.range(bookId: "offlinka_cs_prirucky", path: "pdf/prvni-pomoc.pdf", offset: 100, length: 50)
        XCTAssertEqual(part, whole.subdata(in: 100..<150))
    }

    func testMissingPathMapsToNotFound() {
        XCTAssertThrowsError(try source.resource(bookId: "offlinka_cs_prirucky", path: "nic-takoveho.html")) { error in
            guard case ContentError.notFound = error else { return XCTFail("expected notFound, got \(error)") }
        }
        XCTAssertThrowsError(try source.resource(bookId: "nonexistent", path: "index.html")) { error in
            XCTAssertEqual(error as? ContentError, .unknownBook("nonexistent"))
        }
    }

    func testResponderServesTheMainPage() throws {
        let responder = SchemeResponder(source: source, assets: .none, texts: ReaderTexts())
        let root = responder.reply(to: SchemeRequest(url: try XCTUnwrap(URL(string: "offlinka://offlinka_cs_prirucky/"))))
        XCTAssertTrue(String(decoding: root.body, as: UTF8.self).contains("location.replace(\"offlinka://offlinka_cs_prirucky/index.html\""))
        let page = responder.reply(to: SchemeRequest(url: try XCTUnwrap(URL(string: "offlinka://offlinka_cs_prirucky/index.html"))))
        XCTAssertEqual(page.statusCode, 200)
    }

    func testSearchAndRandom() throws {
        let hits = try source.suggest(bookId: "offlinka_cs_prirucky", query: "Prvn", limit: 10)
        XCTAssertTrue(hits.contains { $0.path == BookCatalog.firstAidPath }, "\(hits)")
        let all = try source.suggestAll(query: "Prvn", limit: 10)
        XCTAssertTrue(all.contains { $0.bookId == "offlinka_cs_prirucky" })
        XCTAssertNotNil(try source.randomArticle(bookId: "offlinka_cs_prirucky"))
        XCTAssertNotNil(source.illustration(bookId: "offlinka_cs_prirucky", size: 48))
    }
}
