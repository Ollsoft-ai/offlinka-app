package cz.offlinka.app.reader

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val t = TestGraph(context, scope)

    private fun controller() = ReaderController(context, t.graph.server, t.graph.shelf, scope)

    @After fun tearDown() {
        ReaderController.timersPaused = false
        scope.cancel()
    }

    @Test fun `timers paused by one activity are resumed by the WebView of the recreated one`() {
        // Activity A: in the background, timers paused (process-wide).
        val a = controller()
        a.webView
        a.pause()
        assertThat(ReaderController.timersPaused).isTrue()
        a.destroy()
        // Activity B (language change, font size, "don't keep activities"): resumed before its WebView exists.
        val b = controller()
        b.resume()
        assertThat(ReaderController.timersPaused).isTrue()
        // Its WebView is created during composition: it must not start with frozen timers.
        b.webView
        assertThat(ReaderController.timersPaused).isFalse()
        b.destroy()
    }

    @Test fun `a destroyed controller stops following the shelf, so the activity can go`() {
        val c = controller()
        assertThat(c.stateJob.isActive).isTrue()
        c.destroy()
        assertThat(c.stateJob.isCancelled).isTrue()
    }

    @Test fun `TalkBack does not read the reader under the connect cover`() {
        val c = controller()
        c.setCovered(true)
        val w = c.webView
        assertThat(w.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        c.setCovered(false)
        assertThat(w.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO)
        c.destroy()
    }
}
