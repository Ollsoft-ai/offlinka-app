import Foundation
import Observation

enum TileState: String, Equatable, Sendable {
    case opening, open, failed
}

/// One book of the drive on Home and in the Library: open, still opening, or failed (dimmed, retry).
struct ShelfTile: Identifiable, Equatable, Sendable {
    /// The book's `baseKey` (`wikipedia_cs_all`), known before it is opened.
    let id: String
    /// Nil for the guides bundled in the app.
    let entry: DriveEntry?
    var record: BookRecord?
    var state: TileState

    var kind: BookKind { BookKind.of(name: record?.name ?? id) }
    var isBuiltin: Bool { entry == nil }
    var fileName: String { record?.fileName ?? entry?.url.lastPathComponent ?? id }
}

/// A search index being built on the phone.
struct IndexProgress: Equatable, Sendable {
    let bookId: String
    let fraction: Double

    /// 0…99 for `search_index_building` ("… {n} %"); 100 only once it is attached.
    var percent: Int { min(99, max(0, Int(fraction * 100))) }
}

/// A book the core opened, with its record.
struct OpenedBook: Sendable {
    let book: ContentBook
    let record: BookRecord
}

/// Shared between the shelf and the index-building thread: should the build stop?
final class IndexControl: @unchecked Sendable {
    private let lock = NSLock()
    private var generation = 0
    private var stopped = false
    private var paused = false

    func setGeneration(_ value: Int) {
        lock.lock(); generation = value; lock.unlock()
    }

    /// The user cancelled: no more builds this session (the next launch resumes).
    func stop() {
        lock.lock(); stopped = true; lock.unlock()
    }

    func clearStop() {
        lock.lock(); stopped = false; lock.unlock()
    }

    /// Background time is running out: stop now, continue in the foreground.
    func setPaused(_ value: Bool) {
        lock.lock(); paused = value; lock.unlock()
    }

    var isStopped: Bool {
        lock.lock(); defer { lock.unlock() }
        return stopped
    }

    func shouldStop(_ buildGeneration: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return stopped || paused || buildGeneration != generation
    }
}

/// The books of the plugged-in drive (Android's `Shelf`, same rules):
/// - the UI-language Wikipedia opens first and Home shows at once; the rest open 3 at a time;
/// - a book that fails to open stays as a dimmed tile with "Zkusit znovu";
/// - a rescan or unplug publishes the new state first and only then closes the old books' files;
/// - missing search indexes are built on the phone, one by one, in the foreground, into
///   Application Support (never on the drive), the UI-language Wikipedia first; English top1m
///   only when asked; a restart resumes; an index of an old edition is deleted;
/// - the guides bundled with the app stand in when the drive has none, and work without a drive.
@MainActor
@Observable
final class Shelf {
    enum LoadResult: Equatable, Sendable {
        case ready
        /// The first book could not be read: the drive is gone.
        case driveGone
        /// Another load started meanwhile.
        case superseded
    }

    private enum BuildOutcome: Sendable {
        case built, stopped, driveFailed, failed
    }

    private(set) var tiles: [ShelfTile] = []
    private(set) var content: ShelfContent?
    private(set) var shadowed: [DriveEntry] = []
    /// Progress of the UI-language Wikipedia's index, the only one shown (UX review r2); the other
    /// books are indexed silently.
    private(set) var indexing: IndexProgress?
    /// An index is being built (any book).
    private(set) var isIndexing = false
    /// The book whose index is being built now.
    private(set) var buildingBookId: String?
    /// The guides bundled with the app, once opened.
    private(set) var builtinGuides: BookRecord?

