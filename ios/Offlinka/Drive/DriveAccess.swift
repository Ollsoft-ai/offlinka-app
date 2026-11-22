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
    /// Calls into the drive (`DriveProbe`): one at a time, off the main thread, with a time limit.
    private let gate = DriveProbe.Gate()

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

    /// Takes over a folder from the folder picker (after it scanned fine) and remembers it.
    /// `alreadyAccessing`: the caller started its security scope; it is ended on `release()`.
    /// Throws when no bookmark could be made; the folder is still usable for this session.
    func adopt(_ picked: URL, alreadyAccessing: Bool) throws {
        if current != picked || !accessing {
            release()
        } else if alreadyAccessing {
            // Same folder picked again: balance the extra start.
            picked.stopAccessingSecurityScopedResource()
        }
        if current == nil {
            accessing = alreadyAccessing
            current = picked
        }
        let data = try picked.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        store(data)
    }

    /// Resolves the remembered drive and opens its security scope, off the main thread. A drive that does not
    /// answer within `DriveProbe.timeout` counts as unplugged (`.unavailable`); the watcher tries again.
    func restore() async -> Restore {
        guard let bookmark else { return .none }
        if let current {
            switch await DriveProbe.run(gate: gate, { Self.isReachable(current) }) {
            case .value(true): return .available(current)
            case .busy: return .unavailable
            case .value(false), .timedOut: break
            }
            guard self.current == current else { return self.current.map { .available($0) } ?? .unavailable }
        }
        release()
        let outcome = await DriveProbe.run(gate: gate, { Self.resolve(bookmark) }, late: { late in
            // Answered after the time ran out: nobody uses this scope.
            if late.started { late.url?.stopAccessingSecurityScopedResource() }
        })
        guard case .value(let resolved) = outcome, let url = resolved.url, resolved.reachable else {
            return .unavailable
        }
        if let adopted = self.current {
            // A drive was picked while this ran: keep that one.
            if resolved.started { url.stopAccessingSecurityScopedResource() }
            return .available(adopted)
        }
        current = url
        accessing = resolved.started
        if let fresh = resolved.freshBookmark, self.bookmark == bookmark {
            store(fresh)
        }
        return .available(url)
    }

    /// Is the folder being read still there? Off the main thread; a drive that does not answer in time is gone.
    /// nil: an earlier check is still waiting for the drive (it reports itself).
    func checkCurrentReachable() async -> Bool? {
        guard let current else { return false }
        switch await DriveProbe.run(gate: gate, { Self.isReachable(current) }) {
        case .value(let reachable): return reachable
        case .timedOut: return false
        case .busy: return nil
        }
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

    private struct Resolved: Sendable {
        var url: URL?
        var started = false
        var reachable = false
        var freshBookmark: Data?
    }

    /// Bookmark → folder, security scope started, folder there? (Runs on a background thread.)
    nonisolated private static func resolve(_ bookmark: Data) -> Resolved {
        var isStale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &isStale) else {
            return Resolved()
        }
        let started = url.startAccessingSecurityScopedResource()
        guard isReachable(url) else {
            if started { url.stopAccessingSecurityScopedResource() }
            return Resolved(url: url)
        }
        let fresh = isStale ? try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) : nil
        return Resolved(url: url, started: started, reachable: true, freshBookmark: fresh)
    }

    nonisolated private static func isReachable(_ url: URL) -> Bool {
        var isDirectory: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
    }
}
