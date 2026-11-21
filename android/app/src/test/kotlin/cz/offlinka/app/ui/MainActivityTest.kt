package cz.offlinka.app.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun `first start asks for the drive and opens the system folder picker`() {
        waitForText("Zapojte flashku Offlinka")
        compose.onNodeWithText("Vybrat flashku").performClick()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertThat(started.intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    @Test fun `a granted drive opens home, unplugging shows the calm screen and plugging back returns`() {
        app.drives.granted += app.tree
        compose.activity.graph.shelf.reloadAsync()
        waitForText("Česká Wikipedie")
        compose.onNodeWithText("Nouzové příručky").assertExists()

        app.drives.drives.getValue(app.tree).plugged = false
        app.books.forEach { it.unplugged = true }
        compose.activity.graph.shelf.reportIoError(java.io.IOException("EIO"))
        waitForText("Flashka je odpojená")
        assertThat(app.books.all { it.closed }).isTrue()

        app.drives.drives.getValue(app.tree).plugged = true
        compose.activity.graph.shelf.onStorageChanged() // what the mount broadcast triggers
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Flashka je odpojená").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Česká Wikipedie").assertExists()
    }
}
