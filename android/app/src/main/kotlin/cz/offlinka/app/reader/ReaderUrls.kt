package cz.offlinka.app.reader

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Every page the reader shows lives at `https://offlinka.local/<bookId>/<path>`; our own reader assets at
 * `https://offlinka.local/_offlinka/<file>`.
 */
object ReaderUrls {
    const val HOST = "offlinka.local"
    const val ORIGIN = "https://$HOST"
    const val ASSETS = "_offlinka"

    data class Target(val bookId: String, val path: String)

    fun article(bookId: String, path: String): String = "$ORIGIN/$bookId/${encodePath(path)}"

    /** Like [article] for "page.html#anchor" (guide sections): the anchor stays a fragment. */
    fun articleAt(bookId: String, pathWithAnchor: String): String {
        val hash = pathWithAnchor.indexOf('#')
        if (hash < 0) return article(bookId, pathWithAnchor)
        return article(bookId, pathWithAnchor.substring(0, hash)) + "#" + encodePath(pathWithAnchor.substring(hash + 1))
    }

    /**
     * Scheme, host and raw path of a URL, parsed by hand: java.net.URI rejects characters Chromium leaves
     * raw in paths (`|`, `^`, `[`, `]`), which made such pages of our own look external.
     */
    private class Parts(val scheme: String, val host: String, val rawPath: String)

    private fun split(url: String): Parts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end).substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val after = rest.substring(end)
        val pathEnd = after.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) after.length else it }
        return Parts(scheme, host.lowercase(), after.substring(0, pathEnd))
    }

    fun asset(name: String): String = "$ORIGIN/$ASSETS/$name"

    fun isOurs(url: String): Boolean = split(url)?.let { it.scheme == "https" && it.host == HOST } ?: false

    /** Book and ZIM path of one of our URLs (query and fragment dropped, percent-decoding applied). */
    fun parse(url: String): Target? {
        val u = split(url) ?: return null
        if (u.scheme != "https" || u.host != HOST) return null
        val trimmed = u.rawPath.removePrefix("/")
        val slash = trimmed.indexOf('/')
        val bookId = decode(if (slash < 0) trimmed else trimmed.substring(0, slash))
        if (bookId.isEmpty()) return null
        val path = if (slash < 0) "" else decode(trimmed.substring(slash + 1))
        return Target(bookId, path)
    }

    fun host(url: String): String? = split(url)?.host?.ifEmpty { null }

    /** Percent-decodes a path; unlike form decoding, `+` stays `+`. */
    fun decode(s: String): String =
        if ('%' !in s) s else runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** Encodes each segment of a ZIM path for a URL; `/` stays a separator. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        buildString {
            for (b in segment.toByteArray(StandardCharsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~!$&'()*+,;=:@") {
                    append(ch)
                } else {
                    append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
                }
            }
        }
    }

    /**
     * An internet link that points at a book we have offline: Wikipedia and its sister projects
     * (`https://cs.wikipedia.org/wiki/Praha`, mobile `cs.m.wikipedia.org`), mapped to candidate book ids in
     * preference order + the article path. Null for anything else.
     */
    fun wikimediaTarget(url: String): Pair<List<String>, String>? {
        val u = split(url) ?: return null
        val host = u.host.ifEmpty { return null }
        val parts = host.split('.')
        if (parts.size < 3 || parts.last() != "org") return null
        val lang = parts.first()
        val project = parts[parts.size - 2]
        val raw = u.rawPath
        if (!raw.startsWith("/wiki/")) return null
        val title = decode(raw.removePrefix("/wiki/")).replace(' ', '_')
        if (title.isEmpty()) return null
        val books = when (project) {
            "wikipedia" -> when (lang) {
                "en" -> listOf("wikipedia_en_top1m", "wikipedia_en_medicine", "wikipedia_en_all")
                else -> listOf("wikipedia_${lang}_all")
            }
            "wiktionary", "wikiquote", "wikisource", "wikibooks", "wikiversity" -> listOf("${project}_${lang}_all")
            "wikivoyage" -> listOf("wikivoyage_${lang}_europe", "wikivoyage_${lang}_all")
            else -> return null
        }
        return books to title
    }
}
