import Foundation
import Observation

/// An article the user saved or visited. Keyed by the book's stable `name`, not its id, so it
/// survives a new edition of the drive.
struct ArticleRef: Codable, Hashable, Identifiable, Sendable {
    var bookName: String
    var path: String
    var title: String
    var date: Date

    var id: String { bookName + "\n" + path }
}

enum AppTheme: String, Codable, CaseIterable, Sendable {
    case system, light, dark
}

struct AppSettings: Codable, Equatable, Sendable {
    var language: AppLanguage = .system
    var theme: AppTheme = .system
    /// Article text scale, one of `textScales`.
    var textScale: Double = 1.0
    /// Settings > "Ukládat historii".
    var saveHistory = true
    /// Home's first-run card „Vyzkoušejte hned“ was closed.
    var tryNowDismissed = false

    /// design/reader/inject.js range: 0.8 … 1.6 (1 = 17 px body text).
    static let textScales: [Double] = [0.8, 0.9, 1.0, 1.15, 1.3, 1.45, 1.6]

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        language = (try? container.decodeIfPresent(AppLanguage.self, forKey: .language)) ?? .system
        theme = (try? container.decodeIfPresent(AppTheme.self, forKey: .theme)) ?? .system
        textScale = (try? container.decodeIfPresent(Double.self, forKey: .textScale)) ?? 1.0
        saveHistory = (try? container.decodeIfPresent(Bool.self, forKey: .saveHistory)) ?? true
        tryNowDismissed = (try? container.decodeIfPresent(Bool.self, forKey: .tryNowDismissed)) ?? false
    }

    /// Next larger (`step` 1) or smaller (`step` -1) text scale.
    func steppedTextScale(_ step: Int) -> Double {
        let scales = Self.textScales
        let nearest = scales.enumerated().min { abs($0.element - textScale) < abs($1.element - textScale) }?.offset ?? 1
        let index = min(max(nearest + step, 0), scales.count - 1)
        return scales[index]
    }
}

/// Everything the app remembers, in one small JSON file.
struct UserData: Codable, Equatable, Sendable {
    var saved: [ArticleRef] = []
    var history: [ArticleRef] = []
    var recentSearches: [String] = []
    var settings = AppSettings()
    /// Books (ZIM names) whose heavy search index the user asked for: a restart resumes the build.
    var indexRequests: [String] = []

    init() {}

    /// Tolerant decoding: a field added in a later version (or a broken one) never loses the rest.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        saved = (try? container.decodeIfPresent([ArticleRef].self, forKey: .saved)) ?? []
        history = (try? container.decodeIfPresent([ArticleRef].self, forKey: .history)) ?? []
        recentSearches = (try? container.decodeIfPresent([String].self, forKey: .recentSearches)) ?? []
        settings = (try? container.decodeIfPresent(AppSettings.self, forKey: .settings)) ?? AppSettings()
        indexRequests = (try? container.decodeIfPresent([String].self, forKey: .indexRequests)) ?? []
    }
}

/// Saved articles, history, recent searches and settings, persisted as JSON in Application Support.
/// Nothing leaves the phone.
@MainActor
@Observable
final class UserStore {
    static let historyLimit = 500
    static let recentSearchLimit = 12

    private(set) var data: UserData

    private let fileURL: URL?
    private let writer = DispatchQueue(label: "cz.offlinka.app.userstore", qos: .utility)
    private let write: @Sendable (Data, URL) throws -> Void

    /// `fileURL` nil keeps everything in memory (previews, UI tests). `write` is injectable so tests can
    /// stand in a full disk.
    init(fileURL: URL?, write: @escaping @Sendable (Data, URL) throws -> Void = UserStore.atomicWrite) {
        self.fileURL = fileURL
        self.write = write
        self.data = Self.load(from: fileURL)
    }

    /// Writes a new file and swaps it in, so a failed write (a full phone) leaves the old file whole.
    nonisolated static func atomicWrite(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    /// Keeps everything the app stores out of iCloud and computer backups, like Android (`allowBackup=false`):
    /// saved articles, history, searches, settings and the drive bookmark (`Application Support/Offlinka`,
    /// indexes included) and the pages' own storage, such as game scores (`Library/WebKit`). Then „everything
    /// stays on this phone“ is literally true. Called at launch; a folder that is missing is created first so
    /// the flag is on it before anything is written.
    nonisolated static func excludeAppDataFromBackup(appSupport: URL? = nil, library: URL? = nil) {
        let manager = FileManager.default
        let support = appSupport ?? defaultFileURL().deletingLastPathComponent()
        let webKit = (library ?? manager.urls(for: .libraryDirectory, in: .userDomainMask).first)?
            .appendingPathComponent("WebKit", isDirectory: true)
        for folder in [support, webKit].compactMap({ $0 }) {
            try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
            #if canImport(Darwin)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var url = folder
            try? url.setResourceValues(values)
            #endif
        }
    }

    nonisolated static func defaultFileURL() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Offlinka", isDirectory: true).appendingPathComponent("user-data.json")
    }

