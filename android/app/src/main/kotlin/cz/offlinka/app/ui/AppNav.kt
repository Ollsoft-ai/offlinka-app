package cz.offlinka.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import cz.offlinka.app.AppGraph
import cz.offlinka.app.BuildConfig
import cz.offlinka.app.reader.ReaderController
import cz.offlinka.app.reader.ReaderEvent
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.ui.R
import cz.offlinka.ui.components.MainTab
import cz.offlinka.ui.components.OfflinkaBottomBar
import cz.offlinka.ui.model.ArticleRef
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.TocEntry
import cz.offlinka.ui.screens.AboutScreen
import cz.offlinka.ui.screens.AboutState
import cz.offlinka.ui.screens.BookDetailsSheet
import cz.offlinka.ui.screens.HomeScreen
import cz.offlinka.ui.screens.LibraryScreen
import cz.offlinka.ui.screens.ReaderScreen
import cz.offlinka.ui.screens.ReaderState
import cz.offlinka.ui.screens.SavedScreen
import cz.offlinka.ui.screens.SearchScreen
import cz.offlinka.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/** Routes (string routes keep the graph free of serialization plugins). */
object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SAVED = "saved"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val SEARCH = "search?scope={scope}&fromReader={fromReader}"
    const val READER = "reader?url={url}&fresh={fresh}"
    const val BOOK = "book/{id}?file={file}"

    fun search(scope: String? = null, fromReader: Boolean = false) =
        "search?scope=${scope.orEmpty().enc()}&fromReader=$fromReader"

    fun reader(url: String, fresh: Boolean = true) = "reader?url=${url.enc()}&fresh=$fresh"

    fun book(id: String, file: String? = null) = "book/${id.enc()}?file=${file.orEmpty().enc()}"

    private fun String.enc(): String = android.net.Uri.encode(this)

    val tabs = mapOf(HOME to MainTab.HOME, LIBRARY to MainTab.LIBRARY, SAVED to MainTab.SAVED, SETTINGS to MainTab.SETTINGS)
}

val LocalReaderController = staticCompositionLocalOf<ReaderController> { error("no reader") }

/** Key on the reader's back stack entry: a search started in the reader hands its result back here. */
private const val OPEN_IN_READER = "openInReader"

