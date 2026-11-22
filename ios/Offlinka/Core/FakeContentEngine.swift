import Foundation

/// An in-memory `ContentEngine` for the demo drive, previews and tests. Books are found by the
/// file name of the opened path; failures, slow opens and index builds can be simulated.
final class FakeContentEngine: ContentEngine, @unchecked Sendable {
    let version = "fake"

    private let lock = NSLock()
    private var definitions: [String: FakeContentSource.FakeBook]
    private var failing: Set<String> = []
    private var failingIO: Set<String> = []
    private var opened: [String] = []
    private var built: [String] = []
    private var trims = 0
    private var budget: Int64 = 0
    private var openDelay: TimeInterval = 0
    private var buildDelay: TimeInterval = 0
    /// Builds of these book ids fail like a write to a full phone.
    private var failingBuilds: Set<String> = []
    private var failedBuilds: [String] = []

    /// `books` keyed by file name (`wikipedia_cs_all_maxi-offlinka_2026-09.zim`).
    init(books: [String: FakeContentSource.FakeBook]) {
        self.definitions = books
    }

    /// The demo books keyed by their record's file name.
    static func demo() -> FakeContentEngine {
        var books: [String: FakeContentSource.FakeBook] = [:]
        for book in FakeContentSource.demoBooks() {
            books[book.record.fileName] = book
        }
        return FakeContentEngine(books: books)
    }

    /// A scan of the demo drive (`/OFFLINKA/Knihovna/…`), for `Shelf.load`.
    static func demoScan(root: URL = URL(fileURLWithPath: "/OFFLINKA")) -> DriveScan {
        let entries = FakeContentSource.demoBooks().map { book in
            DriveEntry(
                url: root.appendingPathComponent(book.record.folder.rawValue).appendingPathComponent(book.record.fileName),
                folder: book.record.folder,
                name: ZimFileName(fileName: book.record.fileName),
                indexURL: nil,
                sizeBytes: book.record.sizeBytes
            )
        }
        return DriveScan(root: root, books: entries.sorted { $0.name.baseKey < $1.name.baseKey }, shadowed: [])
    }

    // MARK: - Test controls

    /// `io`: fail like an unplugged drive (`ContentError.io`), else like a damaged file.
    func failOpening(_ fileName: String, _ fails: Bool = true, io: Bool = false) {
        lock.lock()
        if fails {
            if io { failingIO.insert(fileName) } else { failing.insert(fileName) }
        } else {
            failing.remove(fileName)
            failingIO.remove(fileName)
        }
        lock.unlock()
    }

    /// Book ids whose index was built, in order.
    var buildLog: [String] {
        lock.lock()
        defer { lock.unlock() }
        return built
    }

    func define(_ fileName: String, _ book: FakeContentSource.FakeBook) {
        lock.lock()
        definitions[fileName] = book
        lock.unlock()
    }

    func setOpenDelay(_ seconds: TimeInterval) {
        lock.lock()
        openDelay = seconds
        lock.unlock()
    }

    /// Book ids whose build failed (`failBuilds`), in order.
    var failedBuildLog: [String] {
        lock.lock()
        defer { lock.unlock() }
        return failedBuilds
    }

    /// Makes the index builds of `bookIds` fail with an I/O error (no space left).
    func failBuilds(_ bookIds: Set<String>) {
        lock.lock()
        failingBuilds = bookIds
        lock.unlock()
    }

    func setBuildDelay(_ seconds: TimeInterval) {
        lock.lock()
        buildDelay = seconds
        lock.unlock()
    }

    /// File names in the order they were opened.
    var openLog: [String] {
        lock.lock()
        defer { lock.unlock() }
        return opened
    }

    var trimCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return trims
    }

    var cacheBudget: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return budget
    }

    // MARK: - ContentEngine

    func open(path: String) throws -> ContentBook {
        let fileName = (path as NSString).lastPathComponent
        lock.lock()
        let definition = definitions[fileName]
        let fails = failing.contains(fileName)
        let failsIO = failingIO.contains(fileName)
        let delay = openDelay
        lock.unlock()
        if delay > 0 { Thread.sleep(forTimeInterval: delay) }
        lock.lock()
        opened.append(fileName)
        lock.unlock()
        if failsIO { throw ContentError.io("simulated unplugged: \(fileName)") }
        if fails { throw ContentError.invalidZim("simulated: \(fileName)") }
        guard let definition else { throw ContentError.invalidZim("no fake book for \(fileName)") }
        return FakeContentBook(definition)
    }

    func newLibrary() -> ContentLibrary {
        FakeContentLibrary()
    }

    func buildIndex(_ book: ContentBook, outPath: String,
                    progress: @escaping @Sendable (Double) -> Void,
                    isCancelled: @escaping @Sendable () -> Bool) throws {
        if let fake = book as? FakeContentBook, fake.isClosed { throw ContentError.io("closed") }
        let steps = 5
        lock.lock()
        let delay = buildDelay
        let fails = failingBuilds.contains(book.info.id)
        lock.unlock()
        if fails {
            lock.lock()
            failedBuilds.append(book.info.id)
            lock.unlock()
            throw ContentError.io("No space left on device")
        }
        let part = outPath + ".part"
        FileManager.default.createFile(atPath: part, contents: Data("partial".utf8))
        for step in 1...steps {
            if delay > 0 { Thread.sleep(forTimeInterval: delay / Double(steps)) }
            if isCancelled() {
                try? FileManager.default.removeItem(atPath: part)
                throw ContentError.cancelled
            }
            progress(Double(step) / Double(steps))
        }
        try? FileManager.default.removeItem(atPath: outPath)
        try FileManager.default.moveItem(atPath: part, toPath: outPath)
        lock.lock()
        built.append(book.info.id)
        lock.unlock()
    }

    func trimMemory() {
        lock.lock()
        trims += 1
        lock.unlock()
    }

    func setCacheBudget(bytes: Int64) {
        lock.lock()
        budget = bytes
        lock.unlock()
    }
}

