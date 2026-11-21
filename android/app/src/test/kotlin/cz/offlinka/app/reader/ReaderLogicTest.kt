package cz.offlinka.app.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class PageRecoveryTest {
    @Test fun `a page shown before the books were open is reloaded once they are`() {
        val r = PageRecovery()
        r.onPageStarted(booksOpen = false)
        assertThat(r.shouldReloadWhenReady()).isTrue()
        assertThat(r.shouldReloadWhenReady()).isFalse()
    }

    @Test fun `a 503 main frame (book closed or unplugged) is reloaded, a 404 is not`() {
        val r = PageRecovery()
        r.onPageStarted(booksOpen = true)
        r.onMainFrameStatus(404)
        assertThat(r.shouldReloadWhenReady()).isFalse()
        r.onMainFrameStatus(503)
        assertThat(r.shouldReloadWhenReady()).isTrue()
    }

    @Test fun `a normal page is left alone`() {
        val r = PageRecovery()
        r.onPageStarted(booksOpen = true)
        assertThat(r.shouldReloadWhenReady()).isFalse()
    }
}

class ReaderZoomTest {
    @Test fun `the system font scale multiplies the reader's own size`() {
        assertThat(ReaderZoom.textZoom(100, 1f)).isEqualTo(100)
        assertThat(ReaderZoom.textZoom(100, 1.3f)).isEqualTo(130)
        assertThat(ReaderZoom.textZoom(150, 2f)).isEqualTo(300)
        assertThat(ReaderZoom.cssScale(120, 1.5f)).isWithin(0.001f).of(1.8f)
        assertThat(ReaderZoom.textZoom(200, 5f)).isEqualTo(400)
    }
}

@RunWith(RobolectricTestRunner::class)
class HeadingTest {
    @Test fun `contents come back from evaluateJavascript as a JSON string`() {
        val evaluated = "\"[{\\\"id\\\":\\\"Historie\\\",\\\"text\\\":\\\"Historie\\\",\\\"level\\\":2},{\\\"id\\\":\\\"ofl-h-1\\\",\\\"text\\\":\\\"Pravěk\\\",\\\"level\\\":3}]\""
        assertThat(Heading.parse(evaluated)).containsExactly(
            Heading("Historie", "Historie", 2),
            Heading("ofl-h-1", "Pravěk", 3),
        ).inOrder()
        assertThat(Heading.parse("null")).isEmpty()
        assertThat(Heading.parse("garbage")).isEmpty()
    }
}
