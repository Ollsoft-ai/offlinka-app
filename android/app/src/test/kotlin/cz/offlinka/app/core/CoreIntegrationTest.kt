package cz.offlinka.app.core

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.content.ContentBook
import cz.offlinka.app.content.ContentException
import cz.offlinka.app.content.CoreContentSource
import cz.offlinka.app.reader.AssetSource
import cz.offlinka.app.reader.ContentServer
import cz.offlinka.app.reader.ReaderLook
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.app.reader.ServedBook
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The app's own code (CoreContentSource, ContentServer) on the real core (host build via JNA) and the real
 * ZIMs of drive edition 2026-11.3: what the WebView would get for real pages.
 */
class CoreIntegrationTest {
    private class B(override val content: ContentBook, override val isWiki: Boolean) : ServedBook {
        override val mainPath: String? get() = content.info.mainPath
    }

    companion object {
        private val drive = File(System.getProperty("offlinka.driveFiles") ?: "/srv/offlinka/drive/release/2026-11.3/files")
        private val source = CoreContentSource()
        private val books = linkedMapOf<String, B>()
        private val errors = mutableListOf<IOException>()
        private lateinit var server: ContentServer

        @BeforeClass @JvmStatic fun open() {
            assumeTrue("host core not built", File(System.getProperty("offlinka.hostCore") ?: "").isFile)
            assumeTrue("drive files not found", File(drive, "Telefon").isDirectory)
            fun add(id: String, rel: String, wiki: Boolean) {
                val f = File(drive, rel)
                if (f.isFile) books[id] = B(source.openPath(f.path), wiki)
            }
            add("wikipedia_cs_all", "Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim", true)
            add("wikipedia_sk_all", "Telefon/wikipedia_sk_all_mini-offlinka_2026-08.zim", true)
            add("offlinka_cs_prirucky", "Telefon/offlinka_cs_prirucky.zim", false)
            add("offlinka_mul_hry", "Telefon/offlinka_mul_hry.zim", false)
            server = ContentServer(
                books = { books },
                assets = AssetSource { null },
                look = { ReaderLook(false) },
                notFoundPage = { "<p>404</p>" },
                onDriveError = { errors += it },
            )
        }

        @AfterClass @JvmStatic fun close() {
            books.values.forEach { it.content.close() }
        }
    }

    private fun get(url: String, mainFrame: Boolean = true, headers: Map<String, String> = emptyMap()) =
        server.serve(url, "GET", headers, mainFrame)

    @Test fun `metadata maps onto BookMeta`() {
        val info = books.getValue("offlinka_cs_prirucky").content.info
        assertThat(info.id).isEqualTo("offlinka_cs_prirucky")
        assertThat(info.title).isEqualTo("Nouzové příručky")
        assertThat(info.mainPath).isNotNull()
        assertThat(info.articleCount).isGreaterThan(0L)
        assertThat(source.coreVersion).isNotEmpty()
    }

    @Test fun `czech wikipedia main page is served with the reader stylesheet`() {
        val wiki = books["wikipedia_cs_all"]
        assumeTrue(wiki != null)
        val root = get("https://offlinka.local/wikipedia_cs_all/")
        val js = root.bytes().decodeToString()
        assertThat(js).contains("location.replace('https://offlinka.local/wikipedia_cs_all/")
        val main = wiki!!.content.info.mainPath!!
        val r = get(ReaderUrls.article("wikipedia_cs_all", main))
        assertThat(r.status).isEqualTo(200)
        assertThat(r.mime).isEqualTo("text/html")
        val html = r.bytes().decodeToString()
        assertThat(html).contains("/_offlinka/reader.css")
        assertThat(html.lowercase()).contains("<body")
    }

