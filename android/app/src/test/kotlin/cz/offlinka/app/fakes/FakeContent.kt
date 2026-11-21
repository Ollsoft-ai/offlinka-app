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
    /** closeFile() was called: like the core, reads now fail with Io and suggest returns nothing. */
    @Volatile var fileClosed = false
    /** The native object was destroyed (close()). */
    @Volatile var destroyed = false
    /** Retired by the shelf: its file is released (or the object destroyed). */
    val closed: Boolean get() = fileClosed || destroyed
    var attachedIndex: String? = null

    override val info: BookMeta get() = meta.copy(hasIndex = attachedIndex != null)

    override fun illustration(size: Int): ByteArray? = null

    private fun check() {
        if (destroyed) throw IllegalStateException("Book object has already been destroyed")
        if (unplugged || fileClosed) throw ContentException.Io("drive gone")
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

    @Volatile var gets = 0
    /** Makes every read throw this (a closed UniFFI object, a core panic …). */
    @Volatile var failWith: RuntimeException? = null

    override fun get(path: String): ContentResource {
        failWith?.let { throw it }
        gets++
        val (p, e) = resolve(path)
        return ContentResource(p, e.mime, e.data)
    }

    override fun getRange(path: String, offset: Long, length: Int): ByteArray {
        failWith?.let { throw it }
        val data = resolve(path).second.data
        if (offset >= data.size) return ByteArray(0)
        return data.copyOfRange(offset.toInt(), minOf(data.size, offset.toInt() + length))
    }

    override fun contentLength(path: String): Long {
        failWith?.let { throw it }
        return resolve(path).second.data.size.toLong()
    }

    override fun suggest(query: String, limit: Int): List<ContentSuggestion> {
        if (unplugged || fileClosed) return emptyList()
        val q = norm(query)
        return entries.filter { (_, e) -> e.title.isNotEmpty() && norm(e.title).startsWith(q) }
            .entries.sortedBy { it.value.title.length }
            .take(limit)
            .map { (p, e) -> ContentSuggestion(e.title, e.redirectTo ?: p, if (e.redirectTo != null) entries[e.redirectTo]?.title else null) }
    }

    override fun randomArticle(): String? = random

    override fun attachIndexFd(fd: Int) { attachedIndex = "fd:$fd" }

    override fun attachIndexPath(path: String) {
        check()
        attachedIndex = path
    }

    var preloaded = false

    override fun preloadIndex() { preloaded = true }

    /** Called when the book is retired (tests check what the shelf published by then). */
    var onClose: (() -> Unit)? = null

    /** closeFile() waits on this gate (a read that hangs on a pulled drive) and records its thread. */
    @Volatile var closeFileGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var closeFileThread: String? = null

    override fun closeFile() {
        closeFileThread = Thread.currentThread().name
        closeFileGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
        onClose?.invoke()
        fileClosed = true
    }

    override fun close() {
        if (!closed) onClose?.invoke()
        destroyed = true
    }

    companion object {
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
    }
}

class FakeLibrary : ContentLibrary {
    val books: MutableList<ContentBook> = java.util.concurrent.CopyOnWriteArrayList()
    var closed = false

    override fun add(book: ContentBook) { books += book }

    override fun suggestAll(query: String, limit: Int): List<LibraryHit> =
        books.toList().flatMap { b -> b.suggest(query, limit).map { LibraryHit(b.info.id, it.title, it.path) } }.take(limit)

    override fun close() { closed = true }
}

/** Opens [FakeBook]s by file name; [books] maps file names to factories. */
class FakeContentSource(private val books: Map<String, () -> FakeBook>) : ContentSource {
    // Copy-on-write: tests iterate these (any/filter/count) while IO threads append.
    val opened: MutableList<FakeBook> = java.util.concurrent.CopyOnWriteArrayList()
    val openedNames: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val failing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Files whose open is waiting at its gate right now (or was). */
    val waitingAtGate: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Opening these files waits until the gate is opened (to observe the shelf mid-way). */
    val gated: MutableMap<String, java.util.concurrent.CountDownLatch> = java.util.concurrent.ConcurrentHashMap()
    @Volatile var trims = 0
    @Volatile var budget = -1L
    val built: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    /** Index builds wait on this gate when set. */
    @Volatile var buildGate: java.util.concurrent.CountDownLatch? = null
    /** Building the index of these book ids throws this. */
    val buildErrors: MutableMap<String, Exception> = java.util.concurrent.ConcurrentHashMap()
    /** Called with the file name before each open (to pull the drive mid-load). */
    @Volatile var onOpen: ((String) -> Unit)? = null

    override val coreVersion = "fake-1.0"

    override fun openFd(fd: Int, name: String): ContentBook = openPath(name)

    override fun openPath(path: String): ContentBook {
        val name = path.substringAfterLast('/')
        onOpen?.invoke(name)
        gated[name]?.let { gate ->
            waitingAtGate += name
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        if (name in failing) throw ContentException.InvalidZim(name)
        val book = books[name]?.invoke() ?: throw ContentException.InvalidZim(name)
        opened += book
        openedNames += name
        return book
    }

    override fun newLibrary(): ContentLibrary = FakeLibrary()

    @Volatile var trimThread: String? = null

    override fun trimMemory() {
        trimThread = Thread.currentThread().name
        trims++
    }

    override fun setCacheBudget(bytes: Long) { budget = bytes }

    override fun buildIndex(book: ContentBook, outPath: String, onProgress: (Double) -> Unit, isCancelled: () -> Boolean) {
        buildErrors[book.info.id]?.let { throw it }
        for (i in 1..10) {
            buildGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (isCancelled()) throw ContentException.Cancelled("cancelled")
            onProgress(i / 10.0)
        }
        java.io.File(outPath).writeText("index of ${book.info.id}")
        built += book.info.id
    }
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

/** A 5 MB "video" without a file extension (MP4 signature). */
fun mp4Bytes() = ByteArray(5 * 1024 * 1024) { i -> if (i in 4..7) "ftyp"[i - 4].code.toByte() else (i % 253).toByte() }

fun fakeMaps() = FakeBook(
    meta("maps_en_czech-republic", "Czech Republic", main = "index.html"),
    mapOf(
        "index.html" to FakeEntry("text/html", "<html><head><title>Map</title><script type=module src=./a.js></script></head><body></body></html>".toByteArray(), "Map"),
        "media/clip" to FakeEntry("application/octet-stream", mp4Bytes()),
        "tiles.pmtiles" to FakeEntry("application/octet-stream", ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }),
    ),
)