    /// Called synchronously when new content is published, before the old content is retired.
    @ObservationIgnored var onContentChange: ((ShelfContent?) -> Void)?
    /// A read failed with an I/O error while opening or indexing: is the drive still there?
    @ObservationIgnored var onDriveFailure: (() -> Void)?
    /// Book names whose heavy index (English top1m) the user asked for; persisted by the app.
    @ObservationIgnored var indexRequests: () -> Set<String> = { [] }
    @ObservationIgnored var requestIndex: (String) -> Void = { _ in }
    @ObservationIgnored var removeIndexRequest: (String) -> Void = { _ in }
    /// Is the drive still there? A read error on one book then only fails that book's index.
    @ObservationIgnored var driveReachable: () -> Bool = { true }
    /// Building started or stopped (the app keeps the screen on meanwhile).
    @ObservationIgnored var onIndexingChange: ((Bool) -> Void)?
    /// English top1m needs about 460 MB while its index is built: offered on 3 GB+ phones only.
    @ObservationIgnored var heavyIndexAllowed = true
    @ObservationIgnored var freeSpace: (URL) -> Int64? = Shelf.availableCapacity
    @ObservationIgnored var minFreeForIndex: Int64 = 400 * 1024 * 1024

    @ObservationIgnored let engine: ContentEngine
    @ObservationIgnored let indexDirectory: URL?
    @ObservationIgnored let openParallelism: Int
    @ObservationIgnored private let uiLanguage: () -> String
    @ObservationIgnored private let bundledGuidesPath: String?
    @ObservationIgnored private var builtinBook: ContentBook?
    @ObservationIgnored private var guidesOnly: ShelfContent?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var openTask: Task<Void, Never>?
    @ObservationIgnored private var indexTask: Task<Void, Never>?
    @ObservationIgnored private var failedIndex: Set<String> = []
    @ObservationIgnored private var foreground = true
    @ObservationIgnored private var idleWaiters: [() -> Void] = []
    /// `startIndexing` was asked while a pass was running: run another pass when it ends.
    @ObservationIgnored private var indexingAskedAgain = false
    @ObservationIgnored private let control = IndexControl()

    /// `indexDirectory` nil: never build indexes (demo, tests that do not need it).
    init(engine: ContentEngine, indexDirectory: URL?, bundledGuidesPath: String?,
         uiLanguage: @escaping () -> String, openParallelism: Int = 3) {
        self.engine = engine
        self.indexDirectory = indexDirectory
        self.bundledGuidesPath = bundledGuidesPath
        self.uiLanguage = uiLanguage
        self.openParallelism = max(1, openParallelism)
    }

