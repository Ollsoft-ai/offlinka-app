import Foundation

/// The books open right now, as the reader and search see them. Thread-safe: the scheme handler
/// and search read it from background threads while the shelf adds books that finished opening.
/// A rescan or unplug publishes a new `ShelfContent` and then retires the old one (`closeFile` on
/// its books), so an in-flight read gets an I/O error page, never a crash.
final class ShelfContent: ContentSource, @unchecked Sendable {
    let coreVersion: String
    let uiLanguage: String

    private let library: ContentLibrary
    private let lock = NSLock()
    private var handles: [String: ContentBook] = [:]
    private var records: [String: BookRecord] = [:]
    private var builtinIds: Set<String> = []
    private var pending: Set<String> = []
    private var retired = false

    init(library: ContentLibrary, coreVersion: String, uiLanguage: String) {
        self.library = library
        self.coreVersion = coreVersion
        self.uiLanguage = uiLanguage
    }

    // MARK: - Shelf side

    /// Adds an opened book. `builtin`: the guides bundled with the app, never closed with the drive.
    func add(_ book: ContentBook, record: BookRecord, builtin: Bool = false) {
        lock.lock()
        handles[record.id] = book
        records[record.id] = record
        pending.remove(record.id)
        if builtin { builtinIds.insert(record.id) }
        lock.unlock()
        library.add(book)
    }

    /// A book's record changed (its search index got attached).
    func update(_ record: BookRecord) {
        lock.lock()
        if records[record.id] != nil { records[record.id] = record }
        lock.unlock()
    }

    /// Ids of books still being opened (lowercased).
    func setPending(_ ids: Set<String>) {
        lock.lock()
        pending = Set(ids.map { $0.lowercased() })
        lock.unlock()
    }

    func removePending(_ id: String) {
        lock.lock()
        pending.remove(id.lowercased())
        lock.unlock()
    }

    func handle(id: String) -> ContentBook? {
        lock.lock()
        defer { lock.unlock() }
        return handles[id.lowercased()]
    }

    func isBuiltin(_ id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return builtinIds.contains(id.lowercased())
    }

    /// Open books with their handles (index building, preloading).
    var openBooks: [(record: BookRecord, book: ContentBook)] {
        lock.lock()
        defer { lock.unlock() }
        return records.values.compactMap { record in handles[record.id].map { (record, $0) } }
    }

    /// Closes the drive's files of every book (not the bundled guides). Call only after a new
    /// state was published, so no new request reaches these books.
    func retire() {
        lock.lock()
        let toClose = handles.filter { !builtinIds.contains($0.key) }.map(\.value)
        retired = true
        pending.removeAll()
        lock.unlock()
        for book in toClose {
            book.closeFile()
        }
    }

    var isRetired: Bool {
        lock.lock()
        defer { lock.unlock() }
        return retired
    }

    // MARK: - ContentSource

    var books: [BookRecord] {
        lock.lock()
        let all = Array(records.values)
        lock.unlock()
        return BookCatalog.sorted(all, uiLanguage: uiLanguage)
    }

    func isPending(bookId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return pending.contains(bookId.lowercased())
    }

    private func book(_ bookId: String) throws -> ContentBook {
        guard let book = handle(id: bookId) else { throw ContentError.unknownBook(bookId) }
        return book
    }

    func resource(bookId: String, path: String) throws -> ContentResource {
        try book(bookId).resource(path: path)
    }

    func contentLength(bookId: String, path: String) throws -> Int64 {
        try book(bookId).contentLength(path: path)
    }

    func range(bookId: String, path: String, offset: Int64, length: Int) throws -> Data {
        try book(bookId).range(path: path, offset: offset, length: length)
    }

    func suggestAll(query: String, limit: Int) throws -> [SearchHit] {
        library.suggestAll(query: query, limit: limit).compactMap { hit in
            let id = hit.bookId.lowercased()
            guard handle(id: id) != nil else { return nil }
            return SearchHit(bookId: id, title: hit.title, path: hit.path, snippet: hit.snippet)
        }
    }

    func suggest(bookId: String, query: String, limit: Int) throws -> [SearchHit] {
        try book(bookId).suggest(query: query, limit: limit)
    }

    func randomArticle(bookId: String) throws -> String? {
        try book(bookId).randomArticle()
    }

    func illustration(bookId: String, size: Int) -> Data? {
        handle(id: bookId)?.illustration(size: size)
    }
}

extension BookRecord {
    /// A record from what the core says plus where the file is on the drive.
    init(info: ZimInfo, fileName: String, folder: DriveFolder, fileSize: Int64) {
        self.init(
            id: info.id.lowercased(),
            name: info.name.isEmpty ? info.id : info.name,
            title: info.title,
            summary: info.summary,
            language: info.language,
            creator: info.creator,
            publisher: info.publisher,
            date: info.date,
            articleCount: info.articleCount,
            mediaCount: info.mediaCount,
            mainPath: info.mainPath,
            sizeBytes: info.sizeBytes > 0 ? info.sizeBytes : fileSize,
            hasIndex: info.hasIndex,
            fileName: fileName,
            folder: folder
        )
    }

    func withIndex(_ hasIndex: Bool) -> BookRecord {
        BookRecord(id: id, name: name, title: title, summary: summary, language: language, creator: creator,
                   publisher: publisher, date: date, articleCount: articleCount, mediaCount: mediaCount,
                   mainPath: mainPath, sizeBytes: sizeBytes, hasIndex: hasIndex, fileName: fileName, folder: folder)
    }
}
