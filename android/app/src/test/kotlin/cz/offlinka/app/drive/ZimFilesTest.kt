package cz.offlinka.app.drive

import com.google.common.truth.Truth.assertThat
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.Flavour
import cz.offlinka.ui.model.MapRegion
import org.junit.Test

class ZimNameTest {
    @Test fun `wikipedia maxi with custom tag and date`() {
        val n = ZimName.parse("wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        assertThat(n.key).isEqualTo("wikipedia_cs_all")
        assertThat(n.kind).isEqualTo(BookKind.WIKIPEDIA)
        assertThat(n.flavour).isEqualTo(Flavour.MAXI)
        assertThat(n.date).isEqualTo("2026-09")
        assertThat(n.language).isEqualTo("cs")
    }

    @Test fun `english top1m nopic`() {
        val n = ZimName.parse("wikipedia_en_top1m_nopic_2026-04.zim")
        assertThat(n.key).isEqualTo("wikipedia_en_top1m")
        assertThat(n.flavour).isEqualTo(Flavour.NOPIC)
        assertThat(n.kind).isEqualTo(BookKind.WIKIPEDIA)
    }

    @Test fun `medicine is its own kind`() {
        assertThat(ZimName.parse("wikipedia_en_medicine_nopic_2026-10.zim").kind).isEqualTo(BookKind.MEDICINE)
    }

    @Test fun `maps with region and no flavour`() {
        val n = ZimName.parse("maps_en_czech-republic_2026-06.zim")
        assertThat(n.key).isEqualTo("maps_en_czech-republic")
        assertThat(n.kind).isEqualTo(BookKind.MAPS)
        assertThat(n.region).isEqualTo(MapRegion.CZECHIA)
        assertThat(n.flavour).isEqualTo(Flavour.OTHER)
        assertThat(ZimName.parse("maps_en_slovakia_2026-06.zim").region).isEqualTo(MapRegion.SLOVAKIA)
    }

    @Test fun `own books without date`() {
        assertThat(ZimName.parse("offlinka_cs_prirucky.zim").kind).isEqualTo(BookKind.GUIDES)
        assertThat(ZimName.parse("offlinka_mul_hry.zim").kind).isEqualTo(BookKind.GAMES)
        assertThat(ZimName.parse("offlinka_mul_hry.zim").key).isEqualTo("offlinka_mul_hry")
    }

    @Test fun `sister projects`() {
        assertThat(ZimName.parse("wiktionary_cs_all_nopic_2026-07.zim").kind).isEqualTo(BookKind.WIKTIONARY)
        assertThat(ZimName.parse("wikivoyage_en_europe_maxi_2026-09.zim").kind).isEqualTo(BookKind.WIKIVOYAGE)
        assertThat(ZimName.parse("wikisource_cs_all_maxi_2026-08.zim").kind).isEqualTo(BookKind.WIKISOURCE)
        assertThat(ZimName.parse("wikiversity_cs_all_maxi_2026-08.zim").kind).isEqualTo(BookKind.WIKIVERSITY)
        assertThat(ZimName.parse("wikibooks_cs_all_maxi-offlinka_2026-10.zim").kind).isEqualTo(BookKind.WIKIBOOKS)
        assertThat(ZimName.parse("wikiquote_cs_all_maxi-offlinka_2026-07.zim").kind).isEqualTo(BookKind.WIKIQUOTE)
    }

    @Test fun `unknown names still get a safe key`() {
        val n = ZimName.parse("Moje Kniha (2).zim")
        assertThat(n.kind).isEqualTo(BookKind.OTHER)
        assertThat(n.key).matches("[a-z0-9_-]+")
    }
}

class BookSelectorTest {
    private fun f(name: String, folder: DriveFolder, drive: Int = 0, size: Long = 1) =
        DriveFile(name, folder, size, "$drive\u0000$name", drive)

