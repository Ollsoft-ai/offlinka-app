package cz.offlinka.app.drive

import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.content.ContentException
import cz.offlinka.app.fakes.FakeBook
import cz.offlinka.app.fakes.FakeContentSource
import cz.offlinka.app.fakes.FakeDriveAccess
import cz.offlinka.app.fakes.fakeGuides
import cz.offlinka.app.fakes.fakeMaps
import cz.offlinka.app.fakes.fakeWiki
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.screens.ConnectProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/** Round-2 review fixes of the shelf: threading, races, leaks, index housekeeping, folder picks. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShelfRound3Test {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val drives = FakeDriveAccess()
    private val source = FakeContentSource(
        mapOf(
            "wikipedia_cs_all_maxi-offlinka_2026-09.zim" to { fakeWiki() },
            "wikipedia_en_top1m_nopic_2026-04.zim" to { fakeWiki("wikipedia_en_top1m") },
            "offlinka_cs_prirucky.zim" to { fakeGuides() },
            "maps_en_czech-republic_2026-06.zim" to { fakeMaps() },
        ),
    )
    private val memory = InMemoryDriveMemory()

    private fun shelf(indexDir: File? = null, io: kotlinx.coroutines.CoroutineDispatcher = dispatcher, minFree: Long = 0) = Shelf(
        drives, source, scope, io = io, pollMillis = 1_000, indexDir = indexDir, memory = memory,
        // In the background: the unplug watcher then waits instead of polling, so advanceUntilIdle ends.
        uiLanguage = { "cs" }, foreground = MutableStateFlow(false), minFreeForIndex = minFree,
    )

    private fun drive(uri: String = "content://ext/tree/1234-ABCD%3A", vararg files: Pair<String, Long> = arrayOf(
        "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 10L,
        "Knihovna/offlinka_cs_prirucky.zim" to 20L,
        "Knihovna/maps_en_czech-republic_2026-06.zim" to 30L,
    )) = drives.add(uri, "OFFLINKA", *files)

    private fun ready(s: Shelf) = s.state.value as ShelfState.Ready

    /** For shelves on a real IO dispatcher: run the shelf's scope until [cond] holds. */
    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (cond()) return
            Thread.sleep(10)
        }
        error("timed out")
    }

    private fun stopWatchers() = scope.coroutineContext[Job]!!.cancelChildren()

    // --- major #3: closeFile never on the caller's (main) thread

    @Test fun `unplugging publishes Unplugged at once even while closing a book hangs on a dead read`() {
        val s = shelf(io = Dispatchers.IO)
        val tree = drive()
        s.onPicked(tree)
        waitFor { (s.state.value as? ShelfState.Ready)?.opening == false }
        val books = ready(s).books.map { it.content as FakeBook }
        val gate = CountDownLatch(1)
        books.forEach { it.closeFileGate = gate }
        drives.drives.getValue(tree).plugged = false
        s.reportIoError(java.io.IOException("EIO"))
        // The shelf's own (main) dispatcher finishes although every closeFile() is still blocked.
        waitFor { s.state.value == ShelfState.Unplugged }
        assertThat(books.none { it.fileClosed }).isTrue()
        gate.countDown()
        waitFor { books.all { it.fileClosed } }
        assertThat(books.map { it.closeFileThread }).doesNotContain(Thread.currentThread().name)
        stopWatchers()
    }

    // --- minor #5: an old retry never brings back closed books

    @Test fun `a retry that finishes after the drive was pulled does not bring the old books back`() {
        val s = shelf(io = Dispatchers.IO)
        source.failing += "offlinka_cs_prirucky.zim"
        val tree = drive()
        s.onPicked(tree)
        waitFor { (s.state.value as? ShelfState.Ready)?.opening == false }
        source.failing.clear()
        val gate = CountDownLatch(1)
        source.gated["offlinka_cs_prirucky.zim"] = gate
        s.retry("offlinka_cs_prirucky")
        waitFor { ready(s).tiles.any { it.id == "offlinka_cs_prirucky" && it.state == BookState.OPENING } }
        // The file is open on the drive and the core is still reading it when the drive goes.
        waitFor { "offlinka_cs_prirucky.zim" in source.waitingAtGate }
        drives.drives.getValue(tree).plugged = false
        s.reportIoError(java.io.IOException("EIO"))
        waitFor { s.state.value == ShelfState.Unplugged }
        gate.countDown()
        // The late book is never published, and nobody holds it: it is destroyed.
        waitFor { source.opened.any { it.meta.id == "offlinka_cs_prirucky" && it.destroyed } }
        assertThat(s.state.value).isEqualTo(ShelfState.Unplugged)
        stopWatchers()
    }

    // --- minor #6: books opened but never published are freed

    @Test fun `a book that finishes opening after a reload is destroyed, not leaked`() {
        val s = shelf(io = Dispatchers.IO)
        val gate = CountDownLatch(1)
        source.gated["maps_en_czech-republic_2026-06.zim"] = gate
        s.onPicked(drive())
        waitFor { s.state.value is ShelfState.Ready }
        s.reloadAsync()
        waitFor { ready(s).generation >= 2 }
        gate.countDown()
        waitFor { source.opened.count { it.meta.id == "maps_en_czech-republic" } >= 1 && (s.state.value as? ShelfState.Ready)?.opening == false }
        val maps = source.opened.filter { it.meta.id == "maps_en_czech-republic" }
        val served = ready(s).byId.getValue("maps_en_czech-republic").content
        waitFor { maps.filter { it !== served }.all { it.destroyed } }
        assertThat((served as FakeBook).destroyed).isFalse()
    }

    // --- major #2: pages of a book that is still opening are 503, not 404

    @Test fun `a book that is still opening is pending, an unknown one is not`() {
        val s = shelf(io = Dispatchers.IO)
        val gate = CountDownLatch(1)
        source.gated["maps_en_czech-republic_2026-06.zim"] = gate
        s.onPicked(drive())
        waitFor { s.state.value is ShelfState.Ready }
        assertThat(s.isPending("maps_en_czech-republic")).isTrue()
        assertThat(s.isPending("wikipedia_cs_all")).isFalse()
        assertThat(s.isPending("wikivoyage_xx_all")).isFalse()
        gate.countDown()
        waitFor { (s.state.value as? ShelfState.Ready)?.opening == false }
        assertThat(s.isPending("maps_en_czech-republic")).isFalse()
    }

    // --- minor #10: a drive pulled during the first scan ends on the unplugged cover

    @Test fun `a drive pulled while the books open ends on the unplugged cover, not on failed tiles`() = runTest(dispatcher) {
        val s = shelf()
        val tree = drive()
        // Opening the map (second) pulls the drive: the guides (third) then fail to open.
        source.onOpen = { name -> if (name.startsWith("maps")) drives.drives.getValue(tree).plugged = false }
        s.onPicked(tree)
        advanceUntilIdle()
        assertThat(s.state.value).isEqualTo(ShelfState.Unplugged)
        stopWatchers()
    }

    // --- minor #15: re-picking a drive in use never drops its permission

    @Test fun `re-picking the drive in use while its listing fails keeps the permission`() = runTest(dispatcher) {
        val s = shelf()
        val tree = drive()
        s.onPicked(tree)
        advanceUntilIdle()
        drives.drives.getValue(tree).plugged = false
        s.onPicked(tree)
        advanceUntilIdle()
        assertThat(drives.released).doesNotContain(tree)
        assertThat(drives.granted).contains(tree)
        stopWatchers()
    }

    // --- minor #14: Knihovna and Telefon picked separately are one drive, named after the drive

    @Test fun `picking Knihovna and Telefon separately shows one drive with the drive's name`() = runTest(dispatcher) {
        val s = shelf()
        drives.volumeLabels["1234-ABCD"] = "OFFLINKA"
        val lib = drives.add("content://ext/tree/1234-ABCD%3AKnihovna", "Knihovna", "wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 10L)
        val phone = drives.add("content://ext/tree/1234-ABCD%3ATelefon", "Telefon", "offlinka_cs_prirucky.zim" to 20L)
        s.onPicked(lib)
        advanceUntilIdle()
        s.onPicked(phone)
        advanceUntilIdle()
        val r = ready(s)
        assertThat(r.drives.map { it.name to it.bookCount }).containsExactly("OFFLINKA" to 2)
        assertThat(r.byId.getValue("wikipedia_cs_all").ui.driveName).isEqualTo("OFFLINKA")
        assertThat(r.byId.getValue("wikipedia_cs_all").ui.folder).isEqualTo("Knihovna")
        assertThat(r.byId.getValue("offlinka_cs_prirucky").ui.folder).isEqualTo("Telefon")
        // Forgetting it releases both folders.
        s.forget(r.drives.single().id)
        advanceUntilIdle()
        assertThat(drives.released).containsAtLeast(lib, phone)
    }

    // --- minor #7: index folder housekeeping on every load

    @Test fun `each load keeps the indexes of remembered drives and removes the rest`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val s = shelf(indexDir = dir)
        val a = drive()
        val b = drives.add("content://ext/tree/9999-0000%3A", "DRUHA", "Knihovna/wikipedia_en_top1m_nopic_2026-04.zim" to 50L)
        s.onPicked(a)
        advanceUntilIdle()
        s.onPicked(b)
        advanceUntilIdle()
        val top1m = File(dir, Shelf.indexFileName("wikipedia_en_top1m_nopic_2026-04.zim", 50L)).apply { writeText("asked once") }
        val stale = File(dir, Shelf.indexFileName("wikipedia_cs_all_maxi-offlinka_2026-01.zim", 5L)).apply { writeText("old") }
        val part = File(dir, "x.offidx.part").apply { writeText("killed build") }
        val cs = File(dir, Shelf.indexFileName("wikipedia_cs_all_maxi-offlinka_2026-09.zim", 10L))
        assertThat(cs.isFile).isTrue()

        // The second drive goes away: its index stays (it may come back), the junk goes.
        drives.drives.getValue(b).plugged = false
        s.reload()
        advanceUntilIdle()
        assertThat(top1m.isFile).isTrue()
        assertThat(stale.exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(cs.isFile).isTrue()

        // Forgetting the second drive drops its index on the next load.
        s.forget(b.toString())
        advanceUntilIdle()
        assertThat(top1m.exists()).isFalse()
        assertThat(cs.isFile).isTrue()
    }

    @Test fun `a phone-built index goes once the drive ships its own`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val built = File(dir, Shelf.indexFileName("wikipedia_cs_all_maxi-offlinka_2026-09.zim", 10L)).apply { writeText("phone") }
        val s = shelf(indexDir = dir)
        s.onPicked(
            drive(
                files = arrayOf(
                    "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 10L,
                    "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.offidx" to 1_000L,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(built.exists()).isFalse()
        assertThat((ready(s).byId.getValue("wikipedia_cs_all").content as FakeBook).attachedIndex).startsWith("fd:")
    }

    // --- minor #8: one bad book does not stop indexing; low space and the queue are visible

    @Test fun `an Io error on one book with the drive still there skips that book only`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        source.buildErrors["wikipedia_cs_all"] = ContentException.Io("bad sector")
        val s = shelf(indexDir = dir)
        s.onPicked(drive())
        advanceUntilIdle()
        assertThat(source.built).containsExactly("offlinka_cs_prirucky", "maps_en_czech-republic").inOrder()
        assertThat(s.state.value).isInstanceOf(ShelfState.Ready::class.java)
        assertThat(ready(s).byId.getValue("wikipedia_cs_all").ui.hasIndex).isFalse()
    }

    @Test fun `not enough free space stops building and says so for the queued books`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val s = shelf(indexDir = dir, minFree = Long.MAX_VALUE)
        s.onPicked(drive())
        advanceUntilIdle()
        assertThat(source.built).isEmpty()
        assertThat(s.indexRun.value.lowSpace).isTrue()
        assertThat(s.indexRun.value.queued).containsAtLeast("wikipedia_cs_all", "offlinka_cs_prirucky")
    }

    // --- major #4: stopping a build forgets the request and the others go on

    @Test fun `stopping the English index forgets the request, a restart does not start it again`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val s = shelf(indexDir = dir)
        s.onPicked(
            drive(
                files = arrayOf(
                    "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 10L,
                    "Knihovna/wikipedia_en_top1m_nopic_2026-04.zim" to 50L,
                ),
            ),
        )
        advanceUntilIdle()
        val gate = CountDownLatch(1)
        source.buildGate = gate
        s.buildIndexNow("wikipedia_en_top1m")
        assertThat(memory.indexRequests()).containsExactly("wikipedia_en_top1m")
        assertThat(s.indexRun.value.queued).contains("wikipedia_en_top1m")
        s.stopIndex("wikipedia_en_top1m")
        gate.countDown()
        advanceUntilIdle()
        assertThat(memory.indexRequests()).isEmpty()
        assertThat(source.built).doesNotContain("wikipedia_en_top1m")
        assertThat(s.indexing.value).isNull()
        s.reload()
        advanceUntilIdle()
        assertThat(source.built).doesNotContain("wikipedia_en_top1m")
        val en = ready(s).byId.getValue("wikipedia_en_top1m")
        assertThat(s.canBuildOnDemand(en)).isTrue()
    }

    @Test fun `the UI-language Wikipedia announces its finished index once`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("idx").toFile()
        val s = shelf(indexDir = dir)
        val got = mutableListOf<String>()
        val c = backgroundScope.launchCollect(s) { got += it }
        s.onPicked(drive())
        advanceUntilIdle()
        runCurrent()
        assertThat(got).containsAtLeast("offlinka_cs_prirucky", "wikipedia_cs_all")
        c.cancel()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(s: Shelf, onEach: (String) -> Unit) =
        launch(dispatcher) { s.indexed.collect { onEach(it) } }

    @Test fun `the problem screen for a folder without books still says so`() = runTest(dispatcher) {
        val s = shelf()
        val dcim = drives.add("content://ext/tree/1234-ABCD%3ADCIM", "DCIM", "foto.jpg" to 1L)
        s.onPicked(dcim)
        advanceUntilIdle()
        assertThat(s.state.value).isEqualTo(ShelfState.Problem(ConnectProblem.WRONG_FOLDER))
        assertThat(drives.released).containsExactly(dcim)
    }
}
