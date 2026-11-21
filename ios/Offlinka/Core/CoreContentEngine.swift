import Foundation

/// The real engine: the Rust core (`offlinka-core`) through its UniFFI Swift bindings
/// (`Generated/OfflinkaCore.swift`, compiled into this target; C symbols from
/// `Frameworks/OfflinkaCore.xcframework`).
///
/// This file is the only one that touches the generated API (`Book`, `Library`, `BookInfo`,
/// `Resource`, `Suggestion`, `LibrarySuggestion`, `CoreError`, `IndexBuildListener`, `coreVersion()`,
/// `buildIndex`, `trimMemory`, `setClusterCacheBudget`). If the bindings change shape, only this
/// file changes.
struct CoreContentEngine: ContentEngine {
    var version: String { currentCoreVersion() }

    func open(path: String) throws -> ContentBook {
        do {
            return CoreContentBook(try Book.openPath(path: path))
        } catch {
            throw Self.map(error)
        }
    }

    func newLibrary() -> ContentLibrary {
        CoreContentLibrary()
    }

    func buildIndex(_ book: ContentBook, outPath: String,
                    progress: @escaping @Sendable (Double) -> Void,
                    isCancelled: @escaping @Sendable () -> Bool) throws {
        guard let coreBook = book as? CoreContentBook else { throw ContentError.unsupported("not a core book") }
        let listener = IndexListener(progress: progress, isCancelled: isCancelled)
        do {
            try coreBuildIndex(book: coreBook.book, outPath: outPath, listener: listener)
        } catch {
            throw Self.map(error)
        }
    }

    func trimMemory() {
        coreTrimMemory()
    }

    func setCacheBudget(bytes: Int64) {
        coreSetClusterCacheBudget(bytes: UInt64(max(0, bytes)))
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
        case "cancelled": return .cancelled
        default: return .unsupported(message)
        }
    }
}

/// One core `Book`. The object is thread-safe on the Rust side.
final class CoreContentBook: ContentBook, @unchecked Sendable {
    let book: Book
    private let lock = NSLock()
    private var cachedInfo: ZimInfo?

    init(_ book: Book) {
        self.book = book
    }

    var info: ZimInfo {
        lock.lock()
        defer { lock.unlock() }
        if let cachedInfo { return cachedInfo }
        let raw = book.info()
        let info = ZimInfo(
            id: raw.id,
            name: raw.name,
            title: raw.title,
            summary: raw.description,
            language: raw.language,
            creator: raw.creator,
            publisher: raw.publisher,
            date: raw.date,
            articleCount: Int(clamping: raw.articleCount),
            mediaCount: Int(clamping: raw.mediaCount),
            mainPath: raw.mainPath,
            sizeBytes: Int64(clamping: raw.sizeBytes),
            hasIndex: raw.hasIndex
        )
        cachedInfo = info
        return info
    }

    /// `has_index` changes after `attachIndex`, so the cached info is refreshed then.
    private func refreshInfo() {
        lock.lock()
        cachedInfo = nil
        lock.unlock()
        _ = info
    }

    func resource(path: String) throws -> ContentResource {
        do {
            let resource = try book.get(path: path)
            return ContentResource(path: resource.path, mimeType: resource.mime, data: Data(resource.data))
        } catch {
            throw CoreContentEngine.map(error)
        }
    }

    func contentLength(path: String) throws -> Int64 {
        do {
            return Int64(clamping: try book.contentLength(path: path))
        } catch {
            throw CoreContentEngine.map(error)
        }
    }

    func range(path: String, offset: Int64, length: Int) throws -> Data {
        do {
            return Data(try book.getRange(path: path, offset: UInt64(max(0, offset)), len: UInt32(clamping: max(0, length))))
        } catch {
            throw CoreContentEngine.map(error)
        }
    }

    func suggest(query: String, limit: Int) -> [SearchHit] {
        let id = info.id.lowercased()
        return book.suggest(query: query, limit: UInt32(clamping: max(0, limit))).map { hit in
            SearchHit(bookId: id, title: hit.title, path: hit.path, snippet: hit.snippet)
        }
    }

    func randomArticle() -> String? {
        book.randomArticle()
    }

    func illustration(size: Int) -> Data? {
        book.illustration(size: UInt32(clamping: max(0, size))).map { Data($0) }
    }

    func attachIndex(path: String) throws {
        do {
            try book.attachIndexPath(path: path)
        } catch {
            throw CoreContentEngine.map(error)
        }
        refreshInfo()
    }

    func preloadIndex() throws {
        do {
            try book.preloadIndex()
        } catch {
            throw CoreContentEngine.map(error)
        }
    }

    func closeFile() {
        book.closeFile()
    }
}

final class CoreContentLibrary: ContentLibrary, @unchecked Sendable {
    private let library = Library()

    func add(_ book: ContentBook) {
        guard let coreBook = book as? CoreContentBook else { return }
        library.add(book: coreBook.book)
    }

    func suggestAll(query: String, limit: Int) -> [SearchHit] {
        library.suggestAll(query: query, limit: UInt32(clamping: max(0, limit))).map { hit in
            SearchHit(bookId: hit.bookId, title: hit.title, path: hit.path, snippet: nil)
        }
    }
}

/// `IndexBuildListener` (UniFFI callback interface) over two closures.
private final class IndexListener: IndexBuildListener, @unchecked Sendable {
    private let progress: @Sendable (Double) -> Void
    private let cancelled: @Sendable () -> Bool

    init(progress: @escaping @Sendable (Double) -> Void, isCancelled: @escaping @Sendable () -> Bool) {
        self.progress = progress
        self.cancelled = isCancelled
    }

    func onProgress(fraction: Double) {
        progress(fraction)
    }

    func isCancelled() -> Bool {
        cancelled()
    }
}

// At file scope so they resolve to the generated global functions, not same-named members.
private func currentCoreVersion() -> String { coreVersion() }
private func coreBuildIndex(book: Book, outPath: String, listener: IndexBuildListener) throws {
    try buildIndex(book: book, outPath: outPath, listener: listener)
}
private func coreTrimMemory() { trimMemory() }
private func coreSetClusterCacheBudget(bytes: UInt64) { setClusterCacheBudget(bytes: bytes) }
