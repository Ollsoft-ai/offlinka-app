package cz.offlinka.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.offlinka.app.AppGraph
import cz.offlinka.app.content.ContentException
import cz.offlinka.app.data.RecentSearch
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.SearchGroup
import cz.offlinka.ui.model.SearchHit
import cz.offlinka.ui.screens.SearchState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Suggestions while typing: every keystroke asks the core (no debounce, it is fast with an index);
 * a newer query cancels waiting for an older one. Results come grouped by book in the core's ranking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val graph: AppGraph,
    initialScope: String?,
    searchDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(initialScope)

    private data class Results(
        val query: String,
        val groups: List<SearchGroup>,
        val searching: Boolean,
        val retriedWord: String? = null,
    )

    private val results = combine(query, scope, graph.shelf.state) { q, s, shelf -> Triple(q.trim(), s, shelf) }
        .transformLatest { (q, s, shelf) ->
            val ready = shelf as? ShelfState.Ready
            if (q.isEmpty() || ready == null) {
                emit(Results(q, emptyList(), false))
                return@transformLatest
            }
            emit(Results(q, emptyList(), true))
            emit(search(ready, q, s))
        }
        .flowOn(searchDispatcher)

    private val base = combine(query, scope, results, graph.db.searches().recent(RECENT_SHOWN), graph.shelf.state) { q, s, r, recent, shelf ->
        val ready = shelf as? ShelfState.Ready
        SearchState(
            query = q,
            scope = s?.let { ready?.byId?.get(it)?.ui },
            groups = if (r.query == q.trim()) r.groups else emptyList(),
            recent = recent.map { it.query },
            searching = r.searching || r.query != q.trim(),
            retriedWord = r.retriedWord.takeIf { r.query == q.trim() },
        )
    }

    val state: StateFlow<SearchState> = combine(base, graph.shelf.indexing) { st, idx ->
        st.copy(indexProgress = idx?.let { (it.fraction * 100).toInt().coerceIn(0, 99) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState(scope = null))

    init {
        // The core loads an index's lookup tables lazily; do it now, before the first keystroke.
        graph.shelf.preloadIndexes()
    }

    fun setQuery(q: String) { query.value = q }

    fun setScope(bookId: String?) { scope.value = bookId }

    /** Remembers the query once the user opens a result or presses search. */
    fun remember(q: String = query.value) {
        val t = q.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            graph.db.searches().add(RecentSearch(t, System.currentTimeMillis()))
            graph.db.searches().trim(RECENT_KEPT)
        }
    }

    fun removeRecent(q: String) = viewModelScope.launch { graph.db.searches().remove(q) }

    fun clearRecent() = viewModelScope.launch { graph.db.searches().clear() }

    fun cancelIndexing() = graph.shelf.cancelIndexing()

    /**
     * Titles only: when a multi-word query finds nothing, the longest word alone is tried
     * ("krvácení z nosu u dětí" → "krvácení") and the results say so.
     */
    private fun search(ready: ShelfState.Ready, q: String, scopeId: String?): Results {
        val groups = searchOnce(ready, q, scopeId)
        if (groups.any { it.hits.isNotEmpty() }) return Results(q, groups, false)
        val longest = q.split(Regex("\\s+")).filter { it.length >= 3 }.maxByOrNull { it.length }
        if (longest != null && longest != q) {
            val retry = searchOnce(ready, longest, scopeId)
            if (retry.any { it.hits.isNotEmpty() }) return Results(q, retry, false, retriedWord = longest)
        }
        return Results(q, groups, false)
    }

    private fun searchOnce(ready: ShelfState.Ready, q: String, scopeId: String?): List<SearchGroup> = try {
        if (scopeId != null) {
            val book = ready.byId[scopeId]
            val hits = book?.content?.suggest(q, SCOPED_LIMIT).orEmpty()
                .map { SearchHit(book!!.id, it.title, it.path, it.snippet) }
            if (book != null) listOf(SearchGroup(book.ui, hits)) else emptyList()
        } else {
            val hits = ready.library.suggestAll(q, ALL_LIMIT)
            val groups = LinkedHashMap<String, MutableList<SearchHit>>()
            for (h in hits) {
                val book = ready.byCoreId[h.bookId] ?: ready.byId[h.bookId] ?: continue
                groups.getOrPut(book.id) { mutableListOf() } += SearchHit(book.id, h.title, h.path)
            }
            uiWikipediaFirst(groups.map { (id, list) -> SearchGroup(ready.byId.getValue(id).ui, list) }, graph.uiLanguage())
        }
    } catch (e: ContentException.Io) {
        graph.shelf.reportIoError(e)
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** Design: the UI-language Wikipedia first, then the core's order (best match). */
        fun uiWikipediaFirst(groups: List<SearchGroup>, ui: String): List<SearchGroup> =
            groups.sortedBy { g -> if (g.book.kind == BookKind.WIKIPEDIA && g.book.language == ui) 0 else 1 }

        const val ALL_LIMIT = 60
        const val SCOPED_LIMIT = 50
        const val RECENT_SHOWN = 8
        const val RECENT_KEPT = 30
    }
}