    // MARK: - Settings

    var settings: AppSettings {
        get { data.settings }
        set {
            guard newValue != data.settings else { return }
            data.settings = newValue
            persist()
        }
    }

    // MARK: - Saved

    var saved: [ArticleRef] { data.saved }

    func isSaved(bookName: String, path: String) -> Bool {
        data.saved.contains { $0.bookName == bookName && $0.path == path }
    }

    /// Saves or un-saves. Returns the new state.
    @discardableResult
    func toggleSaved(_ article: ArticleRef) -> Bool {
        if let index = data.saved.firstIndex(where: { $0.id == article.id }) {
            data.saved.remove(at: index)
            persist()
            return false
        }
        data.saved.insert(article, at: 0)
        persist()
        return true
    }

    func removeSaved(_ article: ArticleRef) {
        data.saved.removeAll { $0.id == article.id }
        persist()
    }

    /// Puts an article back where it was (Undo after removing).
    func restoreSaved(_ article: ArticleRef, at index: Int) {
        guard !data.saved.contains(where: { $0.id == article.id }) else { return }
        data.saved.insert(article, at: min(max(index, 0), data.saved.count))
        persist()
    }

    // MARK: - History

    var history: [ArticleRef] { data.history }

    /// Moves the article to the top of the history. Pages without a title (images, redirects) are
    /// skipped, and nothing is recorded while "save history" is off.
    func recordVisit(_ article: ArticleRef) {
        guard data.settings.saveHistory else { return }
        guard !article.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        data.history.removeAll { $0.id == article.id }
        data.history.insert(article, at: 0)
        if data.history.count > Self.historyLimit {
            data.history.removeLast(data.history.count - Self.historyLimit)
        }
        persist()
    }

    func removeHistory(_ article: ArticleRef) {
        data.history.removeAll { $0.id == article.id }
        persist()
    }

    func clearHistory() {
        data.history.removeAll()
        persist()
    }

    // MARK: - Recent searches

    var recentSearches: [String] { data.recentSearches }

    func addRecentSearch(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        data.recentSearches.removeAll { $0.compare(trimmed, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame }
        data.recentSearches.insert(trimmed, at: 0)
        if data.recentSearches.count > Self.recentSearchLimit {
            data.recentSearches.removeLast(data.recentSearches.count - Self.recentSearchLimit)
        }
        persist()
    }

    func clearRecentSearches() {
        data.recentSearches.removeAll()
        persist()
    }

    // MARK: - Index requests

    var indexRequests: Set<String> { Set(data.indexRequests) }

    /// "Zastavit přípravu": the build is not resumed on the next start either.
    func removeIndexRequest(_ bookName: String) {
        guard data.indexRequests.contains(bookName) else { return }
        data.indexRequests.removeAll { $0 == bookName }
        persist()
    }

    func requestIndex(_ bookName: String) {
        guard !data.indexRequests.contains(bookName) else { return }
        data.indexRequests.append(bookName)
        persist()
    }

    // MARK: - Persistence

    /// Waits until pending writes are on disk.
    func flush() {
        writer.sync {}
    }

    private func persist() {
        guard let fileURL else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let encoded = try? encoder.encode(data) else { return }
        let write = self.write
        writer.async {
            // A full phone: the write fails quietly, the previous file stays whole, and everything is
            // still in memory; the next change writes it all again (Android: guarded Room writes).
            do {
                try write(encoded, fileURL)
            } catch {
                DebugLog.note("user data not saved: \(error.localizedDescription)")
            }
        }
    }

    private static func load(from fileURL: URL?) -> UserData {
        guard let fileURL, let raw = try? Data(contentsOf: fileURL) else { return UserData() }
        if let decoded = try? JSONDecoder().decode(UserData.self, from: raw) {
            return decoded
        }
        // Unreadable file: keep it aside for diagnosis and start fresh rather than crash.
        let aside = fileURL.deletingPathExtension().appendingPathExtension("corrupt.json")
        try? FileManager.default.removeItem(at: aside)
        try? FileManager.default.moveItem(at: fileURL, to: aside)
        return UserData()
    }
}