    @Test fun `an article, its stylesheet and an image come with the right mime`() {
        assumeTrue(books.containsKey("wikipedia_cs_all"))
        val r = get(ReaderUrls.article("wikipedia_cs_all", "Praha"))
        assertThat(r.status).isEqualTo(200)
        val html = r.bytes().decodeToString()
        assertThat(html).contains("Praha")
        // The first stylesheet and image of our own origin, resolved like the WebView would.
        val base = java.net.URI(ReaderUrls.article("wikipedia_cs_all", "Praha"))
        fun ours(regex: Regex) = regex.findAll(html).map { base.resolve(it.groupValues[1].replace("&amp;", "&")).toString() }
            .firstOrNull { ReaderUrls.isOurs(it) }
        ours(Regex("""<link[^>]+href="([^"]+\.css)"""))?.let { url ->
            val c = get(url, mainFrame = false)
            assertThat(c.status).isEqualTo(200)
            assertThat(c.mime).isEqualTo("text/css")
        }
        ours(Regex("""<img[^>]+src="([^"]+)""""))?.let { url ->
            val i = get(url, mainFrame = false)
            assertThat(i.status).isEqualTo(200)
            assertThat(i.mime).startsWith("image/")
            assertThat(i.bytes().size).isGreaterThan(100)
        }
    }

    @Test fun `range on a real entry matches the full read`() {
        val games = books.getValue("offlinka_mul_hry")
        val main = games.content.info.mainPath!!
        val full = games.content.get(main)
        val r = get(ReaderUrls.article("offlinka_mul_hry", full.path), mainFrame = false, headers = mapOf("Range" to "bytes=5-54"))
        assertThat(r.status).isEqualTo(206)
        assertThat(r.headers["Content-Range"]).isEqualTo("bytes 5-54/${full.data.size}")
        assertThat(r.bytes().toList()).isEqualTo(full.data.copyOfRange(5, 55).toList())
    }

    @Test fun `guides pages behind the home quick links exist`() {
        val guides = books.getValue("offlinka_cs_prirucky").content
        for (p in listOf("prvni-pomoc.html", "vypadek-proudu.html")) {
            assertThat(guides.get(p).mime).startsWith("text/html")
        }
    }

    @Test fun `games load their own scripts`() {
        val games = books.getValue("offlinka_mul_hry")
        val main = games.content.info.mainPath!!
        val html = get(ReaderUrls.article("offlinka_mul_hry", main)).bytes().decodeToString()
        assertThat(html).doesNotContain("/_offlinka/reader.css")
        val scripts = Regex("""<script[^>]+src="([^"]+)"""").findAll(html).map { it.groupValues[1] }.filter { !it.startsWith("http") }.toList()
        for (s in scripts) {
            val url = java.net.URI(ReaderUrls.article("offlinka_mul_hry", main)).resolve(s).toString()
            assertThat(get(url, mainFrame = false).status).isEqualTo(200)
        }
    }

    @Test fun `missing article is a 404, not a drive error`() {
        val r = get(ReaderUrls.article("offlinka_cs_prirucky", "nic-takoveho.html"))
        assertThat(r.status).isEqualTo(404)
        assertThat(errors).isEmpty()
        try {
            books.getValue("offlinka_cs_prirucky").content.get("nic-takoveho.html")
            error("expected NotFound")
        } catch (e: ContentException.NotFound) {
            // mapped from CoreException.NotFound
        }
    }

    @Test fun `library search across Czech and Slovak Wikipedia`() {
        assumeTrue(books.containsKey("wikipedia_cs_all") && books.containsKey("wikipedia_sk_all"))
        source.newLibrary().use { lib ->
            books.values.forEach { lib.add(it.content) }
            val t0 = System.nanoTime()
            // Without a .offidx (edition 2026-11.3 ships none yet) the core matches title prefixes with diacritics.
            val hits = lib.suggestAll("Český Krumlov", 20)
            val ms = (System.nanoTime() - t0) / 1e6
            assertThat(hits.map { it.title }).contains("Český Krumlov")
            assertThat(hits.first().bookId).isEqualTo("wikipedia_cs_all")
            assertThat(hits.map { it.bookId }.toSet()).contains("wikipedia_sk_all")
            println("suggestAll('Český Krumlov') on cs+sk mini without index: ${"%.1f".format(ms)} ms, ${hits.size} hits")
        }
    }

    @Test fun `with a search index diacritics do not matter`() {
        val index = System.getenv("OFFLINKA_CS_MINI_INDEX")?.let(::File)
        assumeTrue("set OFFLINKA_CS_MINI_INDEX to a .offidx of the cs mini", index?.isFile == true)
        val f = File(drive, "Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim")
        source.openPath(f.path).use { book ->
            book.attachIndexPath(index!!.path)
            assertThat(book.info.hasIndex).isTrue()
            val hits = book.suggest("cesky krumlov", 10)
            assertThat(hits.first().title).isEqualTo("Český Krumlov")
        }
    }

    @Test fun `random article is a real page`() {
        assumeTrue(books.containsKey("wikipedia_cs_all"))
        val wiki = books.getValue("wikipedia_cs_all").content
        val path = wiki.randomArticle()
        assertThat(path).isNotNull()
        assertThat(wiki.get(path!!).mime).startsWith("text/html")
    }
}
