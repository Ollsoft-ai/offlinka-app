import Foundation

/// What the core says about one book (`BookInfo`), before we add where it lives on the drive.
struct ZimInfo: Equatable, Sendable {
    var id: String
    var name: String
    var title: String
    var summary: String
    var language: String
    var creator: String
    var publisher: String
    var date: String
    var articleCount: Int
    var mediaCount: Int
    var mainPath: String?
    var sizeBytes: Int64
    var hasIndex: Bool
}

/// One ZIM opened in the core. Thread-safe. After `closeFile()` the object stays valid: reads fail
/// with `ContentError.io`, `suggest` returns nothing, `info` keeps its value (core round 2).
protocol ContentBook: AnyObject, Sendable {
    /// Read lazily by the core on first use (metadata), then cached.
    var info: ZimInfo { get }
    func resource(path: String) throws -> ContentResource
    func contentLength(path: String) throws -> Int64
    func range(path: String, offset: Int64, length: Int) throws -> Data
    func suggest(query: String, limit: Int) -> [SearchHit]
    func randomArticle() -> String?
    func illustration(size: Int) -> Data?
    /// Attaches a `.offidx` (reads only its header; the lookup tables load on the first search).
    func attachIndex(path: String) throws
    /// Loads the index lookup tables now (call off the main thread, e.g. when Search opens).
    func preloadIndex() throws
    /// Closes the ZIM and index files now (unplug, rescan). Never destroys the object.
    func closeFile()
}

/// Books searched together (`Library` in the core).
protocol ContentLibrary: AnyObject, Sendable {
    func add(_ book: ContentBook)
    /// Merged and ranked over all added books. `bookId` is the core's id, not lowercased.
    func suggestAll(query: String, limit: Int) -> [SearchHit]
}

/// The core as a whole: opening books, building indexes, memory.
protocol ContentEngine: Sendable {
    var version: String { get }
    func open(path: String) throws -> ContentBook
    func newLibrary() -> ContentLibrary
    /// Builds a `.offidx` for `book` at `outPath` (writes `<outPath>.part`, then renames). Blocking.
    /// `isCancelled` is polled with every progress call; returning true throws `ContentError.cancelled`.
    func buildIndex(_ book: ContentBook, outPath: String,
                    progress: @escaping @Sendable (Double) -> Void,
                    isCancelled: @escaping @Sendable () -> Bool) throws
    /// Memory warning: drop cached clusters and unload index tables (they reload on demand).
    func trimMemory()
    /// One decompressed-cluster cache shared by all books.
    func setCacheBudget(bytes: Int64)
}
