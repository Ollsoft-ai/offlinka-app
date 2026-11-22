@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cz.offlinka.app.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.offlinka.app.AppGraph
import cz.offlinka.app.data.HistoryEntry
import cz.offlinka.app.data.SavedArticle
import cz.offlinka.app.data.safeWrite
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.ui.model.AppLanguage
import cz.offlinka.ui.model.ArticleRef
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.QuickLink
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.screens.BookDetailsState
import cz.offlinka.ui.screens.HomeState
import cz.offlinka.ui.screens.LibraryState
import cz.offlinka.ui.screens.SavedState
import cz.offlinka.ui.screens.SavedTab
import cz.offlinka.ui.screens.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import cz.offlinka.ui.model.TryNowItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SharingStarted.Companion.Ui get() = WhileSubscribed(5_000)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    /** "Vyzkoušejte hned" links whose pages exist in the books on hand (checked off the main thread). */
    private val tryNow: Flow<List<TryNowItem>> = combine(graph.shelf.state, graph.settings.values) { s, v -> s to v.tryNowDismissed }
        .mapLatest { (s, dismissed) ->
            if (dismissed || s !is ShelfState.Ready) emptyList() else withContext(Dispatchers.IO) { TryNow.items(graph) }
        }
        .distinctUntilChanged()

    val state: StateFlow<HomeState> = combine(
        graph.shelf.state, graph.db.history().recent(1), graph.searchNote, tryNow,
    ) { shelf, recent, note, tryItems ->
        val ready = shelf as? ShelfState.Ready
        val tiles = ready?.tiles.orEmpty()
        val last = recent.firstOrNull()?.takeIf { ready?.byId?.containsKey(it.bookId) == true }
        HomeState(
            books = tiles,
            continueReading = last?.let { ArticleRef(it.bookId, it.path, it.title, it.visitedAt) },
            continueBook = last?.let { ready?.byId?.get(it.bookId)?.ui },
            hasGuides = graph.shelf.servedBooks().values.any { it.ui.kind == BookKind.GUIDES },
            hasRandom = ready?.books?.any { it.ui.kind == BookKind.WIKIPEDIA } == true,
            searchNote = note,
            tryNow = tryItems,
        )
    }.stateIn(viewModelScope, SharingStarted.Ui, HomeState(emptyList()))

    fun retry(book: BookUi) = graph.shelf.retry(book.id)

    fun dismissTryNow() = graph.settings.dismissTryNow()

    fun dismissNote() = graph.dismissSearchNote()

    /** URL for a quick link, or null when the book is missing. */
    suspend fun quickLink(link: QuickLink): String? = withContext(Dispatchers.IO) {
        when (link) {
            QuickLink.RANDOM -> {
                val wiki = graph.shelf.primaryWikipedia() ?: return@withContext null
                runCatching { wiki.content.randomArticle() }.getOrNull()?.let { ReaderUrls.article(wiki.id, it) }
            }
            QuickLink.FIRST_AID, QuickLink.PREPARE -> {
                // The guides of the drive, or the copy bundled with the app.
                val guides = graph.shelf.servedBooks().values.firstOrNull { it.ui.kind == BookKind.GUIDES } ?: return@withContext null
                val candidates = if (link == QuickLink.FIRST_AID) GUIDE_FIRST_AID else GUIDE_PREPARE
                val path = candidates.firstOrNull { p -> runCatching { guides.content.get(p) }.isSuccess }
                    ?: guides.mainPath ?: ""
                ReaderUrls.article(guides.id, path)
            }
        }
    }

    companion object {
        /** Pages of offlinka_cs_prirucky.zim (drive-prep tools/build_own_zims.sh). */
        val GUIDE_FIRST_AID = listOf("prvni-pomoc.html", "prvni-pomoc")
        val GUIDE_PREPARE = listOf("vypadek-proudu.html", "vypadek-proudu")
    }
}

class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    fun retry(book: BookUi) = graph.shelf.retry(book.id)

    val state: StateFlow<LibraryState> = graph.shelf.state.map { shelf ->
        val ready = shelf as? ShelfState.Ready
        LibraryState(
            books = ready?.tiles.orEmpty(),
            replaced = ready?.replaced.orEmpty(),
            drive = ready?.drives?.firstOrNull { it.connected },
        )
    }.stateIn(viewModelScope, SharingStarted.Ui, LibraryState(emptyList()))
}

class BookDetailsViewModel(private val graph: AppGraph, private val bookId: String, private val fileName: String?) : ViewModel() {
    private val illustration = MutableStateFlow<ImageBitmap?>(null)

    /** Re-evaluated when the user starts or stops a build (the request lives in preferences). */
    private val requests = MutableStateFlow(0)

