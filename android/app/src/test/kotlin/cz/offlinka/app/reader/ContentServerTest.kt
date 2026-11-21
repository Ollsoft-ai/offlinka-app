package cz.offlinka.app.reader

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.content.ContentBook
import cz.offlinka.app.fakes.fakeGuides
import cz.offlinka.app.fakes.fakeMaps
import cz.offlinka.app.fakes.fakeWiki
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ContentServerTest {
    private class B(
        override val content: ContentBook,
        override val isWiki: Boolean,
        override val isMap: Boolean = false,
        override val language: String = "",
    ) : ServedBook {
        override val mainPath: String? get() = content.info.mainPath
    }

    private val wiki = fakeWiki()
    private val maps = fakeMaps()
    private val guides = fakeGuides()
    private val errors = mutableListOf<IOException>()
    private var dark = false
    private val server = ContentServer(
        books = {
            mapOf(
                "wikipedia_cs_all" to B(wiki, true, language = "cs"),
                "maps_en_czech-republic" to B(maps, false, isMap = true),
                "offlinka_cs_prirucky" to B(guides, false),
            )
        },
        assets = AssetSource { name -> if (name == "reader.css") ByteArrayInputStream("css".toByteArray()) else null },
        look = { ReaderLook(dark) },
        notFoundPage = { d -> "<p>missing ${if (d) "dark" else "light"}</p>" },
        onDriveError = { errors += it },
        pending = { id -> id in pending },
    )
    private val pending = mutableSetOf<String>()

    private fun get(url: String, mainFrame: Boolean = true, headers: Map<String, String> = emptyMap()) =
        server.serve(url, "GET", headers, mainFrame)

    @Test fun `wiki html gets the reader stylesheet and theme`() {
        dark = true
        val r = get(ReaderUrls.article("wikipedia_cs_all", "Praha"))
        assertThat(r.status).isEqualTo(200)
        assertThat(r.mime).isEqualTo("text/html")
        assertThat(r.charset).isEqualTo("utf-8")
        val html = r.bytes().decodeToString()
        assertThat(html).contains("/_offlinka/reader.css")
        assertThat(html).contains("window.__offlinka={theme:'dark',scale:1.00,lang:'cs'}")
        assertThat(html).contains("/_offlinka/inject.js")
        assertThat(html.indexOf("reader.css")).isLessThan(html.indexOf("</head>"))
        assertThat(html.indexOf("reader.css")).isGreaterThan(html.indexOf("<title>"))
        assertThat(html).contains("Praha je hlavní město.")
    }

    @Test fun `maps and guides keep their own look`() {
        val r = get(ReaderUrls.article("offlinka_cs_prirucky", "prvni-pomoc.html")).bytes().decodeToString()
        assertThat(r).doesNotContain("reader.css")
        assertThat(r).contains("První pomoc")
    }

    @Test fun `css and images keep their mime`() {
        assertThat(get(ReaderUrls.article("wikipedia_cs_all", "_res_/style.css"), false).mime).isEqualTo("text/css")
        val img = get(ReaderUrls.article("wikipedia_cs_all", "I/Praha.jpg"), false)
        assertThat(img.mime).isEqualTo("image/jpeg")
        assertThat(img.bytes()).hasLength(1000)
    }

    @Test fun `a redirect in the main frame replaces the url`() {
        val r = get(ReaderUrls.article("wikipedia_cs_all", "Prag"))
        assertThat(r.status).isEqualTo(200)
        assertThat(r.bytes().decodeToString()).contains("location.replace('https://offlinka.local/wikipedia_cs_all/Praha')")
    }

    @Test fun `a redirected subresource is served directly`() {
        val r = get(ReaderUrls.article("wikipedia_cs_all", "Prag"), mainFrame = false)
        assertThat(r.bytes().decodeToString()).contains("Praha je hlavní město.")
    }

    @Test fun `book root goes to the main page`() {
        val r = get("https://offlinka.local/offlinka_cs_prirucky/")
        assertThat(r.bytes().decodeToString()).contains("offlinka_cs_prirucky/index.html")
    }

    @Test fun `missing article is a calm 404 page in the main frame and empty otherwise`() {
        val main = get(ReaderUrls.article("wikipedia_cs_all", "Neexistuje"))
        assertThat(main.status).isEqualTo(404)
        assertThat(main.bytes().decodeToString()).isEqualTo("<p>missing light</p>")
        val sub = get(ReaderUrls.article("wikipedia_cs_all", "x.png"), mainFrame = false)
        assertThat(sub.status).isEqualTo(404)
        assertThat(sub.bytes()).isEmpty()
        assertThat(get(ReaderUrls.article("no_such_book", "Praha")).status).isEqualTo(404)
    }

    @Test fun `everything outside offlinka_local is blocked`() {
        for (url in listOf("https://cs.wikipedia.org/wiki/Praha", "http://offlinka.local/x", "https://tile.openstreetmap.org/1/1/1.png")) {
            val r = get(url, mainFrame = false)
            assertThat(r.status).isEqualTo(403)
            assertThat(r.bytes()).isEmpty()
        }
        assertThat(server.serve(ReaderUrls.article("wikipedia_cs_all", "Praha"), "POST", emptyMap(), false).status).isEqualTo(403)
    }

    @Test fun `range requests answer 206 with the slice`() {
        val url = ReaderUrls.article("wikipedia_cs_all", "I/Praha.jpg")
        val r = get(url, false, mapOf("Range" to "bytes=10-19"))
        assertThat(r.status).isEqualTo(206)
        assertThat(r.headers["Content-Range"]).isEqualTo("bytes 10-19/1000")
        assertThat(r.headers["Content-Length"]).isEqualTo("10")
        assertThat(r.bytes().toList()).isEqualTo((10..19).map { it.toByte() })
        val open = get(url, false, mapOf("range" to "bytes=990-"))
        assertThat(open.headers["Content-Range"]).isEqualTo("bytes 990-999/1000")
        val suffix = get(url, false, mapOf("Range" to "bytes=-5"))
        assertThat(suffix.headers["Content-Range"]).isEqualTo("bytes 995-999/1000")
        assertThat(get(url, false, mapOf("Range" to "bytes=5000-")).status).isEqualTo(416)
    }

    @Test fun `big media streams in chunks with the full length`() {
        val r = get(ReaderUrls.article("maps_en_czech-republic", "tiles.pmtiles"), false)
        assertThat(r.status).isEqualTo(200)
        assertThat(r.headers["Content-Length"]).isEqualTo((6 * 1024 * 1024).toString())
        assertThat(r.headers["Accept-Ranges"]).isEqualTo("bytes")
        val bytes = r.bytes()
        assertThat(bytes.size).isEqualTo(6 * 1024 * 1024)
        assertThat(bytes[5_000_000]).isEqualTo((5_000_000 % 251).toByte())
    }

    @Test fun `big range is streamed too`() {
        val r = get(ReaderUrls.article("maps_en_czech-republic", "tiles.pmtiles"), false, mapOf("Range" to "bytes=1000-4000999"))
        assertThat(r.status).isEqualTo(206)
        assertThat(r.bytes().size).isEqualTo(4_000_000)
    }

    @Test fun `unplugged drive is reported and answered softly`() {
        wiki.unplugged = true
        val r = get(ReaderUrls.article("wikipedia_cs_all", "Praha"))
        assertThat(r.status).isEqualTo(503)
        assertThat(errors).hasSize(1)
    }

    @Test fun `reader assets are served and cannot escape their folder`() {
        val css = get("https://offlinka.local/_offlinka/reader.css", false)
        assertThat(css.status).isEqualTo(200)
        assertThat(css.mime).isEqualTo("text/css")
        assertThat(get("https://offlinka.local/_offlinka/../secret", false).status).isEqualTo(404)
        assertThat(get("https://offlinka.local/_offlinka/nope.js", false).status).isEqualTo(404)
    }

    @Test fun `a closed book or a core panic answers 503 instead of crashing the WebView thread`() {
        wiki.failWith = IllegalStateException("Book object has already been destroyed")
        assertThat(get(ReaderUrls.article("wikipedia_cs_all", "Praha")).status).isEqualTo(503)
        wiki.failWith = RuntimeException("InternalException: panicked at src/zim.rs")
        assertThat(get(ReaderUrls.article("wikipedia_cs_all", "I/Praha.jpg"), false, mapOf("Range" to "bytes=0-9")).status).isEqualTo(503)
    }

    @Test fun `a stream whose book closes mid-way ends with an IOException`() {
        val r = get(ReaderUrls.article("maps_en_czech-republic", "tiles.pmtiles"), false)
        val buf = ByteArray(1024)
        r.body.read(buf)
        maps.failWith = IllegalStateException("closed")
        var thrown: Throwable? = null
        try {
            while (r.body.read(buf) >= 0) { /* drain */ }
        } catch (e: Throwable) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(java.io.IOException::class.java)
    }

    @Test fun `the server remembers which page was answered 503 until it is served`() {
        val url = ReaderUrls.article("wikivoyage_en_europe", "Praha")
        pending += "wikivoyage_en_europe"
        get(url)
        assertThat(server.wasUnavailable(url)).isTrue()
        // Sub-resources never count, only the page itself.
        get(ReaderUrls.article("wikivoyage_en_europe", "x.css"), false)
        assertThat(server.wasUnavailable(ReaderUrls.article("wikivoyage_en_europe", "x.css"))).isFalse()
        pending.clear()
        val ok = ReaderUrls.article("wikipedia_cs_all", "Praha")
        get(ok)
        assertThat(server.wasUnavailable(ok)).isFalse()
        get(url) // now a 404, not "come back later"
        assertThat(server.wasUnavailable(url)).isFalse()
    }

    @Test fun `a book that is still opening answers 503 so the reader reloads it, not a 404`() {
        pending += "wikivoyage_en_europe"
        assertThat(get(ReaderUrls.article("wikivoyage_en_europe", "Praha")).status).isEqualTo(503)
        assertThat(get(ReaderUrls.article("wikipedia_xx_all", "Praha")).status).isEqualTo(404)
        pending.clear()
        assertThat(get(ReaderUrls.article("wikivoyage_en_europe", "Praha")).status).isEqualTo(404)
    }

    @Test fun `big media without an extension is streamed with a sniffed type`() {
        val before = maps.gets
        val r = get(ReaderUrls.article("maps_en_czech-republic", "media/clip"), false)
        assertThat(r.status).isEqualTo(200)
        assertThat(r.mime).isEqualTo("video/mp4")
        assertThat(r.headers["Accept-Ranges"]).isEqualTo("bytes")
        assertThat(r.bytes().size).isEqualTo(5 * 1024 * 1024)
        assertThat(maps.gets).isEqualTo(before)
        val range = get(ReaderUrls.article("maps_en_czech-republic", "media/clip"), false, mapOf("Range" to "bytes=0-99"))
        assertThat(range.status).isEqualTo(206)
        assertThat(range.mime).isEqualTo("video/mp4")
        assertThat(maps.gets).isEqualTo(before) // never a full decompress for the MIME of a range
    }

    @Test fun `redirect stubs are remembered so they get no history row`() {
        val url = ReaderUrls.article("wikipedia_cs_all", "Prag")
        assertThat(server.wasRedirect(url)).isFalse()
        get(url)
        assertThat(server.wasRedirect(url)).isTrue()
        assertThat(server.wasRedirect(ReaderUrls.article("wikipedia_cs_all", "Praha"))).isFalse()
    }

    @Test fun `map pages get the worker bridge before their own scripts`() {
        val html = get(ReaderUrls.article("maps_en_czech-republic", "index.html")).bytes().decodeToString()
        assertThat(html).contains("/_offlinka/map-bridge.js")
        assertThat(html.indexOf("map-bridge.js")).isLessThan(html.indexOf("./a.js"))
        assertThat(html).doesNotContain("reader.css")
    }

    @Test fun `no page can keep a service worker`() {
        val sw = server.serveServiceWorker(ReaderUrls.article("maps_en_czech-republic", "sw.js"))
        assertThat(sw.status).isEqualTo(403)
        // Every HTML page (not only Wikipedia) unregisters old workers before its own scripts run.
        for (url in listOf(ReaderUrls.article("offlinka_cs_prirucky", "prvni-pomoc.html"), ReaderUrls.article("wikipedia_cs_all", "Praha"))) {
            val html = get(url).bytes().decodeToString()
            assertThat(html).contains("getRegistrations")
        }
        val map = get(ReaderUrls.article("maps_en_czech-republic", "index.html")).bytes().decodeToString()
        assertThat(map.indexOf("getRegistrations")).isLessThan(map.indexOf("map-bridge.js"))
    }

    @Test fun `Czech books tell the reader script their language for typography`() {
        val html = get(ReaderUrls.article("wikipedia_cs_all", "Praha")).bytes().decodeToString()
        assertThat(html).contains("lang:'cs'")
        assertThat(HtmlInjector.snippet(ReaderLook(false), "en")).contains("lang:'en'")
        assertThat(HtmlInjector.snippet(ReaderLook(false), "")).doesNotContain("lang:")
    }

    @Test fun `sniffing recognises common media`() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        assertThat(ContentServer.sniff("....ftypisom".toByteArray())).isEqualTo("video/mp4")
        assertThat(ContentServer.sniff(b(0x1A, 0x45, 0xDF, 0xA3))).isEqualTo("video/webm")
        assertThat(ContentServer.sniff("OggS".toByteArray())).isEqualTo("audio/ogg")
        assertThat(ContentServer.sniff("ID3....".toByteArray())).isEqualTo("audio/mpeg")
        assertThat(ContentServer.sniff("RIFF....WEBP".toByteArray())).isEqualTo("image/webp")
        assertThat(ContentServer.sniff("%PDF-1.7".toByteArray())).isEqualTo("application/pdf")
        assertThat(ContentServer.sniff("hello".toByteArray())).isNull()
    }

    @Test fun `range header parsing`() {
        assertThat(ContentServer.parseRange("bytes=0-0", 10)).isEqualTo(0L to 0L)
        assertThat(ContentServer.parseRange("bytes=5-100", 10)).isEqualTo(5L to 9L)
        assertThat(ContentServer.parseRange("bytes=-20", 10)).isEqualTo(0L to 9L)
        assertThat(ContentServer.parseRange("bytes=10-", 10)).isNull()
        assertThat(ContentServer.parseRange("bytes=7-3", 10)).isNull()
        assertThat(ContentServer.parseRange("items=1-2", 10)).isNull()
        assertThat(ContentServer.parseRange("bytes=abc", 10)).isNull()
    }

    @Test fun `mime splitting`() {
        assertThat(ContentServer.splitMime("text/html; charset=UTF-8")).isEqualTo("text/html" to "UTF-8")
        assertThat(ContentServer.splitMime("image/png")).isEqualTo("image/png" to null)
    }
}

