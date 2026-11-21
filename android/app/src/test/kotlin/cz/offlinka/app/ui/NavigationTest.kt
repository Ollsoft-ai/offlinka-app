package cz.offlinka.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.TestGraph
import cz.offlinka.app.reader.ReaderController
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.ui.theme.OfflinkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real navigation graph on fake books: tabs, opening books, search into the reader. Czech UI. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "cs")
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var t: TestGraph
    private lateinit var nav: NavHostController
    private lateinit var reader: ReaderController

    @Before fun setUp() {
        t = TestGraph(compose.activity, scope)
        t.connect()
        reader = ReaderController(compose.activity, t.graph.server, t.graph.shelf, scope, t.graph.readerPage)
        compose.setContent {
            nav = rememberNavController()
            OfflinkaTheme(darkTheme = false) {
                CompositionLocalProvider(LocalReaderController provides reader) {
                    AppNavHost(t.graph, nav, onChangeDrive = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        compose.runOnUiThread { reader.destroy() }
        scope.cancel()
    }

    private fun route() = nav.currentBackStackEntry?.destination?.route

    @Test fun `home lists the books of the drive`() {
        compose.onNodeWithText("Co chcete najít?").assertIsDisplayed()
        compose.onNodeWithText("Česká Wikipedie").assertExists()
        compose.onNodeWithText("Nouzové příručky").assertExists()
        compose.onNodeWithText("Mapa Česka").assertExists()
    }

    @Test fun `tapping a book opens its main page in the reader`() {
        compose.onNodeWithText("Česká Wikipedie").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.READER)
        assertThat(nav.currentBackStackEntry?.arguments?.getString("url"))
            .isEqualTo(ReaderUrls.article("wikipedia_cs_all", "Hlavní_strana"))
    }

    @Test fun `bottom bar switches tabs`() {
        compose.onNodeWithText("Knihovna").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.LIBRARY)
        compose.onNodeWithText("Uložené").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SAVED)
        compose.onNodeWithText("Zatím nic uloženého").assertExists()
        compose.onNodeWithText("Nastavení").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETTINGS)
        compose.onNodeWithText("Domů").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.HOME)
    }

    @Test fun `search finds an article and opens it`() {
        compose.onNodeWithText("Hledat v Offlince").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SEARCH)
        compose.onNode(hasSetTextAction()).performTextInput("cesky")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Český Krumlov").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Český Krumlov").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.READER)
        assertThat(nav.currentBackStackEntry?.arguments?.getString("url"))
            .isEqualTo(ReaderUrls.article("wikipedia_cs_all", "Český_Krumlov"))
        // Back from the reader returns home, not to the search.
        compose.runOnUiThread { nav.popBackStack() }
        assertThat(route()).isEqualTo(Routes.HOME)
    }

    @Test fun `library details show the file and open the book`() {
        compose.onNodeWithText("Knihovna").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Podrobnosti")[0].performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.BOOK)
        compose.onNodeWithText("wikipedia_cs_all_maxi-offlinka_2026-09.zim").assertExists()
        compose.onNodeWithText("Otevřít úvodní stránku").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.READER)
    }

    @Test fun `settings open about`() {
        compose.onNodeWithText("Nastavení").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("O aplikaci").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.ABOUT)
        compose.onNodeWithText("Verze", substring = true).assertExists()
    }
}
