package cz.offlinka.app.reader

import cz.offlinka.app.content.ContentBook
import cz.offlinka.app.content.ContentException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** A response for the WebView, independent of Android so it can be tested on the JVM. */
class Served(
    val status: Int,
    val reason: String,
    val mime: String,
    val charset: String?,
    val headers: Map<String, String>,
    val body: InputStream,
) {
    /** Reads the whole body (tests and small responses only). */
    fun bytes(): ByteArray = body.use { it.readBytes() }
}

/** What the server needs to know about a book. */
interface ServedBook {
    val content: ContentBook
    val mainPath: String?
    /** MediaWiki output: gets the reader stylesheet. */
    val isWiki: Boolean
    /** A MapLibre map viewer: gets the worker fetch bridge. */
    val isMap: Boolean get() = false
}

/** Reader assets bundled with the app (`assets/reader/`). */
fun interface AssetSource {
    fun open(name: String): InputStream?
}

/** How injected pages should look right now: theme and text scale (1 = 17 px body text). */
data class ReaderLook(val dark: Boolean, val scale: Float = 1f)

/**
 * Answers every WebView request for `https://offlinka.local/…` from the core: correct MIME, `Range`
 * support for media, a calm 404 page for misses. Requests to any other host get an empty 403 so nothing
 * ever leaves the phone.
 */
