package cz.offlinka.app.drive

import android.net.Uri
import android.util.Log
import cz.offlinka.app.content.ContentBook
import cz.offlinka.app.content.ContentLibrary
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.reader.ServedBook
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.DriveUi
import cz.offlinka.ui.screens.ConnectProblem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** A book opened from the drive. */
class OpenBook(
    val ui: BookUi,
    override val content: ContentBook,
    /** The core's own id of the book ([cz.offlinka.app.content.BookMeta.id]); search hits carry it. */
    val coreId: String,
) : ServedBook {
    val id: String get() = ui.id
    override val mainPath: String? get() = content.info.mainPath
    override val isWiki: Boolean get() = ui.kind.isWiki
}

sealed interface ShelfState {
    /** No drive was ever picked: show "Zapojte flashku". */
    data object NoDrive : ShelfState

    data object Loading : ShelfState

    data class Problem(val problem: ConnectProblem) : ShelfState

    /** The drive was there and is gone (unplugged or unmounted). Waits for it to come back. */
    data object Unplugged : ShelfState

    data class Ready(
        val books: List<OpenBook>,
        /** Phone editions not opened because a bigger edition of the same book is on the drive. */
        val replaced: List<BookUi>,
        val drives: List<DriveUi>,
        val library: ContentLibrary,
    ) : ShelfState {
        val byId: Map<String, OpenBook> = books.associateBy { it.id }
        val byCoreId: Map<String, OpenBook> = books.associateBy { it.coreId }
    }
}

/**
 * Finds the drive, picks the books on it, opens them in the core and watches for the drive to go away
 * and come back. All drive I/O runs on [io].
 */
