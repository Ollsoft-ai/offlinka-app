package cz.offlinka.core

import com.google.common.truth.Truth.assertThat
import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Native
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Host-JVM tests of the generated Kotlin bindings against the real core (linux-x86_64 build, loaded through
 * JNA) and the real ZIMs of the drive edition. Skipped when either is missing (other machines).
 */
class CoreBindingsTest {
    private interface LibC : JnaLibrary {
        fun open(path: String, flags: Int): Int
    }

    companion object {
        private val drive = File(System.getProperty("offlinka.driveFiles") ?: "/srv/offlinka/drive/release/2026-11.3/files")
        private val guides get() = File(drive, "Telefon/offlinka_cs_prirucky.zim")
        private val games get() = File(drive, "Telefon/offlinka_mul_hry.zim")
        private val csMini get() = File(drive, "Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim")
        private val skMini get() = File(drive, "Telefon/wikipedia_sk_all_mini-offlinka_2026-08.zim")

        @BeforeClass @JvmStatic fun available() {
            val lib = File(System.getProperty("offlinka.hostCore") ?: "")
            assumeTrue("host core not built: $lib (scripts/gen-bindings.sh)", lib.isFile)
            assumeTrue("drive files not found: $drive", guides.isFile)
        }
    }

    @Test fun `core reports its version`() {
        assertThat(coreVersion()).isNotEmpty()
    }

    @Test fun `guides book opens with metadata and a main page`() {
        Book.openPath(guides.path).use { book ->
            val info = book.info()
            assertThat(info.id).isEqualTo("offlinka_cs_prirucky")
            assertThat(info.title).isEqualTo("Nouzové příručky")
            assertThat(info.language).isEqualTo("ces")
            assertThat(info.mainPath).isNotNull()
            val main = book.get(info.mainPath!!)
            assertThat(main.mime).startsWith("text/html")
            assertThat(main.data.decodeToString()).contains("<html")
            assertThat(book.get("prvni-pomoc.html").data.size).isGreaterThan(1000)
        }
    }

    @Test fun `missing path is NotFound`() {
        Book.openPath(guides.path).use { book ->
            try {
                book.get("does/not/exist.html")
                error("expected NotFound")
            } catch (e: CoreException.NotFound) {
                // expected
            }
        }
    }

    @Test fun `not a zim is InvalidZim`() {
        val f = File.createTempFile("bad", ".zim").apply { writeText("hello"); deleteOnExit() }
        try {
            Book.openPath(f.path).close()
            error("expected InvalidZim")
        } catch (e: CoreException) {
            assertThat(e).isInstanceOf(CoreException.InvalidZim::class.java)
        }
    }

    @Test fun `opening from a file descriptor works and the core owns it`() {
        assumeTrue("games ZIM not found: $games", games.isFile)
        val libc = Native.load("c", LibC::class.java)
        val fd = libc.open(games.path, 0)
        assertThat(fd).isAtLeast(0)
        Book.openFd(fd, games.name).use { book ->
            val info = book.info()
            assertThat(info.mainPath).isNotNull()
            assertThat(book.get(info.mainPath!!).data).isNotEmpty()
        }
    }

    @Test fun `range reads match full reads`() {
        assumeTrue("games ZIM not found: $games", games.isFile)
        Book.openPath(games.path).use { book ->
            val main = book.info().mainPath!!
            val full = book.get(main)
            val len = book.contentLength(full.path)
            assertThat(len).isEqualTo(full.data.size.toULong())
            val part = book.getRange(full.path, 10u, 100u)
            assertThat(part.toList()).isEqualTo(full.data.copyOfRange(10, 110).toList())
            assertThat(book.getRange(full.path, len + 10u, 10u)).isEmpty()
        }
    }

    @Test fun `wikipedia mini suggests without diacritics`() {
        assumeTrue(csMini.isFile)
        Book.openPath(csMini.path).use { book ->
            val hits = book.suggest("Praha", 10u)
            assertThat(hits.map { it.title }).contains("Praha")
            assertThat(book.randomArticle()).isNotNull()
            val main = book.get(book.info().mainPath!!)
            assertThat(main.mime).startsWith("text/html")
        }
    }

    @Test fun `library merges books with Czech Wikipedia first`() {
        assumeTrue(csMini.isFile && skMini.isFile)
        Book.openPath(skMini.path).use { sk ->
            Book.openPath(csMini.path).use { cs ->
                BookLibrary().use { lib ->
                    lib.add(sk)
                    lib.add(cs)
                    assertThat(lib.books()).hasSize(2)
                    val hits = lib.suggestAll("Bratislava", 20u)
                    assertThat(hits).isNotEmpty()
                    assertThat(hits.first().bookId).isEqualTo("wikipedia_cs_all")
                    assertThat(hits.map { it.bookId }.toSet()).containsExactly("wikipedia_cs_all", "wikipedia_sk_all")
                }
            }
        }
    }

    @Test fun `illustration is a png`() {
        Book.openPath(guides.path).use { book ->
            val png = book.illustration(48u)
            if (png != null) assertThat(png.copyOfRange(1, 4).decodeToString()).isEqualTo("PNG")
        }
    }
}
