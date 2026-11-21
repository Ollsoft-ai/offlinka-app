import Foundation
import Observation

/// App state: which drive is open, the content, the reader engine, navigation and user data.
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
    }

    private(set) var phase: Phase = .starting
    private(set) var content: ContentSource?
    private(set) var engine: ReaderEngine?
    /// Phone editions that the big editions replace (shown greyed in the library).
    private(set) var shadowed: [DriveEntry] = []
    /// Short message at the bottom of the screen ("Článek je v Uložených").
    var toast: String?
    /// Where each open reader has got to (by the page it was opened with), so a reader rebuilt after
    /// the drive came back, or after a language change, continues on the same page.
    @ObservationIgnored var resumeLocations: [ReaderTarget: ReaderTarget] = [:]

    let store: UserStore
    let router = Router()
    let isDemo: Bool

    @ObservationIgnored private let access: DriveAccess
    @ObservationIgnored private let factory: ContentSourceFactory
    @ObservationIgnored private var watchTask: Task<Void, Never>?
    @ObservationIgnored private var openGeneration = 0
    @ObservationIgnored private var lastReopen = Date.distantPast
    @ObservationIgnored private var isActive = true
    @ObservationIgnored private var toastTask: Task<Void, Never>?

    init(store: UserStore, access: DriveAccess, factory: ContentSourceFactory, isDemo: Bool = false) {
        self.store = store
        self.access = access
        self.factory = factory
        self.isDemo = isDemo
        L10n.apply(store.settings.language)
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
            return AppModel(store: store, access: DriveAccess(bookmarkFile: nil), factory: DemoContentSourceFactory(), isDemo: true)
        }
        #endif
        return AppModel(
            store: UserStore(fileURL: UserStore.defaultFileURL()),
            access: DriveAccess(bookmarkFile: DriveAccess.defaultBookmarkFile()),
            factory: CoreContentSourceFactory()
        )
    }

    var driveName: String? {
        isDemo ? "OFFLINKA" : access.displayName
    }

    var books: [BookRecord] {
        content?.books ?? []
    }

    var edition: String? {
        BookCatalog.edition(of: books)
    }

    // MARK: - Lifecycle

    func start() {
        guard phase == .starting else { return }
        if isDemo {
            open(URL(fileURLWithPath: "/OFFLINKA"), picked: true)
            return
        }
        restoreRememberedDrive()
    }

    func sceneDidBecomeActive() {
        isActive = true
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

    func sceneDidEnterBackground() {
        isActive = false
        watchTask?.cancel()
        watchTask = nil
        store.flush()
    }

    // MARK: - Picking the drive

    func didPick(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            do {
                try access.adopt(url)
            } catch {
                // No bookmark: works now, but the app will ask again next launch.
            }
            open(url, picked: true)
        case .failure:
            phase = .connect(problem: .noAccess)
        }
    }

    /// Settings > "Vybrat jinou flashku" and the unplugged screen.
    func chooseAnotherDrive() {
        closeContent()
        access.release()
        phase = .connect(problem: nil)
    }

    func retry() {
        restoreRememberedDrive()
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
            open(root, picked: false)
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
        }
        store.settings = settings
    }

    func showToast(_ message: String) {
        toast = message
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    // MARK: - Private

    private func restoreRememberedDrive() {
        switch access.restore() {
        case .none:
            phase = .connect(problem: nil)
        case .unavailable:
            closeContent()
            phase = .unplugged
            startWatching()
        case .available(let url):
            open(url, picked: false)
        }
    }

    private func open(_ root: URL, picked: Bool) {
        openGeneration += 1
        let generation = openGeneration
        if phase != .ready {
            phase = .opening
        }
        let factory = self.factory
        let isDemo = self.isDemo
        Task { [weak self] in
            let outcome = await Task.detached(priority: .userInitiated) { () -> Result<(ContentSource, [DriveEntry]), Error> in
                do {
                    if isDemo {
                        let scan = DriveScan(root: root, books: [], shadowed: [])
                        return .success((try factory.open(scan: scan), []))
                    }
                    let scan = try DriveScanner().scan(root: root)
                    return .success((try factory.open(scan: scan), scan.shadowed))
                } catch {
                    return .failure(error)
                }
            }.value
            guard let self, generation == self.openGeneration else { return }
            switch outcome {
            case .success(let (source, shadowed)):
                self.closeContent()
                self.content = source
                let settings = self.store.settings
                let engine = ReaderEngine(content: source, appearance: ReaderAppearance(theme: settings.theme, textScale: settings.textScale))
                engine.schemeHandler.onDriveFailure = { [weak self] in self?.reportDriveFailure() }
                self.engine = engine
                self.shadowed = shadowed
                if picked {
                    // Another drive: start fresh. Same drive back: keep the tabs and open readers.
                    self.router.reset()
                    self.resumeLocations = [:]
                }
                self.phase = .ready
                self.startWatching()
            case .failure(let error):
                self.handleOpenFailure(error, picked: picked)
            }
        }
    }

    private func handleOpenFailure(_ error: Error, picked: Bool) {
        closeContent()
        let driveGone: Bool
        if let problem = error as? DriveScanProblem {
            if case .noBooks = problem {
                phase = .connect(problem: .wrongFolder)
                return
            }
            driveGone = true
        } else if let contentError = error as? ContentError, contentError.isDriveFailure {
            driveGone = true
        } else {
            phase = .connect(problem: .unreadable)
            return
        }
        if driveGone && picked {
            phase = .connect(problem: .notFound)
        } else {
            phase = .unplugged
            startWatching()
        }
    }

    private func driveDisappeared() {
        closeContent()
        access.release()
        phase = .unplugged
        startWatching()
    }

    private func closeContent() {
        engine = nil
        content = nil
        shadowed = []
    }

    /// While reading: is the drive still there? While unplugged: is it back?
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
                        self.open(url, picked: false)
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
    /// A ready app on the demo drive, for SwiftUI previews.
    static func preview(phase: Phase = .ready, language: AppLanguage = .cs) -> AppModel {
        let store = UserStore(fileURL: nil)
        store.settings.language = language
        let model = AppModel(store: store, access: DriveAccess(bookmarkFile: nil), factory: DemoContentSourceFactory(), isDemo: true)
        let source = FakeContentSource.demo()
        model.content = source
        model.engine = ReaderEngine(content: source, appearance: ReaderAppearance(theme: .system, textScale: 1))
        model.phase = phase
        store.recordVisit(ArticleRef(bookName: "wikipedia_cs_all", path: "Sněžka", title: "Sněžka", date: Date()))
        store.toggleSaved(ArticleRef(bookName: "wikipedia_cs_all", path: "Praha", title: "Praha", date: Date()))
        store.addRecentSearch("Komenský")
        return model
    }
}
#endif
