package cz.offlinka.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.data.Settings
import cz.offlinka.app.drive.DriveAccess
import cz.offlinka.app.drive.DriveMemory
import cz.offlinka.app.drive.OpenBook
import cz.offlinka.app.drive.Shelf
import cz.offlinka.app.reader.AssetSource
import cz.offlinka.app.reader.ContentServer
import cz.offlinka.app.reader.NotFoundPage
import cz.offlinka.app.reader.PageInfo
import cz.offlinka.app.reader.ReaderLook
import cz.offlinka.app.reader.ReaderZoom
import cz.offlinka.app.reader.RetainedReaderState
import cz.offlinka.app.search.GuideSections
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.IndexInfo
import cz.offlinka.ui.model.IndexPhase
import cz.offlinka.ui.model.SearchNote
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.screens.SavedTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Everything the app needs, wired by hand (small app, no DI framework). Tests build it with fakes. */
class AppGraph(
    val context: Context,
    val source: ContentSource,
    val drives: DriveAccess,
    val db: AppDatabase,
    val settings: Settings,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** The app is in the foreground (ProcessLifecycleOwner); background work like the unplug watcher waits for it. */
    val foreground: StateFlow<Boolean> = MutableStateFlow(true),
    indexDir: File? = File(context.noBackupFilesDir, "indexes"),
    /** The guides book shipped with the app (copied out of the APK on first start). */
    builtinGuidesPath: (() -> String?)? = null,
) {
    /** "Historie" from the reader menu opens the Uložené screen on its history tab. */
    val savedTabRequest = MutableStateFlow<SavedTab?>(null)

    /** What the reader's WebView shows; written by the activity's ReaderController. */
    val readerPage = MutableStateFlow(PageInfo())

    /** The reader's back/forward list across activity recreation (kept in memory, not in the saved-state bundle). */
    val retainedReader = RetainedReaderState()

    /** A removable volume (the USB drive) is mounted, as far as Android tells. Null = unknown. */
    val driveVisible = MutableStateFlow<Boolean?>(null)

    val shelf = Shelf(
        drives = drives,
        source = source,
        scope = scope,
        indexDir = indexDir,
        memory = PrefsDriveMemory(context),
        uiLanguage = ::uiLanguage,
        foreground = foreground,
        builtinGuides = builtinGuidesPath,
    )

    /** A page to open as soon as possible (app-icon shortcut): the reader with books, else the bundled guides. */
    val openRequest = MutableStateFlow<String?>(null)

    /** X on the "preparing" note: hidden for the rest of this run (preparing goes on). */
    private val noteHidden = MutableStateFlow(false)

    /** The UI-language Wikipedia got its index during this run: "Hledání je připravené" until closed. */
    private val searchJustReady = MutableStateFlow(false)

    init {
        scope.launch {
            shelf.indexed.collect { id -> if (id == shelf.primaryWikipedia()?.id) searchJustReady.value = true }
        }
    }

    /** The note under the search field (Home, Search): one percentage, for the UI-language Wikipedia only. */
    val searchNote: StateFlow<SearchNote?> = combine(shelf.indexing, shelf.state, noteHidden, searchJustReady) { idx, _, hidden, ready ->
        val primary = shelf.primaryWikipedia()
        when {
            ready -> SearchNote(ready = true)
            hidden -> null
            idx != null && primary != null && idx.bookId == primary.id -> SearchNote(percent = percent(idx.fraction))
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun dismissSearchNote() {
        if (searchJustReady.value) searchJustReady.value = false else noteHidden.value = true
    }

    /** Accent-free search works for the UI-language Wikipedia (or there is none to wait for). */
    fun accentFreeReady(): Boolean = shelf.primaryWikipedia()?.ui?.hasIndex ?: true

    /** What book details show about a book's on-device index, or null when there is nothing to show. */
    fun indexInfo(book: OpenBook): IndexInfo? {
        if (book.ui.hasIndex || book.builtin) return null
        val building = shelf.indexing.value
        if (building?.bookId == book.id) return IndexInfo(IndexPhase.BUILDING, percent(building.fraction))
        val run = shelf.indexRun.value
        if (book.id in run.queued) return IndexInfo(if (run.lowSpace) IndexPhase.LOW_SPACE else IndexPhase.QUEUED)
        if (heavyIndexAllowed && shelf.canBuildOnDemand(book)) return IndexInfo(IndexPhase.OFFER)
        return null
    }

    private fun percent(fraction: Double) = (fraction * 100).toInt().coerceIn(0, 99)

    @Volatile private var sections: GuideSections? = null

    /** Section headings of the guides book (built once, off the main thread, from the served guides). */
    fun guideSections(): GuideSections? = sections ?: run {
        val guides = shelf.servedBooks().values.firstOrNull { it.ui.kind == BookKind.GUIDES } ?: return null
        runCatching { GuideSections.build(guides.content, guides.id) }.getOrNull()?.also { sections = it }
    }

    /** UI language for ordering books: the app's own choice, else the system language; `cs` for anything else. */
    fun uiLanguage(): String {
        val tag = settings.language.tag.ifEmpty { Locale.getDefault().language }
        return when (tag.substringBefore('-')) {
            "sk" -> "sk"
            "en" -> "en"
            else -> "cs"
        }
    }

    /** Whether pages render dark right now (theme setting or, for "system", the current configuration). */
    @Volatile
    var systemDark: Boolean = false

    /** The phone has the memory (~460 MB) to build the English top1m index on request. */
    @Volatile
    var heavyIndexAllowed: Boolean = true

    /** The system font scale, applied on top of the reader's own text size. */
    @Volatile
    var fontScale: Float = 1f

    fun readerDark(): Boolean = when (settings.values.value.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val server = ContentServer(
        books = { shelf.servedBooks() },
        assets = AssetSource { name -> runCatching { context.assets.open("reader/$name") }.getOrNull() },
        look = { ReaderLook(dark = readerDark(), scale = ReaderZoom.cssScale(settings.values.value.textZoom, fontScale)) },
        notFoundPage = { dark -> NotFoundPage.html(localizedContext(), dark) },
        onDriveError = { shelf.reportIoError(it) },
        pending = shelf::isPending,
    )

    /**
     * Context in the app's own UI language. On Android 8 to 12 the per-app language reaches only activities,
     * so pages made outside them (the 404 page) need it explicitly.
     */
    fun localizedContext(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locales[0])
        return context.createConfigurationContext(config)
    }
}

/** Drive labels in SharedPreferences, so an unplugged drive keeps its name. */
private class PrefsDriveMemory(context: Context) : DriveMemory {
    private val prefs = context.getSharedPreferences("drives", Context.MODE_PRIVATE)
    override fun label(tree: String): String? = prefs.getString(tree, null)
    override fun remember(tree: String, label: String) = prefs.edit().putString(tree, label).apply()
    override fun forget(tree: String) {
        prefs.edit().remove(tree).apply()
        indexPrefs.edit().remove(tree).apply()
    }
    override fun indexRequests(): Set<String> = prefs.getStringSet(INDEX_REQUESTS, emptySet()).orEmpty().toSet()
    override fun requestIndex(bookId: String) = prefs.edit().putStringSet(INDEX_REQUESTS, indexRequests() + bookId).apply()
    override fun cancelIndexRequest(bookId: String) = prefs.edit().putStringSet(INDEX_REQUESTS, indexRequests() - bookId).apply()
    override fun indexesOf(tree: String): Set<String>? = indexPrefs.getStringSet(tree, null)?.toSet()
    override fun rememberIndexes(tree: String, names: Set<String>) = indexPrefs.edit().putStringSet(tree, names).apply()

    private val indexPrefs = context.getSharedPreferences("drive-indexes", Context.MODE_PRIVATE)

    private companion object {
        const val INDEX_REQUESTS = "_index_requests"
    }
}
