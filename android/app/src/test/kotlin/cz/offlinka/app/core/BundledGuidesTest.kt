package cz.offlinka.app.core

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.content.CoreContentSource
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The guides bundled in the APK, read by the real core (host build): needs no drive files, runs on CI. */
class BundledGuidesTest {
    @Test fun `the guides bundled in the app never send anyone to Kiwix`() {
        // The app edition (scripts/build-app-guides.py): what a store reviewer without the drive opens.
        assumeTrue("host core not built", File(System.getProperty("offlinka.hostCore") ?: "").isFile)
        val asset = File("src/main/assets/offlinka_cs_prirucky.zim")
        assumeTrue(asset.isFile)
        CoreContentSource().openPath(asset.path).use { book ->
            val main = book.info.mainPath!!
            val seen = linkedSetOf<String>()
            val queue = ArrayDeque(listOf(main))
            while (queue.isNotEmpty()) {
                val path = queue.removeFirst()
                if (!seen.add(path)) continue
                val res = book.get(path)
                if (!res.mime.startsWith("text/") && !res.mime.contains("css") && !res.mime.contains("javascript")) continue
                val text = res.data.toString(Charsets.UTF_8)
                assertThat(text.lowercase()).doesNotContain("kiwix")
                if (res.mime.startsWith("text/html")) {
                    // Relative links of the book only (no data:, tel:, https:, anchors).
                    Regex("""(?:href|src)="([^"]+)"""").findAll(text).map { it.groupValues[1].substringBefore('#').substringBefore('?') }
                        .filter { it.isNotEmpty() && ':' !in it && !it.startsWith("/") }
                        .forEach { link -> queue += java.net.URI(path).resolve(link).path.removePrefix("/") }
                }
            }
            assertThat(seen).containsAtLeast("prvni-pomoc.html", "vypadek-proudu.html")
            assertThat(seen.none { it.startsWith("jak-") || it.startsWith("zacni-zde") }).isTrue()
            assertThat(book.info.title.lowercase()).doesNotContain("kiwix")
            assertThat(book.info.description.lowercase()).doesNotContain("kiwix")
        }
    }

}
