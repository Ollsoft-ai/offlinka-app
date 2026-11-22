import XCTest
@testable import Offlinka

/// The shelf's rules (Android `ShelfTest` twins) on the fake engine.
@MainActor
final class ShelfTests: XCTestCase {
    private var indexDir: URL!
    private let csFile = "wikipedia_cs_all_maxi-offlinka_2026-09.zim"
    private let skFile = "wikipedia_sk_all_maxi-offlinka_2026-08.zim"
    private let gamesFile = "offlinka_mul_hry.zim"

    override func setUp() async throws {
        indexDir = FileManager.default.temporaryDirectory.appendingPathComponent("offlinka-shelf-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: indexDir)
    }

    // MARK: - Helpers

    private static let top1m: FakeContentSource.FakeBook = {
        let record = BookRecord(id: "wikipedia_en_top1m", name: "wikipedia_en_top1m", title: "Wikipedia", summary: "", language: "eng",
                                creator: "Wikipedia", publisher: "openZIM", date: "2026-04-01", articleCount: 1_000_000, mediaCount: 0,
                                mainPath: "index", sizeBytes: 17_134_155_148, hasIndex: false,
                                fileName: "wikipedia_en_top1m_nopic_2026-04.zim", folder: .library)
        return FakeContentSource.FakeBook(record: record, entries: ["index": .html("Wikipedia", "<p>Welcome</p>")], illustration: nil)
    }()

    private func books(indexed: Bool, withGuides: Bool = true, extra: [FakeContentSource.FakeBook] = []) -> [FakeContentSource.FakeBook] {
        (FakeContentSource.demoBooks() + extra)
            .filter { withGuides || $0.record.name != "offlinka_cs_prirucky" }
            .map { book in
                var copy = book
                copy.record = book.record.withIndex(indexed)
                return copy
            }
    }

    private func engine(_ books: [FakeContentSource.FakeBook], guidesInBundle: Bool = true,
                        bundledGuidesIndexed: Bool = true) -> FakeContentEngine {
        var byFile: [String: FakeContentSource.FakeBook] = [:]
        for book in books {
            byFile[book.record.fileName] = book
        }
        // The bundled guides have the same file name: the drive's copy (if any) is what the test set up.
        if guidesInBundle, var guides = FakeContentSource.demoBooks().first(where: { $0.record.name == "offlinka_cs_prirucky" }),
           byFile[guides.record.fileName] == nil {
            guides.record = guides.record.withIndex(bundledGuidesIndexed)
            byFile[guides.record.fileName] = guides
        }
        return FakeContentEngine(books: byFile)
    }

    private func scan(_ books: [FakeContentSource.FakeBook]) -> DriveScan {
        let root = URL(fileURLWithPath: "/OFFLINKA")
        let entries = books.map { book in
            DriveEntry(url: root.appendingPathComponent("Knihovna").appendingPathComponent(book.record.fileName),
                       folder: .library, name: ZimFileName(fileName: book.record.fileName), indexURL: nil, sizeBytes: book.record.sizeBytes)
        }
        return DriveScan(root: root, books: entries, shadowed: [])
    }

    private func shelf(_ engine: FakeContentEngine, ui: String = "cs", indexes: Bool = false, bundledGuides: Bool = true) -> Shelf {
        let shelf = Shelf(engine: engine, indexDirectory: indexes ? indexDir : nil,
                          bundledGuidesPath: bundledGuides ? "/bundle/offlinka_cs_prirucky.zim" : nil, uiLanguage: { ui })
        shelf.freeSpace = { _ in nil }
        return shelf
    }

    private func waitUntil(_ what: String, timeout: Double = 10, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline {
                XCTFail("timed out waiting for: \(what)")
                return
            }
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    private func indexFiles() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: indexDir.path)) ?? []).sorted()
    }

    // MARK: - Opening

    func testUILanguageWikipediaOpensFirstAndHomeShowsBeforeTheRest() async {
        let list = books(indexed: true)
        let engine = engine(list)
        engine.setOpenDelay(0.3)
        let shelf = shelf(engine)

        let result = await shelf.load(scan: scan(list))

        XCTAssertEqual(result, .ready)
        XCTAssertEqual(engine.openLog.first, "offlinka_cs_prirucky.zim", "the bundled guides open once at start")
        XCTAssertEqual(engine.openLog.dropFirst().first, csFile, "then the UI-language Wikipedia, before anything else")
        XCTAssertEqual(shelf.tiles.first?.id, "wikipedia_cs_all")
        XCTAssertEqual(shelf.tiles.first?.state, .open)
        XCTAssertNotNil(shelf.content?.book(id: "wikipedia_cs_all"))
        XCTAssertTrue(shelf.tiles.dropFirst().contains { $0.state == .opening }, "Home is published while the others still open")
        XCTAssertTrue(shelf.content?.isPending(bookId: "wikipedia_sk_all") ?? false)

        await waitUntil("all books open") { shelf.tiles.allSatisfy { $0.state == .open } }
        XCTAssertEqual(shelf.tiles.map(\.id), ["wikipedia_cs_all", "maps_en_czech-republic", "offlinka_cs_prirucky", "wikipedia_sk_all", "offlinka_mul_hry"])
        XCTAssertEqual(shelf.content?.books.count, 5)
        XCTAssertFalse(shelf.content?.isPending(bookId: "wikipedia_sk_all") ?? true)
    }

    func testSlovakUIOpensAndShowsSlovakWikipediaFirst() async {
        let list = books(indexed: true)
        let engine = engine(list)
        let shelf = shelf(engine, ui: "sk", bundledGuides: false)
        _ = await shelf.load(scan: scan(list))
        XCTAssertEqual(engine.openLog.first, skFile)
        await waitUntil("all open") { shelf.tiles.allSatisfy { $0.state == .open } }
        // Slovak UI: the Slovak Wikipedia first; Mapa Česka moves down (Mapa Slovenska would take its place).
        XCTAssertEqual(shelf.tiles.map(\.id), ["wikipedia_sk_all", "offlinka_cs_prirucky", "wikipedia_cs_all", "maps_en_czech-republic", "offlinka_mul_hry"])
    }

    func testFailedBookStaysAsTileAndRetryOpensIt() async {
        let list = books(indexed: true)
        let engine = engine(list)
        engine.failOpening(gamesFile)
        let shelf = shelf(engine)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("opening finished") { !shelf.tiles.contains { $0.state == .opening } }

        let games = shelf.tiles.first { $0.id == "offlinka_mul_hry" }
        XCTAssertEqual(games?.state, .failed)
        XCTAssertNil(shelf.content?.book(id: "offlinka_mul_hry"))
        XCTAssertFalse(shelf.content?.isPending(bookId: "offlinka_mul_hry") ?? true)

        engine.failOpening(gamesFile, false)
        await shelf.retry("offlinka_mul_hry")
        XCTAssertEqual(shelf.tiles.first { $0.id == "offlinka_mul_hry" }?.state, .open)
        XCTAssertNotNil(shelf.content?.book(id: "offlinka_mul_hry"))
    }

    func testDriveGoneWhenTheFirstBookCannotBeRead() async {
        let list = books(indexed: true)
        let engine = engine(list)
        engine.failOpening(csFile, io: true)
        let shelf = shelf(engine)
        let result = await shelf.load(scan: scan(list))
        XCTAssertEqual(result, .driveGone)
        XCTAssertNil(shelf.content)
        XCTAssertTrue(shelf.tiles.isEmpty)
    }

    /// Android code review blocker 1: the old books must still be open when the new state is published.
    func testRescanPublishesNewBooksBeforeClosingTheOldOnes() async throws {
        let list = books(indexed: true)
        let engine = engine(list)
        let shelf = shelf(engine)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("all open") { shelf.tiles.allSatisfy { $0.state == .open } }
        let old = try XCTUnwrap(shelf.content)
        // The drive's books (the bundled guides stay open across drives).
        let oldBooks = old.openBooks.filter { !old.isBuiltin($0.record.id) }.compactMap { $0.book as? FakeContentBook }
        XCTAssertEqual(oldBooks.count, 4)

        var closedWhenPublished: [Bool] = []
        var published: ShelfContent?
        shelf.onContentChange = { new in
            published = new
            closedWhenPublished = oldBooks.map(\.isClosed)
        }
        _ = await shelf.load(scan: scan(list))

        XCTAssertNotNil(published)
        XCTAssertFalse(published === old)
        XCTAssertEqual(closedWhenPublished, Array(repeating: false, count: 4))
        // Closed afterwards, off the main actor (closing waits for reads in progress).
        await waitUntil("old books closed") { oldBooks.allSatisfy(\.isClosed) && old.isRetired }
        // A request still in flight on the old books gets an I/O error (503 page), never a crash.
        XCTAssertThrowsError(try old.resource(bookId: "wikipedia_cs_all", path: "Praha")) { error in
            XCTAssertTrue((error as? ContentError)?.isDriveFailure ?? false)
        }
    }

    func testBundledGuidesStandInWhenTheDriveHasNoneAndSurviveUnplug() async throws {
        let list = books(indexed: true, withGuides: false)
        let engine = engine(list)
        let shelf = shelf(engine)
        _ = await shelf.load(scan: scan(list))

        let guidesTile = try XCTUnwrap(shelf.tiles.first { $0.id == "offlinka_cs_prirucky" })
        XCTAssertTrue(guidesTile.isBuiltin)
        XCTAssertEqual(guidesTile.record?.folder, .app)
        XCTAssertNoThrow(try shelf.content?.resource(bookId: "offlinka_cs_prirucky", path: BookCatalog.firstAidPath))

        shelf.retire()
        XCTAssertNil(shelf.content)
        XCTAssertTrue(shelf.tiles.isEmpty)
        // Without a drive the guides still open.
        let guides = try XCTUnwrap(shelf.guidesContent())
        XCTAssertNoThrow(try guides.resource(bookId: "offlinka_cs_prirucky", path: BookCatalog.firstAidPath))
    }

    /// Without guides on the drive, the bundled ones get a search index too („prvni pomoc“ needs it).
    func testBundledGuidesGetASearchIndex() async throws {
        let list = books(indexed: true, withGuides: false)
        let engine = engine(list, bundledGuidesIndexed: false)
        let shelf = shelf(engine, indexes: true)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("guides indexed") {
            shelf.tiles.first { $0.id == "offlinka_cs_prirucky" }?.record?.hasIndex == true && !shelf.isIndexing
        }
        XCTAssertTrue(engine.buildLog.contains("offlinka_cs_prirucky"))
        XCTAssertEqual(shelf.builtinGuides?.hasIndex, true)

        // The next drive does not build or attach it again.
        let builds = engine.buildLog.count
        _ = await shelf.load(scan: scan(list))
        await waitUntil("idle") { shelf.tiles.allSatisfy { $0.state == .open } && !shelf.isIndexing }
        XCTAssertEqual(engine.buildLog.count, builds)
        XCTAssertEqual(shelf.tiles.first { $0.id == "offlinka_cs_prirucky" }?.record?.hasIndex, true)
    }

    /// Like Android 0.3.1: the drive's guides (which still explain the Kiwix apps) are left out; the app's own stand in.
    func testTheBundledGuidesAlwaysStandInForTheDrivesCopy() async {
        let list = books(indexed: true)
        let shelf = shelf(engine(list))
        _ = await shelf.load(scan: scan(list))
        await waitUntil("all open") { shelf.tiles.allSatisfy { $0.state == .open } }
        let guides = shelf.tiles.filter { $0.id == "offlinka_cs_prirucky" }
        XCTAssertEqual(guides.count, 1)
        XCTAssertEqual(guides.first?.isBuiltin, true)
        XCTAssertEqual(guides.first?.record?.folder, .app)
    }

    func testADriveWithOnlyTheGuidesOpensTheBundledOnes() async {
        let list = books(indexed: true).filter { $0.record.name == "offlinka_cs_prirucky" }
        let shelf = shelf(engine(list))
        let result = await shelf.load(scan: scan(list))
        XCTAssertEqual(result, .ready)
        XCTAssertEqual(shelf.tiles.map(\.id), ["offlinka_cs_prirucky"])
        XCTAssertEqual(shelf.tiles.first?.isBuiltin, true)
    }

    // MARK: - Search indexes

    func testMissingIndexesAreBuiltUILanguageWikipediaFirst() async {
        let list = books(indexed: false)
        let engine = engine(list)
        let shelf = shelf(engine, indexes: true)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("all indexed") { shelf.tiles.allSatisfy { $0.record?.hasIndex == true } && !shelf.isIndexing }

        // The guides first (they take no time), then the UI-language Wikipedia, the other one, the rest by size.
        XCTAssertEqual(engine.buildLog, ["offlinka_cs_prirucky", "wikipedia_cs_all", "wikipedia_sk_all", "offlinka_mul_hry", "maps_en_czech-republic"])
        XCTAssertTrue(indexFiles().contains(BookCatalog.indexFileName(zimFileName: csFile, size: 9_958_167_476)))
        XCTAssertEqual(shelf.content?.book(id: "wikipedia_cs_all")?.hasIndex, true)
        XCTAssertNil(shelf.indexing)
        XCTAssertFalse(indexFiles().contains { $0.hasSuffix(".part") })
    }

    func testAnIndexBuiltEarlierIsAttachedAtOpenAndNotRebuilt() async throws {
        try FileManager.default.createDirectory(at: indexDir, withIntermediateDirectories: true)
        let existing = indexDir.appendingPathComponent(BookCatalog.indexFileName(zimFileName: csFile, size: 9_958_167_476))
        try Data("idx".utf8).write(to: existing)
        let list = books(indexed: false)
        let engine = engine(list)
        let shelf = shelf(engine, indexes: true)
        _ = await shelf.load(scan: scan(list))
        XCTAssertEqual(shelf.tiles.first?.record?.hasIndex, true, "the restart resumes: no rebuild of what is there")
        await waitUntil("all indexed") { shelf.tiles.allSatisfy { $0.record?.hasIndex == true } && !shelf.isIndexing }
        XCTAssertFalse(engine.buildLog.contains("wikipedia_cs_all"))
    }

    /// Code review r2 #7: the folder keeps only indexes of files on this drive.
    func testIndexFolderKeepsOnlyIndexesOfThisDrive() async throws {
        try FileManager.default.createDirectory(at: indexDir, withIntermediateDirectories: true)
        let oldEdition = "wikipedia_cs_all_maxi-offlinka_2026-01-9000000000.offidx"
        let notOnThisDrive = "wikibooks_cs_all_maxi-offlinka_2026-10-44663460.offidx"
        let leftover = "wikipedia_sk_all_maxi-offlinka_2026-08-3501486592.offidx.part"
        let beforeFormatTag = "wikipedia_cs_all_maxi-offlinka_2026-09-9958167476.offidx"   // built by the old core: rebuilt
        for name in [oldEdition, notOnThisDrive, leftover, beforeFormatTag] {
            try Data("x".utf8).write(to: indexDir.appendingPathComponent(name))
        }
        let list = books(indexed: false)
        let shelf = shelf(engine(list), indexes: true)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("all indexed") { shelf.tiles.allSatisfy { $0.record?.hasIndex == true } && !shelf.isIndexing }
        let expected = list.map { BookCatalog.indexFileName(zimFileName: $0.record.fileName, size: $0.record.sizeBytes) }.sorted()
        XCTAssertEqual(indexFiles(), expected)
    }

    func testOnlyTheUILanguageWikipediaShowsProgress() async {
        let list = books(indexed: false)
        let engine = engine(list)
        engine.setBuildDelay(0.3)
        let shelf = shelf(engine, indexes: true)
        var seen: Set<String> = []
        _ = await shelf.load(scan: scan(list))
        let deadline = Date().addingTimeInterval(15)
        while !(shelf.tiles.allSatisfy { $0.record?.hasIndex == true } && !shelf.isIndexing), Date() < deadline {
            if let progress = shelf.indexing { seen.insert(progress.bookId) }
            try? await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(seen, ["wikipedia_cs_all"])
    }

    func testAReadErrorOnOneBookFailsOnlyThatBookWhileTheDriveIsThere() async throws {
        let list = books(indexed: false)
        let engine = engine(list)
        // Guides and the Czech Wikipedia are built first: time to break the Slovak one before its turn.
        engine.setBuildDelay(0.4)
        let shelf = shelf(engine, indexes: true)
        var failures = 0
        shelf.onDriveFailure = { failures += 1 }
        shelf.driveReachable = { true }
        _ = await shelf.load(scan: scan(list))
        await waitUntil("opened") { shelf.tiles.allSatisfy { $0.state == .open } }
        // The Slovak Wikipedia's file "breaks": its reads fail like a bad sector.
        let sk = try XCTUnwrap(shelf.content?.handle(id: "wikipedia_sk_all") as? FakeContentBook)
        sk.closeFile()
        await waitUntil("indexing done") { !shelf.isIndexing && engine.buildLog.count == 4 }
        XCTAssertFalse(engine.buildLog.contains("wikipedia_sk_all"))
        XCTAssertEqual(failures, 0)
        XCTAssertEqual(shelf.tiles.first { $0.id == "maps_en_czech-republic" }?.record?.hasIndex, true, "indexing went on after the failed book")
    }

    /// Book details (Android `indexInfo`): the book being built shows its progress, the others „Ve frontě“,
    /// and „Zastavit přípravu“ empties the queue.
    func testBookDetailsShowBuildingAndQueuedUntilStopped() async throws {
        let list = books(indexed: false)
        let engine = engine(list)
        engine.setBuildDelay(2)
        let shelf = shelf(engine, indexes: true)
        _ = await shelf.load(scan: scan(list))
        // The bundled guides go first and show nothing (like Android); then the drive's books.
        await waitUntil("building a drive book", timeout: 15) {
            shelf.buildingBookId != nil && shelf.buildingBookId != "offlinka_cs_prirucky" && !shelf.indexQueue.isEmpty
        }
        let records = shelf.tiles.filter { !$0.isBuiltin }.compactMap(\.record)
        let building = try XCTUnwrap(records.first { $0.id == shelf.buildingBookId })
        guard case .building = shelf.indexPhase(of: building) else {
            return XCTFail("expected building, got \(String(describing: shelf.indexPhase(of: building)))")
        }
        let waiting = records.filter { $0.id != building.id && !$0.hasIndex }
        XCTAssertFalse(waiting.isEmpty)
        for record in waiting {
            XCTAssertEqual(shelf.indexPhase(of: record), .queued, record.id)
        }
        shelf.cancelIndexing(building)
        XCTAssertTrue(shelf.indexQueue.isEmpty)
        await waitUntil("stopped") { !shelf.isIndexing }
        XCTAssertNil(shelf.indexPhase(of: waiting[0]))
    }

    /// No room for an index: every book still to do says „Málo místa“ and offers to try again.
    func testLowSpaceMarksTheBooksStillToDo() async {
        let list = books(indexed: false)
        let engine = engine(list)
        let shelf = shelf(engine, indexes: true)
        shelf.freeSpace = { _ in 1024 }
        _ = await shelf.load(scan: scan(list))
        await waitUntil("pass ended") { shelf.indexLowSpace && !shelf.isIndexing }
        XCTAssertTrue(engine.buildLog.isEmpty)
        for tile in shelf.tiles where !tile.isBuiltin {
            guard let record = tile.record, !record.hasIndex else { continue }
            XCTAssertEqual(shelf.indexPhase(of: record), .lowSpace, record.id)
        }
    }

    func testEnglishTop1mOnlyOnRequest() async throws {
        let list = books(indexed: false, extra: [Self.top1m])
        let engine = engine(list)
        let shelf = shelf(engine, indexes: true)
        var requested: Set<String> = []
        shelf.indexRequests = { requested }
        shelf.requestIndex = { requested.insert($0) }
        _ = await shelf.load(scan: scan(list))
        await waitUntil("automatic indexes built") { engine.buildLog.count == 5 && !shelf.isIndexing }
        XCTAssertFalse(engine.buildLog.contains("wikipedia_en_top1m"))

        let top1m = try XCTUnwrap(shelf.tiles.first { $0.id == "wikipedia_en_top1m" }?.record)
        XCTAssertTrue(shelf.canBuildOnDemand(top1m))
        shelf.heavyIndexAllowed = false
        XCTAssertFalse(shelf.canBuildOnDemand(top1m), "only on phones with 3 GB or more")
        shelf.heavyIndexAllowed = true

        shelf.buildIndexNow(top1m)
        XCTAssertEqual(requested, ["wikipedia_en_top1m"])
        await waitUntil("top1m built") { engine.buildLog.contains("wikipedia_en_top1m") && !shelf.isIndexing }
        XCTAssertEqual(shelf.tiles.first { $0.id == "wikipedia_en_top1m" }?.record?.hasIndex, true)
        XCTAssertFalse(shelf.canBuildOnDemand(top1m))
    }

    func testStoppingAnOnDemandBuildForgetsTheRequest() async throws {
        let list = books(indexed: true) + [Self.top1m]
        let engine = engine(list)
        engine.setBuildDelay(2)
        let shelf = shelf(engine, indexes: true)
        var requested: Set<String> = []
        shelf.indexRequests = { requested }
        shelf.requestIndex = { requested.insert($0) }
        shelf.removeIndexRequest = { requested.remove($0) }
        _ = await shelf.load(scan: scan(list))
        await waitUntil("opened") { shelf.tiles.allSatisfy { $0.state == .open } }
        let top1m = try XCTUnwrap(shelf.tiles.first { $0.id == "wikipedia_en_top1m" }?.record)
        shelf.buildIndexNow(top1m)
        await waitUntil("building") { shelf.buildingBookId == "wikipedia_en_top1m" }
        shelf.cancelIndexing(top1m)
        await waitUntil("stopped") { !shelf.isIndexing }
        XCTAssertTrue(requested.isEmpty, "a restart must not start it again")
        XCTAssertTrue(indexFiles().isEmpty)
    }

    func testCancelStopsTheBuildAndLeavesNoFile() async {
        let list = books(indexed: false)
        let engine = engine(list)
        engine.setBuildDelay(2)
        let shelf = shelf(engine, indexes: true)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("build started") { shelf.buildingBookId != nil }
        shelf.cancelIndexing()
        await waitUntil("build stopped") { !shelf.isIndexing }
        XCTAssertTrue(engine.buildLog.isEmpty)
        XCTAssertNil(shelf.indexing)
        XCTAssertTrue(indexFiles().isEmpty, "no .offidx and no .part left: \(indexFiles())")
    }

    func testBuildsOnlyInTheForegroundAndResumes() async {
        let list = books(indexed: false)
        let engine = engine(list)
        let shelf = shelf(engine, indexes: true)
        shelf.setForeground(false)
        _ = await shelf.load(scan: scan(list))
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(shelf.isIndexing)
        XCTAssertTrue(engine.buildLog.isEmpty)

        shelf.setForeground(true)
        await waitUntil("all indexed") { shelf.tiles.allSatisfy { $0.record?.hasIndex == true } && !shelf.isIndexing }
    }

    func testBackgroundTimeRunningOutStopsCleanlyThenResumes() async {
        let list = books(indexed: false)
        let engine = engine(list)
        engine.setBuildDelay(1)
        let shelf = shelf(engine, indexes: true)
        _ = await shelf.load(scan: scan(list))
        await waitUntil("build started") { shelf.buildingBookId != nil }

        shelf.setForeground(false)
        var stopped = false
        shelf.whenIndexingStops { stopped = true }
        shelf.pauseIndexing()
        await waitUntil("build paused") { stopped }
        XCTAssertTrue(engine.buildLog.isEmpty)
        XCTAssertFalse(indexFiles().contains { $0.hasSuffix(".part") })

        engine.setBuildDelay(0)
        shelf.setForeground(true)
        await waitUntil("all indexed") { shelf.tiles.allSatisfy { $0.record?.hasIndex == true } && !shelf.isIndexing }
    }

    func testUnplugDuringABuildStopsItWithoutReportingTheDriveTwice() async {
        let list = books(indexed: false)
        let engine = engine(list)
        engine.setBuildDelay(1)
        let shelf = shelf(engine, indexes: true)
        var failures = 0
        shelf.onDriveFailure = { failures += 1 }
        _ = await shelf.load(scan: scan(list))
        await waitUntil("build started") { shelf.buildingBookId != nil }
        shelf.retire()
        await waitUntil("build stopped") { !shelf.isIndexing }
        XCTAssertEqual(failures, 0)
        XCTAssertNil(shelf.indexing)
        XCTAssertTrue(engine.buildLog.isEmpty)
    }

    func testPreloadAndTrimReachTheEngine() async {
        let list = books(indexed: true)
        let engine = engine(list)
        let shelf = shelf(engine)
        _ = await shelf.load(scan: scan(list))
        shelf.preloadIndexes()
        shelf.trimMemory()
        await waitUntil("trimmed") { engine.trimCount == 1 }
    }
}
