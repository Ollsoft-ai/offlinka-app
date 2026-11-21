package cz.offlinka.app.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.MainActivity
import cz.offlinka.app.OfflinkaApp
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.drive.DriveAccess
import cz.offlinka.app.fakes.FakeBook
import cz.offlinka.app.fakes.FakeContentSource
import cz.offlinka.app.fakes.FakeDriveAccess
import cz.offlinka.app.fakes.fakeGuides
import cz.offlinka.app.fakes.fakeWiki
import cz.offlinka.app.fakes.memoryDb
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.graph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app on fake drives: Robolectric starts it through this Application. */
class TestApp : OfflinkaApp() {
    val drives = FakeDriveAccess()
    val books = mutableListOf<FakeBook>()
    val tree = drives.add(
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3A", "OFFLINKA",
        "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 9_958_167_476L,
        "Telefon/offlinka_cs_prirucky.zim" to 393_612L,
    )

    override fun createSource(): ContentSource = FakeContentSource(
        mapOf(
            "wikipedia_cs_all_maxi-offlinka_2026-09.zim" to { fakeWiki().also { books += it } },
            "offlinka_cs_prirucky.zim" to { fakeGuides().also { books += it } },
        ),
    )

    override fun createDrives(): DriveAccess = drives

    override fun createDatabase(): AppDatabase = memoryDb(this)

    override val watchStorageBroadcasts = false
}

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, qualifiers = "cs")
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as TestApp

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Waits for [done] while running the main looper (where the shelf resumes after its IO work);
     * compose's waitUntil alone does not run it in Robolectric when the condition reads no UI.
     */
    private fun awaitIdle(done: () -> Boolean) = compose.waitUntil(30_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        done()
    }

    /** Clicks a button that may sit in the scrolling column or pinned below it (compact screens). */
    private fun tap(text: String) {
        val node = compose.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test fun `first start asks for the drive and opens the system folder picker`() {
        waitForText("Zapojte flashku Offlinka")
        compose.activity.graph.driveVisible.value = null
        tap("Vybrat flashku")
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertThat(started.intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    @Test fun `with no USB volume mounted the first tap explains, the second opens the picker anyway`() {
        waitForText("Zapojte flashku Offlinka")
        compose.runOnUiThread { compose.activity.graph.driveVisible.value = false }
        tap("Vybrat flashku")
        waitForText("Flashku nevidím")
        assertThat(shadowOf(compose.activity).nextStartedActivityForResult).isNull()
        tap("Vybrat znovu")
        assertThat(shadowOf(compose.activity).nextStartedActivityForResult.intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    @Test fun `a mounted drive is pointed out`() {
        waitForText("Zapojte flashku Offlinka")
        compose.runOnUiThread { compose.activity.graph.driveVisible.value = true }
        // Step 1 is marked done instead of a note that repeats the button.
        waitForText("Flashka je zapojená.")
    }

    @Test fun `the help opens as a sheet`() {
        waitForText("Zapojte flashku Offlinka")
        tap("Flashka se neukazuje?")
        waitForText("Když se flashka neukazuje")
    }

    @Test fun `the screen stays on while a search index is built`() {
        val source = compose.activity.graph.source as cz.offlinka.app.fakes.FakeContentSource
        val gate = java.util.concurrent.CountDownLatch(1)
        source.buildGate = gate
        app.drives.granted += app.tree
        compose.activity.graph.shelf.reloadAsync()
        waitForText("Česká Wikipedie")
        fun keptOn() = compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        awaitIdle { keptOn() }
        gate.countDown()
        awaitIdle { !keptOn() }
    }

    @Test fun `a granted drive opens home, unplugging shows the calm screen and plugging back returns`() {
        app.drives.granted += app.tree
        compose.activity.graph.shelf.reloadAsync()
        waitForText("Česká Wikipedie")
        compose.onNodeWithText("Nouzové příručky").assertExists()

        app.drives.drives.getValue(app.tree).plugged = false
        val bundled = compose.activity.graph.shelf.builtin?.content
        val driveBooks = app.books.filter { it !== bundled }
        driveBooks.forEach { it.unplugged = true }
        compose.activity.graph.shelf.reportIoError(java.io.IOException("EIO"))
        waitForText("Flashka je odpojená")
        assertThat(driveBooks.all { it.closed }).isTrue()

        // Back on the cover screen does not move the hidden pages and does not crash.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Flashka je odpojená")

        // The bundled guides open without the drive.
        tap("Otevřít první pomoc")
        waitForText("Nouzové příručky")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Flashka je odpojená")

        app.drives.drives.getValue(app.tree).plugged = true
        compose.activity.graph.shelf.onStorageChanged() // what the mount broadcast triggers
        // Driven by the shelf's state: every book open again, then the UI settles.
        val shelf = compose.activity.graph.shelf
        awaitIdle { (shelf.state.value as? ShelfState.Ready)?.opening == false }
        compose.waitForIdle()
        compose.onNodeWithText("Flashka je odpojená").assertDoesNotExist()
        compose.onNodeWithText("Česká Wikipedie", useUnmergedTree = true).assertExists()
    }
}
