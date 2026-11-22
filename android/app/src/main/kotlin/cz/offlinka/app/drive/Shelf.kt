package cz.offlinka.app.drive

import android.net.Uri
import android.util.Log
import cz.offlinka.app.content.ContentBook
import cz.offlinka.app.content.ContentException
import cz.offlinka.app.content.ContentLibrary
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.reader.ServedBook
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.DriveUi
import cz.offlinka.ui.screens.ConnectProblem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A book opened from the drive (or the guides bundled with the app). */
class OpenBook(
    val ui: BookUi,
    override val content: ContentBook,
    /** The core's own id of the book ([cz.offlinka.app.content.BookMeta.id]); search hits carry it. */
    val coreId: String,
    /** The guides copy shipped inside the app: never closed with the drive's books. */
    val builtin: Boolean = false,
) : ServedBook {
    val id: String get() = ui.id
    override val mainPath: String? get() = content.info.mainPath
    override val isWiki: Boolean get() = ui.kind.isWiki
    override val isMap: Boolean get() = ui.kind == BookKind.MAPS
    override val language: String get() = ui.language
}

sealed interface ShelfState {
    /** No drive was ever picked: show "Zapojte flashku". */
    data object NoDrive : ShelfState

    data object Loading : ShelfState

    data class Problem(val problem: ConnectProblem) : ShelfState

    /** The drive was there and is gone (unplugged or unmounted). Waits for it to come back. */
    data object Unplugged : ShelfState

    data class Ready(
        /** Every book of the drive in home order, open or still opening or broken. */
        val tiles: List<BookUi>,
        /** The books that are open. */
        val books: List<OpenBook>,
        /** Phone editions not opened because a bigger edition of the same book is on the drive. */
        val replaced: List<BookUi>,
        val drives: List<DriveUi>,
        val library: ContentLibrary,
        /** Drives (tree URIs) that were reachable for this state: a change means a rescan. */
        val reachable: Set<String>,
        val generation: Int,
    ) : ShelfState {
        val byId: Map<String, OpenBook> = books.associateBy { it.id }
        val byCoreId: Map<String, OpenBook> = books.associateBy { it.coreId }
        val opening: Boolean get() = tiles.any { it.state == BookState.OPENING }
    }
}

/** On-device search index being built. */
data class IndexProgress(val bookId: String, val fraction: Double)

/** Books waiting for an index, and whether the last attempt stopped for lack of space on the phone. */
data class IndexRun(val queued: Set<String> = emptySet(), val lowSpace: Boolean = false)

/** Remembers drive names, so an unplugged drive keeps its label in Settings. */
interface DriveMemory {
    fun label(tree: String): String?
    fun remember(tree: String, label: String)
    fun forget(tree: String)

    /** Books whose heavy search index the user asked for (English top1m); kept so a restart resumes it. */
    fun indexRequests(): Set<String> = emptySet()
    fun requestIndex(bookId: String) {}
    fun cancelIndexRequest(bookId: String) {}

    /** Names of the phone-built indexes this drive's books use (its last listing), so they survive an unplug. */
    fun indexesOf(tree: String): Set<String>? = null
    fun rememberIndexes(tree: String, names: Set<String>) {}
}

class InMemoryDriveMemory : DriveMemory {
    private val labels = mutableMapOf<String, String>()
    private val requests = mutableSetOf<String>()
    override fun label(tree: String) = labels[tree]
    override fun remember(tree: String, label: String) { labels[tree] = label }
    override fun forget(tree: String) {
        labels.remove(tree)
        synchronized(indexes) { indexes.remove(tree) }
    }
    private val indexes = mutableMapOf<String, Set<String>>()
    override fun indexRequests(): Set<String> = synchronized(requests) { requests.toSet() }
    override fun requestIndex(bookId: String) { synchronized(requests) { requests += bookId } }
    override fun cancelIndexRequest(bookId: String) { synchronized(requests) { requests -= bookId } }
    override fun indexesOf(tree: String): Set<String>? = synchronized(indexes) { indexes[tree] }
    override fun rememberIndexes(tree: String, names: Set<String>) { synchronized(indexes) { indexes[tree] = names } }
}

