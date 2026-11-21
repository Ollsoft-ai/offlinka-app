package cz.offlinka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import cz.offlinka.ui.components.MainTab
import cz.offlinka.ui.components.OfflinkaBottomBar
import cz.offlinka.ui.screens.AboutScreen
import cz.offlinka.ui.screens.AboutState
import cz.offlinka.ui.screens.BookDetailsSheet
import cz.offlinka.ui.screens.BookDetailsState
import cz.offlinka.ui.screens.ConnectMode
import cz.offlinka.ui.screens.ConnectProblem
import cz.offlinka.ui.screens.ConnectScreen
import cz.offlinka.ui.screens.ConnectState
import cz.offlinka.ui.screens.FindState
import cz.offlinka.ui.screens.HomeScreen
import cz.offlinka.ui.screens.HomeState
import cz.offlinka.ui.screens.LibraryScreen
import cz.offlinka.ui.screens.LibraryState
import cz.offlinka.ui.screens.Notice
import cz.offlinka.ui.screens.ReaderScreen
import cz.offlinka.ui.screens.ReaderState
import cz.offlinka.ui.screens.SavedScreen
import cz.offlinka.ui.screens.SavedState
import cz.offlinka.ui.screens.SavedTab
import cz.offlinka.ui.screens.SearchScreen
import cz.offlinka.ui.screens.SearchState
import cz.offlinka.ui.screens.SettingsScreen
import cz.offlinka.ui.screens.SettingsState
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaTheme
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.model.IndexInfo
import cz.offlinka.ui.model.IndexPhase
import cz.offlinka.ui.model.SearchNote
import cz.offlinka.ui.model.TocEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.TimeZone

/** One variant of the device: UI language and light/dark. */
data class Variant(val name: String, val locale: String, val dark: Boolean, val fontScale: Float = 1f, val smallPhone: Boolean = false) {
    override fun toString() = name

    fun paparazzi() = Paparazzi(
        deviceConfig = (if (smallPhone) SMALL_PHONE else DeviceConfig.PIXEL_5).copy(
            locale = locale,
            nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
            softButtons = false,
            fontScale = fontScale,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 0.1,
    )

    companion object {
        val CS_LIGHT = Variant("cs-light", "cs", false)
        val CS_DARK = Variant("cs-dark", "cs", true)
        val EN_LIGHT = Variant("en-light", "en", false)
        val EN_DARK = Variant("en-dark", "en", true)
        val SK_LIGHT = Variant("sk-light", "sk", false)
        val SK_DARK = Variant("sk-dark", "sk", true)
        val CS_LARGE = Variant("cs-font160", "cs", false, fontScale = 1.6f)
        val SMALL = Variant("cs-360x640", "cs", false, smallPhone = true)
        val SMALL_LARGE = Variant("cs-360x640-font160", "cs", false, fontScale = 1.6f, smallPhone = true)

        /** A budget phone: 360 × 640 dp (720 × 1280 px at xhdpi). */
        val SMALL_PHONE = DeviceConfig.PIXEL_5.copy(
            screenWidth = 720,
            screenHeight = 1280,
            xdpi = 320,
            ydpi = 320,
            density = com.android.resources.Density.XHIGH,
        )
    }
}

private val noop: () -> Unit = {}

/** Every main screen in Czech and English, light and dark. Goldens: ui/src/test/snapshots/images. */
@RunWith(Parameterized::class)
class ScreensTest(private val v: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = listOf(Variant.CS_LIGHT, Variant.CS_DARK, Variant.EN_LIGHT, Variant.EN_DARK, Variant.SK_DARK)
    }

    @get:Rule val paparazzi = v.paparazzi()

    /** Slovak dark exists only for the store's dark reader screenshot ([reader]). */
    private fun snap(skDark: Boolean = false, content: @Composable () -> Unit) {
        org.junit.Assume.assumeTrue(v != Variant.SK_DARK || skDark)
        paparazzi.snapshot { OfflinkaTheme(darkTheme = v.dark, content = content) }
    }

    @Test fun connect() = snap { ConnectScreen(ConnectState(), onPick = noop) }

