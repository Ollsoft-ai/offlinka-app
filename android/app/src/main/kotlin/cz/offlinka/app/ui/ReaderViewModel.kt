package cz.offlinka.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.offlinka.app.AppGraph
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.reader.PageInfo
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything about the current article that is not the WebView itself. */
data class ArticleState(
    val bookId: String? = null,
    val path: String? = null,
    val book: BookUi? = null,
    val title: String = "",
    val saved: Boolean = false,
    /** Maps and games are apps, not articles: nothing to save, no text size. */
    val isApp: Boolean = false,
)

/**
 * Follows the page the WebView shows ([page], from the activity's ReaderController), records each visit
 * in the history and keeps the "Uložené" toggle in sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val graph: AppGraph,
    page: Flow<PageInfo>,
) : ViewModel() {
    private val store = ArticleStore(graph)
    private val panel = MutableStateFlow(false)
    val textPanel: StateFlow<Boolean> = panel

    private val current = page.map { p ->
        val t = p.url?.let { ReaderUrls.parse(it) }
        Triple(t, p.title, p.notFound)
    }.distinctUntilChanged()

    val article: StateFlow<ArticleState> = combine(current, graph.shelf.state) { (t, title, notFound), shelf ->
        val book = t?.let { (shelf as? ShelfState.Ready)?.byId?.get(it.bookId)?.ui }
        ArticleState(
            bookId = t?.bookId,
            path = t?.path?.takeUnless { notFound },
            book = book,
            title = title,
            isApp = book?.kind == BookKind.MAPS || book?.kind == BookKind.GAMES,
        )
    }.flatMapLatest { a ->
        if (a.bookId == null || a.path == null || a.isApp) flowOf(a)
        else graph.db.saved().isSaved(a.bookId, a.path).map { a.copy(saved = it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ArticleState())

    init {
        // One history row per finished visit of a real article.
        viewModelScope.launch {
            page.map { it.visit to it }.distinctUntilChanged { a, b -> a.first == b.first }.collect { (_, p) ->
                if (p.visit == 0 || p.notFound) return@collect
                val t = p.url?.let { ReaderUrls.parse(it) } ?: return@collect
                if (t.bookId == ReaderUrls.ASSETS || t.path.isEmpty()) return@collect
                val book = graph.shelf.book(t.bookId) ?: return@collect
                if (book.ui.kind == BookKind.MAPS || book.ui.kind == BookKind.GAMES) return@collect
                store.recordVisit(t.bookId, t.path, p.title.ifBlank { t.path.substringAfterLast('/').replace('_', ' ') })
            }
        }
    }

    fun toggleSaved() {
        val a = article.value
        val bookId = a.bookId ?: return
        val path = a.path ?: return
        viewModelScope.launch {
            store.toggleSaved(bookId, path, a.title.ifBlank { path.replace('_', ' ') }, a.saved)
        }
    }

    fun toggleTextPanel() { panel.value = !panel.value }

    fun setTextZoom(z: Int) = graph.settings.setTextZoom(z)

    fun mainPageUrl(): String? {
        val a = article.value
        val id = a.bookId ?: return null
        val main = graph.shelf.book(id)?.mainPath ?: return null
        return ReaderUrls.article(id, main)
    }
}