@Composable
fun AppNavHost(
    graph: AppGraph,
    nav: NavHostController,
    onChangeDrive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(graph) {
        viewModelFactory {
            initializer { HomeViewModel(graph) }
            initializer { LibraryViewModel(graph) }
            initializer { SavedViewModel(graph) }
            initializer { SettingsViewModel(graph) }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = Routes.tabs[route]

    fun openReader(url: String) {
        nav.navigate(Routes.reader(url, fresh = true)) {
            popUpTo(Routes.READER) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun openArticle(bookId: String, path: String) = openReader(ReaderUrls.article(bookId, path))

    Column(modifier.fillMaxSize()) {
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.weight(1f).fillMaxWidth()) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                HomeScreen(
                    state = state,
                    onSearch = { nav.navigate(Routes.search()) },
                    onOpenBook = { b -> openBookMain(graph, b)?.let(::openReader) },
                    onRetryBook = vm::retry,
                    onQuickLink = { link -> scope.launch { vm.quickLink(link)?.let(::openReader) } },
                    onContinue = { ref -> openArticle(ref.bookId, ref.path) },
                )
            }
            composable(Routes.LIBRARY) {
                val vm: LibraryViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                LibraryScreen(
                    state = state,
                    onOpen = { b -> openBookMain(graph, b)?.let(::openReader) },
                    onDetails = { b -> nav.navigate(Routes.book(b.id, b.fileName)) },
                    onRetry = vm::retry,
                )
            }
            composable(Routes.SAVED) {
                val vm: SavedViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val removed = stringResource(R.string.saved_removed)
                val undo = stringResource(R.string.action_undo)
                SavedScreen(
                    state = state,
                    onTab = vm::setTab,
                    onOpen = { ref: ArticleRef -> openArticle(ref.bookId, ref.path) },
                    onRemoveSaved = { ref ->
                        vm.removeSaved(ref)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            if (snackbar.showSnackbar(removed, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                vm.restoreSaved(ref)
                            }
                        }
                    },
                    snackbarHostState = snackbar,
                    onRemoveHistory = { vm.removeHistory(it) },
                    onClearHistory = { vm.clearHistory() },
                )
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onLanguage = vm::setLanguage,
                    onTheme = vm::setTheme,
                    onTextZoom = vm::setTextZoom,
                    onChangeDrive = onChangeDrive,
                    onSaveHistory = vm::setSaveHistory,
                    onClearSearches = { vm.clearSearches() },
                    onAbout = { nav.navigate(Routes.ABOUT) },
                    onForgetDrive = { vm.forgetDrive(it) },
                )
            }
            composable(Routes.ABOUT) {
                val context = LocalContext.current
                AboutScreen(
                    onCopyEmail = { email ->
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(email, email))
                    },
                    state = AboutState(
                        appVersion = BuildConfig.VERSION_NAME,
                        coreVersion = remember { runCatching { graph.source.coreVersion }.getOrNull() },
                        edition = graph.shelf.ready?.drives?.firstOrNull { it.connected }?.edition,
                        notices = Notices.all,
                    ),
                    onBack = { nav.popBackStack() },
                )
            }
            dialog(
                Routes.BOOK,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("file") { type = NavType.StringType; defaultValue = "" },
                ),
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) { entry ->
                // The sheet draws its own scrim; no second dim from the dialog window.
                val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                SideEffect { window?.setDimAmount(0f) }
                val id = entry.arguments?.getString("id").orEmpty()
                val file = entry.arguments?.getString("file")?.ifEmpty { null }
                val vm: BookDetailsViewModel = viewModel(factory = viewModelFactory { initializer { BookDetailsViewModel(graph, id, file) } })
                val state by vm.state.collectAsStateWithLifecycle()
                val s = state
                if (s != null) {
                    BookDetailsSheet(
                        state = s,
                        onDismiss = { nav.popBackStack() },
                        onOpen = { openBookMain(graph, s.book)?.let(::openReader) },
                        onBuildIndex = vm::buildIndex,
                    )
                }
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(
                    navArgument("scope") { type = NavType.StringType; defaultValue = "" },
                    navArgument("fromReader") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val scopeId = entry.arguments?.getString("scope")?.ifEmpty { null }
                val fromReader = entry.arguments?.getBoolean("fromReader") ?: false
                val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(graph, scopeId) } })
                val state by vm.state.collectAsStateWithLifecycle()
                fun open(url: String) {
                    vm.remember()
                    val reader = if (fromReader) nav.previousBackStackEntry else null
                    if (reader != null && reader.destination.route == Routes.READER) {
                        reader.savedStateHandle[OPEN_IN_READER] = url
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.reader(url, fresh = true)) {
                            popUpTo(Routes.SEARCH) { inclusive = true }
                        }
                    }
                }
                SearchScreen(
                    state = state,
                    onQueryChange = vm::setQuery,
                    onSubmit = {
                        val first = state.groups.firstOrNull()?.hits?.firstOrNull()
                        if (first != null) open(ReaderUrls.article(first.bookId, first.path)) else vm.remember()
                    },
                    onHit = { hit -> open(ReaderUrls.article(hit.bookId, hit.path)) },
                    onRecent = { q -> vm.setQuery(q) },
                    onRemoveRecent = { vm.removeRecent(it) },
                    onScope = { b: BookUi? -> vm.setScope(b?.id) },
                    onBack = { nav.popBackStack() },
                    onClearRecent = { vm.clearRecent() },
                    onCancelIndex = { vm.cancelIndexing() },
                )
            }
            composable(
                Routes.READER,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("fresh") { type = NavType.BoolType; defaultValue = true },
                ),
            ) { entry -> ReaderDestination(graph, entry, nav) }
        }
        if (tab != null) {
            OfflinkaBottomBar(tab, onSelect = { t ->
                val target = Routes.tabs.entries.first { it.value == t }.key
                nav.navigate(target) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
        }
    }
}

/** URL of a book's main page. */
private fun openBookMain(graph: AppGraph, book: BookUi): String? {
    val open = graph.shelf.book(book.id) ?: return null
    return ReaderUrls.article(book.id, open.mainPath ?: "")
}