/// One fake book: reads from the in-memory entries; `closeFile` makes reads fail like the core.
final class FakeContentBook: ContentBook, @unchecked Sendable {
    let definition: FakeContentSource.FakeBook
    private let lock = NSLock()
    private var closed = false
    private var indexed: Bool

    init(_ definition: FakeContentSource.FakeBook) {
        self.definition = definition
        self.indexed = definition.record.hasIndex
    }

    var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    var info: ZimInfo {
        let record = definition.record
        lock.lock()
        let hasIndex = indexed
        lock.unlock()
        return ZimInfo(id: record.id, name: record.name, title: record.title, summary: record.summary,
                       language: record.language, creator: record.creator, publisher: record.publisher,
                       date: record.date, articleCount: record.articleCount, mediaCount: record.mediaCount,
                       mainPath: record.mainPath, sizeBytes: record.sizeBytes, hasIndex: hasIndex)
    }

    private func open() throws -> FakeContentSource.FakeBook {
        if isClosed { throw ContentError.io("file closed") }
        return definition
    }

    func resource(path: String) throws -> ContentResource {
        let (finalPath, entry) = try open().resolve(path)
        return ContentResource(path: finalPath, mimeType: entry.mimeType, data: entry.data)
    }

    func contentLength(path: String) throws -> Int64 {
        Int64(try open().resolve(path).1.data.count)
    }

    func range(path: String, offset: Int64, length: Int) throws -> Data {
        let data = try open().resolve(path).1.data
        let start = Int(min(max(0, offset), Int64(data.count)))
        let end = min(data.count, start + max(0, length))
        return data.subdata(in: start..<end)
    }

    func suggest(query: String, limit: Int) -> [SearchHit] {
        guard !isClosed else { return [] }
        return definition.scored(query).prefix(limit).map { $0.1 }
    }

    func randomArticle() -> String? {
        isClosed ? nil : definition.randomArticle()
    }

    func illustration(size: Int) -> Data? {
        definition.illustration
    }

    func attachIndex(path: String) throws {
        _ = try open()
        guard FileManager.default.fileExists(atPath: path) else { throw ContentError.io("no index at \(path)") }
        lock.lock()
        indexed = true
        lock.unlock()
    }

    func preloadIndex() throws {
        _ = try open()
    }

    func closeFile() {
        lock.lock()
        closed = true
        lock.unlock()
    }
}

/// Merges the books' hits like the core: by match class, Czech Wikipedia first, then add order.
final class FakeContentLibrary: ContentLibrary, @unchecked Sendable {
    private let lock = NSLock()
    private var books: [ContentBook] = []

    func add(_ book: ContentBook) {
        lock.lock()
        defer { lock.unlock() }
        guard !books.contains(where: { $0.info.id == book.info.id }) else { return }
        books.append(book)
    }

    func suggestAll(query: String, limit: Int) -> [SearchHit] {
        lock.lock()
        let all = books
        lock.unlock()
        var scored: [(score: Int, order: Int, hit: SearchHit)] = []
        let ranked = all.enumerated().sorted { lhs, rhs in
            let left = lhs.element.info.id.hasPrefix("wikipedia_cs") ? 0 : 1
            let right = rhs.element.info.id.hasPrefix("wikipedia_cs") ? 0 : 1
            return left != right ? left < right : lhs.offset < rhs.offset
        }
        for (order, element) in ranked.enumerated() {
            guard let fake = element.element as? FakeContentBook, !fake.isClosed else { continue }
            for (score, hit) in fake.definition.scored(query) {
                scored.append((score, order, hit))
            }
        }
        scored.sort { lhs, rhs in
            if lhs.score != rhs.score { return lhs.score > rhs.score }
            if lhs.order != rhs.order { return lhs.order < rhs.order }
            return lhs.hit.title.count < rhs.hit.title.count
        }
        return scored.prefix(limit).map { $0.hit }
    }
}
