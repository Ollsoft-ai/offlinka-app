package cz.offlinka.app.drive

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.FakeBook
import cz.offlinka.app.fakes.FakeContentSource
import cz.offlinka.app.fakes.FakeDriveAccess
import cz.offlinka.app.fakes.fakeGuides
import cz.offlinka.app.fakes.fakeMaps
import cz.offlinka.app.fakes.fakeWiki
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.screens.ConnectProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

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
            "wikipedia_sk_all_maxi-offlinka_2026-08.zim" to { fakeWiki("wikipedia_sk_all") },
            "wikipedia_en_top1m_nopic_2026-04.zim" to { fakeWiki("wikipedia_en_top1m") },
            "offlinka_cs_prirucky.zim" to { fakeGuides() },
            "maps_en_czech-republic_2026-06.zim" to { fakeMaps() },
        ),
    )
    private var ui = "cs"
    private val foreground = MutableStateFlow(true)
    private fun shelf(indexDir: File? = null, builtin: (() -> String?)? = null) = Shelf(
        drives, source, scope, io = dispatcher, pollMillis = 1_000, indexDir = indexDir,
        uiLanguage = { ui }, foreground = foreground, builtinGuides = builtin,
    )
    private val shelf = shelf()

    private fun offlinkaDrive(uri: String = "content://ext/tree/1234-ABCD%3A", label: String = "OFFLINKA") = drives.add(
        uri, label,
        "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 9_958_167_476L,
        "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
        "Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim" to 911_871_253L,
        "Telefon/offlinka_cs_prirucky.zim" to 393_612L,
        "README.txt" to 5_638L,
    )

    private val ready get() = shelf.state.value as ShelfState.Ready

    @Test fun `no permission yet means no drive`() = runTest(dispatcher) {
        shelf.start()
        advanceUntilIdle()
        assertThat(shelf.state.value).isEqualTo(ShelfState.NoDrive)
    }

    @Test fun `picking the drive opens the best edition of each book`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        shelf.onPicked(tree)
        advanceUntilIdle()
        assertThat(ready.tiles.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky").inOrder()
        assertThat(ready.tiles.map { it.state }).containsExactly(BookState.OPEN, BookState.OPEN)
        assertThat(ready.books.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky")
        assertThat(ready.byId.getValue("wikipedia_cs_all").ui.fileName).isEqualTo("wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        assertThat(ready.byId.getValue("wikipedia_cs_all").ui.folder).isEqualTo("Knihovna")
        // The identical guides file in Telefon is not "replaced", it is the same book.
        assertThat(ready.replaced.map { it.fileName }).containsExactly("wikipedia_cs_all_mini-offlinka_2026-09.zim")
        assertThat(ready.drives.single().name).isEqualTo("OFFLINKA")
        assertThat(ready.drives.single().bookCount).isEqualTo(2)
        assertThat(drives.granted).containsExactly(tree)
        assertThat(source.openedNames).containsExactly("wikipedia_cs_all_maxi-offlinka_2026-09.zim", "offlinka_cs_prirucky.zim")
    }

    @Test fun `the drive edition comes from its README`() = runTest(dispatcher) {
        val tree = drives.addWithContent(
            "content://ext/tree/1234-ABCD%3A", "OFFLINKA",
            mapOf("README.txt" to "﻿Offlinka\r\nStav obsahu (edice listopad 2026)\r\n".toByteArray()),
            "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
        )
        shelf.onPicked(tree)
        advanceUntilIdle()
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

    @Test fun `picking the Knihovna or Telefon folder itself works`() = runTest(dispatcher) {
        val tree = drives.add(
            "content://ext/tree/1234-ABCD%3AKnihovna", "Knihovna",
            "offlinka_cs_prirucky.zim" to 393_612L, "wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 9_958_167_476L,
        )
        shelf.onPicked(tree)
        advanceUntilIdle()
        assertThat(ready.tiles.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky")
        assertThat(ready.byId.getValue("wikipedia_cs_all").ui.folder).isEqualTo("Knihovna")
    }

    @Test fun `picking a wrong folder while books are open keeps them and only says why`() = runTest(dispatcher) {
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        val before = shelf.state.value
        val problems = mutableListOf<ConnectProblem>()
        val collector = backgroundScope.launch(dispatcher) { shelf.pickProblem.collect { problems += it } }
        runCurrent()
        val dcim = drives.add("content://ext/tree/1234-ABCD%3ADCIM", "DCIM", "foto.jpg" to 1L)
        shelf.onPicked(dcim)
        advanceUntilIdle()
        runCurrent() // the collector lives in backgroundScope, which advanceUntilIdle leaves alone
        assertThat(shelf.state.value).isSameInstanceAs(before)
        assertThat((before as ShelfState.Ready).books.none { (it.content as FakeBook).closed }).isTrue()
        assertThat(problems).containsExactly(ConnectProblem.WRONG_FOLDER)
        assertThat(drives.released).containsExactly(dcim)
        collector.cancel()
    }

    @Test fun `a permission the system refuses to persist is reported`() = runTest(dispatcher) {
        drives.refusePersist = true
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        assertThat(shelf.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.NO_ACCESS))
    }

    @Test fun `unplugging publishes Unplugged before closing, plugging back reopens`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        shelf.onPicked(tree)
        advanceUntilIdle()
        val first = ready
        val book = first.books.first().content as FakeBook
        var stateAtClose: ShelfState? = null
        book.onClose = { stateAtClose = shelf.state.value }
        drives.drives.getValue(tree).plugged = false
        wikiMaxi.last().unplugged = true
        shelf.reportIoError(java.io.IOException("EIO"))
        runCurrent() // not advanceUntilIdle: the shelf now polls for the drive
        assertThat(shelf.state.value).isEqualTo(ShelfState.Unplugged)
        assertThat(book.closed).isTrue()
        // Blocker 1: nobody could still get the closed book from the published state.
        assertThat(stateAtClose).isEqualTo(ShelfState.Unplugged)
        // The drive's files are released at once so Android can unmount it.
        assertThat(book.fileClosed).isTrue()

        drives.drives.getValue(tree).plugged = true
        advanceTimeBy(1_500)
        advanceUntilIdle()
        val again = ready
        assertThat(again.books.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky")
        assertThat(again.books.first().content).isNotSameInstanceAs(first.books.first().content)
    }

    @Test fun `a rescan publishes the new books before it closes the old ones`() = runTest(dispatcher) {
        val a = offlinkaDrive()
        shelf.onPicked(a)
        advanceUntilIdle()
        val old = ready.books.map { it.content as FakeBook }
        var stateAtClose: ShelfState.Ready? = null
        old.first().onClose = { stateAtClose = shelf.state.value as? ShelfState.Ready }
        drives.add("content://ext/tree/9999-0000%3A", "MAPY", "Knihovna/maps_en_czech-republic_2026-06.zim" to 1L)
        shelf.onPicked(android.net.Uri.parse("content://ext/tree/9999-0000%3A"))
        advanceUntilIdle()
        assertThat(old.all { it.closed }).isTrue()
        // When the old book closed, the published state already held the new instances.
        assertThat(stateAtClose).isNotNull()
        assertThat(stateAtClose!!.books.map { it.content }).containsNoneIn(old)
        // Not destroyed: a page still loading from an old book gets a clean Io (503, reload), never a crash.
        assertThat(old.none { it.destroyed }).isTrue()
    }

    @Test fun `an io error with the drive still present changes nothing`() = runTest(dispatcher) {
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        val before = shelf.state.value
        shelf.reportIoError(java.io.IOException("one bad cluster"))
        advanceUntilIdle()
        assertThat(shelf.state.value).isSameInstanceAs(before)
    }

    @Test fun `after a reboot without the drive the app waits for it, only in the foreground`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        drives.granted += tree
        drives.drives.getValue(tree).plugged = false
        shelf.start()
        advanceTimeBy(500)
        assertThat(shelf.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.NOT_FOUND))
        foreground.value = false
        drives.drives.getValue(tree).plugged = true
        advanceTimeBy(5_000)
        assertThat(shelf.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.NOT_FOUND))
        foreground.value = true
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

    @Test fun `two drives are merged, an unplugged one keeps its name and its own count`() = runTest(dispatcher) {
        val a = offlinkaDrive()
        val b = drives.add("content://ext/tree/9999-0000%3A", "MAPY", "Knihovna/maps_en_czech-republic_2026-06.zim" to 1_065_265_251L)
        shelf.onPicked(a)
        advanceUntilIdle()
        shelf.onPicked(b)
        advanceUntilIdle()
        assertThat(ready.tiles.map { it.id }).containsExactly("wikipedia_cs_all", "maps_en_czech-republic", "offlinka_cs_prirucky").inOrder()
        assertThat(ready.drives.map { it.name to it.bookCount }).containsExactly("OFFLINKA" to 2, "MAPY" to 1)
        drives.drives.getValue(b).plugged = false
        shelf.reload()
        advanceUntilIdle()
        assertThat(ready.tiles.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky")
        assertThat(ready.drives.map { Triple(it.name, it.connected, it.bookCount) })
            .containsExactly(Triple("OFFLINKA", true, 2), Triple("MAPY", false, 0)).inOrder()
    }

    @Test fun `a stale permission of an old drive does not rescan on every resume, forgetting releases it`() = runTest(dispatcher) {
        val old = drives.add("content://ext/tree/0000-OLD%3A", "STARA", "Knihovna/maps_en_czech-republic_2026-06.zim" to 1L)
        drives.drives.getValue(old).plugged = false
        drives.granted += old
        val tree = offlinkaDrive()
        shelf.onPicked(tree)
        advanceUntilIdle()
        val opened = source.openedNames.size
        repeat(3) {
            shelf.onStorageChanged()
            advanceUntilIdle()
        }
        assertThat(source.openedNames).hasSize(opened)
        assertThat(ready.drives.map { it.id to it.connected }).containsExactly(old.toString() to false, tree.toString() to true)

        shelf.forget(old.toString())
        advanceUntilIdle()
        assertThat(drives.released).contains(old)
        assertThat(ready.drives.map { it.id }).containsExactly(tree.toString())
    }

    @Test fun `a newly plugged second drive is picked up on resume`() = runTest(dispatcher) {
        val tree = offlinkaDrive()
        val second = drives.add("content://ext/tree/9999-0000%3A", "MAPY", "Knihovna/maps_en_czech-republic_2026-06.zim" to 1L)
        drives.drives.getValue(second).plugged = false
        drives.granted += second
        shelf.onPicked(tree)
        advanceUntilIdle()
        assertThat(ready.byId).doesNotContainKey("maps_en_czech-republic")
        drives.drives.getValue(second).plugged = true
        shelf.onStorageChanged()
        advanceUntilIdle()
        assertThat(ready.byId).containsKey("maps_en_czech-republic")
    }

    @Test fun `the UI-language Wikipedia opens first, the rest fill in`() = runTest(dispatcher) {
        val gate = CountDownLatch(1)
        source.gated["offlinka_cs_prirucky.zim"] = gate
        source.gated["maps_en_czech-republic_2026-06.zim"] = gate
        ui = "sk"
        val tree = drives.add(
            "content://ext/tree/1234-ABCD%3A", "OFFLINKA",
            "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 1L,
            "Knihovna/wikipedia_sk_all_maxi-offlinka_2026-08.zim" to 1L,
            "Knihovna/offlinka_cs_prirucky.zim" to 1L,
            "Knihovna/maps_en_czech-republic_2026-06.zim" to 1L,
        )
        val parallel = Shelf(drives, source, scope, io = kotlinx.coroutines.Dispatchers.IO, uiLanguage = { ui })
        parallel.onPicked(tree)
        val first = waitFor(parallel) { it.byId.containsKey("wikipedia_sk_all") }
        assertThat(source.openedNames.first()).isEqualTo("wikipedia_sk_all_maxi-offlinka_2026-08.zim")
        assertThat(first.tiles.first().id).isEqualTo("wikipedia_sk_all")
        assertThat(first.tiles.first { it.id == "offlinka_cs_prirucky" }.state).isEqualTo(BookState.OPENING)
        gate.countDown()
        val all = waitFor(parallel) { r -> r.tiles.all { it.state == BookState.OPEN } }
        // Slovak UI: the Czech map moves behind the Czech Wikipedia (the Slovak map would be second).
        assertThat(all.tiles.map { it.id }).containsExactly(
            "wikipedia_sk_all", "offlinka_cs_prirucky", "wikipedia_cs_all", "maps_en_czech-republic",
        ).inOrder()
        assertThat(all.books).hasSize(4)
    }

    private fun waitFor(s: Shelf, cond: (ShelfState.Ready) -> Boolean): ShelfState.Ready {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            (s.state.value as? ShelfState.Ready)?.takeIf(cond)?.let { return it }
            Thread.sleep(10)
        }
        error("timed out, state ${s.state.value}")
    }

    @Test fun `a book that fails is a dimmed tile and can be retried`() = runTest(dispatcher) {
        source.failing += "offlinka_cs_prirucky.zim"
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        assertThat(ready.tiles.first { it.id == "offlinka_cs_prirucky" }.state).isEqualTo(BookState.FAILED)
        assertThat(ready.byId).doesNotContainKey("offlinka_cs_prirucky")
        source.failing.clear()
        shelf.retry("offlinka_cs_prirucky")
        advanceUntilIdle()
        assertThat(ready.tiles.first { it.id == "offlinka_cs_prirucky" }.state).isEqualTo(BookState.OPEN)
        assertThat(ready.byId).containsKey("offlinka_cs_prirucky")
    }

    @Test fun `a broken file falls back to the next edition`() = runTest(dispatcher) {
        source.failing += "wikipedia_cs_all_maxi-offlinka_2026-09.zim"
        shelf.onPicked(offlinkaDrive())
        advanceUntilIdle()
        assertThat(ready.byId.getValue("wikipedia_cs_all").ui.fileName).isEqualTo("wikipedia_cs_all_mini-offlinka_2026-09.zim")
    }

    @Test fun `the search index next to a book is attached`() = runTest(dispatcher) {
        val tree = drives.add(
            "content://ext/tree/1234-ABCD%3A", "OFFLINKA",
            "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
            "Knihovna/offlinka_cs_prirucky.offidx" to 1_000L,
        )
        shelf.onPicked(tree)
        advanceUntilIdle()
        assertThat(ready.books.single().ui.hasIndex).isTrue()
        assertThat((ready.books.single().content as FakeBook).attachedIndex).startsWith("fd:")
    }

    @Test fun `missing indexes are built on the phone, most useful first, English top1m only on request`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val memory = InMemoryDriveMemory()
        val s = Shelf(
            drives, source, scope, io = dispatcher, pollMillis = 1_000, indexDir = dir, memory = memory,
            uiLanguage = { ui }, foreground = foreground,
        )
        val tree = drives.add(
            "content://ext/tree/1234-ABCD%3A", "OFFLINKA",
            "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 10L,
            "Knihovna/wikipedia_en_top1m_nopic_2026-04.zim" to 30L,
            "Knihovna/offlinka_cs_prirucky.zim" to 20L,
            "Knihovna/maps_en_czech-republic_2026-06.zim" to 40L,
        )
        s.onPicked(tree)
        advanceUntilIdle()
        // The guides first (instant, emergency pages), then the UI-language Wikipedia, then the rest.
        assertThat(source.built).containsExactly("offlinka_cs_prirucky", "wikipedia_cs_all", "maps_en_czech-republic").inOrder()
        val r = s.state.value as ShelfState.Ready
        assertThat(r.byId.getValue("wikipedia_cs_all").ui.hasIndex).isTrue()
        assertThat(r.tiles.first { it.id == "wikipedia_cs_all" }.hasIndex).isTrue()
        val cs = r.byId.getValue("wikipedia_cs_all").content as FakeBook
        assertThat(cs.attachedIndex).isEqualTo(File(dir, Shelf.indexFileName("wikipedia_cs_all_maxi-offlinka_2026-09.zim", 10L)).path)
        // The lookup tables load when Search opens, not right after building.
        assertThat(cs.preloaded).isFalse()
        s.preloadIndexes()
        advanceUntilIdle()
        assertThat(cs.preloaded).isTrue()
        val en = r.byId.getValue("wikipedia_en_top1m")
        assertThat(en.ui.hasIndex).isFalse()
        assertThat(s.canBuildOnDemand(en)).isTrue()
        assertThat(s.indexing.value).isNull()

        // Book details → "Připravit hledání": built now, and the request survives a restart.
        s.buildIndexNow("wikipedia_en_top1m")
        advanceUntilIdle()
        assertThat(source.built.last()).isEqualTo("wikipedia_en_top1m")
        assertThat((s.state.value as ShelfState.Ready).byId.getValue("wikipedia_en_top1m").ui.hasIndex).isTrue()
        assertThat(memory.indexRequests()).containsExactly("wikipedia_en_top1m")

        // Next start: the built indexes are attached when the books open, nothing is rebuilt.
        source.built.clear()
        s.reload()
        advanceUntilIdle()
        assertThat(source.built).isEmpty()
        assertThat((s.state.value as ShelfState.Ready).books.all { it.ui.hasIndex }).isTrue()
    }

    @Test fun `a new edition rebuilds its index and the old edition's index is removed`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val old = File(dir, Shelf.indexFileName("wikipedia_cs_all_maxi-offlinka_2026-05.zim", 9L)).apply { writeText("old") }
        val s = shelf(indexDir = dir)
        s.onPicked(drives.add("content://ext/tree/1234-ABCD%3A", "OFFLINKA", "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 10L))
        advanceUntilIdle()
        assertThat(source.built).contains("wikipedia_cs_all")
        assertThat(old.exists()).isFalse()
        assertThat(File(dir, Shelf.indexFileName("wikipedia_cs_all_maxi-offlinka_2026-09.zim", 10L)).isFile).isTrue()
    }

    @Test fun `building an index can be cancelled and leaves the book usable`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val s = shelf(indexDir = dir)
        s.cancelIndexing()
        s.onPicked(offlinkaDrive())
        advanceUntilIdle()
        assertThat(source.built).isEmpty()
        assertThat((s.state.value as ShelfState.Ready).byId).containsKey("wikipedia_cs_all")
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".offidx") }).isEmpty()
    }

    @Test fun `the bundled guides work without a drive and stand in when the drive has none`() = runTest(dispatcher) {
        val guidesFile = File.createTempFile("guides", ".tmp").let { File(it.parentFile, "offlinka_cs_prirucky.zim").apply { writeText("zim") } }
        val s = shelf(builtin = { guidesFile.path })
        s.start()
        advanceUntilIdle()
        assertThat(s.state.value).isEqualTo(ShelfState.NoDrive)
        assertThat(s.servedBooks().keys).containsExactly("offlinka_cs_prirucky")

        val tree = drives.add("content://ext/tree/1234-ABCD%3A", "OFFLINKA", "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 1L)
        s.onPicked(tree)
        advanceUntilIdle()
        val r = s.state.value as ShelfState.Ready
        assertThat(r.tiles.map { it.id }).containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky").inOrder()
        assertThat(r.byId.getValue("offlinka_cs_prirucky").builtin).isTrue()
        // Unplugging never closes the bundled book.
        drives.drives.getValue(tree).plugged = false
        s.reportIoError(java.io.IOException("EIO"))
        runCurrent()
        assertThat((s.builtin!!.content as FakeBook).closed).isFalse()
        assertThat(s.servedBooks().keys).containsExactly("offlinka_cs_prirucky")
        // Stop the unplug watcher (it polls until the drive returns).
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelChildren()
    }
}