@Composable
private fun ReaderDestination(graph: AppGraph, entry: NavBackStackEntry, nav: NavHostController) {
    val controller = LocalReaderController.current
    val vm: ReaderViewModel = viewModel(factory = viewModelFactory { initializer { ReaderViewModel(graph, graph.readerPage) } })
    val page by controller.page.collectAsStateWithLifecycle()
    val find by controller.find.collectAsStateWithLifecycle()
    val article by vm.article.collectAsStateWithLifecycle()
    val panel by vm.textPanel.collectAsStateWithLifecycle()
    val settings by graph.settings.values.collectAsStateWithLifecycle()
    val dark = cz.offlinka.ui.theme.Offlinka.colors.isDark
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val external = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val toc = remember { androidx.compose.runtime.mutableStateOf<List<TocEntry>?>(null) }
    val savedMsg = stringResource(R.string.reader_saved_toast)
    val removedMsg = stringResource(R.string.reader_removed_toast)
    val copiedMsg = stringResource(R.string.reader_link_copied)
    val cannotOpen = stringResource(R.string.error_unsupported)

    // First visit of this entry: load its URL (unless the WebView already restored a page after recreation).
    LaunchedEffect(entry.id) {
        val handle = entry.savedStateHandle
        if (handle.get<Boolean>("loaded") != true) {
            handle["loaded"] = true
            val url = entry.arguments?.getString("url").orEmpty()
            val fresh = entry.arguments?.getBoolean("fresh") ?: true
            if (url.isNotEmpty() && !(controller.hasPage() && page.url == url)) controller.load(url, fresh)
        }
    }
    // A search started from the reader hands its result back.
    val pending by entry.savedStateHandle.getStateFlow<String?>(OPEN_IN_READER, null).collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let {
            entry.savedStateHandle[OPEN_IN_READER] = null
            controller.load(it, fresh = false)
        }
    }
    LaunchedEffect(settings.textZoom) { controller.setTextZoom(settings.textZoom) }
    LaunchedEffect(dark) { controller.setDark(dark) }
    LaunchedEffect(Unit) {
        controller.events.collect { e ->
            when (e) {
                is ReaderEvent.External -> external.value = e.url
                ReaderEvent.CannotOpenFile -> snackbar.showSnackbar(cannotOpen)
            }
        }
    }
    BackHandler(enabled = find == null && page.canGoBack) { controller.back() }
    BackHandler(enabled = find != null) { controller.closeFind() }
    BackHandler(enabled = panel) { vm.toggleTextPanel() }
    BackHandler(enabled = toc.value != null) { toc.value = null }

    ReaderScreen(
        state = ReaderState(
            title = article.title,
            book = article.book,
            canGoBack = page.canGoBack,
            canGoForward = page.canGoForward,
            saved = article.saved,
            progress = page.progress,
            find = find,
            textZoom = settings.textZoom,
            theme = settings.theme,
            showTextPanel = panel,
            externalLink = external.value,
            isApp = article.isApp,
            showTitle = page.titleInBar,
            contents = toc.value,
        ),
        onContents = { controller.contents { list -> toc.value = list.map { TocEntry(it.id, it.text, it.level) } } },
        onHeading = { h ->
            toc.value = null
            controller.scrollTo(h.id)
        },
        onCloseContents = { toc.value = null },
        onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
        onBack = controller::back,
        onForward = controller::forward,
        onToggleSave = {
            val was = article.saved
            vm.toggleSaved()
            scope.launch { snackbar.showSnackbar(if (was) removedMsg else savedMsg) }
        },
        onSearch = { nav.navigate(Routes.search(fromReader = true)) },
        onOpenFind = controller::openFind,
        onFindQuery = controller::findQuery,
        onFindNext = controller::findNext,
        onCloseFind = controller::closeFind,
        onToggleTextPanel = vm::toggleTextPanel,
        onTextZoom = vm::setTextZoom,
        onTheme = { graph.settings.setTheme(it) },
        onMainPage = { vm.mainPageUrl()?.let { controller.load(it, fresh = false) } },
        onBookDetails = { article.book?.let { nav.navigate(Routes.book(it.id, it.fileName)) } },
        onHistory = {
            graph.savedTabRequest.value = cz.offlinka.ui.screens.SavedTab.HISTORY
            nav.navigate(Routes.SAVED) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        onDismissExternal = { external.value = null },
        onCopyLink = { link ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(link, link))
            external.value = null
            scope.launch { snackbar.showSnackbar(copiedMsg) }
        },
        snackbarHostState = snackbar,
    ) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    addView(controller.detachedWebView())
                }
            },
            onRelease = { it.removeAllViews() },
            modifier = Modifier.fillMaxSize().background(cz.offlinka.ui.theme.Offlinka.colors.bg),
        )
    }
}

/**
 * The guides bundled with the app, opened from the Connect / Unplugged screen without a drive: its own
 * WebView ([controller]), the reader chrome without saving.
 */
@Composable
fun GuidesReader(graph: AppGraph, controller: ReaderController, url: String?, onClose: () -> Unit) {
    val page by controller.page.collectAsStateWithLifecycle()
    val find by controller.find.collectAsStateWithLifecycle()
    val settings by graph.settings.values.collectAsStateWithLifecycle()
    val dark = cz.offlinka.ui.theme.Offlinka.colors.isDark
    var panel by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(url) { if (url != null) controller.load(url, fresh = true) }
    LaunchedEffect(settings.textZoom) { controller.setTextZoom(settings.textZoom) }
    LaunchedEffect(dark) { controller.setDark(dark) }
    BackHandler { if (page.canGoBack) controller.back() else onClose() }
    BackHandler(enabled = find != null) { controller.closeFind() }
    ReaderScreen(
        state = ReaderState(
            title = page.title,
            book = graph.shelf.builtin?.ui,
            canGoBack = page.canGoBack,
            canGoForward = page.canGoForward,
            progress = page.progress,
            find = find,
            textZoom = settings.textZoom,
            theme = settings.theme,
            showTextPanel = panel,
            canSave = false,
        ),
        onHome = onClose,
        onBack = controller::back,
        onForward = controller::forward,
        onToggleSave = {},
        onSearch = {},
        onOpenFind = controller::openFind,
        onFindQuery = controller::findQuery,
        onFindNext = controller::findNext,
        onCloseFind = controller::closeFind,
        onToggleTextPanel = { panel = !panel },
        onTextZoom = { graph.settings.setTextZoom(it) },
        onTheme = { graph.settings.setTheme(it) },
        onMainPage = { url?.let { controller.load(it, fresh = false) } },
        onBookDetails = {},
        onHistory = {},
        onDismissExternal = {},
        onCopyLink = {},
    ) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    addView(controller.detachedWebView())
                }
            },
            onRelease = { it.removeAllViews() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
