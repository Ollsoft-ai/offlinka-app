package cz.offlinka.app.search

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.FakeBook
import cz.offlinka.app.fakes.FakeEntry
import cz.offlinka.app.fakes.html
import cz.offlinka.app.fakes.meta
import org.junit.Test

class GuideSectionsTest {
    private val page = """
        <html><head><title>První pomoc – rychlé karty</title></head><body>
        <h1>První pomoc</h1>
        <div id="volani" class="card red"><h3>1 · Nejdřív bezpečí, pak volejte</h3><p>155</p></div>
        <div id="krvaceni" class="card red">
          <h3>4 · Silné krvácení</h3><p>Tlačte na ránu.</p>
        </div>
        <div id="popaleniny" class="card"><h3>6 · Popálenina</h3></div>
        <h2 id="kontakty">Důležitá čísla &amp; kontakty</h2>
        </body></html>
    """.trimIndent()

    private val book = FakeBook(
        meta("offlinka_cs_prirucky", "Nouzové příručky", main = "index.html"),
        mapOf(
            "index.html" to html("<a href=\"prvni-pomoc.html\">První pomoc</a> <a href=\"https://x.cz/a.html\">web</a>", "Nouzové příručky"),
            "prvni-pomoc.html" to FakeEntry("text/html", page.toByteArray(), "První pomoc – rychlé karty"),
        ),
    )
    private val sections = GuideSections.build(book, "offlinka_cs_prirucky")

    @Test fun `headings become sections that open the page at their card`() {
        assertThat(sections.sections.map { it.title to it.path }).containsAtLeast(
            "První pomoc › Nejdřív bezpečí, pak volejte" to "prvni-pomoc.html#volani",
            "První pomoc › Silné krvácení" to "prvni-pomoc.html#krvaceni",
            "První pomoc › Popálenina" to "prvni-pomoc.html#popaleniny",
            "První pomoc › Důležitá čísla & kontakty" to "prvni-pomoc.html#kontakty",
        ).inOrder()
    }

    @Test fun `accent-free words of the heading or its card find it`() {
        val hit = sections.search("krvaceni").single()
        assertThat(hit.title).isEqualTo("První pomoc › Silné krvácení")
        assertThat(hit.path).isEqualTo("prvni-pomoc.html#krvaceni")
        assertThat(hit.bookId).isEqualTo("offlinka_cs_prirucky")
        assertThat(sections.search("popalen").map { it.path }).containsExactly("prvni-pomoc.html#popaleniny")
        assertThat(sections.search("silne krv").map { it.path }).containsExactly("prvni-pomoc.html#krvaceni")
        assertThat(sections.search("kr")).isEmpty()
        assertThat(sections.search("zlomenina")).isEmpty()
    }
}