    val state: StateFlow<BookDetailsState?> = combine(
        graph.shelf.state, illustration, graph.shelf.indexing, graph.shelf.indexRun, requests,
    ) { shelf, img, _, _, _ ->
        val ready = shelf as? ShelfState.Ready ?: return@combine null
        val open = ready.byId[bookId]
        if (open != null && (fileName == null || open.ui.fileName == fileName)) {
            BookDetailsState(open.ui, img, index = graph.indexInfo(open))
        } else {
            ready.replaced.firstOrNull { it.id == bookId && (fileName == null || it.fileName == fileName) }
                ?.let { BookDetailsState(it, null, replaced = true) }
                ?: ready.tiles.firstOrNull { it.id == bookId }?.let { BookDetailsState(it, null) }
        }
    }.stateIn(viewModelScope, SharingStarted.Ui, null)

    fun buildIndex() {
        graph.shelf.buildIndexNow(bookId)
        requests.value++
    }

    /** "Zastavit přípravu": stops this book's index and forgets the request (the others go on). */
    fun stopIndex() {
        graph.shelf.stopIndex(bookId)
        requests.value++
    }

    init {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { graph.shelf.book(bookId)?.content?.illustration(96) }.getOrNull()
            }
            if (bytes != null) {
                illustration.value = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }

    suspend fun randomUrl(): String? = withContext(Dispatchers.IO) {
        val book = graph.shelf.book(bookId) ?: return@withContext null
        runCatching { book.content.randomArticle() }.getOrNull()?.let { ReaderUrls.article(bookId, it) }
    }
}

class SavedViewModel(private val graph: AppGraph) : ViewModel() {
    private val tab = MutableStateFlow(SavedTab.SAVED)

    val state: StateFlow<SavedState> = combine(
        tab,
        graph.db.saved().all(),
        graph.db.history().recent(HISTORY_SHOWN),
        graph.shelf.state,
    ) { t, saved, history, shelf ->
        val books = (shelf as? ShelfState.Ready)?.books?.associate { it.id to it.ui }.orEmpty()
        SavedState(
            tab = t,
            saved = saved.map { ArticleRef(it.bookId, it.path, it.title, it.savedAt) },
            history = history.map { ArticleRef(it.bookId, it.path, it.title, it.visitedAt) },
            books = books,
            now = System.currentTimeMillis(),
        )
    }.stateIn(viewModelScope, SharingStarted.Ui, SavedState())

    init {
        viewModelScope.launch {
            graph.savedTabRequest.collect { t ->
                if (t != null) {
                    tab.value = t
                    graph.savedTabRequest.value = null
                }
            }
        }
    }

    fun setTab(t: SavedTab) { tab.value = t }

    fun removeSaved(ref: ArticleRef) = viewModelScope.launch { safeWrite("saved") { graph.db.saved().remove(ref.bookId, ref.path) } }

    fun restoreSaved(ref: ArticleRef) = viewModelScope.launch {
        safeWrite("saved") { graph.db.saved().save(SavedArticle(ref.bookId, ref.path, ref.title, ref.time)) }
    }

    fun removeHistory(ref: ArticleRef) = viewModelScope.launch { safeWrite("history") { graph.db.history().remove(ref.bookId, ref.path) } }

    fun clearHistory() = viewModelScope.launch { safeWrite("history") { graph.db.history().clear() } }

    private companion object {
        const val HISTORY_SHOWN = 300
    }
}

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val language = MutableStateFlow(graph.settings.language)

    val state: StateFlow<SettingsState> = combine(
        graph.settings.values,
        graph.shelf.state,
        graph.db.searches().recent(1),
        language,
    ) { values, shelf, recent, lang ->
        SettingsState(
            language = lang,
            theme = values.theme,
            textZoom = values.textZoom,
            drives = (shelf as? ShelfState.Ready)?.drives.orEmpty(),
            saveHistory = values.saveHistory,
            hasRecentSearches = recent.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Ui, SettingsState())

    fun setLanguage(l: AppLanguage) {
        language.value = l
        graph.settings.setLanguage(l)
    }

    fun setTheme(t: ThemeMode) = graph.settings.setTheme(t)

    fun setTextZoom(z: Int) = graph.settings.setTextZoom(z)

    fun setSaveHistory(on: Boolean) {
        graph.settings.setSaveHistory(on)
        if (!on) viewModelScope.launch { safeWrite("history") { graph.db.history().clear() } }
    }

    fun clearSearches() = viewModelScope.launch { safeWrite("searches") { graph.db.searches().clear() } }

    fun forgetDrive(id: String) = graph.shelf.forget(id)
}

/** Saves or removes the article shown in the reader; records history. */
class ArticleStore(private val graph: AppGraph) {
    suspend fun toggleSaved(bookId: String, path: String, title: String, saved: Boolean) {
        safeWrite("saved") {
            if (saved) graph.db.saved().remove(bookId, path)
            else graph.db.saved().save(SavedArticle(bookId, path, title, System.currentTimeMillis()))
        }
    }

    suspend fun recordVisit(bookId: String, path: String, title: String) {
        if (!graph.settings.values.value.saveHistory) return
        safeWrite("history") {
            graph.db.history().record(HistoryEntry(bookId, path, title, System.currentTimeMillis()))
            graph.db.history().trim(HISTORY_KEPT)
        }
    }

    private companion object {
        const val HISTORY_KEPT = 1000
    }
}
