import XCTest
@testable import Offlinka

@MainActor
final class UserStoreTests: XCTestCase {
    private var directory: URL!
    private var file: URL { directory.appendingPathComponent("user-data.json") }

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("offlinka-store-\(UUID().uuidString)")
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func article(_ path: String, title: String? = nil, book: String = "wikipedia_cs_all") -> ArticleRef {
        ArticleRef(bookName: book, path: path, title: title ?? path, date: Date(timeIntervalSinceReferenceDate: 1000))
    }

    /// A full phone (injected write failure): saving, history and searches go on in memory, nothing crashes,
    /// the file on disk stays whole, and the next write after space is back stores everything.
    func testAFullDiskNeverCrashesOrCorruptsTheFile() throws {
        let first = UserStore(fileURL: file)
        first.toggleSaved(article("Praha"))
        first.flush()
        let before = try Data(contentsOf: file)

        let full = LockedFlag(true)
        let store = UserStore(fileURL: file) { data, url in
            if full.value { throw CocoaError(.fileWriteOutOfSpace) }
            try UserStore.atomicWrite(data, to: url)
        }
        store.recordVisit(article("Sněžka"))
        store.addRecentSearch("krkonose")
        XCTAssertTrue(store.toggleSaved(article("Brno")))
        store.flush()
        XCTAssertEqual(try Data(contentsOf: file), before, "a failed write leaves the old file whole")
        XCTAssertEqual(store.history.map(\.path), ["Sněžka"])
        XCTAssertEqual(store.recentSearches, ["krkonose"])
        XCTAssertTrue(store.isSaved(bookName: "wikipedia_cs_all", path: "Brno"))

        full.value = false
        store.addRecentSearch("praha")
        store.flush()
        let reloaded = UserStore(fileURL: file)
        XCTAssertEqual(Set(reloaded.saved.map(\.path)), ["Praha", "Brno"])
        XCTAssertEqual(reloaded.history.map(\.path), ["Sněžka"])
        XCTAssertEqual(reloaded.recentSearches, ["praha", "krkonose"])
    }

    /// Saved articles, history, searches, settings and page storage stay out of iCloud backups (like Android).
    func testAppDataIsKeptOutOfBackups() throws {
        let support = directory.appendingPathComponent("Application Support/Offlinka", isDirectory: true)
        let library = directory.appendingPathComponent("Library", isDirectory: true)
        UserStore.excludeAppDataFromBackup(appSupport: support, library: library)
        let webKit = library.appendingPathComponent("WebKit", isDirectory: true)
        for folder in [support, webKit] {
            var isDirectory: ObjCBool = false
            XCTAssertTrue(FileManager.default.fileExists(atPath: folder.path, isDirectory: &isDirectory) && isDirectory.boolValue)
            #if canImport(Darwin)
            XCTAssertEqual(try folder.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true, folder.path)
            #endif
        }
        // The store keeps writing there as before.
        let store = UserStore(fileURL: support.appendingPathComponent("user-data.json"))
        store.addRecentSearch("praha")
        store.flush()
        XCTAssertEqual(UserStore(fileURL: support.appendingPathComponent("user-data.json")).recentSearches, ["praha"])
    }

    func testSavedToggleAndPersistence() {
        let store = UserStore(fileURL: file)
        XCTAssertTrue(store.toggleSaved(article("Praha")))
        XCTAssertTrue(store.isSaved(bookName: "wikipedia_cs_all", path: "Praha"))
        XCTAssertFalse(store.isSaved(bookName: "wikipedia_sk_all", path: "Praha"))
        store.toggleSaved(article("Sněžka"))
        store.flush()

        let reloaded = UserStore(fileURL: file)
        XCTAssertEqual(reloaded.saved.map(\.path), ["Sněžka", "Praha"])

        XCTAssertFalse(reloaded.toggleSaved(article("Praha")))
        XCTAssertEqual(reloaded.saved.map(\.path), ["Sněžka"])
    }

    func testUndoPutsSavedArticleBackInPlace() {
        let store = UserStore(fileURL: nil)
        store.toggleSaved(article("C"))
        store.toggleSaved(article("B"))
        store.toggleSaved(article("A"))
        let removed = article("B")
        store.removeSaved(removed)
        XCTAssertEqual(store.saved.map(\.path), ["A", "C"])
        store.restoreSaved(removed, at: 1)
        XCTAssertEqual(store.saved.map(\.path), ["A", "B", "C"])
        store.restoreSaved(removed, at: 0)
        XCTAssertEqual(store.saved.map(\.path), ["A", "B", "C"], "no duplicates")
    }

