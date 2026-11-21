package cz.offlinka.app.content

import java.io.IOException

/**
 * The app's view of the Rust core (SPEC "Core API"). [CoreContentSource] maps it 1:1 onto the UniFFI
 * bindings; tests use fakes. Every call may block on drive I/O: call from a background thread.
 */
interface ContentSource {
    val coreVersion: String

    /** Opens a ZIM from a detached file descriptor. The book owns the fd and closes it. */
    fun openFd(fd: Int, name: String): ContentBook

    fun openPath(path: String): ContentBook

    fun newLibrary(): ContentLibrary

    /** Drops cached clusters and unloads in-memory indexes of all books (Android onTrimMemory). */
    fun trimMemory()

    /** Budget of the decompressed-cluster cache shared by all open books. */
    fun setCacheBudget(bytes: Long)

    /**
     * Builds [book]'s search index into [outPath] (app storage). Blocking; [onProgress] gets 0..1,
     * [isCancelled] is polled with it. Throws [ContentException.Cancelled] when cancelled; leaves no
     * file behind on failure. Does not attach the index.
     */
    fun buildIndex(book: ContentBook, outPath: String, onProgress: (Double) -> Unit, isCancelled: () -> Boolean)
}

interface ContentBook : AutoCloseable {
    val info: BookMeta

    fun illustration(size: Int): ByteArray?

    /** Follows redirects; [ContentResource.path] is the final path. */
    fun get(path: String): ContentResource

    fun getRange(path: String, offset: Long, length: Int): ByteArray

    fun contentLength(path: String): Long

    fun suggest(query: String, limit: Int): List<ContentSuggestion>

    fun randomArticle(): String?

    /** Attaches a `.offidx` search index; the book owns the fd. */
    fun attachIndexFd(fd: Int)

    fun attachIndexPath(path: String)

    /** Loads an attached index's in-memory part now (call off the UI thread right after attaching). */
    fun preloadIndex()

    /** Releases the ZIM and index files at once (unplug): later reads fail with [ContentException.Io]. */
    fun closeFile()
}

interface ContentLibrary : AutoCloseable {
    fun add(book: ContentBook)

    /** Merged and ranked across books (Czech Wikipedia first). [LibraryHit.bookId] is [BookMeta.id]. */
    fun suggestAll(query: String, limit: Int): List<LibraryHit>
}

data class BookMeta(
    val id: String,
    val name: String,
    val title: String,
    val description: String,
    val language: String,
    val creator: String,
    val publisher: String,
    val date: String,
    val articleCount: Long,
    val mediaCount: Long,
    val mainPath: String?,
    val sizeBytes: Long,
    val hasIndex: Boolean,
)

class ContentResource(val path: String, val mime: String, val data: ByteArray)

data class ContentSuggestion(val title: String, val path: String, val snippet: String?)

data class LibraryHit(val bookId: String, val title: String, val path: String)

/** Mirrors the core's `CoreError`. Every failure of the drive surfaces as [Io]. */
sealed class ContentException(message: String?) : IOException(message) {
    class Io(message: String?) : ContentException(message)
    class InvalidZim(message: String?) : ContentException(message)
    class NotFound(message: String?) : ContentException(message)
    class Unsupported(message: String?) : ContentException(message)
    class Cancelled(message: String?) : ContentException(message)
}
