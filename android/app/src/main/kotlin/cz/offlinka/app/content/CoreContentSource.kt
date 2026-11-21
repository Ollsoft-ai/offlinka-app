package cz.offlinka.app.content

import cz.offlinka.core.Book
import cz.offlinka.core.BookLibrary
import cz.offlinka.core.CoreException
import cz.offlinka.core.IndexBuildListener
import cz.offlinka.core.buildIndex as nativeBuildIndex
import cz.offlinka.core.coreVersion as nativeCoreVersion
import cz.offlinka.core.setClusterCacheBudget
import cz.offlinka.core.trimMemory as nativeTrimMemory

/** [ContentSource] on the Rust core through its UniFFI bindings (package `cz.offlinka.core`). */
class CoreContentSource : ContentSource {
    override val coreVersion: String by lazy { nativeCoreVersion() }

    override fun openFd(fd: Int, name: String): ContentBook = CoreBook(call { Book.openFd(fd, name) })

    override fun openPath(path: String): ContentBook = CoreBook(call { Book.openPath(path) })

    override fun newLibrary(): ContentLibrary = CoreLibrary(BookLibrary())

    override fun trimMemory() = nativeTrimMemory()

    override fun setCacheBudget(bytes: Long) = setClusterCacheBudget(bytes.coerceAtLeast(0).toULong())

    override fun buildIndex(book: ContentBook, outPath: String, onProgress: (Double) -> Unit, isCancelled: () -> Boolean) = call {
        nativeBuildIndex(
            (book as CoreBook).book,
            outPath,
            object : IndexBuildListener {
                override fun onProgress(fraction: Double) = onProgress(fraction)
                override fun isCancelled(): Boolean = isCancelled()
            },
        )
    }
}

private class CoreBook(val book: Book) : ContentBook {
    override val info: BookMeta get() = book.info().let { i ->
        BookMeta(
            id = i.id,
            name = i.name,
            title = i.title,
            description = i.description,
            language = i.language,
            creator = i.creator,
            publisher = i.publisher,
            date = i.date,
            articleCount = i.articleCount.toLong(),
            mediaCount = i.mediaCount.toLong(),
            mainPath = i.mainPath,
            sizeBytes = i.sizeBytes.toLong(),
            hasIndex = i.hasIndex,
        )
    }

    override fun illustration(size: Int): ByteArray? = book.illustration(size.toUInt())

    override fun get(path: String): ContentResource = call {
        val r = book.get(path)
        ContentResource(r.path, r.mime, r.data)
    }

    override fun getRange(path: String, offset: Long, length: Int): ByteArray =
        call { book.getRange(path, offset.toULong(), length.toUInt()) }

    override fun contentLength(path: String): Long = call { book.contentLength(path).toLong() }

    override fun suggest(query: String, limit: Int): List<ContentSuggestion> =
        book.suggest(query, limit.toUInt()).map { ContentSuggestion(it.title, it.path, it.snippet) }

    override fun randomArticle(): String? = book.randomArticle()

    override fun attachIndexFd(fd: Int) = call { book.attachIndexFd(fd) }

    override fun attachIndexPath(path: String) = call { book.attachIndexPath(path) }

    override fun preloadIndex() = call { book.preloadIndex() }

    override fun closeFile() = call { book.closeFile() }

    override fun close() = book.close()
}

private class CoreLibrary(val library: BookLibrary) : ContentLibrary {
    override fun add(book: ContentBook) {
        library.add((book as CoreBook).book)
    }

    override fun suggestAll(query: String, limit: Int): List<LibraryHit> =
        library.suggestAll(query, limit.toUInt()).map { LibraryHit(it.bookId, it.title, it.path) }

    override fun close() = library.close()
}

/** Maps the core's errors onto [ContentException]. */
private inline fun <T> call(block: () -> T): T = try {
    block()
} catch (e: CoreException.Io) {
    throw ContentException.Io(e.message)
} catch (e: CoreException.NotFound) {
    throw ContentException.NotFound(e.message)
} catch (e: CoreException.InvalidZim) {
    throw ContentException.InvalidZim(e.message)
} catch (e: CoreException.Unsupported) {
    throw ContentException.Unsupported(e.message)
} catch (e: CoreException.Cancelled) {
    throw ContentException.Cancelled(e.message)
}
