import XCTest
@testable import Offlinka

/// The real Rust core through the generated bindings, on real ZIMs.
///
/// Needs the small drive ZIMs in `ios/OfflinkaTests/Fixtures/` (git-ignored; copy them from
/// `/srv/offlinka/drive/release/<edition>/files/Knihovna/`: `offlinka_cs_prirucky.zim`,
/// `offlinka_mul_hry.zim`), or a folder with them in the `OFFLINKA_FIXTURES` environment
/// variable. Skipped when they are missing.
@MainActor
final class CoreEngineTests: XCTestCase {
    private var fixtures: URL!
    private var work: URL!
    private let engine = CoreContentEngine()

    override func setUp() async throws {
        let fromEnvironment = ProcessInfo.processInfo.environment["OFFLINKA_FIXTURES"].map { URL(fileURLWithPath: $0, isDirectory: true) }
        let folder = fromEnvironment ?? Bundle(for: Self.self).url(forResource: "Fixtures", withExtension: nil)
        let guides = folder?.appendingPathComponent("offlinka_cs_prirucky.zim")
        try XCTSkipUnless(guides.map { FileManager.default.fileExists(atPath: $0.path) } ?? false,
                          "copy offlinka_cs_prirucky.zim into ios/OfflinkaTests/Fixtures to run the core tests")
        fixtures = folder
        work = FileManager.default.temporaryDirectory.appendingPathComponent("offlinka-core-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        if let work { try? FileManager.default.removeItem(at: work) }
    }

    private var guidesPath: String { fixtures.appendingPathComponent("offlinka_cs_prirucky.zim").path }

    func testOpensBookWithMetadata() throws {
        let book = try engine.open(path: guidesPath)
        let info = book.info
        XCTAssertEqual(info.id, "offlinka_cs_prirucky")
        XCTAssertEqual(info.title, "Nouzové příručky")
        XCTAssertEqual(info.language, "ces")
        XCTAssertEqual(info.mainPath, "index.html")
        XCTAssertGreaterThan(info.articleCount, 0)
        XCTAssertFalse(engine.version.isEmpty)
    }

    func testReadsArticleAndRanges() throws {
        let book = try engine.open(path: guidesPath)
        let page = try book.resource(path: BookCatalog.firstAidPath)
        XCTAssertTrue(page.mimeType.hasPrefix("text/html"))
        XCTAssertTrue(String(decoding: page.data, as: UTF8.self).contains("První pomoc"))
        let whole = try book.resource(path: "pdf/prvni-pomoc.pdf").data
        XCTAssertEqual(Int(try book.contentLength(path: "pdf/prvni-pomoc.pdf")), whole.count)
        XCTAssertEqual(try book.range(path: "pdf/prvni-pomoc.pdf", offset: 100, length: 50), whole.subdata(in: 100..<150))
        XCTAssertThrowsError(try book.resource(path: "nic-takoveho.html")) { error in
            guard case ContentError.notFound = error else { return XCTFail("expected notFound, got \(error)") }
        }
    }

    /// Drives sold without `.offidx`: the phone builds it (byte-identical to offlinka-cli) and attaches it.
    func testBuildsAttachesAndSearchesWithoutDiacritics() throws {
        let book = try engine.open(path: guidesPath)
        XCTAssertFalse(book.info.hasIndex)
        let out = work.appendingPathComponent(BookCatalog.indexFileName(zimFileName: "offlinka_cs_prirucky.zim", size: 393_612)).path
        let progress = ProgressLog()
        try engine.buildIndex(book, outPath: out, progress: { progress.add($0) }, isCancelled: { false })
        XCTAssertTrue(FileManager.default.fileExists(atPath: out))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out + ".part"))
        XCTAssertEqual(progress.values, progress.values.sorted(), "progress only grows")
        XCTAssertTrue(progress.values.allSatisfy { (0...1).contains($0) })

        try book.attachIndex(path: out)
        XCTAssertTrue(book.info.hasIndex)
        try book.preloadIndex()
        let hits = book.suggest(query: "prvni pomoc", limit: 10)
        XCTAssertEqual(hits.first?.path, BookCatalog.firstAidPath, "\(hits)")
    }

    func testCancelLeavesNoFile() throws {
        let book = try engine.open(path: guidesPath)
        let out = work.appendingPathComponent("cancelled.offidx").path
        XCTAssertThrowsError(try engine.buildIndex(book, outPath: out, progress: { _ in }, isCancelled: { true })) { error in
            XCTAssertEqual(error as? ContentError, .cancelled)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: out))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out + ".part"))
    }

    /// Unplug / rescan: closeFile makes later reads fail softly, the object stays usable.
    func testCloseFileFailsReadsSoftly() throws {
        let book = try engine.open(path: guidesPath)
        let title = book.info.title
        book.closeFile()
        XCTAssertThrowsError(try book.resource(path: "index.html")) { error in
            XCTAssertTrue((error as? ContentError)?.isDriveFailure ?? false, "\(error)")
        }
        XCTAssertTrue(book.suggest(query: "Prvn", limit: 5).isEmpty)
        XCTAssertEqual(book.info.title, title)
        XCTAssertThrowsError(try engine.buildIndex(book, outPath: work.appendingPathComponent("x.offidx").path, progress: { _ in }, isCancelled: { false }))
    }

    func testMemoryCallsAndLibraryMerge() throws {
        engine.setCacheBudget(bytes: 32 * 1024 * 1024)
        let book = try engine.open(path: guidesPath)
        let library = engine.newLibrary()
        library.add(book)
        XCTAssertTrue(library.suggestAll(query: "Prvn", limit: 10).contains { $0.path == BookCatalog.firstAidPath })
        engine.trimMemory()
        XCTAssertNoThrow(try book.resource(path: "index.html"), "caches reload after trim")
    }

    /// The whole shelf on the real core: opens the fixtures, builds their indexes in app storage.
    func testShelfOpensAndIndexesRealFiles() async throws {
        let scan = try DriveScanner().scan(root: fixtures)
        let indexes = work.appendingPathComponent("Indexes", isDirectory: true)
        let shelf = Shelf(engine: engine, indexDirectory: indexes, bundledGuidesPath: nil, uiLanguage: { "cs" })
        shelf.freeSpace = { _ in nil }
        let result = await shelf.load(scan: scan)
        XCTAssertEqual(result, .ready)
        let deadline = Date().addingTimeInterval(120)
        while !(shelf.tiles.allSatisfy { $0.state == .open && $0.record?.hasIndex == true } && !shelf.isIndexing), Date() < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertTrue(shelf.tiles.allSatisfy { $0.record?.hasIndex == true }, "\(shelf.tiles)")
        let responder = SchemeResponder(source: try XCTUnwrap(shelf.content), assets: .none, texts: ReaderTexts())
        let root = responder.reply(to: SchemeRequest(url: try XCTUnwrap(URL(string: "offlinka://offlinka_cs_prirucky/"))))
        XCTAssertTrue(String(decoding: root.body, as: UTF8.self).contains("location.replace(\"offlinka://offlinka_cs_prirucky/index.html\""))
        let hits = try XCTUnwrap(shelf.content).suggestAll(query: "vypadek proudu", limit: 5)
        XCTAssertTrue(hits.contains { $0.path == BookCatalog.prepareAtHomePath }, "\(hits)")
    }
}

/// Progress values from the index builder's thread.
private final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [Double] = []

    func add(_ value: Double) {
        lock.lock()
        stored.append(value)
        lock.unlock()
    }

    var values: [Double] {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}