    func testHistoryMovesRevisitsToTopAndSkipsUntitled() {
        let store = UserStore(fileURL: nil)
        store.recordVisit(article("Praha"))
        store.recordVisit(article("Brno"))
        store.recordVisit(article("Praha"))
        store.recordVisit(article("I/obrazek.png", title: "  "))
        XCTAssertEqual(store.history.map(\.path), ["Praha", "Brno"])
    }

    func testHistoryIsCapped() {
        let store = UserStore(fileURL: nil)
        for index in 0..<(UserStore.historyLimit + 20) {
            store.recordVisit(article("A\(index)"))
        }
        XCTAssertEqual(store.history.count, UserStore.historyLimit)
        XCTAssertEqual(store.history.first?.path, "A\(UserStore.historyLimit + 19)")
    }

    func testHistoryOffRecordsNothing() {
        let store = UserStore(fileURL: nil)
        store.settings.saveHistory = false
        store.recordVisit(article("Praha"))
        XCTAssertTrue(store.history.isEmpty)
    }

    func testRecentSearchesDedupeIgnoringCaseAndDiacritics() {
        let store = UserStore(fileURL: nil)
        store.addRecentSearch("Praha")
        store.addRecentSearch("snezka")
        store.addRecentSearch("  praha ")
        store.addRecentSearch("")
        XCTAssertEqual(store.recentSearches, ["praha", "snezka"])
        for index in 0..<30 {
            store.addRecentSearch("q\(index)")
        }
        XCTAssertEqual(store.recentSearches.count, UserStore.recentSearchLimit)
        store.clearRecentSearches()
        XCTAssertTrue(store.recentSearches.isEmpty)
    }

    func testSettingsRoundTrip() {
        let store = UserStore(fileURL: file)
        store.settings.language = .sk
        store.settings.theme = .dark
        store.settings.textScale = 1.3
        store.flush()
        let reloaded = UserStore(fileURL: file)
        XCTAssertEqual(reloaded.settings.language, .sk)
        XCTAssertEqual(reloaded.settings.theme, .dark)
        XCTAssertEqual(reloaded.settings.textScale, 1.3)
        XCTAssertTrue(reloaded.settings.saveHistory)
    }

    func testIndexRequestsPersistAndCanBeForgotten() {
        let store = UserStore(fileURL: file)
        store.requestIndex("wikipedia_en_top1m")
        store.requestIndex("wikipedia_en_top1m")
        store.flush()
        let reloaded = UserStore(fileURL: file)
        XCTAssertEqual(reloaded.indexRequests, ["wikipedia_en_top1m"])
        reloaded.removeIndexRequest("wikipedia_en_top1m")
        XCTAssertTrue(reloaded.indexRequests.isEmpty)
    }

    func testTextScaleSteps() {
        var settings = AppSettings()
        XCTAssertEqual(settings.steppedTextScale(1), 1.15)
        XCTAssertEqual(settings.steppedTextScale(-1), 0.9)
        settings.textScale = 1.6
        XCTAssertEqual(settings.steppedTextScale(1), 1.6)
        settings.textScale = 0.8
        XCTAssertEqual(settings.steppedTextScale(-1), 0.8)
        // A value from an older version snaps to the nearest step.
        settings.textScale = 2.0
        XCTAssertEqual(settings.steppedTextScale(-1), 1.45)
    }

    func testOlderOrPartialFileStillLoads() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let json = #"{"saved":[{"bookName":"wikipedia_cs_all","path":"Praha","title":"Praha","date":1000}],"settings":{"theme":"dark"},"futureField":42}"#
        try Data(json.utf8).write(to: file)
        let store = UserStore(fileURL: file)
        XCTAssertEqual(store.saved.map(\.path), ["Praha"])
        XCTAssertEqual(store.settings.theme, .dark)
        XCTAssertEqual(store.settings.language, .system)
        XCTAssertTrue(store.history.isEmpty)
    }

    func testCorruptFileIsSetAsideNotFatal() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: file)
        let store = UserStore(fileURL: file)
        XCTAssertTrue(store.saved.isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("user-data.corrupt.json").path))
    }
}

/// A flag a test flips while the store's writer thread reads it.
final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Bool

    init(_ value: Bool) { stored = value }

    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); stored = newValue; lock.unlock() }
    }
}