class Shelf(
    private val drives: DriveAccess,
    private val source: ContentSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMillis: Long = 2_000,
) {
    private val _state = MutableStateFlow<ShelfState>(ShelfState.Loading)
    val state: StateFlow<ShelfState> = _state.asStateFlow()

    private val lock = Mutex()
    private var watcher: Job? = null

    /** The current books, or empty while there is no drive. Safe from any thread. */
    val ready: ShelfState.Ready? get() = _state.value as? ShelfState.Ready

    fun start() {
        scope.launch { reload() }
    }

    /** The user picked [tree] in the system folder picker. */
    fun onPicked(tree: Uri) {
        scope.launch {
            lock.withLock {
                _state.value = ShelfState.Loading
                val persisted = withContext(io) {
                    runCatching { drives.persist(tree) }.onFailure { Log.w(TAG, "persist failed", it) }.isSuccess
                }
                val listing = withContext(io) { scan(listOf(tree)) }
                when {
                    listing.reachable.isEmpty() -> {
                        if (persisted) withContext(io) { drives.release(tree) }
                        _state.value = ShelfState.Problem(ConnectProblem.NOT_FOUND)
                    }
                    listing.files.none { it.name.endsWith(".zim", ignoreCase = true) } -> {
                        if (persisted) withContext(io) { drives.release(tree) }
                        _state.value = ShelfState.Problem(ConnectProblem.WRONG_FOLDER)
                    }
                    !persisted -> _state.value = ShelfState.Problem(ConnectProblem.NO_ACCESS)
                    else -> {
                        // Another folder of a drive we already know replaces nothing: all granted trees stay usable.
                        loadLocked()
                    }
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

    /**
     * Something failed while reading a book. If the drive is gone, close everything and wait for it.
     * Called from WebView and search threads.
     */
    fun reportIoError(e: Throwable) {
        Log.w(TAG, "read failed: $e")
        scope.launch {
            val current = ready ?: return@launch
            val alive = withContext(io) { drives.grantedTrees().any { reachable(it) } }
            if (!alive) {
                lock.withLock {
                    if (_state.value === current) {
                        closeBooks(current)
                        _state.value = ShelfState.Unplugged
                        watchForReturn()
                    }
                }
            }
        }
    }

    /** A storage volume was mounted or removed (system broadcast): check the drive now. */
    fun onStorageChanged() {
        scope.launch {
            when (val s = _state.value) {
                is ShelfState.Ready -> {
                    val alive = withContext(io) { drives.grantedTrees().any { reachable(it) } }
                    if (!alive) reportIoError(IOException("volume removed"))
                    else if (s.drives.any { !it.connected }) reload()
                }
                is ShelfState.Unplugged, is ShelfState.Problem -> reload()
                else -> Unit
            }
        }
    }

    private fun watchForReturn() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive && _state.value is ShelfState.Unplugged) {
                delay(pollMillis)
                val back = withContext(io) { drives.grantedTrees().any { reachable(it) } }
                if (back) {
                    reload()
                    break
                }
            }
        }
    }

    private fun reachable(tree: Uri): Boolean = drives.hasPermission(tree) && runCatching { drives.list(tree, null) }.isSuccess

    private suspend fun loadLocked() {
        val before = _state.value
        val trees = withContext(io) { drives.grantedTrees() }
        if (trees.isEmpty()) {
            (before as? ShelfState.Ready)?.let { closeBooks(it) }
            _state.value = ShelfState.NoDrive
            return
        }
        if (before !is ShelfState.Ready) _state.value = ShelfState.Loading
        val listing = withContext(io) { scan(trees) }
        if (listing.reachable.isEmpty()) {
            (before as? ShelfState.Ready)?.let { closeBooks(it) }
            _state.value = if (before is ShelfState.Ready || before is ShelfState.Unplugged) {
                ShelfState.Unplugged.also { watchForReturn() }
            } else {
                ShelfState.Problem(ConnectProblem.NOT_FOUND).also { watchForReturnFromProblem() }
            }
            return
        }
        val choices = BookSelector.select(listing.files)
        if (choices.isEmpty()) {
            (before as? ShelfState.Ready)?.let { closeBooks(it) }
            _state.value = ShelfState.Problem(ConnectProblem.WRONG_FOLDER)
            return
        }
        val opened = withContext(io) { open(choices, listing) }
        (before as? ShelfState.Ready)?.let { closeBooks(it) }
        if (opened.isEmpty()) {
            _state.value = ShelfState.Problem(ConnectProblem.NOT_FOUND)
            return
        }
        val library = withContext(io) { source.newLibrary().also { lib -> opened.forEach { lib.add(it.content) } } }
        val openedFiles = opened.map { it.ui.fileName }.toSet()
        val replaced = choices.flatMap { c -> listOf(c.zim) + c.fallbacks }
            .filter { it.name !in openedFiles }
            .map { f ->
                val n = ZimName.parse(f.name)
                BookUi(
                    id = n.key, kind = n.kind, language = BookSelector.uiLanguage(n.language), region = n.region,
                    zimTitle = f.name, flavour = n.flavour, topic = n.topic, sizeBytes = f.size, date = n.date,
                    fileName = f.name, folder = f.folder.label,
                )
            }
        val ready = ShelfState.Ready(
            books = opened,
            replaced = replaced,
            drives = trees.mapIndexed { i, t ->
                val mine = opened.filter { it.ui.driveName == listing.labels[i] }
                DriveUi(
                    name = listing.labels[i] ?: "OFFLINKA",
                    bookCount = mine.size,
                    connected = t in listing.reachable,
                    edition = listing.editions.getOrNull(i),
                    totalBytes = listing.files.filter { it.driveIndex == i }.sumOf { it.size },
                )
            },
            library = library,
        )
        _state.value = ready
    }

    private fun indexOf(file: DriveFile, listing: Listing): DriveFile? = BookSelector.indexFor(
        file,
        listing.files.filter { it.name.endsWith(".offidx", true) }
            .associateBy { Triple(it.driveIndex, it.folder, it.name.substringBeforeLast('.').lowercase()) },
    )

    /** Problem(NOT_FOUND) after a reboot with the drive not yet mounted: retry quietly in the background. */
    private fun watchForReturnFromProblem() {
        watcher?.cancel()
        watcher = scope.launch {
            repeat(30) {
                delay(pollMillis)
                if (_state.value !is ShelfState.Problem) return@launch
                val back = withContext(io) { drives.grantedTrees().any { reachable(it) } }
                if (back) {
                    reload()
                    return@launch
                }
            }
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
            labels += drives.label(tree)
            val root = try {
                drives.list(tree, null)
            } catch (e: IOException) {
                Log.i(TAG, "drive $tree not reachable: $e")
                return@forEachIndexed
            }
            reachable += tree
            root.firstOrNull { !it.isDir && it.name.equals("README.txt", ignoreCase = true) && it.size in 1..65_536 }?.let { readme ->
                editions[index] = runCatching {
                    drives.openStream(tree, readme.id).use { DriveEdition.parse(it.readBytes().toString(Charsets.UTF_8)) }
                }.getOrNull()
            }
            fun add(entries: List<DocEntry>, folder: DriveFolder) {
                entries.filter { !it.isDir && (it.name.endsWith(".zim", true) || it.name.endsWith(".offidx", true)) }
                    .forEach { files += DriveFile(it.name, folder, it.size, "$index\u0000${it.id}", index) }
            }
            add(root, DriveFolder.PICKED)
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

    private fun open(choices: List<BookChoice>, listing: Listing): List<OpenBook> = choices.mapNotNull { choice ->
        for (file in listOf(choice.zim) + choice.fallbacks) {
            val book = openOne(file, listing) ?: continue
            return@mapNotNull book
        }
        null
    }

    private fun openOne(file: DriveFile, listing: Listing): OpenBook? {
        val (treeIndex, docId) = file.handle.split('\u0000', limit = 2).let { it[0].toInt() to it[1] }
        val tree = listing.trees[treeIndex]
        return try {
            val content = source.openFd(drives.openFd(tree, docId), file.name)
            val name = ZimName.parse(file.name)
            // The core reads the index with pread (no mmap), so a pulled drive gives Io errors, never SIGBUS.
            indexOf(file, listing)?.let { index ->
                runCatching {
                    content.attachIndexFd(drives.openFd(tree, index.handle.substringAfter('\u0000')))
                }.onFailure { Log.w(TAG, "index ${index.name} not attached", it) }
            }
            val info = content.info
            val ui = BookUi(
                id = name.key,
                kind = name.kind,
                language = BookSelector.uiLanguage(name.language.ifEmpty { info.language }),
                region = name.region,
                zimTitle = info.title.ifBlank { file.name.removeSuffix(".zim") },
                zimDescription = info.description,
                flavour = name.flavour,
                topic = name.topic,
                sizeBytes = if (info.sizeBytes > 0) info.sizeBytes else file.size,
                date = name.date.ifEmpty { info.date.take(7) },
                zimDate = info.date,
                articleCount = info.articleCount,
                mediaCount = info.mediaCount,
                fileName = file.name,
                folder = file.folder.label,
                hasIndex = info.hasIndex,
                driveName = listing.labels[treeIndex],
                publisher = info.publisher,
                creator = info.creator,
            )
            OpenBook(ui, content, info.id)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${file.name}", e)
            null
        }
    }

    private fun closeBooks(ready: ShelfState.Ready) {
        runCatching { ready.library.close() }
        ready.books.forEach { b -> runCatching { b.content.close() } }
    }

    /** Book to use for "Náhodný článek" and the default search scope: Czech Wikipedia when present. */
    fun primaryWikipedia(): OpenBook? = ready?.books?.let { books ->
        books.firstOrNull { it.ui.kind == BookKind.WIKIPEDIA && it.ui.language == "cs" }
            ?: books.firstOrNull { it.ui.kind == BookKind.WIKIPEDIA }
    }

    fun book(id: String): OpenBook? = ready?.byId?.get(id)

    private companion object {
        const val TAG = "OfflinkaShelf"
    }
}
