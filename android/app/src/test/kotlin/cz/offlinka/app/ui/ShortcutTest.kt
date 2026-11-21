package cz.offlinka.app.ui

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.MainActivity
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.graph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** App-icon shortcuts open the bundled guides even with no drive at all. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, qualifiers = "cs")
class ShortcutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `the První pomoc shortcut opens the first-aid cards without a drive`() {
        val context = ApplicationProvider.getApplicationContext<TestApp>()
        val intent = Intent(MainActivity.ACTION_OPEN_GUIDE)
            .setClassName(context, MainActivity::class.java.name)
            .putExtra(MainActivity.EXTRA_PATH, "prvni-pomoc.html")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Driven by the shelf's state, not by time: the first scan ends (no drive), then the overlay shows.
            val graph = context.graph
            // The main looper runs while waiting: the shelf resumes there after its IO work.
            compose.waitUntil(30_000) {
                shadowOf(Looper.getMainLooper()).idle()
                graph.shelf.state.value !is ShelfState.Loading && graph.openRequest.value == null
            }
            compose.waitForIdle()
            compose.onNodeWithText("Nouzové příručky").assertExists()
            scenario.onActivity { a ->
                assertThat(a.graph.readerPage.value.url).isNull()
                assertThat(a.graph.openRequest.value).isNull()
            }
        }
    }

    @Test fun `a shortcut with a strange page is ignored`() {
        val context = ApplicationProvider.getApplicationContext<TestApp>()
        val intent = Intent(MainActivity.ACTION_OPEN_GUIDE)
            .setClassName(context, MainActivity::class.java.name)
            .putExtra(MainActivity.EXTRA_PATH, "../../etc/passwd")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { a -> assertThat(a.graph.openRequest.value).isNull() }
        }
    }
}