    @Test fun home() = snap {
        WithTabs(MainTab.HOME) {
            HomeScreen(
                HomeState(Samples.books, continueReading = Samples.history.first(), continueBook = Samples.csWiki),
                onSearch = noop, onOpenBook = {}, onQuickLink = {}, onContinue = {},
            )
        }
    }

    @Test fun search() = snap {
        SearchScreen(
            SearchState(query = "cesky krumlov", groups = Samples.searchGroups),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun reader() = snap(skDark = true) {
        ReaderScreen(
            ReaderState(title = "Praha", book = Samples.csWiki, canGoBack = true, saved = true),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun library() = snap {
        WithTabs(MainTab.LIBRARY) {
            LibraryScreen(LibraryState(Samples.books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
        }
    }

    @Test fun saved() = snap {
        WithTabs(MainTab.SAVED) {
            SavedScreen(
                SavedState(SavedTab.SAVED, Samples.saved, Samples.history, Samples.books.associateBy { it.id }, Samples.NOW, TimeZone.getTimeZone("Europe/Prague")),
                onTab = {}, onOpen = {}, onRemoveSaved = {}, onRemoveHistory = {}, onClearHistory = noop,
            )
        }
    }

    @Test fun history() = snap {
        WithTabs(MainTab.SAVED) {
            SavedScreen(
                SavedState(SavedTab.HISTORY, Samples.saved, Samples.history, Samples.books.associateBy { it.id }, Samples.NOW, TimeZone.getTimeZone("Europe/Prague")),
                onTab = {}, onOpen = {}, onRemoveSaved = {}, onRemoveHistory = {}, onClearHistory = noop,
            )
        }
    }

    @Test fun settings() = snap {
        WithTabs(MainTab.SETTINGS) {
            SettingsScreen(
                SettingsState(textZoom = 115, drives = Samples.drives, hasRecentSearches = true),
                onLanguage = {}, onTheme = {}, onTextZoom = {}, onChangeDrive = noop,
                onSaveHistory = {}, onClearSearches = noop, onAbout = noop,
            )
        }
    }

    @Test fun about() = snap {
        AboutScreen(
            AboutState("1.0.0", "0.1.0", "2026-11", listOf(Notice("Jetpack Compose", "Apache License 2.0"), Notice("UniFFI", "MPL-2.0"))),
            onBack = noop,
        )
    }

    @Test fun bookDetails() = snap {
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(LibraryState(Samples.books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
            BookDetailsSheet(BookDetailsState(Samples.csWiki), onDismiss = noop, onOpen = noop)
        }
    }

    @Test fun readerTextSize() = snap {
        ReaderScreen(
            ReaderState(title = "Praha", book = Samples.csWiki, canGoBack = true, textZoom = 115, showTextPanel = true, theme = cz.offlinka.ui.model.ThemeMode.SYSTEM),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }
}

/** Further states, Czech light and dark. */
@RunWith(Parameterized::class)
class ScreenStatesTest(private val v: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = listOf(Variant.CS_LIGHT, Variant.CS_DARK, Variant.SK_LIGHT, Variant.EN_LIGHT)
    }

    @get:Rule val paparazzi = v.paparazzi()

    /** States are Czech only, except the store screenshots (map, guides) that need every language. */
    private fun snap(allLanguages: Boolean = false, content: @Composable () -> Unit) {
        org.junit.Assume.assumeTrue(v.locale == "cs" || allLanguages)
        paparazzi.snapshot { OfflinkaTheme(darkTheme = v.dark, content = content) }
    }

    @Test fun connectWrongFolder() = snap {
        ConnectScreen(ConnectState(problem = ConnectProblem.WRONG_FOLDER), onPick = noop)
    }

    @Test fun connectHelp() = snap {
        ConnectScreen(ConnectState(problem = ConnectProblem.NOT_FOUND, showHelp = true), onPick = noop)
    }

    @Test fun connectSearching() = snap { ConnectScreen(ConnectState(mode = ConnectMode.SEARCHING), onPick = noop) }

    @Test fun unplugged() = snap { ConnectScreen(ConnectState(mode = ConnectMode.UNPLUGGED), onPick = noop) }

    @Test fun searchRecent() = snap {
        SearchScreen(
            SearchState(recent = listOf("Sněžka", "krvácení", "Komenský", "první pomoc popáleniny")),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun searchNoResults() = snap {
        SearchScreen(
            SearchState(query = "xyzzy"),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun searchInBook() = snap {
        SearchScreen(
            SearchState(query = "krumlov", scope = Samples.csWiki, groups = Samples.searchGroups.take(1)),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun readerFind() = snap {
        ReaderScreen(
            ReaderState(
                title = "Praha", book = Samples.csWiki, canGoBack = true, canGoForward = true,
                find = FindState("Vltav", current = 2, total = 7, done = true),
            ),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun readerExternalLink() = snap {
        ReaderScreen(
            ReaderState(title = "Praha", book = Samples.csWiki, externalLink = "https://www.praha.eu/jnp/cz/index.html"),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun readerMap() = snap(allLanguages = true) {
        val map = if (v.locale == "sk") Samples.mapSk else Samples.mapCz
        ReaderScreen(
            ReaderState(title = if (v.locale == "sk") "Slovakia" else "Czech Republic", book = map, isApp = true, isMap = true),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { Column(Modifier.fillMaxSize().background(Offlinka.colors.surfaceMuted)) {} }
    }

    /** The bundled first-aid guide in the reader (store screenshot "První pomoc funguje i bez flashky"). */
    @Test fun readerGuides() = snap(allLanguages = true) {
        ReaderScreen(
            ReaderState(title = "První pomoc – rychlé karty", book = Samples.guides, canGoBack = true, saved = true),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun savedEmpty() = snap {
        WithTabs(MainTab.SAVED) {
            SavedScreen(SavedState(), onTab = {}, onOpen = {}, onRemoveSaved = {}, onRemoveHistory = {}, onClearHistory = noop)
        }
    }

    @Test fun homeSmallDrive() = snap {
        WithTabs(MainTab.HOME) {
            HomeScreen(
                HomeState(listOf(Samples.csWiki.copy(flavour = cz.offlinka.ui.model.Flavour.MINI), Samples.guides), hasRandom = true),
                onSearch = noop, onOpenBook = {}, onQuickLink = {}, onContinue = {},
            )
        }
    }

    @Test fun homeOpeningAndFailed() = snap {
        WithTabs(MainTab.HOME) {
            HomeState(
                Samples.books.take(6).mapIndexed { i, b ->
                    b.copy(state = when (i) { 1, 2 -> BookState.OPENING; 3 -> BookState.FAILED; else -> BookState.OPEN })
                },
            ).let { HomeScreen(it, onSearch = noop, onOpenBook = {}, onQuickLink = {}, onContinue = {}) }
        }
    }

    @Test fun connectDriveSeen() = snap { ConnectScreen(ConnectState(driveSeen = true), onPick = noop) }

    @Test fun connectSlow() = snap { ConnectScreen(ConnectState(mode = ConnectMode.SEARCHING, slow = true), onPick = noop) }

    @Test fun unpluggedWithGuides() = snap {
        ConnectScreen(ConnectState(mode = ConnectMode.UNPLUGGED, canOpenGuides = true), onPick = noop)
    }

    @Test fun readerContents() = snap {
        ReaderScreen(
            ReaderState(
                title = "Praha", book = Samples.csWiki, canGoBack = true,
                contents = listOf(
                    TocEntry("Nazev", "Název", 2), TocEntry("Historie", "Historie", 2), TocEntry("Pravek", "Pravěk", 3),
                    TocEntry("Stredovek", "Středověk", 3), TocEntry("Geografie", "Geografie", 2), TocEntry("Doprava", "Doprava", 2),
                ),
            ),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun searchIndexingAndRetry() = snap {
        SearchScreen(
            SearchState(query = "krvácení z nosu u dětí", groups = Samples.searchGroups.take(1), retriedWord = "krvácení", searchNote = SearchNote(percent = 42), accentFreeReady = false),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun settingsTwoDrives() = snap {
        WithTabs(MainTab.SETTINGS) {
            SettingsScreen(
                SettingsState(drives = Samples.drives + cz.offlinka.ui.model.DriveUi("old", "STARA", 0, false)),
                onLanguage = {}, onTheme = {}, onTextZoom = {}, onChangeDrive = noop,
                onSaveHistory = {}, onClearSearches = noop, onAbout = noop,
            )
        }
    }

    @Test fun bookDetailsSupport() = snap {
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(LibraryState(Samples.books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
            BookDetailsSheet(BookDetailsState(Samples.csWiki, supportOpen = true), onDismiss = noop, onOpen = noop)
        }
    }

    @Test fun bookDetailsIndexOffer() = snap {
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(LibraryState(Samples.books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
            BookDetailsSheet(BookDetailsState(Samples.enWiki.copy(hasIndex = false), index = IndexInfo(IndexPhase.OFFER)), onDismiss = noop, onOpen = noop)
        }
    }

    @Test fun readerSaved() = snap {
        ReaderScreen(
            ReaderState(title = "Praha", book = Samples.csWiki, saved = true),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun searchNoteReady() = snap {
        SearchScreen(
            SearchState(searchNote = SearchNote(ready = true), recent = listOf("Sněžka")),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun searchBuildingRecent() = snap {
        SearchScreen(
            SearchState(searchNote = SearchNote(percent = 40), accentFreeReady = false, recent = listOf("Sněžka")),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun searchNoResultsWhileIndexing() = snap {
        SearchScreen(
            SearchState(query = "krvaceni", searchNote = SearchNote(percent = 12), accentFreeReady = false),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }

    @Test fun homeTryNowAndNote() = snap {
        WithTabs(MainTab.HOME) {
            HomeScreen(
                HomeState(Samples.books, searchNote = SearchNote(percent = 40), tryNow = Samples.tryNow),
                onSearch = noop, onOpenBook = {}, onQuickLink = {}, onContinue = {},
            )
        }
    }

    @Test fun libraryOpeningAndFailed() = snap {
        WithTabs(MainTab.LIBRARY) {
            LibraryScreen(
                LibraryState(
                    Samples.books.mapIndexed { i, b -> b.copy(state = when (i) { 0 -> BookState.OPENING; 1 -> BookState.FAILED; else -> BookState.OPEN }) },
                    drive = Samples.drives.first(),
                ),
                onOpen = {}, onDetails = {},
            )
        }
    }

    @Test fun bookDetailsQueued() = snap { DetailsWithIndex(IndexInfo(IndexPhase.QUEUED)) }

    @Test fun bookDetailsBuilding() = snap { DetailsWithIndex(IndexInfo(IndexPhase.BUILDING, 37)) }

    @Test fun bookDetailsLowSpace() = snap { DetailsWithIndex(IndexInfo(IndexPhase.LOW_SPACE)) }

    @Composable
    private fun DetailsWithIndex(index: IndexInfo) {
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(LibraryState(Samples.books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
            BookDetailsSheet(BookDetailsState(Samples.enWiki.copy(hasIndex = false), index = index), onDismiss = noop, onOpen = noop)
        }
    }

    @Test fun libraryDetailsPhoneEdition() = snap {
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(LibraryState(Samples.books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
            BookDetailsSheet(BookDetailsState(Samples.phoneEditions.first(), replaced = true), onDismiss = noop, onOpen = noop)
        }
    }

    @Test fun settingsLanguagePicker() = snap {
        SettingsScreen(
            SettingsState(language = cz.offlinka.ui.model.AppLanguage.CS, drives = Samples.drives),
            onLanguage = {}, onTheme = {}, onTextZoom = {}, onChangeDrive = noop,
            onSaveHistory = {}, onClearSearches = noop, onAbout = noop, languagePickerOpen = true,
        )
    }

    @Test fun aboutNotices() = snap {
        AboutState("1.0.0", "0.1.0", "2026-11", listOf(Notice("Jetpack Compose", "Apache License 2.0"), Notice("UniFFI", "MPL-2.0")), noticesOpen = true)
            .let { AboutScreen(it, onBack = noop) }
    }
}

@Composable
private fun WithTabs(tab: MainTab, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) { content() }
        OfflinkaBottomBar(tab, onSelect = {})
    }
}

/** Stand-in for the WebView (which layoutlib cannot render): what an article roughly looks like. */
@Composable
private fun ArticleMock() {
    val c = Offlinka.colors
    Column(Modifier.fillMaxSize().background(c.bg).padding(16.dp)) {
        Text("Praha", style = Offlinka.type.display, color = c.ink)
        Text(
            "Praha je hlavní a současně největší město Česka a 12. největší město Evropské unie. " +
                "Leží mírně na sever od středu Čech na řece Vltavě, uvnitř Pražské plošiny.",
            style = Offlinka.type.body, color = c.ink, modifier = Modifier.padding(top = 12.dp),
        )
        Text("Historie", style = Offlinka.type.title, color = c.ink, modifier = Modifier.padding(top = 20.dp))
        Text(
            "Území Prahy bylo osídleno již v paleolitu. Pražský hrad byl založen koncem 9. století.",
            style = Offlinka.type.body, color = c.ink, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Slovak UI (the Slovak Wikipedia leads) and Czech at 160 % system font size. */
@RunWith(Parameterized::class)
class LocaleAndFontTest(private val v: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = listOf(Variant.SK_LIGHT, Variant.CS_LARGE)
    }

    @get:Rule val paparazzi = v.paparazzi()

    private fun snap(content: @Composable () -> Unit) = paparazzi.snapshot { OfflinkaTheme(darkTheme = v.dark, content = content) }

    private val books get() = if (v.locale == "sk") Samples.booksSk else Samples.books

    @Test fun home() = snap {
        WithTabs(MainTab.HOME) {
            HomeScreen(HomeState(books), onSearch = noop, onOpenBook = {}, onQuickLink = {}, onContinue = {})
        }
    }

    @Test fun connect() = snap { ConnectScreen(ConnectState(), onPick = noop) }

    @Test fun library() = snap {
        WithTabs(MainTab.LIBRARY) {
            LibraryScreen(LibraryState(books, Samples.phoneEditions, Samples.drives.first()), onOpen = {}, onDetails = {})
        }
    }

    @Test fun settings() = snap {
        WithTabs(MainTab.SETTINGS) {
            SettingsScreen(
                SettingsState(textZoom = 115, drives = Samples.drives),
                onLanguage = {}, onTheme = {}, onTextZoom = {}, onChangeDrive = noop,
                onSaveHistory = {}, onClearSearches = noop, onAbout = noop,
            )
        }
    }

    @Test fun saved() = snap {
        WithTabs(MainTab.SAVED) {
            SavedScreen(
                SavedState(SavedTab.HISTORY, Samples.saved, Samples.history, Samples.books.associateBy { it.id }, Samples.NOW, TimeZone.getTimeZone("Europe/Prague")),
                onTab = {}, onOpen = {}, onRemoveSaved = {}, onRemoveHistory = {}, onClearHistory = noop,
            )
        }
    }

    @Test fun reader() = snap {
        ReaderScreen(
            ReaderState(title = "Praha", book = Samples.csWiki, canGoBack = true),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { ArticleMock() }
    }

    @Test fun search() = snap {
        SearchScreen(
            SearchState(query = "cesky krumlov", groups = Samples.searchGroups),
            onQueryChange = {}, onSubmit = noop, onHit = {}, onRecent = {}, onRemoveRecent = {}, onScope = {}, onBack = noop,
            autoFocus = false,
        )
    }
}

/** A 360 × 640 dp budget phone at 100 % and 160 % font: the connect button must stay on screen. */
@RunWith(Parameterized::class)
class SmallPhoneTest(private val v: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = listOf(Variant.SMALL, Variant.SMALL_LARGE)
    }

    @get:Rule val paparazzi = v.paparazzi()

    private fun snap(content: @Composable () -> Unit) = paparazzi.snapshot { OfflinkaTheme(darkTheme = v.dark, content = content) }

    @Test fun connect() = snap { ConnectScreen(ConnectState(canOpenGuides = true), onPick = noop) }

    @Test fun connectDriveSeen() = snap { ConnectScreen(ConnectState(driveSeen = true, canOpenGuides = true), onPick = noop) }

    @Test fun home() = snap {
        WithTabs(MainTab.HOME) {
            HomeScreen(HomeState(Samples.books, searchNote = SearchNote(percent = 40)), onSearch = noop, onOpenBook = {}, onQuickLink = {}, onContinue = {})
        }
    }
}
