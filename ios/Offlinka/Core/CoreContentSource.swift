import Foundation

/// The real content source: the Rust core (`offlinka-core`) through its UniFFI Swift bindings
/// (`Generated/offlinka_core.swift`, compiled into this target, C symbols from
/// `Frameworks/OfflinkaCore.xcframework`).
///
/// This is the only file that touches the generated API (`Book`, `Library`, `BookInfo`,
/// `Resource`, `Suggestion`, `LibrarySuggestion`, `CoreError`, `coreVersion()`). If the bindings
/// change shape, only this file changes.
final class CoreContentSource: ContentSource, @unchecked Sendable {
    let books: [BookRecord]
    let coreVersion: String
    /// Books that were on the drive but could not be opened (diagnostics).
    let failures: [(fileName: String, error: ContentError)]

    // Immutable after init; the core objects are thread-safe (Arc + locks on the Rust side).
    private let library: Library
    private let coreBooks: [String: Book]

    /// Opens every book of the scan (best edition first) and attaches its `.offidx` index.
    /// Books that fail to open are skipped; throws only when none opens.
    init(scan: DriveScan) throws {
        let library = Library()
        var coreBooks: [String: Book] = [:]
        var records: [BookRecord] = []
        var failures: [(fileName: String, error: ContentError)] = []

        for entry in scan.books.sorted(by: { $0.rank > $1.rank }) {
            do {
                let book = try Book.openPath(path: entry.url.path)
                if let index = entry.indexURL {
                    // A missing or mismatched index only makes search slower; never fatal.
                    try? book.attachIndexPath(path: index.path)
                }
                let info = book.info()
                let id = info.id.lowercased()
                guard coreBooks[id] == nil else { continue }
                coreBooks[id] = book
                library.add(book: book)
                records.append(Self.record(info, entry: entry))
            } catch {
                failures.append((entry.url.lastPathComponent, Self.map(error)))
            }
        }

        guard !records.isEmpty else {
            throw failures.first?.error ?? ContentError.notFound("no books")
        }
        self.library = library
        self.coreBooks = coreBooks
        self.books = BookCatalog.sorted(records)
        self.failures = failures
        self.coreVersion = currentCoreVersion()
    }

    // MARK: - ContentSource

    func resource(bookId: String, path: String) throws -> ContentResource {
        let book = try core(bookId)
        do {
            let resource = try book.get(path: path)
            return ContentResource(path: resource.path, mimeType: resource.mime, data: Data(resource.data))
        } catch {
            throw Self.map(error)
        }
    }

    func contentLength(bookId: String, path: String) throws -> Int64 {
        let book = try core(bookId)
        do {
            return Int64(clamping: try book.contentLength(path: path))
        } catch {
            throw Self.map(error)
        }
    }

    func range(bookId: String, path: String, offset: Int64, length: Int) throws -> Data {
        let book = try core(bookId)
        do {
            let bytes = try book.getRange(path: path, offset: UInt64(max(0, offset)), len: UInt32(clamping: max(0, length)))
            return Data(bytes)
        } catch {
            throw Self.map(error)
        }
    }

    func suggestAll(query: String, limit: Int) throws -> [SearchHit] {
        library.suggestAll(query: query, limit: UInt32(clamping: max(0, limit))).map { hit in
            SearchHit(bookId: hit.bookId.lowercased(), title: hit.title, path: hit.path, snippet: nil)
        }
    }

    func suggest(bookId: String, query: String, limit: Int) throws -> [SearchHit] {
        let book = try core(bookId)
        let id = bookId.lowercased()
        return book.suggest(query: query, limit: UInt32(clamping: max(0, limit))).map { hit in
            SearchHit(bookId: id, title: hit.title, path: hit.path, snippet: hit.snippet)
        }
    }

    func randomArticle(bookId: String) throws -> String? {
        try core(bookId).randomArticle()
    }

    func illustration(bookId: String, size: Int) -> Data? {
        guard let book = coreBooks[bookId.lowercased()] else { return nil }
        return book.illustration(size: UInt32(clamping: max(0, size))).map { Data($0) }
    }

    // MARK: - Mapping

    private func core(_ bookId: String) throws -> Book {
        guard let book = coreBooks[bookId.lowercased()] else { throw ContentError.unknownBook(bookId) }
        return book
    }

    private static func record(_ info: BookInfo, entry: DriveEntry) -> BookRecord {
        let size = Int64(clamping: info.sizeBytes)
        return BookRecord(
            id: info.id.lowercased(),
            name: info.name.isEmpty ? info.id : info.name,
            title: info.title,
            summary: info.description,
            language: info.language,
            creator: info.creator,
            publisher: info.publisher,
            date: info.date,
            articleCount: Int(clamping: info.articleCount),
            mediaCount: Int(clamping: info.mediaCount),
            mainPath: info.mainPath,
            sizeBytes: size > 0 ? size : entry.sizeBytes,
            hasIndex: info.hasIndex,
            fileName: entry.url.lastPathComponent,
            folder: entry.folder
        )
    }

    /// Maps the core's `CoreError` to `ContentError` by its case name, read with `Mirror`, so this
    /// compiles whether UniFFI names the cases `Io(message:)` or `io(message:)`.
    static func map(_ error: Error) -> ContentError {
        if let mapped = error as? ContentError { return mapped }
        let message = String(describing: error)
        let mirror = Mirror(reflecting: error)
        let caseName: String
        if mirror.displayStyle == .enum, let label = mirror.children.first?.label {
            caseName = label
        } else {
            caseName = message.split(separator: "(").first.map(String.init) ?? message
        }
        switch caseName.lowercased() {
        case "io": return .io(message)
        case "invalidzim": return .invalidZim(message)
        case "notfound": return .notFound(message)
        case "unsupported": return .unsupported(message)
        default: return .unsupported(message)
        }
    }
}

/// At file scope so it resolves to the generated global `coreVersion()`, not the property.
private func currentCoreVersion() -> String {
    coreVersion()
}

/// Opens drives with the Rust core.
struct CoreContentSourceFactory: ContentSourceFactory {
    func open(scan: DriveScan) throws -> ContentSource {
        try CoreContentSource(scan: scan)
    }
}