    static func defaultIndexDirectory() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Offlinka", isDirectory: true).appendingPathComponent("Indexes", isDirectory: true)
    }

    /// The UI-language Wikipedia (or, without one, any) has its search index: diacritics-insensitive
    /// search works, so the "no háčky needed" tip may show.
    var primaryWikipediaIndexed: Bool {
        let books = content?.books ?? []
        guard let primary = BookCatalog.primaryWikipedia(in: books, languageCode: uiLanguage()) else { return true }
        return primary.hasIndex
    }

    // MARK: - Opening

    /// Opens the guides bundled with the app (once).
    func openBuiltinGuides() async {
        guard builtinBook == nil, let path = bundledGuidesPath else { return }
        let engine = self.engine
        let opened = await Task.detached(priority: .userInitiated) { () -> OpenedBook? in
            guard let book = try? engine.open(path: path) else { return nil }
            let url = URL(fileURLWithPath: path)
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            return OpenedBook(book: book, record: BookRecord(info: book.info, fileName: url.lastPathComponent, folder: .app, fileSize: size))
        }.value
        guard builtinBook == nil, let opened else { return }
        builtinBook = opened.book
        builtinGuides = opened.record
    }

    /// Only the bundled guides, for "Otevřít první pomoc" without a drive.
    func guidesContent() -> ShelfContent? {
        if let guidesOnly { return guidesOnly }
        guard let builtinBook, let builtinGuides else { return nil }
        let content = ShelfContent(library: engine.newLibrary(), coreVersion: engine.version, uiLanguage: uiLanguage())
        content.add(builtinBook, record: builtinGuides, builtin: true)
        guidesOnly = content
        return content
    }

    /// Opens the books of `scan`: the primary book first, then publishes, then the rest in parallel.
    func load(scan: DriveScan) async -> LoadResult {
        generation += 1
        let gen = generation
        control.setGeneration(gen)
        openTask?.cancel()
        openTask = nil
        await openBuiltinGuides()
        guard gen == generation else { return .superseded }

        let ui = uiLanguage()
        let ordered = scan.books.sorted { lhs, rhs in
            let left = BookKind.of(name: lhs.name.baseKey).homeOrder(uiLanguage: ui)
            let right = BookKind.of(name: rhs.name.baseKey).homeOrder(uiLanguage: ui)
            return left != right ? left < right : lhs.name.baseKey < rhs.name.baseKey
        }
        guard !ordered.isEmpty else { return .driveGone }
        let primaryIndex = ordered.firstIndex { BookKind.of(name: $0.name.baseKey).wikipediaLanguage == ui }
            ?? ordered.firstIndex { BookKind.of(name: $0.name.baseKey).isWikipedia }
            ?? 0
        let primary = await Self.openDetached(ordered[primaryIndex], engine: engine, indexDirectory: indexDirectory)
        guard gen == generation else {
            if case .success(let opened) = primary {
                let book = opened.book
                Task.detached(priority: .utility) { book.closeFile() }
            }
            return .superseded
        }
        if case .failure(let error) = primary, error.isDriveFailure {
            return .driveGone
        }

        let newContent = ShelfContent(library: engine.newLibrary(), coreVersion: engine.version, uiLanguage: ui)
        var newTiles = ordered.map { ShelfTile(id: $0.name.baseKey, entry: $0, record: nil, state: .opening) }
        switch primary {
        case .success(let opened):
            newContent.add(opened.book, record: opened.record)
            newTiles[primaryIndex].record = opened.record
            newTiles[primaryIndex].state = .open
        case .failure:
            newTiles[primaryIndex].state = .failed
        }
        // Without guides on the drive, the copy bundled with the app stands in.
        if let builtinBook, let builtinGuides, !ordered.contains(where: { BookKind.of(name: $0.name.baseKey) == .guides }) {
            newContent.add(builtinBook, record: builtinGuides, builtin: true)
            newTiles.append(ShelfTile(id: builtinGuides.id, entry: nil, record: builtinGuides, state: .open))
            newTiles.sort { lhs, rhs in
                let left = lhs.kind.homeOrder(uiLanguage: ui)
                let right = rhs.kind.homeOrder(uiLanguage: ui)
                return left != right ? left < right : lhs.id < rhs.id
            }
        }
        newContent.setPending(Set(newTiles.filter { $0.state == .opening }.map(\.id)))
        publish(newContent, tiles: newTiles, shadowed: scan.shadowed)

        let rest = newTiles.filter { $0.state == .opening }.compactMap(\.entry)
        openTask = Task { [weak self] in
            await self?.openRest(rest, gen: gen)
            self?.startIndexing()
        }
        return .ready
    }

    /// The drive is gone, or another drive is being picked: publish "no books", then close the old ones.
    func retire() {
        generation += 1
        control.setGeneration(generation)
        openTask?.cancel()
        openTask = nil
        publish(nil, tiles: [], shadowed: [])
    }

    /// Tries again to open a book that failed.
    func retry(_ tileId: String) async {
        guard let index = tiles.firstIndex(where: { $0.id == tileId && $0.state == .failed }),
              let entry = tiles[index].entry, let content
        else { return }
        let gen = generation
        tiles[index].state = .opening
        content.setPending(Set(tiles.filter { $0.state == .opening }.map(\.id)))
        let result = await Self.openDetached(entry, engine: engine, indexDirectory: indexDirectory)
        finishOpening(entry, result, gen: gen)
        if case .success = result {
            startIndexing()
        }
    }

    private func publish(_ newContent: ShelfContent?, tiles newTiles: [ShelfTile], shadowed newShadowed: [DriveEntry]) {
        let old = content
        content = newContent
        tiles = newTiles
        shadowed = newShadowed
        if newContent == nil {
            indexing = nil
        }
        onContentChange?(newContent)
        // Only now, and off the main actor (closing waits for reads in progress, which can hang on a
        // pulled drive): requests still in flight on the old books get an I/O error page, never a crash.
        if let old, old !== newContent {
            Task.detached(priority: .utility) { old.retire() }
        }
    }

    private func openRest(_ entries: [DriveEntry], gen: Int) async {
        let engine = self.engine
        let directory = indexDirectory
        let width = openParallelism
        await withTaskGroup(of: (DriveEntry, Result<OpenedBook, ContentError>).self) { group in
            var pending = entries.makeIterator()
            for _ in 0..<width {
                guard let entry = pending.next() else { break }
                group.addTask { (entry, Self.open(entry, engine: engine, indexDirectory: directory)) }
            }
            while let finished = await group.next() {
                finishOpening(finished.0, finished.1, gen: gen)
                if let next = pending.next() {
                    group.addTask { (next, Self.open(next, engine: engine, indexDirectory: directory)) }
                }
            }
        }
    }

    private func finishOpening(_ entry: DriveEntry, _ result: Result<OpenedBook, ContentError>, gen: Int) {
        guard gen == generation, let content, let index = tiles.firstIndex(where: { $0.entry?.url == entry.url }) else {
            // A newer state took over (or the drive is gone): this book is never published, close it.
            if case .success(let opened) = result {
                let book = opened.book
                Task.detached(priority: .utility) { book.closeFile() }
            }
            return
        }
        switch result {
        case .success(let opened):
            content.add(opened.book, record: opened.record)
            tiles[index].record = opened.record
            tiles[index].state = .open
        case .failure(let error):
            content.removePending(tiles[index].id)
            tiles[index].state = .failed
            if error.isDriveFailure {
                onDriveFailure?()
            }
        }
    }

    nonisolated static func openDetached(_ entry: DriveEntry, engine: ContentEngine, indexDirectory: URL?) async -> Result<OpenedBook, ContentError> {
        await Task.detached(priority: .userInitiated) {
            open(entry, engine: engine, indexDirectory: indexDirectory)
        }.value
    }

    /// Opens one file and attaches its index: the drive's `.offidx`, else one built on the phone.
    nonisolated static func open(_ entry: DriveEntry, engine: ContentEngine, indexDirectory: URL?) -> Result<OpenedBook, ContentError> {
        do {
            let book = try engine.open(path: entry.url.path)
            if let index = entry.indexURL {
                try? book.attachIndex(path: index.path)
            } else if let directory = indexDirectory {
                let built = directory.appendingPathComponent(BookCatalog.indexFileName(zimFileName: entry.url.lastPathComponent, size: entry.sizeBytes))
                if FileManager.default.fileExists(atPath: built.path) {
                    do {
                        try book.attachIndex(path: built.path)
                    } catch ContentError.invalidZim {
                        // Not this book's index (or damaged): build it again.
                        try? FileManager.default.removeItem(at: built)
                    } catch {}
                }
            }
            let record = BookRecord(info: book.info, fileName: entry.url.lastPathComponent, folder: entry.folder, fileSize: entry.sizeBytes)
            return .success(OpenedBook(book: book, record: record))
        } catch let error as ContentError {
            return .failure(error)
        } catch {
            return .failure(.unsupported(String(describing: error)))
        }
    }

    // MARK: - Search indexes

    /// Builds the missing search indexes one by one (foreground only). Reading never waits for it.
    func startIndexing() {
        guard indexDirectory != nil, foreground, content != nil, !control.isStopped else { return }
        guard indexTask == nil else {
            // The running pass may already have picked its last book: look again when it ends.
            indexingAskedAgain = true
            return
        }
        indexingAskedAgain = false
        let gen = generation
        isIndexing = true
        onIndexingChange?(true)
        indexTask = Task { [weak self] in
            await self?.runIndexing(gen: gen)
            guard let self else { return }
            self.indexTask = nil
            self.buildingBookId = nil
            self.indexing = nil
            self.isIndexing = false
            self.onIndexingChange?(false)
            let waiters = self.idleWaiters
            self.idleWaiters = []
            waiters.forEach { $0() }
            // A rescan happened during the build, or something new was asked for: another pass.
            if self.generation != gen || self.indexingAskedAgain {
                self.startIndexing()
            }
        }
    }

    /// Book details → "Zastavit přípravu": no more builds this session, and the book's request is
    /// forgotten so the next start does not begin again (code review r2).
    func cancelIndexing(_ record: BookRecord? = nil) {
        control.stop()
        if let record {
            removeIndexRequest(record.name)
        }
    }

    /// Book details → "Připravit hledání" (English top1m).
    func buildIndexNow(_ record: BookRecord) {
        requestIndex(record.name)
        control.clearStop()
        startIndexing()
    }

    func canBuildOnDemand(_ record: BookRecord) -> Bool {
        indexDirectory != nil && heavyIndexAllowed && !record.hasIndex && BookCatalog.isOnDemandIndex(record.name)
            && !indexRequests().contains(record.name) && !(content?.isBuiltin(record.id) ?? true)
    }

    func setForeground(_ value: Bool) {
        foreground = value
        if value {
            control.setPaused(false)
            startIndexing()
        }
    }

    /// Background time is running out: the build stops (no file is left) and resumes in the foreground.
    func pauseIndexing() {
        control.setPaused(true)
    }

    /// Runs `body` once no index is being built.
    func whenIndexingStops(_ body: @escaping () -> Void) {
        if indexTask == nil {
            body()
        } else {
            idleWaiters.append(body)
        }
    }

    /// Search opened: load the index lookup tables now, off the main thread.
    func preloadIndexes() {
        guard let content else { return }
        let books = content.openBooks.filter { $0.record.hasIndex }.map(\.book)
        Task.detached(priority: .utility) {
            for book in books {
                try? book.preloadIndex()
            }
        }
    }

    /// Memory warning: drop the core's caches and index tables (they reload on demand).
    func trimMemory() {
        let engine = self.engine
        Task.detached(priority: .utility) {
            engine.trimMemory()
        }
    }

    private func runIndexing(gen: Int) async {
        guard let directory = indexDirectory else { return }
        // No build runs now: drop .part files and indexes of files that are not on this drive.
        // A file that has its .offidx on the drive does not need the phone's copy either.
        let current = Set(tiles.compactMap { tile in
            tile.entry.flatMap { $0.indexURL == nil ? BookCatalog.indexFileName(zimFileName: $0.url.lastPathComponent, size: $0.sizeBytes) : nil }
        })
        Self.prepareIndexDirectory(directory, keep: current)
        while gen == generation, foreground, !control.shouldStop(gen), let target = nextIndexTarget() {
            let file = directory.appendingPathComponent(BookCatalog.indexFileName(zimFileName: target.record.fileName, size: target.entry.sizeBytes))
            if !FileManager.default.fileExists(atPath: file.path) {
                if let free = freeSpace(directory), free < minFreeForIndex { return }
                buildingBookId = target.record.id
                if isPrimaryWikipedia(target.record) {
                    indexing = IndexProgress(bookId: target.record.id, fraction: 0)
                }
                let buildStart = DispatchTime.now().uptimeNanoseconds
                let outcome = await build(target.book, to: file, bookId: target.record.id, gen: gen)
                DebugLog.note("index \(target.record.id): \(outcome) in \(DebugLog.ms(since: buildStart)) ms")
                buildingBookId = nil
                switch outcome {
                case .built:
                    Self.removeOtherEditions(in: directory, bookKey: target.entry.name.baseKey, keep: file)
                case .stopped:
                    return
                case .driveFailed:
                    if driveReachable() {
                        // A bad sector or a full phone: only this book fails, the others go on.
                        failedIndex.insert(target.record.fileName)
                        continue
                    }
                    onDriveFailure?()
                    return
                case .failed:
                    failedIndex.insert(target.record.fileName)
                    continue
                }
            }
            guard gen == generation else { return }
            let book = target.book
            let path = file.path
            let attached = await Task.detached(priority: .utility) { (try? book.attachIndex(path: path)) != nil }.value
            guard gen == generation else { return }
            if attached {
                markIndexed(target.record)
            } else {
                try? FileManager.default.removeItem(at: file)
                failedIndex.insert(target.record.fileName)
            }
        }
    }

    private func build(_ book: ContentBook, to file: URL, bookId: String, gen: Int) async -> BuildOutcome {
        let engine = self.engine
        let control = self.control
        let progress: @Sendable (Double) -> Void = { fraction in
            Task { @MainActor in
                self.reportProgress(bookId: bookId, fraction: fraction, gen: gen)
            }
        }
        return await Task.detached(priority: .utility) { () -> BuildOutcome in
            do {
                try engine.buildIndex(book, outPath: file.path, progress: progress, isCancelled: { control.shouldStop(gen) })
                return .built
            } catch ContentError.cancelled {
                return .stopped
            } catch let error as ContentError where error.isDriveFailure {
                return control.shouldStop(gen) ? .stopped : .driveFailed
            } catch {
                return .failed
            }
        }.value
    }

    private func reportProgress(bookId: String, fraction: Double, gen: Int) {
        guard gen == generation, buildingBookId == bookId, indexing?.bookId == bookId else { return }
        indexing = IndexProgress(bookId: bookId, fraction: fraction)
    }

    private func isPrimaryWikipedia(_ record: BookRecord) -> Bool {
        BookCatalog.primaryWikipedia(in: content?.books ?? [], languageCode: uiLanguage())?.id == record.id
    }

    private func markIndexed(_ record: BookRecord) {
        let updated = record.withIndex(true)
        content?.update(updated)
        if let index = tiles.firstIndex(where: { $0.record?.id == record.id }) {
            tiles[index].record = updated
        }
    }

    /// The next book to index: automatic ones by priority (then smaller first), then requested ones.
    private func nextIndexTarget() -> (record: BookRecord, entry: DriveEntry, book: ContentBook)? {
        guard let content else { return nil }
        let ui = uiLanguage()
        let asked = indexRequests()
        var best: (record: BookRecord, entry: DriveEntry, book: ContentBook, priority: Int)?
        for tile in tiles {
            guard tile.state == .open, let record = tile.record, let entry = tile.entry, !record.hasIndex,
                  !failedIndex.contains(record.fileName), let book = content.handle(id: record.id),
                  let priority = BookCatalog.indexPriority(name: record.name, uiLanguage: ui, requested: asked.contains(record.name))
            else { continue }
            if let current = best, (current.priority, current.entry.sizeBytes) <= (priority, entry.sizeBytes) { continue }
            best = (record, entry, book, priority)
        }
        return best.map { ($0.record, $0.entry, $0.book) }
    }

    // MARK: - Index files

    /// Creates the folder, keeps it out of backups (the indexes are rebuilt from the drive), removes
    /// `.part` files a killed build left behind and indexes of files no longer on the drive.
    /// `keep` nil keeps every finished index.
    nonisolated static func prepareIndexDirectory(_ directory: URL, keep: Set<String>? = nil) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        #if canImport(Darwin)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try? url.setResourceValues(values)
        #endif
        let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for file in files {
            let orphan = file.hasSuffix(".offidx") && keep.map { !$0.contains(file) } == true
            if file.hasSuffix(".part") || orphan {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(file))
            }
        }
    }

    /// A new edition got its index: the old edition's index of the same book is dead weight.
    nonisolated static func removeOtherEditions(in directory: URL, bookKey: String, keep: URL) {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for file in files where file != keep.lastPathComponent && BookCatalog.bookKey(ofIndexFile: file) == bookKey {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(file))
        }
    }

    nonisolated static func availableCapacity(_ url: URL) -> Int64? {
        #if canImport(Darwin)
        return (try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        #else
        return nil
        #endif
    }
}

extension BookCatalog {
    /// Title of a tile, also before its book is open.
    static func title(of tile: ShelfTile) -> String {
        if let record = tile.record { return title(of: record) }
        if let entry = tile.entry { return title(ofEntry: entry) }
        return tile.id
    }

    static func description(of tile: ShelfTile) -> String {
        if let record = tile.record { return description(of: record) }
        let isMini = tile.entry?.name.flavour == .mini
        return tr(tile.kind.descriptionKey(isMini: isMini))
    }
}
