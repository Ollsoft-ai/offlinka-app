package cz.offlinka.app.search

import cz.offlinka.app.content.ContentBook
import cz.offlinka.ui.model.SearchHit
import java.text.Normalizer

/**
 * The section headings (h2/h3) of the guides book, searchable without accents: "krvaceni" finds
 * "První pomoc › Silné krvácení" and opens the page at that card. The guides book is tiny, so this is
 * built once in memory from its pages; the title index alone only knows the few page titles.
 */
class GuideSections(val bookId: String, val sections: List<Section>) {
    /** [path] is "page.html#anchor" (or just the page when the heading has no anchor). */
    data class Section(val path: String, val title: String, val words: List<String>)

    /** Sections whose words start with every word of [query] (at least 3 letters typed), in book order. */
    fun search(query: String, limit: Int = 6): List<SearchHit> {
        val q = words(query)
        if (q.isEmpty() || q.sumOf { it.length } < 3) return emptyList()
        return sections.asSequence()
            .filter { s -> q.all { w -> s.words.any { it.startsWith(w) } } }
            .take(limit)
            .map { SearchHit(bookId, it.title, it.path) }
            .toList()
    }

    companion object {
        private val href = Regex("""href="([^"#?:/]+\.html)"""")
        private val titleTag = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val h1 = Regex("""<h1[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        /** An `id="…"` attribute, or a whole h2/h3 element (with its own attributes and text). */
        private val token = Regex(
            """<h([23])([^>]*)>(.*?)</h\1\s*>|\sid="([^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val ownId = Regex("""\sid="([^"]+)"""")
        private val tags = Regex("<[^>]+>")
        private val numbering = Regex("""^\d+\s*[·.)]\s*""")

        /** Reads the main page and every page it links to; pages that fail are skipped. */
        fun build(book: ContentBook, bookId: String): GuideSections {
            val main = book.info.mainPath ?: return GuideSections(bookId, emptyList())
            val mainHtml = book.get(main).data.toString(Charsets.UTF_8)
            val pages = (listOf(main) + href.findAll(mainHtml).map { it.groupValues[1] }).distinct()
            val sections = pages.flatMap { page ->
                runCatching { parse(page, book.get(page).data.toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
            }
            return GuideSections(bookId, sections)
        }

        /** Headings of one page; the anchor is the heading's own id or the nearest id before it (the card). */
        fun parse(page: String, html: String): List<Section> {
            val pageTitle = shortTitle(
                text(titleTag.find(html)?.groupValues?.get(1) ?: h1.find(html)?.groupValues?.get(1) ?: page.removeSuffix(".html")),
            )
            val out = mutableListOf<Section>()
            var lastId: String? = null
            for (m in token.findAll(html)) {
                val id = m.groups[4]?.value
                if (id != null) {
                    lastId = id
                    continue
                }
                val anchor = ownId.find(m.groupValues[2])?.groupValues?.get(1) ?: lastId
                lastId = null
                val heading = text(m.groupValues[3]).replace(numbering, "").trim()
                if (heading.isEmpty()) continue
                out += Section(
                    path = if (anchor != null) "$page#$anchor" else page,
                    title = "$pageTitle › $heading",
                    words = words(heading + " " + (anchor ?: "").replace('-', ' ')),
                )
            }
            return out
        }

        /** "První pomoc – rychlé karty" → "První pomoc". */
        private fun shortTitle(t: String): String = t.substringBefore(" – ").substringBefore(" - ").trim()

        private fun text(html: String): String = html.replace(tags, " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: " " }
            .replace(Regex("\\s+"), " ").trim()

        /** Lower case, accents removed, split on anything that is not a letter or digit. */
        fun words(s: String): List<String> =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}
