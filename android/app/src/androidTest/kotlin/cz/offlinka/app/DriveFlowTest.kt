package cz.offlinka.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.ui.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on an emulator, with a folder as the drive (FolderDrive): the system picker is answered
 * with that folder, the real Rust core reads the guides book from it, the real WebView shows it.
 */
@RunWith(AndroidJUnit4::class)
class DriveFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<FixtureApp>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    private fun str(id: Int) = activity.getString(id)

    private fun waitForText(text: String, timeout: Long = 20_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String, timeout: Long = 20_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }

    private fun tap(text: String) {
        val node = compose.onAllNodesWithText(text).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before fun setUp() {
        app.drive.prepare(app)
        onMain { app.graph.shelf.reloadAsync() }
        Intents.init()
        // The system folder picker answers with the fixture drive.
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(app.drive.tree)))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After fun tearDown() {
        Intents.release()
        scenario.close()
    }

    /** Connect screen → picker → Home with the drive's tiles. */
    private fun connect() {
        waitForText(str(R.string.connect_title))
        tap(str(R.string.connect_button))
        // An emulator has no USB volume: the first tap explains, the second opens the picker anyway.
        compose.waitForIdle()
        if (compose.onAllNodesWithText(str(R.string.connect_pick_again)).fetchSemanticsNodes().isNotEmpty()) {
            tap(str(R.string.connect_pick_again))
        }
        waitForText(str(R.string.home_books_title))
    }

    @Test fun connectShowsHomeWithTheDrivesBooks() {
        connect()
        compose.onNodeWithText(str(R.string.book_guides)).assertExists()
        val ready = app.graph.shelf.state.value as ShelfState.Ready
        assertEquals(listOf("offlinka_cs_prirucky"), ready.tiles.map { it.id })
        assertTrue(ready.books.single().builtin.not())
    }

    @Test fun searchOpensTheArticleAndBackReturnsToTheSearch() {
        connect()
        tap(str(R.string.home_search_placeholder))
        compose.onNode(hasSetTextAction()).performTextInput("prvni pomoc")
        // Accent-free: the guides' index is built on the phone in well under a second.
        waitForText("První pomoc – rychlé karty", timeout = 30_000)
        tap("První pomoc – rychlé karty")
        compose.waitUntil(20_000) { app.graph.readerPage.value.url?.contains("prvni-pomoc.html") == true }
        // "Zpět" is never disabled: with no page history it returns to the search, query kept.
        tap(str(R.string.reader_bar_back))
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("prvni pomoc")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("První pomoc – rychlé karty").assertExists()
    }

    /**
     * The API 26 regression of 0.3.0: a page requested while its book is not served yet (after process
     * death, or a book still opening) is answered 503, and old WebViews send no HTTP-error callback for
     * intercepted responses. The page must still load once the book is there.
     */
    @Test fun aPageOfABookStillOpeningLoadsOnceTheBookIsThere() {
        scenario.close()
        Intents.release()
        val slowBook = "wikivoyage_en_europe_maxi-offlinka_2026-09.zim"
        app.drive.prepare(app, alsoAs = listOf(slowBook))
        val gate = java.util.concurrent.CountDownLatch(1)
        app.drive.gates[slowBook] = gate
        onMain { app.graph.shelf.reloadAsync() }
        Intents.init()
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(app.drive.tree)))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }

        connect()
        val url = ReaderUrls.article("wikivoyage_en_europe", "prvni-pomoc.html")
        assertTrue(app.graph.shelf.isPending("wikivoyage_en_europe"))
        onMain { app.graph.openRequest.value = url }
        // The reader asked for the page while the book was opening: "come back later".
        compose.waitUntil(20_000) { app.graph.server.wasUnavailable(url) }
        gate.countDown()
        // The book opens; the page loads by itself (its title reaches the reader).
        compose.waitUntil(30_000) { app.graph.readerPage.value.title.startsWith("První pomoc") }
        assertTrue(!app.graph.server.wasUnavailable(url))
    }

    @Test fun pullingTheDriveMidReadShowsTheCoverAndPluggingBackRestoresTheArticle() {
        connect()
        tap(str(R.string.home_search_placeholder))
        compose.onNode(hasSetTextAction()).performTextInput("prvni pomoc")
        waitForText("První pomoc – rychlé karty", timeout = 30_000)
        tap("První pomoc – rychlé karty")
        compose.waitUntil(20_000) { app.graph.readerPage.value.url?.contains("prvni-pomoc.html") == true }

        app.drive.unplug()
        onMain { app.graph.shelf.onStorageChanged() } // what the unmount broadcast does
        waitForText(str(R.string.unplugged_title))

        app.drive.plugIn()
        // The app polls for the drive while it is in the foreground.
        gone(str(R.string.unplugged_title), timeout = 30_000)
        compose.waitUntil(20_000) { app.graph.shelf.state.value is ShelfState.Ready }
        assertTrue(app.graph.readerPage.value.url!!.contains("prvni-pomoc.html"))
        compose.onNodeWithText(str(R.string.reader_bar_back)).assertExists()
    }
}
