package cz.offlinka.app.fakes

import cz.offlinka.app.content.BookMeta
import cz.offlinka.app.content.ContentBook
import cz.offlinka.app.content.ContentException
import cz.offlinka.app.content.ContentLibrary
import cz.offlinka.app.content.ContentResource
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.content.ContentSuggestion
import cz.offlinka.app.content.LibraryHit
import java.text.Normalizer

/** One entry of a fake book: content or a redirect to another path. */
data class FakeEntry(val mime: String, val data: ByteArray, val title: String = "", val redirectTo: String? = null)

/** An in-memory book behaving like the core: redirects, ranges, normalised prefix suggestions. */
class FakeBook(
    val meta: BookMeta,
    val entries: Map<String, FakeEntry>,
    val random: String? = entries.keys.firstOrNull(),
) : ContentBook {
    @Volatile var unplugged = false
    @Volatile var closed = false
    var attachedIndex: String? = null

    override val info: BookMeta get() = meta.copy(hasIndex = attachedIndex != null)

    override fun illustration(size: Int): ByteArray? = null

    private fun check() {
        if (unplugged) throw ContentException.Io("drive gone")
    }

    private fun resolve(path: String): Pair<String, FakeEntry> {
        check()
        var p = path
        repeat(10) {
            val e = entries[p] ?: throw ContentException.NotFound(p)
            val next = e.redirectTo ?: return p to e
            p = next
        }
        throw ContentException.InvalidZim("redirect loop")
    }

    override fun get(path: String): ContentResource {
        val (p, e) = resolve(path)
        return ContentResource(p, e.mime, e.data)
    }

    override fun getRange(path: String, offset: Long, length: Int): ByteArray {
        val data = resolve(path).second.data
        if (offset >= data.size) return ByteArray(0)
        return data.copyOfRange(offset.toInt(), minOf(data.size, offset.toInt() + length))
    }

    override fun contentLength(path: String): Long = resolve(path).second.data.size.toLong()

    override fun suggest(query: String, limit: Int): List<ContentSuggestion> {
        if (unplugged) return emptyList()
        val q = norm(query)
        return entries.filter { (_, e) -> e.title.isNotEmpty() && norm(e.title).startsWith(q) }
            .entries.sortedBy { it.value.title.length }
            .take(limit)
            .map { (p, e) -> ContentSuggestion(e.title, e.redirectTo ?: p, if (e.redirectTo != null) entries[e.redirectTo]?.title else null) }
    }

    override fun randomArticle(): String? = random

    override fun attachIndexFd(fd: Int) { attachedIndex = "fd:$fd" }

    override fun attachIndexPath(path: String) { attachedIndex = path }

    override fun close() { closed = true }

    companion object {
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
    }
}

class FakeLibrary : ContentLibrary {
    val books = mutableListOf<ContentBook>()
    var closed = false

    override fun add(book: ContentBook) { books += book }

    override fun suggestAll(query: String, limit: Int): List<LibraryHit> =
        books.flatMap { b -> b.suggest(query, limit).map { LibraryHit(b.info.id, it.title, it.path) } }.take(limit)

    override fun close() { closed = true }
}

/** Opens [FakeBook]s by file name; [books] maps file names to factories. */
class FakeContentSource(private val books: Map<String, () -> FakeBook>) : ContentSource {
    val opened = mutableListOf<FakeBook>()
    val failing = mutableSetOf<String>()

    override val coreVersion = "fake-1.0"

    override fun openFd(fd: Int, name: String): ContentBook = openPath(name)

    override fun openPath(path: String): ContentBook {
        val name = path.substringAfterLast('/')
        if (name in failing) throw ContentException.InvalidZim(name)
        val book = books[name]?.invoke() ?: throw ContentException.InvalidZim(name)
        opened += book
        return book
    }

    override fun newLibrary(): ContentLibrary = FakeLibrary()
}

fun meta(id: String, title: String = id, main: String? = "Main", articles: Long = 100) = BookMeta(
    id = id, name = id, title = title, description = "", language = "ces", creator = "", publisher = "",
    date = "2026-09-01", articleCount = articles, mediaCount = 0, mainPath = main, sizeBytes = 1000, hasIndex = false,
)

fun html(body: String, title: String = "") =
    FakeEntry("text/html", "<html><head><title>$title</title></head><body>$body</body></html>".toByteArray(), title)

/** A small Czech Wikipedia-like book. */
fun fakeWiki(id: String = "wikipedia_cs_all") = FakeBook(
    meta(id, "Wikipedie", main = "Hlavní_strana"),
    mapOf(
        "Hlavní_strana" to html("<a href=\"Praha\">Praha</a>", "Hlavní strana"),
        "Praha" to html("<p>Praha je hlavní město.</p>", "Praha"),
        "Prag" to FakeEntry("text/html", ByteArray(0), "Prag", redirectTo = "Praha"),
        "Český_Krumlov" to html("<p>Město v jižních Čechách.</p>", "Český Krumlov"),
        "Brno" to html("<p>Brno</p>", "Brno"),
        "_res_/style.css" to FakeEntry("text/css", "body{}".toByteArray()),
        "I/Praha.jpg" to FakeEntry("image/jpeg", ByteArray(1000) { it.toByte() }),
    ),
    random = "Brno",
)

fun fakeGuides() = FakeBook(
    meta("offlinka_cs_prirucky", "Nouzové příručky", main = "index.html"),
    mapOf(
        "index.html" to html("<a href=\"prvni-pomoc.html\">První pomoc</a>", "Nouzové příručky"),
        "prvni-pomoc.html" to html("<h1>První pomoc</h1>", "První pomoc"),
        "vypadek-proudu.html" to html("<h1>Výpadek proudu</h1>", "Výpadek proudu"),
    ),
)

fun fakeMaps() = FakeBook(
    meta("maps_en_czech-republic", "Czech Republic", main = "index.html"),
    mapOf(
        "index.html" to html("<div id=map></div>", "Map"),
        "tiles.pmtiles" to FakeEntry("application/octet-stream", ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }),
    ),
)