class ContentServer(
    private val books: () -> Map<String, ServedBook>,
    private val assets: AssetSource,
    private val look: () -> ReaderLook,
    private val notFoundPage: (dark: Boolean) -> String,
    private val onDriveError: (IOException) -> Unit,
    /** False while the drive's books are not open (start, unplugged): unknown books are then "come back later". */
    private val driveReady: () -> Boolean = { true },
) {
    private val redirects = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 64
        },
    )

    /** [url] was answered with a redirect stub (not a page worth a history row). */
    fun wasRedirect(url: String): Boolean = redirects.containsKey(url)

    fun serve(url: String, method: String, headers: Map<String, String>, mainFrame: Boolean): Served {
        if (!ReaderUrls.isOurs(url)) return blocked()
        if (method != "GET" && method != "HEAD") return blocked()
        val target = ReaderUrls.parse(url) ?: return notFound(mainFrame)
        if (target.bookId == ReaderUrls.ASSETS) return asset(target.path)
        // Any failure below (a book closed under us, a panic in the core) must answer, never crash the WebView thread.
        return try {
            val book = books()[target.bookId] ?: return if (driveReady()) notFound(mainFrame) else error503()
            if (target.path.isEmpty()) {
                val main = book.mainPath ?: return notFound(mainFrame)
                return replaceWith(url, ReaderUrls.article(target.bookId, main))
            }
            val range = headers.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
            if (range != null) {
                rangeResponse(book, target, range) ?: full(book, target, mainFrame, url)
            } else {
                full(book, target, mainFrame, url)
            }
        } catch (e: ContentException.NotFound) {
            notFound(mainFrame)
        } catch (e: ContentException.Io) {
            onDriveError(e)
            error503()
        } catch (e: ContentException) {
            notFound(mainFrame)
        } catch (e: IOException) {
            onDriveError(e)
            error503()
        } catch (e: Exception) {
            // IllegalStateException of a closed UniFFI object, InternalException of a core panic, …
            error503()
        }
    }

    private fun full(book: ServedBook, target: ReaderUrls.Target, mainFrame: Boolean, url: String): Served {
        // Big entries (media, map tiles archives; also without a file extension) are streamed in chunks
        // instead of being decompressed into one array.
        val length = book.content.contentLength(target.path)
        if (length > STREAM_THRESHOLD) {
            val mime = mimeOf(target.path) ?: sniff(book.content.getRange(target.path, 0, SNIFF_BYTES)) ?: "application/octet-stream"
            return stream(book, target, length, mime)
        }
        val res = book.content.get(target.path)
        if (mainFrame && res.path != target.path && res.path.isNotEmpty()) {
            // A redirect: show the target under its own URL so relative links and history are right.
            return replaceWith(url, ReaderUrls.article(target.bookId, res.path))
        }
        val (mime, charset) = splitMime(res.mime)
        var data = res.data
        if (mime == "text/html" && book.isWiki) {
            data = HtmlInjector.inject(data, look())
        } else if (mime == "text/html" && book.isMap && mainFrame) {
            data = HtmlInjector.injectFirst(data, MAP_BRIDGE)
        }
        return Served(
            200, "OK", mime, charset ?: if (mime.startsWith("text/") || mime.endsWith("javascript")) "utf-8" else null,
            baseHeaders(mime) + ("Content-Length" to data.size.toString()),
            ByteArrayInputStream(data),
        )
    }

    private fun stream(book: ServedBook, target: ReaderUrls.Target, length: Long, mime: String): Served =
        Served(
            200, "OK", mime, null,
            baseHeaders(mime) + mapOf("Content-Length" to length.toString(), "Accept-Ranges" to "bytes"),
            RangeInputStream(book.content, target.path, 0, length),
        )

    private fun rangeResponse(book: ServedBook, target: ReaderUrls.Target, header: String): Served? {
        val total = book.content.contentLength(target.path)
        val r = parseRange(header, total) ?: return Served(
            416, "Range Not Satisfiable", "text/plain", null,
            mapOf("Content-Range" to "bytes */$total"), ByteArrayInputStream(ByteArray(0)),
        )
        val (start, end) = r
        val len = end - start + 1
        // MIME from the extension, else from the first bytes: never decompress a whole entry for a range.
        val mime = mimeOf(target.path) ?: sniff(book.content.getRange(target.path, 0, SNIFF_BYTES)) ?: "application/octet-stream"
        val body: InputStream = if (len <= MAX_RANGE_IN_MEMORY) {
            ByteArrayInputStream(book.content.getRange(target.path, start, len.toInt()))
        } else {
            RangeInputStream(book.content, target.path, start, len)
        }
        return Served(
            206, "Partial Content", mime, null,
            baseHeaders(mime) + mapOf(
                "Content-Range" to "bytes $start-$end/$total",
                "Content-Length" to len.toString(),
                "Accept-Ranges" to "bytes",
            ),
            body,
        )
    }

    private fun asset(name: String): Served {
        if (name.contains("..")) return notFound(false)
        val stream = assets.open(name) ?: return notFound(false)
        val mime = mimeOf(name) ?: "application/octet-stream"
        return Served(200, "OK", mime, if (mime.startsWith("text/")) "utf-8" else null, baseHeaders(mime), stream)
    }

    /** Replaces the current page ([from]) with [url] without adding a history entry. */
    private fun replaceWith(from: String, url: String): Served {
        redirects[from] = true
        val js = url.replace("\\", "\\\\").replace("'", "\\'")
        val html = "<!doctype html><meta charset=utf-8><script>location.replace('$js')</script>"
        return Served(200, "OK", "text/html", "utf-8", baseHeaders("text/html"), ByteArrayInputStream(html.toByteArray()))
    }

    private fun notFound(mainFrame: Boolean): Served {
        val body = if (mainFrame) notFoundPage(look().dark).toByteArray() else ByteArray(0)
        return Served(404, "Not Found", "text/html", "utf-8", baseHeaders("text/html"), ByteArrayInputStream(body))
    }

    private fun error503() =
        Served(503, "Service Unavailable", "text/plain", "utf-8", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun blocked() = Served(403, "Forbidden", "text/plain", "utf-8", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun baseHeaders(mime: String): Map<String, String> = buildMap {
        put("Access-Control-Allow-Origin", "*")
        put("Cache-Control", if (mime == "text/html") "no-cache" else "max-age=86400")
        put("X-Content-Type-Options", "nosniff")
    }

    companion object {
        const val STREAM_THRESHOLD = 4L * 1024 * 1024
        const val MAX_RANGE_IN_MEMORY = 2L * 1024 * 1024
        private const val SNIFF_BYTES = 64

        /** Script that lets map viewers' web workers fetch tiles through the page (assets/reader/map-bridge.js). */
        val MAP_BRIDGE = "<script src=\"/${ReaderUrls.ASSETS}/map-bridge.js\"></script>"

        /** MIME type from an entry's first bytes, for entries without a file extension. */
        fun sniff(head: ByteArray): String? {
            fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it] == b[it].toByte() }
            fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
            return when {
                ascii(4, "ftyp") -> if (ascii(8, "M4A")) "audio/mp4" else "video/mp4"
                at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
                ascii(0, "OggS") -> "audio/ogg"
                ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) -> "audio/mpeg"
                ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
                ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
                ascii(0, "%PDF") -> "application/pdf"
                at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
                at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
                ascii(0, "GIF8") -> "image/gif"
                ascii(0, "PMTiles") -> "application/vnd.pmtiles"
                at(0, 0x00, 0x61, 0x73, 0x6D) -> "application/wasm"
                else -> null
            }
        }

        /** `bytes=a-b`, `bytes=a-`, `bytes=-n` → inclusive [start, end] clamped to [total]; null if unsatisfiable. */
        fun parseRange(header: String, total: Long): Pair<Long, Long>? {
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0 || total <= 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            val (start, end) = when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) return null
                    (total - n).coerceAtLeast(0) to total - 1
                }
                else -> {
                    val s = a.toLongOrNull() ?: return null
                    val e = if (b.isEmpty()) total - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(total - 1)
                    s to e
                }
            }
            if (start < 0 || start >= total || end < start) return null
            return start to end
        }

        fun splitMime(raw: String): Pair<String, String?> {
            val parts = raw.split(';')
            val mime = parts[0].trim().lowercase().ifEmpty { "application/octet-stream" }
            val charset = parts.drop(1).map { it.trim() }.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"', ' ')
            return mime to charset
        }

        fun isMedia(mime: String) = mime.startsWith("video/") || mime.startsWith("audio/") || mime == "application/vnd.pmtiles"

        /** MIME from the file extension, for range/stream responses before the core is asked. */
        fun mimeOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "ogv" -> "video/ogg"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "pbf", "mvt" -> "application/x-protobuf"
            "pmtiles" -> "application/vnd.pmtiles"
            "wasm" -> "application/wasm"
            else -> null
        }
    }
}

/** Streams [length] bytes of a big entry from [offset] in 512 KiB range reads. */
class RangeInputStream(
    private val book: ContentBook,
    private val path: String,
    offset: Long,
    length: Long,
) : InputStream() {
    private var pos = offset
    private val end = offset + length
    private var buf = ByteArray(0)
    private var bufPos = 0

    private fun fill(): Boolean {
        if (bufPos < buf.size) return true
        if (pos >= end) return false
        val n = minOf(CHUNK.toLong(), end - pos).toInt()
        buf = try {
            book.getRange(path, pos, n)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // A closed book or a core panic ends the stream like a read error, never as a crash.
            throw IOException(e)
        }
        if (buf.isEmpty()) return false
        pos += buf.size
        bufPos = 0
        return true
    }

    override fun read(): Int = if (fill()) buf[bufPos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, buf.size - bufPos)
        System.arraycopy(buf, bufPos, b, off, n)
        bufPos += n
        return n
    }

    override fun available(): Int = buf.size - bufPos

    private companion object {
        const val CHUNK = 512 * 1024
    }
}
