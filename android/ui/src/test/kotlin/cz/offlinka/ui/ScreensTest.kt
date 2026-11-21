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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.TimeZone

/** One variant of the device: UI language and light/dark. */
data class Variant(val name: String, val locale: String, val dark: Boolean) {
    override fun toString() = name

    fun paparazzi() = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            locale = locale,
            nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
            softButtons = false,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 0.1,
    )

    companion object {
        val CS_LIGHT = Variant("cs-light", "cs", false)
        val CS_DARK = Variant("cs-dark", "cs", true)
        val EN_LIGHT = Variant("en-light", "en", false)
        val EN_DARK = Variant("en-dark", "en", true)
    }
}

private val noop: () -> Unit = {}

/** Every main screen in Czech and English, light and dark. Goldens: ui/src/test/snapshots/images. */
@RunWith(Parameterized::class)
class ScreensTest(private val v: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = listOf(Variant.CS_LIGHT, Variant.CS_DARK, Variant.EN_LIGHT, Variant.EN_DARK)
    }

    @get:Rule val paparazzi = v.paparazzi()

    private fun snap(content: @Composable () -> Unit) = paparazzi.snapshot { OfflinkaTheme(darkTheme = v.dark, content = content) }

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

    @Test fun reader() = snap {
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
        fun variants() = listOf(Variant.CS_LIGHT, Variant.CS_DARK)
    }

    @get:Rule val paparazzi = v.paparazzi()

    private fun snap(content: @Composable () -> Unit) = paparazzi.snapshot { OfflinkaTheme(darkTheme = v.dark, content = content) }

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

    @Test fun readerMap() = snap {
        ReaderScreen(
            ReaderState(title = "Czech Republic", book = Samples.mapCz, isApp = true),
            onHome = noop, onBack = noop, onForward = noop, onToggleSave = noop, onSearch = noop, onOpenFind = noop,
            onFindQuery = {}, onFindNext = {}, onCloseFind = noop, onToggleTextPanel = noop, onTextZoom = {},
            onTheme = {}, onMainPage = noop, onBookDetails = noop, onHistory = noop, onDismissExternal = noop,
            onCopyLink = {},
        ) { Column(Modifier.fillMaxSize().background(Offlinka.colors.surfaceMuted)) {} }
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
