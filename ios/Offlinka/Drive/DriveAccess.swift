import Foundation

/// Remembers the drive folder the user picked (security-scoped bookmark) and keeps its security
/// scope open while the app reads from it. Never writes to the drive.
@MainActor
final class DriveAccess {
    enum Restore: Equatable {
        /// No drive was ever picked (or it was forgotten).
        case none
        /// The remembered drive is plugged in and accessible.
        case available(URL)
        /// A drive is remembered but cannot be reached now (unplugged, or the bookmark went stale).
        case unavailable
    }

    /// Folder being read now (security scope started).
    private(set) var current: URL?
    private var accessing = false
    private var bookmark: Data?
    private let bookmarkFile: URL?

    /// `bookmarkFile` nil keeps the bookmark in memory only (demo mode, tests).
    init(bookmarkFile: URL?) {
        self.bookmarkFile = bookmarkFile
        self.bookmark = bookmarkFile.flatMap { try? Data(contentsOf: $0) }
    }

    static func defaultBookmarkFile() -> URL {
        UserStore.defaultFileURL().deletingLastPathComponent().appendingPathComponent("drive.bookmark")
    }

    var hasBookmark: Bool { bookmark != nil }

    /// Volume or folder name for Settings ("OFFLINKA").
    var displayName: String? {
        current?.lastPathComponent
    }

    /// Takes over the folder returned by the folder picker and remembers it.
    /// Throws when no bookmark could be made; the folder is still usable for this session.
    func adopt(_ picked: URL) throws {
        release()
        accessing = picked.startAccessingSecurityScopedResource()
        current = picked
        let data = try picked.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        store(data)
    }

    /// Resolves the remembered drive and opens its security scope.
    func restore() -> Restore {
        guard let bookmark else { return .none }
        if let current, Self.isReachable(current) {
            return .available(current)
        }
        release()
        var isStale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &isStale) else {
            return .unavailable
        }
        let started = url.startAccessingSecurityScopedResource()
        guard Self.isReachable(url) else {
            if started { url.stopAccessingSecurityScopedResource() }
            return .unavailable
        }
        current = url
        accessing = started
        if isStale, let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            store(fresh)
        }
        return .available(url)
    }

    /// Is the folder being read still there? (Cheap; polled while reading.)
    func isCurrentReachable() -> Bool {
        current.map(Self.isReachable) ?? false
    }

    /// Ends the security scope (drive unplugged, another drive picked).
    func release() {
        if accessing, let current {
            current.stopAccessingSecurityScopedResource()
        }
        accessing = false
        current = nil
    }

    /// Forgets the drive completely. Nothing on the drive changes.
    func forget() {
        release()
        bookmark = nil
        if let bookmarkFile {
            try? FileManager.default.removeItem(at: bookmarkFile)
        }
    }

    private func store(_ data: Data) {
        bookmark = data
        guard let bookmarkFile else { return }
        try? FileManager.default.createDirectory(at: bookmarkFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: bookmarkFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func isReachable(_ url: URL) -> Bool {
        var isDirectory: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
    }
}