/**
 * Finds the drive, picks the books on it, opens them in the core (the UI-language Wikipedia first, the
 * rest in parallel while the user already reads), builds missing search indexes in the background and
 * watches for the drive to go away and come back. All drive I/O runs on [io].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Shelf(
    private val drives: DriveAccess,
    private val source: ContentSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMillis: Long = 2_000,
    /** Where search indexes built on the phone are kept; null = never build. */
    private val indexDir: File? = null,
    private val memory: DriveMemory = InMemoryDriveMemory(),
    /** `cs`, `sk` or `en`: which Wikipedia opens first and leads the home screen. */
    private val uiLanguage: () -> String = { "cs" },
    /** The app is visible: the unplug watcher only polls then. */
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
    /** Path of the guides book bundled with the app, opened once at start. */
    private val builtinGuides: (() -> String?)? = null,
    private val openParallelism: Int = 3,
    private val minFreeForIndex: Long = 400L * 1024 * 1024,
) {
    private val _state = MutableStateFlow<ShelfState>(ShelfState.Loading)
    val state: StateFlow<ShelfState> = _state.asStateFlow()

    private val _indexing = MutableStateFlow<IndexProgress?>(null)
    /** The index being built right now (one at a time). */
    val indexing: StateFlow<IndexProgress?> = _indexing.asStateFlow()

    private val _indexRun = MutableStateFlow(IndexRun())
    /** Queue and space state of the index builder, for "Ve frontě" / "Málo místa". */
    val indexRun: StateFlow<IndexRun> = _indexRun.asStateFlow()

    private val _indexed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Book ids whose index was just built and attached (the one-time "Hledání je připravené"). */
    val indexed: SharedFlow<String> = _indexed

    private val lock = Mutex()
    private val publish = Mutex()
    private var watcher: Job? = null
    private var openJob: Job? = null
    private var indexJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var indexCancelled = false
    private var listing: Listing? = null

    @Volatile var builtin: OpenBook? = null
        private set

    private val _pickProblem = MutableSharedFlow<ConnectProblem>(extraBufferCapacity = 1)

    /** A drive picked while books were open could not be used (shown as a short message). */
    val pickProblem: SharedFlow<ConnectProblem> = _pickProblem

    /** The current books, or null while there is no drive. Safe from any thread. */
    val ready: ShelfState.Ready? get() = _state.value as? ShelfState.Ready

    /**
     * A page of [bookId] cannot be served yet but may be soon (the book is still opening, or the shelf is
     * loading or waiting for the drive): answer 503 so the reader reloads it, not "Článek tu není".
     */
    fun isPending(bookId: String): Boolean = when (val s = _state.value) {
        is ShelfState.Ready -> s.tiles.any { it.id == bookId && it.state == BookState.OPENING }
        ShelfState.Loading, ShelfState.Unplugged -> true
        else -> false
    }

    /** Books the reader may serve now: the drive's, or only the bundled guides without a drive. */
    fun servedBooks(): Map<String, OpenBook> =
        ready?.byId ?: builtin?.let { mapOf(it.id to it) } ?: emptyMap()

    fun start() {
        scope.launch {
            openBuiltin()
            reload()
        }
    }

    private suspend fun openBuiltin() {
        val provider = builtinGuides ?: return
        builtin = withContext(io) {
            val path = provider() ?: return@withContext null
            runCatching {
                val content = source.openPath(path)
                val name = ZimName.parse(File(path).name)
                // Search in the guides is accent-free once their (tiny) index is built on the phone.
                indexDir?.let { File(it, indexFileName(File(path).name, File(path).length())) }?.takeIf { it.isFile }?.let { idx ->
                    runCatching { content.attachIndexPath(idx.path) }.onFailure { idx.delete() }
                }
                val info = content.info
                OpenBook(uiFor(name, File(path).name, File(path).length(), DriveFolder.PICKED, null).copy(
                    zimTitle = info.title, zimDescription = info.description, articleCount = info.articleCount,
                    hasIndex = info.hasIndex, folder = "",
                ), content, info.id, builtin = true)
            }.onFailure { Log.w(TAG, "bundled guides not opened", it) }.getOrNull()
        }
    }

    /** The user picked [tree] in the system folder picker. */
    fun onPicked(tree: Uri) {
        scope.launch {
            lock.withLock {
                // With books open (Settings → "Vybrat jinou flashku") they stay usable while the new drive is scanned.
                val hadBooks = _state.value is ShelfState.Ready
                if (!hadBooks) _state.value = ShelfState.Loading
                val wasGranted = withContext(io) { runCatching { tree in drives.grantedTrees() }.getOrDefault(false) }
                val persisted = withContext(io) {
                    runCatching { drives.persist(tree) }.onFailure { Log.w(TAG, "persist failed", it) }.isSuccess
                }
                val listing = withContext(io) { scan(listOf(tree)) }
                val problem = when {
                    listing.reachable.isEmpty() -> ConnectProblem.NOT_FOUND
                    listing.files.none { it.name.endsWith(".zim", ignoreCase = true) } -> ConnectProblem.WRONG_FOLDER
                    !persisted -> ConnectProblem.NO_ACCESS
                    else -> null
                }
                // Only a new grant is given back; a drive already in use keeps its permission (a listing can fail briefly).
                if (problem != null && problem != ConnectProblem.NO_ACCESS && persisted && !wasGranted) {
                    withContext(io) { drives.release(tree) }
                }
                when {
                    problem == null -> loadLocked()
                    // The books already open stay; the problem is a short message instead of the connect screen.
                    hadBooks -> _pickProblem.tryEmit(problem)
                    else -> _state.value = ShelfState.Problem(problem)
                }
            }
        }
    }

    /** The picker was cancelled or failed. */
    fun onPickCancelled() {
        if (_state.value is ShelfState.Loading) scope.launch { reload() }
    }

    /** Re-scans every granted drive. */
    suspend fun reload() = lock.withLock { loadLocked() }

    fun reloadAsync() {
        scope.launch { reload() }
    }

    /** Settings → "Zapomenout flashku": drop the permission of a drive that is not plugged in. */
    fun forget(tree: String) {
        scope.launch {
            withContext(io) {
                val volume = DriveTrees.volumeId(Uri.parse(tree))
                val trees = (drives.grantedTrees().filter { DriveTrees.volumeId(it) == volume } + Uri.parse(tree)).distinct()
                trees.forEach { t ->
                    drives.release(t)
                    memory.forget(t.toString())
                }
            }
            reload()
        }
    }

    /** Tries again to open a book that failed. */
    fun retry(bookId: String) {
        scope.launch {
            val now = ready ?: return@launch
            val l = listing ?: return@launch
            val tile = now.tiles.firstOrNull { it.id == bookId && it.state == BookState.FAILED } ?: return@launch
            val choice = BookSelector.select(l.files, uiLanguage()).firstOrNull { it.key == bookId } ?: return@launch
            publishTile(now.generation, tile.copy(state = BookState.OPENING), null)
            var opened: OpenBook? = null
            var published = false
            try {
                opened = withContext(io) { openChoice(choice, l) }
                published = publishTile(now.generation, opened?.ui ?: tile.copy(state = BookState.FAILED), opened)
            } finally {
                // Never published (unplugged or reloaded meanwhile): nobody references it, free it now.
                if (!published) opened?.let { destroyLater(it) }
            }
        }
    }

    /**
     * Something failed while reading a book. If the drive is gone, close everything and wait for it.
     * Called from WebView and search threads.
     */
    fun reportIoError(e: Throwable) {
        Log.w(TAG, "read failed: ${e.javaClass.simpleName}")
        scope.launch {
            val current = ready ?: return@launch
            val alive = withContext(io) { reachableTrees() }
            if (alive.isEmpty()) {
                lock.withLock {
                    if ((_state.value as? ShelfState.Ready)?.generation == current.generation) {
                        val now = _state.value as ShelfState.Ready
                        generation++
                        openJob?.cancel()
                        indexJob?.cancel()
                        // Publish first, close after: a WebView thread must never get a closed book.
                        _state.value = ShelfState.Unplugged
                        retire(now)
                        watchForReturn()
                    }
                }
            }
        }
    }

    /** A storage volume was mounted or removed, or the app came back to the front: check the drives. */
    fun onStorageChanged() {
        scope.launch {
            when (val s = _state.value) {
                is ShelfState.Ready -> {
                    val alive = withContext(io) { reachableTrees() }
                    when {
                        alive.isEmpty() -> reportIoError(IOException("volume removed"))
                        // Only a change of the reachable drives is worth a rescan (not a stale old permission).
                        alive.map { it.toString() }.toSet() != s.reachable -> reload()
                    }
                }
                is ShelfState.Unplugged, is ShelfState.Problem -> reload()
                else -> Unit
            }
        }
    }

    /** Stops building search indexes for this session. */
    fun cancelIndexing() {
        indexCancelled = true
    }

    /** Book details → "Zastavit přípravu": stop this book's index now, forget the request, go on with the others. */
    fun stopIndex(bookId: String) {
        memory.cancelIndexRequest(bookId)
        stoppedIndex += bookId
        updateQueue()
    }

    private fun reachableTrees(): List<Uri> = drives.grantedTrees().filter { reachable(it) }

    private fun watchForReturn() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive && _state.value is ShelfState.Unplugged) {
                delay(pollMillis)
                foreground.first { it }
                val back = withContext(io) { reachableTrees().isNotEmpty() }
                if (back) {
                    reload()
                    break
                }
            }
        }
    }

    /** Problem(NOT_FOUND) after a reboot with the drive not yet mounted: retry quietly for a minute. */
    private fun watchForReturnFromProblem() {
        watcher?.cancel()
        watcher = scope.launch {
            repeat(30) {
                delay(pollMillis)
                foreground.first { it }
                if (_state.value !is ShelfState.Problem) return@launch
                val back = withContext(io) { reachableTrees().isNotEmpty() }
                if (back) {
                    reload()
                    return@launch
                }
            }
        }
    }

    private fun reachable(tree: Uri): Boolean = drives.hasPermission(tree) && runCatching { drives.list(tree, null) }.isSuccess

    private suspend fun loadLocked() {
        val before = _state.value
        val gen = ++generation
        openJob?.cancel()
        indexJob?.cancel()
        val trees = withContext(io) { drives.grantedTrees() }
        if (trees.isEmpty()) {
            _state.value = ShelfState.NoDrive
            (before as? ShelfState.Ready)?.let { retire(it) }
            return
        }
        if (before !is ShelfState.Ready) _state.value = ShelfState.Loading
        val l = withContext(io) { scan(trees).also { cleanupIndexes(it) } }
        if (l.reachable.isEmpty()) {
            _state.value = if (before is ShelfState.Ready || before is ShelfState.Unplugged) {
                ShelfState.Unplugged.also { watchForReturn() }
            } else {
                ShelfState.Problem(ConnectProblem.NOT_FOUND).also { watchForReturnFromProblem() }
            }
            (before as? ShelfState.Ready)?.let { retire(it) }
            return
        }
        val ui = uiLanguage()
        // The drive's guides edition still explains the Kiwix apps (founders' rule until the store launch):
        // the app only ever shows its own bundled edition, so the drive's copy is left out everywhere.
        // A folder holding only the drive's guides is still the drive (it shows the app's guides then).
        if (BookSelector.select(l.files, ui).isEmpty()) {
            _state.value = ShelfState.Problem(ConnectProblem.WRONG_FOLDER)
            (before as? ShelfState.Ready)?.let { retire(it) }
            return
        }
        val choices = BookSelector.select(l.files.filterNot { isDriveGuides(it.name) }, ui)
        listing = l
        // The book people start with opens first; Home shows as soon as it is there.
        val primaryIndex = if (choices.isEmpty()) {
            -1
        } else {
            choices.indexOfFirst { it.name.kind == BookKind.WIKIPEDIA && BookSelector.uiLanguage(it.name.language) == ui }
                .takeIf { it >= 0 } ?: choices.indexOfFirst { it.name.kind == BookKind.WIKIPEDIA }.takeIf { it >= 0 } ?: 0
        }
        val primary = choices.getOrNull(primaryIndex)?.let { withContext(io) { openChoice(it, l) } }
        val library = withContext(io) { source.newLibrary().also { lib -> primary?.let { lib.add(it.content) } } }
        val books = listOfNotNull(primary).toMutableList()
        val tiles = choices.mapIndexed { i, c ->
            when {
                i != primaryIndex -> uiFor(c.name, c.zim.name, c.zim.size, c.zim.folder, l.labels[c.zim.driveIndex]).copy(state = BookState.OPENING)
                primary != null -> primary.ui
                else -> uiFor(c.name, c.zim.name, c.zim.size, c.zim.folder, l.labels[c.zim.driveIndex]).copy(state = BookState.FAILED)
            }
        }.toMutableList()
        // Without guides on the drive, the copy bundled with the app stands in.
        // Opened at start; tried again here when that failed (or the shelf was used without start()).
        if (builtin == null) openBuiltin()
        val guides = builtin
        if (guides != null) {
            books += guides
            withContext(io) { library.add(guides.content) }
            tiles += guides.ui
            tiles.sortBy { t -> tileOrder(t, ui) }
        }
        val opening = choices.filterIndexed { i, _ -> i != primaryIndex }
        // Fallback editions; an identical copy of a chosen file (guides in Knihovna and Telefon) is not listed.
        val replaced = choices.flatMap { c -> listOf(c.zim) + c.fallbacks }
            .filter { f -> choices.none { it.zim.name == f.name } }
            .map { f ->
                val n = ZimName.parse(f.name)
                uiFor(n, f.name, f.size, f.folder, l.labels[f.driveIndex])
            }
        val readyState = ShelfState.Ready(
            tiles = tiles,
            books = books,
            replaced = replaced,
            // A drive's book count still includes its own guides file (it is on the drive).
            drives = drivesUi(trees, l, BookSelector.select(l.files, ui)),
            library = library,
            reachable = l.reachable.map { it.toString() }.toSet(),
            generation = gen,
        )
        // Publish first, close the old books after: in-flight WebView requests switch to the new ones.
        publish.withLock { _state.value = readyState }
        (before as? ShelfState.Ready)?.let { retire(it) }
        openJob = scope.launch {
            coroutineScope {
                val limited = io.limitedParallelism(openParallelism)
                for (c in opening) {
                    launch(limited) {
                        var b: OpenBook? = null
                        var published = false
                        try {
                            b = openChoice(c, l)
                            val tile = b?.ui ?: uiFor(c.name, c.zim.name, c.zim.size, c.zim.folder, l.labels[c.zim.driveIndex])
                                .copy(state = BookState.FAILED)
                            published = publishTile(gen, tile, b)
                        } finally {
                            // Opened but never published (reload, unplug, cancel): nobody holds it, free it now.
                            if (!published) b?.let { destroyLater(it) }
                        }
                    }
                }
            }
            // A drive pulled during the first scan leaves only failed tiles: show the unplugged cover now.
            val now = ready
            if (now != null && now.generation == gen && now.tiles.any { it.state == BookState.FAILED }) {
                if (withContext(io) { reachableTrees().isEmpty() }) reportIoError(IOException("drive gone while opening"))
            }
            startIndexing(gen)
        }
    }

    private fun tileOrder(t: BookUi, ui: String): Int =
        BookSelector.homeOrder(ZimName.parse(t.fileName.ifEmpty { t.id }), ui)

    /** Replaces one tile (and adds its book) in the current state if [gen] is still current. */
    private suspend fun publishTile(gen: Int, tile: BookUi, book: OpenBook?): Boolean = publish.withLock {
        val now = _state.value as? ShelfState.Ready ?: return@withLock false
        if (now.generation != gen || gen != generation) return@withLock false
        if (book != null && now.byId[book.id] == null) withContext(io) { runCatching { now.library.add(book.content) } }
        if (gen != generation) return@withLock false
        // Compare-and-set: an Unplugged (or newer) state written meanwhile is never overwritten by this old one.
        _state.compareAndSet(
            now,
            now.copy(
                tiles = now.tiles.map { if (it.id == tile.id) tile else it },
                books = if (book != null) now.books.filter { it.id != book.id } + book else now.books,
            ),
        )
    }

    /** One row per physical drive: Knihovna and Telefon picked separately on the same volume are one drive. */
    private fun drivesUi(trees: List<Uri>, l: Listing, choices: List<BookChoice>): List<DriveUi> =
        trees.indices.groupBy { DriveTrees.volumeId(trees[it]) }.values.map { idx ->
            val label = idx.firstNotNullOfOrNull { l.labels[it] } ?: idx.firstNotNullOfOrNull { memory.label(trees[it].toString()) }
            DriveUi(
                id = trees[idx.first()].toString(),
                name = label ?: DEFAULT_LABEL,
                bookCount = choices.count { it.zim.driveIndex in idx },
                connected = idx.any { trees[it] in l.reachable },
                edition = idx.firstNotNullOfOrNull { l.editions.getOrNull(it) },
                totalBytes = l.files.filter { it.driveIndex in idx && it.name.endsWith(".zim", true) }.sumOf { it.size },
            )
        }

    /** Files whose index failed to build or attach this session (not retried until the next start). */
    private val failedIndex: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    /** Books the user stopped in book details this session. */
    private val stoppedIndex: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    @Volatile private var indexJobGen = -1

    /** Book details → "Připravit hledání" for a book that is not indexed automatically (English top1m). */
    fun buildIndexNow(bookId: String) {
        memory.requestIndex(bookId)
        stoppedIndex -= bookId
        indexCancelled = false
        updateQueue()
        ready?.let { startIndexing(it.generation) }
    }

    /** Not indexed automatically (too heavy), not asked for yet, and there is somewhere to build it. */
    fun canBuildOnDemand(book: OpenBook): Boolean =
        indexDir != null && !book.builtin && BookSelector.onDemandIndex(ZimName.parse(book.ui.fileName)) &&
            book.id !in memory.indexRequests()

    /** Books still waiting for an index, in build order (automatic ones by priority, then those asked for). */
    private fun indexTargets(ui: String): List<OpenBook> {
        val now = ready ?: return emptyList()
        if (indexDir == null) return emptyList()
        val asked = memory.indexRequests()
        return now.books
            .filter { !it.ui.hasIndex && it.ui.fileName !in failedIndex && it.id !in stoppedIndex }
            .mapNotNull { b ->
                val p = BookSelector.indexPriority(ZimName.parse(b.ui.fileName), ui) ?: if (b.id in asked) 10 else null
                p?.let { b to it }
            }
            .sortedWith(compareBy({ it.second }, { it.first.ui.sizeBytes }))
            .map { it.first }
    }

    private fun updateQueue(lowSpace: Boolean = _indexRun.value.lowSpace) {
        _indexRun.value = IndexRun(indexTargets(uiLanguage()).map { it.id }.toSet(), lowSpace)
    }

    /**
     * Builds missing search indexes one by one into app storage (never on the drive), in the background.
     * Reading never waits for it; a restart continues with what is still missing (a killed build starts over).
     */
    private fun startIndexing(gen: Int) {
        val dir = indexDir ?: return
        if (indexJob?.isActive == true && indexJobGen == gen) return
        val previous = indexJob
        indexJobGen = gen
        indexJob = scope.launch(io) {
            previous?.cancelAndJoin()
            val ui = uiLanguage()
            updateQueue(lowSpace = false)
            while (isActive && gen == generation && !indexCancelled) {
                val b = indexTargets(ui).firstOrNull() ?: break
                val size = if (b.builtin) b.ui.sizeBytes else listing?.files?.firstOrNull { it.name == b.ui.fileName }?.size ?: b.ui.sizeBytes
                val file = File(dir, indexFileName(b.ui.fileName, size))
                if (!file.isFile) {
                    dir.mkdirs()
                    if (dir.usableSpace < minFreeForIndex) {
                        updateQueue(lowSpace = true)
                        break
                    }
                    _indexing.value = IndexProgress(b.id, 0.0)
                    try {
                        source.buildIndex(
                            b.content, file.path,
                            onProgress = { f -> _indexing.value = IndexProgress(b.id, f) },
                            isCancelled = { !isActive || gen != generation || indexCancelled || b.id in stoppedIndex },
                        )
                    } catch (e: ContentException.Cancelled) {
                        _indexing.value = null
                        if (b.id in stoppedIndex) {
                            updateQueue()
                            continue
                        }
                        break
                    } catch (e: ContentException.Io) {
                        _indexing.value = null
                        File(file.path + ".part").delete()
                        if (gen != generation) break
                        // A bad sector or a full phone: skip this book. Only a gone drive stops everything.
                        if (reachableTrees().isEmpty()) {
                            reportIoError(e)
                            break
                        }
                        Log.w(TAG, "index of ${b.id} failed, going on", e)
                        failedIndex += b.ui.fileName
                        updateQueue()
                        continue
                    } catch (e: Exception) {
                        _indexing.value = null
                        Log.w(TAG, "index of ${b.id} not built", e)
                        failedIndex += b.ui.fileName
                        updateQueue()
                        continue
                    }
                }
                val attached = runCatching { b.content.attachIndexPath(file.path) }
                    .onFailure {
                        Log.w(TAG, "index ${file.name} not attached", it)
                        file.delete()
                        failedIndex += b.ui.fileName
                    }.isSuccess
                _indexing.value = null
                val indexed = OpenBook(b.ui.copy(hasIndex = true), b.content, b.coreId, b.builtin)
                if (attached && b.builtin) builtin = indexed
                if (attached && publishTile(gen, indexed.ui, indexed)) {
                    _indexed.tryEmit(b.id)
                }
                updateQueue()
            }
            _indexing.value = null
            if (!_indexRun.value.lowSpace) _indexRun.value = IndexRun(emptySet(), false)
        }
    }

    /**
     * Keeps the index folder to what the drives still use: an index survives while a remembered drive's last
     * listing has its book (so unplugging a second drive keeps its indexes), half-built `.part` files go, and
     * a phone-built index goes once the drive ships its own `.offidx`.
     */
    private fun cleanupIndexes(l: Listing) {
        val dir = indexDir ?: return
        val shipped = l.files.filter { it.name.endsWith(".offidx", true) }
            .associateBy { Triple(it.driveIndex, it.folder, it.name.substringBeforeLast('.').lowercase()) }
        l.trees.forEachIndexed { i, t ->
            if (t !in l.reachable) return@forEachIndexed
            val names = l.files.filter { it.driveIndex == i && it.name.endsWith(".zim", true) && BookSelector.indexFor(it, shipped) == null }
                .map { indexFileName(it.name, it.size) }.toSet()
            runCatching { memory.rememberIndexes(t.toString(), names) }
        }
        val keep = l.trees.flatMap { memory.indexesOf(it.toString()).orEmpty() }.toSet() +
            listOfNotNull(builtin?.let { indexFileName(it.ui.fileName, it.ui.sizeBytes) })
        dir.listFiles()?.forEach { f ->
            val stale = f.name.endsWith(".part") || (f.name.endsWith(".offidx") && f.name !in keep)
            if (stale && f.delete()) Log.i(TAG, "removed index ${f.name}")
        }
    }

    /** Search opened: load the in-memory part of every attached index now, off the UI thread. */
    fun preloadIndexes() {
        val books = ready?.books ?: return
        scope.launch(io) {
            books.filter { it.ui.hasIndex }.forEach { b -> runCatching { b.content.preloadIndex() } }
        }
    }

    private class Listing(
        val files: List<DriveFile>,
        val reachable: Set<Uri>,
        val labels: List<String?>,
        val trees: List<Uri>,
        /** Edition of each drive ("2026-11") read from its README.txt, when present. */
        val editions: List<String?> = emptyList(),
    )

    private fun scan(trees: List<Uri>): Listing {
        val files = mutableListOf<DriveFile>()
        val reachable = mutableSetOf<Uri>()
        val labels = mutableListOf<String?>()
        val editions = MutableList<String?>(trees.size) { null }
        trees.forEachIndexed { index, tree ->
            val folderName = runCatching { drives.label(tree) }.getOrNull()
            // A folder inside the drive was picked (Knihovna, Telefon): name the drive, not the folder.
            val label = if (DriveTrees.pickedPath(tree).isEmpty()) {
                folderName
            } else {
                runCatching { drives.volumeLabel(tree) }.getOrNull() ?: DEFAULT_LABEL
            }
            labels += label
            val picked = try {
                drives.list(tree, null)
            } catch (e: IOException) {
                Log.i(TAG, "drive $tree not reachable: $e")
                return@forEachIndexed
            }
            reachable += tree
            // A copy of the drive in phone storage (the review sample unzipped into Download) is picked one level
            // too high: when the only relevant child is OFFLINKA, read that folder instead.
            var root = picked
            var nested: DocEntry? = null
            repeat(2) { // Download/OFFLINKA, or OFFLINKA/OFFLINKA when the zip was unzipped into its own folder
                val inner = DriveLayout.nestedDrive(root) ?: return@repeat
                root = runCatching { drives.list(tree, inner.id) }.getOrNull() ?: return@repeat
                nested = inner
            }
            if (label != null) runCatching { memory.remember(tree.toString(), label) }
            root.firstOrNull { !it.isDir && it.name.equals("README.txt", ignoreCase = true) && it.size in 1..65_536 }?.let { readme ->
                editions[index] = runCatching {
                    drives.openStream(tree, readme.id).use { DriveEdition.parse(it.readBytes().toString(Charsets.UTF_8)) }
                }.getOrNull()
            }
            fun add(entries: List<DocEntry>, folder: DriveFolder) {
                entries.filter { !it.isDir && (it.name.endsWith(".zim", true) || it.name.endsWith(".offidx", true)) }
                    .forEach { files += DriveFile(it.name, folder, it.size, "$index\u0000${it.id}", index) }
            }
            // The picked folder itself counts too: some phones refuse the drive's root, then people pick Knihovna.
            add(
                root,
                when {
                    nested != null -> DriveFolder.PICKED
                    folderName.equals("Knihovna", true) -> DriveFolder.LIBRARY
                    folderName.equals("Telefon", true) -> DriveFolder.PHONE
                    else -> DriveFolder.PICKED
                },
            )
            for (dir in root.filter { it.isDir }) {
                val folder = when {
                    dir.name.equals("Knihovna", ignoreCase = true) -> DriveFolder.LIBRARY
                    dir.name.equals("Telefon", ignoreCase = true) -> DriveFolder.PHONE
                    else -> continue
                }
                runCatching { drives.list(tree, dir.id) }
                    .onSuccess { add(it, folder) }
                    .onFailure { Log.w(TAG, "listing ${dir.name} failed", it) }
            }
        }
        return Listing(files, reachable, labels, trees, editions)
    }

    /** Opens the best file of a book, falling back to its other editions. */
    private fun openChoice(choice: BookChoice, l: Listing): OpenBook? {
        for (file in listOf(choice.zim) + choice.fallbacks) {
            openOne(file, l)?.let { return it }
        }
        return null
    }

    private fun openOne(file: DriveFile, l: Listing): OpenBook? {
        val (treeIndex, docId) = file.handle.split('\u0000', limit = 2).let { it[0].toInt() to it[1] }
        val tree = l.trees[treeIndex]
        return try {
            val content = source.openFd(drives.openFd(tree, docId), file.name)
            val name = ZimName.parse(file.name)
            // The core reads indexes with pread (no mmap), so a pulled drive gives Io errors, never SIGBUS.
            val driveIndex = BookSelector.indexFor(
                file,
                l.files.filter { it.name.endsWith(".offidx", true) }
                    .associateBy { Triple(it.driveIndex, it.folder, it.name.substringBeforeLast('.').lowercase()) },
            )
            if (driveIndex != null) {
                runCatching { content.attachIndexFd(drives.openFd(tree, driveIndex.handle.substringAfter('\u0000'))) }
                    .onFailure { Log.w(TAG, "index ${driveIndex.name} not attached", it) }
            } else {
                val built = indexDir?.let { File(it, indexFileName(file.name, file.size)) }
                if (built != null && built.isFile) {
                    runCatching { content.attachIndexPath(built.path) }
                        .onFailure {
                            Log.w(TAG, "built index ${built.name} not attached", it)
                            built.delete()
                        }
                }
            }
            val info = content.info
            val ui = uiFor(name, file.name, file.size, file.folder, l.labels[treeIndex]).copy(
                language = BookSelector.uiLanguage(name.language.ifEmpty { info.language }),
                zimTitle = info.title.ifBlank { file.name.removeSuffix(".zim") },
                zimDescription = info.description,
                sizeBytes = if (info.sizeBytes > 0) info.sizeBytes else file.size,
                date = name.date.ifEmpty { info.date.take(7) },
                zimDate = info.date,
                articleCount = info.articleCount,
                mediaCount = info.mediaCount,
                hasIndex = info.hasIndex,
                publisher = info.publisher,
                creator = info.creator,
                state = BookState.OPEN,
            )
            OpenBook(ui, content, info.id)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${file.name}", e)
            null
        }
    }

    private fun uiFor(n: ZimName, fileName: String, size: Long, folder: DriveFolder, drive: String?) = BookUi(
        id = n.key, kind = n.kind, language = BookSelector.uiLanguage(n.language), region = n.region,
        zimTitle = fileName.removeSuffix(".zim"), flavour = n.flavour, topic = n.topic, sizeBytes = size, date = n.date,
        fileName = fileName, folder = folder.label, driveName = drive,
    )

    /**
     * Retires the books of an old state. WebView threads and media streams may still hold them, so they
     * are not destroyed here: closeFile() releases the drive's files at once (Android can unmount, no fd
     * leak) and later reads fail softly with Io (served as 503, the reader reloads). The native objects
     * are freed by the UniFFI cleaner once nothing references them.
     */
    private fun retire(ready: ShelfState.Ready) {
        val books = ready.books.filter { !it.builtin }
        if (books.isEmpty()) return
        // Off the main thread: closing waits for reads in progress, which hang while USB retries on a pulled drive.
        scope.launch(io + NonCancellable) { books.forEach { b -> runCatching { b.content.closeFile() } } }
    }

    /** A book that was never published: no other thread can hold it, so it is destroyed (off the main thread). */
    private fun destroyLater(b: OpenBook) {
        scope.launch(io + NonCancellable) { runCatching { b.content.close() } }
    }

    /** Book to use for "Náhodný článek" and the default search scope: the UI-language Wikipedia when present. */
    fun primaryWikipedia(): OpenBook? = ready?.books?.let { books ->
        val ui = uiLanguage()
        books.firstOrNull { it.ui.kind == BookKind.WIKIPEDIA && it.ui.language == ui }
            ?: books.firstOrNull { it.ui.kind == BookKind.WIKIPEDIA && it.ui.language == "cs" }
            ?: books.firstOrNull { it.ui.kind == BookKind.WIKIPEDIA }
    }

    fun book(id: String): OpenBook? = servedBooks()[id]

    companion object {
        private const val TAG = "OfflinkaShelf"
        const val DEFAULT_LABEL = "OFFLINKA"

        /**
         * On-device index format tag. Bumped when indexes built by older versions must be rebuilt once:
         * `v5b` = core index v5 built by the hardened core (an index built while a drive was being pulled
         * could rank wrongly up to app 0.3.0). Files with other names are removed by the load clean-up.
         */
        const val INDEX_TAG = "v5b"

        /** The drive's own guides book (or its index): never shown, the app has its own edition. */
        fun isDriveGuides(fileName: String): Boolean =
            ZimName.parse(fileName.substringBeforeLast('.') + ".zim").kind == BookKind.GUIDES

        /** Name of a search index built on the phone for a ZIM file (bound to that exact file and format). */
        fun indexFileName(fileName: String, size: Long): String =
            fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + size + "-" + INDEX_TAG + ".offidx"

    }
}