class HtmlInjectorTest {
    @Test fun `snippet goes right before the closing head tag`() {
        val out = HtmlInjector.inject("<html><HEAD lang=\"cs\"><title>x</title><link rel=stylesheet href=a.css></HEAD><body></body></html>".toByteArray(), ReaderLook(false))
            .decodeToString()
        assertThat(out).contains("href=a.css><link id=\"offlinka-reader-css\"")
        assertThat(out).contains("theme:'light'")
        assertThat(out).endsWith("</HEAD><body></body></html>")
    }

    @Test fun `pages without head get the snippet first`() {
        val out = HtmlInjector.inject("<p>hi</p>".toByteArray(), ReaderLook(true)).decodeToString()
        assertThat(out).endsWith("<p>hi</p>")
        assertThat(out).startsWith("<link id=\"offlinka-reader-css\"")
    }

    @Test fun `utf8 text survives`() {
        val out = HtmlInjector.inject("<head></head>Příliš žluťoučký kůň".toByteArray(), ReaderLook(false)).decodeToString()
        assertThat(out).endsWith("Příliš žluťoučký kůň")
    }

    @Test fun `look changes call the page hook`() {
        assertThat(HtmlInjector.lookScript(ReaderLook(true, 1.15f)))
            .isEqualTo("window.offlinkaReader&&window.offlinkaReader({theme:'dark',scale:1.15})")
    }
}
