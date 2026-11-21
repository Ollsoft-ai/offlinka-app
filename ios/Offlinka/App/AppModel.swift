import Foundation
import Observation
import UIKit

/// A transient message; equal when the text is (enough for animations).
struct Toast: Equatable {
    let id = UUID()
    let message: String
    let actionTitle: String?
    let action: (@MainActor () -> Void)?

    static func == (lhs: Toast, rhs: Toast) -> Bool { lhs.id == rhs.id }
}

/// App state: which drive is open, the reader engine, navigation and user data. The books
/// themselves (opening order, tiles, search indexes) are the `Shelf`'s job.
///
/// Phases: `starting` → (`connect` | `opening` | `unplugged`) → `ready`. While `ready`, the drive
/// is checked every few seconds and after every read error; when it disappears the app shows the
/// calm `unplugged` state and reopens by itself as soon as the drive is back.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable {
        case starting
        /// Pick the drive. `problem` explains why the last attempt failed.
        case connect(problem: ConnectProblem?)
        case opening
        case unplugged
        case ready
    }

    enum ConnectProblem: Equatable, Sendable {
        /// Picked folder cannot be read (drive pulled out while picking, …).
        case notFound
        /// No books in the picked folder: a folder inside the drive, or another drive.
        case wrongFolder
        /// Could not get permission for the folder.
        case noAccess
        /// Books are there but none could be opened.
        case unreadable

        var title: String {
            switch self {
            case .notFound: return tr(.connectErrNotfoundTitle)
            case .wrongFolder: return tr(.connectErrWrongfolderTitle)
            case .noAccess: return tr(.connectErrNoaccessTitle)
            case .unreadable: return tr(.errorTitle)
            }
        }
    }

    private enum OpenMode {
        /// The remembered drive (launch, plugged back).
        case restore
        /// Picked in the Files sheet; `accessing` = its security scope was started.
        case picked(accessing: Bool)
    }

    private(set) var phase: Phase = .starting
    private(set) var engine: ReaderEngine?
    /// The bundled guides opened over the connect / unplugged screens ("Otevřít první pomoc").
    var guidesReader: ReaderTarget?
    private(set) var guidesEngine: ReaderEngine?
    /// Short message at the bottom of the screen ("Článek je v Uložených"), optionally with an action ("Vrátit").
    var toast: Toast?
    /// Where each open reader has got to (by the page it was opened with), so a reader rebuilt after
    /// the drive came back, or after a language change, continues on the same page.
    @ObservationIgnored var resumeLocations: [ReaderTarget: ReaderTarget] = [:]

    let store: UserStore
    let router = Router()
    let shelf: Shelf
    let isDemo: Bool
    /// English top1m's index is offered on phones with 3 GB or more.
    let heavyIndexAllowed: Bool

    @ObservationIgnored private let access: DriveAccess
    @ObservationIgnored private let scanner: @Sendable (URL) throws -> DriveScan
    @ObservationIgnored private var watchTask: Task<Void, Never>?
    @ObservationIgnored private var openGeneration = 0
    @ObservationIgnored private var lastReopen = Date.distantPast
    @ObservationIgnored private var isActive = true
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    static let lowMemoryLimit: UInt64 = 3 * 1024 * 1024 * 1024

    init(store: UserStore, access: DriveAccess, engine: ContentEngine,
         scanner: @escaping @Sendable (URL) throws -> DriveScan,
         indexDirectory: URL?, bundledGuidesPath: String?, isDemo: Bool = false) {
        self.store = store
        self.access = access
        self.scanner = scanner
        self.isDemo = isDemo
        L10n.apply(store.settings.language)
        let memory = ProcessInfo.processInfo.physicalMemory
        self.heavyIndexAllowed = memory >= Self.lowMemoryLimit
        // One cluster cache for all books: the core's 48 MB, 32 MB on phones under 3 GB.
        engine.setCacheBudget(bytes: (memory < Self.lowMemoryLimit ? 32 : 48) * 1024 * 1024)
        self.shelf = Shelf(engine: engine, indexDirectory: indexDirectory, bundledGuidesPath: bundledGuidesPath,
                           uiLanguage: { L10n.languageCode })
        shelf.heavyIndexAllowed = heavyIndexAllowed
        shelf.indexRequests = { [store] in store.indexRequests }
        shelf.requestIndex = { [store] name in store.requestIndex(name) }
        shelf.removeIndexRequest = { [store] name in store.removeIndexRequest(name) }
        shelf.driveReachable = { [access] in access.isCurrentReachable() }
        shelf.onIndexingChange = { [weak self] building in self?.updateIdleTimer(building: building) }
        shelf.onContentChange = { [weak self] content in self?.contentChanged(content) }
        shelf.onDriveFailure = { [weak self] in self?.reportDriveFailure() }
    }

    /// The app as launched by the user, or the demo drive for UI tests and screenshots
    /// (launch argument `-demo-drive`, debug builds only).
    static func live(arguments: [String] = ProcessInfo.processInfo.arguments) -> AppModel {
        #if DEBUG
        if arguments.contains("-demo-drive") {
            let store = UserStore(fileURL: nil)
            if let language = arguments.firstIndex(of: "-demo-language").flatMap({ arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil }),
               let parsed = AppLanguage(rawValue: language) {
                store.settings.language = parsed
            }
            return demo(store: store)
        }
        #endif
        return AppModel(
            store: UserStore(fileURL: UserStore.defaultFileURL()),
            access: DriveAccess(bookmarkFile: DriveAccess.defaultBookmarkFile()),
            engine: CoreContentEngine(),
            scanner: { try DriveScanner().scan(root: $0) },
            indexDirectory: Shelf.defaultIndexDirectory(),
            bundledGuidesPath: Bundle.main.path(forResource: "offlinka_cs_prirucky", ofType: "zim")
        )
    }

    /// The demo drive (fake engine; the bundled guides are the demo guides).
    static func demo(store: UserStore) -> AppModel {
        AppModel(store: store, access: DriveAccess(bookmarkFile: nil), engine: FakeContentEngine.demo(),
                 scanner: { _ in FakeContentEngine.demoScan() }, indexDirectory: nil,
                 bundledGuidesPath: "/bundle/offlinka_cs_prirucky.zim", isDemo: true)
    }

    var driveName: String? {
        isDemo ? "OFFLINKA" : access.displayName
    }

    var hasRememberedDrive: Bool { access.hasBookmark }

    var content: ShelfContent? { shelf.content }

    var books: [BookRecord] {
        shelf.content?.books ?? []
    }

    var edition: String? {
        BookCatalog.edition(of: books)
    }

    // MARK: - Lifecycle

    func start() {
        guard phase == .starting else { return }
        Task { await shelf.openBuiltinGuides() }
        if isDemo {
            open(URL(fileURLWithPath: "/OFFLINKA"), mode: .restore)
            return
        }
        restoreRememberedDrive()
    }

    func sceneDidBecomeActive() {
        isActive = true
        endBackgroundTask()
        shelf.setForeground(true)
        updateIdleTimer(building: shelf.isIndexing)
        switch phase {
        case .ready:
            checkDrive()
        case .unplugged:
            restoreRememberedDrive()
        default:
            break
        }
        startWatching()
    }

    /// Indexes are built in the foreground only. A build that is running gets the usual background
    /// time to finish; when that runs out it stops cleanly and continues on the next start.
    func sceneDidEnterBackground() {
        isActive = false
        watchTask?.cancel()
        watchTask = nil
        store.flush()
        shelf.setForeground(false)
        UIApplication.shared.isIdleTimerDisabled = false
        guard shelf.isIndexing, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "offlinka-index") { [weak self] in
            MainActor.assumeIsolated {
                self?.shelf.pauseIndexing()
                self?.endBackgroundTask()
            }
        }
        shelf.whenIndexingStops { [weak self] in self?.endBackgroundTask() }
    }

    func didReceiveMemoryWarning() {
        shelf.trimMemory()
    }

    // MARK: - Picking the drive

    /// The Files sheet returned a folder (connect screen, or "Vybrat jinou flashku" with books open).
    func didPick(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            let accessing = url.startAccessingSecurityScopedResource()
            open(url, mode: .picked(accessing: accessing))
        case .failure:
            if phase == .ready {
                showToast(ConnectProblem.noAccess.title)
            } else {
                phase = .connect(problem: .noAccess)
            }
        }
    }

    /// The unplugged screen: pick another drive.
    func chooseAnotherDrive() {
        shelf.retire()
        access.release()
        phase = .connect(problem: nil)
    }

    /// Settings → "Zapomenout flashku". Nothing on the drive changes.
    func forgetDrive() {
        shelf.retire()
        access.forget()
        router.reset()
        resumeLocations = [:]
        phase = .connect(problem: nil)
    }

    func retry() {
        restoreRememberedDrive()
    }

    func retryBook(_ tileId: String) {
        Task { await shelf.retry(tileId) }
    }

    /// The guides bundled with the app, without a drive (unplugged and connect screens).
    func openBundledGuides() {
        guard let content = shelf.guidesContent(), let guides = shelf.builtinGuides else { return }
        if guidesEngine == nil {
            let settings = store.settings
            guidesEngine = ReaderEngine(content: content, appearance: ReaderAppearance(theme: settings.theme, textScale: settings.textScale))
        }
        guidesReader = ReaderTarget(bookId: guides.id, path: BookCatalog.firstAidPath)
    }

    // MARK: - Drive failures

    /// A read failed with an I/O error. If the drive is gone, show the unplugged state; if it is
    /// still there (unplugged and plugged back between checks), reopen the books once.
    func reportDriveFailure() {
        guard phase == .ready, !isDemo else { return }
        if !access.isCurrentReachable() {
            driveDisappeared()
        } else if Date().timeIntervalSince(lastReopen) > 10, let root = access.current {
            lastReopen = Date()
            open(root, mode: .restore)
        }
    }

    // MARK: - Settings

    func updateSettings(_ change: (inout AppSettings) -> Void) {
        var settings = store.settings
        change(&settings)
        if settings.language != store.settings.language {
            // Before the store changes: the root view rebuilds on that change and reads L10n.
            L10n.apply(settings.language)
            engine?.refreshTexts()
            guidesEngine?.refreshTexts()
        }
        store.settings = settings
    }

    func showToast(_ message: String, actionTitle: String? = nil, action: (@MainActor () -> Void)? = nil) {
        toast = Toast(message: message, actionTitle: actionTitle, action: action)
        toastTask?.cancel()
        let seconds: Double = action == nil ? 2.5 : 5
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    /// Runs the toast's action (Undo) and hides it.
    func performToastAction() {
        let action = toast?.action
        toast = nil
        action?()
    }

    // MARK: - Private

    /// New books published: a new reader engine (scheme handler) for them. Read errors from an
    /// older engine (requests still in flight on retired books) are ignored.
    private func contentChanged(_ content: ShelfContent?) {
        guard let content else {
            engine = nil
            return
        }
        let settings = store.settings
        let newEngine = ReaderEngine(content: content, appearance: ReaderAppearance(theme: settings.theme, textScale: settings.textScale))
        newEngine.schemeHandler.onDriveFailure = { [weak self, weak newEngine] in
            guard let self, let newEngine, self.engine === newEngine else { return }
            self.reportDriveFailure()
        }
        engine = newEngine
    }

    private func restoreRememberedDrive() {
        switch access.restore() {
        case .none:
            phase = .connect(problem: nil)
        case .unavailable:
            shelf.retire()
            phase = .unplugged
            startWatching()
        case .available(let url):
            open(url, mode: .restore)
        }
    }

    private func open(_ root: URL, mode: OpenMode) {
        openGeneration += 1
        let generation = openGeneration
        let wasReady = phase == .ready
        if !wasReady {
            phase = .opening
        }
        let scanner = self.scanner
        Task { [weak self] in
            let scanned = await Task.detached(priority: .userInitiated) { () -> Result<DriveScan, Error> in
                Result { try scanner(root) }
            }.value
            guard let self, generation == self.openGeneration else { return }
            switch scanned {
            case .failure(let error):
                self.openFailed(error, root: root, mode: mode, wasReady: wasReady)
            case .success(let scan):
                if case .picked(let accessing) = mode {
                    // Only a usable pick replaces the remembered drive.
                    do {
                        try self.access.adopt(root, alreadyAccessing: accessing)
                    } catch {
                        // No bookmark: works now, but the app will ask again next launch.
                    }
                    self.router.reset()
                    self.resumeLocations = [:]
                }
                let result = await self.shelf.load(scan: scan)
                guard generation == self.openGeneration else { return }
                switch result {
                case .ready:
                    self.phase = .ready
                    self.startWatching()
                case .driveGone:
                    self.openFailed(ContentError.io("first book not readable"), root: root, mode: mode, wasReady: wasReady)
                case .superseded:
                    break
                }
            }
        }
    }

    private func openFailed(_ error: Error, root: URL, mode: OpenMode, wasReady: Bool) {
        let problem: ConnectProblem
        let driveGone: Bool
        if let scanProblem = error as? DriveScanProblem {
            if case .noBooks = scanProblem {
                problem = .wrongFolder
                driveGone = false
            } else {
                problem = .notFound
                driveGone = true
            }
        } else if let contentError = error as? ContentError, contentError.isDriveFailure {
            problem = .notFound
            driveGone = true
        } else {
            problem = .unreadable
            driveGone = false
        }
        switch mode {
        case .picked(let accessing):
            if accessing && access.current != root {
                root.stopAccessingSecurityScopedResource()
            }
            if wasReady {
                // The books already open stay; the problem is a short message.
                showToast(problem.title)
            } else {
                phase = .connect(problem: problem)
            }
        case .restore:
            if driveGone {
                driveDisappeared()
            } else {
                shelf.retire()
                phase = .connect(problem: problem)
            }
        }
    }

    private func driveDisappeared() {
        shelf.retire()
        access.release()
        phase = .unplugged
        startWatching()
    }

    /// A long index build (English top1m: about 10 minutes) needs the screen on: with the screen off
    /// the app is suspended and the build stops (code review r2). Foreground only.
    private func updateIdleTimer(building: Bool) {
        UIApplication.shared.isIdleTimerDisabled = building && isActive
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    /// While reading: is the drive still there? While unplugged: is it back? (Foreground only.)
    private func startWatching() {
        guard !isDemo, isActive, watchTask == nil else { return }
        watchTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard let self, !Task.isCancelled else { return }
                switch self.phase {
                case .ready:
                    self.checkDrive()
                case .unplugged:
                    if case .available(let url) = self.access.restore() {
                        self.open(url, mode: .restore)
                    }
                default:
                    self.watchTask = nil
                    return
                }
            }
        }
    }

    private func checkDrive() {
        guard phase == .ready, !isDemo else { return }
        if !access.isCurrentReachable() {
            driveDisappeared()
        }
    }
}

#if DEBUG
extension AppModel {
    /// The demo drive for SwiftUI previews (books fill in a moment after the preview appears).
    static func preview(phase: Phase = .ready, language: AppLanguage = .cs) -> AppModel {
        let store = UserStore(fileURL: nil)
        store.settings.language = language
        let model = demo(store: store)
        store.recordVisit(ArticleRef(bookName: "wikipedia_cs_all", path: "Sněžka", title: "Sněžka", date: Date()))
        store.toggleSaved(ArticleRef(bookName: "wikipedia_cs_all", path: "Praha", title: "Praha", date: Date()))
        store.addRecentSearch("Komenský")
        if phase == .ready {
            model.start()
        } else {
            model.phase = phase
        }
        return model
    }
}
#endif
