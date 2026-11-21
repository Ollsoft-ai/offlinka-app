package cz.offlinka.app.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.MainActivity
import cz.offlinka.app.graph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Nouzové příručky").fetchSemanticsNodes().isNotEmpty() }
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
