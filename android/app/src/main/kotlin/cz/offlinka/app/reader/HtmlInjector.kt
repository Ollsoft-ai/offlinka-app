package cz.offlinka.app.reader

/**
 * Adds the reader stylesheet and its hook (design/reader: `reader.css`, `inject.js`) to pages of MediaWiki
 * books, just before `</head>` so our rules come after the page's own CSS. The stylesheet is scoped to
 * `body.mediawiki`, so maps, games and the guides keep their look anyway. On these pages the text size
 * is the stylesheet's scale (WebView text zoom stays 100), elsewhere the WebView's text zoom.
 */
object HtmlInjector {
    private val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val headOpen = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

    fun snippet(look: ReaderLook): String =
        "<link id=\"offlinka-reader-css\" rel=\"stylesheet\" href=\"/${ReaderUrls.ASSETS}/reader.css\">" +
            "<script>window.__offlinka=${look(look)};</script>" +
            "<script src=\"/${ReaderUrls.ASSETS}/inject.js\"></script>" +
            TITLE_WATCH

    /**
     * Tells the app (web message listener `offlinkaApp`) when the article's own h1 scrolls out of view,
     * so the top bar can show the article title instead of the book.
     */
    private const val TITLE_WATCH = "<script>addEventListener('DOMContentLoaded',function(){" +
        "var h=document.querySelector('h1');if(!h||!window.offlinkaApp||!window.IntersectionObserver)return;" +
        "new IntersectionObserver(function(e){offlinkaApp.postMessage(e[0].isIntersecting?'0':'1')}).observe(h)})</script>"

    fun inject(html: ByteArray, look: ReaderLook): ByteArray {
        val text = html.toString(Charsets.UTF_8)
        val tag = snippet(look)
        val close = headClose.find(text)
        val out = when {
            close != null -> text.substring(0, close.range.first) + tag + text.substring(close.range.first)
            else -> headOpen.find(text)?.let { m -> text.substring(0, m.range.last + 1) + tag + text.substring(m.range.last + 1) }
                ?: (tag + text)
        }
        return out.toByteArray(Charsets.UTF_8)
    }

    /** Puts [snippet] first in `<head>`, before the page's own scripts (the map bridge must wrap Worker first). */
    fun injectFirst(html: ByteArray, snippet: String): ByteArray {
        val text = html.toString(Charsets.UTF_8)
        val m = headOpen.find(text)
        val out = if (m != null) text.substring(0, m.range.last + 1) + snippet + text.substring(m.range.last + 1) else snippet + text
        return out.toByteArray(Charsets.UTF_8)
    }

    /** Applies theme and text scale to an open page without reloading. */
    fun lookScript(look: ReaderLook): String = "window.offlinkaReader&&window.offlinkaReader(${look(look)})"

    private fun look(look: ReaderLook): String {
        val scale = "%.2f".format(java.util.Locale.ROOT, look.scale.coerceIn(0.8f, 2f))
        return "{theme:'${if (look.dark) "dark" else "light"}',scale:$scale}"
    }
}
