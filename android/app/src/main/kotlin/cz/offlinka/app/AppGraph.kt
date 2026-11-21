package cz.offlinka.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.data.Settings
import cz.offlinka.app.drive.DriveAccess
import cz.offlinka.app.drive.DriveMemory
import cz.offlinka.app.drive.Shelf
import cz.offlinka.app.reader.AssetSource
import cz.offlinka.app.reader.ContentServer
import cz.offlinka.app.reader.NotFoundPage
import cz.offlinka.app.reader.PageInfo
import cz.offlinka.app.reader.ReaderLook
import cz.offlinka.app.reader.ReaderZoom
import cz.offlinka.app.reader.RetainedReaderState
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.screens.SavedTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        driveReady = { shelf.ready != null },
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
    override fun forget(tree: String) = prefs.edit().remove(tree).apply()
    override fun indexRequests(): Set<String> = prefs.getStringSet(INDEX_REQUESTS, emptySet()).orEmpty().toSet()
    override fun requestIndex(bookId: String) = prefs.edit().putStringSet(INDEX_REQUESTS, indexRequests() + bookId).apply()

    private companion object {
        const val INDEX_REQUESTS = "_index_requests"
    }
}
