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

    @Test fun `a dead renderer does not take the app down, the page comes back in a new WebView`() {
        val c = controller()
        val first = c.webView
        val url = ReaderUrls.article("offlinka_cs_prirucky", "prvni-pomoc.html")
        c.load(url, fresh = true)
        val client = org.robolectric.Shadows.shadowOf(first).webViewClient
        val gone = object : android.webkit.RenderProcessGoneDetail() {
            override fun didCrash() = false // killed for memory
            override fun rendererPriorityAtExit() = 0
        }
        val generation = c.generation.value
        assertThat(client.onRenderProcessGone(first, gone)).isTrue()
        assertThat(c.generation.value).isEqualTo(generation + 1)
        val second = c.webView
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(org.robolectric.Shadows.shadowOf(second).lastLoadedUrl).isEqualTo(url)
        c.destroy()
    }

    @Test fun `retry after the system WebView was missing asks the host for a new one`() {
        val c = controller()
        val generation = c.generation.value
        c.retry()
        assertThat(c.unavailable.value).isFalse()
        assertThat(c.generation.value).isEqualTo(generation + 1)
        c.destroy()
    }
}

class FileCopyTest {
    @Test fun `files are copied in chunks and big ones are refused`() {
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val book = cz.offlinka.app.fakes.FakeBook(
            cz.offlinka.app.fakes.meta("x"),
            mapOf("pdf/karta.pdf" to cz.offlinka.app.fakes.FakeEntry("application/pdf", data)),
        )
        val dir = java.nio.file.Files.createTempDirectory("shared").toFile()
        java.io.File(dir, "old.pdf").writeText("previous file")
        val out = FileCopy.toCache(book, "pdf/karta.pdf", dir)!!
        assertThat(out.name).isEqualTo("karta.pdf")
        assertThat(out.readBytes()).isEqualTo(data)
        assertThat(dir.list()!!.toList()).containsExactly("karta.pdf")
        assertThat(book.gets).isEqualTo(0) // never the whole entry in memory

        val huge = object : cz.offlinka.app.content.ContentBook by book {
            override fun contentLength(path: String) = FileCopy.MAX_BYTES + 1
        }
        assertThat(FileCopy.toCache(huge, "pdf/karta.pdf", dir)).isNull()
    }
}