    @Test fun `maxi in Knihovna wins over mini in Telefon`() {
        val choices = BookSelector.select(
            listOf(
                f("wikipedia_cs_all_mini-offlinka_2026-09.zim", DriveFolder.PHONE),
                f("wikipedia_cs_all_maxi-offlinka_2026-09.zim", DriveFolder.LIBRARY),
            ),
        )
        assertThat(choices).hasSize(1)
        assertThat(choices[0].zim.name).isEqualTo("wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        assertThat(choices[0].fallbacks.map { it.name }).containsExactly("wikipedia_cs_all_mini-offlinka_2026-09.zim")
    }

    @Test fun `mini is used when there is no maxi`() {
        val c = BookSelector.select(listOf(f("wikipedia_sk_all_mini-offlinka_2026-08.zim", DriveFolder.PHONE)))
        assertThat(c.single().zim.folder).isEqualTo(DriveFolder.PHONE)
    }

    @Test fun `the same file in both folders is opened once from Knihovna`() {
        val c = BookSelector.select(
            listOf(f("offlinka_cs_prirucky.zim", DriveFolder.PHONE), f("offlinka_cs_prirucky.zim", DriveFolder.LIBRARY)),
        )
        assertThat(c.single().zim.folder).isEqualTo(DriveFolder.LIBRARY)
    }

    @Test fun `newer edition wins`() {
        val c = BookSelector.select(
            listOf(
                f("wikipedia_cs_all_maxi_2026-03.zim", DriveFolder.LIBRARY),
                f("wikipedia_cs_all_maxi_2026-09.zim", DriveFolder.LIBRARY),
            ),
        )
        assertThat(c.single().zim.name).contains("2026-09")
    }

    @Test fun `index next to the zim is matched`() {
        val c = BookSelector.select(
            listOf(
                f("wikipedia_cs_all_maxi-offlinka_2026-09.zim", DriveFolder.LIBRARY),
                f("wikipedia_cs_all_maxi-offlinka_2026-09.offidx", DriveFolder.LIBRARY),
                f("wikipedia_cs_all_mini-offlinka_2026-09.offidx", DriveFolder.PHONE),
            ),
        )
        assertThat(c.single().index?.name).isEqualTo("wikipedia_cs_all_maxi-offlinka_2026-09.offidx")
    }

    @Test fun `home order follows the spec`() {
        val names = listOf(
            "wikivoyage_en_europe_maxi_2026-09.zim", "offlinka_mul_hry.zim", "maps_en_slovakia_2026-06.zim",
            "wikipedia_en_top1m_nopic_2026-04.zim", "wikipedia_sk_all_maxi_2026-08.zim", "offlinka_cs_prirucky.zim",
            "wikipedia_en_medicine_nopic_2026-10.zim", "maps_en_czech-republic_2026-06.zim", "wikipedia_cs_all_maxi_2026-09.zim",
            "wiktionary_cs_all_nopic_2026-07.zim", "wikibooks_cs_all_maxi_2026-10.zim",
        )
        val keys = BookSelector.select(names.map { f(it, DriveFolder.LIBRARY) }).map { it.key }
        assertThat(keys).containsExactly(
            "wikipedia_cs_all", "wikipedia_sk_all", "wikipedia_en_top1m", "wikipedia_en_medicine",
            "maps_en_czech-republic", "maps_en_slovakia", "offlinka_cs_prirucky", "offlinka_mul_hry",
            "wikibooks_cs_all", "wiktionary_cs_all", "wikivoyage_en_europe",
        ).inOrder()
    }

    @Test fun `two drives - first drive wins a tie, books from both are used`() {
        val c = BookSelector.select(
            listOf(
                f("wikipedia_cs_all_maxi_2026-09.zim", DriveFolder.LIBRARY, drive = 1),
                f("wikipedia_cs_all_maxi_2026-09.zim", DriveFolder.LIBRARY, drive = 0),
                f("maps_en_slovakia_2026-06.zim", DriveFolder.LIBRARY, drive = 1),
            ),
        )
        assertThat(c.map { it.key }).containsExactly("wikipedia_cs_all", "maps_en_slovakia").inOrder()
        assertThat(c[0].zim.driveIndex).isEqualTo(0)
    }

    @Test fun `ui language from 3-letter codes`() {
        assertThat(BookSelector.uiLanguage("ces")).isEqualTo("cs")
        assertThat(BookSelector.uiLanguage("slk")).isEqualTo("sk")
        assertThat(BookSelector.uiLanguage("eng")).isEqualTo("en")
        assertThat(BookSelector.uiLanguage("mul")).isEqualTo("mul")
    }
}

class DriveEditionTest {
    @Test fun `edition from the drive README`() {
        val readme = "Offlinka\r\nStav obsahu (edice listopad 2026)\r\nObsah je snímek…\r\nStav obsahu (edícia november 2026)"
        assertThat(DriveEdition.parse(readme)).isEqualTo("2026-11")
        assertThat(DriveEdition.parse("(edice červenec 2027)")).isEqualTo("2027-07")
        assertThat(DriveEdition.parse("(edice červen 2027)")).isEqualTo("2027-06")
        assertThat(DriveEdition.parse("(edice leden 2028)")).isEqualTo("2028-01")
        assertThat(DriveEdition.parse("nothing here")).isNull()
    }
}
