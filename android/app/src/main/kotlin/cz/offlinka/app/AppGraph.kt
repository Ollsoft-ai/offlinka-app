package cz.offlinka.app

import android.content.Context
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.data.Settings
import cz.offlinka.app.drive.DriveAccess
import cz.offlinka.app.drive.Shelf
import cz.offlinka.app.reader.AssetSource
import cz.offlinka.app.reader.ContentServer
import cz.offlinka.app.reader.NotFoundPage
import cz.offlinka.app.reader.PageInfo
import cz.offlinka.app.reader.ReaderLook
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.screens.SavedTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything the app needs, wired by hand (small app, no DI framework). Tests build it with fakes. */
class AppGraph(
    val context: Context,
    val source: ContentSource,
    val drives: DriveAccess,
    val db: AppDatabase,
    val settings: Settings,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** "Historie" from the reader menu opens the Uložené screen on its history tab. */
    val savedTabRequest = MutableStateFlow<SavedTab?>(null)

    /** What the reader's WebView shows; written by the activity's ReaderController. */
    val readerPage = MutableStateFlow(PageInfo())

    val shelf = Shelf(drives, source, scope)

    /** Whether pages render dark right now (theme setting or, for "system", the current configuration). */
    @Volatile
    var systemDark: Boolean = false

    fun readerDark(): Boolean = when (settings.values.value.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val server = ContentServer(
        books = { shelf.ready?.byId ?: emptyMap() },
        assets = AssetSource { name -> runCatching { context.assets.open("reader/$name") }.getOrNull() },
        look = { ReaderLook(dark = readerDark(), scale = settings.values.value.textZoom / 100f) },
        notFoundPage = { dark -> NotFoundPage.html(context, dark) },
        onDriveError = { shelf.reportIoError(it) },
    )
}
