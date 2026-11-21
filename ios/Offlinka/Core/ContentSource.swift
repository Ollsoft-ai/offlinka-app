import Foundation

/// The folder on the drive a book file was found in.
enum DriveFolder: String, Codable, Hashable, Sendable, CaseIterable {
    /// `Knihovna/`: the big editions (maxi Wikipedia, maps, ...).
    case library = "Knihovna"
    /// `Telefon/`: the small phone editions (mini Wikipedia, guides, games).
    case phone = "Telefon"
    /// The guides book bundled inside the app (works without the drive).
    case app = "Offlinka"

    /// The folders a drive has.
    static let onDrive: [DriveFolder] = [.library, .phone]
}

/// One opened book (ZIM file). App-side copy of the core's `BookInfo`, plus where it lives on the drive.
struct BookRecord: Identifiable, Hashable, Sendable {
    /// The core's stable book id, lowercased: the ZIM `Name` (`wikipedia_cs_all`), else the file stem.
    /// Same for the maxi and mini edition and across editions. Also the host of `offlinka://<id>/<path>`.
    let id: String
    /// ZIM `Name` metadata (falls back to `id`). Saved articles and history key on it.
    let name: String
    let title: String
    /// The ZIM's own description (often poor; the UI prefers `BookCatalog`).
    let summary: String
    /// ISO 639-3, may be a comma list (`ces,eng`).
    let language: String
    let creator: String
    let publisher: String
    /// `YYYY-MM-DD` as stored in the ZIM.
    let date: String
    let articleCount: Int
    let mediaCount: Int
    /// Path of the main page inside the book (no leading slash), if the ZIM has one.
    let mainPath: String?
    let sizeBytes: Int64
    let hasIndex: Bool
    let fileName: String
    let folder: DriveFolder
}

/// One article or file read from a book.
struct ContentResource: Sendable, Equatable {
    /// Path after the core followed redirects. Differs from the requested path for redirects.
    let path: String
    let mimeType: String
    let data: Data
}

/// One search hit (not called `Suggestion`: that name belongs to the generated core bindings).
struct SearchHit: Hashable, Sendable {
    let bookId: String
    let title: String
    let path: String
    let snippet: String?
}

/// Errors the app distinguishes. Mirrors the core's `CoreError` plus app-side cases.
enum ContentError: Error, Equatable, Sendable {
    /// Reading the drive failed (typically: it was unplugged).
    case io(String)
    case invalidZim(String)
    case notFound(String)
    case unsupported(String)
    case unknownBook(String)
    /// `buildIndex` stopped because its listener asked to.
    case cancelled

    var isDriveFailure: Bool {
        if case .io = self { return true }
        return false
    }
}

/// Everything the UI needs from the content. The real implementation wraps the Rust core
/// (`CoreContentSource`); `FakeContentSource` drives previews, UI tests and unit tests.
///
/// All calls are synchronous and may block on USB I/O: call them off the main thread.
/// Implementations must be safe to call from any thread.
protocol ContentSource: AnyObject, Sendable {
    /// Books in display order (see `BookCatalog`).
    var books: [BookRecord] { get }
    var coreVersion: String { get }

    func resource(bookId: String, path: String) throws -> ContentResource
    func contentLength(bookId: String, path: String) throws -> Int64
    func range(bookId: String, path: String, offset: Int64, length: Int) throws -> Data
    /// Merged, ranked suggestions over all books.
    func suggestAll(query: String, limit: Int) throws -> [SearchHit]
    func suggest(bookId: String, query: String, limit: Int) throws -> [SearchHit]
    func randomArticle(bookId: String) throws -> String?
    /// PNG favicon of the book, if any.
    func illustration(bookId: String, size: Int) -> Data?
    /// The book is on the drive but still being opened (the reader shows "Otevírám…", then reloads).
    func isPending(bookId: String) -> Bool
}

extension ContentSource {
    func isPending(bookId: String) -> Bool { false }

    func book(id: String) -> BookRecord? {
        let key = id.lowercased()
        return books.first { $0.id == key }
    }

    func book(named name: String) -> BookRecord? {
        books.first { $0.name == name }
    }
}

