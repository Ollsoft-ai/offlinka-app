package cz.offlinka.app.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderUrlsTest {
    @Test fun `article urls round-trip with diacritics and odd characters`() {
        for (path in listOf("Praha", "Český_Krumlov", "C#", "100%_čistý", "AC/DC", "Kdo?_Co?", "a+b", "_mw_/style.css")) {
            val url = ReaderUrls.article("wikipedia_cs_all", path)
            assertThat(url).startsWith("https://offlinka.local/wikipedia_cs_all/")
            assertThat(ReaderUrls.parse(url)).isEqualTo(ReaderUrls.Target("wikipedia_cs_all", path))
        }
    }

    @Test fun `query and fragment are not part of the path`() {
        assertThat(ReaderUrls.parse("https://offlinka.local/maps/index.html?lat=50#z=7"))
            .isEqualTo(ReaderUrls.Target("maps", "index.html"))
    }

    @Test fun `book root has an empty path`() {
        assertThat(ReaderUrls.parse("https://offlinka.local/wikipedia_cs_all/")?.path).isEmpty()
        assertThat(ReaderUrls.parse("https://offlinka.local/wikipedia_cs_all")?.path).isEmpty()
    }

    @Test fun `other hosts and schemes are not ours`() {
        assertThat(ReaderUrls.parse("https://cs.wikipedia.org/wiki/Praha")).isNull()
        assertThat(ReaderUrls.parse("http://offlinka.local/a/b")).isNull()
        assertThat(ReaderUrls.isOurs("https://offlinka.local/a")).isTrue()
        assertThat(ReaderUrls.isOurs("https://example.com/")).isFalse()
    }

    @Test fun `wikimedia links map to offline books`() {
        assertThat(ReaderUrls.wikimediaTarget("https://cs.wikipedia.org/wiki/%C4%8Cesk%C3%BD_Krumlov"))
            .isEqualTo(listOf("wikipedia_cs_all") to "Český_Krumlov")
        assertThat(ReaderUrls.wikimediaTarget("https://cs.m.wikipedia.org/wiki/Praha"))
            .isEqualTo(listOf("wikipedia_cs_all") to "Praha")
        assertThat(ReaderUrls.wikimediaTarget("https://en.wikipedia.org/wiki/Prague")?.first)
            .containsAtLeast("wikipedia_en_top1m", "wikipedia_en_medicine").inOrder()
        assertThat(ReaderUrls.wikimediaTarget("https://cs.wiktionary.org/wiki/voda"))
            .isEqualTo(listOf("wiktionary_cs_all") to "voda")
        assertThat(ReaderUrls.wikimediaTarget("https://en.wikivoyage.org/wiki/Prague")?.first?.first())
            .isEqualTo("wikivoyage_en_europe")
        assertThat(ReaderUrls.wikimediaTarget("https://example.com/wiki/Praha")).isNull()
        assertThat(ReaderUrls.wikimediaTarget("https://cs.wikipedia.org/w/index.php?title=Praha")).isNull()
    }
}
