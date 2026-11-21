package cz.offlinka.app.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class PageRecoveryTest {
    @Test fun `a page shown before its book was served reloads once the book is there`() {
        val r = PageRecovery()
        r.onPageStarted("wikivoyage_en_europe", served = false)
        // The first Ready state has only the Czech Wikipedia: not yet.
        assertThat(r.shouldReload(setOf("wikipedia_cs_all"))).isFalse()
        // Wikivoyage opened later (same generation): now.
        assertThat(r.shouldReload(setOf("wikipedia_cs_all", "wikivoyage_en_europe"))).isTrue()
        assertThat(r.shouldReload(setOf("wikipedia_cs_all", "wikivoyage_en_europe"))).isFalse()
    }

    @Test fun `a 503 main frame (book closed, unplugged or opening) reloads, a 404 does not`() {
        val r = PageRecovery()
        r.onPageStarted("wikipedia_cs_all", served = true)
        r.onMainFrameStatus("wikipedia_cs_all", 404)
        assertThat(r.shouldReload(setOf("wikipedia_cs_all"))).isFalse()
        r.onMainFrameStatus("wikipedia_cs_all", 503)
        assertThat(r.shouldReload(setOf("wikipedia_cs_all"))).isTrue()
    }

    @Test fun `a normal page and pages that are not ours are left alone`() {
        val r = PageRecovery()
        r.onPageStarted("wikipedia_cs_all", served = true)
        assertThat(r.shouldReload(setOf("wikipedia_cs_all"))).isFalse()
        r.onPageStarted(null, served = false)
        assertThat(r.shouldReload(setOf("wikipedia_cs_all"))).isFalse()
    }
}

class LinkPolicyTest {
    @Test fun `files and phone links go to other apps only right after a touch`() {
        assertThat(LinkPolicy.userInitiated(lastTouch = 0, now = 10_000)).isFalse()
        assertThat(LinkPolicy.userInitiated(lastTouch = 9_000, now = 10_000)).isTrue()
        assertThat(LinkPolicy.userInitiated(lastTouch = 1_000, now = 10_000)).isFalse()
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
