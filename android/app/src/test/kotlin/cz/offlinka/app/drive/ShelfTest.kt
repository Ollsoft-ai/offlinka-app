package cz.offlinka.app.drive

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.FakeBook
import cz.offlinka.app.fakes.FakeContentSource
import cz.offlinka.app.fakes.FakeDriveAccess
import cz.offlinka.app.fakes.fakeGuides
import cz.offlinka.app.fakes.fakeMaps
import cz.offlinka.app.fakes.fakeWiki
import cz.offlinka.ui.screens.ConnectProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShelfTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val drives = FakeDriveAccess()
    private val wikiMaxi = mutableListOf<FakeBook>()
    private val source = FakeContentSource(
        mapOf(
            "wikipedia_cs_all_maxi-offlinka_2026-09.zim" to { fakeWiki().also { wikiMaxi += it } },
            "wikipedia_cs_all_mini-offlinka_2026-09.zim" to { fakeWiki() },
            "offlinka_cs_prirucky.zim" to { fakeGuides() },
            "maps_en_czech-republic_2026-06.zim" to { fakeMaps() },
        ),
    )
    private val shelf = Shelf(drives, source, scope, io = dispatcher, pollMillis = 1_000)

    private fun offlinkaDrive(uri: String = "content://ext/tree/1234-ABCD%3A", label: String = "OFFLINKA") = drives.add(
        uri, label,
        "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 9_958_167_476L,
        "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
        "Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim" to 911_871_253L,
        "Telefon/offlinka_cs_prirucky.zim" to 393_612L,
        "README.txt" to 5_638L,
    )

    @Test fun `no permission yet means no drive`() = runTest(dispatcher) {
        shelf.start()
        advanceUntilIdle()
        assertThat(shelf.state.value).isEqualTo(ShelfState.NoDrive)
    }

    @Test fun `picking the drive opens the best edition of each book`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        shelf.onPicked(tree)
        advanceUntilIdle()
        val ready = shelf.state.value as ShelfState.Ready
        assertThat(ready.books.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky").inOrder()
        assertThat(ready.books.first().ui.fileName).isEqualTo("wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        assertThat(ready.books.first().ui.folder).isEqualTo("Knihovna")
        assertThat(ready.books.first().ui.sizeBytes).isEqualTo(1000)
        // The identical guides file in Telefon is not "replaced", it is the same book.
        assertThat(ready.replaced.map { it.fileName }).containsExactly("wikipedia_cs_all_mini-offlinka_2026-09.zim")
        assertThat(ready.drives.single().name).isEqualTo("OFFLINKA")
        assertThat(drives.granted).containsExactly(tree)
        assertThat(source.opened).hasSize(2)
        assertThat(drives.openedFds).containsExactly(
            "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim", "Knihovna/offlinka_cs_prirucky.zim",
        )
    }

    @Test fun `the drive edition comes from its README`() = runTest(dispatcher) {
        val tree = drives.addWithContent(
            "content://ext/tree/1234-ABCD%3A", "OFFLINKA",
            mapOf("README.txt" to "\uFEFFOfflinka\r\nStav obsahu (edice listopad 2026)\r\n".toByteArray()),
            "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
        )
        shelf.onPicked(tree)
        advanceUntilIdle()
        val ready = shelf.state.value as ShelfState.Ready
        assertThat(ready.drives.single().edition).isEqualTo("2026-11")
    }

    @Test fun `wrong folder is explained and its permission released`() = runTest(dispatcher) {
        val tree = drives.add("content://ext/tree/1234-ABCD%3ADCIM", "DCIM", "foto.jpg" to 1L)
        shelf.onPicked(tree)
        advanceUntilIdle()
        assertThat(shelf.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.WRONG_FOLDER))
        assertThat(drives.granted).isEmpty()
        assertThat(drives.released).containsExactly(tree)
    }

    @Test fun `picking the Knihovna folder itself still works`() = runTest(dispatcher) {
        val tree = drives.add("content://ext/tree/1234-ABCD%3AKnihovna", "Knihovna", "offlinka_cs_prirucky.zim" to 393_612L)
        shelf.onPicked(tree)
        advanceUntilIdle()
        val ready = shelf.state.value as ShelfState.Ready
        assertThat(ready.books.single().id).isEqualTo("offlinka_cs_prirucky")
    }

    @Test fun `a permission the system refuses to persist is reported`() = runTest(dispatcher) {
        drives.refusePersist = true
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        assertThat(shelf.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.NO_ACCESS))
    }

    @Test fun `unplugging closes the books, plugging back reopens them`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        shelf.onPicked(tree)
        advanceUntilIdle()
        val first = shelf.state.value as ShelfState.Ready
        drives.drives.getValue(tree).plugged = false
        wikiMaxi.last().unplugged = true
        shelf.reportIoError(java.io.IOException("EIO"))
        runCurrent() // not advanceUntilIdle: the shelf now polls for the drive forever
        assertThat(shelf.state.value).isEqualTo(ShelfState.Unplugged)
        assertThat((first.books.first().content as FakeBook).closed).isTrue()

        drives.drives.getValue(tree).plugged = true
        advanceTimeBy(1_500)
        advanceUntilIdle()
        val again = shelf.state.value as ShelfState.Ready
        assertThat(again.books.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky")
        assertThat(again.books.first().content).isNotSameInstanceAs(first.books.first().content)
    }

    @Test fun `an io error with the drive still present changes nothing`() = runTest(dispatcher) {
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        val before = shelf.state.value
        shelf.reportIoError(java.io.IOException("one bad cluster"))
        advanceUntilIdle()
        assertThat(shelf.state.value).isSameInstanceAs(before)
    }

    @Test fun `after a reboot without the drive the app waits for it`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        drives.granted += tree
        drives.drives.getValue(tree).plugged = false
        shelf.start()
        advanceTimeBy(500)
        assertThat(shelf.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.NOT_FOUND))
        drives.drives.getValue(tree).plugged = true
        advanceTimeBy(1_500)
        advanceUntilIdle()
        assertThat(shelf.state.value).isInstanceOf(ShelfState.Ready::class.java)
    }

    @Test fun `permission lost after reboot asks to pick again`() = runTest(dispatcher) {
        offlinkaDrive()
        shelf.start()
        advanceUntilIdle()
        assertThat(shelf.state.value).isEqualTo(ShelfState.NoDrive)
    }

    @Test fun `two drives are merged`() = runTest(dispatcher) {
        val a = offlinkaDrive()
        val b = drives.add("content://ext/tree/9999-0000%3A", "MAPY", "Knihovna/maps_en_czech-republic_2026-06.zim" to 1_065_265_251L)
        shelf.onPicked(a)
        advanceUntilIdle()
        shelf.onPicked(b)
        advanceUntilIdle()
        val ready = shelf.state.value as ShelfState.Ready
        assertThat(ready.books.map { it.id }).containsExactly("wikipedia_cs_all", "maps_en_czech-republic", "offlinka_cs_prirucky").inOrder()
        assertThat(ready.drives.map { it.name }).containsExactly("OFFLINKA", "MAPY")
        // One drive unplugged: the other's books stay.
        drives.drives.getValue(b).plugged = false
        shelf.reload()
        advanceUntilIdle()
        val one = shelf.state.value as ShelfState.Ready
        assertThat(one.books.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky")
        assertThat(one.drives.map { it.connected }).containsExactly(true, false).inOrder()
    }

    @Test fun `a broken file falls back to the next edition`() = runTest(dispatcher) {
        source.failing += "wikipedia_cs_all_maxi-offlinka_2026-09.zim"
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        val ready = shelf.state.value as ShelfState.Ready
        assertThat(ready.books.first().ui.fileName).isEqualTo("wikipedia_cs_all_mini-offlinka_2026-09.zim")
    }

    @Test fun `the search index next to a book is attached`() = runTest(dispatcher) {
        val tree = drives.add(
            "content://ext/tree/1234-ABCD%3A", "OFFLINKA",
            "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
            "Knihovna/offlinka_cs_prirucky.offidx" to 1_000L,
        )
        shelf.onPicked(tree)
        advanceUntilIdle()
        val ready = shelf.state.value as ShelfState.Ready
        assertThat(ready.books.single().ui.hasIndex).isTrue()
        assertThat((ready.books.single().content as FakeBook).attachedIndex).startsWith("fd:")
    }
}
